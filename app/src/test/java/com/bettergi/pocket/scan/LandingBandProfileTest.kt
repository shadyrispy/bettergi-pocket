package com.bettergi.pocket.scan

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 落地测量条带 `grids.<key>.landingBand` 的 profile 登记对账
 * （design-docs/swipe-landing-measure.md §1；测量值来源见各 profile note）。
 *
 * 锁定的不是"存在性"而是**具体几何**——条带窗口混入静态区（y 过深）或被状态条污染
 * （x 过左）都会把判别力毁掉（risk1b 反例：含静态区的带 score 只有 0.35）。
 */
class LandingBandProfileTest {

    private fun assetsDir(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val candidate = File(dir, "src/main/assets/dsl")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("src/main/assets/dsl not found")
    }

    private fun profile(name: String, w: Int, h: Int): ScreenProfile {
        val p = ScreenProfile(JSONObject(File(assetsDir(), name).readText()))
        p.calibrate(w, h)
        return p
    }

    /** 3200 基准（5 帧滑动语料 dsl/verify/_swipe_progress_3200/ 实测定稿）。 */
    @Test
    fun `artifact landing band matches 3200 corpus calibration`() {
        val b = profile("profiles.json", 3200, 1440).landingBandFor("artifact_backpack")
        assertNotNull("profiles.json 须登记 artifact_backpack.landingBand", b)
        // x[1200,2080]=cols4-7（0712 筛选状态条文本实测 x≤1200）；y[1172,1235]=R4 可见 63px（1240 起静态）
        assertEquals(1200, b!!.x0); assertEquals(2080, b.x1)
        assertEquals(1172, b.y0); assertEquals(1235, b.y1)
        // 搜索窗含未滚动原位（多步补滑中间落点可测）
        assertEquals(0, b.sy0); assertEquals(1235, b.sy1)
    }

    /** 3200 武器背包：R4 顶 = cardOrigin.y(201)+3×pitch(292) = 1077，与圣遗物同屏视口裁剪（y1235 止）。 */
    @Test
    fun `weapon landing band derived from same viewport clip`() {
        val b = profile("profiles.json", 3200, 1440).landingBandFor("weapon_backpack")
        assertNotNull(b)
        assertEquals(1200, b!!.x0); assertEquals(2080, b.x1)
        assertEquals(1077, b.y0); assertEquals(1235, b.y1)
    }

    /** 2560 档（真机截图 _shots2560/118 圣遗物、125 武器实测定稿：视口裁剪 y1239 止）。 */
    @Test
    fun `2560 profiles carry device-measured bands`() {
        val p = profile("profiles_2560x1440.json", 2560, 1440)
        val a = p.landingBandFor("artifact_backpack")
        assertNotNull("2560 档须登记（网格层为实机标定，不可由 3200 反推）", a)
        assertEquals(1000, a!!.x0); assertEquals(1840, a.x1)
        assertEquals(1124, a.y0); assertEquals(1239, a.y1)
        assertEquals(0, a.sy0); assertEquals(1239, a.sy1)
        val w = p.landingBandFor("weapon_backpack")
        assertNotNull(w)
        assertEquals(1029, w!!.y0); assertEquals(1239, w.y1)
    }

    /**
     * 2244 档（_shots2244/curated，视口内容到 ~1035）。
     * ⚠️ 2026-09-16 修正：圣遗物 y0 由 **767 → 774**（= 162 + 3×204）。原 767 从来不是行边界，
     * 系抄错（= 2244 武器 766+1）；独立复核见下方「最后一行卡顶」通用不变量与 profile note。
     */
    @Test
    fun `2244 profiles carry device-measured bands`() {
        val p = profile("profiles_2244x1080.json", 2244, 1080)
        val a = p.landingBandFor("artifact_backpack")
        assertNotNull("2244 档须登记", a)
        assertEquals(820, a!!.x0); assertEquals(1435, a.x1)
        assertEquals(774, a.y0); assertEquals(945, a.y1)
        val w = p.landingBandFor("weapon_backpack")
        assertNotNull(w)
        assertEquals(766, w!!.y0); assertEquals(945, w.y1)
    }

    /**
     * ★ 通用不变量（2026-09-16 加）：比硬编码字面量强得多 —— 登记的条带顶必须落在
     * **第 visibleRows 行（末行，即滑动锚行）的卡顶**上：`y0 = cardOrigin.y + (rows−1)×rowPitch ±2px`。
     *
     * 为什么值得单独立一条：字面量断言只能当"变更哨兵"，**无法发现测量本身是错的**——
     * 2244 圣遗物的 767 就是这么漏过去的（767 与 774 差 7px，而它根本不是任何行边界；
     * 对截图做「卡顶向下强边」检测，强边成对 353/364、557/568、761/772，下沿与几何预测
     * 366/570/774 差 −2px）。这条规则一次就能抓出那类错误。
     * 容差 ±2：3200 圣遗物为 1172（语料实测）vs 几何 1173，差 1px 属测量噪声。
     */
    @Test
    fun `every registered band starts at the last visible row top`() {
        val cases = listOf(
            Triple("profiles.json", 3200 to 1440, listOf("artifact_backpack", "weapon_backpack")),
            Triple("profiles_2560x1440.json", 2560 to 1440, listOf("artifact_backpack", "weapon_backpack")),
            Triple("profiles_2244x1080.json", 2244 to 1080, listOf("artifact_backpack", "weapon_backpack")),
        )
        var checked = 0
        for ((name, size, grids) in cases) {
            val p = profile(name, size.first, size.second)
            for (g in grids) {
                val raw = p.rawObject("grids.$g")!!
                val originY = raw.getJSONArray("cardOrigin").getInt(1)
                val pitchY = raw.getJSONArray("pitch").getInt(1)
                val rows = raw.getInt("visibleRows")
                val expected = originY + (rows - 1) * pitchY
                val b = p.landingBandFor(g) ?: error("$name / $g 未登记 landingBand")
                assertTrue(
                    "$name/$g landingBand.y0=${b.y0} 应等于第 $rows 行卡顶 $expected（±2px）",
                    Math.abs(b.y0 - expected) <= 2,
                )
                assertTrue("$name/$g 条带高应 > 8px（模板太薄无法匹配）", b.y1 - b.y0 > 8)
                checked++
            }
        }
        assertEquals("应覆盖 3 档 × 2 网格", 6, checked)
    }

    /** 未登记的网格（如 char_popup）返回 null ⇒ 引擎回退既有测量路径，不改变行为。 */
    @Test
    fun `unregistered grid returns null`() {
        assertNull(profile("profiles.json", 3200, 1440).landingBandFor("char_popup"))
    }
}
