package com.bettergi.pocket.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.bettergi.pocket.MainActivity
import com.bettergi.pocket.R
import com.bettergi.pocket.capture.CapturePermissionActivity
import com.bettergi.pocket.capture.ProjectionFrameSource
import com.bettergi.pocket.capture.ScreenCaptureController
import com.bettergi.pocket.feature.autopick.AutoPickFeature
import com.bettergi.pocket.feature.autoskip.AutoSkipFeature
import com.bettergi.pocket.genshin.GenshinLaunchMonitor
import com.bettergi.pocket.genshin.GenshinLauncher
import com.bettergi.pocket.input.AccessibilityAutomationController
import com.bettergi.pocket.input.InputAccessibilityService
import com.bettergi.pocket.overlay.OverlayWindowController
import com.bettergi.pocket.recognition.RecognitionAssets
import com.bettergi.pocket.scan.ScanListener
import com.bettergi.pocket.scan.ScriptRunner
import com.bettergi.pocket.settings.TriggerSettings
import com.bettergi.pocket.settings.TriggerSettingsRepository
import com.bettergi.pocket.trigger.TriggerEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TriggerForegroundService : Service() {
    private lateinit var settingsRepository: TriggerSettingsRepository
    private lateinit var captureController: ScreenCaptureController
    private lateinit var overlayController: OverlayWindowController
    private lateinit var genshinLauncher: GenshinLauncher
    private lateinit var genshinLaunchMonitor: GenshinLaunchMonitor
    private lateinit var engine: TriggerEngine
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var frameSource: ProjectionFrameSource
    private lateinit var scriptRunner: ScriptRunner

    @Volatile
    private var requestingCapturePermission = false

    @Volatile
    private var shutDown = false

    private val settingsListener: (TriggerSettings) -> Unit = { settings ->
        if (settings.screenShareEnabled) {
            if (!captureController.isRunning()) {
                requestCapturePermission()
            }
            engine.start()
        } else {
            stopScreenShare()
        }
        // 自动扫描：投影就绪即开跑；关闭即停（P1-c 悬浮窗入口）
        if (settings.scanEnabled && settings.screenShareEnabled && captureController.isRunning()) {
            if (!scriptRunner.isRunning()) {
                overlayController.collapse() // 自动化执行前收面板（与滑动测试缩球对称）
                scriptRunner.startScan(settings.scanFlow, maxPagesOrDefault(settings.scanMaxPages))
            }
        } else if (!settings.scanEnabled && scriptRunner.isRunning()) {
            scriptRunner.stop()
            overlayController.updateScanProgress("已手动停止")
        }
    }

    override fun onCreate() {
        super.onCreate()
        settingsRepository = TriggerSettingsRepository(applicationContext)
        captureController = ScreenCaptureController(applicationContext) {
            if (settingsRepository.get().screenShareEnabled) {
                settingsRepository.setScreenShareEnabled(false)
                Toast.makeText(
                    applicationContext,
                    "屏幕共享已停止，可能被其他录制应用占用",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        genshinLauncher = GenshinLauncher(applicationContext)
        overlayController = OverlayWindowController(
            applicationContext,
            settingsRepository,
            genshinLauncher = genshinLauncher,
            onExit = {
                val stop = Intent(this, TriggerForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
                startService(stop)
            },
            onShareGoodRequested = { shareGood() },
        )
        genshinLaunchMonitor = GenshinLaunchMonitor(
            settingsRepository = settingsRepository,
            launcher = genshinLauncher,
            isGenshinInForeground = { InputAccessibilityService.isGenshinInForeground() },
            canAutoLaunch = { !requestingCapturePermission && !shutDown },
        )
        val recognitionAssets = RecognitionAssets(applicationContext.assets)
        frameSource = ProjectionFrameSource(captureController)
        engine = TriggerEngine(
            settingsRepository = settingsRepository,
            frameSource = frameSource,
            features = listOf(
                AutoPickFeature(),
                AutoSkipFeature(recognitionAssets, overlayController),
            ),
            actionController = AccessibilityAutomationController(overlayController) {
                // 动作受理后标记帧源时间戳：扫描链路 grabFresh 只取动作后新帧
                frameSource.markActionAt(SystemClock.elapsedRealtime())
            },
        )
        scriptRunner = ScriptRunner(
            context = applicationContext,
            frameSource = frameSource,
            captureController = captureController,
            overlayController = overlayController,
            listener = scanListener,
        )
        settingsRepository.addListener(settingsListener)
        genshinLaunchMonitor.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startInForeground(sharing = captureController.isRunning())
                overlayController.show()
                InputAccessibilityService.promptIfDisconnected(applicationContext)
            }
            ACTION_STOP -> {
                shutdown()
                stopSelf()
            }
            ACTION_CAPTURE_RESULT -> {
                requestingCapturePermission = false
                if (!settingsRepository.get().screenShareEnabled) {
                    captureController.stop()
                    startInForeground(sharing = false)
                    return START_STICKY
                }
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData =
                    if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_RESULT_DATA)
                    }
                if (resultData != null) {
                    startInForeground(sharing = true)
                    captureController.start(resultCode, resultData)
                    Log.i(TAG, "capture started; running=${captureController.isRunning()}")
                    engine.start()
                    InputAccessibilityService.ensureEnabled(applicationContext)
                    // 投影刚就绪：若扫描开关已开，直接启动（悬浮窗先开扫描再授权的场景）
                    if (settingsRepository.get().scanEnabled) {
                        val s = settingsRepository.get()
                        scriptRunner.startScan(s.scanFlow, maxPagesOrDefault(s.scanMaxPages))
                    }
                } else {
                    settingsRepository.setScreenShareEnabled(false)
                }
            }
            ACTION_CAPTURE_DENIED -> {
                requestingCapturePermission = false
                settingsRepository.setScreenShareEnabled(false)
            }
            ACTION_SCAN_START -> {
                // 零授权扫描入口：通知栏直接开启（悬浮窗面板开关之外的通道）
                settingsRepository.setScanEnabled(true)
            }
            ACTION_SCAN_STOP -> {
                settingsRepository.setScanEnabled(false)
            }
            ACTION_DEBUG_SET_SCREEN_SHARE -> {
                // adb 调试入口：必须用本服务的 repo 实例（否则 settingsListener 不触发）
                settingsRepository.setScreenShareEnabled(
                    intent.getBooleanExtra(EXTRA_ENABLED, false),
                )
            }
            ACTION_DEBUG_SET_SCAN -> {
                settingsRepository.setScanEnabled(intent.getBooleanExtra(EXTRA_ENABLED, false))
            }
            ACTION_DEBUG_STATUS -> {
                val s = settingsRepository.get()
                Log.i(
                    TAG,
                    "status screenShare=${s.screenShareEnabled} scan=${s.scanEnabled} " +
                        "autoSkip=${s.autoSkipEnabled} autoPick=${s.autoPickEnabled} " +
                        "captureRunning=${captureController.isRunning()}",
                )
            }
            ACTION_DEBUG_SET_PROBE -> {
                // 切换 :a11y Overlay 探针（桥模式：服务侧 toggle 调用即 :a11y 进程内执行）
                InputAccessibilityService.toggleProbe(this)
            }
            ACTION_DEBUG_SET_VERBOSE -> {
                // §13：识别日志 D 级开关（逐格/逐次明细，默认关闭以免一页 21 行刷屏）
                val on = intent.getBooleanExtra(EXTRA_ENABLED, false)
                com.bettergi.pocket.log.RecognitionLog.verbose = on
                Log.i(TAG, "recognition log verbose=$on")
            }
            ACTION_DEBUG_SWIPE_TEST -> {
                val startY = intent.getIntExtra(EXTRA_START_Y, 1150)
                val dist = intent.getIntExtra(EXTRA_DIST, 876)
                val measure = intent.getBooleanExtra(EXTRA_MEASURE, false)
                if (startY != null && dist != 0) {
                    val fromX = 1614
                    val toY = startY - dist
                    // 派发滑动到主线程（InputAccessibilityService.swipe 通过桥即可：主进程调起即用 :a11y 实例）
                    // dist>0 上滑翻页；dist<0 下滑回顶（网格在顶端钳制，用于逐点标定前复位到首页）
                    mainHandler.post {
                        val ok = InputAccessibilityService.swipe(fromX, startY, fromX, toY, durationMs = 400, segments = 3)
                        Log.i(TAG, "debug swipe ($fromX,$startY)->($fromX,$toY) dist=$dist ok=$ok")
                        // §12.2 标定：滑动结束 + 动画 settle 后抓帧测上沿相位误差，供距离自适应算法对标真值
                        if (measure && ok) {
                            scriptRunner.scope.launch {
                                delay(700)
                                val err = scriptRunner.measureTopEdge("artifact_backpack")
                                Log.i(
                                    TAG,
                                    "align[artifact_backpack]: swipeMeasured dist=$dist startY=$startY err=${err ?: "null"}",
                                )
                            }
                        }
                    }
                } else if (startY != null && dist == 0 && measure) {
                    // 仅测量模式（dist=0）：不滑动，直接抓当前帧测上沿误差（逐点标定前取基准）
                    scriptRunner.scope.launch {
                        delay(200)
                        val err = scriptRunner.measureTopEdge("artifact_backpack")
                        Log.i(TAG, "align[artifact_backpack]: measureOnly err=${err ?: "null"}")
                    }
                } else {
                    Log.w(TAG, "debug swipe: startY invalid or dist=0 without measure")
                }
            }
            ACTION_DEBUG_SCAN_FLOW -> {
                val flow = intent.getStringExtra(EXTRA_FLOW) ?: "artifact_scan"
                val maxPages = intent.getIntExtra(EXTRA_MAX_PAGES, Int.MAX_VALUE)
                val geoAdvance = intent.getBooleanExtra(EXTRA_GEO_ADVANCE, true)
                val adaptive = intent.getBooleanExtra(EXTRA_ADAPTIVE_DIST, true)
                if (captureController.isRunning()) {
                    scriptRunner.startScan(
                        flow, maxPages,
                        useGeometryAdvance = geoAdvance,
                        useAdaptiveDistance = adaptive,
                    )
                } else {
                    Log.w(TAG, "scan flow request ignored: projection not running")
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        settingsRepository.removeListener(settingsListener)
        shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** P1-c 扫描监听：进度更新悬浮窗副文本 + 前台通知（P2 后的零授权进度通道）+ 结束后开关自动复位。 */
    private val scanListener = object : ScanListener {
        override fun onProgress(stage: String, vars: Map<String, Any?>) {
            mainHandler.post {
                val text = when (stage) {
                    "artifact" -> "扫描中：第 ${vars["idx"]} 件 ${vars["piece"] ?: ""}"
                    "character" -> "扫描中：第 ${vars["idx"]} 位 ${vars["name"] ?: ""}"
                    "weapon" -> "扫描中：第 ${vars["idx"]} 把 ${vars["piece"] ?: ""}"
                    "exported" -> "完成：${vars["count"]} 件已导出"
                    "total_mismatch" -> "总数不符：读到 ${vars["total"]} 实扫 ${vars["scanned"]}"
                    else -> "扫描中…"
                }
                overlayController.updateScanProgress(text)
                // :a11y 探针挂载时同步进度（跨进程状态桥，P2 机制验证）
                InputAccessibilityService.pushScanProgress(text)
                updateForegroundNotification(text)
                (vars["file"] as? String)?.let { lastGoodFile = it }
                Log.i("BetterGI.Scan", "progress[$stage]: $vars")
            }
        }

        override fun onFinished(reason: String) {
            mainHandler.post {
                val doneText = if (reason.startsWith("error")) "失败：$reason" else "已结束（$reason）"
                overlayController.updateScanProgress(doneText)
                updateForegroundNotification("扫描$doneText")
                if (settingsRepository.get().scanEnabled) {
                    settingsRepository.setScanEnabled(false)
                }
            }
            Log.i("BetterGI.Scan", "scan finished: $reason")
        }
    }

    /** 最近一次 GOOD 导出文件名（悬浮窗「分享 GOOD」用）。 */
    @Volatile
    private var lastGoodFile: String? = null

    /**
     * 悬浮窗「分享 GOOD」：拉 app 前台（MainActivity 中转）再起系统分享 chooser——
     * service 后台直接 startActivity(chooser) 依赖 SAW 豁免，Android 14+ ROM 不可靠。
     */
    private fun shareGood() {
        val file = lastGoodFile
        if (file == null) {
            Toast.makeText(this, "还没有 GOOD 导出，先完成一次扫描", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            .putExtra(MainActivity.EXTRA_AUTO_SHARE, file)
        startActivity(intent)
    }

    /** 更新前台服务通知内容（同 ID notify，保留 foreground 语义）。 */
    private fun updateForegroundNotification(progressText: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(
                NOTIFICATION_ID,
                buildNotification(
                    sharing = captureController.isRunning(),
                    progressText = progressText,
                ),
            )
        } catch (e: Exception) {
            Log.w("BetterGI.Service", "update scan notification failed", e)
        }
    }

    private fun shutdown() {
        if (shutDown) return
        shutDown = true
        scriptRunner.stop()
        InputAccessibilityService.cancelRecoverCheck()
        genshinLaunchMonitor.stop()
        engine.release()
        captureController.stop()
        overlayController.hide()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun stopScreenShare() {
        requestingCapturePermission = false
        engine.stop()
        captureController.stop()
        if (!shutDown) {
            startInForeground(sharing = false)
        }
    }

    /** 0 = 不限（悬浮窗约定）→ ScanEngine Int.MAX_VALUE。 */
    private fun maxPagesOrDefault(raw: Int): Int = if (raw <= 0) Int.MAX_VALUE else raw

    private fun requestCapturePermission() {
        if (requestingCapturePermission) return
        requestingCapturePermission = true
        try {
            // 悬浮窗点击是用户交互 + SAW 豁免（与原版同款语义）：直接启动授权 activity
            val intent = Intent(this, CapturePermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            // 兜底：部分 ROM 收紧 SAW 后台启动 → 拉 app 前台，MainActivity 前台内再发起（无通知依赖）
            requestingCapturePermission = false
            Log.w(TAG, "direct capture launch failed, bringing app to front", e)
            val intent = Intent(this, MainActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                .putExtra(MainActivity.EXTRA_AUTO_REQUEST_CAPTURE, true)
            startActivity(intent)
        }
    }

    private fun startInForeground(sharing: Boolean) {
        createNotificationChannelIfNeeded()
        val notification = buildNotification(sharing)
        if (Build.VERSION.SDK_INT >= 29) {
            val serviceType =
                if (sharing) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        sharing: Boolean,
        progressText: String? = null,
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = progressText
            ?: if (sharing) "正在共享屏幕" else "点悬浮球可开启共享屏幕"
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        // 仅 FGS 常驻通知：开始/停止扫描与分享 GOOD 全部走悬浮窗面板（零通知依赖，fix53）
        builder
            .setContentTitle("更好的原神")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
        return builder.build()
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        val existing = manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "BetterGIPocket",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val TAG = "BetterGI.Service"
        const val ACTION_START = "com.bettergi.pocket.action.START"
        const val ACTION_STOP = "com.bettergi.pocket.action.STOP"
        const val ACTION_CAPTURE_RESULT = "com.bettergi.pocket.action.CAPTURE_RESULT"
        const val ACTION_CAPTURE_DENIED = "com.bettergi.pocket.action.CAPTURE_DENIED"
        const val ACTION_SCAN_START = "com.bettergi.pocket.action.SCAN_START"
        const val ACTION_SCAN_STOP = "com.bettergi.pocket.action.SCAN_STOP"
        const val ACTION_DEBUG_SET_SCREEN_SHARE = "com.bettergi.pocket.action.DEBUG_SET_SCREEN_SHARE"
        const val ACTION_DEBUG_SET_SCAN = "com.bettergi.pocket.action.DEBUG_SET_SCAN"
        const val ACTION_DEBUG_STATUS = "com.bettergi.pocket.action.DEBUG_STATUS"
        const val ACTION_DEBUG_SET_PROBE = "com.bettergi.pocket.action.DEBUG_SET_PROBE"
        const val ACTION_DEBUG_SET_VERBOSE = "com.bettergi.pocket.action.DEBUG_SET_VERBOSE"
        const val ACTION_DEBUG_SWIPE_TEST = "com.bettergi.pocket.action.DEBUG_SWIPE_TEST"
        const val ACTION_DEBUG_SCAN_FLOW = "com.bettergi.pocket.action.DEBUG_SCAN_FLOW"

        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_START_Y = "startY"
        const val EXTRA_DIST = "dist"
        const val EXTRA_MEASURE = "measure"
        const val EXTRA_FLOW = "flow"
        const val EXTRA_MAX_PAGES = "maxPages"
        /** §12.1 A/B：true=几何推导翻页落点，false=profiles 写死坐标。 */
        const val EXTRA_GEO_ADVANCE = "geoAdvance"
        /** §12.2 A/B：true=每页按相位误差自适应翻页距离。 */
        const val EXTRA_ADAPTIVE_DIST = "adaptiveDist"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val NOTIFICATION_CHANNEL_ID = "bettergi_pocket_trigger"
        private const val NOTIFICATION_ID = 1001
    }
}
