package com.bettergi.pocket.scan

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #316（2026-10-19）：祝圣横幅**误报自愈**两条守门的接缝测试。
 *
 * 背景（r4 全量日志实证）：上一格祝圣横幅未褪尽的过渡帧让 `vote zhusheng` 误报 ⇒ 本格被
 * 当作祝圣件施加 yShift ⇒ ROI 整体下移**恰好一行**（zhushengYShiftFrame 每档都 == 副词条
 * 行距）⇒ level 徽标位读到上一行残留、首条副词条丢失 ⇒ 身份键变化被 GT 对账计为漏件。
 * 误报帧与真祝圣帧的命中分布**完全相同**（均 (6,7,5)/25）⇒ 调 ratio 无解，只能从时间维
 * （残留会褪）与结构维（yShift 后 level 读空）双守门。
 *
 * 本类钉**可在 JVM 侧验证的结构不变式**：
 * ① 双帧裁决的等待常量与面板指纹闸门同拍长（「新面板渲染完成」的实测节奏）；
 * ② yShift 后备校验的**几何前提**：误判格回退后用的 rects 与 yShift=0 的原位 rects 逐值
 *    相等（该前提破了 ⇒ 回退重读仍然读错位置，#316 的方案 2 就白做）；
 * ③ `zhushengYShiftFrame` 与副词条行距的**每档等式** —— 这不是巧合是布局事实，破了它
 *    （有人改 profile）误判就从「丢一行」变成「丢任意量」，两条守门的前提都动摇。
 */
class ZhushengMisjudgeGuardTest {

    private fun assetsDir(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val c = File(dir, "src/main/assets/dsl")
            if (c.isDirectory) return c
            dir = dir.parentFile ?: return@repeat
        }
        error("src/main/assets/dsl not found")
    }

    private fun raw(name: String): JSONObject = JSONObject(File(assetsDir(), name).readText())

    /** ① 双帧裁决等待 = 面板指纹轮询拍长（同一物理节奏：新面板渲染完成）。 */
    @Test
    fun `confirm poll shares the panel-render cadence`() {
        assertEquals(
            "ZHUSHENG_CONFIRM_POLL_MS 应与 PANEL_FP_POLL_MS 同拍长（新面板渲染完成的实测节奏；" +
                "分家 = 有人只改了其中一个，双帧裁决等的时间就不是「横幅褪去」的时间了",
            ScanEngine.Companion.PANEL_FP_POLL_MS,
            ScanEngine.Companion.ZHUSHENG_CONFIRM_POLL_MS,
        )
    }

    /**
     * ③ 每档不变式：zhushengYShiftFrame == 副词条首两行行距。
     * 这是「误判 = 恰好丢一行」的结构前提，也是方案 2 可用「level 读空」检测的原因
     * （移一行后 level 徽标位正好压到上一行副词条文本上，读到的是乱码/别的行）。
     */
    @Test
    fun `yShift equals substat row pitch in every tier`() {
        for (name in listOf(
            "profiles.json", "profiles_2560x1440.json", "profiles_2244x1080.json", "profiles_1280x720.json",
        )) {
            val root = raw(name)
            val shift = root.optInt("zhushengYShiftFrame", 63)
            val subs = root.getJSONObject("panels")
                .getJSONObject("artifact_backpack")
                .getJSONArray("subStats")
            assertTrue("$name subStats 至少两行", subs.length() >= 2)
            val pitch = subs.getJSONArray(1).getInt(1) - subs.getJSONArray(0).getInt(1)
            assertEquals(
                "$name: zhushengYShiftFrame($shift) 应等于副词条行距($pitch)——" +
                    "这是「误判恰好丢一行」与方案 2 检测前提的布局事实",
                pitch.toFloat(), shift.toFloat(), 1.5f,
            )
        }
    }

    /**
     * ② 方案 2 的几何前提：**原位** subStats rects（yShift=0）与「施 shift 后的 rects − shift」
     * 逐值相等——回退重读构造的 rects 必须精确落回原位，Profile 上不存在「回退后仍偏一格」
     * 的写法。同时验证 level 原位 rect 确实与施 shift 的 rect 不同（守门有判别力）。
     */
    @Test
    fun `unshifted rects math is exact for the fallback reread`() {
        for (name in listOf("profiles_2560x1440.json", "profiles_1280x720.json")) {
            val root = raw(name)
            val shift = root.optInt("zhushengYShiftFrame", 63)
            val panel = root.getJSONObject("panels").getJSONObject("artifact_backpack")
            val subs = panel.getJSONArray("subStats")

            // 施 shift 的 rects（生产代码 subRects 的构造式）
            val shifted = (0 until subs.length()).map {
                val r = subs.getJSONArray(it)
                listOf(r.getInt(0), r.getInt(1) + shift, r.getInt(2), r.getInt(3) + shift)
            }
            // 原位 rects（#316 回退重读的构造式）
            val unshifted = (0 until subs.length()).map {
                val r = subs.getJSONArray(it)
                listOf(r.getInt(0), r.getInt(1), r.getInt(2), r.getInt(3))
            }
            // 回退 = 施 shift 后逐轴减回 shift —— 必须与原位逐值相等
            for (i in shifted.indices) {
                val back = listOf(
                    shifted[i][0], shifted[i][1] - shift, shifted[i][2], shifted[i][3] - shift,
                )
                assertEquals("$name sub$i 回退矩形应精确落回原位", unshifted[i], back)
            }

            // 判别力：shift 前后的 level rect 必须不同（否则 yShift 对 level 无效果，守门无从触发）
            val lv = panel.getJSONArray("level")
            assertTrue(
                "$name: level rect 施 shift($shift) 后必须移动（y0 ${lv.getInt(1)} → ${lv.getInt(1) + shift}）",
                lv.getInt(1) != lv.getInt(1) + shift,
            )
        }
    }

    /**
     * ④ VoteJudges 判据本身（纯函数侧）：紫色梯度像素在 zone 配置下命中、普通底纹像素不命中。
     * 这是双帧裁决能工作的前提——如果判据本身恒 false，双帧裁决永远走不到。
     * 用 2560 档 zone 配置（1280 是它的 ×0.5 换算，同一判据）。
     */
    @Test
    fun `zone predicate separates banner purple from plain background`() {
        val root = raw("profiles_2560x1440.json")
        val zone = root.getJSONObject("zones").getJSONObject("artifact.panel.zhusheng")
        val rgb = zone.getJSONArray("rgb")
        val pred = VoteJudges.RgbPredicate(
            rgb.getInt(0), rgb.getInt(1), rgb.getInt(2), rgb.getInt(3), rgb.getInt(4), rgb.getInt(5),
            requireRB = if (rgb.length() > 6) rgb.getInt(6) else 0,
            requireBG = if (rgb.length() > 7) rgb.getInt(7) else 0,
        )
        // 普通底纹（profile 实测：b216 g229 r236，恒 25/25 全同）——G=229 被 gMax=140 排除
        assertFalse("普通底纹不得命中", pred.matches(r = 236, g = 229, b = 216))
        // 祝圣紫横幅梯度（judge 域内的两端；requireBG=60 ⇒ B-G>60 是「紫」的跨通道签名）
        assertTrue("祝圣紫(暗端)应命中", pred.matches(r = 102, g = 61, b = 200))
        assertTrue("祝圣紫(亮端)应命中", pred.matches(r = 210, g = 140, b = 250))
    }
}