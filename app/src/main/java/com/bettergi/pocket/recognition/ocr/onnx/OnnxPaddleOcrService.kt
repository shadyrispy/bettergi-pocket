package com.bettergi.pocket.recognition.ocr.onnx

import android.util.Log
import com.bettergi.pocket.recognition.IntRect
import com.bettergi.pocket.recognition.ocr.IOcrService
import com.bettergi.pocket.recognition.ocr.OcrResult
import com.bettergi.pocket.recognition.ocr.OcrResultRegion
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import kotlin.math.roundToInt

/**
 * PaddleOCR（det + rec）ONNX 管线，实现 [IOcrService]。
 *
 * 与 ML Kit 实现的分工（方案 §6.1）：**预处理入参是 Mat，不落 Bitmap**
 * ——采集链本身就是 buffer→Mat（模板匹配也要 Mat），转 Bitmap 反而多一次物化；
 * 归一化/缩放全部走 OpenCV 原生（向量化），只有 NCHW 重排的一次内存拷贝是固有成本。
 *
 * 文本清洗不在本层做：本服务只出模型识别的原始文本，**清洗仍由 scan 层
 * `StatParser.clean` / `OcrText` 负责**（irminsul 那套无条件 O→0/S→5/a→4 映射
 * 会污染含拉丁字符的套装名/武器名，且与本仓既有清洗规则冲突，故不移植）。
 *
 * 移植自 irminsul `OcrPipeline` + `OnnxRuntimeEngine`，适配：Mat 入参、同步接口、IntRect 几何。
 */
class OnnxPaddleOcrService(
    private val engine: OnnxOcrEngine,
    private val dict: List<String>,
) : IOcrService {

    init {
        // 防呆：irminsul 20260827 踩过的坑——rec.onnx 是 PP-OCRv6 模型却误绑 ppocr_keys_v1.txt
        // （v1 字符序），症状是**汉字全部解码错乱但 ASCII/数字看起来正常**，极易漏检。
        if (dict.size != EXPECTED_DICT_SIZE) {
            Log.w(
                TAG,
                "字典条目数 ${dict.size}，预期 $EXPECTED_DICT_SIZE。" +
                    "若模型不是 PP-OCRv6-tiny，需同时核对 MODEL_CLASS_COUNT 与字典文件。",
            )
        }
    }

    /** 已准备（会话就绪）则可识别；否则所有方法返回空，调用方应降级到 ML Kit。 */
    val ready: Boolean get() = engine.ready

    override val hasFastRecOnlyBatch: Boolean = true

    /** 当前生效 EP 档位（诊断/日志用） */
    val tierLabel: String get() = engine.tier.label

    /** 生产入口：自己采集设备事实。 */
    fun prepare(): Boolean = prepare(DeviceCapabilities.collect())

    /**
     * 首启按硬件定档：EP 候选序 + intra-op 线程实测择优 + 预热。
     * 应在后台线程调用（建会话与基准为秒级）。全部档位均不可用返回 false。
     *
     * 为什么还要实测 intra：同一条曲线**跨会话能漂 ±60%**（Kirin 970 上 intra=2 实测 406~818ms），
     * 任何写死值都只在"测它的那一次"成立。⚠️ 但**别**据此以为 4 更好 —— 2026-09-26 五轮复测里
     * 四轮 intra=4 比 2 慢 2.2~2.3×，理由与数据见 [EpTierPicker.intraOpCandidates]。
     *
     * 为什么默认不试 XNNPACK/NNAPI：见 [EpTierPicker.candidatesFor] —— 两者的失败方式
     * 分别是"挂起"和"原生 SIGSEGV"，都抓不住，拿启动去赌不值。需要时用 [allowRiskyEp] 显式开。
     */
    fun prepare(caps: DeviceCapabilities, allowRiskyEp: Boolean = false): Boolean {
        val order = EpTierPicker.candidatesFor(caps, allowRiskyEp)
        engine.tierOrder = order
        var ok = false
        for (tier in order) {
            ok = runCatching { engine.initialize(tier) }.getOrDefault(false)
            if (ok) break
            Log.w(TAG, "EP ${tier.label} 建会话失败，回退下一档")
        }
        if (!ok) {
            Log.e(TAG, "所有 EP 档位均不可用（候选=${order.joinToString { it.label }}），OCR 不可用")
            return false
        }
        val candidates = EpTierPicker.intraOpCandidates(caps)
        val table = if (candidates.size > 1) engine.benchmarkIntra(candidates) else emptyMap()
        val best = table.entries.minByOrNull { it.value }?.key
        if (best != null && best != engine.intraOpThreads) {
            if (engine.initialize(engine.tier, best)) {
                Log.i(TAG, "intra-op 择优 $candidates → $best（重开会话）")
            } else {
                Log.w(TAG, "intra=$best 重开会话失败，沿用 ${engine.intraOpThreads}")
                engine.initialize(engine.tier, engine.intraOpThreads)
            }
        }
        Log.i(
            TAG,
            "ONNX OCR ready=true ort=1.30.0 ${caps.summary()} 候选=${order.joinToString { it.label }} " +
                "tier=${engine.tier.label} intra=${engine.intraOpThreads} 基准=" +
                (if (table.isEmpty()) "跳过(单候选)" else table.entries.joinToString { "${it.key}=${it.value}ms" }),
        )
        return true
    }

    override fun recognize(mat: Mat): OcrResult {
        if (!engine.ready || mat.empty()) return OcrResult.EMPTY
        val prepared = preprocessDet(mat) ?: return OcrResult.EMPTY
        val (input, geo) = prepared
        val probMap = engine.runDet(input)
        if (probMap.size < DET_SIZE * DET_SIZE) return OcrResult.EMPTY
        val boxes = DbPostProcessor.toTextBoxes(probMap, DET_SIZE, DET_SIZE)
        val regions = ArrayList<OcrResultRegion>(boxes.size)
        for (box in boxes) {
            val rect = DbPostProcessor.toRect(box, geo, mat.cols(), mat.rows())
            if (rect.isEmpty()) continue
            val line = recognizeLine(mat, rect) ?: continue
            if (line.text.isBlank()) continue
            regions += OcrResultRegion(rect, line.text, line.score)
        }
        return OcrResult(regions.sortedWith(compareBy({ it.rect.centerY }, { it.rect.centerX })))
    }

    /** rec-only：整块 Mat 当作一行直接识别（跳过 det）。稳态扫描的槽位识别走这条。 */
    override fun recognizeWithoutDetector(mat: Mat): OcrResult {
        if (!engine.ready || mat.empty()) return OcrResult.EMPTY
        val rect = IntRect(0, 0, mat.cols(), mat.rows())
        val line = recognizeLine(mat, rect) ?: return OcrResult.EMPTY
        if (line.text.isBlank()) return OcrResult.EMPTY
        return OcrResult(listOf(OcrResultRegion(rect, line.text, line.score)))
    }

    /**
     * 批量 rec-only：按 rois 顺序逐槽识别，返回与 rois 一一对应的结果（无文本则空串、score=0）。
     * 相比接口默认实现（逐个建子图再 recognizeWithoutDetector），这里直接按绝对坐标做 rec，
     * 省掉子图 Mat 头的创建/释放，且返回的 rect 保持原图绝对坐标。
     */
    override fun recognizeRois(mat: Mat, rois: List<IntRect>): List<OcrResultRegion> {
        if (!engine.ready || mat.empty()) return rois.map { OcrResultRegion(it, "", 0f) }
        // ★ 2026-09-12 实测结论：**padding 打 batch（原 B2 方案）已回退 —— 收益为负**。
        //   实测（BlueStacks 2244；两侧各 3 次取中位；只比非等待的「其它」分量＝点击+OCR+导出）：
        //     武器   42 格：逐槽 206ms/格  →  批量 221ms/格  （+7%，但武器侧样本 7529~9530 方差大 ⇒ 不显著）
        //     圣遗物 21 格：逐槽 374ms/格  →  批量 437ms/格  （+17%，两侧区间不重叠 ⇒ 确定负优化）
        //   根因两条：① 计算量按 `n × Wmax` 计，而各槽宽度**异质**（圣遗物 subStats 4 槽
        //   scaledW=684，其余 5 槽仅 145~389）⇒ n·Wmax / Σw = **1.59×**，窄槽被白白 pad 宽；
        //   ② 每次调用需分配 `n*3*Wmax*REC_H` 个 float（圣遗物峰值 **3.5MB/格**）再逐行 arraycopy
        //   ⇒ GC 与内存带宽开销可观。
        //   精度结论：批量与逐槽**逐字段完全等价**（武器 36 件 vs 改动前 golden、圣遗物 20 件
        //   批量 vs 逐槽，均 100% 一致）⇒ 纯性能取舍，故回退。
        //   （若再试：仅当各槽 scaledW 彼此接近时才可能有收益，必须单独实测，勿凭直觉重上。）
        return rois.map { roi ->
            if (roi.isEmpty()) return@map OcrResultRegion(roi, "", 0f)
            val line = recognizeLine(mat, roi)
            if (line == null || line.text.isBlank()) OcrResultRegion(roi, "", 0f)
            else OcrResultRegion(roi, line.text, line.score)
        }
    }

    /**
     * rec 宽度基准（**只读探针**）：按真实 ROI 宽度测单次 rec 耗时，并回报**输出时间步 T**
     * （`logits.size / MODEL_CLASS_COUNT`）⇒ 可算出 logits 物化的真实字节数。
     *
     * 零张量输入（与 [bench] 同口径）⇒ 只测纯执行开销，不依赖图像内容。
     */
    /**
     * OCR 并行度探针（只读）：按 `spec="1:1,1:2,2:1,3:1"` 逐档建 N 个会话跑真实槽宽，
     * 报每轮 wall ms。见 [OcrParallelProbe] 的判读说明。
     */
    fun parallelProbe(spec: String, widths: IntArray, runs: Int = 5): String {
        if (!engine.ready) return "engine not ready"
        return OcrParallelProbe.run(engine.recModelPath, spec, widths, runs)
    }

    fun recProbe(widths: IntArray, runs: Int = 5): String {
        if (!engine.ready) return "engine not ready"
        val sb = StringBuilder("tier=${engine.tier.label} runs=$runs")
        for (w in widths) {
            val wi = w.coerceIn(1, REC_W_MAX)
            val input = FloatBuffer.wrap(FloatArray(3 * REC_H * wi))
            engine.runRec(input, wi) // 预热
            val ms = medianMs(runs) { engine.runRec(input, wi) }
            val logits = engine.runRec(input, wi)
            val steps = if (logits.isEmpty()) -1 else logits.size / MODEL_CLASS_COUNT
            val bytes = logits.size.toLong() * 4
            sb.append(
                "\n  rec w=$wi ${ms}ms steps=$steps logits=${bytes}B " +
                    "(${"%.2f".format(bytes / 1048576.0)}MB)",
            )
        }
        return sb.toString()
    }

    /**
     * 诊断基准（adb `DEBUG_OCR_BENCH` 用）：det/rec 各跑 [runs] 次取中位 ms。
     * 零张量即测纯执行开销，不依赖图像内容；用于真机 EP 选型与瓶颈定位。
     */
    fun bench(runs: Int = 5): String {
        if (!engine.ready) return "engine not ready"
        val detIn = FloatBuffer.wrap(FloatArray(DET_SIZE * DET_SIZE * 3))
        engine.runDet(detIn) // 预热
        val detMs = medianMs(runs) { engine.runDet(detIn) }
        val recW = 320
        val recIn = FloatBuffer.wrap(FloatArray(3 * REC_H * recW))
        engine.runRec(recIn, recW) // 预热
        val recMs = medianMs(runs) { engine.runRec(recIn, recW) }
        // ★ 分段：建张量 / native run / 输出物化 —— 见 OnnxOcrEngine.runSplitProbe 的动机说明
        val split = listOf(320, 684).joinToString("\n  ") { w ->
            runCatching { engine.runSplitProbe(w, runs) }.getOrElse { "rec w=$w split probe failed: ${it.message}" }
        }
        return "tier=${engine.tier.label} det=${detMs}ms rec=${recMs}ms runs=$runs\n  $split"
    }

    private inline fun medianMs(runs: Int, block: () -> Unit): Long {
        val times = LongArray(runs) {
            val t0 = System.nanoTime()
            block()
            (System.nanoTime() - t0) / 1_000_000
        }
        return times.sorted()[times.size / 2]
    }

    /** 单行：rec 预处理 → 推理 → CTC 解码。返回 null 表示推理失败（调用方跳过该行）。 */
    private fun recognizeLine(src: Mat, rect: IntRect): Line? {
        val prepared = preprocessRec(src, rect) ?: return null
        val (input, width) = prepared
        val logits = engine.runRec(input, width)
        if (logits.isEmpty()) return null
        val steps = logits.size / MODEL_CLASS_COUNT
        if (steps <= 0) return null
        val decoded = CtcDecoder.decode(logits, steps, MODEL_CLASS_COUNT, dict)
        return Line(rect, decoded.text, decoded.confidence)
    }

    private data class Line(val rect: IntRect, val text: String, val score: Float)

    // ---- 预处理（Mat 直通，归一化与缩放全在 OpenCV 原生侧完成）----

    /**
     * det 预处理：等比缩放 + 黑边 pad 到 640×640（左上锚定，pad 在右/下）。
     *
     * 为什么不直接拉伸到 640×640：irminsul B1 教训——拉伸会让宽屏截图里的文字压扁，
     * 汉字识别直接错乱。pad 区域归一化后为 -1（最黑）→ 低概率，不会产框。
     */
    private fun preprocessDet(src: Mat): Pair<FloatBuffer, DbPostProcessor.DetGeometry>? {
        val w = src.cols()
        val h = src.rows()
        if (w <= 0 || h <= 0) return null
        val scale = minOf(DET_SIZE.toFloat() / w, DET_SIZE.toFloat() / h)
        val sw = (w * scale).roundToInt().coerceIn(1, DET_SIZE)
        val sh = (h * scale).roundToInt().coerceIn(1, DET_SIZE)
        val bgr = ensureBgr(src) ?: return null
        val canvas = Mat.zeros(DET_SIZE, DET_SIZE, CvType.CV_8UC3)
        val resized = Mat()
        val ownedBgr = bgr !== src
        try {
            Imgproc.resize(bgr, resized, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            val roi = Mat(canvas, Rect(0, 0, sw, sh))
            try {
                resized.copyTo(roi)
            } finally {
                roi.release()
            }
            val nchw = toNchw(canvas)
            return FloatBuffer.wrap(nchw) to DbPostProcessor.DetGeometry(sw.toFloat() / w, sh.toFloat() / h)
        } finally {
            resized.release()
            canvas.release()
            if (ownedBgr) bgr.release()
        }
    }

    /**
     * rec 预处理：按宽高比等比缩放到高 48（**不拉伸、不固定宽**），归一化 → NCHW。
     *
     * irminsul 教训：原实现固定宽 320 拉伸，破坏字符纵横比，是汉字识别错乱的根因。
     */
    private fun preprocessRec(src: Mat, rect: IntRect): Pair<FloatBuffer, Int>? {
        val w = src.cols()
        val h = src.rows()
        if (w <= 0 || h <= 0) return null
        val x = rect.x.coerceIn(0, w - 1)
        val y = rect.y.coerceIn(0, h - 1)
        val rw = rect.width.coerceIn(1, w - x)
        val rh = rect.height.coerceIn(1, h - y)
        val bgr = ensureBgr(src) ?: return null
        val ownedBgr = bgr !== src
        val crop = Mat(bgr, Rect(x, y, rw, rh)) // 零拷贝视图（仅新建 Mat 头）
        val scaledW = scaledWidthFor(rw, rh)
        val resized = Mat()
        try {
            Imgproc.resize(crop, resized, Size(scaledW.toDouble(), REC_H.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            val nchw = toNchw(resized)
            return FloatBuffer.wrap(nchw) to scaledW
        } finally {
            resized.release()
            crop.release()
            if (ownedBgr) bgr.release()
        }
    }

    /** 非 3 通道输入统一转成 BGR（采集链正常就是 BGR，这里是防御） */
    private fun ensureBgr(src: Mat): Mat? {
        return when (src.channels()) {
            3 -> src
            1 -> {
                val dst = Mat()
                Imgproc.cvtColor(src, dst, Imgproc.COLOR_GRAY2BGR)
                dst
            }
            4 -> {
                val dst = Mat()
                Imgproc.cvtColor(src, dst, Imgproc.COLOR_BGRA2BGR)
                dst
            }
            else -> null
        }
    }

    /**
     * Mat(BGR, CV_8UC3) → NCHW float，归一化到 [-1, 1]（v/127.5 - 1，det 与 rec 同一公式）。
     *
     * 归一化与缩放都在 OpenCV 原生侧做（`convertTo` 带 scale/shift + `split`），
     * Kotlin 侧只做 3 次整块读取 + 3 次 arraycopy 完成通道重排，避免逐像素循环。
     */
    private fun toNchw(mat: Mat): FloatArray {
        val stride = mat.cols() * mat.rows()
        val out = FloatArray(3 * stride)
        val floatMat = Mat()
        try {
            mat.convertTo(floatMat, CvType.CV_32FC3, 1.0 / 127.5, -1.0)
            val planes = ArrayList<Mat>(3)
            try {
                Core.split(floatMat, planes) // 顺序 B, G, R
                val tmp = FloatArray(stride)
                // 输出通道序 R,G,B（模型约定），源 planes 序 B,G,R
                for (ch in 0 until 3) {
                    planes[2 - ch].get(0, 0, tmp)
                    System.arraycopy(tmp, 0, out, ch * stride, stride)
                }
            } finally {
                planes.forEach { it.release() }
            }
        } finally {
            floatMat.release()
        }
        return out
    }

    companion object {
        private const val TAG = "BetterGI.Ocr.Onnx"

        const val DET_SIZE = 640
        const val REC_H = 48
        const val REC_W_MAX = 4096

        /**
         * rec 模型输出类数（index 0 = CTC blank，1..6905 = 字符类）。
         *
         * **不得用 dict.size + 1 推断**：字典 6904 条 + blank = 6905，模型还多 1 个未知类 → 6906。
         * irminsul 审计 H3：`dict[best-1]` 契约下 classCount 必须是模型真实类数，否则时间步步长错乱，
         * 解码出一串乱码（且看起来"有输出"，极具迷惑性）。
         */
        const val MODEL_CLASS_COUNT = 6906

        /** 配套字典 ppocrv6_tiny_dict.txt 的条目数（用于初始化防呆校验，非推理参数） */
        const val EXPECTED_DICT_SIZE = 6904

        /**
         * 等比缩放到高 [REC_H] 后的宽（PP-OCR `resize_to_h48` 对齐）：
         * 640×32 → 960×48；48×48 → 48×48。JVM 可单测。
         */
        fun scaledWidthFor(w: Int, h: Int): Int =
            ((w * REC_H.toFloat() / h.coerceAtLeast(1)).roundToInt()).coerceIn(1, REC_W_MAX)
    }
}
