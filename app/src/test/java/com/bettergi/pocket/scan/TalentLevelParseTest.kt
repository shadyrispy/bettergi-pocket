package com.bettergi.pocket.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 天赋读数的**解析律**与**合并律**（#153，平票方向与采样停止律见 #166）。
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
        // 平票 ⇒ 取**更大**的那个（#166：掉字型错读恒偏小，判据见 voteTalentLevel KDoc）
        assertEquals(9, ScanEngine.voteTalentLevel(listOf(s(4, 1, 1), s(9, 1, 1)), 0))
        // 0 = 这次没读到，不参与投票
        assertEquals(10, ScanEngine.voteTalentLevel(listOf(s(0, 0, 0), s(10, 0, 0)), 0))
        assertEquals(0, ScanEngine.voteTalentLevel(listOf(s(0, 0, 0), s(0, 0, 0)), 2))
        // 单次读数原样通过（多数情形：一次重读就三项齐了）
        assertEquals(7, ScanEngine.voteTalentLevel(listOf(s(7, 10, 10)), 0))
    }

    @Test
    fun `平票取更大——真机三例掉字错读不再靠掷硬币`() {
        fun s(vararg v: Int) = IntArray(v.size) { i -> v[i] }

        // 19:30 轮 随机姓名(=Manekina)：第 2 帧 'Lv.1o' 把末位 0 认成字母 ⇒ 10 掉成 1。
        // 旧律（平票取更晚）**已写进导出件**：20261001_1941_2244_character_HEAD.json 里 auto=1，真值 10
        assertEquals(10, ScanEngine.voteTalentLevel(listOf(s(10, 10, 10), s(1, 10, 10)), 0))
        // 19:30 / 19:45 轮 Fischl burst：第 2 帧 '•Lv.i2' 把首位 1 认成字母 ⇒ 12 掉成 2，
        // 再按 c6 减 3 夹到下限 1（GT 9）；两遍访问的第二遍（重复⇒未入库）才没造成损失
        assertEquals(12, ScanEngine.voteTalentLevel(listOf(s(8, 13, 12), s(8, 13, 2)), 2))
        // 同轮 Citlali：偏小的一侧在**先** ⇒ 新律同样给对（真值 6）
        assertEquals(6, ScanEngine.voteTalentLevel(listOf(s(4, 10, 10), s(6, 10, 10)), 0))
        // 多数票仍然优先：偏大的偶发错读只有 1 票，压不过真值
        assertEquals(10, ScanEngine.voteTalentLevel(listOf(s(10, 0, 0), s(10, 0, 0), s(11, 0, 0)), 0))
    }

    @Test
    fun `平票判定驱动重读的停止律`() {
        fun s(vararg v: Int) = IntArray(v.size) { i -> v[i] }

        // 两个样本在 burst 格 12:2 平票 ⇒ 还要继续采（#166 之前这里直接收工）
        assertTrue(ScanEngine.talentSamplesTied(listOf(s(8, 13, 12), s(8, 13, 2))))
        // 第三帧站住多数 ⇒ 不再平票，可以收
        assertFalse(ScanEngine.talentSamplesTied(listOf(s(8, 13, 12), s(8, 13, 2), s(8, 13, 12))))
        // 读数完全一致（最常见路径）⇒ 不平票
        assertFalse(ScanEngine.talentSamplesTied(listOf(s(10, 10, 10), s(10, 10, 10))))
        // 0 / -1 不算一票，也就不构成平票（与 voteTalentLevel 同口径）
        assertFalse(ScanEngine.talentSamplesTied(listOf(s(9, 11, 0), s(9, 11, -1))))
        assertFalse(ScanEngine.talentSamplesTied(listOf(s(0, 0, 0), s(0, 0, 0))))
        // 三格里只要有一格平票就要继续采（10/11/12 各 1 票）
        assertTrue(ScanEngine.talentSamplesTied(listOf(s(6, 10, 10), s(6, 11, 10), s(6, 12, 10))))
        // 有分歧但已站住多数（10 两票 vs 11 一票）⇒ 不该继续采，收工条件是"平票"而不是"一致"
        assertFalse(ScanEngine.talentSamplesTied(listOf(s(6, 10, 10), s(6, 11, 10), s(6, 10, 10))))
    }
}
