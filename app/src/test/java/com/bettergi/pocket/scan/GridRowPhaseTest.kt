package com.bettergi.pocket.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 底栏锚**绝对行相位**（[GridAlign.rowPhase] 的三段纯函数）守门。
 *
 * 为什么需要它（run9 定性，任务 #38/#49）：翻页时喂给 `withGridRowOffset` 的量此前来自
 * fpband 的**相邻帧残差**（"这一滑比目标少滚了多少"），而那个 API 要的是**绝对**量
 * （"行现在在哪"）。两者只在上页正好落在名义位时等价。真机实测两者方向相反：
 * 条带给 φ=+44..+119（把点击**向下**推），同三帧像素量出的真实行顶却比名义**偏上** 71..100 ⇒
 * 点击落到卡下沿最后 1..12px ⇒ 整页 tap 落空（页级冻结，每页漏 12~14 件）。
 *
 * 锚点选择（2026-09-24 那版"detectRow 当绝对相位"被 run7 否决的教训）：不找"卡框顶"，
 * 只认卡内那条**等级标签亮带**（6 列同时出现、边沿硬、随行走 pitch），
 * 按 profiles 标定 `cardtop = 带中心 − labelAnchor(209)` 折回行顶 ⇒ 卡内特征从误差源变成锚。
 * 下面的期望值全部取自**真机帧**逐列量出的带中心（run9 三张冻结帧 + 两张正常帧），非构造数据。
 */
class GridRowPhaseTest {

    private val first = 290 + 209   // 名义第 0 行亮带中心 = cardOrigin.y + labelAnchor
    private val pitch = 278
    private val tol = 14

    // ---- brightBands：合成剖面 ----

    @Test
    fun `亮带中心按行距等距落在合成剖面上`() {
        val prof = DoubleArray(1440) { 135.0 }
        for (r in 0 until 3) for (y in (410 + r * 278)..(446 + r * 278)) prof[y] = 225.0
        assertEquals(
            listOf(428, 706, 984),
            GridAlign.brightBands(prof, 200, 1200, 20, 96),
        )
    }

    @Test
    fun `对比度不足的空列不产出亮带`() {
        val prof = DoubleArray(1440) { 135.0 }
        for (y in 410..446) prof[y] = 160.0   // 峰−中位 = 25 < BAND_CONTRAST(40)
        assertEquals(0, GridAlign.brightBands(prof, 200, 1200, 20, 96).size)
    }

    @Test
    fun `超宽高亮块不当作亮带`() {
        val prof = DoubleArray(1440) { 135.0 }
        for (y in 300..900) prof[y] = 230.0   // 601px 宽 > BAND_MAX_RUN ⇒ 整片高亮，不是"一条带"
        assertEquals(0, GridAlign.brightBands(prof, 200, 1200, 20, 96).size)
    }

    // ---- periodKeep：一次性亮带剔除 ----

    @Test
    fun `筛选栏那种一次性亮带被周期守卫剔除`() {
        // 218 = 顶部筛选栏亮带（实测存在于所有帧），与行网格不等距 ⇒ 必须先被杀掉，
        // 否则它会投出一张与真行只差几个 px 的假票。
        assertEquals(listOf(428, 706, 984), GridAlign.periodKeep(listOf(218, 428, 706, 984), pitch, tol))
    }

    // ---- phaseFromBars：真机帧实测 ----

    @Test
    fun `run9冻结帧p019 - 实测行顶比名义偏上71`() {
        val cols = listOf(
            listOf(428, 704), listOf(428, 704), listOf(428, 704),
            listOf(428, 704, 986), listOf(428, 704, 986), listOf(431, 707, 986),
        )
        val p = GridAlign.phaseFromBars(cols, first, pitch, tol, 5)!!
        assertEquals(-71, p.offset)                 // 实测行顶 219 = 名义 290 − 71
        assertEquals(6, p.columns)
        assertEquals(15, p.votes)
        assertTrue("一致带应远小于 2·tol，实际 ${p.spread}", p.spread <= tol * 2)
    }

    @Test
    fun `run9冻结帧p026 - 实测行顶比名义偏上100`() {
        val cols = listOf(
            listOf(398, 677), listOf(398, 677), listOf(398, 677),
            listOf(398, 677), listOf(398, 677, 956), listOf(404, 680, 956),
        )
        assertEquals(-100, GridAlign.phaseFromBars(cols, first, pitch, tol, 5)!!.offset)
    }

    @Test
    fun `run9冻结帧p063 - 实测行顶比名义偏上76`() {
        val cols = listOf(
            listOf(422, 701, 980), listOf(422, 701), listOf(422, 701),
            listOf(422, 701, 980), listOf(422, 701, 980), listOf(425, 704, 980),
        )
        assertEquals(-76, GridAlign.phaseFromBars(cols, first, pitch, tol, 5)!!.offset)
    }

    @Test
    fun `正常帧自检 - 同一把尺在非冻结帧上读回名义位`() {
        // chk.png（非冻结的正常背包帧）⇒ δ=−4 ⇒ 行顶 286 ≈ 名义 290。
        // 这条是"锚 209 与名义行顶 290 互相咬合"的证据：二者若错开 ~85px，正常帧不可能
        // 正好回到 0，而三张冻结帧也不会给出与条带**反号**的读数。
        val cols = listOf(
            listOf(494, 776, 1055), listOf(494, 773, 1052), listOf(494, 773, 1052),
            listOf(494, 773, 1052), listOf(494, 773, 1052), listOf(494, 773, 1052),
        )
        val p = GridAlign.phaseFromBars(cols, first, pitch, tol, 5)!!
        assertEquals(-4, p.offset)
        assertTrue("|δ| 应远小于半行距（行序无歧义），实际 ${p.offset}", Math.abs(p.offset) < pitch / 2)
    }

    @Test
    fun `行序折叠 - 第二行的带投出与第一行同一票`() {
        // 带中心相差整行距时 δ 必须相同（centeredMod 吸收行序），否则同页 3 行会自相对着投。
        val one = GridAlign.phaseFromBars(List(5) { listOf(428, 706) }, first, pitch, tol, 5)!!
        val two = GridAlign.phaseFromBars(List(5) { listOf(706, 984) }, first, pitch, tol, 5)!!
        assertEquals(one.offset, two.offset)
    }

    @Test
    fun `有效列不足门限则拒测`() {
        val cols = listOf(listOf(428, 706), listOf(430, 708), listOf(426, 704))   // 3 列 < minCols 5
        assertNull(GridAlign.phaseFromBars(cols, first, pitch, tol, 5))
    }

    @Test
    fun `半对半翻票则拒测而不是选中位边`() {
        // 3 列说 −71、3 列说 +60：全体中位会给出一个"看似自洽"的错答案 ⇒ 众数按**列数**计，
        // 两边都只有 3 列 < 5 ⇒ 必须拒测（宁可不平移，也不能按半信半疑的读数挪点击）。
        val cols = listOf(
            listOf(428, 706), listOf(428, 706), listOf(428, 706),
            listOf(559, 837), listOf(559, 837), listOf(559, 837),
        )
        assertNull(GridAlign.phaseFromBars(cols, first, pitch, tol, 5))
    }

    @Test
    fun `票源分散无众数则拒测`() {
        val cols = listOf(
            listOf(428, 706), listOf(450, 728), listOf(472, 750),
            listOf(494, 772), listOf(516, 794),
        )
        assertNull(GridAlign.phaseFromBars(cols, first, pitch, tol, 5))
    }

    @Test
    fun `单列只剩一条带时该列不参与投票`() {
        // 尾区/半空页：某列只剩一条带（无周期邻居）⇒ 不得进入投票，否则 bars≥2 的守卫被绕开。
        val cols = listOf(
            listOf(428, 706), listOf(428, 706), listOf(428), listOf(706),
            listOf(428, 706), listOf(428, 706),
        )
        val p = GridAlign.phaseFromBars(cols, first, pitch, tol, 4)!!
        assertEquals(4, p.columns)
        assertEquals(-71, p.offset)
    }
}
