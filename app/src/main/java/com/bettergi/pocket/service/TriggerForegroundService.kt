package com.bettergi.pocket.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import com.bettergi.pocket.MainActivity
import com.bettergi.pocket.R
import com.bettergi.pocket.capture.CapturePermissionActivity
import com.bettergi.pocket.capture.CaptureResultHolder
import com.bettergi.pocket.capture.ProjectionFrameSource
import com.bettergi.pocket.capture.ScreenCaptureController
import com.bettergi.pocket.feature.autopick.AutoPickFeature
import com.bettergi.pocket.feature.autoskip.AutoSkipFeature
import com.bettergi.pocket.genshin.GenshinLaunchMonitor
import com.bettergi.pocket.notice.NoticeCenter
import com.bettergi.pocket.scan.GoodRepository
import com.bettergi.pocket.genshin.GenshinLauncher
import com.bettergi.pocket.input.AccessibilityAutomationController
import com.bettergi.pocket.input.InputAccessibilityService
import com.bettergi.pocket.input.SwipeMethod
import com.bettergi.pocket.overlay.OverlayBridge
import com.bettergi.pocket.recognition.RecognitionAssets
import com.bettergi.pocket.recognition.ocr.OcrFactory
import com.bettergi.pocket.scan.ScanListener
import com.bettergi.pocket.scan.ScriptRunner
import com.bettergi.pocket.settings.TriggerSettings
import com.bettergi.pocket.settings.TriggerSettingsRepository
import com.bettergi.pocket.trigger.TriggerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TriggerForegroundService : Service() {
    private lateinit var settingsRepository: TriggerSettingsRepository
    private lateinit var captureController: ScreenCaptureController
    /**
     * 悬浮窗门面（2026-09-18 宿主迁移）。
     * 悬浮窗本体已搬到无障碍进程（`TYPE_ACCESSIBILITY_OVERLAY` 零权限），本服务只持有转发门面；
     * 方法签名与原控制器一致 ⇒ 扫描/自动对话侧零改动。
     */
    private lateinit var overlayController: OverlayBridge
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

    /** 无障碍状态变化 ⇒ 重连后把悬浮窗请求回来（见 onCreate 注释）。 */
    private val a11yStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (shutDown) return
            mainHandler.post { overlayController.show() }
        }
    }

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
                startFlowIfReady(settings.scanFlow, maxPagesOrDefault(settings.scanMaxPages))
            }
        } else if (!settings.scanEnabled && scriptRunner.isRunning()) {
            scriptRunner.stop()
            overlayController.updateScanProgress("已手动停止")
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 进程内单例：设置桥 Provider 必须拿到同一实例，否则两个缓存互相打架
        settingsRepository = TriggerSettingsRepository.app(applicationContext)
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
        // 「退出 / 开始导出」由无障碍进程经设置桥回调（METHOD_STOP / METHOD_SHARE_GOOD），
        // 因此这里不再需要 onExit / onShareGoodRequested 回调。
        overlayController = OverlayBridge(applicationContext)
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
        // ⚠️ 2026-09-18 上机实测：无障碍重连后**悬浮窗不会自己回来**（旧窗口随服务销毁，
        //    而没有新的 overlay_show 请求）⇒ 表现为「助手在跑但球没了」，只能重启助手。
        //    这里订阅无障碍状态变化，重连后补一次显示请求（幂等：已显示时 show() 直接返回）。
        ContextCompat.registerReceiver(
            applicationContext,
            a11yStateReceiver,
            IntentFilter(InputAccessibilityService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
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
            ACTION_SHARE_GOOD -> {
                // 悬浮窗「开始导出」（无障碍进程发起）
                shareGood()
            }
            ACTION_CAPTURE_RESULT -> {
                requestingCapturePermission = false
                if (!settingsRepository.get().screenShareEnabled) {
                    captureController.stop()
                    CaptureResultHolder.take() // 丢弃未消费的结果，避免下次误用
                    startInForeground(sharing = false)
                    return START_STICKY
                }
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                // 从进程内单例取授权结果 Intent（避免嵌套 parcel 丢失 IBinder extra）
                val resultData = CaptureResultHolder.take()
                if (resultData != null) {
                    startInForeground(sharing = true)
                    captureController.start(resultCode, resultData)
                    Log.i(TAG, "capture started; running=${captureController.isRunning()}")
                    engine.start()
                    InputAccessibilityService.ensureEnabled(applicationContext)
                    // 投影刚就绪：若扫描开关已开，直接启动（悬浮窗先开扫描再授权的场景）
                    if (settingsRepository.get().scanEnabled) {
                        val s = settingsRepository.get()
                        startFlowIfReady(s.scanFlow, maxPagesOrDefault(s.scanMaxPages))
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
                // 关设置：UI/悬浮窗入口靠 applySettings 的 `!scanEnabled && isRunning` 分支取消本轮
                settingsRepository.setScanEnabled(false)
                // ⚠️ 兜底直停：adb 调试路径（DEBUG_SCAN_FLOW）**不经 scanEnabled 启动**，
                //   此时 setScanEnabled(false) 是 **no-op**（值未变 ⇒ settingsListener 不触发）
                //   ⇒ 上面那句停不掉在跑的扫描，以前只能 `am force-stop`（连带丢掉 MediaProjection 授权）。
                if (scriptRunner.isRunning()) {
                    scriptRunner.stop()
                    Log.i(TAG, "scan stop requested (direct abort)")
                }
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
            ACTION_DEBUG_SET_GOOD -> {
                // 调试链：把设备上的 GOOD/计划文件复制成"当前输入"（用户侧入口在管理器，由 SAF 提供）
                val src = intent.getStringExtra(EXTRA_GOOD_SRC) ?: ""
                val (ok, msg) = GoodRepository.importFrom(applicationContext, java.io.File(src))
                Log.i(TAG, "debug set good: ok=$ok msg=$msg src=$src")
                NoticeCenter.post(
                    if (ok) NoticeCenter.Level.INFO else NoticeCenter.Level.ERROR,
                    "GOOD 输入：$msg",
                )
            }
            ACTION_DEBUG_DUMP_GOOD -> {
                val f = lastGoodFile
                if (f == null) {
                    Log.w(TAG, "dump good: 无 lastGoodFile（本轮未导出？）")
                } else {
                    runCatching {
                        val dstDir = getExternalFilesDir(null) ?: filesDir
                        val dst = java.io.File(dstDir, "sweep_last_good.json")
                        java.io.File(filesDir, f).copyTo(dst, overwrite = true)
                        Log.i(TAG, "dump good: $f -> ${dst.absolutePath} (${dst.length()}B)")
                    }.onFailure { Log.w(TAG, "dump good failed", it) }
                }
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
                // ★ 2026-09-18 扩展：`--ez bal true` ⇒ 走 BAL 探针（挂**可点击**的 a11y overlay +
                //   立即/点击两条 startActivity 路径），用于核实「长按浮窗 → 启动 MainActivity」是否被拦。
                //   `--ez mount false` ⇒ 对照组（不挂 overlay，只为对比 BAL 判定差异）。
                val bal = intent.getBooleanExtra("bal", false)
                if (bal) {
                    val mount = intent.getBooleanExtra("mount", true)
                    Log.i(TAG, "debug: BAL probe 触发 mount=$mount")
                    InputAccessibilityService.probeBal(this, mount)
                } else {
                    InputAccessibilityService.toggleProbe(this)
                }
            }
            ACTION_DEBUG_SET_VERBOSE -> {
                // §13：识别日志 D 级开关（逐格/逐次明细，默认关闭以免一页 21 行刷屏）
                val on = intent.getBooleanExtra(EXTRA_ENABLED, false)
                com.bettergi.pocket.log.RecognitionLog.verbose = on
                Log.i(TAG, "recognition log verbose=$on")
            }
            ACTION_DEBUG_PERF_PROBE -> {
                // ★ 只读性能探针（2026-09-12）：帧路径拆段 + ROI 口径 + rec 宽度基准。
                //   目的：为「就绪信号廉价化 / ROI 级 BGR 转换」提供分量实测值
                //   （见 dsl/verify/_audit/IMAGE-PATH-COST.md §8）。**不改任何行为**。
                val runs = intent.getIntExtra(EXTRA_RUNS, 30)
                val roisB64 = intent.getStringExtra(EXTRA_ROIS_B64).orEmpty()
                val roisCsv = intent.getStringExtra(EXTRA_ROIS).orEmpty()
                val widthsCsv = intent.getStringExtra(EXTRA_WIDTHS).orEmpty()
                val stabilityB64 = intent.getStringExtra(EXTRA_STABILITY_B64).orEmpty()
                if (scriptRunner.isRunning()) {
                    Log.w(PERF_TAG, "perf probe: 扫描进行中，测量结果会被干扰（建议空闲时跑）")
                }
                val rois = if (roisB64.isNotEmpty()) parseRoiCsv(decodeB64(roisB64)) else parseRoiCsv(roisCsv)
                // ★ ROI 稳定性探针（只读）：`stabilityB64` = base64("name:x,y,w,h;name:…")
                val stability = if (stabilityB64.isNotEmpty()) parseNamedRois(decodeB64(stabilityB64)) else emptyList()
                val stabilityFrames = intent.getIntExtra(EXTRA_FRAMES, 60)
                // ★ OCR 并行度探针（只读）：`ocrpar="1:1,1:2,2:1,3:1"`（k:intra 列表）
                val ocrPar = intent.getStringExtra(EXTRA_OCR_PAR).orEmpty()
                // ★ 帧率探针（只读）：`--ei frate 1200`
                val frateMs = intent.getIntExtra(EXTRA_FRAME_RATE, 0)
                val widths = if (widthsCsv.isBlank()) {
                    intArrayOf(145, 220, 405, 684)
                } else {
                    widthsCsv.split(',').mapNotNull { it.trim().toIntOrNull() }.toIntArray()
                }
                val capturing = captureController.isRunning()
                scriptRunner.scope.launch {
                    if (!capturing) {
                        Log.i(PERF_TAG, "perf probe frame: 投影未运行（frame 段跳过）")
                    } else {
                        Log.i(PERF_TAG, "perf probe frame: ${captureController.benchFramePath(runs, rois)}")
                        // ROI 稳定性探针（只读）：为「自适应就绪锚」挑选真正静止的 ROI
                        if (stability.isNotEmpty()) {
                            Log.i(PERF_TAG, captureController.probeRoiStability(stability, stabilityFrames))
                        }
                    }
                    Log.i(PERF_TAG, "perf probe rec:\n${OcrFactory.recProbe(widths, runs.coerceAtLeast(5))}")
                    if (frateMs > 0) {
                        Log.i(PERF_TAG, "perf probe " + captureController.probeFrameRate(frateMs.toLong()))
                    }
                    if (ocrPar.isNotEmpty()) {
                        Log.i(PERF_TAG, "perf probe ocrPar:\n" + OcrFactory.ocrParallelProbe(ocrPar, widths, runs.coerceAtLeast(3)))
                    }
                    Log.i(PERF_TAG, "perf probe done")
                }
            }
            ACTION_DEBUG_SWIPE_TEST -> {
                val startY = intent.getIntExtra(EXTRA_START_Y, 1150)
                val dist = intent.getIntExtra(EXTRA_DIST, 876)
                val measure = intent.getBooleanExtra(EXTRA_MEASURE, false)
                // 可测任意网格（weapon_backpack/char_popup/...）；缺省 artifact_backpack
                val gridKey = intent.getStringExtra(EXTRA_GRID) ?: "artifact_backpack"
                // ★ 2026-09-13 补参数（默认值 = 原硬编码值，行为不变）：
                //   fromX：2244 上 1614 落在**详情面板**（面板 x≥1450）⇒ 测网格必须显式传（网格 x≈275..1435）
                //   durMs/segments：1 = **单段匀速**（一条直线段、指定时长，无 90/10 分段、无回退）
                val fromX = intent.getIntExtra(EXTRA_FROM_X, 1614)
                val durMs = intent.getLongExtra(EXTRA_DUR_MS, 400L)
                val segs = intent.getIntExtra(EXTRA_SEGS, 3)
                // method：0=路标链(默认,多次dispatch) 1=三段式(单次dispatch的continueStroke链)
                val mtd = if (intent.getIntExtra(EXTRA_METHOD, 0) == 1)
                    SwipeMethod.THREE_SEGMENT
                else SwipeMethod.WAYPOINT_CHAIN
                if (startY != null && dist != 0) {
                    val toY = startY - dist
                    // 派发滑动到主线程（InputAccessibilityService.swipe 通过桥即可：主进程调起即用 :a11y 实例）
                    // dist>0 上滑翻页；dist<0 下滑回顶（网格在顶端钳制，用于逐点标定前复位到首页）
                    mainHandler.post {
                        val ok = InputAccessibilityService.swipe(fromX, startY, fromX, toY, durationMs = durMs, segments = segs, method = mtd)
                        Log.i(TAG, "debug swipe ($fromX,$startY)->($fromX,$toY) dist=$dist ok=$ok")
                        // §12.2 标定：滑动结束 + 动画 settle 后抓帧测上沿相位误差，供距离自适应算法对标真值
                        if (measure && ok) {
                            scriptRunner.scope.launch {
                                delay(700)
                                val err = scriptRunner.measureTopEdge(gridKey)
                                Log.i(
                                    TAG,
                                    "align[$gridKey]: swipeMeasured dist=$dist startY=$startY err=${err ?: "null"}",
                                )
                            }
                        }
                    }
                } else if (startY != null && dist == 0 && measure) {
                    // 仅测量模式（dist=0）：不滑动，直接抓当前帧测上沿误差（逐点标定前取基准）
                    scriptRunner.scope.launch {
                        delay(200)
                        val err = scriptRunner.measureTopEdge(gridKey)
                        Log.i(TAG, "align[$gridKey]: measureOnly err=${err ?: "null"}")
                    }
                } else {
                    Log.w(TAG, "debug swipe: startY invalid or dist=0 without measure")
                }
            }
            ACTION_DEBUG_SCAN_FLOW -> {
                val flow = intent.getStringExtra(EXTRA_FLOW) ?: "artifact_scan"
                val maxPages = intent.getIntExtra(EXTRA_MAX_PAGES, Int.MAX_VALUE)
                // ⚠️ 2026-09-17 默认改为 **false**：几何起点（x=638，两卡缝隙）实测让 BS 的滚动**被截断**
            // （落地条带 L 仅 501~516px = **1.7 行**，而遍历 3 行 ⇒ 重叠 1.3 行 ⇒ 同件大量重复，
            //  武器扫描"多 126"）；改用 profile 坐标（x=1614）后落地 **777~812px = 2.7 行** ✓ 稳定。
            val geoAdvance = intent.getBooleanExtra(EXTRA_GEO_ADVANCE, false)
                val adaptive = intent.getBooleanExtra(EXTRA_ADAPTIVE_DIST, true)
                // §16.4 标定/调试用 plan 注入：EXTRA_PLAN 直接 JSON（adb shell 会吞双引号→失效），
                // EXTRA_PLAN_B64 为 base64(JSON)（仅 [A-Za-z0-9+/=]，device sh 不吞，标定稳定通道）。
                val plan = (intent.getStringExtra(EXTRA_PLAN)
                    ?: intent.getStringExtra(EXTRA_PLAN_B64)?.let { b64 ->
                        try { String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT)) }
                        catch (e: Exception) { Log.w(TAG, "debug scan flow: bad planB64", e); null }
                    })?.let { raw ->
                    try {
                        JSONArray(raw).let { arr ->
                            (0 until arr.length()).map { arr.getJSONObject(it) }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "debug scan flow: invalid plan JSON, ignored", e)
                        null
                    }
                }
                // 时序覆盖：交给 startScan 单入口 apply（其余入口传 null ⇒ 复位，防跨轮残留）
                val timingSpec = intent.getStringExtra(EXTRA_TIMING)
                Log.i(TAG, "debug scan flow: flow=$flow maxPages=$maxPages timing='${timingSpec ?: ""}'")
                if (captureController.isRunning()) {
                    scriptRunner.startScan(
                        flow, maxPages,
                        useGeometryAdvance = geoAdvance,
                        useAdaptiveDistance = adaptive,
                        plan = plan,
                        timingSpec = timingSpec,
                    )
                } else {
                    Log.w(TAG, "scan flow request ignored: projection not running")
                }
            }
            ACTION_DEBUG_OCR_BENCH -> {
                // 真机 OCR 基准：det/rec 中位耗时（EP 选型/瓶颈定位，IO 线程不阻塞主线程）
                scriptRunner.scope.launch(Dispatchers.IO) {
                    Log.i(TAG, "ocr bench: ${OcrFactory.bench()}")
                }
            }
            ACTION_DEBUG_OCR_DET -> {
                // ONNX det 全管线真机探针（任意界面可跑，为移除 ML Kit 做验收）
                scriptRunner.scope.launch(Dispatchers.IO) {
                    val out = runCatching { scriptRunner.ocrDetProbe() }
                        .getOrDefault("probe failed: ${captureController.isRunning()}")
                    Log.i(TAG, "ocr det probe: $out")
                }
            }
            ACTION_DEBUG_CLICK -> {
                val x = intent.getIntExtra(EXTRA_CLICK_X, 0)
                val y = intent.getIntExtra(EXTRA_CLICK_Y, 0)
                val duration = intent.getIntExtra(EXTRA_CLICK_DURATION, 120).toLong()
                Log.i(TAG, "debug click at ($x,$y) duration=$duration")
                val ok = InputAccessibilityService.click(x, y, duration)
                Log.i(TAG, "debug click at ($x,$y) accepted=$ok")
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

    /** 最近一次 GOOD 导出文件名（悬浮窗「开始导出」用）。 */
    @Volatile
    private var lastGoodFile: String? = null

    /**
     * 悬浮窗「开始导出」：拉 app 前台（MainActivity 中转）再起系统分享 chooser——
     * service 后台直接 startActivity(chooser) 虽命中「UID 持有可见窗口」豁免，
     * 但分享面板需要 Activity 上下文，Android 14+ ROM 上直接起不可靠。
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
        runCatching { unregisterReceiver(a11yStateReceiver) }
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

    /**
     * **起流程的唯一入口**（2026-09-18 修"点了没反应"）。
     *
     * 修复前的缺陷：`startScan` 的 `plan` 参数**只有 adb 调试通道会传**，用户从悬浮窗点
     * 「圣遗物锁定 / 自动装备」时 `plan = null` ⇒ `ScanEngine.foreach` 打一行 warn 就返回，
     * 表现为"跑了一遍界面什么都没做"。
     *
     * 现在的行为：按脚本自己的 `ui.actions`（有没有 `import`）+ `vars`（要什么数据）取输入；
     * 需要输入却没有 / 这份文件喂不了它 ⇒ **发一条提醒并拒绝起跑**，不再静默空跑。
     */
    private fun startFlowIfReady(flowKey: String, maxPages: Int) {
        val ctx = applicationContext
        if (GoodRepository.needsInput(ctx, flowKey)) {
            val plan = GoodRepository.planFor(ctx, flowKey)
            if (plan == null) {
                val label = GoodRepository.labelOf(ctx, flowKey)
                NoticeCenter.warn("「$label」需要先选一份输入文件（管理器 → GOOD 数据）")
                if (settingsRepository.get().scanEnabled) {
                    settingsRepository.setScanEnabled(false)
                }
                return
            }
            overlayController.collapse() // 自动化执行前收面板（与滑动测试缩球对称）
            Log.i(TAG, "start flow=$flowKey plan=${plan.size} (from GoodRepository)")
            scriptRunner.startScan(flowKey, maxPages, plan = plan)
            return
        }
        overlayController.collapse()
        scriptRunner.startScan(flowKey, maxPages)
    }

    private fun requestCapturePermission() {
        if (requestingCapturePermission) return
        requestingCapturePermission = true
        try {
            // 悬浮窗点击 = 用户交互，且本应用 UID 持有可见窗口（无障碍进程里的悬浮窗）⇒
            // 命中「允许后台启动 Activity」的豁免，无需 SAW 也能直接拉起授权 activity
            // （2026-09-18 已用探针实测：吊销「显示在上层」权限后仍可启动）
            val intent = Intent(this, CapturePermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            // 兜底：部分 ROM 收紧后台启动限制 → 拉 app 前台，MainActivity 前台内再发起（无通知依赖）
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

    /**
     * 解析探针的 ROI 串：`"x,y,w,h;x,y,w,h;…"` → `List<IntRect>`（帧坐标）。
     * 非法片段静默跳过（探针不该因输入错误而失败）。
     *
     * ⚠️ **不要用 `--es` 直接传含 `;` 的串**：`adb shell` 会把 `;` 当**命令分隔符**，
     * 只有第一个 ROI 能到达（实测踩过）。故新增 [EXTRA_ROIS_B64]（base64）作为主通道。
     */
    private fun parseRoiCsv(csv: String): List<com.bettergi.pocket.recognition.IntRect> {
        if (csv.isBlank()) return emptyList()
        return csv.split(';').mapNotNull { seg ->
            val p = seg.trim().split(',').mapNotNull { it.trim().toIntOrNull() }
            if (p.size >= 4) com.bettergi.pocket.recognition.IntRect(p[0], p[1], p[2], p[3]) else null
        }
    }

    /** base64 解码（探针参数通道；非法输入返回空串，绝不抛）。 */
    private fun decodeB64(s: String): String = try {
        String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), Charsets.UTF_8)
    } catch (_: Exception) {
        ""
    }

    /**
     * 解析带名字的 ROI 串：`"name:x,y,w,h;name:x,y,w,h;…"` → `List<Pair<String,IntRect>>`。
     * 供 **ROI 稳定性探针** 用（要报出每个候选的名字）。非法片段跳过。
     */
    private fun parseNamedRois(s: String): List<Pair<String, com.bettergi.pocket.recognition.IntRect>> {
        if (s.isBlank()) return emptyList()
        return s.split(';').mapNotNull { seg ->
            val i = seg.indexOf(':')
            if (i <= 0) return@mapNotNull null
            val name = seg.substring(0, i).trim()
            val p = seg.substring(i + 1).trim().split(',').mapNotNull { it.trim().toIntOrNull() }
            if (name.isEmpty() || p.size < 4) {
                null
            } else {
                name to com.bettergi.pocket.recognition.IntRect(p[0], p[1], p[2], p[3])
            }
        }
    }

    companion object {
        const val TAG = "BetterGI.Service"
        const val ACTION_START = "com.bettergi.pocket.action.START"
        const val ACTION_STOP = "com.bettergi.pocket.action.STOP"
        const val ACTION_CAPTURE_RESULT = "com.bettergi.pocket.action.CAPTURE_RESULT"
        const val ACTION_CAPTURE_DENIED = "com.bettergi.pocket.action.CAPTURE_DENIED"
        /**
         * 悬浮窗「开始导出」：由无障碍进程经设置桥（`overlay_share_good`）转成服务指令。
         * 之所以不在无障碍进程直接起分享：导出文件名只有本服务知道（`lastGoodFile`），
         * 且系统分享面板必须由带 Activity 的进程拉前台。
         */
        const val ACTION_SHARE_GOOD = "com.bettergi.pocket.action.SHARE_GOOD"
        const val ACTION_SCAN_START = "com.bettergi.pocket.action.SCAN_START"
        const val ACTION_SCAN_STOP = "com.bettergi.pocket.action.SCAN_STOP"
        const val ACTION_DEBUG_SET_SCREEN_SHARE = "com.bettergi.pocket.action.DEBUG_SET_SCREEN_SHARE"
        const val ACTION_DEBUG_SET_SCAN = "com.bettergi.pocket.action.DEBUG_SET_SCAN"
        const val ACTION_DEBUG_STATUS = "com.bettergi.pocket.action.DEBUG_STATUS"

        /**
         * 把最近一次 GOOD 导出**拷贝到外部目录**供 adb 拉取（标定/回归比对用）。
         * 背景：BlueStacks 禁用 `run-as`（setegid 失败）且无 su ⇒ 私有 filesDir 读不到。
         * 落点：`/sdcard/Android/data/<pkg>/files/sweep_last_good.json`（adb shell 有 ext_data_rw 可读）。
         */
        const val ACTION_DEBUG_DUMP_GOOD = "com.bettergi.pocket.action.DEBUG_DUMP_GOOD"
        /** 调试：把设备上的 GOOD/配装计划文件复制成当前输入（`--es src /sdcard/xxx.json`）。 */
        const val ACTION_DEBUG_SET_GOOD = "com.bettergi.pocket.action.DEBUG_SET_GOOD"
        const val EXTRA_GOOD_SRC = "src"
        const val ACTION_DEBUG_SET_PROBE = "com.bettergi.pocket.action.DEBUG_SET_PROBE"
        const val ACTION_DEBUG_SET_VERBOSE = "com.bettergi.pocket.action.DEBUG_SET_VERBOSE"
        const val ACTION_DEBUG_SWIPE_TEST = "com.bettergi.pocket.action.DEBUG_SWIPE_TEST"
        const val ACTION_DEBUG_SCAN_FLOW = "com.bettergi.pocket.action.DEBUG_SCAN_FLOW"
        const val ACTION_DEBUG_CLICK = "com.bettergi.pocket.action.DEBUG_CLICK"
        const val ACTION_DEBUG_OCR_BENCH = "com.bettergi.pocket.action.DEBUG_OCR_BENCH"
        const val ACTION_DEBUG_OCR_DET = "com.bettergi.pocket.action.DEBUG_OCR_DET"

        /**
         * ★ 只读性能探针（2026-09-12）：帧路径拆段（alloc/put/cvtColor/全帧 vs ROI 口径）
         * + rec 宽度基准（含输出时间步 T）。**不改任何行为**，为
         * `dsl/verify/_audit/IMAGE-PATH-COST.md` §8 的决策提供分量实测值。
         * 用法：`-a <本 action> --ei runs 30 --es rois "x,y,w,h;…" --es widths "145,220,405,684"`
         */
        const val ACTION_DEBUG_PERF_PROBE = "com.bettergi.pocket.action.DEBUG_PERF_PROBE"
        const val EXTRA_RUNS = "runs"
        /** ROI 列表（帧坐标）：`"x,y,w,h;x,y,w,h"`；空 = 跳过 ROI 段。⚠️ adb shell 会吞 `;` ⇒ 优先用 B64。 */
        const val EXTRA_ROIS = "rois"
        /** ★ 首选通道：`base64("x,y,w,h;…")`。避开 device shell 对 `;` 的命令分隔解析。 */
        const val EXTRA_ROIS_B64 = "roisB64"
        /** rec 宽度列表（逗号分隔）；空 = 默认 `145,220,405,684`。 */
        const val EXTRA_WIDTHS = "widths"
        /**
         * ★ ROI **稳定性**探针输入：`base64("name:x,y,w,h;name:…")`。
         * 用于挑选"渲染完成后跨帧像素恒等"的 ROI 作自适应就绪锚（见 PIPELINE-FEASIBILITY.md §11.3）。
         */
        const val EXTRA_STABILITY_B64 = "stabilityB64"
        /** 稳定性探针的采样帧数（默认 60 ≈ 2s @30fps）。 */
        const val EXTRA_FRAMES = "frames"
        /**
         * ★ OCR 并行度探针输入：`"k:intra,k:intra,…"`（如 `"1:1,1:2,2:1,3:1"`）。
         * 用于判断"该调 intra 还是该做多会话并行池"，见 `OcrParallelProbe` 的 KDoc。
         */
        const val EXTRA_OCR_PAR = "ocrpar"
        /** ★ 帧率探针窗口（ms）：`--ei frate 1200`；0 = 跳过。 */
        const val EXTRA_FRAME_RATE = "frate"

        /** 探针日志独立 tag，便于 `logcat -s BetterGI.Perf` 直取。 */
        const val PERF_TAG = "BetterGI.Perf"

        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_START_Y = "startY"
        const val EXTRA_DIST = "dist"
        const val EXTRA_MEASURE = "measure"
        const val EXTRA_GRID = "grid"
        const val EXTRA_FROM_X = "fromX"
        const val EXTRA_DUR_MS = "durMs"
        const val EXTRA_SEGS = "segments"
        const val EXTRA_METHOD = "method"
        const val EXTRA_FLOW = "flow"
        const val EXTRA_MAX_PAGES = "maxPages"
        /** §14 P4：外部任务计划 JSON 数组（明文，adb shell 会吞双引号，标定用请走 planB64）。 */
        const val EXTRA_PLAN = "plan"
        /** §16.4 标定稳定通道：base64(JSON) 的 plan，device sh 不吞引号，标定/调试首选。 */
        const val EXTRA_PLAN_B64 = "planB64"

        /**
         * 时序覆盖（仅调试标定）：`--es timing "nav=650,poll=120,sstable=150,swFastMs=110,..."`
         * 见 [com.bettergi.pocket.scan.TimingOverrides] 与 dsl/verify/_audit/PERF-timing.md 档位计划。
         * 空/缺省 → 全默认（与改动前逐位一致）。
         */
        const val EXTRA_TIMING = "timing"
        /** §12.1 A/B：true=几何推导翻页落点，false=profiles 写死坐标。 */
        const val EXTRA_GEO_ADVANCE = "geoAdvance"
        /** §12.2 A/B：true=每页按相位误差自适应翻页距离。 */
        const val EXTRA_ADAPTIVE_DIST = "adaptiveDist"
        const val EXTRA_CLICK_X = "x"
        const val EXTRA_CLICK_Y = "y"
        const val EXTRA_CLICK_DURATION = "duration"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val NOTIFICATION_CHANNEL_ID = "bettergi_pocket_trigger"
        private const val NOTIFICATION_ID = 1001
    }
}
