package com.bettergi.pocket.recognition.ocr.onnx

import android.util.Log
import com.bettergi.pocket.core.IntRect
import com.bettergi.pocket.recognition.ocr.IOcrService
import com.bettergi.pocket.recognition.ocr.OcrResult
import com.bettergi.pocket.recognition.ocr.OcrResultRegion
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
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
    /** rec 输出类型声明（P3，来自 manifest 可选键 `recOutputType`，缺省 auto = 旧口径）。 */
    private val recOutputType: String = OnnxModelAssets.OUTPUT_AUTO,
) : IOcrService {

    init {
        // 防呆：irminsul 20260827 踩过的坑——rec.onnx 是 PP-OCRv6 模型却误绑 ppocr_keys_v1.txt
        // （v1 字符序），症状是**汉字全部解码错乱但 ASCII/数字看起来正常**，极易漏检。
        // A24：由 Log.w 升级为**硬失败**——字典错绑的引擎等于全量乱码，宁可 OCR 不可用
        // （init 抛出 → OcrFactory.createOnnx 失败 → prepare 通路返回 false）也不能静默错下去。
        if (dict.size != EXPECTED_DICT_SIZE) {
            throw IllegalStateException(
                "字典条目数 ${dict.size}，预期 $EXPECTED_DICT_SIZE。" +
                    "若模型不是 PP-OCRv6-tiny，需同时核对 MODEL_CLASS_COUNT 与字典文件。",
            )
        }
    }

    /**
     * 引擎会话是否就绪（降档/重建窗口、首启预热未完成时为 false）。
     * ★ 2026-09-30（A7）：旧注释「否则所有方法返回空，调用方应降级到 ML Kit」已废弃 ——
     * ML Kit 兜底从未实现、方案裁决**不做**静默兜底。scan 层网关（OcrGatewayImpl）在调用前
     * 查询本字段，未就绪时抛 OcrUnavailableException 与「图上没字」区分。
     * 下方 recognize* 在未就绪时仍返回空结果，只是探针/直接调用者的最后一道防御。
     */
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
        // ★ #74：**取最小中位数**换成了带余量的换档判据 —— 实测最小者只有**比当前档快 ≥20%**
        //   才换，否则留在原档。理由见 EpTierPicker.pickIntraOp 的 KDoc（同机五轮里 intra=4
        //   有四轮慢 2.2~2.3×，而换档是服务生命周期内不可逆的）。
        val incumbent = engine.intraOpThreads
        val best = if (table.isEmpty()) null else EpTierPicker.pickIntraOp(table, incumbent)
        if (best != null && best != incumbent) {
            if (engine.initialize(engine.tier, best)) {
                Log.i(
                    TAG,
                    "intra-op 择优 $candidates → $best（重开会话；基准=" +
                        table.entries.joinToString { "${it.key}=${it.value}ms" } + "）",
                )
            } else {
                // ★ 2026-09-30（A19，optimization-plan-20260930 轨 E）：兜底初始化的返回值此前
                //   **未检查** —— best 与 incumbent 两次建会话都失败（典型：低内存 OOM）时
                //   ready=false 却照样返回 true、日志打 "ready=true"，调用方（OcrFactory.createOnnx）
                //   以为引擎可用。现显式接住：沿用也失败 ⇒ prepare 返回 false（createOnnx 已有
                //   失败路径：engine.close() + 返回 null）。
                //   A6 build-then-swap 之后：initialize(best) 失败时 incumbent 会话**未受影响**
                //   （ready 仍为 true），此时无需真的重开 —— 仅当 incumbent 已不在（防御：
                //   首启从未成功过 / 已被 close）才走兜底重建，且必须检查其返回值。
                if (engine.ready) {
                    Log.w(TAG, "intra=$best 重开会话失败，沿用 $incumbent（原会话未受影响）")
                } else {
                    val restored = engine.initialize(engine.tier, incumbent)
                    if (!restored) {
                        Log.e(TAG, "intra=$best 重开失败，沿用 $incumbent 亦失败 ⇒ OCR 不可用")
                        return false
                    }
                    Log.w(TAG, "intra=$best 重开失败，已重建 $incumbent 会话")
                }
            }
        } else if (table.isNotEmpty()) {
            Log.i(
                TAG,
                "intra-op 不换档，留住 $incumbent（基准=" +
                    table.entries.joinToString { "${it.key}=${it.value}ms" } +
                    "，换档需快 ≥${EpTierPicker.INTRA_SWITCH_MARGIN_PCT}%）",
            )
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
        val ws = acquireWorkspace()
        try {
            val prepared = preprocessDet(ws, mat) ?: return OcrResult.EMPTY
            val (input, geo) = prepared
            // GC P0（工单 B）：det 概率图（~1.6MB）免物化——DbPostProcessor 直接顺序读
            // ORT 输出 FloatBuffer（在张量 close 前消费），argmax/阈值遍历与 FloatArray
            // 路径逐位一致。null = 推理失败或输出长度不足（与旧判据同口径）。
            val boxes = engine.runDetInto(input) { buf, n ->
                if (n < DET_SIZE * DET_SIZE) null else DbPostProcessor.toTextBoxes(buf, DET_SIZE, DET_SIZE)
            } ?: return OcrResult.EMPTY
            val regions = ArrayList<OcrResultRegion>(boxes.size)
            for (box in boxes) {
                val rect = DbPostProcessor.toRect(box, geo, mat.cols(), mat.rows())
                if (rect.isEmpty()) continue
                val line = recognizeLine(ws, mat, rect) ?: continue
                if (line.text.isBlank()) continue
                regions += OcrResultRegion(rect, line.text, line.score)
            }
            return OcrResult(regions.sortedWith(compareBy({ it.rect.centerY }, { it.rect.centerX })))
        } finally {
            releaseWorkspace(ws)
        }
    }

    /** rec-only：整块 Mat 当作一行直接识别（跳过 det）。稳态扫描的槽位识别走这条。 */
    override fun recognizeWithoutDetector(mat: Mat): OcrResult {
        if (!engine.ready || mat.empty()) return OcrResult.EMPTY
        val ws = acquireWorkspace()
        try {
            val rect = IntRect(0, 0, mat.cols(), mat.rows())
            val line = recognizeLine(ws, mat, rect) ?: return OcrResult.EMPTY
            if (line.text.isBlank()) return OcrResult.EMPTY
            return OcrResult(listOf(OcrResultRegion(rect, line.text, line.score)))
        } finally {
            releaseWorkspace(ws)
        }
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
        val ws = acquireWorkspace()
        try {
            return rois.map { roi ->
                if (roi.isEmpty()) return@map OcrResultRegion(roi, "", 0f)
                val line = recognizeLine(ws, mat, roi)
                if (line == null || line.text.isBlank()) OcrResultRegion(roi, "", 0f)
                else OcrResultRegion(roi, line.text, line.score)
            }
        } finally {
            releaseWorkspace(ws)
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

    /** 单行：rec 预处理 → 推理 → CTC 解码。返回 null 表示推理失败（调用方跳过该行）。
     *  ws：调用方持有的预处理工作区（Stage 4-4），输入缓冲在本次 runRec 返回前一直有效。
     *  GC P0（工单 B）：logits（9 槽一轮 ≈10MB）免物化——CtcDecoder 直接顺序读 ORT 输出
     *  FloatBuffer（在张量 close 前消费完）；解码结果 Result 是不可变值对象，块外使用安全。
     *  判据与旧实现同口径：空输出/步数为 0 ⇒ null。 */
    private fun recognizeLine(ws: PreprocWorkspace, src: Mat, rect: IntRect): Line? {
        val prepared = preprocessRec(ws, src, rect) ?: return null
        val (input, width) = prepared
        val decoded = engine.runRecInto(input, width) { buf, n ->
            val steps = n / MODEL_CLASS_COUNT
            if (n == 0 || steps <= 0) null
            else CtcDecoder.decode(buf, steps, MODEL_CLASS_COUNT, dict, declaredOutput = recOutputType)
        } ?: return null
        return Line(rect, decoded.text, decoded.confidence)
    }

    private data class Line(val rect: IntRect, val text: String, val score: Float)

    // ---- 预处理（Mat 直通，归一化与缩放全在 OpenCV 原生侧完成）----

    // ================= Stage 4-4：短命分配复用 =================

    /**
     * 预处理工作区：det/rec 共用的画布与 NCHW 中转缓冲，逐帧复用。
     *
     * 单个工作区约 **17.5MB 常驻**（native：canvas 1.2 + floatMat 4.7 + planes 3×1.6 + resized ≤1.2；
     * Java 堆：out 4.7 + tmp 1.6MB），对应旧路径**每次全帧 det 的一次性分配**（见 [preprocessDetReference]）。
     *
     * 生命周期契约：acquire → 一次 det 预处理+runDet（或 N 次 rec 预处理+runRec）→ release。
     * 输入 FloatBuffer 包装的是 [out]，必须在推理返回前不被下一个预处理覆盖 ——
     * 推理是同步的且在持有工作区期间执行，单线程内天然满足。
     */
    private class PreprocWorkspace {
        /** det 画布：构造时 Mat.zeros 一次，之后逐帧只清 pad 条带（见 preprocessDet） */
        val detCanvas: Mat = Mat.zeros(DET_SIZE, DET_SIZE, CvType.CV_8UC3)
        /** resize 目标（det/rec 共用；同一工作区单线程内顺序使用） */
        val resized: Mat = Mat()
        /** convertTo 的 32FC3 中转 */
        val floatMat: Mat = Mat()
        /** split 输出的 3 个通道平面（原生序 B,G,R） */
        val planes: ArrayList<Mat> = arrayListOf(Mat(), Mat(), Mat())
        /** 通道平面→Java 的中转（grow-only，永不收缩） */
        var tmp: FloatArray = FloatArray(0)
        /** NCHW 输出（grow-only；det 恒 3×640×640，rec 随宽度变化取最大） */
        var out: FloatArray = FloatArray(0)

        fun release() {
            detCanvas.release()
            resized.release()
            floatMat.release()
            planes.forEach { it.release() }
        }
    }

    /**
     * 工作区池的**并发依据**：runDet/runRec 在引擎里只持 `sessionLock.read`（见
     * OnnxOcrEngine）——读写锁允许多线程同时推理，OR T session 本身并发安全。生产调用面上
     * 并发**确实可能**：OcrFactory.default 是单例服务，ScriptRunner.ocrDetProbe（调试探针，
     * 任意界面可触发）与扫描主协程（Dispatchers.Default，startScan 快速重启窗口里新旧 job 并存）
     * 都可能同时调 recognize/recognizeRois。因此**不能**用裸实例字段当缓冲（会把线程 A 的输入
     * 覆盖线程 B 的推理），也不能 ThreadLocal（Dispatchers.Default 每个 worker 都会物化一份
     * 17.5MB，且 Mat 持 native 内存无法随线程池回收）。
     *
     * 取舍：**小型独占池**（上限 [WS_POOL_MAX]）。常态单线程零争用（acquire 一次、release 归还）；
     * 并发时各取独占工作区互不干扰；池耗尽的极端调用走「新建-即弃」，语义等同旧逐次分配路径，
     * 只慢不错。上限 2 覆盖已知并发面（扫描 + 探针），常驻上限约 2×17.5MB。
     */
    private val wsPool = ArrayDeque<PreprocWorkspace>()
    private val wsLock = Any()

    private fun acquireWorkspace(): PreprocWorkspace =
        synchronized(wsLock) { wsPool.removeFirstOrNull() ?: PreprocWorkspace() }

    private fun releaseWorkspace(ws: PreprocWorkspace) = synchronized(wsLock) {
        if (wsPool.size < WS_POOL_MAX) wsPool.addLast(ws) else ws.release()
    }

    /**
     * det 预处理（复用版）：等比缩放 + 黑边 pad 到 640×640（左上锚定，pad 在右/下）。
     * 逻辑与 [preprocessDetReference]（逐次分配对照版）逐位等价；差别只在缓冲来源：
     * - 画布不再逐帧 `Mat.zeros`：构造时清零一次，之后逐帧只清 **pad 条带**（右条 x∈[sw,640)
     *   全高 + 下条 y∈[sh,640) x∈[0,sw)，两条并集恰为内容区 (0,0,sw,sh) 的补集）——
     *   上一帧内容只可能落在其内容区 (0,0,sw_prev,sh_prev) ⊂ 本帧 pad 区（或本帧内容区，
     *   会被 resize+copyTo 整块覆盖），故逐帧重清两条即与全新零画布逐位一致。
     * - resized/floatMat/planes/out/tmp 全部复用（OpenCV 的 create() 在尺寸不变时零重分配）。
     *
     * 为什么不直接拉伸到 640×640：irminsul B1 教训——拉伸会让宽屏截图里的文字压扁，
     * 汉字识别直接错乱。pad 区域归一化后为 -1（最黑）→ 低概率，不会产框。
     */
    private fun preprocessDet(ws: PreprocWorkspace, src: Mat): Pair<FloatBuffer, DbPostProcessor.DetGeometry>? {
        val w = src.cols()
        val h = src.rows()
        if (w <= 0 || h <= 0) return null
        val scale = minOf(DET_SIZE.toFloat() / w, DET_SIZE.toFloat() / h)
        val sw = (w * scale).roundToInt().coerceIn(1, DET_SIZE)
        val sh = (h * scale).roundToInt().coerceIn(1, DET_SIZE)
        val bgr = ensureBgr(src) ?: return null
        val ownedBgr = bgr !== src
        try {
            // 清 pad 条带（与逐帧 Mat.zeros 等价，理由见上 KDoc）
            if (sw < DET_SIZE) {
                val right = Mat(ws.detCanvas, Rect(sw, 0, DET_SIZE - sw, DET_SIZE))
                try { right.setTo(ZERO_SCALAR) } finally { right.release() }
            }
            if (sh < DET_SIZE) {
                val bottom = Mat(ws.detCanvas, Rect(0, sh, sw, DET_SIZE - sh))
                try { bottom.setTo(ZERO_SCALAR) } finally { bottom.release() }
            }
            Imgproc.resize(bgr, ws.resized, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            val roi = Mat(ws.detCanvas, Rect(0, 0, sw, sh))
            try {
                ws.resized.copyTo(roi)
            } finally {
                roi.release()
            }
            val nchw = toNchw(ws, ws.detCanvas)
            // 显式限定长度：out 可能被更宽的 rec 预处理撑大，det 张量必须恰为 3×640×640
            return FloatBuffer.wrap(nchw, 0, 3 * DET_SIZE * DET_SIZE) to
                DbPostProcessor.DetGeometry(sw.toFloat() / w, sh.toFloat() / h)
        } finally {
            if (ownedBgr) bgr.release()
        }
    }

    /**
     * rec 预处理（复用版）：按宽高比等比缩放到高 48（**不拉伸、不固定宽**），归一化 → NCHW。
     * 与 [preprocessRec] 原逐次分配逻辑逐位等价，缓冲改用 [ws]（crop/resized 内容整块覆盖）。
     *
     * irminsul 教训：原实现固定宽 320 拉伸，破坏字符纵横比，是汉字识别错乱的根因。
     */
    private fun preprocessRec(ws: PreprocWorkspace, src: Mat, rect: IntRect): Pair<FloatBuffer, Int>? {
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
        try {
            Imgproc.resize(crop, ws.resized, Size(scaledW.toDouble(), REC_H.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            val nchw = toNchw(ws, ws.resized)
            // 显式限定长度：out/tmp 可能被更宽的上一行撑大，rec 张量必须恰为 3×48×scaledW
            return FloatBuffer.wrap(nchw, 0, 3 * scaledW * REC_H) to scaledW
        } finally {
            crop.release()
            if (ownedBgr) bgr.release()
        }
    }

    /** 非 3 通道输入统一转成 BGR（采集链正常就是 BGR，这里是防御；防御路径保留逐次分配） */
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
     * 复用版：floatMat/planes/out/tmp 来自 [ws]（grow-only，尺寸不足时才重新分配）。
     *
     * 归一化与缩放都在 OpenCV 原生侧做（`convertTo` 带 scale/shift + `split`），
     * Kotlin 侧只做 3 次整块读取 + 3 次 arraycopy 完成通道重排，避免逐像素循环。
     */
    private fun toNchw(ws: PreprocWorkspace, mat: Mat): FloatArray {
        val stride = mat.cols() * mat.rows()
        val out = if (ws.out.size >= 3 * stride) ws.out else FloatArray(3 * stride).also { ws.out = it }
        val tmp = if (ws.tmp.size >= stride) ws.tmp else FloatArray(stride).also { ws.tmp = it }
        mat.convertTo(ws.floatMat, CvType.CV_32FC3, 1.0 / 127.5, -1.0)
        Core.split(ws.floatMat, ws.planes) // 顺序 B, G, R
        // 输出通道序 R,G,B（模型约定），源 planes 序 B,G,R
        for (ch in 0 until 3) {
            ws.planes[2 - ch].get(0, 0, tmp)
            System.arraycopy(tmp, 0, out, ch * stride, stride)
        }
        return out
    }

    // ================= 对照实现（Stage 4-4 之前的逐次分配原路径）=================
    // 仅供逐位一致性测试（OnnxOcrBenchmarkTest）与耗时对比使用，生产路径不再调用。

    /**
     * 对照：det 预处理，逐次分配（原版逐字保留）。
     * 每次调用分配：canvas 1.2MB + resized ≤1.2MB（native），floatMat 4.7MB + planes 4.7MB（native），
     * out 4.7MB + tmp 1.6MB（Java 堆）→ 全帧 det 约 **17~18MB/帧** 的短命分配。
     */
    private fun preprocessDetReference(src: Mat): Pair<FloatArray, DbPostProcessor.DetGeometry>? {
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
            val nchw = toNchwOnce(canvas)
            return nchw to DbPostProcessor.DetGeometry(sw.toFloat() / w, sh.toFloat() / h)
        } finally {
            resized.release()
            canvas.release()
            if (ownedBgr) bgr.release()
        }
    }

    /** 对照：toNchw，逐次分配（原版逐字保留）。 */
    private fun toNchwOnce(mat: Mat): FloatArray {
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

    // ---- 测试钩子（仅 JVM 单测使用）----

    /** 旧路径（逐次分配）det 预处理结果。 */
    internal fun detPreprocessOnceForTest(src: Mat): Pair<FloatArray, DbPostProcessor.DetGeometry>? =
        preprocessDetReference(src)

    /** 新路径（复用）det 预处理结果：走生产 acquire/release，返回 out 数组副本（防工作区复写）。 */
    internal fun detPreprocessReuseForTest(src: Mat): Pair<FloatArray, DbPostProcessor.DetGeometry>? {
        val ws = acquireWorkspace()
        try {
            val prepared = preprocessDet(ws, src) ?: return null
            val (buf, geo) = prepared
            return FloatArray(3 * DET_SIZE * DET_SIZE).also { buf.get(it) } to geo
        } finally {
            releaseWorkspace(ws)
        }
    }

    companion object {
        private const val TAG = "BetterGI.Ocr.Onnx"

        const val DET_SIZE = 640
        const val REC_H = 48
        const val REC_W_MAX = 4096

        /** pad 条带清零用的黑标量（det 画布复用，见 preprocessDet） */
        private val ZERO_SCALAR = Scalar(0.0, 0.0, 0.0)

        /** 预处理工作区池上限（并发依据与取舍见 wsPool 的 KDoc；≈2×17.5MB 常驻上限） */
        private const val WS_POOL_MAX = 2

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
