package com.bettergi.pocket.recognition

/**
 * 对齐 BetterGI [SystemInfo]：以 1920 宽为 1080P 基准。
 *
 * - 捕获宽 > 1920：识别前先缩到 1920 宽，模板用 1.0
 * - 捕获宽 < 1920：画面不放大，1080P 模板按 [assetScale] 缩小
 */
data class CaptureScale(
    val captureWidth: Int,
    val captureHeight: Int,
    val assetScale: Double,
    val scaleTo1080PRatio: Double,
    val recognitionWidth: Int,
    val recognitionHeight: Int,
) {
    fun scale1080P(value: Double): Double = value * assetScale

    fun scale1080P(value: Int): Int = kotlin.math.round(value * assetScale).toInt()

    fun rect1080P(x: Int, y: Int, w: Int, h: Int): IntRect {
        return IntRect(scale1080P(x), scale1080P(y), scale1080P(w), scale1080P(h))
    }

    companion object {
        const val BASELINE_WIDTH = 1920
        const val BASELINE_HEIGHT = 1080

        fun fromCaptureSize(width: Int, height: Int): CaptureScale {
            require(width > 0 && height > 0) { "Capture size must be positive: ${width}x$height" }
            val assetScale = if (width < BASELINE_WIDTH) width / BASELINE_WIDTH.toDouble() else 1.0
            val scaleTo1080PRatio = width / BASELINE_WIDTH.toDouble()
            val recognitionWidth: Int
            val recognitionHeight: Int
            if (width > BASELINE_WIDTH) {
                val downscale = width / BASELINE_WIDTH.toDouble()
                recognitionWidth = BASELINE_WIDTH
                recognitionHeight = (height / downscale).toInt()
            } else {
                recognitionWidth = width
                recognitionHeight = height
            }
            return CaptureScale(
                captureWidth = width,
                captureHeight = height,
                assetScale = assetScale,
                scaleTo1080PRatio = scaleTo1080PRatio,
                recognitionWidth = recognitionWidth,
                recognitionHeight = recognitionHeight,
            )
        }
    }
}
