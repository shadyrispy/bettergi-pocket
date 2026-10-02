package com.bettergi.pocket.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * 翻页**落地位移**探针的纯函数单测（[VoteJudges.profileShift] / [VoteJudges.PROFILE_SHIFT_MIN_SCORE]）。
 *
 * 为什么单独测：闭环（`ScanEngine` 的 `advance` 段）靠这个探针把「指令距离 → 实际落地」从开环变闭环，
 * 而干跑单测的合成帧**页与页之间不是平移关系**（只加一条灰带）⇒ 干跑里必须关掉闭环
 * （见 `ScanEngineDryRunTest.runEngine` 的 `advloop=0`）。闭环的正确性只能在**这里**用
 * "构造已知平移的剖面"来覆盖。
 *
 * 剖面 = 网格区逐行亮度（1 帧像素/行），故"位移"就是**帧像素**，与手势命令同量纲。
 */
class ProfileShiftTest {

    /** 造一条"有行距周期、但每行幅值独特"的剖面 ⇒ 平移 s 后能唯一匹配（不会锁到周期的倍数上）。 */
    private fun baseProfile(n: Int = 840, pitch: Int = 204): DoubleArray {
        val rnd = Random(42)
        val rowAmp = DoubleArray(n / pitch + 2) { 40.0 + rnd.nextDouble() * 60.0 }
        return DoubleArray(n) { i ->
            val row = i / pitch
            rowAmp[row] * (0.6 + 0.4 * Math.sin(2.0 * Math.PI * (i % pitch) / pitch))
        }
    }

    /** 内容**上移** [s] px（列表向前推进）后的剖面：after[i] = before[i + s]。 */
    private fun shifted(before: DoubleArray, s: Int): DoubleArray {
        val mean = before.average()
        return DoubleArray(before.size) { i -> if (i + s < before.size) before[i + s] else mean }
    }

    private val pitch = 204

    @Test
    fun `measures an exact one-page advance`() {
        val before = baseProfile()
        val after = shifted(before, pitch * 3) // 3 行 = 一页推进
        val (dy, score) = VoteJudges.profileShift(before, after)
        assertEquals("应精确测出 3 行位移", pitch * 3, dy)
        assertTrue("真实平移的 score 应 ≥ 门限，实测 $score", score >= VoteJudges.PROFILE_SHIFT_MIN_SCORE)
    }

    @Test
    fun `measures a sub-row shortfall`() {
        // 实测最常见的情形：一页只滚了 2.2 行 ⇒ 闭环要据此补滑 ~0.8 行
        val before = baseProfile()
        val want = 449 // ≈2.2 行
        val (dy, score) = VoteJudges.profileShift(before, shifted(before, want))
        assertEquals(want, dy)
        assertTrue("score=$score", score >= VoteJudges.PROFILE_SHIFT_MIN_SCORE)
    }

    @Test
    fun `measures a tiny movement`() {
        // 后段退化时可能只落地几十像素 —— 必须**测得出来**（否则闭环会误以为已经到位）
        val before = baseProfile()
        val want = 37
        val (dy, score) = VoteJudges.profileShift(before, shifted(before, want))
        assertEquals(want, dy)
        assertTrue("score=$score", score >= VoteJudges.PROFILE_SHIFT_MIN_SCORE)
    }

    @Test
    fun `identical profiles report zero shift`() {
        val before = baseProfile()
        val (dy, _) = VoteJudges.profileShift(before, before.copyOf())
        assertEquals(0, dy)
    }

    @Test
    fun `unrelated profiles fall below the trust threshold`() {
        // 负向用例：内容换了（不是平移）⇒ score 必须低于门限，调用方据此**放弃**这次测量（不要拿 dy 纠偏）
        val before = baseProfile()
        val rnd = Random(7)
        val after = DoubleArray(before.size) { rnd.nextDouble() * 100.0 }
        val (_, score) = VoteJudges.profileShift(before, after)
        assertTrue("无关内容的 score 应 < 门限 ${VoteJudges.PROFILE_SHIFT_MIN_SCORE}，实测 $score",
            score < VoteJudges.PROFILE_SHIFT_MIN_SCORE)
    }
}
