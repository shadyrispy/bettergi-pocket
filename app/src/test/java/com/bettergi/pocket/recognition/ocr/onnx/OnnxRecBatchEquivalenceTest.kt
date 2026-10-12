package com.bettergi.pocket.recognition.ocr.onnx

import com.bettergi.pocket.core.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import nu.pattern.OpenCV
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.File

/**
 * #251 rec 合批的**等价性闸门**：分组结果与逐行结果必须逐字段相同（文本 + 置信度）。
 *
 * 为什么必须有这份测试：合批把每行右侧 pad 到组内最宽，pad 列会进卷积与 CRNN 的双向注意力
 * ⇒ "批里的第 r 行"和"单独跑的第 r 行"**不是显然相等**。09-12 那次实测过等价但没记下 pad 填的
 * 什么值（本实现填 -1 = 纯黑，与 det 画布同约定），所以这条要钉成回归门，而不是靠历史结论。
 *
 * ⚠️ 非空跑设计（`feedback-green-test-can-be-vacuous`）：只比"批 vs 逐行"的话，一旦分组
 *   实际没合并（两臂走了同一条逐行码路）测试会**静默通过**。所以每条用例都先断言
 *   `planRecGroups` 真的把组数压下来了，才去比结果。
 */
class OnnxRecBatchEquivalenceTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCvNative() {
            nu.pattern.OpenCV.loadLocally()
            Mat(2, 2, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0)).release()
        }
    }

    private fun onnxAssets(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(5) {
            val candidate = File(dir, "src/main/assets/onnx")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        throw IllegalStateException("src/main/assets/onnx not found (cwd=${System.getProperty("user.dir")})")
    }

    private fun buildService(): OnnxPaddleOcrService {
        val dir = onnxAssets()
        val engine = OnnxOcrEngine(File(dir, "det.onnx"), File(dir, "rec.onnx"))
        val dict = File(dir, "ppocrv6_tiny_dict.txt").readLines().filter { it.isNotEmpty() }
        val service = OnnxPaddleOcrService(engine, dict)
        check(service.prepare()) { "ONNX 引擎初始化失败（模型缺失或全部 EP 档不可用）" }
        return service
    }

    /**
     * 造一张"面板样"的 BGR 图：白底、多行黑字，每行**宽度不同**（这样才会出现 pad 与分组）。
     * 返回图 + 每行的 ROI（左边界留 8px 空白，避免文字贴边）。
     */
    private fun renderPanel(lines: List<String>): Pair<Mat, List<IntRect>> {
        val f = Font("SansSerif", Font.PLAIN, 44)
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_3BYTE_BGR).createGraphics()
        probe.font = f
        val fm = probe.fontMetrics
        probe.dispose()
        val rowH = 60
        val w = 1200
        val img = BufferedImage(w, rowH * lines.size, BufferedImage.TYPE_3BYTE_BGR)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, img.width, img.height)
        g.color = Color.BLACK
        g.font = f
        val rects = ArrayList<IntRect>(lines.size)
        lines.forEachIndexed { i, text ->
            g.drawString(text, 8, i * rowH + fm.ascent + 8)
            rects += IntRect(8, i * rowH + 4, fm.stringWidth(text) + 12, rowH - 8)
        }
        g.dispose()
        val bytes = (img.raster.dataBuffer as DataBufferByte).data
        val mat = Mat(img.height, img.width, CvType.CV_8UC3)
        mat.put(0, 0, bytes)
        return mat to rects
    }

    /**
     * 生产规则的守卫：**跨宽槽绝不并批**（并了就会把置信度打偏 ~4e-2，见 planRecGroups 的 cliff 表）。
     * 圣遗物 2244 档真实宽度：name 335 / mainName 107 / level 97 / subStats×4 413
     * ⇒ 只允许 subStats 四行并一批，其余各自成组 ⇒ 4 次调用（省 3×56=168ms/格）。
     */
    @Test
    fun `圣遗物 2244 档只并四行 subStats`() {
        val widths = listOf(335, 107, 97, 413, 413, 413, 413)
        val groups = OnnxPaddleOcrService.planRecGroups(widths, 56.0).map { it.sorted() }
        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3, 4, 5, 6)), groups)
        // 每组内 scaledW 必须唯一 ⇒ pad 恒为 0
        for (g in groups) assertEquals("组内混了不同宽度的槽：$g", 1, g.map { widths[it] }.distinct().size)
        assertEquals("fixed=0 必须退化成全部单行", 7, OnnxPaddleOcrService.planRecGroups(widths, 0.0).size)
    }

    /**
     * ★ 决定设计取向的一条：**等宽合批（pad 列数 = 0）是否逐位不变**。
     * 所有 ROI 同宽同高 ⇒ 组内 `Wmax == w` ⇒ 批里每一行的输入与单独跑**逐元素相同**，
     * 唯一变量是 ORT 在 batch>1 时的 kernel 分块/线程划分。
     * 若这条通过 ⇒ 生产只允许等宽合批（"产出不变"的前提守得住）；
     * 若不过 ⇒ 合批连等宽都不等价，#251 直接判死。
     */
    @Test
    fun `等宽合批逐位不变（pad 为零）`() {
        val (mat, roisRaw) = renderPanel(listOf("12345", "987654", "42", "13579 2468"))
        // 统一成同一个矩形宽 ⇒ scaledW 必然相同
        val w = roisRaw.minOf { it.width }
        val rois = roisRaw.map { IntRect(it.x, it.y, w, it.height) }
        val widths = rois.map { OnnxPaddleOcrService.scaledWidthFor(it.width, it.height) }
        assertEquals("构造前提：四行 scaledW 必须相同", 1, widths.distinct().size)
        val merged = OnnxPaddleOcrService.planRecGroups(widths, 56.0)
        assertEquals("等宽必须并成一批（否则这条测的是空跑）", 1, merged.size)

        val service = buildService()
        try {
            OnnxPaddleOcrService.recBatchFixedMs = 0.0
            val perRow = service.recognizeRois(mat, rois)
            OnnxPaddleOcrService.recBatchFixedMs = 56.0
            val batched = service.recognizeRois(mat, rois)
            for (i in perRow.indices) {
                assertEquals("第 $i 槽文本", perRow[i].text, batched[i].text)
                // 实测漂移 2.5e-6（0.8566377 vs 0.8566402）⇒ 这是 ORT 在 batch>1 时换了 kernel
                // 分块的浮点求和次序噪声，不是语义变化。门卡在 1e-4：比噪声大 40×，
                // 比"跨宽合批"实测的 3e-2 漂移小两个数量级 ⇒ 能区分"浮点噪声"与"真被扰动"。
                assertEquals("第 $i 槽置信度（等宽批 ⇒ 只允许浮点噪声级漂移）", perRow[i].score, batched[i].score, 1e-4f)
            }
        } finally {
            OnnxPaddleOcrService.recBatchFixedMs = 56.0
            mat.release()
        }
    }

    /** 纯逻辑：不加载模型，跑得起、也跑得便宜。 */
    @Test
    fun `同宽槽无条件并成一组`() {
        val groups = OnnxPaddleOcrService.planRecGroups(listOf(413, 413, 413, 413), 56.0)
        assertEquals(1, groups.size)
        assertEquals(listOf(0, 1, 2, 3), groups[0])
    }

    @Test
    fun `宽度只要不同就各自成组（哪怕只差 10px）`() {
        // 旧 DP 方案会认为 107/97 值得并（pad 10px×0.55=5.5ms < 省 56ms）；
        // 实测 pad 7 列就把置信度打偏 4.4e-2 ⇒ 等宽是唯一允许的合并。
        assertEquals(2, OnnxPaddleOcrService.planRecGroups(listOf(107, 97), 56.0).size)
        assertEquals(2, OnnxPaddleOcrService.planRecGroups(listOf(1000, 100), 56.0).size)
    }

    @Test
    fun `空输入与单输入不炸`() {
        assertTrue(OnnxPaddleOcrService.planRecGroups(emptyList(), 56.0).isEmpty())
        assertEquals(listOf(listOf(0)), OnnxPaddleOcrService.planRecGroups(listOf(120), 56.0))
    }
}
