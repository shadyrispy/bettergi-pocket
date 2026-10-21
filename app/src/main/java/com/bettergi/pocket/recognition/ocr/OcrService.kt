package com.bettergi.pocket.recognition.ocr

import android.content.Context
import android.util.Log
import com.bettergi.pocket.core.IntRect
import com.bettergi.pocket.recognition.OcrText
import com.bettergi.pocket.recognition.ocr.onnx.OnnxModelAssets
import com.bettergi.pocket.recognition.ocr.onnx.OnnxOcrEngine
import com.bettergi.pocket.recognition.ocr.onnx.OnnxPaddleOcrService
import com.bettergi.pocket.core.image.MatOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.opencv.core.Mat

data class OcrResultRegion(
    val rect: IntRect,
    val text: String,
    val score: Float,
)

data class OcrResult(
    val regions: List<OcrResultRegion>,
) {
    val text: String
        get() = regions
            .sortedWith(compareBy({ it.rect.centerY }, { it.rect.centerX }))
            .joinToString("\n") { it.text }

    companion object {
        val EMPTY = OcrResult(emptyList())
    }
}

interface IOcrService {
    fun recognize(mat: Mat): OcrResult

    /**
     * 本引擎的 [recognizeRois] 是否为真·rec-only 批量（显著快于逐槽完整识别）。
     *
     * ML Kit 默认 false：它的 recognizeWithoutDetector 就是完整识别（无速度差），且调用方
     * 对 <80px 小字有 2x 放大增强——批量路径拿不到该增强，小字精度会回退。
     * ONNX 覆写为 true（rec-only ≈ 2ms/槽 vs 全管线 ≈ 44ms，且 PP-OCR rec 以 48px 高为工作点）。
     */
    val hasFastRecOnlyBatch: Boolean
        get() = false

    fun recognizeWithoutDetector(mat: Mat): OcrResult = recognize(mat)

    fun recognizeText(mat: Mat): String = OcrText.removeAllSpace(recognize(mat).text)

    /**
     * 批量 rec-only：按 rois 顺序逐槽识别，返回与 rois **一一对应**的结果（无文本则空串、score=0）。
     *
     * 方案 §6.1 决策：作为 default 方法进接口——ML Kit **继承即得槽识别能力**（逐槽裁剪 + rec-only），
     * ONNX 覆写为按绝对坐标直接 rec（省掉子图 Mat 头开销，且 rect 保持原图坐标）。
     * 两引擎能力对齐后，A/B 对拍可以共用同一套槽位基准。
     */
    fun recognizeRois(mat: Mat, rois: List<IntRect>): List<OcrResultRegion> =
        rois.map { roi ->
            runCatching {
                val view = MatOps.roiView(mat, roi)
                try {
                    recognizeWithoutDetector(view).regions.firstOrNull() ?: OcrResultRegion(roi, "", 0f)
                } finally {
                    view.release()
                }
            }.getOrDefault(OcrResultRegion(roi, "", 0f))
        }
}

object UnavailableOcrService : IOcrService {
    override fun recognize(mat: Mat): OcrResult = OcrResult.EMPTY
}

object OcrFactory {
    @Volatile
    var default: IOcrService = UnavailableOcrService
        private set

    /** 当前生效引擎（诊断日志 / 面板状态显示用） */
    @Volatile
    var engineLabel: String = "unavailable"
        private set

    /**
     * 是否有可用 OCR 引擎。
     *
     * 移除 ML Kit 后，ONNX 不可用时不再有兜底 → 扫描会静默读出空文本（比崩溃更难查）。
     * 调用方（ScriptRunner）须在开跑前查此值并显式失败。
     */
    val available: Boolean
        get() = default !== UnavailableOcrService

    /** 活跃的 ONNX 服务（基准用）；未启用 ONNX 时为 null。 */
    @Volatile
    private var onnxService: OnnxPaddleOcrService? = null

    /** 诊断基准（adb DEBUG_OCR_BENCH）：当前引擎 det/rec 中位耗时；非 ONNX 时说明原因。 */
    fun bench(): String {
        val s = onnxService
        return if (s != null) s.bench() else "engine=$engineLabel (onnx inactive, no bench)"
    }

    /**
     * rec 宽度基准（只读探针）：按真实 ROI 宽度测单次 rec 耗时 + 输出时间步 T。
     * 见 `dsl/verify/_audit/IMAGE-PATH-COST.md` §8 探针 2。
     */
    /** OCR 并行度探针（只读）：见 `OcrParallelProbe`。 */
    fun ocrParallelProbe(spec: String, widths: IntArray, runs: Int = 5): String {
        val s = onnxService
        return if (s != null) s.parallelProbe(spec, widths, runs) else "engine=$engineLabel (onnx inactive)"
    }

    fun recProbe(widths: IntArray, runs: Int = 5): String {
        val s = onnxService
        return if (s != null) s.recProbe(widths, runs) else "engine=$engineLabel (onnx inactive)"
    }

    /**
     * 初始化 OCR。
     *
     * ONNX PaddleOCR 是**唯一**引擎（ML Kit 已移除：它体积大、精度低，且 R8 下
     * 反射式组件发现被剥坏 ⇒ 保留只会带来 release 崩溃）。初始化在后台协程执行
     * （建会话 + 首次 det 推理为秒级），不阻塞主线程；就绪前 [default] 保持
     * [UnavailableOcrService]，调用方（ScriptRunner）须在开跑前查 [available] 并显式失败。
     */
    fun init(context: Context) {
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val onnx = runCatching { createOnnx(app) }.getOrNull()
            if (onnx != null) {
                default = onnx
                onnxService = onnx
                engineLabel = "onnx:${onnx.tierLabel}"
                Log.i(TAG, "OCR engine → ONNX (${onnx.tierLabel})")
            } else {
                // ONNX 不可用 = 无 OCR（无兜底），必须显式告警；扫描入口会 loud 失败
                Log.e(TAG, "ONNX unavailable, no OCR engine (ML Kit removed)")
            }
        }
    }

    /** 构造 ONNX 服务并预热；任一步失败返回 null（调用方 loud 失败）。 */
    private fun createOnnx(context: Context): OnnxPaddleOcrService? {
        Log.i(TAG, "ONNX init start")
        val assets = OnnxModelAssets(context)
        if (!assets.ensure()) {
            Log.w(TAG, "ONNX model assets incomplete")
            return null
        }
        val engine = OnnxOcrEngine(assets.detModel(), assets.recModel())
        val service = OnnxPaddleOcrService(engine, assets.loadDict())
        if (!service.prepare()) {
            Log.w(TAG, "ONNX prepare failed")
            engine.close()
            return null
        }
        Log.i(TAG, "ONNX init success")
        return service
    }

    private const val TAG = "BetterGI.Ocr"
}
