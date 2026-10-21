package com.bettergi.pocket.recognition.area

import com.bettergi.pocket.core.IntRect
import com.bettergi.pocket.recognition.ocr.IOcrService
import com.bettergi.pocket.recognition.ocr.OcrResult
import com.bettergi.pocket.recognition.ocr.OcrResultRegion
import nu.pattern.OpenCV
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar

/**
 * A21（optimization-plan-20260930 轨 2B）回归：
 * ROI 越界的统一处理 —— 先按源图尺寸 clamp（对齐 deriveCrop 先例），clamp 后空/负尺寸
 * fail-loud（抛带 region 名与 rect 的明确异常），不再"打日志后撞 OpenCV CV_Assert"。
 *
 * 场景原型：16:10 屏跑 16:9 坐标 —— JSON rect（如 1920x2000）超出 capture 高度 1080。
 * 旧实现在 findTemplate 打 error 日志后照常 `Mat(Mat, Rect)` ⇒ CvException 硬崩。
 */
class ImageRegionRoiTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCvNative() {
            nu.pattern.OpenCV.loadLocally()
            Mat(2, 2, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0)).release()
        }
    }

    /** 记录被喂进的 ROI 视图尺寸，并返回一个非空识别结果的桩 OCR。 */
    private class RecordingOcr : IOcrService {
        var lastSize: Pair<Int, Int>? = null

        override fun recognize(mat: Mat): OcrResult {
            lastSize = mat.cols() to mat.rows()
            return OcrResult(listOf(OcrResultRegion(IntRect(0, 0, mat.cols(), mat.rows()), "测试文本", 1f)))
        }
    }

    /** 100x100 源图 + OCR 识别对象（regionOfInterest 即生效 ROI，无参考搜索路径）。 */
    private fun ocrObject(name: String, roi: IntRect) = com.bettergi.pocket.recognition.RecognitionObject().apply {
        recognitionType = com.bettergi.pocket.recognition.RecognitionTypes.Ocr
        regionOfInterest = roi
        this.name = name
    }

    @Test
    fun `A21 ROI 完全越界时抛带名字与rect的明确异常而不是CvException`() {
        val mat = Mat(100, 100, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0))
        try {
            val region = ImageRegion(mat, 0, 0)
            // y 从 100 开始（画面外），clamp 后高度为 0 → 空
            val thrown = runCatching {
                region.find(ocrObject("夜之证", IntRect(0, 100, 100, 100)))
            }
            val e = thrown.exceptionOrNull()
            assertTrue("应抛 IllegalArgumentException 而不是 ${e?.javaClass}", e is IllegalArgumentException)
            assertTrue("异常须带 region 名：${e?.message}", e?.message?.contains("夜之证") == true)
            assertTrue("异常须带原始 rect：${e?.message}", e?.message?.contains("0,100,100x100") == true)
            assertTrue("异常须带源图尺寸：${e?.message}", e?.message?.contains("100x100") == true)
            // 负起点同理
            runCatching { region.find(ocrObject("夜之证", IntRect(-50, -50, 20, 20))) }.getOrNull()
                ?.let { throw AssertionError("完全越界（负向）也应抛出，实际返回 $it") }
        } finally {
            mat.release()
        }
    }

    @Test
    fun `A21 ROI 部分越界时 clamp 后继续识别且坐标按 clamped 原点回映`() {
        val mat = Mat(100, 100, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0))
        try {
            val ocr = RecordingOcr()
            val region = ImageRegion(mat, 0, 0, ocrService = ocr)
            // 16:10 跑 16:9 的原型：rect 高度伸进画面外，与画面仍有交集
            val hit = region.find(ocrObject("夜之证", IntRect(10, 60, 80, 80)))
            // clamp 后 = (10,60,80x40)：OCR 收到的视图是 clamped 尺寸
            assertEquals(80 to 40, ocr.lastSize)
            // 命中区域回报的是 clamped rect（不是原始 rect）
            assertEquals(10, hit.x)
            assertEquals(60, hit.y)
            assertEquals(80, hit.width)
            assertEquals(40, hit.height)
        } finally {
            mat.release()
        }
    }

    @Test
    fun `A21 合法 ROI 行为不变`() {
        val mat = Mat(100, 100, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0))
        try {
            val ocr = RecordingOcr()
            val region = ImageRegion(mat, 0, 0, ocrService = ocr)
            val hit = region.find(ocrObject("夜之证", IntRect(20, 20, 50, 30)))
            assertEquals(50 to 30, ocr.lastSize)
            assertEquals(20, hit.x)
            assertEquals(20, hit.y)
            assertEquals(50, hit.width)
            assertEquals(30, hit.height)
        } finally {
            mat.release()
        }
    }
}
