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
 * §12.1 公式：x = 末尾两卡**间隙中点**；y = 锚行（visibleRows，被底栏遮挡行）上沿 +5；
 * dist = traverseRows × 行距。
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
        assertEquals(1858, p.advanceStart("artifact_backpack")!!.x)
        assertEquals(1178, p.advanceStart("artifact_backpack")!!.y)
        assertEquals(876, p.advanceDistance("artifact_backpack"))
    }

    @Test
    fun `weapon backpack start point matches spec`() {
        val p = profile()
        assertEquals(1858, p.advanceStart("weapon_backpack")!!.x)
        assertEquals(1082, p.advanceStart("weapon_backpack")!!.y)
        assertEquals(876, p.advanceDistance("weapon_backpack"))
    }

    /** char_popup 方案里标"待标定"（无期望值），此处按几何推出并与硬编码距离互验。 */
    @Test
    fun `char popup start point derived from colX and rowY`() {
        val p = profile()
        // x = colX[1] + cardW + ((colX[2]-colX[1]) - cardW)/2 = 433 + 205 + (233-205)/2
        assertEquals(652, p.advanceStart("char_popup")!!.x)
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

    @Test
    fun `start x falls inside the gap between last two cards`() {
        val p = profile()
        val raw = p.rawObject("grids.artifact_backpack")!!
        val origin = raw.getJSONArray("cardOrigin")
        val pitch = raw.getJSONArray("pitch")
        val cardW = raw.getJSONArray("cardSize").getInt(0)
        val card5Left = origin.getInt(0) + 5 * pitch.getInt(0)
        val card6Left = origin.getInt(0) + 6 * pitch.getInt(0)
        val x = p.advanceStart("artifact_backpack")!!.x
        assertTrue("起点 x=$x 必须落在第 6 列卡右缘 ${card5Left + cardW} 与第 7 列卡左缘 $card6Left 之间",
            x > card5Left + cardW && x < card6Left)
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
        val base = 1858
        assertEquals(Math.round(base * 0.8).toInt(), p.advanceStart("artifact_backpack")!!.x)
        // y 不缩放（1440 基准）
        assertEquals(1178, p.advanceStart("artifact_backpack")!!.y)
    }
}
