package com.bettergi.pocket.input

import android.content.Context
import com.bettergi.pocket.overlay.AccessibilityInputGate

/** [AccessibilityInputGate] 的 input 侧实现（方案 3.2 注入）：委托 [InputAccessibilityService] 的静态入口。 */
object DefaultAccessibilityInputGate : AccessibilityInputGate {
    override val stateChangedAction: String = InputAccessibilityService.ACTION_STATE_CHANGED

    override fun ensureEnabled(context: Context, message: String) =
        if (message.isEmpty()) {
            InputAccessibilityService.ensureEnabled(context)
        } else {
            InputAccessibilityService.ensureEnabled(context, message)
        }

    override fun isOperational(context: Context): Boolean =
        InputAccessibilityService.health(context) == AccessibilityServiceHealth.State.CONNECTED

    override fun isDisconnected(context: Context): Boolean =
        InputAccessibilityService.health(context) == AccessibilityServiceHealth.State.DISCONNECTED
}
