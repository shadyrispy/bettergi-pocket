package com.bettergi.pocket.scan

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 祝圣横幅探测器（`zones["artifact.panel.zhusheng"]` + `zhushengYShiftFrame`）**逐档锁定**。
 *
 * 为什么值得单独锁：这条判据一旦不命中，后果不是"报错"而是**静默丢件**——
 * `vars.crafted` 恒 false ⇒ 不施加 yShift ⇒ level/副词条 ROI 读到横幅与空档 ⇒ 词条 0 条
 * ⇒ 被「空壳丢弃」判为读失败 ⇒ 整件不入库，日志里只有一句"读失败"。
 * 同类事故已三次：#24（3200 写死色域）、2026-09-23（2560 沿用 3200 点位 ⇒ 23 件）、
 * 2026-09-25（2244 同样沿用 ⇒ 一轮丢 25 件，1074/GT1099；宽松键复核：缺的 25 件 = 23 个 5★ + 2 个 3★）。
 *
 * 关键事实：**横幅色不是跨档常量**。3200 标定 (145,107,190)，2244 实测 (220,192,255)
 * （同一条紫色梯度的不同亮度段）⇒ 写死的 [VoteJudges.PURPLE_BANNER] 与 2560 的覆盖域都拒 2244 的值。
 * 所以本测试锁的是"该档有没有带上自己量出来的色域/点位/位移"，而不是它们彼此相等。
 */
class ZhushengBannerProfileTest {

    private fun zoneJson(name: String): JSONObject {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val candidate = File(dir, "src/main/assets/dsl")
            if (candidate.isDirectory) return JSONObject(File(candidate, name).readText())
            dir = dir.parentFile ?: return@repeat
        }
        error("src/main/assets/dsl not found")
    }

    private fun points(z: JSONObject): List<List<Int>> =
        z.getJSONArray("points").let { a ->
            (0 until a.length()).map { i -> a.getJSONArray(i).let { p -> listOf(p.getInt(0), p.getInt(1)) } }
        }

    private fun rgb(z: JSONObject): List<Int>? =
        z.optJSONArray("rgb")?.let { a -> (0 until a.length()).map { a.getInt(it) } }

    @Test
    fun `each tier carries its own measured banner points, predicate and shift`() {
        val cases = listOf(
            // file, shift, points, rgb(=null 表示沿用写死的 PURPLE_BANNER), ratio
            "profiles.json" to Quad(63, listOf(listOf(2390, 703), listOf(2400, 703), listOf(2410, 703)), null, null),
            "profiles_2560x1440.json" to Quad(
                63, listOf(listOf(1912, 703), listOf(1920, 703), listOf(1928, 703)),
                listOf(100, 215, 40, 140, 180, 255, 0, 60), 0.15,
            ),
            "profiles_2244x1080.json" to Quad(
                48, listOf(listOf(1750, 530), listOf(1900, 530), listOf(2050, 530)),
                listOf(140, 245, 120, 220, 200, 255, 0, 35), 0.6,
            ),
        )
        for ((file, q) in cases) {
            val root = zoneJson(file)
            val z = root.getJSONObject("zones").getJSONObject("artifact.panel.zhusheng")
            assertEquals("$file zhusheng.points", q.points, points(z))
            assertEquals("$file zhushengYShiftFrame", q.shift, root.getInt("zhushengYShiftFrame"))
            assertEquals("$file zhusheng.rgb", q.rgb, rgb(z))
            if (q.ratio == null) {
                assertEquals("$file 不该带 ratio", false, z.has("ratio"))
            } else {
                assertEquals("$file zhusheng.ratio", q.ratio, z.getDouble("ratio"), 1e-9)
            }
        }
    }

    /**
     * 2244 的横幅色必须**落在自己配的域内**、且**落在另外两档的域外**：
     * 这是"跨档复制色域会静默失效"的最小复现（2026-09-25 的 2244 档就是这么丢掉那 25 件里的 23 个 5★）。
     */
    @Test
    fun `2244 banner color is rejected by the other tiers windows`() {
        val banner2244 = Triple(220, 192, 255) // 三点实测恒值（逐通道 min==max）
        val cream = Triple(236, 229, 216) // 普通面板同点位底色
        val own = rgbOf("profiles_2244x1080.json")!!
        val base = listOf(110, 200, 50, 130, 150, 235, 0, 60) // VoteJudges.PURPLE_BANNER
        val t2560 = rgbOf("profiles_2560x1440.json")!!

        assertEquals(true, matches(own, banner2244))
        assertEquals("普通面板底色不得被判成紫", false, matches(own, cream))
        assertEquals("2244 横幅沿用 3200 写死域应当判不出（这正是 bug）", false, matches(base, banner2244))
        assertEquals("2244 横幅沿用 2560 覆盖域也应当判不出", false, matches(t2560, banner2244))
    }

    private fun rgbOf(file: String): List<Int>? =
        zoneJson(file).getJSONObject("zones").getJSONObject("artifact.panel.zhusheng")
            .optJSONArray("rgb")?.let { a -> (0 until a.length()).map { a.getInt(it) } }

    /** 复刻 RgbPredicate.matches：三通道区间 + requireRB(R−B>) + requireBG(B−G>)。 */
    private fun matches(p: List<Int>, c: Triple<Int, Int, Int>): Boolean =
        c.first in p[0]..p[1] && c.second in p[2]..p[3] && c.third in p[4]..p[5] &&
            (p.getOrElse(6) { 0 } == 0 || c.first - c.third > p[6]) &&
            (p.getOrElse(7) { 0 } == 0 || c.third - c.second > p[7])

    private data class Quad(
        val shift: Int,
        val points: List<List<Int>>,
        val rgb: List<Int>?,
        val ratio: Double?,
    )
}
