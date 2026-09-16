package com.bettergi.pocket.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行级闭环判据（[GridRowCheck]）单测。
 *
 * 构造方式：把全局序列编号 `t0..` 的项当作内容键（"i12" 等），
 * 上一页取 `[P, P+20]`、本页取 `[P+A, P+A+20]`，读失败用 "" 占位（**占位必须保留**：下标 = 全局序号）。
 */
class GridRowCheckTest {

    private fun page(start: Int, size: Int = 21): List<String> = (0 until size).map { "i${start + it}" }

    @Test
    fun `exact one page advance is a violation (no overlap left)`() {
        // 设计不变式：目标前进量必须 < 一页卡片数（profile advance.extra=-150 ⇒ ~17 件）
        // ⇒ 一旦前进到整页，相邻两页**无共享内容键** ⇒ 判据不可测且已丢件 ⇒ 记为 SKIP
        val r = GridRowCheck.check(page(0), page(21), 21)
        assertEquals(GridRowCheck.Verdict.SKIP, r.verdict)
    }

    @Test
    fun `healthy overlap of four items is ok`() {
        // 实际工况（3 行遍历 + 目标 2.49 行）⇒ 前进 ~17 件、重叠 4 件
        val r = GridRowCheck.check(page(0), page(17), 21)
        assertEquals(17, r.advance)
        assertEquals(GridRowCheck.Verdict.OVERLAP, r.verdict)
    }

    @Test
    fun `under roll shows as overlap and is harmless`() {
        // 前进 18 件 ⇒ 欠滚重叠 3 格（去重即消）
        val r = GridRowCheck.check(page(0), page(18), 21)
        assertEquals(18, r.advance)
        assertEquals(GridRowCheck.Verdict.OVERLAP, r.verdict)
        assertEquals(0, r.skipped)
    }

    @Test
    fun `single stalling page is overlap not skip`() {
        // 原地不动（翻页没落地）：前进 0
        val r = GridRowCheck.check(page(0), page(0), 21)
        assertEquals(0, r.advance)
        assertEquals(GridRowCheck.Verdict.OVERLAP, r.verdict)
    }

    @Test
    fun `disjoint pages are a skip`() {
        // 前进 24 件：相邻两页无共享件 ⇒ 锚点找不到 ⇒ 跳行
        val r = GridRowCheck.check(page(0), page(24), 21)
        assertEquals(GridRowCheck.Verdict.SKIP, r.verdict)
        assertNull(r.advance)
    }

    @Test
    fun `border advance 20 items is still measurable`() {
        // 前进 20 件（重叠 1 件）：锚点 i20 在本页下标 0 ⇒ 可测 = 20 ⇒ OK（临界但安全）
        val r = GridRowCheck.check(page(0), page(20), 21)
        assertEquals(20, r.advance)
        assertEquals(GridRowCheck.Verdict.OK, r.verdict)
    }

    @Test
    fun `read failure placeholder does not break arithmetic`() {
        // 本页第 0 格读失败（占位 ""），但锚点 i20 仍能在本页找到（下标 1）⇒ 前进 21-1 = 20
        // 空占位放在**非锚点**格（第 5 格）：锚点 i20 仍在本页下标 0 ⇒ 前进 20
        val cur = page(20).toMutableList().also { it[5] = "" }
        val r = GridRowCheck.check(page(0), cur, 21)
        assertEquals(20, r.advance)
        assertEquals(GridRowCheck.Verdict.OK, r.verdict)
    }

    @Test
    fun `anchor read failure falls back to the other anchor`() {
        // 上一页末格读失败 ⇒ 用倒数第二格（i19）当锚；本页从 i18 起 ⇒ 前进 = 19-1 = 18
        val prev = page(0).toMutableList().also { it[20] = "" }
        val r = GridRowCheck.check(prev, page(18), 21)
        assertEquals(18, r.advance)
        assertEquals(GridRowCheck.Verdict.OVERLAP, r.verdict)
    }

    @Test
    fun `both anchors unreadable is unknown not skip`() {
        val prev = page(0).toMutableList().also { it[19] = ""; it[20] = "" }
        val r = GridRowCheck.check(prev, page(30), 21)
        assertEquals(GridRowCheck.Verdict.UNKNOWN, r.verdict)
        assertNull(r.advance)
    }

    @Test
    fun `degenerate inputs are unknown`() {
        assertEquals(GridRowCheck.Verdict.UNKNOWN, GridRowCheck.check(emptyList(), page(0), 21).verdict)
        assertEquals(GridRowCheck.Verdict.UNKNOWN, GridRowCheck.check(page(0), emptyList(), 21).verdict)
        assertTrue(GridRowCheck.check(page(0), page(21), 1).verdict == GridRowCheck.Verdict.UNKNOWN)
    }
}
