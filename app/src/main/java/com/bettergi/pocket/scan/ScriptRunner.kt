package com.bettergi.pocket.scan

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.bettergi.pocket.capture.FrameSource
import com.bettergi.pocket.capture.ScreenCaptureController
import com.bettergi.pocket.input.InputAccessibilityService
import com.bettergi.pocket.log.RecognitionLog
import com.bettergi.pocket.bridge.OverlayBridge
import com.bettergi.pocket.core.FlowSource
import com.bettergi.pocket.dsl.FlowValidator
import com.bettergi.pocket.recognition.ocr.OcrFactory
import com.bettergi.pocket.recognition.name.GoodNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    /**
     * A15（2026-09-30）：扫描**单飞**状态机（start/stop 原子化）。
     * 事故依据与关键语义见 [ScanLifecycle] 类注释；stop 的等待上限 [STOP_JOIN_TIMEOUT_MS]。
     */
    private val lifecycle = ScanLifecycle(STOP_JOIN_TIMEOUT_MS)

    /** 旧口径（isActive）：服务端 applySettings/isRunning 观察用，语义不变（收尾中的 Cancelling 不算在跑）。 */
    private val running: Boolean
        get() = lifecycle.isActive

    /**
     * 学到的**账户级**性质，跨轮持久。目前只有「上锁确认框不再弹」——那是一次性提示，
     * 账户在本设备确认过就永不复现，每轮从零重学等于每轮白等 1.5s。
     * 单独一个 prefs 文件：它不是用户设置，清了也不该影响任何开关。
     */
    private fun learnedPrefs() = appContext.getSharedPreferences("bp_learned", Context.MODE_PRIVATE)

    private fun lockConfirmAbsentLearned() = learnedPrefs().getBoolean("lock_confirm_absent", false)

    private fun rememberLockConfirmAbsent() {
        learnedPrefs().edit().putBoolean("lock_confirm_absent", true).apply()
    }

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
            // 超时兜底：主线程异常阻塞时不挂死扫描协程（按 dispatch 失败处理）——语义不变，仍返回 null。
            // A14（2026-09-30）：原实现超时返回 null 后，已 post 到主线程的 task 稍后仍会执行
            // ⇒ 引擎按「派发失败」处理后的**游离点击**（主线程卡 5s+ 解除后补一刀，引擎不知情）。
            // 改经 MainDispatchGate：原子「终结权」CAS——超时方抢先置位后，之后才被主线程取出
            // 的 task 入口 CAS 失败，不再执行 block；removeCallbacks 再摘一遍未入队的。
            return MainDispatchGate.dispatch(
                post = { mainHandler.post(it) },
                removePending = { mainHandler.removeCallbacks(it) },
                timeoutMs = MAIN_DISPATCH_TIMEOUT_MS,
                onTimeout = { cleanly -> Log.w(TAG, "main dispatch timed out (cancelledClean=$cleanly)") },
                block = block,
            )
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
     * 名称词典统一加载（dsl/tools/mappings.json → GoodNames），各原语经 NameMatcher 反查。
     */
    fun startScan(
        flowName: String = "artifact_scan",
        maxPages: Int = Int.MAX_VALUE,
        /** ⚠️ 2026-09-17 默认 false（几何起点致滚动截断）。**只切换滑动起点**（几何卡缝 / profile 字面 from）。 */
        useGeometryAdvance: Boolean = false,
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
        // A15（2026-09-30）：上一轮未**真正**终结（含取消收尾中——isActive 在 cancel() 后立即
        // 变 false，不能再用它判忙）⇒ 拒绝新启动，但必须回执：原先静默 return，悬浮窗停在
        // 「启动中」且没有任何结束信号。走 onFinished 既有通路：服务端会复位 scanEnabled 并
        // 显示「已结束（already_running）」，与「本轮没有新扫描起跑」一致（悬浮窗单飞模型下，
        // 重复 start 本就该终止等待态；adb 在扫描进行中重复起扫同此口径）。
        lifecycle.unfinishedJob()?.let {
            Log.w(TAG, "scan already running (cancelling=${!it.isActive})")
            listener.onFinished("already_running")
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
        // A15：先 launch 再经 tryAttach 原子装入（装入前的竞态终检 + 回执见 startScan 尾部）。
        // 残余 TOCTOU（既有语义）：校验窗口内的 stop() 看不到尚未装入的 job，拦不住随后
        // 起跑的本次扫描——主线程串行调用下不存在该窗口。
        val newJob = scope.launch {
            // 提到 try 外：`catch` 块看不到 try 体内声明的局部函数（P2-3 的导出就靠这两行才可达）。
            val exported = java.util.concurrent.atomic.AtomicBoolean(false)
            var exportResults: (() -> Unit)? = null
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
                    Log.w(TAG, "名称词典不可用（tools/mappings.json）", e); null
                }
                val engine = ScanEngine(
                    flowJson = flow,
                    profile = profile,
                    frameSource = frameSource,
                    actions = timedActions, // 只读计时装饰器（委托 actions，行为不变）
                    ocr = OcrGatewayImpl(),
                    names = names,
                    // #105：玩家自定义昵称（管理器页填，账号相关 ⇒ 每次起扫现读，不做进程内缓存）
                    nameOverrides = NameOverrides.load(appContext),
                    listener = listener,
                    maxPages = maxPages,
                    useGeometryAdvance = useGeometryAdvance,
                    plan = plan,
                    // §13：流程名 → 识别日志的来源标签（LOCK/EQUIP/CHAR/SCAN）
                    flowName = flowName,
                    lockConfirmAbsentLearned = lockConfirmAbsentLearned(),
                    onLockConfirmAbsentLearned = { rememberLockConfirmAbsent() },
                    // #83：页首前台闸门。null（无障碍未连接/还没观察到窗口切换）= 放行，
                    // 与注入侧 `allowInject` 对"未知"的口径一致 —— 拦它会把整条流程变全 false。
                    foregroundOk = { InputAccessibilityService.isGenshinInForeground() ?: true },
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
                fun exportNow(why: String) {
                    if (!exported.compareAndSet(false, true)) return
                    // A13（2026-09-30）：engine.results* 是扫描协程并发写的 ArrayList
                    // （ScanEngine :283/:288/:3381），本函数可能从看门狗线程调用 ⇒ 裸 toList()
                    // 会吃 ConcurrentModificationException，且此处原先整体无 try/catch ⇒
                    // 看门狗线程未捕获异常直接杀进程（恰发生在抢救数据的时刻）。
                    // 修法（受白名单限制，不动 ScanEngine 加锁）：快照走 [ResultsSnapshot]
                    // 有界重试（挂死 ⇒ 扫描协程不再 add ⇒ 重试必然拿到稳定快照，见该类注释），
                    // 整个导出体包 runCatching——失败走 RecognitionLog + logcat，绝不裸抛。
                    runCatching {
                        val arts = ResultsSnapshot.withRetry { engine.results.toList() }
                        val wps = ResultsSnapshot.withRetry { engine.resultsWeapons.toList() }
                        val chs = ResultsSnapshot.withRetry { engine.resultsCharacters.toList() }
                        if (arts.isEmpty() && wps.isEmpty() && chs.isEmpty()) {
                            Log.w(TAG, "导出跳过（$why）：无已入库结果")
                            return
                        }
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
                    }.onFailure { e ->
                        Log.e(TAG, "导出失败（$why）", e)
                        RecognitionLog.log(
                            RecognitionLog.Tag.SCAN,
                            RecognitionLog.Level.W,
                            "结果导出失败（$why）：${e.message ?: e.javaClass.simpleName}",
                        )
                    }
                }
                // 交给 try 外的 catch 用：异常发生时本轮已识别的结果不能跟着一起没了（P2-3）。
                exportResults = { exportNow("aborted") }
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
                // 主动 stop()：取消非失败，不刷 error 日志（真机实测 JobCancellationException 噪音）。
                // **不导出**：手动停是"这一轮不要了"，而输入仓库是单文件覆盖写 ⇒ 半截库存会把上一份
                // 完整输入顶掉，`artifact_lock`/`auto_equip` 却拿它当计划依据（与 CaptureSession 同策）。
                Log.i(TAG, "scan cancelled")
                listener.onFinished("cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "scan failed", e)
                // 崩溃 ≠ 用户放弃：本轮已识别的件必须留下（此前 `exportNow("normal")` 被跨过 ⇒ 全废，
                // 而看门狗超时那条路反而导出 —— 见 :296 的 P2-3 注释）。
                exportResults?.invoke()
                listener.onFinished("error: ${e.message}")
            }
        }
        // A15：装入前竞态终检（与 stop/其他 start 并发时锁内单飞；主线程调用方串行 ⇒ 纯防御）。
        // 竞态输家：取消自己刚起的 job（尚未推进实质工作）并发同一回执；输家协程体的
        // catch(CancellationException) 会补一条 "cancelled" 回执——双回执幂等，无害。
        lifecycle.tryAttach(newJob)?.let {
            newJob.cancel()
            Log.w(TAG, "scan already running (raced)")
            listener.onFinished("already_running")
            return
        }
    }

    /** 兼容旧调用：artifact_scan 流程。 */
    fun startArtifactScan() = startScan("artifact_scan")

    /**
     * A15（2026-09-30）：stop→start 原子化。原实现 `cancel(); currentJob = null` —— cancel 是
     * **异步**的，立刻置 null 后新 startScan 直接 launch ⇒ 新旧引擎并存（快速「停止→开始」即
     * 复现：旧引擎在下一个取消检查点之前仍会点击/抓帧）。
     *
     * 现改为：cancel 后**有界等待**取消真正完成（stop 可能从主线程调 ⇒ 不能无界阻塞主线程，
     * 上限 [STOP_JOIN_TIMEOUT_MS]，正常收尾毫秒级即返回）；等待期间 job 引用保留 ⇒
     * [ScanLifecycle.unfinishedJob]/[ScanLifecycle.tryAttach] 全程拒绝新启动（「期间拒绝新启动」）。
     *
     * **超时兜底放行**（工单口径）与残余窗口：旧协程若卡在不可中断的阻塞段（如 dispatchOnMain
     * 的 latch.await——cancel 不会打断它，须等手势完成），放行后新旧引擎至多短暂并存、旧协程
     * 至多再推进一个在途动作/一个取消检查点即退出；真挂死由会话级看门狗（SESSION_STALL_MS）
     * 兜底。放行优先于把主线程/重启通道无限期挂住（卡死场景下用户必须还能重启）。
     */
    fun stop() {
        when (lifecycle.stop()) {
            is ScanLifecycle.StopOutcome.Settled -> Unit
            is ScanLifecycle.StopOutcome.LooseEnd ->
                Log.w(
                    TAG,
                    "stop: 旧扫描协程 ${STOP_JOIN_TIMEOUT_MS}ms 内未收尾，放行后续启动（A15 残余窗口：旧协程至多推进到下一个取消检查点）",
                )
        }
    }

    /**
     * 宿主（前台服务）销毁时调用。
     *
     * [stop] 只掐"当前这一次扫描"，而 [scope] 上还挂着别的常驻协程（`TriggerForegroundService:159`
     * 的抓包状态收集器、各探针）⇒ 服务已经死了它们还在跑，并且持有服务与悬浮窗引用（P3-2）。
     */
    fun shutdown() {
        stop()
        scope.cancel()
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

        /**
         * A15：stop() 等待旧扫描协程真正终结的上限（几百 ms 量级）。
         * stop 可能从主线程调（悬浮窗/applySettings/服务销毁）⇒ 不能无界阻塞主线程；
         * 400ms 远低于 ANR 阈值，正常取消收尾毫秒级即达，超时仅出现在协程正卡在
         * 不可中断阻塞段（如 dispatchOnMain 的 latch.await）时——残余窗口见 [stop] 注释。
         */
        const val STOP_JOIN_TIMEOUT_MS = 400L
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

/**
 * A14（2026-09-30）：主线程派发闸门——「超时取消标记」的原子化。
 * 纯 JVM（无 Android 依赖；post/removePending/onTimeout 均注入），ScriptRunnerTest 直接驱动。
 *
 * 事故依据：原 dispatchOnMain 超时返回 null（引擎按派发失败处理）后，已 post 到主线程的
 * task 稍后仍会执行 ⇒ 游离点击。终结权用一个原子位表达：task 执行入口与超时分支各做一次
 * CAS(false→true)，**谁先抢到谁说了算**——超时方抢到 ⇒ 之后才被取出的 task 不再执行 block；
 * task 抢到 ⇒ block 已开始执行（点击已发生，无法抢占，属 TOCTOU 残余窗口，语义仍按超时
 * 返回 null——与旧实现一致）。
 */
internal object MainDispatchGate {

    /**
     * @param post          把 task 投递到目标线程（生产侧 = mainHandler::post）
     * @param removePending 超时后摘除尚未执行的 task（生产侧 = mainHandler::removeCallbacks；
     *                      先 CAS 后 remove，保证「检查过标记但尚未执行」的窗口收敛到 CAS 一瞬）
     * @param onTimeout     超时回调（参数 = 是否干净取消：false 表示 task 恰在超时分支前已开始）
     * @return block 的结果；超时一律返回 null（约定：生产侧 block 永不返回 null ⇒ null 即超时）
     */
    fun <T> dispatch(
        post: (Runnable) -> Unit,
        removePending: (Runnable) -> Unit,
        timeoutMs: Long,
        onTimeout: (cancelledClean: Boolean) -> Unit = {},
        block: () -> T,
    ): T? {
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: T? = null
        val task = Runnable {
            val won = settled.compareAndSet(false, true)
            try {
                if (won) result = block()
            } finally {
                latch.countDown()
            }
        }
        post(task)
        return if (latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            result
        } else {
            val cancelledClean = settled.compareAndSet(false, true)
            removePending(task)
            onTimeout(cancelledClean)
            null
        }
    }
}

/**
 * A13（2026-09-30）：并发容器的**有界重试**快照（纯 JVM，ScriptRunnerTest 直接驱动）。
 *
 * 背景：会话级看门狗线程在扫描协程可能仍持锁 add 的情况下读 engine.results（ArrayList）
 * ⇒ ConcurrentModificationException。有界重试必然成功的依据（工单口径）：看门狗只在判
 * 「挂死」（SESSION_STALL_MS 无进展）后才并发读——挂死 = 扫描协程不再 add ⇒ 重试必然拿到
 * 稳定快照。retries 耗尽仍冲突 ⇒ 原样抛出，交给 exportNow 的 runCatching 兜底（绝不裸抛）。
 *
 * 测试口径：真 CME 的并发构造不稳定，单测用「第 N 次调用才成功」的桩模拟冲突；
 * 真机验证方式：DEBUG_SCAN_FLOW 注入挂死触发看门狗导出，观察 logcat BetterGI.ScanRunner
 * 无未捕获异常且导出完成（A13 工单允许的注释验证口径）。
 */
internal object ResultsSnapshot {

    /**
     * @param retries 总尝试次数（≥1）；每次 CME 后睡 [retryDelayMs] 再试
     *                （睡眠被打断则不吞中断标记、不再睡，立即补试剩余次数）
     */
    fun <T> withRetry(retries: Int = 3, retryDelayMs: Long = 50L, snapshot: () -> T): T {
        require(retries >= 1) { "retries must be >= 1" }
        var last: Throwable? = null
        repeat(retries) { attempt ->
            try {
                return snapshot()
            } catch (e: ConcurrentModificationException) {
                last = e
                if (attempt < retries - 1) {
                    try {
                        Thread.sleep(retryDelayMs)
                    } catch (_: InterruptedException) {
                        // 看门狗被 wd.interrupt() 收尾打断：保留中断标记，跳过睡眠立即补试
                        Thread.currentThread().interrupt()
                    }
                }
            }
        }
        val final = last ?: IllegalStateException("ResultsSnapshot retry loop exited without exception")
        throw final
    }
}

/**
 * A15（2026-09-30）：扫描**单飞**状态机（start/stop 原子化）。
 * 纯 JVM——只依赖 kotlinx.coroutines.Job，ScriptRunnerTest 用真实 Job + runBlocking 驱动。
 *
 * 事故依据：原 stop() `cancel(); currentJob = null` —— cancel 是异步的，置 null 后新
 * startScan 立即 launch ⇒ 新旧引擎并存：旧引擎在下一个取消检查点之前仍会点击/抓帧。
 *
 * 关键语义：Job.cancel() 后 isActive **立即**变 false（Job 进入 Cancelling 态）⇒ 不能用
 * isActive 判「还在收尾」；「未终结」必须用 !isCompleted（涵盖正常完成 / 取消完成两种终态）。
 * isActive 旧口径保留给 isRunning()（服务端 applySettings 观察用），收尾中的半格余量由
 * 本类的 unfinishedJob 判据兜住。
 */
internal class ScanLifecycle(private val stopJoinTimeoutMs: Long) {

    @Volatile
    private var job: Job? = null

    /** 旧口径：仅 Active 算在跑（Cancelling 收尾不算）。isRunning() 沿用。 */
    val isActive: Boolean get() = job?.isActive == true

    /**
     * 未终结的 job（Active 或 Cancelling 收尾中）；已终结 / 从未启动 ⇒ null。
     * 新启动必须据此拒绝并发 already_running 回执（A15）。
     */
    fun unfinishedJob(): Job? {
        val j = job ?: return null
        return if (j.isCompleted) null else j
    }

    /**
     * 装入新 job（与 stop/其他 start 并发时锁内单飞）。
     * @return null = 受理；非 null = 拒绝，携带当时未终结的旧 job
     *         （调用方取消自己刚起的 candidate 并发回执——竞态输家自取消）。
     */
    fun tryAttach(candidate: Job): Job? = synchronized(this) {
        val cur = job
        if (cur != null && !cur.isCompleted) return cur
        job = candidate
        null
    }

    sealed interface StopOutcome {
        /** 取消已在有界时间内真正终结：引用已清，可干净重启（无双引擎）。 */
        object Settled : StopOutcome

        /** 有界等待超时：旧协程仍在收尾。已放行（引用已清），残余窗口见 ScriptRunner.stop 注释。 */
        object LooseEnd : StopOutcome
    }

    /**
     * stop：cancel + 有界 join。等待期间引用保留 ⇒ unfinishedJob/tryAttach 全程拒绝新启动
     * （工单口径「期间拒绝新启动」）；join 完成或超时后统一清引用（放行）。
     */
    fun stop(): StopOutcome {
        val j = synchronized(this) { job } ?: return StopOutcome.Settled
        j.cancel()
        val joined = runCatching {
            runBlocking { withTimeout(stopJoinTimeoutMs) { j.join() } }
        }.isSuccess
        synchronized(this) {
            // 只清自己：join 期间若有并发 start 装入了新 job（busy 拒绝下理论上不可能），
            // 防御性判断避免误清新 job 的引用导致后续 stop 失灵
            if (job === j) job = null
        }
        return if (joined) StopOutcome.Settled else StopOutcome.LooseEnd
    }
}
