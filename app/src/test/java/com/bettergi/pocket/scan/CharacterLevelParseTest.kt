package com.bettergi.pocket.scan

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 等级文本 → (level, cap) → ascension（#165）。
 *
 * 钉两件事：
 * 1. **粘连必须拆开** —— OCR 常把「80 / 90」里的 `/` 读成 `1`，得到 `80190`；
 *    2026-09-11 因此把 2244 的 ROI 收窄到只框等级数字，结果 cap 恒 null、
 *    ascension 一律按 level 猜 ⇒ 每一位 level 80 都报 asc 5（真机 8 位错）。
 *    正解是解析侧容错 + ROI 保持能看见 max，不是把 max 裁掉。
 * 2. **level 单独不足以定 ascension** —— level 恰好等于突破门槛（20/40/50/60/70/80/90）时，
 *    "卡在本阶段上限"与"已突破到下一阶段"的等级数字一模一样，只有 cap 能分开。
 */
class CharacterLevelParseTest {

    @Test
    fun `干净的 等级level 斜杠 max 拆得开`() {
        assertEquals(90 to 90, parseLevelText("等级90 / 90"))
        assertEquals(80 to 90, parseLevelText("等级80 / 90"))
        assertEquals(80 to 80, parseLevelText("等级80 / 80"))
        assertEquals(70 to 80, parseLevelText("等级70/80"))
    }

    @Test
    fun `斜杠被读成 1 的粘连串仍能还原`() {
        // 09-11 那条注释里的原始故障样本：10 个中 2 个读成 90190。
        assertEquals(90 to 90, parseLevelText("90190"))
        assertEquals(80 to 90, parseLevelText("80190"))
        assertEquals(80 to 80, parseLevelText("80180"))
        assertEquals(70 to 80, parseLevelText("70180"))
        // 斜杠整个丢掉
        assertEquals(80 to 90, parseLevelText("8090"))
    }

    @Test
    fun `新版上限 95 与 100 也算合法上限`() {
        assertEquals(95 to 100, parseLevelText("等级95 / 100"))
        assertEquals(90 to 100, parseLevelText("90100"))
        assertEquals(90 to 95, parseLevelText("90 / 95"))
    }

    @Test
    fun `斜杠读成独立的 1 分段（点号分隔）时不当上限`() {
        // 20:58 轮真机原文：'等级80.1.90' —— 斜杠被读成一个**独立的** "1"（不是粘进数字串）。
        // "1" 不在合法上限表里，必须从 cap 候选中剔掉，否则 level 80 的角色会被判成 cap=1 之类。
        assertEquals(80 to 90, parseLevelText("等级80.1.90"))
        assertEquals(90 to 90, parseLevelText("等级90/1.90"))
        assertEquals(90 to 100, parseLevelText("等级90.1.100"))
    }

    @Test
    fun `ROI 截断只剩 level 时 cap 报没读到而不是猜一个`() {
        // 这正是 2244 出事时的输入形态。
        assertEquals(80 to null, parseLevelText("80"))
        assertEquals(90 to null, parseLevelText("等级90"))
    }

    @Test
    fun `cap 决定 ascension`() {
        assertEquals(6, ascensionOf(90, 90))
        assertEquals(6, ascensionOf(80, 90)) // ← #165 那 8 位
        assertEquals(5, ascensionOf(80, 80)) // ← 另外 5 位，真值就是 5
        assertEquals(5, ascensionOf(70, 80))
        assertEquals(4, ascensionOf(70, 70))
        assertEquals(1, ascensionOf(20, 40))
        assertEquals(0, ascensionOf(20, 20))
        assertEquals(6, ascensionOf(95, 100))
    }

    @Test
    fun `无 cap 时按 level 分层，门槛处只能猜且极性并不一致`() {
        assertEquals(6, ascensionOf(90, null))
        assertEquals(5, ascensionOf(80, null)) // 猜"已突破"
        assertEquals(6, ascensionOf(81, null))
        // ⚠️ 回退分层是 09-17 GT 反推出来的，各门槛极性不统一：
        //   level 70 → 5（乐观，假定已突破到 cap 80）；level 60 → 3（保守，落在 61 以下）。
        //   两边都可能错，所以 cap 读不到时调用方必须打日志（见 ascensionOf 的 KDoc 与 #165）。
        assertEquals(5, ascensionOf(70, null))
        assertEquals(3, ascensionOf(60, null))
        assertEquals(4, ascensionOf(61, null))
        assertEquals(3, ascensionOf(50, null))
    }

    @Test
    fun `真机 2244 那 13 位 level 80 的角色按新解析对得上 GT`() {
        // GT：8 位 asc 6（cap 90）+ 5 位 asc 5（cap 80）。宽 ROI 后两行文本都读得到。
        val asc6 = listOf("Alyosha", "Chevreuse", "Dehya", "Gaming", "Iansan", "KujouSara", "Noelle", "Qiqi")
        val asc5 = listOf("Diluc", "Diona", "Gorou", "Jean", "Lisa")
        asc6.forEach { assertEquals(it, 6, ascensionOf(80, parseLevelText("等级80 / 90").second)) }
        asc5.forEach { assertEquals(it, 5, ascensionOf(80, parseLevelText("等级80 / 80").second)) }
    }
}
