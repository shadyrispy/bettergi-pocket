package com.bettergi.pocket.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 武器**跨页重叠**判据的下标守门（#60，2026-09-25）。
 *
 * 这条判据要求上页身份表是**绝对下标** `r*cols+c` 的全行表。它曾经喂的是一张**紧凑表**
 * （只存 row1/row2 ⇒ 槽位 0 和 1），而读取仍按绝对下标 `(traverseRows-2)*cols+c` /
 * `(traverseRows-1)*cols+c` ⇒ 第一个下标实际落在 row2、第二个越界恒 null。
 * 后果：**「前进 1 行」这一整类重叠没人判**，列表滚到底时页面向前进 1 行，于是尾区同一件
 * 按新页号重发（2560 实测 19 次丢弃**全**是 `新行←上页 row2`，漏掉的 6 件**全**是
 * `新 row0 ← 上页 row1`）。
 *
 * 当时靠真机对照轮才查出来，代价是一轮**方向相反的误修**（把窗口放宽成 `r..r+2`，
 * 因为 identity 不唯一而误伤真·同款多把）。下面两条"紧凑表会漏判/误判"的用例就是把
 * 那个坑固化成断言 —— 换回紧凑表会立刻红。
 */
class CrossPageIdentityTest {

    private companion object {
        const val COLS = 6
        const val ROWS = 3

        /** 绝对表：一行 ROWS、一列 COLS，每格身份唯一，形如 `W<row>_<col>`。 */
        fun absoluteTable(): List<String> =
            (0 until ROWS).flatMap { r -> (0 until COLS).map { c -> "W${r}_$c" } }
    }

    private fun overlaps(prev: List<String>?, row: Int, col: Int, identity: String?): Boolean =
        ScanEngine.crossPageOverlap(prev, COLS, ROWS, row, col, identity)

    // ── 判据必须认下的三种重叠 ──────────────────────────────────────────────

    @Test
    fun `前进 2 行 - 新 row0 就是上页 row2`() {
        val t = absoluteTable()
        assertTrue(overlaps(t, row = 0, col = 2, identity = "W2_2"))
    }

    @Test
    fun `前进 1 行 - 新 row0 就是上页 row1`() {
        // ★ 这一条就是 #60 漏掉的那一类：紧凑表下它返回 false。
        val t = absoluteTable()
        assertTrue(overlaps(t, row = 0, col = 2, identity = "W1_2"))
    }

    @Test
    fun `前进 1 行 - 新 row1 就是上页 row2`() {
        val t = absoluteTable()
        assertTrue(overlaps(t, row = 1, col = 5, identity = "W2_5"))
    }

    // ── 判据必须放过的：真·新内容 与 真·同款多把 ──────────────────────────

    @Test
    fun `前进 2 行时新 row1 来自上页 row3 - 表里没有就不得丢弃`() {
        // 一页只有 ROWS=3 行 ⇒ 前进 2 行后，新 row1/row2 对应上页 row3/row4，**不是重叠而是新件**。
        // 若哪天有人把窗口按"r..末尾"放宽，这条会先红（那次误修正是丢在这里）。
        val t = absoluteTable()
        assertFalse(overlaps(t, row = 1, col = 0, identity = "W3_0"))
        assertFalse(overlaps(t, row = 2, col = 0, identity = "W4_0"))
    }

    @Test
    fun `只比同列 - 同款武器换了列不得当成重叠`() {
        val t = absoluteTable()
        // 上页 row2 第 2 列是 W2_2；本页第 3 列读到同名身份 ⇒ 不判（同款多把必须保住）
        assertFalse(overlaps(t, row = 0, col = 3, identity = "W2_2"))
    }

    @Test
    fun `首页无上页表一律不判`() {
        assertFalse(overlaps(null, row = 0, col = 0, identity = "W0_0"))
    }

    @Test
    fun `非网格路径 row 或 col 为负一律不判`() {
        // snap / rosterFind 等路径不落在页内格坐标上（curCellRow=-1），没有"上页同列那格"可比
        val t = absoluteTable()
        assertFalse(overlaps(t, row = -1, col = 0, identity = "W2_0"))
        assertFalse(overlaps(t, row = 0, col = -1, identity = "W2_0"))
    }

    @Test
    fun `空身份不得与表里的空格相等`() {
        // 读失败的格在表里留空串（见 #55/#37：那格故意留空，好让下页的安全阀拒绝跳过）。
        // 若某格身份也为空，`null/""==""` 会把它判成"上页已读过"⇒ 永久丢件且无痕。
        val t = absoluteTable().toMutableList().also { it[8] = "" }   // row1 col2 空格
        assertFalse(overlaps(t, row = 0, col = 2, identity = ""))
        assertFalse(overlaps(t, row = 0, col = 2, identity = null))
    }

    // ── 把 #60 的坑固化：紧凑表会漏判 ─────────────────────────────────────

    @Test
    fun `紧凑表（只存 row1 row2 槽位 0 1）会漏判前进 1 行 - 这就是当年的 bug`() {
        val compact = (1 until ROWS).flatMap { r -> (0 until COLS).map { c -> "W${r}_$c" } }
        // 绝对下标 (ROWS-2)*COLS+c = 6+c 在紧凑表里取到的其实是 **row2** ⇒ 前进 2 行仍能命中
        assertTrue(ScanEngine.crossPageOverlap(compact, COLS, ROWS, row = 0, col = 2, identity = "W2_2"))
        // 而 row1 这一路（前进 1 行）被静默跳过；(ROWS-1)*COLS+c = 12+c 直接越界取到 null
        assertFalse(ScanEngine.crossPageOverlap(compact, COLS, ROWS, row = 0, col = 2, identity = "W1_2"))
    }

    @Test
    fun `表短于两行时不判`() {
        // traverseRows<2 时 `(rows-2)` 会变成负下标 ⇒ 必须拒判，不能拿负数去凑一个合法槽位
        val t = listOf("W0_0", "W0_1")
        assertFalse(ScanEngine.crossPageOverlap(t, COLS, traverseRows = 1, row = 0, col = 0, identity = "W0_0"))
    }
}
