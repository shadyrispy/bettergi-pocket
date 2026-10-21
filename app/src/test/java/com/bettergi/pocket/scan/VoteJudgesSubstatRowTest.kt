package com.bettergi.pocket.scan

import nu.pattern.OpenCV
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * subStats 行三态颜色判据（VoteJudges.substatRowPending）。
 *
 * 背景：subStats ROI 收窄（2990→2660）后"（待激活）"后缀不再进 OCR 文本，
 * 待激活改由颜色判定 —— 激活行文字**深灰**（中性 lum 90-150 核心像素 1913-2219），
 * 待激活行**中灰**（同带深灰 0、全部 150-215），JPEG 有损下仍完美分离。
 *
 * GT 帧来源：小米 15 / 3200×1440 真机截图（熔铸者的观测 +0，第 4 条待激活），
 * 裁 subStats 面板区 (2230,780)-(3000,1060) 存为 `substats_pending_gt.jpg`。
 */
class VoteJudgesSubstatRowTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            nu.pattern.OpenCV.loadLocally()
            Mat(2, 2, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0)).release()
        }

        /** 合成行带：米色背景（BGR 215,228,232 ≈ GT 背景反色实测）+ 中性灰文字块。 */
        private fun band(textLum: Int?, w: Int = 430, h: Int = 54): Mat {
            val m = Mat(h, w, CvType.CV_8UC3, Scalar(215.0, 228.0, 232.0))
            if (textLum != null) {
                // 文字块 ≈ 行带宽 60%（模拟词条文本的像素体量）
                Imgproc.rectangle(
                    m,
                    Rect(16, h / 4, (w * 0.6).toInt(), h / 2),
                    Scalar(textLum.toDouble(), textLum.toDouble(), textLum.toDouble()),
                    -1,
                )
            }
            return m
        }
    }

    @Test
    fun `激活行（深灰核心）判 false`() {
        val m = band(120)
        try {
            assertEquals(false, VoteJudges.substatRowPending(m, FrameRect(0, 0, 430, 54)))
        } finally {
            m.release()
        }
    }

    @Test
    fun `待激活行（中灰无深灰核心）判 true`() {
        val m = band(170)
        try {
            assertEquals(true, VoteJudges.substatRowPending(m, FrameRect(0, 0, 430, 54)))
        } finally {
            m.release()
        }
    }

    @Test
    fun `空行（纯背景）判 null`() {
        val m = band(null)
        try {
            assertEquals(null, VoteJudges.substatRowPending(m, FrameRect(0, 0, 430, 54)))
        } finally {
            m.release()
        }
    }

    @Test
    fun `GT 帧 激活x3加待激活x1 逐行判据`() {
        val f = File.createTempFile("substats_gt", ".jpg").also {
            VoteJudgesSubstatRowTest::class.java
                .getResourceAsStream("/substats_pending_gt.jpg")!!.copyTo(it.outputStream())
        }
        val mat = Imgcodecs.imread(f.absolutePath)
        assertTrue("GT 帧加载失败", !mat.empty())
        // 裁剪区 (2230,780) 起；profile 行带 y=794/858/922/986，高 54，右缘已收窄到 2660
        val rects = listOf(794, 858, 922, 986).map { y ->
            FrameRect(2230 - 2230, y - 780, 2660 - 2230, y - 780 + 54)
        }
        val verdicts = rects.map { VoteJudges.substatRowPending(mat, it) }
        assertNotNull(verdicts[0])
        assertEquals(listOf(false, false, false, true), verdicts)
        mat.release()
    }

    /**
     * 2244 档跨档安全：华为真机帧（4 条全激活）收窄后 (1516,596)-(1862,780) 逐行仍判 false。
     * 判据阈值在 0.7× 缩档下的实测余量：激活行 dark 921~1784（阈值 150），
     * 文本右缘 ≤1782（收窄线 1862 留 80px 余量）。
     */
    @Test
    fun `GT 帧 2244 档四条全激活逐行判 false`() {
        val f = File.createTempFile("substats_2244", ".jpg").also {
            VoteJudgesSubstatRowTest::class.java
                .getResourceAsStream("/substats_active_2244.jpg")!!.copyTo(it.outputStream())
        }
        val mat = Imgcodecs.imread(f.absolutePath)
        assertTrue("2244 GT 帧加载失败", !mat.empty())
        // 裁剪区 (1516,586) 起；profile 行带 y=596/644/692/740，高 40，右缘收窄到 1862
        val rects = listOf(596, 644, 692, 740).map { y ->
            FrameRect(0, y - 586, 1862 - 1516, y - 586 + 40)
        }
        val verdicts = rects.map { VoteJudges.substatRowPending(mat, it) }
        assertEquals(listOf(false, false, false, false), verdicts)
        mat.release()
    }
}
