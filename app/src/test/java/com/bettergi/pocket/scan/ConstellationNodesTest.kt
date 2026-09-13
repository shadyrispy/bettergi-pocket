package com.bettergi.pocket.scan

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * 命座节点判据（GOODScanner 移植）单测 —— 用**实测值构造的合成帧**把判据锁死。
 *
 * 依据（2244 实测，2026-09-13）：命座页 6 个节点是「**亮描边环 + 暗中心**」⇒ `ring−center ≈ +48~+81`；
 * 而属性页/天赋页在**同坐标**是文字/数值 ⇒ `ring−center ≈ −22~+37`。故
 * `lockedCount(contrast ≥ lockedContrastMin) ≥ pageMinLocked` 判定「在命座页」，
 * 实测命座页 5 角色命中 4~6、另两页 **0**。
 */
class ConstellationNodesTest {

    private fun assetsDir(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val c = File(dir, "src/main/assets/dsl")
            if (c.isDirectory) return c
            dir = dir.parentFile ?: return@repeat
        }
        error("src/main/assets/dsl not found")
    }

    private fun profile(): ScreenProfile {
        val p = ScreenProfile(JSONObject(File(assetsDir(), "profiles.json").readText()))
        p.calibrate(3200, 1440)
        return p
    }

    private fun nodesOf(p: ScreenProfile): List<Pair<Int, Int>> {
        val arr = p.rawObject("screens.char_constellation")!!.getJSONArray("nodes")
        return (0 until arr.length()).map { arr.getJSONArray(it).getInt(0) to arr.getJSONArray(it).getInt(1) }
    }

    /** 画一个「亮环 + 暗心」的节点（命座页的锁定态形态）。 */
    private fun drawRingNode(m: Mat, cx: Int, cy: Int, rIn: Int, rOut: Int, ring: Double, center: Double) {
        Imgproc.circle(m, Point(cx.toDouble(), cy.toDouble()), rOut, Scalar(ring, ring, ring), -1)
        Imgproc.circle(m, Point(cx.toDouble(), cy.toDouble()), rIn, Scalar(center, center, center), -1)
    }

    @Test
    fun `locked ring nodes are recognised as the constellation page`() {
        val p = profile()
        val obj = p.rawObject("screens.char_constellation")!!
        val rIn = obj.getInt("ringInner")
        val rOut = obj.getInt("ringOuter")
        val m = Mat(1440, 3200, org.opencv.core.CvType.CV_8UC3, Scalar(20.0, 20.0, 20.0))
        nodesOf(p).forEach { (cx, cy) -> drawRingNode(m, cx, cy, rIn, rOut, 110.0, 25.0) }

        val read = VoteJudges.constellationNodes(m, p)
        m.release()
        assertNotNull(read)
        read!!
        assertEquals("6 个环节点都应判锁定", 6, read.lockedCount)
        assertEquals("激活数应为 0（全锁）", 0, read.activeCount)
        assertTrue("后缀形（激活恒在锁定之前）", read.suffixPattern)
        assertTrue("应认定在命座页", read.isConstellationPage)
        // 每个节点的 ring−center ≈ 110 − 25 = +85
        read.nodes.forEach { (_, _, c) ->
            assertTrue("contrast=$c 应 ≥ 60", c >= 60.0)
        }
    }

    @Test
    fun `filled blobs (other pages' content) are not taken as the constellation page`() {
        // 非命座页同坐标看到的是文字/数值/立绘 ⇒ 环带与中心都亮（对比度≈0）⇒ 不应认定在命座页。
        val p = profile()
        val m = Mat(1440, 3200, org.opencv.core.CvType.CV_8UC3, Scalar(20.0, 20.0, 20.0))
        nodesOf(p).forEach { (cx, cy) ->
            Imgproc.circle(m, Point(cx.toDouble(), cy.toDouble()), 45, Scalar(150.0, 150.0, 150.0), -1)
        }
        val read = VoteJudges.constellationNodes(m, p)
        m.release()
        assertNotNull(read)
        read!!
        assertEquals("无环结构 ⇒ 0 个锁定", 0, read.lockedCount)
        assertFalse("不应认定在命座页", read.isConstellationPage)
    }

    @Test
    fun `profile holds the calibrated constellation constants`() {
        val p = profile()
        val obj = p.rawObject("screens.char_constellation")!!
        assertEquals("节点数必须是 6", 6, obj.getJSONArray("nodes").length())
        assertTrue("环内径应为正", obj.getInt("ringInner") > 0)
        assertTrue("环外径应大于内径", obj.getInt("ringOuter") > obj.getInt("ringInner"))
        assertTrue("锁定阈值应为正", obj.getDouble("lockedContrastMin") > 0)
        assertTrue("页阈值应 ≥2", obj.getInt("pageMinLocked") >= 2)
        // S 形自检：y 单调递增（上→下），x 先增后减（中间两个最大）
        val ys = nodesOf(p).map { it.second }
        assertEquals("y 必须严格递增（S 形自上而下）", ys, ys.sorted())
        val xs = nodesOf(p).map { it.first }
        assertTrue("x 应呈 S 形（中间大、两端小）", xs.max() > xs.first() && xs.max() > xs.last())
    }
}
