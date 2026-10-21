package com.bettergi.pocket.vd

import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * ★ #310/M5：VD 停更自守（MAA `StaleFrameGuard` 机制的后台模式移植）。
 *
 * ## 动机（与 ScriptRunner 死亡哨兵的分工）
 * 死亡哨兵（#309）管「游戏进程/前台」层面的死（游戏被杀/被顶号）——它判死即中止整轮。
 * 本 Guard 管「**画面**层面的死」：游戏进程活着（登录卡死、挂机黑屏、渲染停更）但 VD
 * 帧流不再出帧——引擎侧表现为 grabFresh 全部超时，OCR 对着死画面空转。处置 = **塞黑帧**
 * （把帧源缓存槽替换成全 0 快照）：下游（预览/fps/引擎取帧）看到的就是一块已知的黑，
 * 而不是无限旧的最后一帧——与 MAA 的 black frame 止血同思想。
 *
 * ## 机制（拍内决策，全部纯函数见 [GuardDecision]）
 * - 500ms 一拍（独立 HandlerThread——服务进程无主 Looper，KeepAlive 同款形态）；
 * - `frameGeneration` 变了 ⇒ **streak 清零，什么都不查**（零开销：活帧拍只读一个 volatile）；
 * - 帧停了 ⇒ 连续 [CONFIRM_POLLS] 拍（≈1s）确认后才查死因（防转场/加载的瞬态停帧）；
 * - 死因：`isAppOnDisplay(原神)` 判不出（null）⇒ **STILL_THERE 不计次不清零**（保守：
 *   判不出的状态不做黑帧替换，否则可能把正常画面盖黑）；Occupied（游戏还在 VD 上）⇒
 *   再查进程活性 `isGameAlive`（pidof），活着也 STILL_THERE（可能只是长时间静止画面），
 *   只有「画面停 + 游戏进程死」才publish 黑帧——**黑帧的语义是"确认没有活画面可等"**；
 * - TOCTOU：publish 返回 false（进入确认到执行之间帧又动了）⇒ 本次放弃，streak 清零；
 * - 恢复：游戏回来后 generation 又动了 ⇒ 黑帧被新帧自然覆盖，streak 从活帧拍起重新清零
 *   （无需显式"解除"状态——黑帧只活在缓存槽里，活帧一来它就没了）。
 *
 * ## 部署位置
 * 服务进程内（[com.bettergi.pocket.shizuku.BgiRemoteService.createDisplay] 后 start）：
 * `isAppOnDisplay`/`pidof` 都是进程内调用，**无 AIDL**；判死动作直接写服务进程持有的
 * 帧源缓存。
 */
class VdBlackFrameGuard(
    /** 帧源提供器（服务进程内 createDisplay 建的实例；null = VD 未建，拍内直接判 STILL_THERE）。 */
    private val frameSourceProvider: () -> VirtualDisplayFrameSource?,

    /** 游戏 task 是否还在 VD 上（true/false/null=判不出）。进程内 ActivityManager 查询。 */
    private val isAppOnDisplay: () -> Boolean?,

    /** 游戏主进程是否存活（服务进程内 pidof；异常按 true）。 */
    private val isGameAlive: () -> Boolean,

    /** 黑帧发布（写帧源缓存槽；返回 false = TOCTOU 拒绝/帧源不可用）。 */
    private val publishBlackFrame: () -> Boolean,

    /** 拍间隔 ms（测试注入小值）。 */
    private val intervalMs: Long = INTERVAL_MS,

    /** 连续停帧多少拍才查死因（默认 2 ≈ 1s，测试注入）。 */
    private val confirmPolls: Int = CONFIRM_POLLS,
) {

    companion object {
        private const val TAG = "BetterGI.VD"

        /** 拍间隔：500ms（MAA StaleFrameGuard 同档）。 */
        const val INTERVAL_MS: Long = 500L

        /** 连续停帧确认拍数：2 拍 ≈ 1s。 */
        const val CONFIRM_POLLS = 2
    }

    // ── 拍内状态（仅 guard 线程读写；@Volatile 供诊断读）──

    @Volatile
    private var lastSeenGen: Long = 0L

    /** 连续停帧拍数（活帧拍清零）。 */
    @Volatile
    var staleStreak: Int = 0
        private set

    /** 已发的黑帧计数（观测用）。 */
    @Volatile
    var blackFramesPublished: Int = 0
        private set

    // ── 调度（KeepAlive 同款 HandlerThread + postDelayed 自续）──

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    /** 启动周期调度（幂等：已跑则 no-op；kick 默认先拍一次）。 */
    @Synchronized
    fun start(kick: Boolean = true) {
        if (isRunning) {
            Log.d(TAG, "black-frame guard 已在跑，start 幂等忽略")
            return
        }
        val t = HandlerThread("BgiVdBlackGuard").also { it.start() }
        thread = t
        handler = Handler(t.looper)
        isRunning = true
        Log.i(TAG, "black-frame guard 启动（每 ${intervalMs}ms，确认 $confirmPolls 拍）")
        if (kick) scheduleTick(0L) else scheduleTick(intervalMs)
    }

    /** 停止调度并退出线程（幂等）。 */
    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        handler?.removeCallbacksAndMessages(null)
        handler = null
        thread?.quitSafely()
        thread = null
        Log.i(TAG, "black-frame guard 停止（黑帧共 $blackFramesPublished 次）")
    }

    private fun scheduleTick(delayMs: Long) {
        val h = handler ?: return
        h.postDelayed({
            if (!isRunning) return@postDelayed
            runCatching { tick() }
                .onFailure { Log.w(TAG, "guard tick 失败（下一拍继续）: ${it.message}") }
            scheduleTick(intervalMs)
        }, delayMs)
    }

    /** 单拍：读代数 → 决策 → 动作（决策本体在 [GuardDecision]，可单测）。 */
    private fun tick() {
        val gen = frameSourceProvider()?.frameGeneration() ?: 0L
        applyTick(gen)
    }

    /**
     * 单拍本体（代数入参化）：调度拍与单测共用同一条状态推进路径
     * （单测注入代数序列，不碰 HandlerThread）。
     */
    private fun applyTick(gen: Long) {
        val last = lastSeenGen
        lastSeenGen = gen
        val decision = GuardDecision.decide(
            generationChanged = gen != last,
            staleStreak = staleStreak,
            confirmPolls = confirmPolls,
        )
        when (decision) {
            GuardDecision.Action.RESET -> {
                staleStreak = 0
                // 活帧拍零开销：不查死因（帧在动 = 有活画面，无论黑帧与否）
            }
            GuardDecision.Action.STALE -> {
                staleStreak++ // 停帧计次，未满确认拍：只等下一拍
            }
            GuardDecision.Action.CONFIRM -> {
                // 已过确认拍：streak 封顶在 confirmPolls（诊断值有界），每拍复查死因
                // （判不出/游戏活 ⇒ STILL_THERE 不动作，下一拍自然重查——MAA 轮询语义）。
                staleStreak = (staleStreak + 1).coerceAtMost(confirmPolls)
                if (staleStreak >= confirmPolls) onConfirmed()
            }
        }
    }

    /** 仅供测试：注入代数走真实拍（决策+状态推进+死因编排），不经调度线程。 */
    fun debugTickForTest(generation: Long) = applyTick(generation)

    /** 已确认停帧：查死因（判不出 ⇒ 保守不动），Occupied 且进程死 ⇒ 塞黑帧。 */
    private fun onConfirmed() {
        val onDisplay = isAppOnDisplay()
        Log.d(TAG, "停帧 ${staleStreak} 拍确认，isAppOnDisplay=$onDisplay")
        if (onDisplay == null) {
            // 判不出 = STILL_THERE：不计次不清零（下一拍重新确认），绝不盲塞黑帧
            Log.d(TAG, "task 表判不出 ⇒ 保守：本轮不动（streak 保持）")
            return
        }
        if (!isGameAlive()) {
            val published = publishBlackFrame()
            if (published) {
                blackFramesPublished++
                Log.w(TAG, "画面停更 + 游戏进程已死 ⇒ 缓存槽替换为黑帧（第 $blackFramesPublished 次）")
            } else {
                Log.w(TAG, "黑帧发布被拒（TOCTOU：确认期间帧又动了 / 帧源不可用）⇒ streak 清零")
            }
            staleStreak = 0
        }
        // 游戏活着 ⇒ STILL_THERE（可能只是长时间静止画面），不动作、streak 保持
    }

    // ── ★ 纯决策函数（判定表单测锚点，无 Android 依赖）──

    /**
     * 单拍决策表（纯映射；streak 的推进由调用方按动作执行）：
     * - 帧动（generation 变了）⇒ [Action.RESET]（streak 清零、零开销直通）；
     * - 帧停、本拍计次后仍 < confirmPolls ⇒ [Action.STALE]（只累计，不查死因）；
     * - 帧停、本拍计次后 ≥ confirmPolls ⇒ [Action.CONFIRM]（查死因；持续停帧时
     *   每拍都落这里 = 周期性复查，MAA 轮询语义）。
     */
    object GuardDecision {
        enum class Action { RESET, STALE, CONFIRM }

        fun decide(generationChanged: Boolean, staleStreak: Int, confirmPolls: Int): Action =
            if (generationChanged) {
                Action.RESET
            } else if (staleStreak + 1 >= confirmPolls) {
                Action.CONFIRM
            } else {
                Action.STALE
            }
    }
}

/**
 * ★ #310/M5：**帧数差分** fps 估计器（服务进程内轻量轮询，1s 一采）。
 *
 * 与 ScriptRunner 帧率哨兵（#309b，15 窗占比）的分工：那边判「帧率持续异常」驱动告警，
 * 这里只回答「现在大概多少 fps」给 UI 显示——单值、无窗口、无告警语义。`-1` = 尚无样本
 * （调用方显示 "-- fps"）。
 *
 * 纯 JVM 类（时间与代数全部入参注入）⇒ 可单测；服务端轮询复用 KeepAlive 的
 * HandlerThread post（不新建线程，见 BgiRemoteService.createDisplay 的接线）。
 */
class FrameCountFpsEstimator(
    /** 采样间隔 ms（服务端 1s）。 */
    private val intervalMs: Long = SAMPLE_INTERVAL_MS,
) {
    companion object {
        const val SAMPLE_INTERVAL_MS = 1_000L
    }

    @Volatile
    private var lastGen: Long = Long.MIN_VALUE

    @Volatile
    private var lastWallMs: Long = Long.MIN_VALUE

    /** 最近一次估计（fps；无样本 = -1）。 */
    @Volatile
    private var lastFpsValue: Double = -1.0

    /**
     * 采一拍：入参是采样时刻的代数与墙钟（调用方从帧源取）。
     * 代数没变 ⇒ fps=0（帧停了也要记一拍，与 #309b 同理由）；变了 ⇒ 差分/时长。
     */
    fun sample(generation: Long, wallMs: Long) {
        val last = lastGen
        val lastWall = lastWallMs
        if (last == Long.MIN_VALUE) {
            // 首采：只有基准没有区间，不产值
        } else {
            val fps = if (generation != last && wallMs > lastWall) {
                (generation - last) * 1000.0 / (wallMs - lastWall)
            } else 0.0
            lastFpsValue = fps
        }
        lastGen = generation
        lastWallMs = wallMs
    }

    /** 最近估计（Double fps；无样本 = -1）。 */
    fun lastFps(): Double = lastFpsValue

    /** 采样间隔（调度方 postDelayed 用）。 */
    fun sampleIntervalMs(): Long = intervalMs

    /** 仅供测试：重置基准（模拟服务重启后的"无样本"态）。 */
    fun resetForTest() {
        lastGen = Long.MIN_VALUE
        lastWallMs = Long.MIN_VALUE
        lastFpsValue = -1.0
    }
}
