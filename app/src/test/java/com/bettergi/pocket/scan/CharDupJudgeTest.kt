package com.bettergi.pocket.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 角色扫描「连续重复」收尾判据回归（2026-09-11 用户定 **streak=3**）。
 *
 * 判据按**词典身份 key**（`GoodCharacter.key`）比对，**不按名字**——名字可能 OCR 读错、
 * 或两个不同角色撞名，按名字会把不同角色误判成重复而**丢件**（实测风险：88 件 vs 已知 92 件）。
 * `key == null`（词典未解析出）时判据**完全不介入**（既不算重复也不清零）。
 *
 * 真机/模拟器实测：88 角色账号扫 20 页，过程中出现 8 次翻页重叠的重复件被正确排除、未入库；
 * 最终由 `pagedGrid reachedEnd` 收尾（列表不回卷）。
 */
class CharDupJudgeTest {

    @Test
    fun `limit zero means disabled - never stops and keeps counter`() {
        val (streak, stop) = CharDupJudge.step(listOf("A", "B"), "A", streak = 2, limit = 0)
        assertEquals(2, streak)
        assertFalse(stop)
    }

    @Test
    fun `null key never intervenes - neither counts nor resets`() {
        // 未解析出身份 ⇒ 宁可多扫一个，也不可错杀/中断连续计数
        val (s1, stop1) = CharDupJudge.step(listOf("A"), null, streak = 2, limit = 3)
        assertEquals(2, s1)
        assertFalse(stop1)
        val (s2, stop2) = CharDupJudge.step(emptyList(), null, streak = 0, limit = 3)
        assertEquals(0, s2)
        assertFalse(stop2)
    }

    @Test
    fun `unique keys never accumulate`() {
        val seen = emptyList<String>()
        var streak = 0
        for (k in listOf("A", "B", "C", "D")) {
            val (s, stop) = CharDupJudge.step(seen, k, streak, 3)
            streak = s
            assertFalse("唯一身份不得止扫（$k）", stop)
        }
        assertEquals(0, streak)
    }

    @Test
    fun `three consecutive duplicates stop exactly at the third`() {
        val seen = listOf("A", "B", "C")
        var streak = 0
        val streaks = mutableListOf<Int>()
        val hits = mutableListOf<Boolean>()
        repeat(3) {
            val (s, stop) = CharDupJudge.step(seen, "A", streak, 3)
            streak = s
            streaks += s
            hits += stop
        }
        assertEquals(listOf(1, 2, 3), streaks)
        assertEquals(listOf(false, false, true), hits)
    }

    @Test
    fun `a new key in between resets the streak`() {
        val seen = listOf("A", "B", "C")
        var streak = 0
        streak = CharDupJudge.step(seen, "A", streak, 3).first
        streak = CharDupJudge.step(seen, "A", streak, 3).first
        assertEquals(2, streak)
        val (afterNew, stopNew) = CharDupJudge.step(seen, "NEW", streak, 3)
        assertEquals(0, afterNew)
        assertFalse(stopNew)
        streak = CharDupJudge.step(seen + "NEW", "A", afterNew, 3).first
        assertEquals(1, streak)
        streak = CharDupJudge.step(seen + "NEW", "A", streak, 3).first
        assertEquals(2, streak)
    }

    @Test
    fun `limit one stops on the first duplicate - legacy roster0 semantics`() {
        val (streak, stop) = CharDupJudge.step(listOf("A", "B"), "A", 0, 1)
        assertEquals(1, streak)
        assertTrue(stop)
    }

    @Test
    fun `duplicate of a later-scanned character also counts`() {
        // 不要求"撞首名"：撞到中段已扫过的角色同样计为重复（滑动失效/翻页重叠时就是这样）
        val (streak, stop) = CharDupJudge.step(listOf("A", "B", "C"), "C", 0, 1)
        assertEquals(1, streak)
        assertTrue(stop)
    }
}
