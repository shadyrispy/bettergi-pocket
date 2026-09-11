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

    /** 会话是否已就绪（未就绪时所有 run 返回空，调用方应降级到 ML Kit） */
    @Volatile
    var ready: Boolean = false
        private set

    /** 初始化（装载模型 + 按目标档创建双会话）。失败返回 false 并保持未就绪。 */
    fun initialize(targetTier: EpTierPicker.Tier): Boolean {
        val result = runCatching {
            close()
            val e = OrtEnvironment.getEnvironment()
            val detOpts = buildOptions(targetTier)
            val recOpts = buildOptions(targetTier)
            val d = e.createSession(detModel.absolutePath, detOpts)
            val r = e.createSession(recModel.absolutePath, recOpts)
            detOpts.close()
            recOpts.close()
            env = e
            detSession = d
            recSession = r
            detInputName = d.inputInfo.keys.firstOrNull() ?: "x"
            recInputName = r.inputInfo.keys.firstOrNull() ?: "x"
            tier = targetTier
            ready = true
        }
        if (result.isFailure) {
            ready = false
        }
        return result.isSuccess
    }

    private fun buildOptions(tier: EpTierPicker.Tier): OrtSession.SessionOptions {
        val opts = OrtSession.SessionOptions()
        // 移动端大核有限，intra=2 实测优于默认（全核调度开销 > 并行收益）
        opts.setIntraOpNumThreads(INTRA_OP_THREADS)
        when (tier) {
            EpTierPicker.Tier.NNAPI -> opts.addNnapi(NN_API_FLAGS)
            EpTierPicker.Tier.XNNPACK -> opts.addXnnpack(emptyMap())
            EpTierPicker.Tier.CPU -> { /* 默认 CPU EP，无需追加 */ }
        }
        return opts
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
     * irminsul 现状为逐行推理（N=1），批量 N>1 优化留待精度/速度实测后再上。
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

    /** 连续失败达阈值则降一档 EP（CPU 为兜底，不再降）。必须在读锁外调用。 */
    private fun maybeDegradeAfterFailure() {
        if (consecutiveFailures.get() < FAILURES_BEFORE_DEGRADE) return
        consecutiveFailures.set(0)
        val next = degradeTier()
        Log.w(TAG, "连续推理失败 $FAILURES_BEFORE_DEGRADE 次，EP 降档 → ${next?.label ?: "已到 CPU 兜底"}")
    }

    /** 运行期降档重建；CPU 档不再降，返回 null。 */
    fun degradeTier(): EpTierPicker.Tier? {
        val next = EpTierPicker.degrade(tier) ?: return null
        return if (initialize(next)) next else null
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
        private const val INTRA_OP_THREADS = 2
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
