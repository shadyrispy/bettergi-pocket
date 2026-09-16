package com.bettergi.pocket.scan

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 网格几何 + §12.1 翻页起点规范化守门（方案 §12.1 / dsl/verify/swipe_adaptive_benchmark.md）。
 *
 * profiles 里存在**两种网格写法**，必须都支持：
 * - 风格 A：`cardOrigin` + `pitch`（artifact_backpack / weapon_backpack，7 列）
 * - 风格 B：`colX` / `rowY` 数组（char_popup / select_3col，3 列）
 * 旧 `cellCenter` 只认风格 A → char_popup 取 cell 中心会抛 JSONException（角色扫描潜在崩溃）。
 *
 * 翻页起点公式（2026-09-16 修订）：x = **最左**两卡**间隙中点**；y = 锚行（visibleRows，
 * 被底栏遮挡行）上沿 +5；dist = traverseRows × 行距。
 *
 * ⚠️ 起点取哪一段缝隙是**有讲究的**：原来取「末尾两卡缝隙」→ 2560 上算出 x=1627，恰好贴住右侧
 * 详情面板左缘（面板 x≳1640）⇒ 拖拽被判成面板操作、网格完全不滚（`adb input swipe` 定案：
 * x=1627 帧差 0.26% vs x=1041 28.64%）⇒ 改为**最左**一段（3200→638、2560→462），
 * 既仍落在卡缝里（不触发拖卡），又远离面板。实测起点与设备日志逐值一致。
 */
class GridGeometryTest {

    private fun profile(): ScreenProfile {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val candidate = File(dir, "src/main/assets/dsl")
            if (candidate.isDirectory) {
                return ScreenProfile(JSONObject(File(candidate, "profiles.json").readText())).apply {
                    calibrate(3200, 1440) // 1:1，base 坐标 = 帧坐标
                }
            }
            dir = dir.parentFile ?: return@repeat
        }
        error("src/main/assets/dsl not found")
    }

    // ---------- §12.1 起点（方案给定期望值）----------
    @Test
    fun `artifact backpack start point matches spec`() {
        val p = profile()
        // 最左卡缝：416 + cardW200 + (pitch244−200)/2 = 638（设备日志 几何起点 base=(638,1178) 逐值一致）
        assertEquals(638, p.advanceStart("artifact_backpack")!!.x)
        assertEquals(1178, p.advanceStart("artifact_backpack")!!.y)
        assertEquals(876, p.advanceDistance("artifact_backpack"))
    }

    @Test
    fun `weapon backpack start point matches spec`() {
        val p = profile()
        assertEquals(638, p.advanceStart("weapon_backpack")!!.x)
        assertEquals(1082, p.advanceStart("weapon_backpack")!!.y)
        assertEquals(876, p.advanceDistance("weapon_backpack"))
    }

    /** char_popup 方案里标"待标定"（无期望值），此处按几何推出并与硬编码距离互验。 */
    @Test
    fun `char popup start point derived from colX and rowY`() {
        val p = profile()
        // x = colX[0] + cardW + ((colX[1]-colX[0]) - cardW)/2 = 200 + 205 + (233-205)/2 = 419
        // ⚠️ 2026-09-16：与背包网格**统一取最左缝隙**（旧值 652 = colX[1] 那段）。char_popup 不受
        //    「避右侧面板」动机影响，但 419 仍在 col0(200..405) / col1(433..638) 之间 ⇒ 同样安全，
        //    且"取最左"这条规则只有一个分支、不按网格分叉。
        assertEquals(419, p.advanceStart("char_popup")!!.x)
        // y = rowY[visibleRows-1] + 5 = 1029 + 5
        assertEquals(1034, p.advanceStart("char_popup")!!.y)
        assertEquals(841, p.advanceDistance("char_popup"))
    }

    @Test
    fun `computed distance reproduces hardcoded advance distance`() {
        // 反验是几何模型正确性的关键证据：三个网格的计算距离必须与 profiles 写死值一致
        val p = profile()
        for (grid in listOf("artifact_backpack", "weapon_backpack", "char_popup")) {
            val hardcoded = p.rawObject("grids.$grid")!!.getJSONObject("advance").getInt("distance")
            val computed = p.advanceDistance(grid)
            assertEquals("grid '$grid' 计算距离应复现硬编码值", hardcoded, computed)
        }
    }

    /**
     * 起点必须落在**相邻两卡的缝隙**里（压在卡上会被判成拖卡 ⇒ 不滚，equip12 实证），
     * 且必须**远离右侧详情面板**（2560 踩过的坑）。这两条合起来才是 `advanceStart` 的不变量；
     * 至于取"第几段"缝隙由 KDoc 的动机说明负责，本测试只钉不变量本身。
     */
    @Test
    fun `start x falls inside the first card gap and clears the detail panel`() {
        val p = profile()
        val raw = p.rawObject("grids.artifact_backpack")!!
        val origin = raw.getJSONArray("cardOrigin")
        val pitch = raw.getJSONArray("pitch")
        val cardW = raw.getJSONArray("cardSize").getInt(0)
        val card0Left = origin.getInt(0)
        val card1Left = origin.getInt(0) + pitch.getInt(0)
        val x = p.advanceStart("artifact_backpack")!!.x
        assertTrue("起点 x=$x 必须落在第 1 列卡右缘 ${card0Left + cardW} 与第 2 列卡左缘 $card1Left 之间",
            x > card0Left + cardW && x < card1Left)
        // 面板左缘：3200 ≈2180/屏宽 0.68、2560 ≈1640/0.64 ⇒ 取 0.60 为安全上界
        assertTrue("起点 x=$x 应远离右侧详情面板（须 < 屏宽 60% = 1920）", x < 3200 * 0.60)
    }

    // ---------- 风格 B 修复（旧实现会抛异常）----------
    @Test
    fun `cell center supports colX and rowY grids`() {
        val p = profile()
        // char_popup 卡 205x242，colX=200/433/666，rowY=188/467/748/1029
        assertEquals(302, p.cellCenter("char_popup", 0).x) // 200 + 205/2
        assertEquals(309, p.cellCenter("char_popup", 0).y) // 188 + 242/2
        assertEquals(535, p.cellCenter("char_popup", 4).x) // index4 = row1,col1 → 433+102
        assertEquals(588, p.cellCenter("char_popup", 4).y) // 467+121
    }

    /** 风格 A 行为不变（与 ScanEngineDryRunTest 的点击坐标期望一致）。 */
    @Test
    fun `cell center unchanged for origin pitch grids`() {
        val p = profile()
        assertEquals(516, p.cellCenter("artifact_backpack", 0).x) // 416 + 200/2
        assertEquals(423, p.cellCenter("artifact_backpack", 0).y) // 297 + 253/2
    }

    // ---------- 几何不足 → null（调用方回退写死坐标）----------
    @Test
    fun `non card grid yields no geometry`() {
        val p = profile()
        // set_filter_popup 无 cardSize、cols 是 {left,right} 对象 → 不适用卡片网格公式
        assertNull(p.advanceStart("set_filter_popup"))
        assertNull(p.advanceDistance("set_filter_popup"))
    }

    @Test
    fun `scaling applies to derived start point`() {
        val p = profile()
        p.calibrate(2560, 1440) // scaleX = 0.8
        val base = 638
        assertEquals(Math.round(base * 0.8).toInt(), p.advanceStart("artifact_backpack")!!.x)
        // y 不缩放（1440 基准）
        assertEquals(1178, p.advanceStart("artifact_backpack")!!.y)
    }
}
