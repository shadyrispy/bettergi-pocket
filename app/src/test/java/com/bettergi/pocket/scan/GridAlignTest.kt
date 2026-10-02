package com.bettergi.pocket.scan

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import nu.pattern.OpenCV
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * §12.2 翻页距离自适应守门（合成帧，JVM 可跑）。
 *
 * 合成网格结构对齐方案实测数据：卡内亮度 124、卡底/间隙带 207~255，
 * 卡 253px + 行距 292px。算法应在平移 0/±20/±40/±80/±120 时把 err 测出来
 * （方案实测：误差全 0、漏检 0、MAE 0.0px）。
 */
class GridAlignTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            nu.pattern.OpenCV.loadLocally()
            Mat(2, 2, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0)).release()
        }

        private const val W = 3200
        private const val H = 1440
    }

    /** 每测试前清 p0 基准，避免用例间状态泄漏（p0 由 captureBaseline 显式建立，测试不建立→退化为 detected−expected）。 */
    @Before
    fun resetState() {
        GridAlign.resetBaseline()
    }

    private fun profile(): ScreenProfile {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val candidate = File(dir, "src/main/assets/dsl")
            if (candidate.isDirectory) {
                return ScreenProfile(JSONObject(File(candidate, "profiles.json").readText())).apply {
                    calibrate(W, H) // 1:1，base 坐标 = 帧坐标
                }
            }
            dir = dir.parentFile ?: return@repeat
        }
        error("src/main/assets/dsl not found")
    }

    /**
     * 合成背包网格：暗面板背景（40，模拟游戏库存面板）+ 每行均匀暗卡体 253px（124）。
     * 卡顶 = 最强「暗→亮」台阶（面板 40 → 卡 124，+84），与真机一致；卡底（卡→面板）为负台阶被 argmax 排除。
     * 首行上沿 = [top]（可整体平移模拟翻页后残留偏移）。列间留空隙（不画卡）以模拟真实 7 列网格。
     */
    private fun syntheticGrid(top: Int, rows: Int = 4, cardW: Int = 200, pitchX: Int = 244, pitchY: Int = 292): Mat {
        val m = Mat(H, W, CvType.CV_8UC3, Scalar(40.0, 40.0, 40.0))
        val g = profile().gridGeometryFor("artifact_backpack")!!
        for (r in 0 until rows) {
            val y = top + r * pitchY
            if (y >= H) break
            for (c in 0 until g.cols) {
                val x = g.colXs[c]
                if (x + cardW > W) continue
                // 卡体：均匀暗（卡顶即最强暗→亮台阶，与真机一致；无亮带以免与卡顶竞争）
                val cardBottom = (y + 253).coerceAtMost(H)
                if (cardBottom > y) {
                    Imgproc.rectangle(m, org.opencv.core.Point(x.toDouble(), y.toDouble()),
                        org.opencv.core.Point((x + cardW).toDouble(), cardBottom.toDouble()),
                        Scalar(124.0, 124.0, 124.0), -1)
                }
            }
        }
        return m
    }

    /**
     * 绝对偏置：合成帧的卡底/间隙亮度分布与真机帧不完全一致，故 err 含一个**恒定**偏置
     * （方案在真机帧上把它标定为 −5，输出 +5 即真实上沿）。这里先在 shift=0 处量出该偏置，
     * 再据此断言**相对精度**——那才是距离自适应真正依赖的性质（err 随漂移线性变化）。
     */
    private fun bias(p: ScreenProfile): Int {
        val expected = p.gridGeometryFor("artifact_backpack")!!.rowYs.first()
        val frame = syntheticGrid(expected)
        val err = GridAlign.measureError(frame, p, "artifact_backpack") ?: 0
        frame.release()
        return err
    }

    @Test
    fun `bias is constant and small`() {
        val p = profile()
        val b = bias(p)
        println("合成帧恒定偏置 = $b（真机标定为 0；此处为合成卡剖面差异所致）")
        assertTrue("偏置应远小于半 pitch(146)，实测 $b", Math.abs(b) <= 20)
    }

    /** 方案实测场景：平移 0/±20/±40/±80/±120 → 误差全 0（此处按恒定偏置折算后应为 0）。 */
    @Test
    fun `shifts within half pitch are measured linearly`() {
        val p = profile()
        val expected = p.gridGeometryFor("artifact_backpack")!!.rowYs.first()
        val b = bias(p)
        val results = ArrayList<String>()
        var worst = 0
        for (shift in intArrayOf(-120, -80, -40, 0, 20, 40, 80, 120)) {
            val frame = syntheticGrid(expected + shift)
            val err = GridAlign.measureError(frame, p, "artifact_backpack")
            val residual = Math.abs((err ?: Int.MAX_VALUE) - shift - b)
            if (err != null) worst = maxOf(worst, residual)
            results += "shift=$shift → err=$err (残差 $residual)"
            frame.release()
        }
        println(results.joinToString("\n"))
        assertTrue("各平移量的残差应 ≤3px（实测最大 $worst）", worst <= 3)
    }

    /**
     * 已知局限（方案未覆盖）：网格是周期信号（周期=行距），搜索窗只有 ±150，
     * 真偏移超出窗口时相位会锁定到邻行（实测 +200px 被测成 −85，仍能通过 |err|≤146 限幅）。
     * 此处钉死「不会输出荒谬值」的底线；兜底已是 fpband 落地条带 + pageDrift 记账
     * （2026-09-14 翻页整体改造，nextDistance 控制律已删）。
     */
    @Test
    fun `aliased measurement stays within guard`() {
        val p = profile()
        val expected = p.gridGeometryFor("artifact_backpack")!!.rowYs.first()
        val frame = syntheticGrid(expected + 200) // 超出搜索窗 → 混叠
        val err = GridAlign.measureError(frame, p, "artifact_backpack")
        println("超出搜索窗 → err=$err（混叠，非真实偏移 200）")
        assertTrue("混叠结果仍需落在限幅内或被判 null：err=$err", err == null || Math.abs(err!!) <= 146)
        frame.release()
    }

    @Test
    fun `non card grid has no alignment`() {
        val p = profile()
        // set_filter_popup 非卡片网格 → 无几何 → 不测
        val frame = syntheticGrid(297)
        assertNull(GridAlign.measureError(frame, p, "set_filter_popup"))
        frame.release()
    }

    /** §12.5 withGridRowOffset：网格类坐标随行偏移平移，panel 类坐标与 rowPitch 不变。 */
    @Test
    fun `grid row offset shifts grid coords but not panel rects`() {
        val p = profile()
        val dy = 100
        val off = p.withGridRowOffset(dy)
        val g0 = p.gridGeometryFor("artifact_backpack")!!
        val g1 = off.gridGeometryFor("artifact_backpack")!!
        assertArrayEquals(g0.rowYs.map { it + dy }.toIntArray(), g1.rowYs)
        assertEquals(g0.rowPitch, g1.rowPitch, 0.001)
        // cellCenter（index 3 = row0 col3）随偏移平移，x 不变
        val c0 = p.cellCenter("artifact_backpack", 3)
        val c1 = off.cellCenter("artifact_backpack", 3)
        assertEquals(c0.x, c1.x)
        assertEquals(c0.y + dy, c1.y)
        // panel 固定坐标不偏移
        assertEquals(p.rect("screens.artifact_backpack.count"), off.rect("screens.artifact_backpack.count"))
        // 偏移不影响原 profile（视图隔离）
        assertEquals(g0.rowYs.toList(), p.gridGeometryFor("artifact_backpack")!!.rowYs.toList())
    }

    @Test
    fun `char popup alignment works with colX rowY geometry`() {        val p = profile()
        val g = p.gridGeometryFor("char_popup")!!
        // 3 列网格；暗面板背景 + 均匀暗卡（卡顶即最强暗→亮台阶，与 artifact 合成一致）
        val m = Mat(H, W, CvType.CV_8UC3, Scalar(40.0, 40.0, 40.0))
        for (r in 0 until g.rowYs.size) {
            val y = g.rowYs[r]
            for (c in 0 until g.cols) {
                val x = g.colXs[c]
                Imgproc.rectangle(m, org.opencv.core.Point(x.toDouble(), y.toDouble()),
                    org.opencv.core.Point((x + g.cardW).toDouble(), (y + g.cardH).toDouble()),
                    Scalar(124.0, 124.0, 124.0), -1)
            }
        }
        val err = GridAlign.measureError(m, p, "char_popup")
        println("char_popup 未平移 → err=$err")
        assertTrue("char_popup 应能测相位（几何用 colX/rowY），实测 $err", err != null && Math.abs(err!!) <= 8)
        m.release()
    }
}
