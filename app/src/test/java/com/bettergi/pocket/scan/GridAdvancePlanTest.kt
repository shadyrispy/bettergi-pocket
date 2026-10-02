package com.bettergi.pocket.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻页**主滑规划 + 记账**纯函数单测（2026-09-16 补，审计 P0-2/P1-2 的回归防线）。
 *
 * 背景：`pageDrift` 记账原先内联在 `ScanEngine` 页循环里，且**声明在循环内** ⇒ 写入迭代结束即丢，
 * 「超半卡高残差并入下一次主滑」这条定稿语义**从未生效**；同时"钳制余量"与"相位记账"写同一变量
 * 且都是赋值语义 ⇒ 谁后写谁生效。现规则唯一实现在 [GridAlign.planMainSwipe] / [GridAlign.driftAfterPage]，
 * 本文件把它钉住；`ScanEngine` 侧只负责调用与跨页持有变量。
 *
 * 参数取自 3200 档实测：`advTarget = 876`（3×292）、`minCmd = round(292×0.6) = 175`、
 * `maxCmd = 起手y(1178) − ADV_MIN_END_Y(60) = 1118`、`cardHalf = 126`。
 */
class GridAdvancePlanTest {

    private val TARGET = 876
    private val MIN_CMD = 175
    private val MAX_CMD = 1118
    private val CARD_HALF = 126

    private fun plan(gain: Double, drift: Int) =
        GridAlign.planMainSwipe(TARGET, gain, drift, MIN_CMD, MAX_CMD)

    // ---------- planMainSwipe ----------

    @Test
    fun `no drift and unity gain reproduces target`() {
        val (cmd, carry) = plan(1.0, 0)
        assertEquals(TARGET, cmd)
        assertEquals("未钳制 ⇒ 无余量", 0, carry)
    }

    @Test
    fun `positive drift never pushes the command past target`() {
        // ★ 2026-09-16 用户定稿「宁愿重复点也不要漏件」：命令封顶 = target ⇒ 正记账**不放大命令**
        //   （原本 876+200=1076 会过滚 ⇒ 跳行静默漏件）。被削掉的部分也**不记账**（否则 drift 只增不减）。
        val (cmd, carry) = plan(1.0, 200)
        assertEquals(TARGET, cmd)
        assertEquals(0, carry)
    }

    @Test
    fun `negative drift still shortens the command`() {
        // 反向仍生效：过冲后少滑（欠滚 ⇒ 重复件，可接受方向）
        val (cmd, carry) = plan(1.0, -200)
        assertEquals(676, cmd)
        assertEquals(0, carry)
    }

    @Test
    fun `overflow above maxCmd keeps positive remainder`() {
        // 欠量 400 想滑 1276：先被起手 y 钳到 1118，再被 **target 上限**削到 876
        // ⇒ 这是"刻意不补偿欠量"⇒ 余量记 0（不再把 158 留到下一页，否则 drift 只增不减）
        val (cmd, carry) = plan(1.0, 400)
        assertEquals(TARGET, cmd)
        assertEquals(0, carry)
    }

    @Test
    fun `underflow below minCmd keeps negative remainder`() {
        // 过冲 −800 想滑 76，被抬到 175（手势不能太短）⇒ 余 −99（下一页少滑）
        val (cmd, carry) = plan(1.0, -800)
        assertEquals(MIN_CMD, cmd)
        assertEquals(-99, carry)
    }

    @Test
    fun `gain below one inflates the command and clamps`() {
        // gain=0.5（落地只有命令一半）⇒ 876/0.5 = 1752：先钳 maxCmd 1118，再被 target 削到 876
        // ⚠️ 生产**恒传 gain=1.0**（不加增益）⇒ 本用例只锁"即便传了 <1 的增益也不会超 target"
        val (cmd, carry) = plan(0.5, 0)
        assertEquals(TARGET, cmd)
        assertEquals(0, carry)
    }

    @Test
    fun `non positive gain falls back to one`() {
        assertEquals(TARGET to 0, plan(0.0, 0))
        assertEquals(TARGET to 0, plan(-2.0, 0))
    }

    @Test
    fun `gain above one shrinks the command`() {
        // BS 实测落地 ≈ 命令 ×1.25 ⇒ 命令应压小（876/1.25=701）
        assertEquals(701, plan(1.25, 0).first)
    }

    // ---------- driftAfterPage ----------

    @Test
    fun `aligned page clears the drift`() {
        // 2026-09-14 实测样本：L=1080 对目标 876 ⇒ residual −204 → φ=88 ≤ 126 ⇒ 平移消化，记账 0
        assertEquals(0, GridAlign.driftAfterPage(88, -204, CARD_HALF))
        // 另一页：residual −685 ⇒ φ=101 ≤ 126 ⇒ 同样清零（否则会把 2 行欠量重复补进下一滑）
        assertEquals(0, GridAlign.driftAfterPage(101, -685, CARD_HALF))
    }

    @Test
    fun `over-limit carries the FULL residual not the modded phi`() {
        // |φ|=140 > 126 ⇒ 本页不平移 ⇒ 记账 = −residual = +685（真实欠量，跨 2 行也必须整额补）
        assertEquals(685, GridAlign.driftAfterPage(140, -685, CARD_HALF))
        // 过冲同理：residual +330 ⇒ 记账 −330（下一页少滑）
        assertEquals(-330, GridAlign.driftAfterPage(-140, 330, CARD_HALF))
    }

    @Test
    fun `boundary is inclusive at cardHalf`() {
        assertEquals("|φ| = cardHalf 仍算可平移", 0, GridAlign.driftAfterPage(CARD_HALF, -200, CARD_HALF))
        assertEquals(200, GridAlign.driftAfterPage(CARD_HALF + 1, -200, CARD_HALF))
        assertEquals(0, GridAlign.driftAfterPage(-CARD_HALF, 200, CARD_HALF))
        assertEquals(-200, GridAlign.driftAfterPage(-CARD_HALF - 1, 200, CARD_HALF))
    }

    // ---------- 符号约定（2026-09-16 真机定案：写反过一次 ⇒ 整页 21 格全重复） ----------

    @Test
    fun `short landing shifts clicks DOWN by the shortfall`() {
        // 3200/BS 实测：命令 876 → 落地 801（少滚 75）⇒ 内容比理想位置低 75px
        // ⇒ withGridRowOffset 是 y += φ ⇒ φ 必须 = +75（点击下移），不是 −75
        assertEquals(75, GridAlign.phiFromLanding(801 - TARGET, 292))
        // 过冲对称：L=951（多滚 75）⇒ 内容偏高 ⇒ φ = −75（点击上移）
        assertEquals(-75, GridAlign.phiFromLanding(951 - TARGET, 292))
        // 精确命中 ⇒ 0（避免给点击引入无谓偏移）
        assertEquals(0, GridAlign.phiFromLanding(0, 292))
    }

    @Test
    fun `phi wraps into half pitch`() {
        // 归一到 (−pitch/2, pitch/2]：L = 876−292−75 = 509 ⇒ residual −367 ⇒ φ = 367−292 = +75
        assertEquals(75, GridAlign.phiFromLanding(509 - TARGET, 292))
    }

    @Test
    fun `phi and drift share the same sign convention`() {
        // 同一次落地测量：φ（点击平移）与记账（下次命令）必须朝**同一方向**补欠量
        val residual = -685   // 只落地 191
        assertTrue("少滚 ⇒ φ 为正（点击下移）", GridAlign.phiFromLanding(residual, 292) > 0)
        assertTrue("少滚 ⇒ 记账为正（下次多滑）", GridAlign.driftAfterPage(140, residual, CARD_HALF) > 0)
        val over = 300        // 过冲 300
        assertTrue("过冲 ⇒ φ 为负（点击上移）", GridAlign.phiFromLanding(over, 292) < 0)
        assertTrue("过冲 ⇒ 记账为负（下次少滑）", GridAlign.driftAfterPage(146, over, CARD_HALF) < 0)
    }

    // ---------- 跨页串联（P0-2 的正面证据） ----------

    @Test
    fun `drift survives into the next page command`() {
        // 第 1 页：只落地 191（脚本实测过的最坏样本）⇒ residual=−685 ⇒ φ=101 ⇒ 清零（本页平移消化）
        val drift1 = GridAlign.driftAfterPage(101, -685, CARD_HALF)
        assertEquals(0, drift1)

        // 第 1 页若判超限（|φ|>126）：记账 = +685（想在下一次多滑以补欠量）。
        // ⚠️ 2026-09-16 定稿「不要加增益，宁愿重复点也不要漏件」⇒ **正记账不放大命令**（封顶 = target）：
        //   过滚会让点击落到下一张卡 ⇒ 中间一行被**静默跳过**；欠滚只是重复读（可观测、可接受）。
        //   被 target 上限削掉的部分**不记账**（记了也永远补不上 ⇒ drift 只增不减）。
        val driftOver = GridAlign.driftAfterPage(140, -685, CARD_HALF)
        val (cmd2, carry2) = plan(1.0, driftOver)
        assertEquals("正记账不放大命令（命令恒 ≤ target）", TARGET, cmd2)
        assertEquals("被 target 上限削掉的部分不记账", 0, carry2)

        // 第 2 页若按 gain 2.0（落地翻倍）：438 + 685 = 1123 ⇒ 先钳 maxCmd 1118，再被 target 削到 876 ⇒ 余 0
        //   （生产恒 gain=1.0；本用例只锁"任何 gain 下都不超 target"）
        val (cmd3, carry3) = plan(2.0, driftOver)
        assertEquals(TARGET, cmd3)
        assertEquals(0, carry3)
    }
}
