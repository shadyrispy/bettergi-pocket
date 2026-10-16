package com.bettergi.pocket.vd

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent

/**
 * ★ #310/M3：后台模式的 ActionGateway 实现——把引擎的 click/tap/swipe/back 翻译成
 * `injectInputEvent`（经 [InjectPlanner] 规划 + [VdInput] 注入），事件全部打到虚拟屏
 * [displayId]（反射 setDisplayId）。
 *
 * 与前台 a11y 版的语义差异（M3 验收关注点）：
 * - 无 overlay passthrough（后台模式无悬浮窗）——`resetPassthrough` 空操作；
 * - `back()` 用 KEYCODE_BACK 的 KeyEvent 注入（无障碍 GLOBAL_ACTION_BACK 的等价物）；
 * - swipe 是 DOWN→MOVE×n→UP 的分步注入（a11y 是 GestureDescription 整段派发）：
 *   MOVE 步长按 ~16ms/步（60fps 合成节奏），轨迹线性插值；末速不归零问题由引擎的
 *   fpband 落地测量兜底（它量的是**画面**位移不是手势速度——命令式滑动残差在
 *   `pageDrift` 记账链路里消化）。
 * - markActionAt：调用方（timedActions 装饰器）在动作后调——本类不持有帧源。
 */
class VdInjectActionGateway(
    private val displayId: Int,
) : com.bettergi.pocket.scan.ScanEngine.ActionGateway {

    companion object {
        private const val TAG = "BetterGI.VD"
        /** MOVE 步进间隔 ms（60fps 合成节奏；scrcpy 同量级）。 */
        private const val MOVE_STEP_MS = 16L
        private const val MOVE_STEPS_MAX = 40
        /** 点击按压时长 ms（对齐 a11y 版 tap 的 100ms 语义）。 */
        private const val TAP_DOWN_MS = 100L
    }

    private val planner = InjectPlanner()

    init {
        runCatching { VdInput.init() }
    }

    override fun resetPassthrough() {
        // 后台模式无悬浮窗穿透概念——空操作（KDoc 见 ScanEngine.ActionGateway）。
    }

    /** 单指 tap：DOWN（100ms 停留）→ UP。两段都同步注入（短事件，失败立即知）。 */
    override fun tap(x: Int, y: Int): Boolean {
        val now = SystemClock.elapsedRealtime()
        val down = planner.planDown(0, 0, x.toFloat(), y.toFloat(), now)
        var ok = true
        for (p in down) ok = VdInput.injectPlanned(p, displayId, planner) && ok
        if (!ok) {
            planner.onInjectFailed(down.first())
            return false
        }
        val up = planner.planUp(0, x.toFloat(), y.toFloat(), now + TAP_DOWN_MS)
        Thread.sleep(TAP_DOWN_MS)
        for (p in up) ok = VdInput.injectPlanned(p, displayId, planner) && ok
        if (!ok) planner.onInjectFailed(up.first())
        return ok
    }

    override fun click(x: Int, y: Int, durationMs: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        val down = planner.planDown(0, 0, x.toFloat(), y.toFloat(), now)
        var ok = true
        for (p in down) ok = VdInput.injectPlanned(p, displayId, planner) && ok
        if (!ok) {
            planner.onInjectFailed(down.first())
            return false
        }
        val up = planner.planUp(0, x.toFloat(), y.toFloat(), now + durationMs)
        Thread.sleep(durationMs.coerceAtMost(2000L))
        for (p in up) ok = VdInput.injectPlanned(p, displayId, planner) && ok
        if (!ok) planner.onInjectFailed(up.first())
        return ok
    }

    /** 线性插值滑动：DOWN → MOVE（每 MOVE_STEP_MS 一步）→ UP（到点即抬，无惯性）。 */
    override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean {
        val now = SystemClock.elapsedRealtime()
        val down = planner.planDown(0, 0, fromX.toFloat(), fromY.toFloat(), now)
        var ok = true
        for (p in down) ok = VdInput.injectPlanned(p, displayId, planner) && ok
        if (!ok) {
            planner.onInjectFailed(down.first())
            return false
        }
        val distX = toX - fromX
        val distY = toY - fromY
        val steps = ((Math.abs(distX) + Math.abs(distY)) / 24).toInt().coerceIn(2, MOVE_STEPS_MAX)
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val x = fromX + (distX * t).toInt()
            val y = fromY + (distY * t).toInt()
            val moves = planner.planMove(0, x.toFloat(), y.toFloat(), SystemClock.elapsedRealtime())
            if (moves.isEmpty()) {
                Log.w(TAG, "swipe MOVE 规划为空（contact 丢失）⇒ 中断并自愈")
                ok = false
                break
            }
            for (p in moves) ok = VdInput.injectPlanned(p, displayId, planner) && ok
            Thread.sleep(MOVE_STEP_MS)
        }
        val up = planner.planUp(0, toX.toFloat(), toY.toFloat(), SystemClock.elapsedRealtime())
        for (p in up) ok = VdInput.injectPlanned(p, displayId, planner) && ok
        if (!ok) planner.onInjectFailed(up.first())
        return ok
    }

    /** 系统返回：KEYCODE_BACK 的 DOWN/UP 注入（无障碍 GLOBAL_ACTION_BACK 的等价物）。 */
    override fun back(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val down = KeyEvent(
            now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0,
        )
        val up = KeyEvent(
            now, now + 80, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0,
        )
        val dOk = VdInput.setDisplayId(down, displayId) && VdInput.inject(down, async = false)
        val uOk = VdInput.setDisplayId(up, displayId) && VdInput.inject(up, async = true)
        return dOk && uOk
    }
}
