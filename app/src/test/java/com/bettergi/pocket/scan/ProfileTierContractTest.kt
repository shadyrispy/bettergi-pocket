package com.bettergi.pocket.scan

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 分辨率档位**合同**守门：每一档 profile 必须自洽地声明「它相对判据标定档的几何比例」，
 * 且这批数值约定的换算关系必须成立。
 *
 * 为什么需要（2026-10-19 加 1280x720 档时立）：
 * 坐标层由 `ScreenProfile.scaleX/scaleY`（帧 ÷ 本档 base）处理，而**专属档 base==帧 ⇒ 恒 1.0**
 * ——于是源码里那批按「1600×900…其实是 3200/2560 帧像素」写死的判据常量（像素计数门限、
 * 特征窗宽、位移容差）在专属档上**完全不缩放**。判据要么恒不中（`gold>=400` 在小档永远达不到
 * ⇒ 收藏态读不出来，静默丢数据），要么恒中（窗口相对放大 ⇒ 误判）。本类把这条约定钉成断言。
 *
 * 三条合同：
 *  ① 长度换算：`lengthScale` = 本档线性比例（相对 1440 高的标定档）；
 *  ② 计数换算：`countScale` = 线性比例²（ROI 面积比）——判据里的"数出多少像素"随面积走；
 *  ③ 旧三档**不许带**这两个键（缺省 1.0 = 现行为逐位不变）。带了就说明有人改了老档的行为，
 *     那必须是一次有实测依据的显式改动，先在这里把话说清楚再改。
 */
class ProfileTierContractTest {

    private fun assetsDir(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val candidate = File(dir, "src/main/assets/dsl")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("src/main/assets/dsl not found")
    }

    private fun raw(name: String): JSONObject = JSONObject(File(assetsDir(), name).readText())
    private fun profile(name: String, w: Int, h: Int): ScreenProfile =
        ScreenProfile(raw(name)).apply { calibrate(w, h) }

    /**
     * 1280x720 档必须带换算比例，且与 base 尺寸自洽。
     *
     * 自洽的含义：`lengthScale` 必须等于「本档高 ÷ 标定档高」= 720/1440 = 0.5
     * ——**用高不用宽**，因为游戏 UI 的面板/控件层是 fit-height 布局（3200→2244 的
     * 面板层 ×0.75 = 1080/1440 就是这么来的；两档 1440 高的 3200/2560 面板层比例恒 1）。
     */
    @Test
    fun `1280x720 declares length and count scale consistent with fit-height`() {
        val p = profile("profiles_1280x720.json", 1280, 720)
        val base = raw("profiles_1280x720.json").getJSONObject("base")
        assertEquals("profiles_1280x720.json base.w", 1280, base.getInt("w"))
        assertEquals("profiles_1280x720.json base.h", 720, base.getInt("h"))

        // 线性比例 = 高度比（fit-height）。标定档高 = 1440。
        assertEquals("lengthScale 应为 720/1440", 0.5, p.lengthScale, 1e-9)
        // 计数比例 = 面积比 = 线性比例²（不是线性比例！差一个平方就是"阈值差 4 倍"）。
        assertEquals("countScale 应为 0.25（面积比）", 0.25, p.countScale, 1e-9)
        assertEquals("countScale 应等于 lengthScale²", p.lengthScale * p.lengthScale, p.countScale, 1e-9)
    }

    /** 专属档 base==帧 ⇒ 坐标缩放恒 1.0（这条是既有三档的承重前提，新档也必须成立）。 */
    @Test
    fun `1280x720 is a native tier with identity coordinate scaling`() {
        val p = profile("profiles_1280x720.json", 1280, 720)
        assertEquals(1.0, p.scaleX, 1e-9)
        assertEquals(1.0, p.scaleY, 1e-9)
        assertEquals(0.0, p.aspectDistortion, 1e-9)
        // 16:9 与 2560x1440 同形 ⇒ 相对基准 3200x1440(20:9) 的失真就是 20:9→16:9 那一档
        val baseline = profile("profiles.json", 1280, 720)
        assertTrue("1280x720 回落基准档时应报出宽高比失真", baseline.aspectDistortion > 0.2)
    }

    /**
     * 计数门限换算：0.25 比例下，标定档门限 400 → 100。
     * 这条同时锁住「不能压到 0」的下限（压到 0 就是**恒通过**，比恒不中更危险：会把未收藏判成收藏）。
     */
    @Test
    fun `countThreshold scales by area and never collapses to zero`() {
        val p = profile("profiles_1280x720.json", 1280, 720)
        assertEquals("400 → 100", 100, p.countThreshold(400))
        assertEquals("150 → 38", 38, p.countThreshold(150))
        assertEquals("100 → 25", 25, p.countThreshold(100))
        assertEquals("40 → 10", 10, p.countThreshold(40))
        assertEquals("60 → 15", 15, p.countThreshold(60))
        assertEquals("555 → 139", 139, p.countThreshold(555))
        // 极小门限不得归零（恒通过）
        assertTrue("countThreshold(2) 不得为 0", p.countThreshold(2) >= 1)
    }

    /**
     * 旧三档必须**不带** lengthScale/countScale ⇒ 缺省 1.0 ⇒ 现行为逐位不变。
     *
     * 这是本次改动的**零回归保证**：只要这条绿，`countThreshold(x) == x`、`length(x)` 走原路，
     * 三档已验证过的行为一个字节都没动。
     */
    @Test
    fun `existing tiers carry no scale keys so behaviour is bit-identical`() {
        val tiers = listOf(
            Triple("profiles.json", 3200, 1440),
            Triple("profiles_2560x1440.json", 2560, 1440),
            Triple("profiles_2244x1080.json", 2244, 1080),
        )
        for ((file, w, h) in tiers) {
            val root = raw(file)
            assertTrue("$file 不应带 lengthScale（旧档行为必须逐位不变）", !root.has("lengthScale"))
            assertTrue("$file 不应带 countScale（旧档行为必须逐位不变）", !root.has("countScale"))
            val p = profile(file, w, h)
            assertEquals("$file countThreshold 恒等", 400, p.countThreshold(400))
            assertEquals("$file countThreshold 恒等", 40, p.countThreshold(40))
        }
    }

    /**
     * `length(base)` 的缩放律：标定档帧 px → 本档帧 px。
     * 专属档（lengthScale=1、scaleY=1）恒等；新档按 0.5。
     */
    @Test
    fun `length scaling keeps identity on existing tiers and halves on 1280`() {
        for ((file, w, h) in listOf(
            Triple("profiles.json", 3200, 1440),
            Triple("profiles_2560x1440.json", 2560, 1440),
            Triple("profiles_2244x1080.json", 2244, 1080),
        )) {
            val p = profile(file, w, h)
            assertEquals("$file: length 必须逐位恒等", 150, p.length(150))
        }
        val small = profile("profiles_1280x720.json", 1280, 720)
        assertEquals("1280 档 length(150)", 75, small.length(150))
        assertEquals("1280 档 length(63)", 32, small.length(63))
        assertEquals("1280 档 length(5)", 3, small.length(5))
    }

    /**
     * 星带 `divisor` 必须随档走（武器星级 = 金像素数 ÷ divisor）。
     * 不缩 ⇒ 小档读成 0★ ⇒ `parseWeaponPanel` 拿不到稀有度 ⇒ 整档武器不入库。
     */
    @Test
    fun `weapon star divisor falls back to count scale when zone omits it`() {
        val small = profile("profiles_1280x720.json", 1280, 720)
        // 1280 档 zone 由换算得来 ⇒ 不再带 divisor（生成器把它按面积折了），这里验折算结果
        val zone = JSONObject(
            File(assetsDir(), "profiles_1280x720.json").readText(),
        ).getJSONObject("zones").optJSONObject("weapon.card.starStrip")
        assertNotNull("1280 档须有 weapon.card.starStrip zone", zone)
        val divisor: Double =
            if (zone!!.has("divisor")) zone.optDouble("divisor", 555.0)
            else small.countThreshold(555).toDouble()
        assertTrue("divisor 必须在合理区间（1★ 样本 561 → 0★ 边缘）", divisor in 100.0..160.0)
    }

    /**
     * 星带五格几何自洽：**显式标定过 `cell` 的档**，格距必须 ≥ 格宽（否则相邻星读同一块像素）；
     * 且无论是否标过 cell，5 格总宽不得越过帧右缘。
     *
     * ⚠️ `cell` **不是**每档都有：3200/2244 的 artifact_backpack 就省略它（代码回退默认 46），
     * 而 2244 的 pitch 只有 42 ⇒ 默认 cell 反而比 pitch 宽 4px（既存的轻微重叠，实测未报问题）。
     * 所以本用例只对**显式写了 cell 的档**要求 pitch ≥ cell，不把那条既存形态当回归拦下
     * （要治它得先在 2244 上量出真实星宽，那是另一件事）。新档（1280）写了 cell ⇒ 受本约束。
     */
    @Test
    fun `star band geometry is self consistent in every tier`() {
        for ((file, w, _) in listOf(
            Triple("profiles.json", 3200, 1440),
            Triple("profiles_2560x1440.json", 2560, 1440),
            Triple("profiles_2244x1080.json", 2244, 1080),
            Triple("profiles_1280x720.json", 1280, 720),
        )) {
            val root = raw(file)
            val panels = root.getJSONObject("panels")
            for (key in listOf("artifact_backpack", "weapon_backpack")) {
                val band = panels.optJSONObject(key)?.optJSONObject("starBand") ?: continue
                if (!band.has("y") || !band.has("x0")) continue
                val hasCell = band.has("cell")
                val cell = band.optInt("cell", 46)
                val pitch = band.getInt("pitch")
                val x0 = band.getInt("x0")
                val y = band.getJSONArray("y")
                if (hasCell) {
                    assertTrue("$file/$key: 显式标定的 cell($cell) 应 ≤ pitch($pitch)", pitch >= cell)
                }
                assertTrue("$file/$key: 第5格右缘 ${x0 + 4 * pitch + cell} 越出帧宽 $w",
                    x0 + 4 * pitch + cell <= w)
                assertTrue("$file/$key: starBand y 应为两值且正", y.length() == 2 && y.getInt(1) > y.getInt(0))
            }
        }
    }
}