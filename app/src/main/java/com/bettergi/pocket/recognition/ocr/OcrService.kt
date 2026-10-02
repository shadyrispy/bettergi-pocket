package com.bettergi.pocket.recognition.ocr

import android.content.Context
import android.util.Log
import com.bettergi.pocket.recognition.IntRect
import com.bettergi.pocket.recognition.OcrText
import com.bettergi.pocket.recognition.ocr.onnx.OnnxModelAssets
import com.bettergi.pocket.recognition.ocr.onnx.OnnxOcrEngine
import com.bettergi.pocket.recognition.ocr.onnx.OnnxPaddleOcrService
import com.bettergi.pocket.recognition.opencv.MatOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.opencv.core.Mat
import java.util.concurrent.TimeUnit

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
     * **两段式**（方案 §6.1 规则 2「init 在后台执行」）：
     * 1. 立刻挂 ML Kit —— 冷启动即可用，不阻塞主线程；
     * 2. 后台预热 ONNX（建会话 + 首次 det 推理为秒级），就绪后**热切换**为默认引擎。
     *
     * ONNX 不可用（模型缺失 / 全部 EP 档失败）则静默保持 ML Kit 兜底——
     * 这正是 R4/Q3 已决「双引擎共存，ML Kit 降级兜底 + A/B 对拍」的落地形态。
     */
    fun init(context: Context, preferOnnx: Boolean = true) {
        default = MlKitOcrService()
        engineLabel = "mlkit"
        if (!preferOnnx) return
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val onnx = runCatching { createOnnx(app) }.getOrNull()
            if (onnx != null) {
                default = onnx
                onnxService = onnx
                engineLabel = "onnx:${onnx.tierLabel}"
                Log.i(TAG, "OCR engine → ONNX (${onnx.tierLabel})")
            } else {
                // ML Kit 移除后此为致命路径：ONNX 不可用 = 无 OCR，必须显式告警
                Log.e(TAG, "ONNX unavailable, keep ML Kit fallback (OCR 能力取决于 ML Kit)")
            }
        }
    }

    /** 构造 ONNX 服务并预热；任一步失败返回 null（调用方兜底 ML Kit）。 */
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

class MlKitOcrService(
    private val timeoutSeconds: Long = 3,
) : IOcrService {
    private val recognizer: TextRecognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    override fun recognize(mat: Mat): OcrResult {
        if (mat.empty()) return OcrResult.EMPTY
        val bitmap = MatOps.matToBitmap(mat)
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val visionText = Tasks.await(recognizer.process(image), timeoutSeconds, TimeUnit.SECONDS)
            toOcrResult(visionText)
        } catch (e: Exception) {
            Log.e(TAG, "ML Kit OCR failed", e)
            OcrResult.EMPTY
        } finally {
            bitmap.recycle()
        }
    }

    private fun toOcrResult(visionText: Text): OcrResult {
        val regions = ArrayList<OcrResultRegion>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val rect = IntRect(box.left, box.top, box.width(), box.height())
                if (rect.isEmpty()) continue
                regions.add(OcrResultRegion(rect, line.text, 1.0f))
            }
        }
        return OcrResult(regions)
    }

    private companion object {
        private const val TAG = "BetterGI.Ocr"
    }
}
