package com.bettergi.pocket.scan

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 圣遗物**身份锚定**（`ScanEngine.anchorHit`）的判据守门（#66，2026-09-26）。
 *
 * 锚定问的是一句话：本页读到的相邻两格 `(idx1, idx2)` 在上页身份表的哪一对相邻格里出现过？
 * 找到的位置 `hit` 隐含对齐偏移 `d = hit - 1`，而 `d` 决定后面**哪些格子可以不点击、不 OCR、
 * 直接抄上页身份**。所以锚错的代价不是慢，是**整段格子从未被读过**（且不产生 extra，对账只看得见少件）。
 *
 * 真机账（同一批日志可复核，`dsl/verify/runlogs/`）：
 * - 2560 全量轮：`跨页重叠跳过` 59 次，其中 2 次的 `d=13`（cols=6 ⇒ 非整行）落在 page 10，
 *   该轮导出 966 / GT 968，缺的正好是 `ScrollOfTheHeroOfCinderCity/flower` ×2（GT 10 → 8）。
 * - 2244 pre64fix 轮：非整行跳格 11 次（page 17 跳 10 + page 25 跳 1），该轮导出 957 / GT 968 ⇒ 缺 11。
 * - 全部 ~100 次「逐位（含词条数值）」命中**无一例外**满足 `d % cols == 0`；
 *   而 10 次「只比 `#` 前缀且候选唯一」的回退命中**无一例外**不满足 ⇒ 回退路已删，本文件把它钉住。
 */
class IdentityAnchorTest {

    private val cols6 = 6
    private val cells = cols6 * 3 // 一页 18 格

    /** 造一张上页全页身份表：`circlet` 段刻意做成"同套同部位同等级同主词条"的连排（5★ 常态）。 */
    private fun prevTable(): List<String> = buildList {
        for (i in 0 until cells) {
            add(if (i < 12) "SetA/flower/20/hp#1.$i,2.$i" else "SetA/circlet/20/critRate_#3.${i}x")
        }
    }

    @Test
    fun `逐位相邻命中给出上页下标`() {
        val prev = prevTable().toMutableList()
        // 真机 2560 的正常情形：本页 idx1,2 ≡ 上页 idx13,14 ⇒ d=12=2×cols
        val i1 = prev[13]
        val i2 = prev[14]
        assertEquals(13, ScanEngine.anchorHit(prev, i1, i2))
        assertEquals(0, (13 - 1) % cols6)
    }

    @Test
    fun `两格前缀相同但词条数值不同 - 一律不算命中（这就是丢 2 件的那次）`() {
        val prev = prevTable()
        // page 10 实录：上页 idx14/15 与本页 idx1/idx2 的 `#` 前缀逐字相同，数值全不同。
        // 前缀回退路当时据此判 d=13（非整行）⇒ 跳掉 2 格从未点击的卡 ⇒ 缺 2 件。
        val i1 = "SetA/circlet/20/critRate_#5.8,9.9,14.0,58.0"
        val i2 = "SetA/circlet/20/critRate_#10.4,10.9,15.5,39.0"
        assertEquals(-1, ScanEngine.anchorHit(prev, i1, i2))
        // 前缀确实相同 —— 说明"唯一候选"的判据当时是被平凡满足的，不是巧合不够严格。
        assertEquals(prev[14].substringBefore('#'), i1.substringBefore('#'))
    }

    @Test
    fun `只有一格逐位相同不算相邻命中`() {
        val prev = prevTable()
        assertEquals(-1, ScanEngine.anchorHit(prev, prev[13], "SetA/circlet/20/critDMG_#9.9,44.0"))
        assertEquals(-1, ScanEngine.anchorHit(prev, "SetA/circlet/20/critDMG_#9.9,44.0", prev[13]))
    }

    @Test
    fun `锚点两格身份相同 - 纯函数不做判据，由调用处的面板停滞守卫挡`() {
        // 记录一条**边界**而非缺陷：(s,s) 若真的相邻出现在上页，anchorHit 会给出 hit。
        // 挡住它的是调用处那条 `i1 == i2 ⇒ 不锚定`（2026-09-24 页 53 实证），不在本函数里。
        val s = "SetA/circlet/20/critRate_#3.3"
        val prev = MutableList(cells) { "SetA/goblet/20/pyro_dmg_#$it" }.also { it[13] = s; it[14] = s }
        assertEquals(13, ScanEngine.anchorHit(prev, s, s))
        // 而上页没有相邻重复时，同一对 (s,s) 无处可配 ⇒ -1。
        assertEquals(-1, ScanEngine.anchorHit(prevTable() + s, s, s))
    }

    @Test
    fun `命中取第一对 - 重复身份对可能落在非整行处，由调用处的取模哨兵拒绝`() {
        // 上页里 (A,B) 出现两次：整行的 13,14 和非整行的 12,13。锚定取第一对 ⇒ hit=12 ⇒ d=11。
        val a = "SetB/sands/20/def_#1.1"; val b = "SetB/sands/20/def_#2.2"
        val prev = MutableList(cells) { "SetA/goblet/20/pyro_dmg_#$it" }.also {
            it[12] = a; it[13] = b; it[14] = a; it[15] = b
        }
        val hit = ScanEngine.anchorHit(prev, a, b)
        assertEquals(12, hit)
        // d=11 不是 6 的整数倍 ⇒ 纯几何上就不可能是"滚过的格数"，调用处据此拒绝跳过。
        assertEquals(11, hit - 1)
        assertEquals(5, (hit - 1) % cols6)
    }

    @Test
    fun `空表与越界不炸`() {
        assertEquals(-1, ScanEngine.anchorHit(emptyList(), "a", "b"))
        assertEquals(-1, ScanEngine.anchorHit(listOf("a"), "a", "b"))
        assertEquals(-1, ScanEngine.anchorHit(listOf("a", "b"), "", "b"))
    }

    @Test
    fun `整行不变量在两档实测值上都成立`() {
        // 2560（cols=6）实测 d=12 / d=6；2244（cols=7）实测 d=14 / d=7 / d=0。
        listOf(12 to 6, 6 to 6, 0 to 6, 14 to 7, 7 to 7, 0 to 7).forEach { (d, cols) ->
            assertEquals("d=$d cols=$cols 应为整行", 0, d % cols)
        }
        // 而 10 次误判的 d 全都不是整行。
        listOf(13 to 6, 15 to 6, 8 to 7, 17 to 7, 18 to 7).forEach { (d, cols) ->
            assertEquals("d=$d cols=$cols 不该是整行", true, d % cols != 0)
        }
    }
}
