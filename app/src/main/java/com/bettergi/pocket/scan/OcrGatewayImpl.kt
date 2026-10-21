package com.bettergi.pocket.scan

import android.util.Log
import com.bettergi.pocket.core.IntRect
import com.bettergi.pocket.recognition.OcrText
import com.bettergi.pocket.recognition.ocr.IOcrService
import com.bettergi.pocket.recognition.ocr.OcrFactory
import com.bettergi.pocket.recognition.ocr.UnavailableOcrService
import com.bettergi.pocket.recognition.ocr.onnx.OnnxPaddleOcrService
import com.bettergi.pocket.core.image.MatOps
import org.opencv.core.Mat

/**
 * OCR 引擎不可用（未就绪 / 从未初始化）——与「图上没字」严格区分。
 *
 * ★ 2026-09-30（A7，optimization-plan-20260930 轨 E）：降档/重建窗口内
 * （[com.bettergi.pocket.recognition.ocr.onnx.OnnxOcrEngine] 换 EP 档、intra 重开，
 * 或首启后台预热未完成）`ready=false`，旧网关把这一状态当成「OCR 结果为空」写进扫描数据
 * ——静默脏数据比显式失败更难查。方案裁决：**不做**静默 ML Kit 兜底（该注释声称的降级
 * 从未实现），一律 loud 失败。
 *
 * 异常落点（ScanEngine 现状，2026-09-30 只读核查）：
 * - 主解析路径（parsePanel → parseWeaponPanel/parseArtifactPanel、ocrWithRetry 等**无本地
 *   catch** 的调用点）：异常穿过 executeStep（只有 finally）抛出 ScanEngine.run()，
 *   由 ScriptRunner `catch (Exception)` 记 "scan failed" 并导出已识别部分 —— **loud**；
 * - 少数本地吞异常的调用点（parseCharacterPanel 的函数级 catch、重读循环/页签判据的
 *   runCatching）会把本异常降级为「该格跳过/放弃重读」——已汇报主线，集成时对这些
 *   catch 放行本异常（一行级改动，见工单汇报）。
 */
class OcrUnavailableException(message: String) : Exception(message)

/**
 * OcrGateway 实现（ScanEngine 不感知具体引擎——方案 P1 验收
 * 「OcrMatch 原语在 ML Kit/ONNX 两种 default 下均通过」）。
 *
 * 原名 MlKitOcrGateway，2026-09-08 改名：本类**不依赖 ML Kit**，只是按
 * [IOcrService] 能力分发的统一网关；改名是移除 ML Kit 的前置清理（避免误以为绑定引擎）。
 *
 * 分发策略（审计建议 #1）：按 [IOcrService.hasFastRecOnlyBatch] 选择单槽路径——
 * - ONNX：recognizeRois（rec-only，跳过整帧 det），每槽 ~2ms vs 全管线 ~44ms；
 * - ML Kit（待移除）：原逐槽 recognizeText，保留 <80px 2x 放大增强（见 recognizeText 注释）。
 *
 * ★ 2026-09-30（A7）：所有入口在调 OCR 前过 [requireReady] 闸门 —— 引擎没就绪 ≠ 图上没字，
 *   前者抛 [OcrUnavailableException]，后者才是空结果。
 *   TOCTOU 说明：闸门检查与推理之间 ready 理论上可翻转（降档恰在两步之间），此时引擎层
 *   run 返回空数组作最后防御；窗口为秒级重建的极小截面，不值得为此加跨层锁。
 *
 * @param serviceProvider 服务源；默认全局 [OcrFactory]，构造注入仅为 JVM 单测能替换引擎桩。
 */
class OcrGatewayImpl(
    private val serviceProvider: () -> IOcrService = { OcrFactory.default },
) : OcrGateway {

    /** A7 可用性闸门：只区分「引擎不可用（抛）」与「放行」。 */
    private fun requireReady(service: IOcrService) {
        when {
            service is UnavailableOcrService ->
                throw OcrUnavailableException(
                    "OCR 引擎未初始化（OcrFactory 仍为 UnavailableOcrService；ScriptRunner 应在开跑前查 OcrFactory.available）",
                )
            service is OnnxPaddleOcrService && !service.ready ->
                throw OcrUnavailableException(
                    "ONNX OCR 引擎未就绪（EP 降档/重建窗口或首启预热未完成，tier=${service.tierLabel}）",
                )
        }
    }

    /**
     * 只读计时包装（2026-09-12 探针）：把一次网关调用的耗时累计进 [PerfProbe]。
     * ⚠️ 不做任何行为改变；`inline` + 非局部返回保持原实现的控制流不变。
     */
    private inline fun <T> timedOcr(block: () -> T): T {
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            PerfProbe.addOcr(System.nanoTime() - t0)
        }
    }

    override suspend fun readNumber(frame: Mat, rect: FrameRect): Int? = timedOcr {
        val service = serviceProvider()
        requireReady(service)
        val text = recognizeText(frame, rect, service) ?: return@timedOcr null
        // "圣遗物 1026/2400" → 斜杠前数字；无斜杠取最后一个数字
        val cleaned = StatParser.clean(text)
        val slash = Regex("(\\d+)\\s*/\\s*\\d+").find(cleaned)
            ?: Regex("\\d+").findAll(cleaned).lastOrNull()
            ?: return@timedOcr null
        slash.value.split("/").first().filter { it.isDigit() }.toIntOrNull()
    }

    override suspend fun readLines(frame: Mat, rects: List<FrameRect>): List<String> = timedOcr {
        val service = serviceProvider()
        requireReady(service)
        rects.mapNotNull { rect -> recognizeText(frame, rect, service)?.takeIf { it.isNotBlank() } }
    }

    /**
     * 批量槽位读取：rec-only 引擎（ONNX）走一次 recognizeRois（N 个 rec 推理，~2ms/槽）；
     * ML Kit 逐槽原路径（含 <80px 2x 放大）。返回与 rects 一一对应，blank 保留为空串。
     *
     * ★ 2026-09-30（A7）：旧实现 `service is UnavailableOcrService` / 引擎未就绪时返回
     *   `rects.map { "" }` —— 把「没引擎」冒充「没字」写进扫描数据，已改为抛
     *   [OcrUnavailableException]（loud）。空串现在只可能来自真的识别不到文本。
     */
    override suspend fun readRois(frame: Mat, rects: List<FrameRect>): List<String> = timedOcr {
        val service = serviceProvider()
        requireReady(service)
        if (frame.cols() <= 0 || frame.rows() <= 0) return@timedOcr rects.map { "" }
        if (service.hasFastRecOnlyBatch) {
            service.recognizeRois(frame, rects.map { it.toIntRect() })
                .map { OcrText.removeAllSpace(it.text) }
        } else {
            rects.map { rect -> recognizeRoiWithUpscale(frame, rect, service).orEmpty() }
        }
    }

    /**
     * 单槽文本，按引擎能力分发。
     *
     * ⚠️ <80px 2x 放大**不能**挪进 IOcrService.recognize：ImageRegion 的 OcrMatch
     * 直接消费 recognize 返回的 region 坐标（放大后坐标会错位），所以放大只能留在
     * 这里这条「只要文本不要坐标」的路径上。
     */
    private fun recognizeText(frame: Mat, rect: FrameRect, service: IOcrService): String? {
        if (frame.cols() <= 0 || frame.rows() <= 0) return null
        if (service.hasFastRecOnlyBatch) {
            // rec-only：PP-OCR rec 以 48px 高为工作点，无需预放大
            val region = service.recognizeRois(frame, listOf(rect.toIntRect())).firstOrNull()
                ?: return null
            return OcrText.removeAllSpace(region.text).takeIf { it.isNotBlank() }
        }
        return recognizeRoiWithUpscale(frame, rect, service)
    }

    /** ML Kit 原路径：<80px 小字 2x 放大增强 + recognizeText。 */
    private fun recognizeRoiWithUpscale(frame: Mat, rect: FrameRect, service: IOcrService): String? {
        if (frame.cols() <= 0 || frame.rows() <= 0) return null
        val x = rect.left.coerceIn(0, frame.cols() - 1)
        val y = rect.top.coerceIn(0, frame.rows() - 1)
        val w = rect.width.coerceIn(1, frame.cols() - x)
        val h = rect.height.coerceIn(1, frame.rows() - y)
        val roi = Mat(frame, org.opencv.core.Rect(x, y, w, h))
        // 小字增强：ML Kit 对 <80px 高的中文识别精度差，放大 2x 再识别
        val scaled = if (h < 80) MatOps.resize(roi, 2.0) else roi
        val scaledOwned = scaled !== roi
        return try {
            val text = service.recognizeText(scaled)
            Log.d("BetterGI.Ocr", "[${OcrFactory.engineLabel}] roi(${w}x$h${if (scaledOwned) " x2" else ""}) -> '$text'")
            text
        } finally {
            if (scaledOwned) scaled.release()
            roi.release()
        }
    }

    private fun FrameRect.toIntRect(): IntRect = IntRect(left, top, width, height)
}
