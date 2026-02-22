package com.bettergi.pocket.recognition

data class IntSize(
    val width: Int,
    val height: Int,
) {
    init {
        require(width >= 0 && height >= 0) { "Size must be non-negative: ${width}x$height" }
    }
}

data class IntRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    val left: Int get() = x
    val top: Int get() = y
    val right: Int get() = x + width
    val bottom: Int get() = y + height
    val centerX: Int get() = x + width / 2
    val centerY: Int get() = y + height / 2

    fun isDefault(): Boolean = x == 0 && y == 0 && width == 0 && height == 0

    fun isEmpty(): Boolean = width <= 0 || height <= 0

    fun offset(dx: Int, dy: Int): IntRect = copy(x = x + dx, y = y + dy)

    fun clampTo(maxWidth: Int, maxHeight: Int): IntRect {
        val x1 = x.coerceIn(0, maxWidth)
        val y1 = y.coerceIn(0, maxHeight)
        val x2 = (x + width).coerceIn(0, maxWidth)
        val y2 = (y + height).coerceIn(0, maxHeight)
        return IntRect(x1, y1, x2 - x1, y2 - y1)
    }

    companion object {
        val EMPTY = IntRect(0, 0, 0, 0)
    }
}

data class ColorBgr(
    val b: Double,
    val g: Double,
    val r: Double,
) {
    companion object {
        val MASK_GREEN = ColorBgr(0.0, 255.0, 0.0)
    }
}
