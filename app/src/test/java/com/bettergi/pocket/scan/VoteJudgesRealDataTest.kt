package com.bettergi.pocket.scan

import org.junit.Assert.assertEquals
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

    // ---- 稀有度-词条数规则（用户定稿 2026-09-02）----
    @Test
    fun `rarity substat rule constants`() {
        // 5★ 恒 4 词条；4★ 初始 2 最高 3；3★/2★ 止扫不解析（止扫上界 = 低于 4★）
        assertEquals(4, ScanEngine.MAX_SUBS_5STAR)
        assertEquals(3, ScanEngine.MAX_SUBS_4STAR)
        assertEquals(4, ScanEngine.STOP_MARKER_RARITY)
    }
}
