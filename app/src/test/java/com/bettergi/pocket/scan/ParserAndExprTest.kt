package com.bettergi.pocket.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 纯函数逻辑单测：StatParser（zh 词条→GOOD key，GOODScanner 键风格）与 Expr（stopWhen 求值器）。
 * OCR 管线的解析层离线全覆盖，样本取自 dsl flow/面板真实文案。
 */
class ParserAndExprTest {

    // ---- StatParser.parse：副词条 ----
    @Test
    fun `parse percent substats`() {
        assertEquals("critRate_", StatParser.parse("暴击率+5.8%")!!.key)
        assertEquals(5.8, StatParser.parse("暴击率+5.8%")!!.value, 1e-9)
        assertEquals("critDMG_", StatParser.parse("暴击伤害+14.0%")!!.key)
        assertEquals("enerRech_", StatParser.parse("元素充能效率+12.4%")!!.key)
        assertEquals("eleMas", StatParser.parse("元素精通+23")!!.key)
        assertEquals("physical_dmg_", StatParser.parse("物理伤害加成+12.3%")!!.key)
        assertEquals("hp_", StatParser.parse("生命值+5.8%")!!.key)
        assertEquals("atk_", StatParser.parse("攻击力+11.7%")!!.key)
        assertEquals("def_", StatParser.parse("防御力+14.6%")!!.key)
    }

    @Test
    fun `parse flat substats`() {
        assertEquals("hp", StatParser.parse("生命值+717")!!.key)
        assertEquals(717.0, StatParser.parse("生命值+717")!!.value, 1e-9)
        assertEquals("atk", StatParser.parse("攻击力+19")!!.key)
        assertEquals("def", StatParser.parse("防御力+23")!!.key)
    }

    @Test
    fun `parse handles cjk spaces and ocr digit errors`() {
        // OCR 在 CJK 间插空格
        assertEquals("critDMG_", StatParser.parse("暴击 伤害+5.8%")!!.key)
        // OCR 数字纠错 n→0 l→1 o→0
        assertEquals(105.0, StatParser.parse("攻击力+1o5")!!.value, 1e-9)
        assertEquals(10.0, StatParser.parse("攻击力+1n")!!.value, 1e-9)
    }

    @Test
    fun `parse suffix fallback for corrupted first char`() {
        // 首字符被图标干扰（GOODScanner 同款场景：晨击伤害）
        assertEquals("critDMG_", StatParser.parse("晨击伤害+5.8%")!!.key)
    }

    @Test
    fun `parse rejects non-stat text`() {
        assertNull(StatParser.parse(""))
        assertNull(StatParser.parse("圣遗物 1026/2400"))
    }

    // ---- StatParser.slotKeyOf ----
    @Test
    fun `slot keys`() {
        assertEquals("flower", StatParser.slotKeyOf("生之花"))
        assertEquals("plume", StatParser.slotKeyOf("死之羽"))
        assertEquals("sands", StatParser.slotKeyOf("时之沙"))
        assertEquals("goblet", StatParser.slotKeyOf("空之杯"))
        assertEquals("circlet", StatParser.slotKeyOf("理之冠"))
        assertNull(StatParser.slotKeyOf("随便什么"))
    }

    // ---- StatParser.extractValue（主词条数值）----
    @Test
    fun `extract values`() {
        assertEquals(4780.0, StatParser.extractValue("4,780")!!, 1e-9)
        assertEquals(46.6, StatParser.extractValue("46.6%")!!, 1e-9)
        assertEquals(90.0, StatParser.extractValue("Lv.90")!!, 1e-9)
        assertNull(StatParser.extractValue("无数字"))
    }

    // ---- Expr：stopWhen 谓词（flow2 真实用例）----
    @Test
    fun `expr stopWhen artifact case`() {
        val expr = "rarity < 4 && level == 0"
        assertEquals(true, Expr.eval(expr, mapOf("rarity" to 3, "level" to 0)))
        assertEquals(false, Expr.eval(expr, mapOf("rarity" to 4, "level" to 0)))
        assertEquals(false, Expr.eval(expr, mapOf("rarity" to 3, "level" to 20)))
    }

    @Test
    fun `expr missing vars default to zero`() {
        assertEquals(true, Expr.eval("rarity < 4", emptyMap()))
    }

    @Test
    fun `expr or and precedence`() {
        // && 优先于 ||：(a==1 && a==0)=false，b==5=false → false；b==9 时为 true
        assertEquals(false, Expr.eval("a == 1 && a == 0 || b == 5", mapOf("a" to 0, "b" to 9)))
        assertEquals(true, Expr.eval("a == 1 && a == 0 || b == 9", mapOf("a" to 0, "b" to 9)))
    }

    @Test
    fun `expr comparison operators`() {
        assertEquals(true, Expr.eval("a <= 2", mapOf("a" to 2)))
        assertEquals(true, Expr.eval("a >= 2", mapOf("a" to 3)))
        assertEquals(true, Expr.eval("a != 2", mapOf("a" to 3)))
        assertEquals(true, Expr.eval("a > 2", mapOf("a" to 3)))
    }

    @Test
    fun `expr rejects bad syntax`() {
        assertThrows(Expr.EvalException::class.java) { Expr.eval("rarity < ", emptyMap()) }
        assertThrows(Expr.EvalException::class.java) { Expr.eval("rarity @ 4", emptyMap()) }
    }
}
