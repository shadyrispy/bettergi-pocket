package com.bettergi.pocket.trigger

fun screenBottomCenter(width: Int, height: Int): Pair<Int, Int> {
    return width / 2 to (height - 20).coerceAtLeast(0)
}
