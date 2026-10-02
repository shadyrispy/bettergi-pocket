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

    /**
     * init 互斥（A6，2026-09-30）：独立于 [sessionLock]，串行化所有 [initialize] 调用
     * （含秒级 createSession 段）。**锁次序恒为 initLock → sessionLock.write**；
     * run/close 路径只碰 sessionLock，绝不反向去拿 initLock —— 否则死锁。
     */
    private val initLock = Any()

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

    /**
     * 连续推理失败窗（达阈值触发 EP 降档）。
     *
     * ★ 2026-09-30（A8，optimization-plan-20260930 轨 E）语义变更：
     * - **只有真失败**（ORT 抛异常 / 输出无效）才递增；慢推理（成功但超阈值）只记
     *   [slowInferCount] 指标，不进本窗 —— 温控降频/游戏抢 CPU 造成的偶发慢推理
     *   不再误触发**不可逆** EP 降档。
     * - 成功不再 `set(0)` 整窗清零，改**有界递减 1**（[boundedSuccessReset]）：
     *   并发下一个线程的一次快成功，不得抹掉另一线程正在累积的真失败窗。
     * - 换档/重建成功（[initialize] 换入新会话）时整窗清零：失败窗语义按档位隔离（per-tier）。
     */
    private val consecutiveFailures = AtomicInteger(0)

    /**
     * 慢推理累计计数（成功但耗时超 [slowInferMs]）—— **只记指标**（诊断/日志），不参与降档（A8）。
     */
    private val slowInferCount = AtomicInteger(0)

    /**
     * 慢推理阈值（ms）。det 真机 CPU 档约 1s，留 3x 余量。
     * internal 可变仅为 JVM 单测能确定性触发慢路径（生产代码勿改）。
     */
    @Volatile
    internal var slowInferMs: Long = SLOW_INFER_MS

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

    /**
     * 会话是否已就绪。
     * ★ 2026-09-30（A7）：旧注释「未就绪时所有 run 返回空，调用方应降级到 ML Kit」已废弃 ——
     * ML Kit 兜底从未实现、方案裁决**不做**静默兜底。scan 层网关（OcrGatewayImpl）在调用前
     * 查询本字段，未就绪时抛 OcrUnavailableException（loud），不再把空结果冒充数据。
     * run 层在未就绪时返回空数组仅作防御（探针/直接调用者）。
     */
    @Volatile
    var ready: Boolean = false
        private set

    /** 诊断/单测观测：当前降档失败窗计数（A8 有界重置语义，见 [consecutiveFailures]）。 */
    internal val consecutiveFailureCount: Int get() = consecutiveFailures.get()

    /** 诊断/单测观测：慢推理（成功但超阈值）累计次数 —— 只记指标，不参与降档（A8）。 */
    internal val slowInferCountTotal: Int get() = slowInferCount.get()

    /** rec 模型文件路径（仅并行度探针 [OcrParallelProbe] 用来另建 N 个会话）。 */
    val recModelPath: String get() = recModel.absolutePath

    /**
     * 初始化（装载模型 + 按目标档创建双会话）。失败返回 false。
     *
     * ★ 2026-09-30（A6，optimization-plan-20260930 轨 E）：整体改为 **build-then-swap**。
     * 旧实现是「写锁内 close → **锁外** createSession → 逐字段赋回」，有两个并发窗口：
     * ① 两个 initialize 交错时，后到者的 close() 会关掉先到者刚建好、还没赋值的 session
     *    （native 泄漏；半新半旧的 det/rec 字段组合）——这正是本类 KDoc 自称规避的
     *    irminsul B5 SIGSEGV 同族 use-after-free；
     * ② 赋回在锁外逐字段进行，run 线程能看到 det 新/rec 旧的不一致快照。
     *
     * 现在的次序（锁结构）：
     * ```
     * initLock（串行化整个 initialize）:
     *   1. 裸段（不持 sessionLock）：createSession 双会话 —— 秒级耗时**不阻塞 run**；
     *      失败时自收自的 options/半途 session，旧会话原封不动。
     *   2. 成功 → sessionLock.write（微秒级）：原子换入全部字段 + 在写锁内 close 旧会话
     *      （写锁保证没有在途 native run 还握着旧 session —— close 等 run 返回后才执行）。
     *   3. 失败 → 不动任何字段：已有 incumbent 会话**继续服务**（降档失败的可用性兜底）；
     *      首启即失败时本来就没有会话，ready 维持 false —— 与旧语义「失败保持未就绪」一致。
     * ```
     * run/close 仍走原 [sessionLock] 读写锁，语义不变（写锁等待在途 run 退出后才放 session）。
     */
    fun initialize(targetTier: EpTierPicker.Tier, intraThreads: Int = DEFAULT_INTRA_OP_THREADS): Boolean {
        synchronized(initLock) {
            val built = runCatching {
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
                BuiltSessions(
                    env = e,
                    det = d,
                    rec = r,
                    detName = d.inputInfo.keys.firstOrNull() ?: "x",
                    recName = r.inputInfo.keys.firstOrNull() ?: "x",
                )
            }
            return built.fold(
                onSuccess = { s ->
                    sessionLock.write {
                        val oldDet = detSession
                        val oldRec = recSession
                        env = s.env
                        detSession = s.det
                        recSession = s.rec
                        detInputName = s.detName
                        recInputName = s.recName
                        tier = targetTier
                        intraOpThreads = intraThreads
                        ready = true
                        // A8：换档即换失败窗语境 —— 旧档积累的失败对新档无意义（per-tier 清零）
                        consecutiveFailures.set(0)
                        // 关旧必须在写锁内：等待所有在途 run 返回后才 close（防 use-after-free）
                        runCatching { oldDet?.close() }
                        runCatching { oldRec?.close() }
                    }
                    true
                },
                onFailure = { t ->
                    // 不静默吞：降档失败时调用方（degradeTier）只拿到 false，这里是唯一线索
                    Log.e(TAG, "initialize(tier=${targetTier.label}, intra=$intraThreads) 失败；保留原会话", t)
                    false
                },
            )
        }
    }

    /** [initialize] 的 build 段产物：全部字段在写锁内一次性原子换入，避免半新半旧快照。 */
    private data class BuiltSessions(
        val env: OrtEnvironment,
        val det: OrtSession,
        val rec: OrtSession,
        val detName: String,
        val recName: String,
    )

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
                    medianAfterDroppingWarmup(LongArray(runs + 1) {
                        val t0 = System.nanoTime()
                        OnnxTensor.createTensor(e, input, shape).use { t ->
                            s.run(Collections.singletonMap(name, t)).use { r -> (r.get(0) as? OnnxTensor)?.floatBuffer }
                        }
                        (System.nanoTime() - t0) / 1_000_000
                    })
                } finally {
                    runCatching { s.close() }
                }
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
    fun runDet(input: FloatBuffer): FloatArray =
        runDetInto(input) { b, n -> FloatArray(n).also { b.get(it) } } ?: FloatArray(0)

    /**
     * GC P0（工单 B）：det 推理的**免物化**版本。ORT 输出的 FloatBuffer 在张量 close 后失效，
     * 因此 [consume] 在 close 前的 use 块内执行（顺序读 argmax/阈值遍历无需物化 float[]，
     * det 输出 ~1.6MB/次）。返回 [consume] 的结果；推理失败/未就绪返回 null。
     * 慢推理看门狗、失败窗计数语义与 [runDet] 完全一致。
     */
    fun <T> runDetInto(input: FloatBuffer, consume: (FloatBuffer, Int) -> T): T? {
        val out = sessionLock.read {
            val s = detSession ?: return@read null
            runSessionWith(s, detInputName, input, longArrayOf(1, 3, DET_SIZE.toLong(), DET_SIZE.toLong()), consume)
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
    fun runRec(input: FloatBuffer, width: Int): FloatArray =
        runRecInto(input, width) { b, n -> FloatArray(n).also { b.get(it) } } ?: FloatArray(0)

    /**
     * GC P0（工单 B）：rec 推理的**免物化**版本。每次 run 后 `FloatArray(b.remaining())`
     * 物化 logits 每格 9 槽 ≈10MB + det ~1.6MB，是 OCR 链路第二大 GC 源；CTC 解码对
     * logits 是纯顺序读（逐时间步 argmax），直接消费 FloatBuffer 可完全免物化。
     * [consume] 在张量 close 前执行（buffer 随 close 失效）；旧 [runRec] 保留委托语义。
     */
    fun <T> runRecInto(input: FloatBuffer, width: Int, consume: (FloatBuffer, Int) -> T): T? {
        val out = sessionLock.read {
            val s = recSession ?: return@read null
            runSessionWith(s, recInputName, input, longArrayOf(1, 3, REC_H.toLong(), width.toLong()), consume)
        }
        maybeDegradeAfterFailure()
        return out
    }

    /**
     * [runSession] 的免物化版：输出 FloatBuffer 在张量 close 前交给 [consume]
     * （契约：`consume(buffer, buffer.remaining())`，buffer 的 [position, limit) 即本次输出，
     * **必须**在 use 块内读完——close 后 native 内存失效）。失败返回 null。
     * 慢推理看门狗与失败窗语义与物化路径逐位一致。
     */
    private fun <T> runSessionWith(
        session: OrtSession,
        inputName: String,
        input: FloatBuffer,
        shape: LongArray,
        consume: (FloatBuffer, Int) -> T,
    ): T? {
        val e = env ?: return null
        val t0 = System.nanoTime()
        return try {
            OnnxTensor.createTensor(e, input, shape).use { tensor ->
                session.run(Collections.singletonMap(inputName, tensor)).use { result ->
                    val t = result.get(0) as? OnnxTensor
                        // ★ 2026-09-30（A8）：输出不是张量 = **无效输出**，属真失败 ⇒ 计入降档窗。
                        //   旧实现此处静默 return 空（不计失败），无效输出永不触发降档。
                        ?: throw IllegalStateException("ORT 输出不是 OnnxTensor（无效输出）")
                    val b = t.floatBuffer
                    consume(b, b.remaining())
                }
            }.also {
                // 慢推理看门狗（A8 后语义）：ORT native run 不可中断，无法真超时，只能事后判定。
                // ★ 2026-09-30（A8，optimization-plan-20260930 轨 E）慢/坏分离：**成功但慢只记指标**
                //   （slowInferCount + 日志），不再计入降档失败窗 —— 温控降频/游戏抢 CPU 时
                //   连续 3 次慢推理曾把 EP 不可逆地降到慢档。EP 整体挂起（如 XNNPACK 类病理）
                //   应由真失败路径（异常）或人工读 slowInferCount 指标处置。
                val ms = (System.nanoTime() - t0) / 1_000_000
                if (ms > slowInferMs) {
                    slowInferCount.incrementAndGet()
                    Log.w(TAG, "slow inference ${ms}ms (tier=${tier.label}, $inputName) > ${slowInferMs}ms —— 只记指标，不计入降档失败窗")
                } else {
                    // 有界重置：一个成功只抵消一次既有失败，不是整窗清零 ——
                    // 并发下一次快的成功不得抹掉另一线程正在累积的真失败计数。
                    boundedSuccessReset()
                }
            }
        } catch (e: Throwable) {
            // 不静默吞：NNAPI 在某些 ROM 上会中途崩，日志是唯一线索
            Log.e(TAG, "ORT run failed (tier=${tier.label}, input=$inputName, shape=${shape.toList()})", e)
            // 真失败（异常/无效输出）→ 唯一进入降档失败窗的入口（A8）
            consecutiveFailures.incrementAndGet()
            null
        }
    }

    /** 成功一次 ⇒ 失败窗有界递减 1（不为负）。CAS 循环：与失败递增/降档清零并发时重读后再定。 */
    private fun boundedSuccessReset() {
        while (true) {
            val cur = consecutiveFailures.get()
            if (cur <= 0) return
            if (consecutiveFailures.compareAndSet(cur, cur - 1)) return
        }
    }

    /**
     * 连续真失败达阈值则降一档 EP（CPU 为兜底，不再降）。必须在读锁外调用。
     *
     * ★ 2026-09-30（A6）：check-then-act（`get() >= 3` → `set(0)`）改 **CAS 抢占**。
     * 旧实现两个线程可同时过闸、并发进 [degradeTier] → 后到者的 initialize 会 close 掉
     * 先到者刚建好、还没写回字段的 session（native 泄漏 / use-after-free，irminsul B5 同族）。
     * 现在同一批失败（≥[FAILURES_BEFORE_DEGRADE]）只有 CAS 赢家执行一次降档；
     * 输家 CAS 失败后重读计数，按新值重新裁决（计数又被推高到阈值则算新一批）。
     * ⚠️ 降档内部走 [initialize]（initLock 串行 + build-then-swap），即使真出现并发重建
     *    也不再互相拆台 —— 这里 CAS 是防重复降档（省秒级重建墙钟），不是唯一防线。
     * ⚠️ 无参重载只在**没有候选序**时用（JVM 单测路径）；真机走 [degradeTier] 的带序版本。
     */
    private fun maybeDegradeAfterFailure() {
        while (true) {
            val cur = consecutiveFailures.get()
            if (cur < FAILURES_BEFORE_DEGRADE) return
            if (!consecutiveFailures.compareAndSet(cur, 0)) continue
            val next = degradeTier(tierOrder)
            Log.w(TAG, "连续推理失败 ≥$FAILURES_BEFORE_DEGRADE 次，EP 降档 → ${next?.label ?: "已到 CPU 兜底"}")
            return
        }
    }

    /**
     * 运行期降档重建；到兜底档后返回 null。候选序由调用方（[tierOrder]）给出。
     * ★ 2026-09-30（A6）：重建失败（initialize 返回 false）时 incumbent 会话**继续服务**
     * （build-then-swap），调用方拿 null 仅表示"降档没成"，OCR 本身不中断。
     */
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

        /**
         * 基准统计（纯函数，JVM 可单测）：丢掉**首帧预热样本**后取剩余的中位数。
         *
         * A20 修复说明：旧写法 `sorted().drop(1)` 是先排序再丢最小值 —— 丢掉的是**最快**样本
         * 而不是"头一次"（首帧编译/分配的慢样本反被保留），中位数因此被系统性拉低/抬高。
         * 正确顺序是先 `drop(1)` 丢首帧，再排序取中位。
         * 空样本（runs<=0）返回 [Long.MAX_VALUE]（与"基准失败"同口径）。
         */
        internal fun medianAfterDroppingWarmup(samples: LongArray): Long {
            val rest = samples.drop(1).sorted()
            return if (rest.isEmpty()) Long.MAX_VALUE else rest[rest.size / 2]
        }

        private const val FAILURES_BEFORE_DEGRADE = 3
        private const val TAG = "BetterGI.Ort"

        private val NN_API_FLAGS = EnumSet.of(
            NNAPIFlags.USE_FP16,
            NNAPIFlags.USE_NCHW,
        )

        /**
         * 慢推理阈值默认值（ms）：det 真机 CPU 档约 1s，留 3x 余量。
         * ★ 2026-09-30（A8）：超阈值**不再计为失败**，只进 slowInferCount 指标；
         * 可变入口见 [slowInferMs]（internal，单测用）。
         */
        private const val SLOW_INFER_MS = 3000L
    }
}
