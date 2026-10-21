package com.bettergi.pocket.scan

import nu.pattern.OpenCV
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * #226 修（[GridAlign.rowPhase]：行缓冲复用 + 行窗限定到 [yFrom,yTo]）的**逐位等价**守门。
 *
 * 为什么必须有：rowPhase 的输出直接喂 `withGridRowOffset(φ)`（点击坐标平移），数值一变动就是
 * **静默挪点击**（格没变、读数变，属漏件类）。改法的两个基元已由 GridAlignTest「复用缓冲与限定
 * 行窗都逐位等价」在 rowMean/brightBands 层钉过；本类补**整条 rowPhase** 的对拍：参照实现是修改前
 * GridAlign.kt:247/248 的逐行抄写（整帧剖面 + 每行 new ByteArray，只调生产侧 internal 纯函数），
 * 双跑断言 RowPhase 五字段全等（守卫拒 ⇒ 双 null），并对同一帧连测两次（新实现不得有跨调用状态）。
 *
 * 合成帧配方同 GridAlignTest.benchFrame（暗面板 40 + 卡体 124 + labelAnchor 亮带两遍画）：内容
 * 必须让守卫**真过** —— 否则对拍的是"提前返回"、空对空（正例全部带非空断言）。
 * detectRow/buildColumnProfiles 那条（measureError）未在本类重抄（要复制私有 detectStep 链）；
 * 其等价性 = 「buffered rowMean ≡ fresh rowMean」（上两行引用的用例）+ 本轮仓外基准对拍。
 */
class GridAlignRowPhaseEquivTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            OpenCV.loadLocally()
        }

        // 私有常量同值抄写（旧 GridAlign.rowPhase 的 20/96/14/5）——常量改了本类先红，再决定抄不抄。
        private const val BAND_MIN_RUN = 20
        private const val BAND_MAX_RUN = 96
        private const val BAND_TOL = 14
        private const val BAND_MIN_COLS = 5
    }

    @Before
    fun resetState() {
        GridAlign.resetBaseline()
    }

    // ---- 参照实现：修改前 GridAlign.kt:247/248 的原样抄写（勿改用生产 buffered 版） ----

    private fun freshRowMean(frame: Mat, y: Int, x0: Int, x1: Int): Double {
        val width = x1 - x0
        val buf = ByteArray(width * frame.channels())
        frame.get(y, x0, buf)
        var sum = 0L
        for (i in buf.indices) sum += buf[i].toLong() and 0xFF
        return sum.toDouble() / buf.size
    }

    private fun oldRowPhase(frame: Mat, profile: ScreenProfile, gridKey: String): GridAlign.RowPhase? {
        if (frame.empty()) return null
        val g = profile.gridGeometryFor(gridKey) ?: return null
        if (g.labelAnchor < 0 || g.rowYs.isEmpty()) return null
        val sy = profile.scaleY
        val pitch = profile.scale(g.rowPitch.toInt().coerceAtLeast(1), sy)
        if (pitch <= 0) return null
        val firstCenter = profile.scale(g.rowYs[0] + g.labelAnchor, sy)
        val minRun = profile.scale(BAND_MIN_RUN, sy)
        val maxRun = profile.scale(BAND_MAX_RUN, sy)
        val tol = profile.scale(BAND_TOL, sy)
        val yFrom = (firstCenter - pitch).coerceAtLeast(0)
        val yTo = (firstCenter + g.rowYs.size * pitch + maxRun).coerceAtMost(frame.rows() - 1)
        if (yTo <= yFrom + pitch) return null
        val barsByCol = ArrayList<List<Int>>(g.cols)
        for (c in 0 until g.cols) {
            val x0 = profile.scale(g.colXs[c], profile.scaleX).coerceIn(0, frame.cols() - 2)
            val x1 = profile.scale(g.colXs[c] + g.cardW, profile.scaleX).coerceIn(x0 + 2, frame.cols())
            val prof = DoubleArray(frame.rows()) { freshRowMean(frame, it, x0, x1) }
            val kept = GridAlign.periodKeep(GridAlign.brightBands(prof, yFrom, yTo, minRun, maxRun), pitch, tol)
            if (kept.size >= 2) barsByCol.add(kept)
        }
        val gate = minOf(
            profile.rawObject("grids.$gridKey")?.optInt("phaseMinCols", BAND_MIN_COLS) ?: BAND_MIN_COLS,
            g.cols,
        )
        return GridAlign.phaseFromBars(barsByCol, firstCenter, pitch, tol, gate)
    }

    // ---- 合成帧（同 GridAlignTest.benchFrame 配方；dy = 内容整体平移，模拟落地偏移） ----

    private fun benchFrame(p: ScreenProfile, gridKey: String, w: Int, h: Int, dy: Int): Mat {
        val g = p.gridGeometryFor(gridKey)!!
        val m = Mat(h, w, CvType.CV_8UC3, Scalar(40.0, 40.0, 40.0))
        for (pass in 0..1) {
            for (r in g.rowYs.indices) {
                val y = p.scale(g.rowYs[r], p.scaleY) + dy
                val bandT = (y + p.scale(g.labelAnchor - 22, p.scaleY)).coerceAtLeast(0)
                val bandB = (y + p.scale(g.labelAnchor + 23, p.scaleY)).coerceAtMost(h)
                val cardB = (y + p.scale(g.cardH, p.scaleY)).coerceAtMost(h)
                val y0 = if (pass == 0) y else bandT
                val y1 = if (pass == 0) cardB else bandB
                val color = if (pass == 0) Scalar(124.0, 124.0, 124.0) else Scalar(228.0, 232.0, 236.0)
                if (y1 <= y0) continue
                for (c in 0 until g.cols) {
                    val x = p.scale(g.colXs[c], p.scaleX).toDouble()
                    val xr = p.scale(g.colXs[c] + g.cardW, p.scaleX).coerceAtMost(w).toDouble()
                    Imgproc.rectangle(m, Point(x, y0.toDouble()), Point(xr, y1.toDouble()), color, -1)
                }
            }
        }
        return m
    }

    private fun profileAt(asset: String, w: Int, h: Int): ScreenProfile {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val candidate = File(dir, "src/main/assets/dsl")
            if (candidate.isDirectory) {
                return ScreenProfile(JSONObject(File(candidate, asset).readText())).apply { calibrate(w, h) }
            }
            dir = dir.parentFile ?: return@repeat
        }
        error("src/main/assets/dsl not found")
    }

    private data class Case(val asset: String, val w: Int, val h: Int, val grid: String, val dy: Int)

    /**
     * 两个档位 × 两张网格 × 多个落地偏移（含奇数 dy：让带心落在像素中间，盯住取整差异）：
     * 新 rowPhase 必须与参照实现逐字段全等，且同帧连测两次同值。
     */
    @Test
    fun `rowPhase 新旧写法在 2244 与 3200 合成帧上逐字段全等`() {
        val cases = listOf(
            Case("profiles_2244x1080.json", 2244, 1080, "artifact_backpack", 0),
            Case("profiles_2244x1080.json", 2244, 1080, "artifact_backpack", 53),
            Case("profiles_2244x1080.json", 2244, 1080, "artifact_backpack", -117),
            Case("profiles_2244x1080.json", 2244, 1080, "weapon_backpack", 0),
            Case("profiles.json", 3200, 1440, "artifact_backpack", 0),
            Case("profiles.json", 3200, 1440, "artifact_backpack", -71),
        )
        for (cs in cases) {
            val p = profileAt(cs.asset, cs.w, cs.h)
            val frame = benchFrame(p, cs.grid, cs.w, cs.h, cs.dy)
            try {
                val ref = oldRowPhase(frame, p, cs.grid)
                val now = GridAlign.rowPhase(frame, p, cs.grid)
                val again = GridAlign.rowPhase(frame, p, cs.grid)
                assertNotNull("合成帧必须让守卫真过，否则对拍空对空（${cs.asset}/${cs.grid}/dy=${cs.dy}）", ref)
                assertEquals("RowPhase 五字段必须全等（${cs.asset}/${cs.grid}/dy=${cs.dy}）", ref, now)
                assertEquals("rowPhase 不得有跨调用状态（连测两次同值）", ref, again)
            } finally {
                frame.release()
            }
        }
    }

    /** 守卫拒绝的帧：双 null 也是等价（防修法在失败路径上分流）。 */
    @Test
    fun `守卫拒时新旧都返回 null`() {
        val p = profileAt("profiles_2244x1080.json", 2244, 1080)
        val frame = benchFrame(p, "artifact_backpack", 2244, 1080, -900)
        try {
            assertNull(oldRowPhase(frame, p, "artifact_backpack"))
            assertNull(GridAlign.rowPhase(frame, p, "artifact_backpack"))
        } finally {
            frame.release()
        }
    }
}
