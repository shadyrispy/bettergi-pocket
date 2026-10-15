package com.bettergi.pocket.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #305：页末**跨页身份重放撤回**的判定表（`ScanEngine.replayWithdrawIdxs`）。
 *
 * 缺陷形状（华为 2244 全量武器实测，对账新 GT 多 5 漏 4）：
 * 同张卡在相邻两页各被读一次、各入库一次 —— 三道既有判据都盖不住：
 * · 像素指纹 0 命中（fpband 残差 89~114px 超可点带 88 ⇒ 按名义行位取不到真行）；
 * · `weaponCellEmitted` 键含页/行/列，挡不住**跨页位移**（p0r2c0 → p1r0c0）；
 * · 锚定时刻的追认撤不到 idx0 —— 那时它的身份还是生产者竞态残值（管道）或刚写。
 * ⇒ 页末 `curPageIds` 已是权威身份，按实测前进量重放逐格比对，逐位同 ⇒ 撤。
 *
 * 本表把 #305 日志里 5 组真实重读（SkywardPride/PrototypeArchaic/CoolSteel/
 * EmeraldOrb/WintersHeavyHeart 的共同形状）钉成断言。
 */
class ReplayWithdrawTest {

    private companion object {
        /** #305 实测口径：cols=7、traverseRows=3、锚定 d=14 ⇒ 前进 aRows=2。 */
        const val COLS = 7
        const val ROWS = 3
        const val ADVANCE = 2
    }

    /** 上页 21 格：W<r>_<c>（唯一身份）。 */
    private fun prevTable(): List<String> =
        (0 until ROWS).flatMap { r -> (0 until COLS).map { c -> "W${r}_$c" } }

    @Test
    fun `页边界位移重读被撤`() {
        // #305 实测主形状：本页 row0 的 c0/c1/c2 ≡ 上页 row2 同列（SkywardPride 三连）。
        // pageIdentities 按 emit 顺序写：idx3,4,5,6 是锚定跳过格（复制身份），这里模拟
        // 「本页 row0 只点了 c0..c2、c3..c6 被跳过」之后的权威表。
        val prev = prevTable()
        val cur = List(COLS) { c -> "W2_$c" } + List(COLS * (ROWS - 1)) { "" }
        val emitted = setOf(0, 1, 2)
        assertEquals(
            "row0 三格身份 ≡ 上页 row2 对齐位 ⇒ 三格都应撤（a=2 时 row0 ← 上页 row2）",
            listOf(0, 1, 2),
            ScanEngine.replayWithdrawIdxs(cur, prev, COLS, ROWS, ADVANCE, emitted),
        )
    }

    @Test
    fun `追认已撤的格天然幂等`() {
        // 锚定时刻追认撤了 idx1/idx2（它们的 emit 记录已 remove）⇒ emitted 只剩 idx0。
        val prev = prevTable()
        val cur = List(COLS) { c -> "W2_$c" } + List(COLS * (ROWS - 1)) { "" }
        assertEquals(
            "emitted 不含已撤格 ⇒ 重放只会返回 idx0（不是重复撤）",
            listOf(0),
            ScanEngine.replayWithdrawIdxs(cur, prev, COLS, ROWS, ADVANCE, setOf(0)),
        )
    }

    @Test
    fun `非重叠格不得误撤`() {
        // 本页 row1 是真新件（身份 ≠ 上页任何对齐位）⇒ 即使 emit 过也不撤。
        val prev = prevTable()
        val cur = List(COLS) { c -> "W2_$c" } +
            listOf("NEW_a", "NEW_b", "NEW_c", "NEW_d", "NEW_e", "NEW_f", "NEW_g") +
            List(COLS) { "" }
        val emitted = setOf(0, 1, 2, 7, 8, 9)
        assertEquals(
            "row0 三格撤、row1 三格留",
            listOf(0, 1, 2),
            ScanEngine.replayWithdrawIdxs(cur, prev, COLS, ROWS, ADVANCE, emitted),
        )
    }

    @Test
    fun `整页前进零重叠不动`() {
        // #292 的教训在重放侧同样成立：前进 ≥ 一整页 ⇒ 上页没有对齐位 ⇒ 任何撤回都是误丢。
        val prev = prevTable()
        val cur = List(COLS * ROWS) { "NEW_$it" }
        assertTrue(
            ScanEngine.replayWithdrawIdxs(cur, prev, COLS, ROWS, ROWS, (0 until COLS * ROWS).toSet()).isEmpty(),
        )
    }

    @Test
    fun `无锚定（-1）或 0 不动`() {
        val prev = prevTable()
        val cur = List(COLS) { c -> "W2_$c" } + List(COLS * (ROWS - 1)) { "" }
        val emitted = setOf(0, 1, 2)
        assertTrue(ScanEngine.replayWithdrawIdxs(cur, prev, COLS, ROWS, -1, emitted).isEmpty())
        assertTrue(ScanEngine.replayWithdrawIdxs(cur, prev, COLS, ROWS, 0, emitted).isEmpty())
    }

    @Test
    fun `空身份格不判`() {
        // idx1 身份为空（读失败）⇒ 不能与上页空格"相等"被撤（宁可慢不可丢）。
        val prev = prevTable()
        val cur = listOf("W2_0", "", "W2_2") + List(COLS * (ROWS - 1) - 3 + COLS) { "" }
        assertEquals(
            "空身份的 idx1 不在撤回清单",
            listOf(0, 2),
            ScanEngine.replayWithdrawIdxs(cur, prev, COLS, ROWS, ADVANCE, setOf(0, 1, 2)),
        )
    }
}
