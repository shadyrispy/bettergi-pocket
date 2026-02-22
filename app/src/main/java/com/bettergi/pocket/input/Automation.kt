package com.bettergi.pocket.input

sealed interface AutomationAction

data class ClickAction(
    val x: Int,
    val y: Int,
    val durationMs: Long = 50L,
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
