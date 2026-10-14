package com.bettergi.pocket.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 武器**跨页重叠**判据的下标守门（#60，2026-09-25）+ **前进量门控**（#292/#215，2026-10-14）。
 *
 * ## 老判据（#60 之前的那一版）
 * 固定窗口 `prev[(row+1)*cols+col] || prev[(row+2)*cols+col]`：**不看这一页实际前进了几行**，
 * 只要本格身份等于上页往下 1~2 行的任意一格就丢弃。列表滚到底/尾区时页面**整页前进**
 * （advance=traverseRows）根本没有任何重叠，此时任何丢弃都是误丢 —— 100842 日志里
 * 124 次「跨页重复」绝大多数正是这一类（#215 根因）。**零重叠页上任何丢弃都是误丢。**
 *
 * ## 新判据（#292）
 * 用**实测前进量** advanceRows 门控：`pr = row + advanceRows`，
 * `pr >= traverseRows ⇒ false`（本格在上页不存在 ⇒ 必是新件）；
 * 否则与 `prev[pr*cols+col]` 逐位比对。advanceRows 来自身份锚定的 `d/cols`
 * （内容对齐，几何 `rowsAdvanced` 恒读 3 ⇒ 不可用）。
 * `advanceRows < 0`（首页/锚定未命中）⇒ false（fail-open：宁可慢，不可丢）。
 *
 * ## 下表坑（#60，仍需守）
 * 上页身份表必须是**绝对下标** `r*cols+c` 的整表。喂**紧凑表**（只存末两行 ⇒ 槽位 0/1）
 * 时按绝对下标读会**张冠李戴**：`(row+advance)` 落到别人的行上。后果是「前进 1 行」这一整类
 * 重叠判不出来（漏判），或把同款多把当成重叠（误丢）。下面把这两条都固化成断言。
 */
class CrossPageIdentityTest {

    private companion object {
        const val COLS = 6
        const val ROWS = 3

        /** 100842 实测口径：cols=7、traverseRows=3、pageSize=21（负对照专用）。 */
        const val COLS7 = 7
        const val ROWS3 = 3

        /** 绝对表：整 ROWS 行、每行 COLS 列，每格身份唯一，形如 `W<row>_<col>`。 */
        fun absoluteTable(): List<String> =
            (0 until ROWS).flatMap { r -> (0 until COLS).map { c -> "W${r}_$c" } }

        /** 7 列绝对表（负对照用，与 100842 日志同形）。 */
        fun table7(): List<String> =
            (0 until ROWS3).flatMap { r -> (0 until COLS7).map { c -> "W${r}_$c" } }

        /** 旧判据（#292 之前的固定 +1/+2 行窗口），**只用于负对照**：证明新判据改的是该改的地方。 */
        fun oldOverlap(prev: List<String>?, cols: Int, row: Int, col: Int, identity: String?): Boolean {
            if (prev == null || row < 0 || col < 0) return false
            if (identity.isNullOrEmpty()) return false
            return prev.getOrNull((row + 1) * cols + col) == identity ||
                prev.getOrNull((row + 2) * cols + col) == identity
        }
    }

    private fun overlaps(prev: List<String>?, row: Int, col: Int, identity: String?, advanceRows: Int): Boolean =
        ScanEngine.crossPageOverlap(prev, COLS, ROWS, row, col, identity, advanceRows)

    // ── 判据必须认下的三种重叠（前进量正确时） ────────────────────────────────

    @Test
    fun `前进 2 行 - 新 row0 就是上页 row2`() {
        val t = absoluteTable()
        assertTrue(overlaps(t, row = 0, col = 2, identity = "W2_2", advanceRows = 2))
    }

    @Test
    fun `前进 1 行 - 新 row0 就是上页 row1`() {
        // ★ 这一条就是 #60 漏掉的那一类：紧凑表下它返回 false。
        val t = absoluteTable()
        assertTrue(overlaps(t, row = 0, col = 2, identity = "W1_2", advanceRows = 1))
    }

    @Test
    fun `前进 1 行 - 新 row1 就是上页 row2`() {
        val t = absoluteTable()
        assertTrue(overlaps(t, row = 1, col = 5, identity = "W2_5", advanceRows = 1))
    }

    // ── 判据必须放过的：真·新内容 与 真·同款多把 ──────────────────────────

    @Test
    fun `前进 2 行时新 row1 来自上页 row3 - 表里没有就不得丢弃`() {
        // 一页只有 ROWS=3 行 ⇒ 前进 2 行后，新 row1/row2 对应上页 row3/row4，**不是重叠而是新件**。
        val t = absoluteTable()
        assertFalse(overlaps(t, row = 1, col = 0, identity = "W3_0", advanceRows = 2))
        assertFalse(overlaps(t, row = 2, col = 0, identity = "W4_0", advanceRows = 2))
    }

    @Test
    fun `只比同列 - 同款武器换了列不得当成重叠`() {
        val t = absoluteTable()
        // 上页 row2 第 2 列是 W2_2；本页第 3 列读到同名身份 ⇒ 不判（同款多把必须保住）
        assertFalse(overlaps(t, row = 0, col = 3, identity = "W2_2", advanceRows = 2))
    }

    @Test
    fun `首页无上页表一律不判`() {
        assertFalse(overlaps(null, row = 0, col = 0, identity = "W0_0", advanceRows = 2))
    }

    @Test
    fun `非网格路径 row 或 col 为负一律不判`() {
        // snap / rosterFind 等路径不落在页内格坐标上（curCellRow=-1），没有"上页同列那格"可比
        val t = absoluteTable()
        assertFalse(overlaps(t, row = -1, col = 0, identity = "W2_0", advanceRows = 2))
        assertFalse(overlaps(t, row = 0, col = -1, identity = "W2_0", advanceRows = 2))
    }

    @Test
    fun `空身份不得与表里的空格相等`() {
        // 读失败的格在表里留空串（见 #55/#37：那格故意留空，好让下页的安全阀拒绝跳过）。
        // 若某格身份也为空，`null/""==""` 会把它判成"上页已读过"⇒ 永久丢件且无痕。
        val t = absoluteTable().toMutableList().also { it[8] = "" }   // row1 col2 空格
        assertFalse(overlaps(t, row = 0, col = 2, identity = "", advanceRows = 2))
        assertFalse(overlaps(t, row = 0, col = 2, identity = null, advanceRows = 2))
    }

    // ── #292 核心：前进量门控（门控未知/前进满页一律放行） ───────────────────

    @Test
    fun `前进满页（advance==traverseRows）时零重叠 - 表里撞上也必须放行`() {
        // ★ 这一条就是 #215：整页前进的页面上，本格身份恰好等于上页中段的某件（同套同部位
        //   长段连排时几乎必然发生）。老判据固定看 +1/+2 行 ⇒ 命中即丢（RED）；新判据 advance=3
        //   ⇒ pr=3 ≥ 3 ⇒ 本格在上页根本不存在 ⇒ 放行（GREEN）。
        val t = absoluteTable()
        assertFalse(overlaps(t, row = 0, col = 0, identity = "W1_0", advanceRows = 3))
        assertFalse(overlaps(t, row = 0, col = 0, identity = "W2_0", advanceRows = 3))
        assertFalse(overlaps(t, row = 2, col = 5, identity = "W2_5", advanceRows = 3))
    }

    @Test
    fun `前进量未知（负数）一律放行 - fail-open`() {
        // 首页/锚定未命中 ⇒ curAdvanceRows=-1。宁可慢（这页照常逐格点、交给内容键去重），
        // 不可丢（按错的 advance 跳掉一格 = 那格根本不点击）。
        val t = absoluteTable()
        assertFalse(overlaps(t, row = 0, col = 2, identity = "W2_2", advanceRows = -1))
        assertFalse(overlaps(t, row = 0, col = 2, identity = "W2_2", advanceRows = 0 - 5))
    }

    @Test
    fun `前进 0 行 - 整页不动时逐位恒等映射`() {
        // 页面完全没动（重复读同一屏）⇒ 每一格都对应上页同格。advance=0 时 pr=row ⇒ 同格比对。
        val t = absoluteTable()
        assertTrue(overlaps(t, row = 0, col = 1, identity = "W0_1", advanceRows = 0))
        assertTrue(overlaps(t, row = 2, col = 5, identity = "W2_5", advanceRows = 0))
    }

    // ── 负对照（#292 硬要求）：同一份 100842 日志的形态 —— 旧红 / 新绿，且合法丢弃不少 ──
    //
    // 100842 实测口径：cols=7、traverseRows=3、pageSize=21；「跨页重复」124 次里绝大多数落在
    // **整页前进**的页面上（该轮 行级闭环=104、OVERLAP=11 ⇒ 真正有重叠的页只有 11 页）。
    // 下面用 7 列绝对表复刻两类样本：(A) 整页前进页的撞名 ⇒ 老判据误丢、新判据放行；
    // (B) 真实前进 1 行页的重叠 ⇒ 老判据丢、新判据同样丢（**合法丢弃不许少**）。

    @Test
    fun `负对照_A 整页前进页撞名 - 老判据误丢_新判据放行`() {
        val t = table7()
        // 本页 idle 地读到上页 row1/row2 的同列身份（同套同部位长段连排），但这一页实际整页前进
        val identity = "W1_0"
        assertTrue(oldOverlap(t, COLS7, row = 0, col = 0, identity = identity))          // 旧：误丢（RED）
        assertFalse(
            ScanEngine.crossPageOverlap(t, COLS7, ROWS3, row = 0, col = 0, identity, advanceRows = 3),
        )                                                                                // 新：放行（GREEN）
    }

    @Test
    fun `负对照_B 真前进 1 行的重叠 - 老判据丢_新判据同样丢（合法丢弃不少）`() {
        val t = table7()
        val identity = "W1_0" // 上页 row1 col0，本页前进 1 行后落回 row0
        assertTrue(oldOverlap(t, COLS7, row = 0, col = 0, identity = identity))          // 旧：丢
        assertTrue(
            ScanEngine.crossPageOverlap(t, COLS7, ROWS3, row = 0, col = 0, identity, advanceRows = 1),
        )                                                                                // 新：同样丢
    }

    // ── 把 #60 的坑固化：紧凑表会漏判 ─────────────────────────────────────

    @Test
    fun `紧凑表（只存 row1 row2 槽位 0 1）会漏判前进 1 行 - 这就是当年的 bug`() {
        // 紧凑表槽位 0/1 装的是上页 row1/row2。按绝对下标 (row+advance)*cols+col 读会张冠李戴：
        // 前进 1 行问 row0 时读 compact[1*6+2]=compact[8]=W2_2，而上页 row1 col2 其实是 W1_2
        // ⇒ 真·重叠判不出来（漏判）；反过来同一个槽位又可能把别的行的同款身份命中（误丢）。
        // 结论：生产方必须落**绝对整表**，紧凑表不能再用。下面断言"紧凑表漏判"这一面。
        val compact = (1 until ROWS).flatMap { r -> (0 until COLS).map { c -> "W${r}_$c" } }
        assertFalse(
            ScanEngine.crossPageOverlap(compact, COLS, ROWS, row = 0, col = 2, identity = "W1_2", advanceRows = 1),
        )
        // 绝对整表的同一问法必须命中（对照）：
        assertTrue(
            ScanEngine.crossPageOverlap(absoluteTable(), COLS, ROWS, row = 0, col = 2, identity = "W1_2", advanceRows = 1),
        )
    }

    @Test
    fun `表短于两行时不判`() {
        // traverseRows<2 时没有"上页那一行"可比 ⇒ 必须拒判，不能拿负数去凑一个合法槽位
        val t = listOf("W0_0", "W0_1")
        assertFalse(ScanEngine.crossPageOverlap(t, COLS, traverseRows = 1, row = 0, col = 0, identity = "W0_0", advanceRows = 0))
    }
}
