package com.bettergi.pocket.scan

import com.bettergi.pocket.scan.ScanEngine.Companion.FILTER_PAGE_CONSISTENCY_MIN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 套装筛选**页级判据**（#147 两道信号 + #169 的落点越界与半行轻推）。
 *
 * 输入形态全部来自真机取证：正常页 16 行全能认出套装名；列表落点 δ 偏出行带余量后
 * 读出来是"自信乱码"（实测 `'禁时文歌'`/`'首时之歌'`）。
 * ⚠️ 旧版本这里把"底部页读成空"归因成"灰化低对比度（#147⑥ 另一条独立洞）"，
 *   2026-10-02 #169 复查**推翻**：同一批套名在邻页一字不差读对过，劣化总是**整页**命中，
 *   真因是 rec-only 面板的名义行带远高于一行字（见 `GridAlign.textLineCrop` KDoc 与
 *   `dsl/verify/_audit/SETFILTER-LINEFIT-20261002.md`）。判据的**动作**没变，只是原因换了。
 * 三条自洽度用例的意义在于**区分**"读成空"与"读成乱码"：只有后者该重读。
 */
class FilterPageConsistencyTest {

    @Test
    fun `干净页判稳`() {
        assertEquals(1.0, filterPageConsistency(16, 0, 16).toDouble(), 1e-4)
        assertTrue(filterPageConsistency(16, 0, 16) >= FILTER_PAGE_CONSISTENCY_MIN)
    }

    @Test
    fun `整页乱码判未稳`() {
        // 16 行都读到字，只 2 行认成套装名 ⇒ 典型的跨行切分
        val c = filterPageConsistency(16, 0, 2)
        assertEquals(0.125, c.toDouble(), 1e-4)
        assertTrue("乱码页必须低于阈值才会触发重读", c < FILTER_PAGE_CONSISTENCY_MIN)
    }

    @Test
    fun `读成空的行不算未稳（分母只数非空行）`() {
        // 尾页：8 行"量不到 ⇒ 不读不点"（读成空）+ 8 行正常且全部认出 ⇒ 分母只数非空的 8 行。
        // 不这么算的话，"到底了"会被误判成"没读稳"，每页白等两次重读（#147 当初的动机）。
        val c = filterPageConsistency(16, 8, 8)
        assertEquals(1.0, c.toDouble(), 1e-4)
        assertFalse("读成空的页不许被这条判据判成未稳（否则每页白等两次重读）", c < FILTER_PAGE_CONSISTENCY_MIN)
    }

    @Test
    fun `整页空白没有证据就不判未稳`() {
        val c = filterPageConsistency(16, 16, 0)
        assertEquals(1.0, c.toDouble(), 1e-4)
        assertFalse(c < FILTER_PAGE_CONSISTENCY_MIN)
    }

    /** 阈值边界要钉住：0.75 是"含"的（`<` 才判未稳），改判据符号会静默多读一整批页。 */
    @Test
    fun `阈值边界取含`() {
        val atMin = filterPageConsistency(16, 0, 12) // 12/16 = 0.75
        assertEquals(FILTER_PAGE_CONSISTENCY_MIN.toDouble(), atMin.toDouble(), 1e-4)
        assertFalse("恰好等于阈值 ⇒ 判稳，不重读", atMin < FILTER_PAGE_CONSISTENCY_MIN)
        assertTrue(filterPageConsistency(16, 0, 11) < FILTER_PAGE_CONSISTENCY_MIN)
    }

    // ── 第二道信号：两次读数逐字相同 ⇒ "稳定但读不出"，别再重读（#147⑥）──

    @Test
    fun `没有对比基准时不许宣称已稳`() {
        assertFalse(filterPageStable(null, listOf("L157祭冰之人")))
    }

    @Test
    fun `两次读数一字不差 判已稳`() {
        // 静止态：三次读数一字不差 ⇒ 再等也不会变，收手是对的。
        // （旧注释说这是"灰化套读不出"，#169 已推翻：那是行位落点偏出行带余量，见本文件头。）
        val a = listOf("L157经冰之人", "R157终火之人", "L361学十", "R361注")
        assertTrue(filterPageStable(a, a.toList()))
    }

    @Test
    fun `同一行读出不同字 判仍在漂`() {
        // 00:33 轮第 3 页同一行两遍：'双保限市的服务' → '平自限事的路者' ⇒ 还在滑，值得等再读
        val prev = listOf("L463双保限市的服务", "L565饮话")
        val next = listOf("L463平自限事的路者", "L565饮话")
        assertFalse(filterPageStable(prev, next))
    }

    // ── #169 §8-5：落点越出列表视口 ⇒ 整格不做（不许钳到边界内继续点）──

    /** 三档真机量出来的边界形状：末行最深落点 vs 面板 bounds 底。 */
    @Test
    fun `末行最深落点越出面板时不点`() {
        // 2244：末行名义中心 916、窗 half 51 ⇒ 最深 967，而 bounds 底 965 ⇒ 越界
        assertNull(filterClickYOrNull(916, 51, 150, 965))
        // 但 ph=+49（真机 2244 尾页实测最大值）落在 965 ⇒ 含边界，点
        assertEquals(965L, filterClickYOrNull(916, 49, 150, 965)!!.toLong())
        // 2560：末行中心 1222 + 68 = 1290 > bounds 底 1272 ⇒ 越界
        assertNull(filterClickYOrNull(1222, 68, 200, 1272))
        // 3200：1222 + 68 = 1290 ≤ 1292 ⇒ 三档里唯一不越界的
        assertEquals(1290L, filterClickYOrNull(1222, 68, 200, 1292)!!.toLong())
        // 首行向上越界同理（窗 half 能把行 0 的落点顶到面板之上）
        assertNull(filterClickYOrNull(270, -71, 200, 1272))
        assertEquals(200L, filterClickYOrNull(270, -70, 200, 1272)!!.toLong())
    }

    /**
     * 反向验红守门：这条判据存在的唯一理由，是"重置"钮就在末行左列落点的正下方
     * （2244 clearBtn 上沿 974 / 3200 1299）。把越界改成"钳到边界内继续点"，本条立刻红 ——
     * 而那正是"读 B 点 A"的回来。
     */
    @Test
    fun `越界时返回 null 而不是钳到边界内`() {
        val y = filterClickYOrNull(916, 51, 150, 965)
        assertNull("越界必须整格不做；钳到 965 等于把点击挪到另一张卡上", y)
    }

    // ── #169 §6-1：整页大面积"量不到" ⇒ 往回轻推半行重读本页 ──

    @Test
    fun `轻推门槛按两轮实测的空行分布定`() {
        // 2560 实测空行分布 3/16、3/16、5/16、7/16 与 3200 的 4/16、5/16 ⇒ 门槛 4/16=0.25：
        // 5 行与 7 行都该推（这两档尾页真出现过），3 行不推（正常尾页就有，别白烧一次滑动）
        assertTrue(shouldNudgeFilterPage(16, 7, 0, FILTER_PAGE_NUDGE_BUDGET))
        assertTrue(shouldNudgeFilterPage(16, 5, 0, FILTER_PAGE_NUDGE_BUDGET))
        assertTrue(shouldNudgeFilterPage(16, 4, 0, FILTER_PAGE_NUDGE_BUDGET))
        assertFalse(shouldNudgeFilterPage(16, 3, 0, FILTER_PAGE_NUDGE_BUDGET))
        // ⚠️ 反向验红：门槛曾定在 1/3 ⇒ 两轮真机一次都没触发（判据不响＝没写）。
        //   把常量改回 1/3，本用例的 4/16 与 5/16 两条立刻红。
    }

    @Test
    fun `每页只推一次`() {
        assertFalse("预算用满 ⇒ 不再推（继续推只会白烧，说明不是窗沿问题）",
            shouldNudgeFilterPage(16, 12, FILTER_PAGE_NUDGE_BUDGET, FILTER_PAGE_NUDGE_BUDGET))
        assertTrue(shouldNudgeFilterPage(16, 12, 0, FILTER_PAGE_NUDGE_BUDGET))
    }

    @Test
    fun `样本太少的页不推`() {
        // 7 行里空 7 行看着像"全坏"，但样本太小不足以判窗沿；交给翻页而不是轻推
        assertFalse(shouldNudgeFilterPage(7, 7, 0, FILTER_PAGE_NUDGE_BUDGET))
        assertTrue(shouldNudgeFilterPage(8, 8, 0, FILTER_PAGE_NUDGE_BUDGET))
    }
}
