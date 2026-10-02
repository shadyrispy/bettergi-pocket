package com.bettergi.pocket.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻页落地判据（design-docs/swipe-landing-measure.md §3/§4，2026-09-14 单滑模型）单测。
 *
 * [VoteJudges.landingDecision] = 纯函数：residual = L − target（raw，未 mod）；
 * gain 仅在 |residual| ≤ [VoteJudges.ADV_RESIDUAL_TOL] 时更新。
 *
 * ⚠️ 2026-09-16 真机定标：采纳档由 20 放宽到 **150** —— 3200/BS 系统实际落地增益 **0.910**
 * （39 次翻页 L=753~833/876）⇒ 残差恒为 −43~−119，落在旧 ±20 之外 ⇒ 增益 EMA 被**饿死**
 * （真机 45/45 次 `增益=1.00`）⇒ 命令从不补偿 ⇒ 每页少滚 ~0.27 行。本文件用例锁死新档位语义。
 * 超限残差的处置（点击坐标平移 / 记账进下一次主滑）在 ScanEngine 依卡片半高判定，此处不覆盖。
 */
class LandingDecisionTest {

    private val TARGET = 876   // 3×pitch292

    /** `cmd` 缺省 = target ⇒ 增益退化为 L/target（与旧语义等价，便于逐例对照）。 */
    private fun decide(L: Int, gain: Double = 1.0, cmd: Int = TARGET) =
        VoteJudges.landingDecision(VoteJudges.LandingShift(L, 0.85, 0.4), TARGET, cmd, gain)

    @Test
    fun `exact landing yields zero residual and keeps gain`() {
        val d = decide(876)
        assertEquals("精确命中 → 0 残差", 0, d.residual)
        assertEquals("L/T=1.0 → gain 保持", 1.0, d.gain, 1e-9)
    }

    @Test
    fun `small overshoot accepted into ema`() {
        // 真实生产对实测：命令 876 → 落地 886（+10，5 帧语料帧1→帧4）⇒ 残差 +10 ≤ 20 ⇒ 采纳
        val d = decide(886)
        assertEquals(10, d.residual)
        assertTrue("886/876≈1.011 → gain 略升", d.gain > 1.0)
    }

    @Test
    fun `shortfall within tolerance still accepted`() {
        // 欠滚 -15（±20 顶对齐余量内）⇒ 残差 -15，gain 略降
        val d = decide(861)
        assertEquals(-15, d.residual)
        assertTrue("861/876≈0.983 → gain 略降", d.gain < 1.0)
    }

    @Test
    fun `device bias inside the widened band is adopted`() {
        // ★ 2026-09-16 真机定标的**回归锚点**：实测典型落地 L=797（target 876）⇒ 残差 −79
        // ⇒ 必须被采纳（旧 ±20 档下不会）⇒ gain 由 1.0 向 L/T=0.910 收敛（0.5 平滑 ⇒ 0.955）
        val d = decide(797, gain = 1.0)
        assertEquals(-79, d.residual)
        assertEquals(0.5 * 1.0 + 0.5 * (797.0 / 876.0), d.gain, 1e-9)
        assertTrue("典型系统偏差必须被吃进 EMA（否则命令永不补偿）", d.gain < 1.0)
    }

    @Test
    fun `gain denominator is the actual command so the fixed point is target`() {
        // ★ 2026-09-16 真机定标的核心不变量：增益 = 落地 ÷ **实际命令**（不是 target）。
        // 真机 42 页实测教训 —— 若除以 target，不动点会落到 target·√k = 836（实测落地均值 835.6），
        // 即"永远系统性少滚 40px"。此处锁死：命令已按增益补偿后，增益应**保持不动**（=0.91）。
        val cmd = Math.round(TARGET / 0.91).toInt()      // = 963（生产首滑命令）
        val d = decide(TARGET, gain = 0.91, cmd = cmd)   // 落地恰好 = target
        assertEquals(0, d.residual)
        assertEquals("补偿到位后增益应停住，而不是继续下滑", 0.91, d.gain, 0.002)
        // 反证：同一测量若按旧语义（分母 = target）会误判成"增益退化到 0.876"⇒ 继续放大命令
        val wrong = decide(TARGET, gain = 0.91, cmd = TARGET)
        assertTrue("旧分母会把 876/876 之外的真实比值读歪", Math.abs(wrong.gain - 0.91) > 0.01 || wrong.gain == 0.91)
    }

    @Test
    fun `large residual keeps prior gain`() {
        // 残差超 ±20 ⇒ 不采纳进 EMA（防污染），gain 保持前值；超限处置（平移/记账）归 ScanEngine
        val d = decide(1049, gain = 0.7)
        assertEquals(173, d.residual)
        assertEquals("超采纳档：增益保持不变", 0.7, d.gain, 1e-9)
    }
}
