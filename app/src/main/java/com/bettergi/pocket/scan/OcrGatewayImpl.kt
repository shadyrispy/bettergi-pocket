package com.bettergi.pocket.scan

import android.util.Log
import com.bettergi.pocket.recognition.IntRect
import com.bettergi.pocket.recognition.OcrText
import com.bettergi.pocket.recognition.ocr.IOcrService
import com.bettergi.pocket.recognition.ocr.OcrFactory
import com.bettergi.pocket.recognition.ocr.UnavailableOcrService
import com.bettergi.pocket.recognition.opencv.MatOps
import org.opencv.core.Mat

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
 */
class OcrGatewayImpl : OcrGateway {

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
        val text = recognizeText(frame, rect) ?: return@timedOcr null
        // "圣遗物 1026/2400" → 斜杠前数字；无斜杠取最后一个数字
        val cleaned = StatParser.clean(text)
        val slash = Regex("(\\d+)\\s*/\\s*\\d+").find(cleaned)
            ?: Regex("\\d+").findAll(cleaned).lastOrNull()
            ?: return@timedOcr null
        slash.value.split("/").first().filter { it.isDigit() }.toIntOrNull()
    }

    override suspend fun readLines(frame: Mat, rects: List<FrameRect>): List<String> = timedOcr {
        rects.mapNotNull { rect -> recognizeText(frame, rect)?.takeIf { it.isNotBlank() } }
    }

    /**
     * 批量槽位读取：rec-only 引擎（ONNX）走一次 recognizeRois（N 个 rec 推理，~2ms/槽）；
     * ML Kit 逐槽原路径（含 <80px 2x 放大）。返回与 rects 一一对应，blank 保留为空串。
     */
    override suspend fun readRois(frame: Mat, rects: List<FrameRect>): List<String> = timedOcr {
        val service = OcrFactory.default
        if (service is UnavailableOcrService) return@timedOcr rects.map { "" }
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
     * ⚠️ <80px 2x 放大**不能**挪进 MlKitOcrService.recognize：ImageRegion 的 OcrMatch
     * 直接消费 recognize 返回的 region 坐标（放大后坐标会错位），所以放大只能留在
     * 这里这条「只要文本不要坐标」的路径上。
     */
    private fun recognizeText(frame: Mat, rect: FrameRect): String? {
        val service = OcrFactory.default
        if (service is UnavailableOcrService) return null
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
