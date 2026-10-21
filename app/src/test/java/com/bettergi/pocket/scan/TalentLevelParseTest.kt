package com.bettergi.pocket.scan

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 天赋读数的**解析律**与**合并律**（#153）。
 *
 * 输入不是编出来的：`talentLevelOf` 的样本全部取自 2244 档六轮 `character_scan` 日志里
 * OCR 真给出来的原文（`char: 天赋等级 … ｜ 原文=[…]`），期望值取自同轮 GT。
 */
class TalentLevelParseTest {

    @Test
    fun `锚定 Lv 之后取数——邻格碎字不再压住真值`() {
        // 干净读数
        assertEquals(10, ScanEngine.talentLevelOf("Lv.10"))
        assertEquals(6, ScanEngine.talentLevelOf("Lv.6"))
        // 左侧标点/装饰：不影响
        assertEquals(4, ScanEngine.talentLevelOf(".Lv.4"))
        assertEquals(1, ScanEngine.talentLevelOf("•Lv.1"))
        assertEquals(3, ScanEngine.talentLevelOf("-Lv.3"))
        // ★ 关键回归：**邻格碎字是个数字**，旧规则「取串里第一个数」会把它当等级
        assertEquals("Diona 真值 1，旧规则读成 4", 1, ScanEngine.talentLevelOf("4Lv.1"))
        assertEquals(1, ScanEngine.talentLevelOf("Lv.1-"))
        assertEquals(10, ScanEngine.talentLevelOf("Lv.10."))
        // 首字母被切/认错：仍要能取到数
        assertEquals(1, ScanEngine.talentLevelOf("iv.1"))
        assertEquals(10, ScanEngine.talentLevelOf("Ev.10"))
        assertEquals(12, ScanEngine.talentLevelOf("Iv.12"))
        // 「1」被认成字母 ⇒ 读不到（交重读），**不能**硬猜成 1
        assertEquals(0, ScanEngine.talentLevelOf("Lv.I"))
        assertEquals(0, ScanEngine.talentLevelOf("Lv.J"))
        // 读到的是行名（冲刺技那一行）⇒ 0，走 #152 的下移补读
        assertEquals(0, ScanEngine.talentLevelOf("流·散步"))
        assertEquals(0, ScanEngine.talentLevelOf("虚实流动"))
        assertEquals(0, ScanEngine.talentLevelOf(""))
        assertEquals(0, ScanEngine.talentLevelOf(null))
    }

    @Test
    fun `越界值判为不可信而不是等级值`() {
        // Skirk 实测导出过 410（`4` + `10` 粘连）⇒ 必须是「不可信」，不能进合并
        assertEquals(-1, ScanEngine.talentLevelOf("Lv.410"))
        assertEquals(-1, ScanEngine.talentLevelOf("Lv.99"))
        // 上界内保留：命座加成后面要减，面板显示值可到 13
        assertEquals(13, ScanEngine.talentLevelOf("Lv.13"))
    }

    @Test
    fun `合并律取多数票而不是 max`() {
        fun s(vararg v: Int) = IntArray(v.size) { i -> v[i] }

        // Diona：首读被邻格碎字污染成 4，重读三次都是 1 ⇒ max 会永久锁死 4
        assertEquals(1, ScanEngine.voteTalentLevel(listOf(s(4, 8, 7), s(1, 8, 7), s(1, 8, 7), s(1, 8, 7)), 0))
        // Chevreuse：首读太早（淡入未完成）读到 1，真值 11 由后面的重读给出
        assertEquals(11, ScanEngine.voteTalentLevel(listOf(s(6, 1, 6), s(6, 11, 6), s(6, 11, 6)), 1))
        // 平票 ⇒ 取**更晚**的那个（面板已淡入完成）
        assertEquals(9, ScanEngine.voteTalentLevel(listOf(s(4, 1, 1), s(9, 1, 1)), 0))
        // 0 = 这次没读到，不参与投票
        assertEquals(10, ScanEngine.voteTalentLevel(listOf(s(0, 0, 0), s(10, 0, 0)), 0))
        assertEquals(0, ScanEngine.voteTalentLevel(listOf(s(0, 0, 0), s(0, 0, 0)), 2))
        // 单次读数原样通过（多数情形：一次重读就三项齐了）
        assertEquals(7, ScanEngine.voteTalentLevel(listOf(s(7, 10, 10)), 0))
    }
}
