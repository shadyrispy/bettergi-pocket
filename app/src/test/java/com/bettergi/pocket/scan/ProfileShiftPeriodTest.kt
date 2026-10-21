package com.bettergi.pocket.scan

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **周期歧义回归**（2026-09-13，用户指出）：网格卡片行距 204px **周期性** ⇒
 * [VoteJudges.profileShift] 从 s=0 向上搜、用严格大于取最大 ⇒ **并列时永远取最小周期对齐候选**
 * ⇒ 真实位移 526px（=2.58 行距）会被报成 118px（526−408，少算两行）⇒ 闭环误判"没滚够" ⇒ 多补滑。
 *
 * 本测试用合成行剖面（周期 204 + 每块轻微差异，模拟"卡片图案不完全相同"）锁死该行为。
 */
class ProfileShiftPeriodTest {

    /** 构造周期 204 的行剖面，每个周期块加一点点差异（模拟真实卡片的细微不同）。 */
    private fun periodicProfile(n: Int, period: Int, seed: Int): DoubleArray {
        val out = DoubleArray(n)
        var blockSeed = seed * 7919
        var i = 0
        while (i < n) {
            blockSeed = blockSeed * 1103515245 + 12345
            val blockBias = ((blockSeed ushr 16) % 7) - 3.0 // 每块 ±3 的整体偏置
            for (k in 0 until period) {
                if (i >= n) break
                // 行内形状：亮暗台阶（模拟卡片/间隙）
                val shape = if (k < 24) 40.0 else if (k < 60) 130.0 else 90.0
                out[i++] = shape + blockBias
            }
        }
        return out
    }

    @Test
    fun `true shift 526 (=2 periods + 118) must not be reported as 118`() {
        val period = 204
        val n = 900
        val truth = 526
        val before = periodicProfile(n, period, 1)
        // after = before 右移 truth（内容上移 ⇒ after[i] = before[i + truth]）
        val after = DoubleArray(n) { i -> before[Math.min(n - 1, i + truth)] }
        val (s, c) = VoteJudges.profileShift(before, after)
        println("profileShift 返回=$s score=%.3f（真值=$truth）".format(c))
        // 老实现（无期望先验）在这里大概率返回 526−2×204=118 —— 该断言用于**暴露**问题，
        // 修好（带期望先验）后应返回 526。
        assertTrue(
            "profileShift 报了 $s，真值 $truth —— 周期歧义少算了 ${truth - s}px（${(truth - s) / period} 行）",
            kotlin.math.abs(s - truth) <= 8,
        )
        assertTrue("score 应足够高", c >= 0.9)
    }

    @Test
    fun `expected prior disambiguates the period`() {
        val period = 204
        val n = 900
        val truth = 526
        val before = periodicProfile(n, period, 1)
        val after = DoubleArray(n) { i -> before[Math.min(n - 1, i + truth)] }
        val (s, c) = VoteJudges.profileShift(before, after, expectedPx = 612)
        println("带期望先验(612) 返回=$s score=%.3f".format(c))
        assertTrue(kotlin.math.abs(s - truth) <= 8)
        assertTrue(c >= 0.9)
    }
}
