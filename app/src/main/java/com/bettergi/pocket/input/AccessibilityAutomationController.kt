package com.bettergi.pocket.input

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bettergi.pocket.bridge.OverlayBridge

/**
 * 动作原语执行端（dispatchGesture 收敛在 :a11y / 本地服务双路径，见 InputAccessibilityService）。
 *
 * [onActionCompleted]：动作 dispatch 受理后回调——FrameSource.markActionAt 的联动点，
 * 扫描链路据此取「动作后新帧」，杜绝旧帧。
 */
class AccessibilityAutomationController(
    private val overlayController: OverlayBridge,
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
                mainHandler.postDelayed(restorePassthrough, passthroughRestoreDelayMs(durationMs))
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
                mainHandler.postDelayed(restorePassthrough, passthroughRestoreDelayMs(totalMs))
            }
            onActionCompleted?.invoke()
        }
    }

    /**
     * 穿透还原延时 = 手势名义时长（抬高到 [MIN_GESTURE_STROKE_MS]）+ [PASSTHROUGH_RESTORE_SLACK_MS]。
     *
     * ⚠️ 约束：还原点必须**晚于 a11y 手势的实际 UP 注入** —— 手势还在跑就把悬浮窗恢复可触摸，
     * 落在窗口上的 UP 会被自家窗吃掉 ⇒ 游戏只见 DOWN 无 UP ⇒ 不触发 click。
     * 下限 120ms 的出处：clickLocal 把 stroke 强制 `durationMs.coerceAtLeast(120L)`
     * （真机标定：零位移短按在 EMUI + 原神背包详情上不可靠），故 durationMs=50 的
     * ClickAction 实际手势时长也是 120ms；余量 600ms 对齐 scan/ScriptRunner.PASSTHROUGH_RESTORE_SLACK_MS
     * （那边是 private 常量，无法直接引用，只能复制量级）—— dispatchGesture 受理与逐段续排均异步，
     * 名义时长 ≠ 实际结束点，窗口晚几百毫秒恢复可点的代价远小于吞掉一次有效点击。
     * swipe 同走本函数：单段名义时长 = durationMs（服务侧仅 coerceAtLeast(1L)），
     * 三段式 = SWIPE_TOTAL_MS；下限只会把还原再往后推，方向安全。
     */
    private fun passthroughRestoreDelayMs(gestureDurationMs: Long): Long =
        gestureDurationMs.coerceAtLeast(MIN_GESTURE_STROKE_MS) + PASSTHROUGH_RESTORE_SLACK_MS

    private companion object {
        const val TAG = "BetterGI.Input"

        /** 手势实际时长下限 = clickLocal 的 stroke 下限（`durationMs.coerceAtLeast(120L)`）。 */
        const val MIN_GESTURE_STROKE_MS = 120L

        /** 还原宽放余量，量级对齐 scan/ScriptRunner.PASSTHROUGH_RESTORE_SLACK_MS。 */
        const val PASSTHROUGH_RESTORE_SLACK_MS = 600L
    }
}
