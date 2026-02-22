package com.bettergi.pocket.capture

/**
 * 全屏 RGBA 快照。含 [ByteArray] 故不用 data class，避免生成错误的 equals/hashCode。
 */
class Frame(
    val timestampMs: Long,
    val width: Int,
    val height: Int,
    val rgba8888: ByteArray,
)
