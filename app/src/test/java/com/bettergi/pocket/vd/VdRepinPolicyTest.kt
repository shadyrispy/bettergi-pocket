package com.bettergi.pocket.vd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★ #310/M2：防漂移重钉策略的单测（VdRepinPolicyTest）。
 *
 * `VirtualDisplayManager.shouldRepin` 是防漂移的**判定策略**纯函数——
 * 三级拉回的"要不要拉"由它决定，"怎么拉"（binder → am → intent）是执行层。
 * 本类锁 MAA 的语义：
 * - 在屏不动（不做无谓投放）；
 * - 不在屏重钉，最多 3 次（REPIN_MAX_ATTEMPTS）；
 * - ==3 次后放弃——MAA 原话语义："无限重试等于每 tick 投放启动请求"，
 *   死循环重钉会变成把 am start 当轮询用的 DoS，必须有个告警出口；
 * - 判不出（null）保守不动。
 */
class VdRepinPolicyTest {

    private fun shouldRepin(
        isOnDisplay: Boolean?,
        driftCount: Int,
        maxAttempts: Int = VirtualDisplayManager.REPIN_MAX_ATTEMPTS,
    ): Boolean = VirtualDisplayManager.shouldRepin(isOnDisplay, driftCount, maxAttempts)

    // ── 在屏不动 ────────────────────────────────────────────────────────

    @Test
    fun on_display_never_repins() {
        assertFalse(shouldRepin(true, 0))
        assertFalse(shouldRepin(true, 1))
        assertFalse(shouldRepin(true, 2))
        assertFalse(shouldRepin(true, 3))
        assertFalse(shouldRepin(true, 100))
    }

    // ── 不在屏重钉 ──────────────────────────────────────────────────────

    @Test
    fun off_display_repins_within_budget() {
        assertTrue(shouldRepin(false, 0))
        assertTrue(shouldRepin(false, 1))
        assertTrue(shouldRepin(false, 2))
    }

    @Test
    fun off_display_gives_up_at_max_attempts() {
        // driftCount == 3（已重钉 3 次仍不在屏）⇒ 拒绝，调用方建议告警
        assertFalse(shouldRepin(false, 3))
        assertFalse(shouldRepin(false, 4))
        assertFalse(shouldRepin(false, 99))
    }

    // ── 判不出保守不动 ──────────────────────────────────────────────────

    @Test
    fun unknown_state_never_repins() {
        assertFalse(shouldRepin(null, 0))
        assertFalse(shouldRepin(null, 1))
    }

    // ── 边界与常量 ──────────────────────────────────────────────────────

    @Test
    fun max_attempts_is_three() {
        // MAA 语义：3 级拉回 × 少量尝试；超过即"每 tick 投放启动请求"的退化
        assertEquals(3, VirtualDisplayManager.REPIN_MAX_ATTEMPTS)
    }

    @Test
    fun custom_budget_is_respected() {
        // 执行环境更宽容时可放宽（如首次拉起游戏冷启动慢）
        assertTrue(shouldRepin(false, 3, maxAttempts = 4))
        assertTrue(shouldRepin(false, 4, maxAttempts = 5))
        assertFalse(shouldRepin(false, 5, maxAttempts = 5))
    }

    @Test
    fun boundary_exactly_one_before_max() {
        val max = VirtualDisplayManager.REPIN_MAX_ATTEMPTS
        assertTrue(shouldRepin(false, max - 1))
        assertFalse(shouldRepin(false, max))
    }

    // ── 默认档常量 ──────────────────────────────────────────────────────

    @Test
    fun default_resolution_matches_maa_profile() {
        assertEquals(1280, VirtualDisplayManager.DEFAULT_W)
        assertEquals(720, VirtualDisplayManager.DEFAULT_H)
        assertEquals(160, VirtualDisplayManager.DEFAULT_DPI)
    }
}
