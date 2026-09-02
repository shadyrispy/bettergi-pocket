package com.bettergi.pocket.input

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bettergi.pocket.overlay.OverlayWindowController

/**
 * 动作原语执行端（dispatchGesture 收敛在 :a11y / 本地服务双路径，见 InputAccessibilityService）。
 *
 * [onActionCompleted]：动作 dispatch 受理后回调——FrameSource.markActionAt 的联动点，
 * 扫描链路据此取「动作后新帧」，杜绝旧帧。
 */
class AccessibilityAutomationController(
    private val overlayController: OverlayWindowController,
    private val onActionCompleted: (() -> Unit)? = null,
) : AutomationController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val restorePassthrough = Runnable { overlayController.restoreClickPassthrough() }

    override fun execute(action: AutomationAction) {
        when (action) {
            is ClickAction -> executeClick(action)
            is LongPressAction -> executePress(action.x, action.y, action.durationMs)
            is SwipeAction -> executeSwipe(action)
        }
    }

    private fun executeClick(action: ClickAction) = executePress(action.x, action.y, action.durationMs)

    private fun executePress(x: Int, y: Int, durationMs: Long) {
        if (!InputAccessibilityService.isConnected()) {
            Log.w(TAG, "skip press, accessibility service is not connected")
            return
        }
        mainHandler.post {
            val needPassthrough = overlayController.prepareClickPassthrough(x, y)
            val dispatched = InputAccessibilityService.click(x, y, durationMs)
            if (!dispatched) {
                if (needPassthrough) {
                    overlayController.restoreClickPassthrough()
                }
                Log.w(TAG, "dispatchGesture failed at $x,$y")
                return@post
            }
            if (needPassthrough) {
                mainHandler.removeCallbacks(restorePassthrough)
                mainHandler.postDelayed(restorePassthrough, durationMs + RESTORE_TOUCH_DELAY_MS)
            }
            onActionCompleted?.invoke()
        }
    }

    private fun executeSwipe(action: SwipeAction) {
        if (!InputAccessibilityService.isConnected()) {
            Log.w(TAG, "skip swipe, accessibility service is not connected")
            return
        }
        val totalMs =
            if (action.segments <= 1) {
                action.durationMs
            } else {
                InputAccessibilityService.SWIPE_TOTAL_MS
            }
        mainHandler.post {
            // 窗口级 touch passthrough：起/终点任一落在悬浮窗内即整窗放行
            val needPassthrough = overlayController.prepareClickPassthrough(action.fromX, action.fromY) or
                overlayController.prepareClickPassthrough(action.toX, action.toY)
            val dispatched = InputAccessibilityService.swipe(
                action.fromX,
                action.fromY,
                action.toX,
                action.toY,
                action.durationMs,
                action.segments,
            )
            if (!dispatched) {
                if (needPassthrough) {
                    overlayController.restoreClickPassthrough()
                }
                Log.w(TAG, "dispatchGesture swipe failed ${action.fromX},${action.fromY}->${action.toX},${action.toY}")
                return@post
            }
            if (needPassthrough) {
                mainHandler.removeCallbacks(restorePassthrough)
                mainHandler.postDelayed(restorePassthrough, totalMs + RESTORE_TOUCH_DELAY_MS)
            }
            onActionCompleted?.invoke()
        }
    }

    private companion object {
        const val TAG = "BetterGI.Input"
        const val RESTORE_TOUCH_DELAY_MS = 40L
    }
}
