package com.bettergi.pocket.input

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bettergi.pocket.overlay.OverlayWindowController

class AccessibilityAutomationController(
    private val overlayController: OverlayWindowController,
) : AutomationController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val restorePassthrough = Runnable { overlayController.restoreClickPassthrough() }

    override fun execute(action: AutomationAction) {
        when (action) {
            is ClickAction -> executeClick(action)
        }
    }

    private fun executeClick(action: ClickAction) {
        if (!InputAccessibilityService.isConnected()) {
            Log.w(TAG, "skip click, accessibility service is not connected")
            return
        }
        mainHandler.post {
            val needPassthrough = overlayController.prepareClickPassthrough(action.x, action.y)
            val dispatched = InputAccessibilityService.click(action.x, action.y, action.durationMs)
            if (!dispatched) {
                if (needPassthrough) {
                    overlayController.restoreClickPassthrough()
                }
                Log.w(TAG, "dispatchGesture failed at ${action.x},${action.y}")
                return@post
            }
            if (needPassthrough) {
                mainHandler.removeCallbacks(restorePassthrough)
                mainHandler.postDelayed(restorePassthrough, action.durationMs + RESTORE_TOUCH_DELAY_MS)
            }
        }
    }

    private companion object {
        const val TAG = "BetterGI.Input"
        const val RESTORE_TOUCH_DELAY_MS = 40L
    }
}
