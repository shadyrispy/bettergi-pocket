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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
     * 词典按 flow 实际用到时懒加载（artifact→ArtifactSetDictionary；weapon→WeaponDictionary）。
     */
    fun startScan(flowName: String = "artifact_scan", maxPages: Int = Int.MAX_VALUE) {
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
        val validFlows = setOf("artifact_scan", "weapon_scan")
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
                val setDictionary = if (flowName == "artifact_scan") {
                    try { ArtifactSetDictionary.load(appContext.assets) } catch (e: Exception) { Log.w(TAG, "set dict unavailable", e); null }
                } else null
                val weaponDictionary = if (flowName == "weapon_scan") {
                    try { WeaponDictionary.load(appContext.assets) } catch (e: Exception) { Log.w(TAG, "weapon dict unavailable", e); null }
                } else null
                val characterDictionary = try {
                    CharacterDictionary.load(appContext.assets)
                } catch (e: Exception) {
                    Log.w(TAG, "characters dict unavailable", e); null
                }
                val engine = ScanEngine(
                    flowJson = flow,
                    profile = profile,
                    frameSource = frameSource,
                    actions = actions,
                    ocr = MlKitOcrGateway(),
                    setDictionary = setDictionary,
                    weaponDictionary = weaponDictionary,
                    characterDictionary = characterDictionary,
                    listener = listener,
                    maxPages = maxPages,
                )
                engine.run()
                val total = engine.results.size + engine.resultsWeapons.size
                if (total > 0) {
                    val file = GoodExporter.export(appContext, engine.results, engine.resultsWeapons)
                    listener.onProgress("exported", mapOf("file" to file, "count" to engine.results.size, "weapons" to engine.resultsWeapons.size))
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

    private companion object {
        const val TAG = "BetterGI.ScanRunner"
        const val RESTORE_TOUCH_DELAY_MS = 40L
        const val MAIN_DISPATCH_TIMEOUT_MS = 5000L
    }
}
