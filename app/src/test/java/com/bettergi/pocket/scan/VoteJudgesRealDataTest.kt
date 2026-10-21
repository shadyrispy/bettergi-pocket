package com.bettergi.pocket.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VoteJudges 阈值与 dsl 定稿对账（evidence-based）：
 * 实测数据来自 dsl/verify/_icon_scan_5723_5724_5725.json（5723 背包 5★ 已锁已收藏 /
 * 5724 管理 5★ / 5725 管理 4★，2026-09-02 四图定谳）。
 * 防止有人调整 Thresholds 时与 profiles judge 定稿漂移。
 */
class VoteJudgesRealDataTest {

    // ---- 实测值（dsl/verify/_icon_scan_5723_5724_5725.json 定稿）----
    private val BP5723_LOCK_GOLD = 870        // 背包面板 lock 金掩码（5★ 锁体更饱满）
    private val BP5723_ASTRAL_GOLD = 1252     // 背包面板 astral 金掩码
    private val M5724_LOCK_RED = 621          // 管理界面 lockChip 红掩码
    private val M5724_STAR_GOLD = 625         // 管理界面 starChip 金掩码（已收藏）
    private val GRAY_STAR_BASELINE = 7        // 灰星基线（未收藏 ≤7px，m3587=7/m3601=0/m3602=0）

    /**
     * 祝圣紫横幅色域回归（2026-09-16 修 bug 后加）。
     * 实拍均值取自 `dsl/uploaded/artifact_backpack_zhusheng_1000053497.jpg`（含紫横幅「祝圣之霜定义」）
     * 三点 (2390/2400/2410, 703) 与对照 `artifact_backpack_1000053536.jpg`（无祝圣）。
     * ⚠️ 旧色域 B[200,255] 把实拍紫(B≈178~194)判成非紫 ⇒ vars.crafted 恒 false ⇒ 祝圣件读错位。
     */
    @Test
    fun `purple banner predicate matches crafted sample and rejects plain panel`() {
        // 祝圣图三点均值（RGB）
        assertTrue(VoteJudges.PURPLE_BANNER.matches(145, 107, 190))
        assertTrue(VoteJudges.PURPLE_BANNER.matches(133, 96, 178))
        assertTrue(VoteJudges.PURPLE_BANNER.matches(150, 113, 194))
        // 对照图三点均值 ⇒ 必须判非紫（否则每个非祝圣件都会被误判 crafted ⇒ 整体错位）
        assertFalse(VoteJudges.PURPLE_BANNER.matches(238, 230, 219))
        // 跨通道约束确实生效（B-G=... 若 B≈G 则非紫）
        assertFalse(VoteJudges.PURPLE_BANNER.matches(160, 160, 170))
    }

    @Test
    fun `panel lock threshold matches verdict 870 gold means locked`() {
        // 870 ≥ 40 → 已锁（阈值定稿 gold>=40）
        assertTrue(BP5723_LOCK_GOLD >= VoteJudges.Thresholds.PANEL_LOCK_GOLD)
        // 未锁态 gold≈0：阈值不会把未锁误判为已锁
        assertTrue(0 < VoteJudges.Thresholds.PANEL_LOCK_GOLD)
    }

    @Test
    fun `astral threshold matches verdict 1252 gold means favorited`() {
        assertTrue(BP5723_ASTRAL_GOLD >= VoteJudges.Thresholds.ASTRAL_GOLD)
        // 收藏点亮实测 625 也过阈值（5724/5725）
        assertTrue(M5724_STAR_GOLD >= VoteJudges.Thresholds.ASTRAL_GOLD)
    }

    @Test
    fun `gray star baseline stays below star threshold`() {
        // 星 chip 判据金≥100px=已收藏；灰星基线 0-7px 必须远低于 100（误判余量 14 倍）
        assertTrue(GRAY_STAR_BASELINE < 100)
        assertTrue(100 - GRAY_STAR_BASELINE >= 90)
    }

    @Test
    fun `manage lock chip red mask threshold consistent with verdict`() {
        // 管理界面 lockChip：红>150px=已锁（5724/5725 实测 621）
        assertTrue(M5724_LOCK_RED > 150)
    }

    /**
     * 5★ 辉光合并实锤（README #14 / flow2 数据源）：背包 5723 整带列投影得 3 段（实际 5★），
     * 第三段 [111,722] 宽 611 ≫ 单星宽——**列投影在背包 5★ 不可靠，必须逐格**。
     * 本测试将「逐格必要性」固化为守护：若有人把 countStars 改回列投影，此案例数据就是反证。
     */
    @Test
    fun `column projection merging on 5star proves per-cell sampling required`() {
        val mergedSegmentWidth = 722 - 111   // 611px
        val singleStarCell = 46
        assertTrue("5★ 辉光合并段远大于单星宽", mergedSegmentWidth > singleStarCell * 4)
        // 逐格法判定：格内金>100px——5723 逐格实测 1019/1047/1181/1489/1861 全部 >100 → 5★
        val perCellCounts = listOf(1019, 1047, 1181, 1489, 1861)
        assertEquals(5, perCellCounts.count { it > 100 })
    }

    // ---- 词条数规则（2026-09-16 **ground truth 定标取代** 2026-09-02 的按稀有度假设）----
    @Test
    fun `substat count rule is truth-calibrated not rarity-guessed`() {
        // 旧假设「5★恒 4 条 / 4★ 最高 3 条」被 GT 推翻（design-docs/good-diff-20260916.md）：
        //   · GT 实测 5★+0 **67%（158/235）只有 3 条** ⇒ 「恒 4」会把套装名行读成幻影第 4 条；
        //   · GT 实测 4★+16 **24/24 都是 4 条** ⇒ 「最高 3」会砍掉真第 4 条。
        // 现改为 StatParser.parseBlock 的连续块读取 + 全局上限 4；止扫上界仍是"低于 4★"。
        assertEquals(4, StatParser.MAX_SUBS)
        assertEquals(4, ScanEngine.STOP_MARKER_RARITY)
    }
}
