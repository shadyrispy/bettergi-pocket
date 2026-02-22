package com.bettergi.pocket.recognition.area

import com.bettergi.pocket.recognition.IntRect

fun interface NodeConverter {
    fun toPrev(x: Int, y: Int, w: Int, h: Int): IntRect
}

class TranslationConverter(
    private val offsetX: Int,
    private val offsetY: Int,
) : NodeConverter {
    override fun toPrev(x: Int, y: Int, w: Int, h: Int): IntRect {
        return IntRect(x + offsetX, y + offsetY, w, h)
    }
}

class ScaleConverter(
    val scale: Double,
) : NodeConverter {
    override fun toPrev(x: Int, y: Int, w: Int, h: Int): IntRect {
        return IntRect(
            x = (x * scale).toInt(),
            y = (y * scale).toInt(),
            width = (w * scale).toInt(),
            height = (h * scale).toInt(),
        )
    }
}

data class ConvertedPosition<T : Region>(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val target: T,
) {
    fun toRect(): IntRect = IntRect(x, y, width, height)
}

fun <T : Region> convertPositionToTarget(
    x: Int,
    y: Int,
    w: Int,
    h: Int,
    start: Region,
    targetClass: Class<T>,
): ConvertedPosition<T> {
    var node: Region? = start
    var cx = x
    var cy = y
    var cw = w
    var ch = h
    while (node != null) {
        if (targetClass.isInstance(node)) {
            @Suppress("UNCHECKED_CAST")
            return ConvertedPosition(cx, cy, cw, ch, node as T)
        }
        val converter = node.prevConverter
            ?: throw IllegalStateException("PrevConverter is null")
        val mapped = converter.toPrev(cx, cy, cw, ch)
        cx = mapped.x
        cy = mapped.y
        cw = mapped.width
        ch = mapped.height
        node = node.prev
    }
    throw IllegalStateException("Target Region not found: ${targetClass.simpleName}")
}

open class Region(
    var x: Int = 0,
    var y: Int = 0,
    var width: Int = 0,
    var height: Int = 0,
    val prev: Region? = null,
    val prevConverter: NodeConverter? = null,
) : AutoCloseable {
    var text: String = ""
    var matchScore: Double? = null

    val left: Int get() = x
    val top: Int get() = y
    val right: Int get() = x + width
    val bottom: Int get() = y + height

    fun isEmpty(): Boolean = width == 0 && height == 0 && x == 0 && y == 0

    fun isExist(): Boolean = !isEmpty()

    fun toRect(): IntRect = IntRect(x, y, width, height)

    fun derive(x: Int, y: Int, w: Int = 0, h: Int = 0): Region {
        return Region(x, y, w, h, this, TranslationConverter(x, y))
    }

    fun derive(rect: IntRect): Region = derive(rect.x, rect.y, rect.width, rect.height)

    fun toNativeCaptureRect(): IntRect {
        val converted = convertPositionToTarget(0, 0, width, height, this, GameCaptureRegion::class.java)
        return converted.toRect()
    }

    fun centerOnNativeCapture(): Pair<Int, Int> {
        val rect = toNativeCaptureRect()
        return rect.centerX to rect.centerY
    }

    override fun close() = Unit
}
