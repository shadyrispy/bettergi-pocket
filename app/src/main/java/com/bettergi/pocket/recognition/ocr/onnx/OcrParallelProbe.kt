package com.bettergi.pocket.recognition.ocr.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * OCR 并行度探针（2026-09-12，**只读**，仅 adb `DEBUG_PERF_PROBE --es ocrpar "…"` 触发）。
 *
 * ## 回答什么问题
 * GOODScanner 用 `OcrPool`（**建 N 个模型实例**）+ rayon 让 N 个 OCR 真并行
 * （`ocr_pool.rs`：单实例内是 `Mutex<Session>` 串行 ⇒ 只能靠多实例并行）。
 * 我们是否值得照搬，取决于**瓶颈到底在哪一侧**：
 *
 * - 若**单次调用**就是算力受限（intra 1→2 近翻倍、2→4 仍涨）⇒ 先调 `INTRA_OP_THREADS` 即可，
 *   根本不必上多会话（多会话反而与 intra 抢核 ⇒ 过订阅）。
 * - 若 intra 已饱和（2 ≈ 4）而 **9 槽总耗时 ≈ 各槽单次之和** ⇒ 瓶颈是"调用串行、核在闲着"
 *   ⇒ **多会话并行（k>1, intra=1）才有收益** ⇒ 才值得做 worker 池。
 *
 * ## 口径
 * 每次测量都把 [widths] 里**全部槽**跑一遍（真实槽宽），按 k 个线程分片、每线程独立会话；
 * `k=1, intra=2` 即**当前生产配置**，可直接作基线。合成零张量输入（与 `recProbe` 同款），
 * 只测执行开销、不依赖图像内容。取 [reps] 轮的中位数。
 */
object OcrParallelProbe {

    /** 解析 `"1:1,1:2,2:1,3:1"` → [(k,intra)…]；非法片段忽略，各值夹到 1..8。 */
    fun parseSpec(spec: String): List<Pair<Int, Int>> =
        spec.split(',').mapNotNull { seg ->
            val i = seg.indexOf(':')
            if (i <= 0) {
                null
            } else {
                val k = seg.substring(0, i).trim().toIntOrNull()
                val t = seg.substring(i + 1).trim().toIntOrNull()
                if (k == null || t == null || k < 1 || t < 1) null else k.coerceAtMost(8) to t.coerceAtMost(8)
            }
        }

    fun run(recModelPath: String, spec: String, widths: IntArray, reps: Int): String {
        val pairs = parseSpec(spec)
        if (pairs.isEmpty()) return "ocr parallel probe: empty spec（示例 \"1:1,1:2,2:1,3:1\"）"
        val w = widths.map { it.coerceIn(1, 4096) }.ifEmpty { listOf(145, 220, 405, 684) }
        val rounds = reps.coerceIn(1, 20)
        val env = OrtEnvironment.getEnvironment()
        val sb = StringBuilder(
            "ocr parallel probe: cpus=${Runtime.getRuntime().availableProcessors()} " +
                "slots=${w.size} widths=$w reps=$rounds",
        )
        for ((k, intra) in pairs) {
            val opts = OrtSession.SessionOptions()
            val sessions = ArrayList<OrtSession>(k)
            val pool = Executors.newFixedThreadPool(k)
            try {
                opts.setIntraOpNumThreads(intra)
                repeat(k) { sessions.add(env.createSession(recModelPath, opts)) }
                val names = sessions.map { it.inputInfo.keys.firstOrNull() ?: "x" }
                // 合成输入：零张量、真实形状（[1,3,48,w]）
                val bufs = w.map { wi -> FloatBuffer.wrap(FloatArray(3 * OnnxOcrEngine.REC_H * wi)) }

                fun round() {
                    val latch = CountDownLatch(k)
                    for (j in 0 until k) {
                        pool.execute {
                            try {
                                var idx = j
                                while (idx < bufs.size) {
                                    val wi = w[idx]
                                    val shape = longArrayOf(1, 3, OnnxOcrEngine.REC_H.toLong(), wi.toLong())
                                    OnnxTensor.createTensor(env, bufs[idx], shape).use { t ->
                                        sessions[j].run(Collections.singletonMap(names[j], t)).use { /* 释放结果 */ }
                                    }
                                    idx += k
                                }
                            } catch (_: Throwable) {
                                // 探针不抛：单点失败只影响该档的读数
                            } finally {
                                latch.countDown()
                            }
                        }
                    }
                    latch.await()
                }

                round() // 预热
                val times = LongArray(rounds) {
                    val t0 = System.nanoTime()
                    round()
                    (System.nanoTime() - t0) / 1_000_000
                }
                val med = times.sorted()[times.size / 2]
                val per = med.toDouble() / w.size
                sb.append("\n  k=$k intra=$intra round=${med}ms perSlot=${"%.2f".format(per)}ms" +
                    (if (k == 1 && intra == 2) "  ← 当前生产配置" else ""))
            } catch (e: Throwable) {
                sb.append("\n  k=$k intra=$intra FAILED: ${e::class.java.simpleName} ${e.message}")
            } finally {
                pool.shutdownNow()
                sessions.forEach { runCatching { it.close() } }
                runCatching { opts.close() }
            }
        }
        sb.append("\n判读：若 intra 1→2→4 单调变小 ⇒ 单调用即算力受限（先调 intra）；")
            .append("\n      若 intra 2≈4 且 k>1 明显更快 ⇒ 是\"调用串行\"受限（才值得做多会话并行池）。")
        return sb.toString()
    }
}
