package com.bettergi.pocket.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 身份串 `#` 后缀语义守门（#52，2026-09-25）。
 *
 * 「连续 ≥3 格副词条块相同 ⇒ 面板半新半旧」这条判据把 `#` 之后的整段当成**副词条块**用。
 * 圣遗物成立（`set/slot/lvl/main#7.4,9.3,10.5,53.0`）；武器身份串是
 * `key/level/rarity#R<精炼>@<位置>` ⇒ 后缀是**精炼等级**，而绝大多数武器都是 R1 ⇒
 * "连续 3 格相同"是常态，于是每一页都会造出一串假失败格：喂给定点重访白烧时间，
 * 并在 `longest*2 >= pageSize` 的**页级冻结快速放弃**判定下把整页静默丢掉。
 *
 * 判据按**语义**门控而不是按 gridKey 硬编码：后缀必须是"逗号分隔的纯数值"。
 */
class IdentitySuffixSemanticsTest {

    @Test
    fun `圣遗物副词条块算数`() {
        assertTrue(ScanEngine.isSubstatBlock("7.4,9.3,10.5,53.0"))
        assertTrue(ScanEngine.isSubstatBlock("3.5,5.1,5.4,23.0"))
        // 空格 / 整数写法不能把真判据打成假阴性
        assertTrue(ScanEngine.isSubstatBlock(" 4.1,16.0 "))
        assertTrue(ScanEngine.isSubstatBlock("2.8,3,11.0"))
        // ⚠️ **尾随逗号不算**副词条块（见下面 `空串与非数值 token` 用例）：身份串是
        //   `substats.map{value}.sorted().joinToString(",")` 拼出来的，正常路径**不可能**产出尾逗号
        //   ⇒ 见到尾逗号只能来自别处（半截 OCR / 手工样本），按"读残缺"处理、不进停滞判据。
        //   （原先这里的注释写成"尾随逗号也不能打成假阴性"，与本文件自己的断言直接矛盾 —— 已更正。）
    }

    @Test
    fun `武器精炼等级不算副词条块`() {
        assertFalse(ScanEngine.isSubstatBlock("R1@"))
        assertFalse(ScanEngine.isSubstatBlock("R5@香菱"))
        // 关键用例：武器常态就是一整页 R1 ⇒ 一旦误判，整页会被当成"页级冻结"放弃
        assertFalse(ScanEngine.isSubstatBlock("R1@角色A"))
    }

    @Test
    fun `空串与非数值 token 都不算副词条块`() {
        assertFalse(ScanEngine.isSubstatBlock(""))
        assertFalse(ScanEngine.isSubstatBlock("hp_,18.0"))      // 混入非数值 token
        assertFalse(ScanEngine.isSubstatBlock(","))             // 两个空 token
        assertFalse(ScanEngine.isSubstatBlock("2.8,"))          // 尾随逗号 ⇒ 半个空 token，读残缺
    }

    @Test
    fun `单值后缀仍算副词条块 - 1 副词条的 3★ 真件`() {
        // run12 尾区实证：`BraveHeart/circlet/0/critRate_#2.8` 是**合法**的 1 副词条 3★ 件。
        // 若因"没有逗号"把它摘出判据，这类件恰好落在列表末尾 —— 正是最容易整页停滞的地方。
        assertTrue(ScanEngine.isSubstatBlock("2.8"))
        assertTrue(ScanEngine.isSubstatBlock("47"))
    }

    @Test
    fun `读失败格（后缀为空）不会开启停滞段`() {
        // 尾区/失败格身份串是 ""，substringAfter('#', "") 给 "" ⇒ 必须走"断开"分支
        val ids = listOf("", "BraveHeart/circlet/0/critRate_#2.8", "", "")
        val blocks = ids.map { ScanEngine.isSubstatBlock(it.substringAfter('#', "")) }
        assertTrue(blocks.joinToString("|"), blocks[1] && !blocks[0] && !blocks[2] && !blocks[3])
    }

    /**
     * 装备者文案截断守门（2026-09-25 武器全量实测）。
     *
     * 185 格里 97 格带「已装…」，其中 **15 格 OCR 把末字「备」吃掉** ⇒ 旧写法
     * `takeIf { contains("已装备") }` 让这 15 件的装备者静默变空。
     *
     * ★ 2026-10-09 双语义改版：
     * ① manage 链路读全行「XX已装备」→ 截断（前三条断言不变）；
     * ② backpack 链路 ROI 收窄到**角色名 3 字区**后文本就是名字 → 不含「已/装/备」的
     *    文本**整体当名字**返回（不再是 null）——旧断言「无标记 ⇒ null」随之作废，
     *    「未装备时该槽根本不进 readRois」（VoteJudges.equippedRowPresent 饱和度门前置），
     *    垃圾文本不会再流到这里；描述文案的防御交由上层角色词典匹配判弃。
     */
    @Test
    fun `装备者文案容忍尾部截断但拒绝把描述当名字`() {
        assertEquals("菲谢尔", ScanEngine.equippedOwnerOf("菲谢尔已装备"))
        assertEquals("九条裟罗", ScanEngine.equippedOwnerOf("九条裟罗已装"))   // 末字被吃
        assertEquals("钟离", ScanEngine.equippedOwnerOf("钟离已"))            // 末两字被吃
        assertEquals("北斗", ScanEngine.equippedOwnerOf(" 北斗已装备 "))       // 带空白
        // ② 名字区直读语义：不含标记 ⇒ 整体当名字（backpack ROI 只框名字区）
        assertEquals("菲谢尔", ScanEngine.equippedOwnerOf("菲谢尔"))
        assertEquals("北斗", ScanEngine.equippedOwnerOf(" 北斗 "))
        assertNull(ScanEngine.equippedOwnerOf(""))
        assertNull(ScanEngine.equippedOwnerOf("   "))
        // 名字区直读下数字/符号原样透传——防御线在角色词典匹配（characterKeyOfDisplay），
        // 且 sat 门已保证只有"已装备"行才进 OCR，垃圾文本到不了这里。
        assertEquals("11", ScanEngine.equippedOwnerOf("11"))
        assertEquals("-", ScanEngine.equippedOwnerOf("-"))
    }
}
