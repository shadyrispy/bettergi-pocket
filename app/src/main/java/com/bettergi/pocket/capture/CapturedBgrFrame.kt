package com.bettergi.pocket.capture

import org.opencv.core.Mat

/**
 * 已转成 BGR 的一帧，供识别使用。调用方负责随 [com.bettergi.pocket.recognition.CaptureContent] 释放 Mat。
 */
class CapturedBgrFrame(
    val width: Int,
    val height: Int,
    val bgr: Mat,
)
