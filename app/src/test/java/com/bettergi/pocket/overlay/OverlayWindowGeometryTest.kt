package com.bettergi.pocket.overlay

import android.view.Gravity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * [OverlayWindowGeometry] 纯函数单测（JVM 裸跑，无需 Robolectric）：
 * 被测函数只吃 Int，Gravity 常量是编译期常量（kotlinc 内联），不触 Android 框架。
 *
 * 钉死的约束：`WindowManager.LayoutParams.x` 的语义随 gravity 横向分量变化 ——
 * END（含 RIGHT）⇒ x 是距右缘偏移，窗口左缘 = 屏宽 − x − 窗宽；START（含 LEFT/未指定）⇒ x 即窗口左缘。
 * 主悬浮窗是 `TOP|END` + x=右缘内边距，日志把手是 `TOP|START`；换算错了，END 窗的命中区会被
 * 错算成屏幕左缘一条带 —— 悬浮球上的模拟点击拿不到穿透（手势被自家 TYPE_ACCESSIBILITY_OVERLAY
 * 吃掉，游戏只见 DOWN 无 UP），屏幕左缘反而假阳性。
 *
 * 用例矩阵：两种 gravity × 窗口内/窗口外/边界 ±slop 各点（半开区间：左/上缘含、右/下缘不含）。
 */
@RunWith(Parameterized::class)
class OverlayWindowGeometryTest(private val case: Case) {

    /** 一条命中用例：窗口几何 + 触点 + 期望结果（不可 private：JUnit 的 @Parameters 方法要暴露它）。 */
    data class Case(
        val name: String,
        val gravity: Int,
        val screenWidth: Int,
        val screenHeight: Int,
        val lpX: Int,
        val lpY: Int,
        val windowWidth: Int,
        val windowHeight: Int,
        val slop: Int,
        val touchX: Int,
        val touchY: Int,
        val expected: Boolean,
    ) {
        override fun toString(): String = name
    }

    @Test
    fun `hit decision follows gravity-resolved window rect`() {
        val hit = OverlayWindowGeometry.contains(
            screenWidth = case.screenWidth,
            screenHeight = case.screenHeight,
            lpX = case.lpX,
            lpY = case.lpY,
            windowWidth = case.windowWidth,
            windowHeight = case.windowHeight,
            gravity = case.gravity,
            slop = case.slop,
            touchX = case.touchX,
            touchY = case.touchY,
        )
        if (case.expected) {
            assertTrue("${case.name}：应命中", hit)
        } else {
            assertFalse("${case.name}：不应命中", hit)
        }
    }

    companion object {

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Case> = endWindowCases() + startWindowCases()

        /**
         * 一组「同几何多点」用例。[left]/[top] 是按 gravity 公式**手算**出的窗口左上角
         * （不调用被测函数得出，防公式自证）；点集覆盖窗口内/外/边界 ±slop。
         * 区间语义与悬浮窗侧保持一致：左/上缘含（≥）、右/下缘不含（<）。
         * 触点允许为负/越出屏缘（slop 外扩 + 带 FLAG_LAYOUT_NO_LIMITS 的窗口本身可画出屏外）。
         */
        private fun rowsFor(
            family: String,
            gravity: Int,
            screenWidth: Int,
            screenHeight: Int,
            lpX: Int,
            lpY: Int,
            windowWidth: Int,
            windowHeight: Int,
            slop: Int,
            left: Int,
            top: Int,
        ): List<Case> {
            fun row(name: String, touchX: Int, touchY: Int, expected: Boolean) = Case(
                name = "$family $name",
                gravity = gravity,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                lpX = lpX,
                lpY = lpY,
                windowWidth = windowWidth,
                windowHeight = windowHeight,
                slop = slop,
                touchX = touchX,
                touchY = touchY,
                expected = expected,
            )
            val cx = left + windowWidth / 2
            val cy = top + windowHeight / 2
            return listOf(
                row("窗口中心", cx, cy, true),
                row("左/上缘边界（含）", left, top, true),
                row("左缘−slop 仍命中", left - slop, cy, true),
                row("左缘−slop−1 不命中", left - slop - 1, cy, false),
                row("右缘（半开区间含）", left + windowWidth, cy, true),
                row("右缘+slop−1 仍命中", left + windowWidth + slop - 1, cy, true),
                row("右缘+slop 不命中", left + windowWidth + slop, cy, false),
                row("上缘−slop 仍命中", cx, top - slop, true),
                row("上缘−slop−1 不命中", cx, top - slop - 1, false),
                row("下缘+slop−1 仍命中", cx, top + windowHeight + slop - 1, true),
                row("下缘+slop 不命中", cx, top + windowHeight + slop, false),
            )
        }

        /**
         * 主悬浮窗（TOP|END）：1440 宽屏、lp.x=右缘内边距 22、窗宽 120
         * ⇒ 实际左缘 = 1440 − 22 − 120 = **1298**；TOP ⇒ lp.y 即顶缘。
         */
        private fun endWindowCases(): List<Case> {
            val rows = rowsFor(
                family = "主悬浮窗(TOP|END)",
                gravity = Gravity.TOP or Gravity.END,
                screenWidth = 1440,
                screenHeight = 900,
                lpX = 22,
                lpY = 200,
                windowWidth = 120,
                windowHeight = 96,
                slop = 30,
                left = 1298,
                top = 200,
            )
            // 回归钉：lp.x 本身（屏幕左缘一带）不按 gravity 换算时会被当成左坐标 ⇒ 整条左缘带
            // 假阳性命中；触点必须落在右缘真正的悬浮球区域（1298..1418）才算命中。
            return rows + Case(
                name = "主悬浮窗(TOP|END) 屏幕左缘（=lp.x 一带）不命中",
                gravity = Gravity.TOP or Gravity.END,
                screenWidth = 1440,
                screenHeight = 900,
                lpX = 22,
                lpY = 200,
                windowWidth = 120,
                windowHeight = 96,
                slop = 30,
                touchX = 22,
                touchY = 248,
                expected = false,
            )
        }

        /** 日志把手（TOP|START）：lp.x 即左缘（现有行为，必须原样保持）。 */
        private fun startWindowCases(): List<Case> = rowsFor(
            family = "日志把手(TOP|START)",
            gravity = Gravity.TOP or Gravity.START,
            screenWidth = 1440,
            screenHeight = 900,
            lpX = 12,
            lpY = 640,
            windowWidth = 728,
            windowHeight = 64,
            slop = 30,
            left = 12,
            top = 640,
        )
    }
}

/**
 * 换算公式的**直接钉**（不经过 [OverlayWindowGeometry.contains] 的边界逻辑，
 * 防止换算与边界两处一起错时互相"自证"）。
 */
class OverlayWindowGeometryResolveTest {

    @Test
    fun `END gravity resolves x as offset from right edge`() {
        // 主悬浮窗实参：1440 宽屏、右缘内边距 22、窗宽 120 ⇒ 左缘 = 1440 − 22 − 120 = 1298
        assertEquals(
            1298,
            OverlayWindowGeometry.resolveWindowLeft(1440, 22, 120, Gravity.TOP or Gravity.END),
        )
    }

    @Test
    fun `START gravity keeps x as window left edge`() {
        // 日志把手（TOP|START）现有行为必须不变：lp.x 即左缘
        assertEquals(
            12,
            OverlayWindowGeometry.resolveWindowLeft(1440, 12, 728, Gravity.TOP or Gravity.START),
        )
    }

    @Test
    fun `no gravity defaults to START semantics`() {
        // 未指定横向 gravity 时系统按 START 解析 ⇒ lp.x 仍是左缘
        assertEquals(40, OverlayWindowGeometry.resolveWindowLeft(1440, 40, 200, Gravity.NO_GRAVITY))
        assertEquals(
            40,
            OverlayWindowGeometry.resolveWindowLeft(1440, 40, 200, Gravity.TOP or Gravity.LEFT),
        )
    }

    @Test
    fun `center horizontal resolves around screen center plus offset`() {
        // 居中后再加 x 偏移：(1440 − 120) / 2 + 10 = 670
        assertEquals(
            670,
            OverlayWindowGeometry.resolveWindowLeft(1440, 10, 120, Gravity.CENTER_HORIZONTAL),
        )
    }

    @Test
    fun `TOP gravity keeps y as window top edge`() {
        assertEquals(
            200,
            OverlayWindowGeometry.resolveWindowTop(900, 200, 96, Gravity.TOP or Gravity.END),
        )
    }

    @Test
    fun `BOTTOM gravity resolves y as offset from bottom edge`() {
        // 900 − 40 − 96 = 764
        assertEquals(764, OverlayWindowGeometry.resolveWindowTop(900, 40, 96, Gravity.BOTTOM))
    }
}
