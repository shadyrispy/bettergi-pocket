package com.bettergi.pocket.scan

import nu.pattern.OpenCV
import org.junit.Assert.assertEquals

import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import java.util.Random

/**
 * 翻页**落地位移**条带匹配单测（[VoteJudges.landingShift]，
 * 方案 design-docs/swipe-landing-measure.md，回测语料 dsl/verify/_swipe_progress_3200/）。
 *
 * 与 [ProfileShiftTest] 的分工：那条路测 1D 行剖面（已证被卡片模板周期性污染 ⇒ 生产弃用为主判据），
 * 本处测**第4行可见条带的 2D 模板匹配**——网格容器只在 y∈[276,1185] 渲染内容、3-pitch 翻页后
 * F0 的 R1-R3 全部滑入顶部隐藏区，唯一幸存共享内容 = R4 可见条（y[1172,1235)，cols4-7）。
 *
 * 合成帧构造原则（对齐 5 帧语料的真实证据）：
 * - 条带内容 = 每行独特幅值的卡片状纹理（孪生卡场景除外）⇒ 平移后可唯一匹配；
 * - 真值链：1/2/3-pitch 与 886（3p+10 过冲）均须精确测出；
 * - 帧内容下移出窗（真值 1049，条带落点 y123 进渐隐区）⇒ score 低于门限 ⇒ null。
 */
class LandingShiftTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            OpenCV.loadLocally()
            Mat(2, 2, CvType.CV_8UC1, Scalar(0.0)).release()
        }

        /** 3200×1440 基准：条带 x 窗（cols4-7）/ R4 可见条 / 搜索窗（含未滚动原位）。 */
        private const val X0 = 1200
        private const val X1 = 2080
        private const val BAND_TOP = 1172
        private const val BAND_H = 63
        private const val SEARCH_TOP = 0
        private const val SEARCH_BOTTOM = 1235
        private const val PITCH = 292
    }

    /**
     * 生成一帧"卡片行"纹理的灰度列块：每行独特纹理（⚠️ 不能只变幅值——TM_CCOEFF_NORMED
     * 对仿射 a·x+b 不变，纯幅值差异的行会互相完美相关，等于全局孪生卡 ⇒ 被主次峰差守卫拒绝）。
     * 每行用随机相位 + 随机频率的横向正弦叠加，行间去相关。
     */
    private fun frame(seed: Long, rowShift: Int = 0): Mat {
        val m = Mat(SEARCH_BOTTOM, X1 - X0, CvType.CV_8UC1, Scalar(120.0))
        val rnd = Random(seed)
        val nRows = (SEARCH_BOTTOM + 16 * PITCH) / PITCH + 2
        val amp = DoubleArray(nRows) { 50.0 + rnd.nextDouble() * 130.0 }
        val phase = DoubleArray(nRows) { rnd.nextDouble() * 2 * Math.PI }
        val freq = IntArray(nRows) { 61 + rnd.nextInt(37) }
        val rowBuf = ByteArray(m.cols())
        for (y in 0 until SEARCH_BOTTOM) {
            val row = (y + rowShift) / PITCH
            val f = freq[row]
            for (x in 0 until m.cols()) {
                val v = amp[row] * (0.7 + 0.3 * Math.sin(2.0 * Math.PI * (x % f) / f + phase[row])) +
                    (x % 13) * 1.4 + (y % 7) * 0.8
                rowBuf[x] = v.coerceIn(0.0, 255.0).toInt().toByte()
            }
            m.put(y, 0, rowBuf)
        }
        return m
    }

    /** 从"翻页前帧"提取条带模板：R4 可见条 = y[BAND_TOP, BAND_TOP+BAND_H)。 */
    private fun bandOf(f: Mat): Mat {
        val sub = Mat(f, org.opencv.core.Rect(0, BAND_TOP, f.cols(), BAND_H))
        val out = sub.clone()
        sub.release()
        return out
    }

    /** 内容上移 [s] px 后的帧：after[y] = before[y+s]，下方补新内容。 */
    private fun shifted(before: Mat, s: Int, fillSeed: Long = 99L): Mat {
        val after = Mat(before.rows(), before.cols(), CvType.CV_8UC1)
        val fill = frame(fillSeed, rowShift = 5 * PITCH) // 语义上"新进入的行"，纹理不同即可
        for (y in 0 until before.rows()) {
            if (y + s < before.rows()) {
                before.row(y + s).copyTo(after.row(y))
            } else {
                fill.row(y + s - before.rows()).copyTo(after.row(y))
            }
        }
        fill.release()
        return after
    }

    private fun measure(band: Mat, search: Mat): VoteJudges.LandingResult =
        VoteJudges.landingShift(band, search, BAND_TOP - SEARCH_TOP)

    private fun ok(r: VoteJudges.LandingResult): VoteJudges.LandingShift =
        (r as VoteJudges.LandingResult.Ok).shift

    @Test
    fun `measures exact one-pitch landing`() {
        val f0 = frame(1L)
        val band = bandOf(f0)
        val r = measure(band, shifted(f0, PITCH))
        assertTrue("1-pitch 落地应可测", r is VoteJudges.LandingResult.Ok)
        val s = ok(r)
        assertEquals(PITCH, s.dy)
        assertTrue("score=${s.score}", s.score >= VoteJudges.LANDING_MIN_SCORE)
        assertTrue("peakGap=${s.peakGap}", s.peakGap >= VoteJudges.LANDING_MIN_PEAK_GAP)
        f0.release(); band.release()
    }

    @Test
    fun `measures exact three-pitch landing`() {
        val f0 = frame(2L)
        val band = bandOf(f0)
        val r = measure(band, shifted(f0, 3 * PITCH))
        assertTrue(r is VoteJudges.LandingResult.Ok)
        val s = ok(r)
        assertEquals(3 * PITCH, s.dy)
        assertTrue("score=${s.score}", s.score >= VoteJudges.LANDING_MIN_SCORE)
        f0.release(); band.release()
    }

    @Test
    fun `measures production landing with overshoot`() {
        // 5 帧语料真值：生产翻页对实测落地 886（3p + 10 过冲）⇒ 必须精确测出（闭环靠它纠偏）
        val f0 = frame(3L)
        val band = bandOf(f0)
        val r = measure(band, shifted(f0, 886))
        assertTrue(r is VoteJudges.LandingResult.Ok)
        assertEquals(886, ok(r).dy)
        f0.release(); band.release()
    }

    @Test
    fun `zero landing reports zero`() {
        // 滑动未生效（列表到底钳制）⇒ 条带停在原位 ⇒ dy=0 且高置信（闭环据此重试/判到底）
        val f0 = frame(4L)
        val band = bandOf(f0)
        val r = measure(band, f0.clone())
        assertTrue(r is VoteJudges.LandingResult.Ok)
        val s = ok(r)
        assertEquals(0, s.dy)
        assertTrue("score=${s.score}", s.score >= VoteJudges.LANDING_MIN_SCORE)
        f0.release(); band.release()
    }

    @Test
    fun `twin band copies are rejected by peak gap`() {
        // 孪生卡密集页：条带内容在正确落点与落点+1行各出现一次（同套同部位同等级的卡片）
        // ⇒ 两峰并列 ⇒ peakGap < 门限 ⇒ 必须**拒绝测量**（漏纠安全，误纠会整行跳过）
        val f0 = frame(5L)
        val band = bandOf(f0)
        val after = shifted(f0, 3 * PITCH)
        // 在 3p 落点 +1 行 处再贴一份条带
        val sub = Mat(after, org.opencv.core.Rect(0, BAND_TOP - 3 * PITCH + PITCH, after.cols(), BAND_H))
        band.copyTo(sub)
        sub.release()
        val r = measure(band, after)
        assertTrue("孪生歧义应拒绝（r=$r）", r is VoteJudges.LandingResult.Reject)
        f0.release(); band.release(); after.release()
    }

    // ---------- ★ 期望落点先验（2026-09-16：真机 fpband 覆盖 4/6 → 目标 6/6） ----------

    /** 先验半窗（生产公式 = round(rowPitch × LANDING_PRIOR_HALF_RATIO)）。 */
    private val PRIOR_HALF = Math.round(PITCH * VoteJudges.LANDING_PRIOR_HALF_RATIO).toInt()

    @Test
    fun `prior window means target plus minus 0_85 row`() {
        // 锁定常量语义：0.85 × 292 = 248 ⇒ 窗 = target ± 248，恰好排除 ±1 行(292) 的孪生峰、
        // 又能容纳实测落地范围（3200 真机 768~1080 = target −108~+204）
        assertEquals(248, PRIOR_HALF)
        assertTrue("先验窗必须排除 ±1 行孪生峰", PRIOR_HALF < PITCH)
        assertTrue("先验窗必须容纳实测最大过冲 +204", PRIOR_HALF > 204)
    }

    @Test
    fun `expected-landing prior disambiguates a one-row twin`() {
        // 同套密集卡页：正确落点(3p) 与 +1 行(2p) 各有一份相同条带 ⇒ 无先验被 peakGap 拒
        // ⇒ 加先验后 +1 行孪生峰被排除在窗外 ⇒ 必须能命中 3p（这是本次覆盖率的正解）
        val f0 = frame(5L)
        val band = bandOf(f0)
        val after = shifted(f0, 3 * PITCH)
        val sub = Mat(after, org.opencv.core.Rect(0, BAND_TOP - 3 * PITCH + PITCH, after.cols(), BAND_H))
        band.copyTo(sub)
        sub.release()
        // 无先验 ⇒ 歧义 ⇒ 拒（安全）
        assertTrue(
            "无先验应拒（孪生歧义）",
            measure(band, after) is VoteJudges.LandingResult.Reject,
        )
        // 有先验 ⇒ 窗外的 2p 孪生峰被屏蔽 ⇒ 命中 3p
        val r = VoteJudges.landingShift(band, after, BAND_TOP - SEARCH_TOP, 3 * PITCH, PRIOR_HALF)
        assertTrue("加先验后应命中（r=$r）", r is VoteJudges.LandingResult.Ok)
        assertEquals(3 * PITCH, (r as VoteJudges.LandingResult.Ok).shift.dy)
        f0.release(); band.release(); after.release()
    }

    @Test
    fun `prior keeps the production hits`() {
        // 回归：先验只收窄搜索区，不改判据 ⇒ 生产落点(≈3p) 仍应精确命中
        for (truth in listOf(3 * PITCH, 886)) {
            val f0 = frame(11L)
            val band = bandOf(f0)
            val r = VoteJudges.landingShift(band, shifted(f0, truth), BAND_TOP - SEARCH_TOP, 3 * PITCH, PRIOR_HALF)
            val ok = r as? VoteJudges.LandingResult.Ok ?: error("真值 $truth 应命中，实际 r=$r")
            assertEquals(truth, ok.shift.dy)
            f0.release(); band.release()
        }
    }

    @Test
    fun `landing far outside the prior window is rejected`() {
        // 真值 2 行(584) 落在窗[3p−248, 3p+248] 之外 ⇒ 窗内只有低分假峰 ⇒ score 门拒（安全方向：
        // 手势只可能落在 target±0.85 行，窗外的"读数"必定是混叠/内容更换）
        val f0 = frame(2L)
        val band = bandOf(f0)
        val r = VoteJudges.landingShift(
            band, shifted(f0, 2 * PITCH), BAND_TOP - SEARCH_TOP, 3 * PITCH, PRIOR_HALF,
        )
        assertTrue("窗外落点应被拒（r=$r）", r is VoteJudges.LandingResult.Reject)
        f0.release(); band.release()
    }

    @Test
    fun `scrolled-out band returns null`() {
        // 过冲越界（真值 1049，条带落点 y123 进顶部渐隐区，5 帧语料实测 score≈0.36 < 门限）
        // ⇒ 测不到 ⇒ Reject ⇒ 调用方走"不可测"分支（不更增益、回退特征锁仅平移；
        //   「没送达」另由 L<半行距 判定 ⇒ 交外层指纹 → 到底重发。相位校正已于 2026-09-14 废除）
        val f0 = frame(6L)
        val band = bandOf(f0)
        val unrelated = frame(7L, rowShift = 9 * PITCH) // 完全不同的行内容
        assertTrue(measure(band, unrelated) is VoteJudges.LandingResult.Reject)
        f0.release(); band.release(); unrelated.release()
    }

    @Test
    fun `unrelated content never crosses score gate`() {
        // 真实语料对照：帧5 顶行 vs 帧4 R1 的分段相关在渐隐区只有 0.12~0.15；
        // 合成"内容更换"帧（非平移关系）必须同样过不了 score 门限
        val f0 = frame(8L)
        val band = bandOf(f0)
        val other = frame(9L, rowShift = 0) // 同 seed 族但行幅值不同 ⇒ 非平移
        // 造非平移：把 other 的行序打乱
        val shuffled = other.clone()
        for (y in 0 until shuffled.rows() step PITCH / 2) {
            val src = (y * 7 + 3) % other.rows()
            other.row(src).copyTo(shuffled.row(y))
        }
        assertTrue(measure(band, shuffled) is VoteJudges.LandingResult.Reject)
        f0.release(); band.release(); other.release(); shuffled.release()
    }
}
