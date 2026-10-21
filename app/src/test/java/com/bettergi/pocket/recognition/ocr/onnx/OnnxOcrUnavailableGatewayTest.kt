package com.bettergi.pocket.recognition.ocr.onnx

import com.bettergi.pocket.recognition.ocr.UnavailableOcrService
import com.bettergi.pocket.scan.FrameRect
import com.bettergi.pocket.scan.OcrGatewayImpl
import com.bettergi.pocket.scan.OcrUnavailableException
import kotlinx.coroutines.runBlocking
import nu.pattern.OpenCV
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import java.io.File

/**
 * A7/A19（optimization-plan-20260930 轨 E）契约回归：
 *
 * - A7：引擎未就绪（降档/重建窗口、首启预热）或从未初始化时，[OcrGatewayImpl] 必须
 *   抛 [OcrUnavailableException]，**不得**返回空串/EMPTY 冒充「图上没字」写进扫描数据
 *   （旧实现声称的"降级到 ML Kit"从未实现，方案裁决不做静默兜底）。
 *   放在 onnx 测试包：被测的可用性信号就是 OnnxPaddleOcrService.ready；gateway 公有 API
 *   从任意包可达，不需要（也不允许）为测而改 scan 层文件。
 * - A19：全部 EP 档建会话失败 ⇒ prepare 返回 false（旧实现 ready=false 却返回 true）。
 *
 * 引擎桩形态：OnnxOcrEngine 构造不建会话（只有 initialize 才碰原生），
 * 传坏路径文件即得一个**零原生成本**的 ready=false 真实引擎 —— 无需 mock（OnnxOcrEngine
 * 是 final 具体类，测试依赖里也没有 mock 库）。
 */
class OnnxOcrUnavailableGatewayTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCvNative() {
            nu.pattern.OpenCV.loadLocally()
            Mat(2, 2, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0)).release()
        }
    }

    private fun onnxAssetsOrNull(): File? {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(5) {
            val candidate = File(dir, "src/main/assets/onnx")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        return null
    }

    /** ready=false 的真实 OnnxPaddleOcrService（不碰原生：引擎从未 initialize）。 */
    private fun notReadyService(): OnnxPaddleOcrService {
        val engine = OnnxOcrEngine(
            File("/nonexistent/bettergi-pocket/det.onnx"),
            File("/nonexistent/bettergi-pocket/rec.onnx"),
        )
        // A24：字典条目数不符已是硬失败（init 抛 IllegalStateException），
        // 本测试只关心 ready=false 语义 ⇒ 用足量假条目绕开防呆。
        return OnnxPaddleOcrService(engine, List(OnnxPaddleOcrService.EXPECTED_DICT_SIZE) { "桩" })
    }

    private fun blankFrame(): Mat = Mat(4, 4, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0))

    // ================= A7 =================

    @Test
    fun `A7 引擎未就绪时 readRois 抛 OcrUnavailableException 而不是返回空`() {
        val gw = OcrGatewayImpl { notReadyService() }
        val frame = blankFrame()
        try {
            runBlocking {
                try {
                    gw.readRois(frame, listOf(FrameRect(0, 0, 2, 2), FrameRect(0, 2, 2, 2)))
                    throw AssertionError("readRois 在引擎未就绪时应抛 OcrUnavailableException")
                } catch (expected: OcrUnavailableException) {
                    // 引擎不可用必须 loud，与「图上没字」（空串）严格区分
                }
            }
        } finally {
            frame.release()
        }
    }

    @Test
    fun `A7 引擎未就绪时 readLines 与 readNumber 同样抛`() {
        val gw = OcrGatewayImpl { notReadyService() }
        val frame = blankFrame()
        try {
            runBlocking {
                try {
                    gw.readLines(frame, listOf(FrameRect(0, 0, 2, 2)))
                    throw AssertionError("readLines 应抛 OcrUnavailableException")
                } catch (expected: OcrUnavailableException) {
                }
                try {
                    gw.readNumber(frame, FrameRect(0, 0, 2, 2))
                    throw AssertionError("readNumber 应抛 OcrUnavailableException")
                } catch (expected: OcrUnavailableException) {
                }
            }
        } finally {
            frame.release()
        }
    }

    @Test
    fun `A7 UnavailableOcrService 同样显式失败`() {
        // OcrFactory.init 尚未把默认引擎切上去（或 ONNX 全档失败）时的旧"静默空串"路径
        val gw = OcrGatewayImpl { UnavailableOcrService }
        val frame = blankFrame()
        try {
            runBlocking {
                try {
                    gw.readRois(frame, listOf(FrameRect(0, 0, 2, 2)))
                    throw AssertionError("UnavailableOcrService 应抛 OcrUnavailableException")
                } catch (expected: OcrUnavailableException) {
                }
            }
        } finally {
            frame.release()
        }
    }

    @Test
    fun `A7 引擎就绪时正常返回_空串只来自真识别不到`() {
        val dir = onnxAssetsOrNull()
        Assume.assumeTrue("src/main/assets/onnx 模型缺失，跳过（对照用例依赖真实模型）", dir != null)
        val assets = dir!!
        val engine = OnnxOcrEngine(File(assets, "det.onnx"), File(assets, "rec.onnx"))
        val dict = File(assets, "ppocrv6_tiny_dict.txt").readLines().filter { it.isNotEmpty() }
        val service = OnnxPaddleOcrService(engine, dict)
        // caps 取保守组合：非高通 ⇒ 候选只有 CPU；大核 2 ⇒ intra 单候选，跳过基准（快）
        val caps = DeviceCapabilities(
            socVendor = "unknown",
            socModel = "gateway-test",
            isArm64 = false,
            cpuCount = 4,
            bigCoreCount = 2,
            bigCoreMaxFreqKHz = 2_000_000,
            qnnLibsLoadable = false,
        )
        assertTrue("真实引擎 prepare 应成功", service.prepare(caps))
        val gw = OcrGatewayImpl { service }
        val frame = blankFrame()
        try {
            val out = runBlocking { gw.readRois(frame, listOf(FrameRect(0, 0, 2, 2), FrameRect(2, 2, 2, 2))) }
            // 纯色 2x2 小图没有字 ⇒ 空串是**真实结果**；与未就绪时的异常路径区分开
            assertEquals(listOf("", ""), out)
        } finally {
            frame.release()
            engine.close()
        }
    }

    // ================= A19 =================

    @Test
    fun `A19 全部 EP 档建会话失败时 prepare 返回 false`() {
        // ⚠️ A19 的"双失败"分支（rebuild(best) 失败 **且** 沿用 incumbent 的兜底 initialize
        //    也失败 ⇒ prepare=false）在 JVM 上无法确定性构造：同一引擎模型路径固定，
        //    createSession 不会先成功后失败；OnnxOcrEngine 是 final 具体类，测试依赖没有
        //    mock 库。该分支已在 OnnxPaddleOcrService.prepare 内用显式返回值检查覆盖，
        //    真机验证依赖低内存场景日志（"沿用 … 亦失败 ⇒ OCR 不可用"）。
        val engine = OnnxOcrEngine(
            File("/nonexistent/bettergi-pocket/det.onnx"),
            File("/nonexistent/bettergi-pocket/rec.onnx"),
        )
        val service = OnnxPaddleOcrService(engine, List(OnnxPaddleOcrService.EXPECTED_DICT_SIZE) { "桩" })
        val caps = DeviceCapabilities(
            socVendor = "unknown",
            socModel = "prepare-fail-test",
            isArm64 = false,
            cpuCount = 4,
            bigCoreCount = 2,
            bigCoreMaxFreqKHz = 2_000_000,
            qnnLibsLoadable = false,
        )
        val ok = service.prepare(caps)
        assertFalse("所有 EP 档建会话失败 ⇒ prepare 必须返回 false", ok)
        assertFalse(service.ready)
        engine.close() // 失败路径后 close 仍须安全（OcrFactory.createOnnx 的失败分支就是这么走的）
    }
}
