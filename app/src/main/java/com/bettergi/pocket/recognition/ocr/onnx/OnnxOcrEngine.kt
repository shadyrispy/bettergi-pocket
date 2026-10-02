package com.bettergi.pocket.recognition.ocr.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.util.Collections
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * ONNX Runtime 推理引擎：det/rec 双 OrtSession + EP 三档协商。
 *
 * 设计要点：
 * - **不持 Context**：只吃模型文件路径，JVM 单测可脱离 Android 直接构造并跑真实推理。
 * - **同步阻塞**：`OrtSession.run` 本就是阻塞 native 调用；`IOcrService` 也是同步接口，
 *   调用方（ScanEngine 协程）已在后台线程。相比 irminsul 的 suspend 版省掉一层无实际挂起的包装。
 * - **线程安全**：OrtSession 非线程安全 → 读写锁。run 取读锁，close 取写锁，
 *   **写锁会等待所有在途 native run 返回后才释放 session**——
 *   irminsul B5 崩溃教训：原生内存 use-after-free → SIGSEGV（native 调用不响应协程取消）。
 * - **输入名动态取**：不同导出的模型输入名可能不是 "x"，从 session.inputInfo 读，不硬编码。
 *
 * 注意：本类用 android.util.Log。JVM 单测里能跑通依赖 `testOptions.unitTests.isReturnDefaultValues = true`
 * （android.jar 方法返回默认值而非抛 "not mocked"），代价是**单测中日志被静默丢弃**——
 * 排查单测问题不要依赖这里的 Log，请在测试内直接 println。
 *
 * 移植自 irminsul `com.esc.irminsul.ocr.OnnxRuntimeEngine`。
 */
class OnnxOcrEngine(
    private val detModel: File,
    private val recModel: File,
) : AutoCloseable {

    private val sessionLock = ReentrantReadWriteLock()

    @Volatile
    private var env: OrtEnvironment? = null

    @Volatile
    private var detSession: OrtSession? = null

    @Volatile
    private var recSession: OrtSession? = null

    /** det/rec 各自的输入张量名（初始化时从模型读出） */
    @Volatile
    private var detInputName: String = "x"

    @Volatile
    private var recInputName: String = "x"

    /** 连续推理失败计数（达阈值触发 EP 降档；成功即清零） */
    private val consecutiveFailures = AtomicInteger(0)

    /** 当前生效 EP 档位 */
    @Volatile
    var tier: EpTierPicker.Tier = EpTierPicker.Tier.CPU
        private set

    /** 当前生效的 intra-op 线程数（首启实测择优，见 [benchmarkIntra]） */
    @Volatile
    var intraOpThreads: Int = DEFAULT_INTRA_OP_THREADS
        private set

    /**
     * 本次会话的 EP 候选序（运行期降档沿此序回退），由 [OnnxPaddleOcrService.prepare] 按硬件写入。
     * 默认只有 CPU ⇒ 单测里构造引擎不会隐式去试任何有风险的原生 EP。
     */
    @Volatile
    var tierOrder: List<EpTierPicker.Tier> = listOf(EpTierPicker.Tier.CPU)

    /** 会话是否已就绪（未就绪时所有 run 返回空，调用方应降级到 ML Kit） */
    @Volatile
    var ready: Boolean = false
        private set

    /** rec 模型文件路径（仅并行度探针 [OcrParallelProbe] 用来另建 N 个会话）。 */
    val recModelPath: String get() = recModel.absolutePath

    /** 初始化（装载模型 + 按目标档创建双会话）。失败返回 false 并保持未就绪。 */
    fun initialize(targetTier: EpTierPicker.Tier, intraThreads: Int = DEFAULT_INTRA_OP_THREADS): Boolean {
        val result = runCatching {
            close()
            val e = OrtEnvironment.getEnvironment()
            val detOpts = buildOptions(targetTier, intraThreads)
            val recOpts = buildOptions(targetTier, intraThreads)
            var d: OrtSession? = null
            var r: OrtSession? = null
            try {
                d = e.createSession(detModel.absolutePath, detOpts)
                r = e.createSession(recModel.absolutePath, recOpts)
            } catch (t: Throwable) {
                // options 与"已经建好的那一半 session"都持 native 内存，而本方法每次换档/
                // 重试都会再走一遍 ⇒ 半途失败必须收干净，否则泄漏会累积。
                runCatching { r?.close() }
                runCatching { d?.close() }
                throw t
            } finally {
                runCatching { detOpts.close() }
                runCatching { recOpts.close() }
            }
            env = e
            detSession = d
            recSession = r
            detInputName = d.inputInfo.keys.firstOrNull() ?: "x"
            recInputName = r.inputInfo.keys.firstOrNull() ?: "x"
            tier = targetTier
            intraOpThreads = intraThreads
            ready = true
        }
        if (result.isFailure) {
            ready = false
        }
        return result.isSuccess
    }

    private fun buildOptions(tier: EpTierPicker.Tier, intraThreads: Int): OrtSession.SessionOptions {
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(intraThreads.coerceAtLeast(1))
        when (tier) {
            EpTierPicker.Tier.NNAPI -> opts.addNnapi(NN_API_FLAGS)
            EpTierPicker.Tier.XNNPACK -> opts.addXnnpack(emptyMap())
            // QNN 需要高通 SDK 的 libQnnHtp.so 等（ORT 的 AAR 不含）；能不能进候选由
            // DeviceCapabilities.qnnLibsLoadable 决定，这里只负责真的加上。
            EpTierPicker.Tier.QNN -> opts.addQnn(emptyMap())
            EpTierPicker.Tier.CPU -> { /* 默认 CPU EP，无需追加 */ }
        }
        return opts
    }

    /**
     * intra-op 线程数择优：对每个候选**另建一个临时 rec 会话**，跑真实槽宽的 run 取中位。
     *
     * 为什么必须实测而不能按核数拍：见 [EpTierPicker.intraOpCandidates] —— 同一台机同一个构建，
     * intra=2 的绝对值跨会话能漂 406~818ms，按机型/核数推出来的数没有依据。
     *
     * ★ 2026-09-28（#74）：`runs` 由 **3 提到 15**，并且调用侧的换档判据从"取最小中位数"换成
     * [EpTierPicker.pickIntraOp]（挑战者须比当前档快 ≥20% 才换）。
     * 背景：真机有一次（2026-09-26 15:57）把 intra=4 判成赢家，而它随后四轮复测都是**慢 2.2~2.3×**
     * ⇒ 3 次采样的中位数不足以挡住这种读数，而换档在服务生命周期内不可逆。
     * 代价（可接受）：初始化多花 `候选数 × 16 次 rec run` 的墙钟。按真机 CPU 档 4 槽一轮 ~400ms 估，
     *   3 个候选 ≈ **2~20s 一次性**；换来的是整轮扫描（数十万格）不再被永久调到慢档。
     *   日志里会打出各候选的中位值（`基准=2=…ms`），跑过就能核这个估。
     *
     * ⚠️ 只测 rec、不测 det：det 恒为固定形状、对线程数不敏感（实测 1.20→1.30 只随版本变），
     *    而 rec 是稳态每格 9 次的主开销，选它做基准才有意义。
     * ⚠️ 这里**不试新 EP**，只换线程数 ⇒ 没有 `createSession` 挂起/SIGSEGV 的风险。
     */
    fun benchmarkIntra(candidates: List<Int>, width: Int = 320, runs: Int = 15): Map<Int, Long> {
        if (candidates.isEmpty()) return emptyMap()
        val e = runCatching { OrtEnvironment.getEnvironment() }.getOrNull() ?: return emptyMap()
        val wi = width.coerceIn(1, OnnxPaddleOcrService.REC_W_MAX)
        val input = FloatBuffer.wrap(FloatArray(3 * REC_H * wi))
        val shape = longArrayOf(1, 3, REC_H.toLong(), wi.toLong())
        val out = LinkedHashMap<Int, Long>()
        for (n in candidates) {
            val ms = runCatching {
                val opts = buildOptions(tier, n)
                // 建会话失败时 opts 同样要关 —— 这里整段包在 runCatching 里，抛出去就是静默泄漏。
                val s = try {
                    e.createSession(recModel.absolutePath, opts)
                } finally {
                    runCatching { opts.close() }
                }
                try {
                    val name = s.inputInfo.keys.firstOrNull() ?: "x"
                    LongArray(runs + 1) {
                        val t0 = System.nanoTime()
                        OnnxTensor.createTensor(e, input, shape).use { t ->
                            s.run(Collections.singletonMap(name, t)).use { r -> (r.get(0) as? OnnxTensor)?.floatBuffer }
                        }
                        (System.nanoTime() - t0) / 1_000_000
                    }.sorted().drop(1) // 丢掉头一次（含首帧编译/分配）
                } finally {
                    runCatching { s.close() }
                }.let { if (it.isEmpty()) Long.MAX_VALUE else it[it.size / 2] }
            }.getOrElse {
                Log.w(TAG, "intra=$n 基准失败，跳过", it)
                Long.MAX_VALUE
            }
            out[n] = ms
        }
        return out
    }

    /**
     * det 推理：输入 float32[1,3,640,640]（NCHW，已归一化到 [-1,1]）→ 概率图 float32[1,1,640,640]。
     * 读锁护持 native run；未就绪返回空数组（调用方降级）。
     */
    fun runDet(input: FloatBuffer): FloatArray {
        val out = sessionLock.read {
            val s = detSession ?: return@read FloatArray(0)
            runSession(s, detInputName, input, longArrayOf(1, 3, DET_SIZE.toLong(), DET_SIZE.toLong()))
        }
        // 必须在读锁**外**降档：ReentrantReadWriteLock 不支持持读锁再取写锁（会死锁）
        maybeDegradeAfterFailure()
        return out
    }

    /**
     * rec 推理：输入 float32[1,3,48,width]（width 随文本宽高比动态）→ logits float32[1,T,C]。
     * ⚠️ 2026-09-12：曾尝试 batch N>1（各 ROI 右侧 pad 到同宽拼一个 batch）→ 实测**负优化**
     *   （武器 +7%、圣遗物 +17%：槽宽异质致计算量 `n·Wmax/Σw = 1.59×`，且每次需分配最大 3.5MB）
     *   ⇒ 已回退为逐行（N=1）。详见 OnnxPaddleOcrService.recognizeRois 的实测记录。
     */
    fun runRec(input: FloatBuffer, width: Int): FloatArray {
        val out = sessionLock.read {
            val s = recSession ?: return@read FloatArray(0)
            runSession(s, recInputName, input, longArrayOf(1, 3, REC_H.toLong(), width.toLong()))
        }
        maybeDegradeAfterFailure()
        return out
    }

    private fun runSession(
        session: OrtSession,
        inputName: String,
        input: FloatBuffer,
        shape: LongArray,
    ): FloatArray {
        val e = env ?: return FloatArray(0)
        val t0 = System.nanoTime()
        return try {
            OnnxTensor.createTensor(e, input, shape).use { tensor ->
                session.run(Collections.singletonMap(inputName, tensor)).use { result ->
                    val t = result.get(0) as? OnnxTensor ?: return FloatArray(0)
                    t.floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                }
            }.also {
                // 慢推理看门狗：ORT native run 不可中断，无法真超时，只能事后判定并计入失败
                // 以触发 EP 降档（XNNPACK 在部分 ROM 上会整体挂起，远超此阈值）。
                val ms = (System.nanoTime() - t0) / 1_000_000
                if (ms > SLOW_INFER_MS) {
                    Log.w(TAG, "slow inference ${ms}ms (tier=${tier.label}, $inputName) > ${SLOW_INFER_MS}ms")
                    consecutiveFailures.incrementAndGet()
                } else {
                    consecutiveFailures.set(0)
                }
            }
        } catch (e: Throwable) {
            // 不静默吞：NNAPI 在某些 ROM 上会中途崩，日志是唯一线索
            Log.e(TAG, "ORT run failed (tier=${tier.label}, input=$inputName, shape=${shape.toList()})", e)
            consecutiveFailures.incrementAndGet()
            FloatArray(0)
        }
    }

    /** 连续失败达阈值则降一档 EP（CPU 为兜底，不再降）。必须在读锁外调用。
     *  ⚠️ 无参重载只在**没有候选序**时用（JVM 单测路径）；真机走 [degradeTier] 的带序版本。 */
    private fun maybeDegradeAfterFailure() {
        if (consecutiveFailures.get() < FAILURES_BEFORE_DEGRADE) return
        consecutiveFailures.set(0)
        val next = degradeTier(tierOrder)
        Log.w(TAG, "连续推理失败 $FAILURES_BEFORE_DEGRADE 次，EP 降档 → ${next?.label ?: "已到 CPU 兜底"}")
    }

    /** 运行期降档重建；到兜底档后返回 null。候选序由调用方（[tierOrder]）给出。 */
    fun degradeTier(order: List<EpTierPicker.Tier>): EpTierPicker.Tier? {
        val next = EpTierPicker.degrade(tier, order) ?: return null
        return if (initialize(next, intraOpThreads)) next else null
    }

    /**
     * 首启基准：按档建会话 → det 预热 1 次（含首帧编译/内存分配）→ 计时 [runs] 次取中位（ms）。
     * 零张量即可测纯执行开销，不依赖图像内容。EP 不可用返回 null。
     */
    fun benchmarkTier(tier: EpTierPicker.Tier, runs: Int = 3): Long? {
        if (!initialize(tier)) return null
        val input = FloatBuffer.wrap(FloatArray(DET_SIZE * DET_SIZE * 3))
        runDet(input) // 预热
        val times = LongArray(runs) {
            val t0 = System.nanoTime()
            runDet(input)
            (System.nanoTime() - t0) / 1_000_000
        }
        return times.sorted()[times.size / 2]
    }

    /**
     * ★ 只读分段探针（`DEBUG_OCR_BENCH` 用）：把 [runSession] 的三步拆开分别计时 ——
     * 建输入张量 / native `session.run` / **把输出 FloatBuffer 物化成 Java float[]**。
     *
     * 它要回答的问题以及已经问出来的答案（2026-09-26 华为 EML-AL00 实测）：
     * ppocr-bench 在同一台机器、**完全同形状**（in=46080 / out=276240）下报 `ort/cpu/rec = 25.6ms`，
     * 而我们端到端 `runRec` 要 80~115ms。当时怀疑差在"我们多做了一份输出拷贝"——
     * **探针否证了这个猜测**：`copy` 只有 4~12ms，`create` 9~27ms，**时间全在 `run` 里**。
     * 随后"静态形状导出"（113ms）与"ORT 1.20→1.22"（80ms）两条也都被实测排除。
     * ⇒ 那 4.4× 至今未归因，完整排除链见 `dsl/verify/_audit/HW-PERF-20260926.md` §6。
     *
     * ⚠️ 这段是 [runSession] 的**镜像**而不是它的调用方：改了 runSession 记得同步这里；
     *    但**别把生产路径改成走这里**（这里没有失败看门狗、也不做 EP 降档）。
     */
    fun runSplitProbe(width: Int, runs: Int = 5): String {
        val e = env ?: return "env not ready"
        val s = recSession ?: return "rec session not ready"
        val wi = width.coerceIn(1, OnnxPaddleOcrService.REC_W_MAX)
        val input = java.nio.FloatBuffer.wrap(FloatArray(3 * REC_H * wi))
        val shape = longArrayOf(1, 3, REC_H.toLong(), wi.toLong())
        var createNs = 0L
        var runNs = 0L
        var copyNs = 0L
        var outElems = 0
        repeat(runs) {
            val t0 = System.nanoTime()
            OnnxTensor.createTensor(e, input, shape).use { tensor ->
                val t1 = System.nanoTime()
                s.run(Collections.singletonMap(recInputName, tensor)).use { result ->
                    val t2 = System.nanoTime()
                    val t = result.get(0) as? OnnxTensor
                    if (t != null) {
                        val b = t.floatBuffer
                        val n = b.remaining()
                        FloatArray(n).also { a -> b.get(a) }
                        outElems = n
                        copyNs += System.nanoTime() - t2
                    }
                    runNs += t2 - t1
                }
                createNs += t1 - t0
            }
        }
        return "rec w=$wi runs=$runs out=$outElems(${"%.2f".format(outElems * 4 / 1048576.0)}MB) " +
            "create=${createNs / runs / 1_000_000}ms run=${runNs / runs / 1_000_000}ms copy=${copyNs / runs / 1_000_000}ms"
    }

    /** 写锁释放：等待所有在途 run 返回后才 close；close 后 run 返回空（不再抛异常）。 */
    override fun close() = sessionLock.write {
        runCatching { detSession?.close() }
        runCatching { recSession?.close() }
        detSession = null
        recSession = null
        ready = false
    }

    companion object {
        const val DET_SIZE = 640
        const val REC_H = 48
        /**
         * 兜底 intra-op 线程数。**不是"实测最优值"** —— 最优值由 [benchmarkIntra] 首启实测决定，
         * 只有基准整体失败时才落到这里。2 这个数在 Kirin 970 上从 1.20 一直成立到 1.30
         * （五轮复测里四轮 intra=4 更慢，见 [EpTierPicker.intraOpCandidates] 的表），
         * 所以它同时也是"基准不可信时最不该被换掉"的那个值。
         */
        const val DEFAULT_INTRA_OP_THREADS = 2
        private const val FAILURES_BEFORE_DEGRADE = 3
        private const val TAG = "BetterGI.Ort"

        private val NN_API_FLAGS = EnumSet.of(
            NNAPIFlags.USE_FP16,
            NNAPIFlags.USE_NCHW,
        )

        /** 慢推理阈值（ms）：超此值计一次失败，累计触发 EP 降档。det 真机 CPU 档约 1s，留 3x 余量。 */
        private const val SLOW_INFER_MS = 3000L
    }
}
