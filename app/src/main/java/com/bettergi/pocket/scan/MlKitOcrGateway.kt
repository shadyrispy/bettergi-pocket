package com.bettergi.pocket.scan

import android.util.Log
import com.bettergi.pocket.recognition.ocr.OcrFactory
import com.bettergi.pocket.recognition.opencv.MatOps
import org.opencv.core.Mat

/**
 * OcrGateway 的 ML Kit 实现（P1-b 先行；ONNX PaddleOCR det+rec 移植后可替换，
 * ScanEngine 不感知——方案 P1 验收「OcrMatch 原语在 ML Kit/ONNX 两种 default 下均通过」）。
 */
class MlKitOcrGateway : OcrGateway {

    override suspend fun readNumber(frame: Mat, rect: FrameRect): Int? {
        val text = recognizeRoi(frame, rect) ?: return null
        // "圣遗物 1026/2400" → 斜杠前数字；无斜杠取最后一个数字
        val cleaned = StatParser.clean(text)
        val slash = Regex("(\\d+)\\s*/\\s*\\d+").find(cleaned)
            ?: Regex("\\d+").findAll(cleaned).lastOrNull()
            ?: return null
        return slash.value.split("/").first().filter { it.isDigit() }.toIntOrNull()
    }

    override suspend fun readLines(frame: Mat, rects: List<FrameRect>): List<String> {
        return rects.mapNotNull { rect -> recognizeRoi(frame, rect)?.takeIf { it.isNotBlank() } }
    }

    private fun recognizeRoi(frame: Mat, rect: FrameRect): String? {
        val service = OcrFactory.default
        if (service is com.bettergi.pocket.recognition.ocr.UnavailableOcrService) return null
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
            Log.d("BetterGI.Ocr", "roi(${w}x$h${if (scaledOwned) " x2" else ""}) -> '$text'")
            text
        } finally {
            if (scaledOwned) scaled.release()
            roi.release()
        }
    }
}
