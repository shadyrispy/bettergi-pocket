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
                scriptRunner.startArtifactScan()
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
                    engine.start()
                    InputAccessibilityService.ensureEnabled(applicationContext)
                    // 投影刚就绪：若扫描开关已开，直接启动（悬浮窗先开扫描再授权的场景）
                    if (settingsRepository.get().scanEnabled) {
                        scriptRunner.startArtifactScan()
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
                    "exported" -> "完成：${vars["count"]} 件已导出"
                    "total_mismatch" -> "总数不符：读到 ${vars["total"]} 实扫 ${vars["scanned"]}"
                    else -> "扫描中…"
                }
                overlayController.updateScanProgress(text)
                // :a11y 探针挂载时同步进度（跨进程状态桥，P2 机制验证）
                InputAccessibilityService.pushScanProgress(text)
                updateForegroundNotification(text, goodFile = vars["file"] as? String)
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

    /** 更新前台服务通知内容（同 ID notify，保留 foreground 语义；通知进度 = P2 零授权通道）。 */
    private fun updateForegroundNotification(progressText: String, goodFile: String? = null) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(
                NOTIFICATION_ID,
                buildNotification(
                    sharing = captureController.isRunning(),
                    progressText = progressText,
                    scanEnabled = settingsRepository.get().scanEnabled,
                    goodFile = goodFile,
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

    private fun requestCapturePermission() {
        if (requestingCapturePermission) return
        requestingCapturePermission = true
        val intent = Intent(this, CapturePermissionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
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
        scanEnabled: Boolean = false,
        goodFile: String? = null,
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
        val scanAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_play),
            if (scanEnabled) "■ 停止扫描" else "▶ 开始扫描",
            PendingIntent.getService(
                this,
                1,
                Intent(this, TriggerForegroundService::class.java).apply {
                    action = if (scanEnabled) ACTION_SCAN_STOP else ACTION_SCAN_START
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setContentTitle("更好的原神")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(scanAction)
            .setOngoing(true)
        goodFile?.let { name ->
            val file = java.io.File(filesDir, name)
            if (file.exists()) {
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val shareIntent = Intent.createChooser(share, "分享 GOOD 导出")
                builder.addAction(
                    Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_share),
                        "分享 GOOD",
                        PendingIntent.getActivity(this, 2, shareIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
                    ).build(),
                )
            }
        }
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
        const val ACTION_START = "com.bettergi.pocket.action.START"
        const val ACTION_STOP = "com.bettergi.pocket.action.STOP"
        const val ACTION_CAPTURE_RESULT = "com.bettergi.pocket.action.CAPTURE_RESULT"
        const val ACTION_CAPTURE_DENIED = "com.bettergi.pocket.action.CAPTURE_DENIED"
        const val ACTION_SCAN_START = "com.bettergi.pocket.action.SCAN_START"
        const val ACTION_SCAN_STOP = "com.bettergi.pocket.action.SCAN_STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val NOTIFICATION_CHANNEL_ID = "bettergi_pocket_trigger"
        private const val NOTIFICATION_ID = 1001
    }
}
