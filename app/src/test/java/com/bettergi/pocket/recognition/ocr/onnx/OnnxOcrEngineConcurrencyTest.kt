package com.bettergi.pocket.recognition.ocr.onnx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A6/A8（optimization-plan-20260930 轨 E）并发与语义回归：
 *
 * - A6：`OnnxOcrEngine.initialize` 旧实现「写锁内 close → 锁外 createSession → 逐字段赋回」
 *   存在 initialize↔initialize 竞态（后到者 close 掉先到者刚建好未赋值的 session ⇒ native
 *   泄漏 / 半新半旧字段组合），降档闸门 check-then-act 又放大了它。修复后为
 *   「initLock 串行 + build-then-swap（写锁内原子换入 + 关旧）」。
 *   本文件用真模型（assets/onnx 的 tiny 模型，JVM onnxruntime-jvm 跑真推理）压并发。
 * - A8：慢/坏分离 —— 成功但超阈值的慢推理**只记指标**不进降档窗；成功对失败窗做
 *   **有界递减 1**（而非 set(0) 整窗清零）；降档闸门 CAS 抢占。
 *
 * 失败注入方式（JVM 上确定性触发「真失败」）：给 [OnnxOcrEngine.runRec] 传一个与 shape
 * 不匹配的输入 buffer ⇒ `OnnxTensor.createTensor` 抛异常 ⇒ runSession catch 计入失败窗。
 *
 * ⚠️ 无法在 JVM 上覆盖的部分（写明而非假装测了）：
 * - 「有 incumbent 时重建失败 ⇒ incumbent 存活」：同一引擎的模型路径固定，JVM 上
 *   createSession 对同一文件不会先成功后失败，且 OnnxOcrEngine 是 final 具体类、
 *   测试依赖里没有 mock 库 ⇒ 无法让 initialize 只失败一次。该路径由 A6 的代码序保证
 *   （build 段失败不触碰任何字段），真机验证靠降档日志。
 * - 慢推理真实超过 3s 的场景：通过 internal 阈值旋钮 slowInferMs 注入，不真等 3s。
 */
class OnnxOcrEngineConcurrencyTest {

    // ---- 资产定位（与 OnnxOcrBenchmarkTest 同一套向上查找约定）----
    private fun onnxAssetsOrNull(): File? {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(5) {
            val candidate = File(dir, "src/main/assets/onnx")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        return null
    }

    private fun assumeAssets(): File {
        val dir = onnxAssetsOrNull()
        Assume.assumeTrue("src/main/assets/onnx 模型缺失，跳过（A6 压测依赖真实模型）", dir != null)
        return dir!!
    }

    private fun newEngine(dir: File): OnnxOcrEngine =
        OnnxOcrEngine(File(dir, "det.onnx"), File(dir, "rec.onnx"))

    /** 合法 rec 输入（零张量即可，形状对齐 w=320）。 */
    private fun recInput(w: Int = 320) = java.nio.FloatBuffer.wrap(FloatArray(3 * OnnxOcrEngine.REC_H * w))

    /**
     * A6 失败注入：buffer 只有 1 个 float，shape 却要 [1,3,48,320] ⇒ createTensor 抛异常
     * ⇒ runSession 捕获计入降档失败窗（真失败路径）。
     */
    private fun injectRealFailure(engine: OnnxOcrEngine) {
        val out = engine.runRec(java.nio.FloatBuffer.wrap(FloatArray(1)), 320)
        assertEquals("注入失败时 runRec 应返回空数组（防御语义不变）", 0, out.size)
    }

    // ================= A6 =================

    @Test
    fun `A6 并发 initialize 与 run 压测_不崩且终态一致`() {
        val dir = assumeAssets()
        val engine = newEngine(dir)
        try {
            val errors = ConcurrentLinkedQueue<Throwable>()
            val stop = AtomicBoolean(false)
            val initRounds = AtomicInteger(0)
            val initDone = CountDownLatch(3)

            // 3 个并发 initialize 线程（不同 intra 参数交错），模拟降档/择优重开的争抢
            val inits = (0 until 3).map { i ->
                Thread {
                    try {
                        repeat(4) {
                            val ok = engine.initialize(EpTierPicker.Tier.CPU, 1 + (i % 2))
                            if (ok) initRounds.incrementAndGet()
                            Thread.sleep(5)
                        }
                    } catch (t: Throwable) {
                        errors.add(t)
                    } finally {
                        initDone.countDown()
                    }
                }
            }

            // 4 个 run 线程与 initialize 全程交叠：要么拿到合法 logits，要么（未就绪窗口）空数组，
            // 绝不允许崩、也不允许输出长度不是类数整数倍的"半新半旧"脏结果
            val runners = (0 until 4).map {
                Thread {
                    try {
                        val input = recInput()
                        while (!stop.get()) {
                            val out = engine.runRec(input, 320)
                            if (out.isNotEmpty()) {
                                assertTrue(
                                    "logits 长度必须是类数整数倍，实际 ${out.size}",
                                    out.size % OnnxPaddleOcrService.MODEL_CLASS_COUNT == 0,
                                )
                            } else {
                                Thread.sleep(1) // 未就绪窗口：让出 CPU，避免热旋
                            }
                        }
                    } catch (t: Throwable) {
                        errors.add(t)
                    }
                }
            }

            runners.forEach { it.start() }
            inits.forEach { it.start() }
            assertTrue("initialize 线程超时未结束", initDone.await(120, TimeUnit.SECONDS))
            stop.set(true)
            runners.forEach { it.join(60_000) }
            runners.forEach { assertFalse("run 线程卡死", it.isAlive) }

            assertTrue("并发期间抛出异常: $errors", errors.isEmpty())
            assertTrue("压测后应至少完成一次成功 initialize", initRounds.get() > 0)
            assertTrue("压测后引擎应就绪", engine.ready)
            assertEquals(EpTierPicker.Tier.CPU, engine.tier)

            // 终态一致性：全部换入完成后一次真实 run 必须成功
            val final = engine.runRec(recInput(), 320)
            assertTrue(
                "终态 runRec 应产出合法 logits（size=${final.size}）",
                final.isNotEmpty() && final.size % OnnxPaddleOcrService.MODEL_CLASS_COUNT == 0,
            )
        } finally {
            engine.close()
        }
    }

    @Test
    fun `A6 降档闸门 CAS_达阈值清零一次且到 CPU 兜底不再重建`() {
        val dir = assumeAssets()
        val engine = newEngine(dir)
        try {
            assertTrue(engine.initialize(EpTierPicker.Tier.CPU))
            engine.tierOrder = listOf(EpTierPicker.Tier.CPU) // 默认序：CPU 为兜底，降档是 no-op

            repeat(3) { injectRealFailure(engine) }
            // 第 3 次失败后 maybeDegradeAfterFailure 已在 runRec 尾部跑过：
            // CAS 赢家清零、degradeTier 到兜底返回 null（不重建）
            assertEquals("达阈值后失败窗应被 CAS 清零", 0, engine.consecutiveFailureCount)
            assertTrue("兜底降档是 no-op，不得破坏 incumbent 会话", engine.ready)
            val out = engine.runRec(recInput(), 320)
            assertTrue("降档 no-op 后 run 仍应正常", out.isNotEmpty())

            // 兜底档再失败不再触发重建（degrade 返回 null），失败窗按新语义重新累积
            injectRealFailure(engine)
            assertEquals(1, engine.consecutiveFailureCount)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `A6 初始化失败保持未就绪且 close 幂等`() {
        // 坏路径引擎：createSession 必败，覆盖 build 段失败分支（首启即失败 ⇒ ready 维持 false）
        val bad = OnnxOcrEngine(File("/nonexistent/bettergi-pocket/det.onnx"), File("/nonexistent/bettergi-pocket/rec.onnx"))
        try {
            assertFalse(bad.initialize(EpTierPicker.Tier.CPU))
            assertFalse(bad.ready)
            assertEquals(0, bad.runRec(recInput(), 320).size)
            assertEquals(0, bad.runDet(java.nio.FloatBuffer.wrap(FloatArray(OnnxOcrEngine.DET_SIZE * OnnxOcrEngine.DET_SIZE * 3))).size)
        } finally {
            bad.close() // 未初始化过也必须安全
            bad.close() // 幂等
        }
    }

    // ================= A8 =================

    @Test
    fun `A8 慢推理只记指标_不进降档失败窗`() {
        val dir = assumeAssets()
        val engine = newEngine(dir)
        try {
            assertTrue(engine.initialize(EpTierPicker.Tier.CPU))
            engine.slowInferMs = -1L // 单测旋钮：一切成功推理都判为"慢"，不必真等 3s

            val out = engine.runRec(recInput(), 320)
            assertTrue("成功推理应产出合法 logits", out.isNotEmpty() && out.size % OnnxPaddleOcrService.MODEL_CLASS_COUNT == 0)

            assertEquals(
                "成功但慢不得计入降档失败窗（旧实现连续 3 次慢 ⇒ 不可逆降档）",
                0,
                engine.consecutiveFailureCount,
            )
            assertEquals("慢推理指标应被记录", 1, engine.slowInferCountTotal)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `A8 成功只递减一次失败窗_而非整窗清零`() {
        val dir = assumeAssets()
        val engine = newEngine(dir)
        try {
            assertTrue(engine.initialize(EpTierPicker.Tier.CPU))

            injectRealFailure(engine)
            injectRealFailure(engine)
            assertEquals(2, engine.consecutiveFailureCount)

            val out = engine.runRec(recInput(), 320)
            assertTrue(out.isNotEmpty())
            assertEquals(
                "一次成功只能有界递减 1（旧实现 set(0) 会抹掉并发线程正在累积的失败窗）",
                1,
                engine.consecutiveFailureCount,
            )
        } finally {
            engine.close()
        }
    }
}
