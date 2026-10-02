package com.bettergi.pocket.scan

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.bettergi.pocket.capture.FrameSource
import com.bettergi.pocket.capture.ScreenCaptureController
import com.bettergi.pocket.input.InputAccessibilityService
import com.bettergi.pocket.overlay.OverlayBridge
import com.bettergi.pocket.dsl.FlowSource
import com.bettergi.pocket.dsl.FlowValidator
import com.bettergi.pocket.recognition.ocr.OcrFactory
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
    private val overlayController: OverlayBridge,
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
        override fun tap(x: Int, y: Int): Boolean {
            val dispatched = dispatchOnMain {
                val needPassthrough = overlayController.prepareClickPassthrough(x, y)
                val ok = InputAccessibilityService.tap(x, y)
                if (!ok && needPassthrough) overlayController.restoreClickPassthrough()
                ok to needPassthrough
            }
            if (dispatched != null) {
                // ⚠️ restore 必须晚于手势 UP 注入：a11y dispatchGesture 的事件注入有排队延迟，
                // 过早恢复 FLAG_NOT_TOUCHABLE → UP 被自家悬浮窗吞掉，游戏只见 DOWN 无 UP → 不触发 click
                // （equip18-22 实证：overlay 覆盖区（名册左上网格）点击全灭、非覆盖区（右下按钮）正常）
                scheduleRestorePassthrough(dispatched.second, PASSTHROUGH_RESTORE_SLACK_MS)
            }
            return onActionDispatched(dispatched != null && dispatched.first)
        }

        override fun click(x: Int, y: Int, durationMs: Long): Boolean {
            val dispatched = dispatchOnMain {
                val needPassthrough = overlayController.prepareClickPassthrough(x, y)
                val ok = InputAccessibilityService.click(x, y, durationMs)
                if (!ok && needPassthrough) overlayController.restoreClickPassthrough()
                ok to needPassthrough
            }
            if (dispatched != null) {
                scheduleRestorePassthrough(dispatched.second, durationMs + PASSTHROUGH_RESTORE_SLACK_MS)
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

        override fun resetPassthrough() {
            dispatchOnMain { overlayController.restoreClickPassthrough() }
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
     * 只读计时装饰器（2026-09-12 探针）：在 [actions] 外面包一层，把 click/swipe 的**真实注入耗时**
     * 累进 [PerfProbe]，供 `scan finished` 后打印一行「分量实测值」。
     *
     * ⚠️ 纯委托：不改变任何行为、不新增任何点击（早期方案曾想"额外点一次测耗时"，会污染游戏状态，已否）。
     * `markActionAt` 的回调链在 [actions] 内部，不受本层影响 ⇒ 帧阈值语义不变。
     */
    private val timedActions = object : ScanEngine.ActionGateway {
        override fun resetPassthrough() = actions.resetPassthrough()

        override fun click(x: Int, y: Int, durationMs: Long): Boolean {
            val t0 = System.nanoTime()
            var ok = false
            try {
                ok = actions.click(x, y, durationMs)
                return ok
            } finally {
                PerfProbe.addClick(System.nanoTime() - t0, ok)
            }
        }

        override fun tap(x: Int, y: Int): Boolean {
            val t0 = System.nanoTime()
            var ok = false
            try {
                ok = actions.tap(x, y)
                return ok
            } finally {
                PerfProbe.addClick(System.nanoTime() - t0, ok)
            }
        }

        override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean {
            val t0 = System.nanoTime()
            try {
                return actions.swipe(fromX, fromY, toX, toY)
            } finally {
                PerfProbe.addSwipe(System.nanoTime() - t0)
            }
        }

        override fun back(): Boolean = actions.back()
    }

    /**
     * 启动扫描流程。flowName 决定 assets/dsl/flows/<flowName>.json（"artifact_scan" | "weapon_scan"）。
     * 名称词典统一加载（dsl/tools/good_names.json → GoodNames），各原语经 NameMatcher 反查。
     */
    fun startScan(
        flowName: String = "artifact_scan",
        maxPages: Int = Int.MAX_VALUE,
        /** ⚠️ 2026-09-17 默认 false（几何起点致滚动截断）。 */
        useGeometryAdvance: Boolean = false,
        // §12.2 默认关：待真机 err 序列标定后再开（adb --ez adaptiveDist true 可开）
        useAdaptiveDistance: Boolean = false,
        /** 外部任务计划（P4 规则层注入）：artifact_lock 的 targets / auto_equip 的 plan。 */
        plan: List<JSONObject>? = null,
        /**
         * 时序覆盖串（调试标定用，形如 `nav=700,poll=120`）。**null = 复位为默认**。
         * ⚠️ 必须在这里统一 apply：扫描入口有 5 处，若只在 DEBUG_SCAN_FLOW 里 apply，
         *    其余入口（普通扫描/自动拾取）会**沿用上一轮的覆盖值**。
         */
        timingSpec: String? = null,
    ) {
        FlowSource.install(appContext)
        Log.i(TAG, "timing: ${TimingOverrides.apply(timingSpec)}")
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
        val validFlows = setOf("artifact_scan", "weapon_scan", "character_scan", "artifact_lock", "auto_equip", "char_calibrate", "setfilter_calibrate")
        val flowFile = "dsl/flows/$flowName.json"
        if (flowName !in validFlows) {
            Log.w(TAG, "unknown flow $flowName; default to artifact_scan")
            startScan("artifact_scan"); return
        }
        // ML Kit 移除前的前置检查：无可用 OCR 则显式失败（避免静默读出空文本）
        if (!OcrFactory.available) {
            Log.e(TAG, "no OCR engine available; abort scan")
            listener.onFinished("ocr_unavailable"); return
        }
        currentJob = scope.launch {
            try {
                val profile = ScreenProfile.loadFor(appContext.assets, size.first, size.second)
                profile.calibrate(size.first, size.second)
                @Suppress("DEPRECATION")
                val appVersion = try {
                    appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "0.0.0"
                } catch (_: Exception) { "0.0.0" }
                val flowText = FlowSource.open(appContext.assets, flowFile).bufferedReader().use { it.readText() }
                val flow = try {
                    JSONObject(flowText)
                } catch (e: Exception) {
                    Log.w(TAG, "flow $flowName JSON 解析失败", e)
                    listener.onFinished("flow_parse_error"); return@launch
                }
                // §16.3 S2 门禁：min_host_version 拒跑 + schema 快速校验
                val info = FlowValidator.parseInfo(flow, flowName)
                if (!FlowValidator.hostSatisfies(info.minHostVersion, appVersion)) {
                    Log.w(TAG, "flow $flowName 需宿主 >= ${info.minHostVersion}，当前 $appVersion 过低")
                    listener.onFinished("host_version_too_low"); return@launch
                }
                val issues = FlowValidator.validate(flow)
                if (issues.isNotEmpty()) {
                    Log.w(TAG, "flow $flowName schema 非法: ${issues.joinToString { "${it.path}: ${it.message}" }}")
                    listener.onFinished("flow_schema_invalid"); return@launch
                }
                // §15：真机启动路径默认开启前置归位（flow 可显式 returnHome:false 关闭，如标定流）
                if (!flow.has("returnHome")) flow.put("returnHome", true)
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
                    actions = timedActions, // 只读计时装饰器（委托 actions，行为不变）
                    ocr = OcrGatewayImpl(),
                    names = names,
                    listener = listener,
                    maxPages = maxPages,
                    useGeometryAdvance = useGeometryAdvance,
                    useAdaptiveDistance = useAdaptiveDistance,
                    plan = plan,
                    // §13：流程名 → 识别日志的来源标签（LOCK/EQUIP/CHAR/SCAN）
                    flowName = flowName,
                )
                // ★ 2026-09-14：取消扫描期常驻穿透，回到逐点 prepare/restore（OverlayWindowController
                //   内调用点均健在）。e63f7a4 引入常驻穿透是为防悬浮窗盖住操作区时手势 UP 不穿透；
                //   现悬浮窗已改右上角小球（钉右缘、只纵向移动），与全部操作点零重叠 ⇒ 前提不成立，
                //   而常驻穿透让扫描期停止/分享/拖球全部失效。仅保留 hidden=true 排查路径（GONE 对照）。
                if (SCAN_OVERLAY_HIDDEN) {
                    overlayController.setScanClickThrough(true, hidden = true)
                }
                // ★★ 2026-09-16 **会话级看门狗**（独立 daemon 线程）★★
                //   真机实测：偶发"主滑派发后无任何日志"的**硬挂**（>15min），卡在 **单个 visit 内部**
                //   ⇒ 页级看门狗（只在格与格之间检查，见 ScanEngine.PAGE_WATCHDOG_MS）**抓不到**
                //   ⇒ 整轮数据全废（导出发生在 run() 返回之后）。
                //   本线程只做一件事：盯 ScanEngine.lastProgressAtMs（freshFrame 打点）。
                //   超 SESSION_STALL_MS 无进展 ⇒ 判挂死 ⇒ **立刻导出已入库结果**（与正常结束同一路径）
                //   + 置 stopRequested（若阻塞随后解除，run() 会自行收尾，不重复导出）。
                //   非侵入：不改任何既有判据/流程，只在扫描期多跑一个 5s 周期的只读线程。
                val wdStop = java.util.concurrent.atomic.AtomicBoolean(false)
                val exported = java.util.concurrent.atomic.AtomicBoolean(false)
                fun exportNow(why: String) {
                    if (!exported.compareAndSet(false, true)) return
                    val arts = engine.results.toList()
                    val wps = engine.resultsWeapons.toList()
                    val chs = engine.resultsCharacters.toList()
                    if (arts.isEmpty() && wps.isEmpty() && chs.isEmpty()) {
                        Log.w(TAG, "导出跳过（$why）：无已入库结果")
                        return
                    }
                    runCatching {
                        val file = GoodExporter.export(appContext, arts, wps, chs)
                        Log.i(TAG, "导出完成（$why）: $file (${arts.size}a ${wps.size}w ${chs.size}c)")
                        listener.onProgress(
                            "exported",
                            mapOf(
                                "file" to file,
                                "count" to arts.size,
                                "weapons" to wps.size,
                                "characters" to chs.size,
                            ),
                        )
                    }.onFailure { Log.e(TAG, "导出失败（$why）", it) }
                }
                val wd = Thread {
                    while (!wdStop.get()) {
                        try {
                            Thread.sleep(5_000)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                        if (wdStop.get()) return@Thread
                        val last = engine.lastProgressAtMs
                        if (last <= 0L) continue
                        val idle = android.os.SystemClock.elapsedRealtime() - last
                        if (idle > SESSION_STALL_MS) {
                            Log.e(
                                TAG,
                                "会话级看门狗：${idle}ms 无进展（阈值 ${SESSION_STALL_MS}ms）⇒ 判挂死，" +
                                    "导出已入库结果并请求停止",
                            )
                            runCatching { engine.vars.stopRequested = true }
                            exportNow("watchdog")
                            wdStop.set(true)
                            return@Thread
                        }
                    }
                }
                wd.isDaemon = true
                wd.name = "scan-session-watchdog"
                wd.start()
                try {
                    engine.run()
                } finally {
                    wdStop.set(true)
                    wd.interrupt()
                    if (SCAN_OVERLAY_HIDDEN) {
                        overlayController.setScanClickThrough(false)
                    }
                }
                exportNow("normal")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 主动 stop()：取消非失败，不刷 error 日志（真机实测 JobCancellationException 噪音）
                Log.i(TAG, "scan cancelled")
                listener.onFinished("cancelled")
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
     * ONNX **det 全管线**探针（移除 ML Kit 前的验收工具）：抓当前帧跑一次 det+rec，
     * 报告引擎/区域数/耗时/前 80 字。任意界面可用——不必进游戏背包即可验证 det 路径。
     */
    suspend fun ocrDetProbe(): String {
        if (!captureController.isRunning()) return "no projection"
        val frame = frameSource.grabFresh(0L, 2500L)
        return try {
            val t0 = System.nanoTime()
            val res = OcrFactory.default.recognize(frame)
            val ms = (System.nanoTime() - t0) / 1_000_000
            // 带上每个文本块的矩形：面板 ROI 标定可直接取识别框坐标，无需看图
            val boxes = res.regions.joinToString(" | ") {
                "'${it.text}'@${it.rect.x},${it.rect.y},${it.rect.width}x${it.rect.height}"
            }
            "engine=${OcrFactory.engineLabel} regions=${res.regions.size} detTotal=${ms}ms\n$boxes"
        } finally {
            frame.release()
        }
    }

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

        /**
         * **会话级看门狗**阈值（2026-09-16）：扫描期 `ScanEngine.lastProgressAtMs` 超此时长无进展
         * ⇒ 判「visit 内部硬挂」（实测 >15min 无日志、页级看门狗抓不到）⇒ 立刻导出已入库结果。
         * 正常单格 ~300ms、单页 ~7s（含 settle ≤6s）⇒ 120s 只可能是真卡死，不会误伤慢页。
         */
        const val SESSION_STALL_MS = 120_000L
        const val MAIN_DISPATCH_TIMEOUT_MS = 5000L
        /** 点击后恢复悬浮窗可触摸的宽放余量：须晚于 a11y 手势 UP 注入（否则 UP 被吞→无 click）。 */
        const val PASSTHROUGH_RESTORE_SLACK_MS = 600L
        /**
         * 排查开关（**编译期常量**）：`true` = 扫描期把悬浮窗直接置 GONE（对照实验用）。
         *
         * ⚠️ 2026-09-16（审计 P2-4）说明：本常量恒为 `false` ⇒ 下面两个 `if` 是**死分支**，
         * 即默认路径**完全不再调用** `setScanClickThrough` —— 扫描期悬浮窗保持可点
         * （停止/分享/拖球都可用），代价是重新依赖逐点 `prepareClickPassthrough` 保证游戏侧点击。
         * 该取舍得失由真机复验裁定：必须同时满足「悬浮窗可点」**且**「点击/滑动全生效」。
         * 想复现旧行为（扫描期常驻穿透）只需把本常量改 `true` 重建。
         */
        const val SCAN_OVERLAY_HIDDEN = false
    }
}
