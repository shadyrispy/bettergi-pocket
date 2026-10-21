package com.bettergi.pocket.scan

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 副词条**连续块**读取（2026-09-16 GT 定标修复的守门用例）。
 *
 * 背景（design-docs/good-diff-20260916.md）：旧实现 `mapNotNull` + 「5★恒4条 / 4★最高3条」
 * 与 ground truth 矛盾 —— GT 实测 5★+0 有 67% 只有 3 条、4★+16 100% 是 4 条。
 */
class SubstatBlockTest {

    private fun line(name: String, v: String) = "$name+$v"

    @Test
    fun `block stops at the set-name line so no phantom 4th substat`() {
        // 真机面板：3 条词条后紧跟「套装名: 2件套：攻击力提高18%」⇒ 第 4 行 ROI 正压该行
        val lines = listOf(
            line("攻击力", "14.0%"),
            line("暴击伤害", "7.8%"),
            line("暴击率", "7.4%"),
            "影中沉凝的幻灭: 2件套：攻击力提高18%",
        )
        val got = StatParser.parseBlock(lines)
        assertEquals("套装名行不得被读成第 4 条词条", 3, got.size)
        assertEquals(listOf("atk_", "critDMG_", "critRate_"), got.map { it.key })
    }

    @Test
    fun `four real substats are all kept`() {
        // 4★+16 真值 24/24 都是 4 条（旧规则「4★最高3」会砍掉第 4 条）
        val lines = listOf(
            line("生命值", "269"),
            line("攻击力", "5.8%"),
            line("元素充能效率", "5.8%"),
            line("暴击率", "3.1%"),
        )
        assertEquals(4, StatParser.parseBlock(lines).size)
    }

    @Test
    fun `blank rows inside the block are skipped but do not terminate`() {
        val lines = listOf(line("防御力", "19"), "", line("元素精通", "21"))
        val got = StatParser.parseBlock(lines)
        assertEquals(2, got.size)
        assertEquals(listOf("def", "eleMas"), got.map { it.key })
    }

    @Test
    fun `garbage row terminates the block`() {
        val lines = listOf(line("攻击力", "5.3%"), "已装备", line("暴击伤害", "7.0%"))
        val got = StatParser.parseBlock(lines)
        assertEquals(1, got.size)
    }

    @Test
    fun `hard cap is four`() {
        val lines = List(6) { line("攻击力", "5.3%") }
        assertEquals(4, StatParser.parseBlock(lines).size)
    }
}
