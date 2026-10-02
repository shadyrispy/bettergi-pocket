package com.bettergi.pocket.capture

import org.opencv.core.Mat

/**
 * 已转成 BGR 的一帧，供识别使用。调用方负责随 [com.bettergi.pocket.recognition.CaptureContent] 释放 Mat。
 *
 * [timestampNs] 为 ImageReader 原始帧时间戳（纳秒，平台相关时间基准），仅用于同源流的
 * 帧新旧相对比较（FrameSource 判"动作后新帧"）；不可与 SystemClock 时刻直接换算。
 */
class CapturedBgrFrame(
    val width: Int,
    val height: Int,
    val bgr: Mat,
    val timestampNs: Long = 0L,
)
