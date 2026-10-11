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
 * ⚠️ 2026-10-10 #244 右缘又按 2560 基准摆回 2720（2660 只剩 1px 余量，见 profiles note）：
 *   长标签行的「（待」会重新进文本，但**进不了本判据的 dark 带**（后缀是中灰 150-215），
 *   下面两条用例的 rect 已按摆回后的右缘重画，判据结论不变。
 * ★ 2026-10-11 左缘裁到次字起点（3200 x0 2238→2264、2244 x0 1522→1540）：裁掉的是行首圆点「·」，
 *   它不在被判读的文本体内，两条用例的 rect 已跟着改（见各自注释），判据结论不变。
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
        // 裁剪区 (2230,780) 起（宽 770 ⇒ 覆盖到原帧 x=3000）；profile 行带 y=794/858/922/986，高 54。
        // 左缘按 2026-10-11 左缘裁切后的 profile 画：x[2264,2720] = 2560 基准 [1744,2200]+520。
        val rects = listOf(794, 858, 922, 986).map { y ->
            FrameRect(2264 - 2230, y - 780, 2720 - 2230, y - 780 + 54)
        }
        val verdicts = rects.map { VoteJudges.substatRowPending(mat, it) }
        assertNotNull(verdicts[0])
        assertEquals(listOf(false, false, false, true), verdicts)
        mat.release()
    }

    /**
     * 2244 档跨档安全：华为真机帧（4 条全激活）裁片区 (1516,586)-(1862,780) 逐行仍判 false。
     * 判据阈值在 0.7× 缩档下的实测余量：激活行 dark 921~1784（阈值 150），
     * 文本右缘 ≤1782（本裁片右缘 1862 留 80px 余量）。
     * ⚠️ 2026-10-10 #244 后 profile 的 2244 档右缘是 **1884**（不是这里的 1862）：
     *   旧值 1800 把 6 字标签行的 % 整枚裁在外面（#243）。外扩的 1800..1884 段在奶白底上，
     *   与上面 3200 档那条同理 ⇒ 计数不变；本用例只覆盖到 1862，因为 GT 裁片就那么大。
     */
    @Test
    fun `GT 帧 2244 档四条全激活逐行判 false`() {
        val f = File.createTempFile("substats_2244", ".jpg").also {
            VoteJudgesSubstatRowTest::class.java
                .getResourceAsStream("/substats_active_2244.jpg")!!.copyTo(it.outputStream())
        }
        val mat = Imgcodecs.imread(f.absolutePath)
        assertTrue("2244 GT 帧加载失败", !mat.empty())
        // 裁剪区 (1516,586) 起（宽 354 ⇒ 覆盖到原帧 x=1870）；profile 行带 y=596/644/692/740，高 40。
        // 左/右缘按 2026-10-11 后的 profile 画：x[1540,1884]；裁片只到 1870 ⇒ 判据内部 coerceIn 截到 1870
        // （右缘差 14px，那 14px 与已覆盖段同为奶白底；左缘 1540 在本裁片内，起点 24）。
        val rects = listOf(596, 644, 692, 740).map { y ->
            FrameRect(1540 - 1516, y - 586, 1884 - 1516, y - 586 + 40)
        }
        val verdicts = rects.map { VoteJudges.substatRowPending(mat, it) }
        assertEquals(listOf(false, false, false, false), verdicts)
        mat.release()
    }
}
