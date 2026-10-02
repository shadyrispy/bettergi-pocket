package com.bettergi.pocket.scan

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 多分辨率坐标守门（profiles 基准 3200x1440 = 20:9）。
 *
 * 背景：真实设备宽高比不同（16:9 / 20:9 / 21:9 / 平板），profiles 坐标按基准缩放。
 * 等比设备（20:9）坐标可靠；非等比设备（16:9 → sx≠sy）**UI 横向分布不同**，
 * 纯缩放坐标会偏移——本测试把这些数值钉住并量化偏差，供后续决定
 * 「新增 16:9 profile」或「按短边归一化 + 居中偏移」。
 */
class ProfileScalingTest {
    private fun assetsDir(): File {
        // 与其他守门测试一致：AGP unit test 工作目录 = module 目录（app/），防御性向上查找
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val candidate = File(dir, "src/main/assets/dsl")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("dsl assets dir not found under ${System.getProperty("user.dir")}")
    }

    private fun profile(): ScreenProfile {
        val json = JSONObject(File(assetsDir(), "profiles.json").readText())
        return ScreenProfile(json)
    }

    @Test
    fun `3200x1440 baseline is uniform (scale 1_0)`() {
        val p = profile()
        p.calibrate(3200, 1440)
        assertEquals(1.0, p.scaleX, 1e-6)
        assertEquals(1.0, p.scaleY, 1e-6)
        assertEquals(0.0, p.aspectDistortion, 1e-6)
    }

    @Test
    fun `2560x1080 keeps 21-9-ish uniform scaling`() {
        val p = profile()
        p.calibrate(2560, 1080)
        assertEquals(0.8, p.scaleX, 1e-3)
        assertEquals(0.75, p.scaleY, 1e-3)
        // 21.3:9 vs 20:9 → 偏差 6.7%，仍属「接近等比」，坐标基本可用
        assertTrue("distortion=${p.aspectDistortion}", p.aspectDistortion > 0.05 && p.aspectDistortion < 0.08)
    }

    @Test
    fun `1920x1080 is 16-9 and distorts coordinates`() {
        val p = profile()
        p.calibrate(1920, 1080)
        assertEquals(0.6, p.scaleX, 1e-3)
        assertEquals(0.75, p.scaleY, 1e-3)

        // count ROI 基准 [2674,63,2874,95] → 帧坐标
        val count = p.rect("screens.artifact_backpack.count")
        println("1920x1080 count rect: $count (w=${count.right - count.left}, h=${count.bottom - count.top})")

        // 与「按短边等比」(scale = min(sx,sy) = 0.6) 的应有位置对比
        val uniformTop = Math.round(63 * 0.6).toInt()
        val dy = count.top - uniformTop
        println("1920x1080 count dy vs uniform: $dy px (ROI height ${count.bottom - count.top})")

        // 卡片中心偏差（致命：点错行）
        val center = p.cellCenter("artifact_backpack", 0)
        val uniformCy = Math.round((201 + 126) * 0.6).toInt()
        val cardDy = center.y - uniformCy
        println("1920x1080 card0 center: (${center.x},${center.y}) vs uniform y=$uniformCy → dy=$cardDy px")

        // 断言：失真度 >20%（16:9 vs 20:9，横向相对纵向压缩 25%）
        assertTrue("1920x1080 distortion should exceed 20%, got ${p.aspectDistortion}", p.aspectDistortion > 0.2)
        // 卡片纵向偏差 > 40px（远超卡片间距容差 → 会点错行，必须换 profile 或归一化）
        assertTrue("card dy $cardDy should exceed 40px", kotlin.math.abs(cardDy) > 40)
    }

    @Test
    fun `2376x1080 keeps 20-9-ish uniform scaling`() {
        val p = profile()
        p.calibrate(2376, 1080)
        assertEquals(0.7425, p.scaleX, 1e-3)
        assertEquals(0.75, p.scaleY, 1e-3)
        assertTrue("22:9 vs 20:9 distortion=${p.aspectDistortion}", p.aspectDistortion < 0.02)
    }
}
