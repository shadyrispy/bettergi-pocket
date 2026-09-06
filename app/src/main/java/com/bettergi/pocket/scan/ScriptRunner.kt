package com.bettergi.pocket.scan

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.bettergi.pocket.capture.FrameSource
import com.bettergi.pocket.capture.ScreenCaptureController
import com.bettergi.pocket.input.InputAccessibilityService
import com.bettergi.pocket.overlay.OverlayWindowController
import com.bettergi.pocket.recognition.name.GoodNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 扫描脚本运行时（方案 §5.5：不进 TriggerEngine 100ms 节拍，独立协程单步循环，
 * 复用 TriggerForegroundService 的生命周期与帧源）。
 *
 * - 帧源：[FrameSource]（P0 投影唯一帧源），动作后新帧由 markActionAt 机制保证。
 * - 动作：直接走 InputAccessibilityService（同步受理返回），经主线程处理悬浮窗 touch passthrough；
 *   受理成功即调用 frameSource.markActionAt —— ScanEngine.freshFrame 的时序基准。
 */
class ScriptRunner(
    context: Context,
    private val frameSource: FrameSource,
    private val captureController: ScreenCaptureController,
    private val overlayController: OverlayWindowController,
    private val listener: ScanListener,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var currentJob: kotlinx.coroutines.Job? = null

    private val running: Boolean
        get() = currentJob?.isActive == true

    private val actions = object : ScanEngine.ActionGateway {
        override fun click(x: Int, y: Int, durationMs: Long): Boolean {
            val dispatched = dispatchOnMain {
                val needPassthrough = overlayController.prepareClickPassthrough(x, y)
                val ok = InputAccessibilityService.click(x, y, durationMs)
                if (!ok && needPassthrough) overlayController.restoreClickPassthrough()
                ok to needPassthrough
            }
            if (dispatched != null) {
                scheduleRestorePassthrough(dispatched.second, durationMs)
            }
            return onActionDispatched(dispatched != null && dispatched.first)
        }

        override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean {
            val totalMs = InputAccessibilityService.SWIPE_TOTAL_MS
            val result = dispatchOnMain {
                val needPassthrough = overlayController.prepareClickPassthrough(fromX, fromY) or
                    overlayController.prepareClickPassthrough(toX, toY)
                val ok = InputAccessibilityService.swipe(fromX, fromY, toX, toY)
                if (!ok && needPassthrough) overlayController.restoreClickPassthrough()
                ok to needPassthrough
            }
            if (result != null) {
                scheduleRestorePassthrough(result.second, totalMs)
            }
            return onActionDispatched(result != null && result.first)
        }

        override fun back(): Boolean {
            // 系统返回键清弹窗：无 overlay 交互（返回键目标由系统路由），直接桥调
            val result = dispatchOnMain { InputAccessibilityService.back() to false }
            return onActionDispatched(result != null && result.first)
        }

        private fun onActionDispatched(ok: Boolean): Boolean {
            if (ok) frameSource.markActionAt(SystemClock.elapsedRealtime())
            return ok
        }

        private fun scheduleRestorePassthrough(needed: Boolean, gestureMs: Long) {
            if (!needed) return
            mainHandler.postDelayed(
                { overlayController.restoreClickPassthrough() },
                gestureMs + RESTORE_TOUCH_DELAY_MS,
            )
        }

        private fun <T> dispatchOnMain(block: () -> T): T? {
            if (Looper.myLooper() == Looper.getMainLooper()) return block()
            var result: T? = null
            val latch = java.util.concurrent.CountDownLatch(1)
            mainHandler.post {
                try {
                    result = block()
                } finally {
                    latch.countDown()
                }
            }
            // 超时兜底：主线程异常阻塞时不挂死扫描协程（按 dispatch 失败处理）
            if (!latch.await(MAIN_DISPATCH_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "main dispatch timed out")
                return null
            }
            return result
        }
    }

    /**
     * 启动扫描流程。flowName 决定 assets/dsl/flows/<flowName>.json（"artifact_scan" | "weapon_scan"）。
     * 名称词典统一加载（dsl/tools/good_names.json → GoodNames），各原语经 NameMatcher 反查。
     */
    fun startScan(
        flowName: String = "artifact_scan",
        maxPages: Int = Int.MAX_VALUE,
        useGeometryAdvance: Boolean = true,
        // §12.2 默认关：待真机 err 序列标定后再开（adb --ez adaptiveDist true 可开）
        useAdaptiveDistance: Boolean = false,
        /** 外部任务计划（P4 规则层注入）：artifact_lock 的 targets / auto_equip 的 plan。 */
        plan: List<JSONObject>? = null,
    ) {
        if (running) {
            Log.w(TAG, "scan already running")
            return
        }
        if (!captureController.isRunning()) {
            Log.w(TAG, "scan requires screen projection")
            listener.onFinished("no_projection")
            return
        }
        val size = captureController.capturedSize()
        if (size == null) {
            listener.onFinished("no_frame_size")
            return
        }
        val validFlows = setOf("artifact_scan", "weapon_scan", "character_scan", "artifact_lock", "auto_equip")
        val flowFile = "dsl/flows/$flowName.json"
        if (flowName !in validFlows) {
            Log.w(TAG, "unknown flow $flowName; default to artifact_scan")
            startScan("artifact_scan"); return
        }
        currentJob = scope.launch {
            try {
                val profile = ScreenProfile.loadFor(appContext.assets, size.first, size.second)
                profile.calibrate(size.first, size.second)
                val flow = JSONObject(
                    appContext.assets.open(flowFile).bufferedReader().use { it.readText() },
                )
                // 模板锚点匹配器：注入 assets 并预读 dsl/templates.json（flow 的 anchor.kind=template 依赖）
                TemplateMatcher.attach(appContext.assets)
                // 副词条档位表：flow 的 dict.subStats="rollTable" 依赖（dsl/tools/rollTable.json）
                RollTable.attach(appContext.assets)
                // 单一名称词典（角色/武器/套装/单件/词条/部位），失败降级为 null → 名称反查跳过
                val names = try {
                    GoodNames.load(appContext.assets)
                } catch (e: Exception) {
                    Log.w(TAG, "good_names unavailable", e); null
                }
                val engine = ScanEngine(
                    flowJson = flow,
                    profile = profile,
                    frameSource = frameSource,
                    actions = actions,
                    ocr = MlKitOcrGateway(),
                    names = names,
                    listener = listener,
                    maxPages = maxPages,
                    useGeometryAdvance = useGeometryAdvance,
                    useAdaptiveDistance = useAdaptiveDistance,
                    plan = plan,
                    // §13：流程名 → 识别日志的来源标签（LOCK/EQUIP/CHAR/SCAN）
                    flowName = flowName,
                )
                engine.run()
                val total = engine.results.size + engine.resultsWeapons.size + engine.resultsCharacters.size
                if (total > 0) {
                    val file = GoodExporter.export(
                        appContext,
                        engine.results,
                        engine.resultsWeapons,
                        engine.resultsCharacters,
                    )
                    listener.onProgress(
                        "exported",
                        mapOf(
                            "file" to file,
                            "count" to engine.results.size,
                            "weapons" to engine.resultsWeapons.size,
                            "characters" to engine.resultsCharacters.size,
                        ),
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "scan failed", e)
                listener.onFinished("error: ${e.message}")
            }
        }
    }

    /** 兼容旧调用：artifact_scan 流程。 */
    fun startArtifactScan() = startScan("artifact_scan")

    fun stop() {
        currentJob?.cancel()
        currentJob = null
    }

    fun isRunning(): Boolean = running

    /**
     * 调试：抓取当前帧，测网格首行上沿相位误差（§12.2 GridAlign.measureError）。
     * 用于滑动距离自适应算法的模拟器标定——对比不同翻页距离下的 err 与真值折返曲线。
     * 调用方需先确保屏幕停在当前网格（如圣遗物背包），否则返回 null。
     */
    suspend fun measureTopEdge(gridKey: String): Int? {
        if (!captureController.isRunning()) {
            Log.w(TAG, "measureTopEdge: no projection")
            return null
        }
        val size = captureController.capturedSize() ?: return null
        val profile = ScreenProfile.loadFor(appContext.assets, size.first, size.second)
        profile.calibrate(size.first, size.second)
        val frame = frameSource.grabFresh(0L, 2500L)
        return try {
            GridAlign.measureError(frame, profile, gridKey)
        } finally {
            frame.release()
        }
    }

    private companion object {
        const val TAG = "BetterGI.ScanRunner"
        const val RESTORE_TOUCH_DELAY_MS = 40L
        const val MAIN_DISPATCH_TIMEOUT_MS = 5000L
    }
}
