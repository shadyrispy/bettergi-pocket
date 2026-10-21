package com.bettergi.pocket.vd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★ #310/M5：VdBlackFrameGuard 的**纯逻辑**判定表单测（JVM，无 Android 依赖）。
 *
 * 两块：
 * 1. [VdBlackFrameGuard.GuardDecision.decide] —— 拍内决策表（帧动清零 / 停帧累计 /
 *    判定拍确认）；状态推进（streak 清零/累计/TOCTOU 拒绝后清零）经 [Harness] 走
 *    真实 Guard 实例的 [VdBlackFrameGuard.debugTickForTest]（决策与状态推进同源，
 *    不测 HandlerThread 调度本体——那是 KeepAlive 同款形态，设备上验证）。
 * 2. [FrameCountFpsEstimator] —— 差分估值（首采无值 / 正常差分 / 帧停记 0 / 回绕防护）。
 */
class VdBlackFrameGuardTest {

    // ── 1. 决策表（纯函数）──────────────────────────────

    @Test
    fun decide_generation_changed_resets() {
        assertEquals(
            VdBlackFrameGuard.GuardDecision.Action.RESET,
            VdBlackFrameGuard.GuardDecision.decide(
                generationChanged = true, staleStreak = 5, confirmPolls = 2,
            ),
        )
        // streak 已经很大时帧一动也必须清零（活帧拍零开销直通）
        assertEquals(
            VdBlackFrameGuard.GuardDecision.Action.RESET,
            VdBlackFrameGuard.GuardDecision.decide(
                generationChanged = true, staleStreak = 999, confirmPolls = 2,
            ),
        )
    }

    @Test
    fun decide_stale_but_below_confirm_is_stale() {
        assertEquals(
            VdBlackFrameGuard.GuardDecision.Action.STALE,
            VdBlackFrameGuard.GuardDecision.decide(
                generationChanged = false, staleStreak = 0, confirmPolls = 2,
            ),
        )
    }

    @Test
    fun decide_stale_reaching_confirm_polls_confirms() {
        assertEquals(
            VdBlackFrameGuard.GuardDecision.Action.CONFIRM,
            VdBlackFrameGuard.GuardDecision.decide(
                generationChanged = false, staleStreak = 1, confirmPolls = 2,
            ),
        )
        assertEquals(
            VdBlackFrameGuard.GuardDecision.Action.CONFIRM,
            VdBlackFrameGuard.GuardDecision.decide(
                generationChanged = false, staleStreak = 2, confirmPolls = 2,
            ),
        )
    }

    // ── 2. 状态推进（经 Harness 走真实例）──────────────

    /**
     * Guard 的可编程外壳：帧源代数 / 在屏判定 / 进程活性 / 黑帧发布全部可控，
     * 走真实例的 debugTickForTest（决策 + 状态推进 + 死因查询的**真实编排**）。
     */
    private class Harness {
        var generation: Long = 100L
        var onDisplay: Boolean? = null
        var gameAlive: Boolean = true
        var publishResult: Boolean = true
        var publishCalls: Int = 0

        val guard = VdBlackFrameGuard(
            frameSourceProvider = { null }, // 代数经 debugTickForTest 注入，不走真帧源
            isAppOnDisplay = { onDisplay },
            isGameAlive = { gameAlive },
            publishBlackFrame = { publishCalls++; publishResult },
        )

        fun tick(gen: Long = generation) = guard.debugTickForTest(gen)
    }

    @Test
    fun alive_frames_keep_streak_at_zero_and_never_query_death() {
        val h = Harness()
        h.tick(gen = 100)
        h.tick(gen = 200)
        h.tick(gen = 300)
        assertEquals(0, h.guard.staleStreak)
        assertEquals(0, h.publishCalls) // 活帧拍零开销：死因查询都不该发生
    }

    @Test
    fun stale_for_two_ticks_with_dead_game_publishes_black_frame() {
        val h = Harness()
        h.onDisplay = false
        h.gameAlive = false
        h.tick(gen = 100) // 基准（RESET：首拍 lastSeenGen=0 ≠ 100，按活帧处理）
        h.tick(gen = 100) // 停帧第 1 拍（STALE，未满确认拍）
        assertEquals(1, h.guard.staleStreak)
        assertEquals(0, h.publishCalls)
        h.tick(gen = 100) // 停帧第 2 拍（CONFIRM）：在屏 false + 进程死 ⇒ 发布
        assertEquals(1, h.publishCalls)
        assertEquals(1, h.guard.blackFramesPublished)
    }

    /**
     * **判不出 = 保守 STILL_THERE**：isAppOnDisplay 返回 null 时，无论进程死活，
     * 不发布黑帧、streak 不清零也不异常推进（下一拍重新确认）。
     */
    @Test
    fun undeterminable_display_is_conservative_no_publish() {
        val h = Harness()
        h.onDisplay = null
        h.gameAlive = false // 进程死了也不许动手——在屏判不出时不信单一代数证据
        h.tick(gen = 100)
        h.tick(gen = 100)
        h.tick(gen = 100)
        h.tick(gen = 100)
        assertEquals(0, h.publishCalls)
        assertTrue(h.guard.blackFramesPublished == 0)
    }

    @Test
    fun occupied_display_with_alive_game_never_publishes() {
        val h = Harness()
        h.onDisplay = true
        h.gameAlive = true
        repeat(6) { h.tick(gen = if (it == 0) 100 else 100) }
        assertEquals(0, h.publishCalls) // 静止画面（游戏活着）：黑帧永不发布
    }

    @Test
    fun frame_moving_again_resets_streak_after_stale_period() {
        val h = Harness()
        h.tick(gen = 100)
        h.tick(gen = 100) // 停 1 拍
        h.tick(gen = 500) // 帧又动了 ⇒ 清零
        assertEquals(0, h.guard.staleStreak)
    }

    @Test
    fun toctou_rejection_resets_streak_and_counts_no_black() {
        val h = Harness()
        h.onDisplay = false
        h.gameAlive = false
        h.tick(gen = 100)
        h.tick(gen = 100)
        h.publishResult = false // publishBlackFrame 返回 false = 进入确认后帧又动了
        h.tick(gen = 100)
        assertEquals(1, h.publishCalls)
        assertEquals(0, h.guard.blackFramesPublished)
        assertEquals(0, h.guard.staleStreak) // TOCTOU 拒绝 ⇒ streak 清零重新确认
    }

    // ── 3. FrameCountFpsEstimator ──────────────────────

    @Test
    fun estimator_first_sample_yields_no_value() {
        val e = FrameCountFpsEstimator()
        e.sample(generation = 1_000_000L, wallMs = 1_000L)
        assertEquals(-1.0, e.lastFps(), 0.0) // 首采只有基准，无区间不产值
    }

    @Test
    fun estimator_differential_counts_frames_per_second() {
        val e = FrameCountFpsEstimator()
        e.sample(generation = 1_000L, wallMs = 1_000L)
        e.sample(generation = 1_060L, wallMs = 2_000L) // 60 帧 / 1s
        assertEquals(60.0, e.lastFps(), 0.001)
        e.sample(generation = 1_090L, wallMs = 3_000L) // 30 帧 / 1s
        assertEquals(30.0, e.lastFps(), 0.001)
    }

    @Test
    fun estimator_stalled_generation_reports_zero_fps() {
        val e = FrameCountFpsEstimator()
        e.sample(generation = 1_000L, wallMs = 1_000L)
        e.sample(generation = 1_000L, wallMs = 2_000L) // 代数没变 ⇒ 0（帧停了也要记一拍）
        assertEquals(0.0, e.lastFps(), 0.0)
        e.sample(generation = 1_000L, wallMs = 3_000L)
        assertEquals(0.0, e.lastFps(), 0.0)
    }

    @Test
    fun estimator_zero_elapsed_wall_is_guarded() {
        val e = FrameCountFpsEstimator()
        e.sample(generation = 1_000L, wallMs = 2_000L)
        e.sample(generation = 1_030L, wallMs = 2_000L) // wallMs 没走：不除零，按 0 记
        assertEquals(0.0, e.lastFps(), 0.0)
    }

    @Test
    fun estimator_reset_returns_to_no_sample() {
        val e = FrameCountFpsEstimator()
        e.sample(generation = 1_000L, wallMs = 1_000L)
        e.sample(generation = 1_060L, wallMs = 2_000L)
        e.resetForTest()
        assertEquals(-1.0, e.lastFps(), 0.0)
        e.sample(generation = 2_000L, wallMs = 9_000L)
        assertEquals(-1.0, e.lastFps(), 0.0) // reset 后首采同样不产值
    }

    // ── 4. VirtualDisplayFrameSource.publishBlackFrame 的 TOCTOU 语义 ──
    // （真帧源硬依赖 ImageReader/HandlerThread，JVM 造不出 ⇒ 决策同构的纯逻辑在
    //   GuardDecision/estimator 里已锁；这里的表只锁「服务侧 TOCTOU 拒绝"时的
    //   guard 侧行为」——即 toctou_rejection 用例。黑帧发布本身的真机验证归设备冒烟。）

    @Test
    fun decision_table_covers_spec_minimum_four_rows() {
        // 验收 2 的判定表 ≥4 条锚点（帧动清零 / 停帧 STALE / 满 2 拍 CONFIRM / 保守判不出）：
        // 前三条上面逐条锁了；第四条（判不出保守）在 undeterminable_display_is_conservative_no_publish。
        // 这里集中断言一遍映射关系，防未来有人把 CONFIRM 提前到第 1 拍。
        val d = VdBlackFrameGuard.GuardDecision::decide
        assertEquals(VdBlackFrameGuard.GuardDecision.Action.RESET, d(true, 0, 2))
        assertEquals(VdBlackFrameGuard.GuardDecision.Action.STALE, d(false, 0, 2))
        assertEquals(VdBlackFrameGuard.GuardDecision.Action.STALE, d(false, 0, 3))
        assertEquals(VdBlackFrameGuard.GuardDecision.Action.CONFIRM, d(false, 1, 2))
        assertEquals(VdBlackFrameGuard.GuardDecision.Action.CONFIRM, d(false, 2, 3))
        assertFalse(VdBlackFrameGuard.GuardDecision.Action.CONFIRM == d(false, 0, 2))
    }
}
