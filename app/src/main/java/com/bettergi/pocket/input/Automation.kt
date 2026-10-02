package com.bettergi.pocket.input

sealed interface AutomationAction

data class ClickAction(
    val x: Int,
    val y: Int,
    val durationMs: Long = 50L,
) : AutomationAction

/**
 * 滑动。低层原语 swipeGesture 的运行时载体。
 *
 * [segments]：1 = 单段惯性放行（通用滑动）；3 = 三段 continueStroke 无惯性滑动（消 fling 精确位移，
 * 经验节奏 400/300/100ms，源自 genshin-scanner-app 真机验证），此时 [durationMs] 仅作第一段缩放基准。
 */
data class SwipeAction(
    val fromX: Int,
    val fromY: Int,
    val toX: Int,
    val toY: Int,
    val durationMs: Long = 400L,
    val segments: Int = 3,
) : AutomationAction

/** 长按（执行体与点击同路径，仅时长不同）。 */
data class LongPressAction(
    val x: Int,
    val y: Int,
    val durationMs: Long = 800L,
) : AutomationAction

interface AutomationController {
    fun execute(action: AutomationAction)
}

object NoOpAutomationController : AutomationController {
    override fun execute(action: AutomationAction) = Unit
}

interface ActionEmitter {
    fun emit(action: AutomationAction)
}
