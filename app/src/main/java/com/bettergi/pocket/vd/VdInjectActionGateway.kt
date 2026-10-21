package com.bettergi.pocket.vd

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent

/**
 * ★ #310/M3：后台模式的 ActionGateway 实现——把引擎的 click/tap/swipe/back 翻译成
 * 对虚拟屏 [displayId] 的输入注入。
 *
 * ★ 底层双通路（2026-10-18 BS 实测定型）：
 * - **主通路 = `Runtime.exec("input -d N ...")`**（shell uid 下与 adb input 完全同一条
 *   binder 链，已验证能落到 VD 游戏窗口）。每次动作 fork 一个短命进程（~60-120ms），
 *   在引擎动作预算（click 100ms+）内可接受；
 * - 备通路 = `injectInputEvent`（[InjectPlanner] + [VdInput]，事件打 displayId + 
 *   uptimeMillis 时间戳）——exec 失败（IOException）时回退它，也保留给将来找出
 *   进程内注入不落地的根因后切回（进程内零 fork，时序精度高一个量级）。
 *
 * 与前台 a11y 版的语义差异（M3 验收关注点）：
 * - 无 overlay passthrough（后台模式无悬浮窗）——`resetPassthrough` 空操作；
 * - `back()` 用 `input keyevent`（无障碍 GLOBAL_ACTION_BACK 的等价物）；
 * - swipe 用 `input swipe` 原生整段手势（带 duration），不再手工分步——exec 通路下
 *   分步注入的步间节拍无法保证，原生 swipe 由系统合成轨迹更接近真人。
 * - markActionAt：调用方（timedActions 装饰器）在动作后调——本类不持有帧源。
 */
class VdInjectActionGateway(
    private val displayId: Int,
) : com.bettergi.pocket.scan.ScanEngine.ActionGateway {

    companion object {
        private const val TAG = "BetterGI.VD"
        /** 点击按压时长 ms（对齐 a11y 版 tap 的 100ms 语义）。 */
        private const val TAP_DOWN_MS = 100L
    }

    init {
        runCatching { VdInput.init() }
    }

    /** `input` 命令执行（shell uid 下等价 adb input；已验证能落到 VD 游戏窗口）。 */
    private fun execInput(vararg args: String): Boolean {
        val full = arrayOf("input", "-d", displayId.toString()) + args
        return runCatching {
            val p = ProcessBuilder(*full).start()
            val code = p.waitFor()
            if (code != 0) {
                val err = p.errorStream.bufferedReader().use { it.readText() }.trim()
                Log.w(TAG, "exec input failed code=$code: ${err.take(120)}")
            }
            code == 0
        }.getOrElse {
            Log.w(TAG, "exec input 异常: ${it.message}")
            false
        }
    }

    /** 备通路：进程内 injectInputEvent（exec 失败时回退；时间戳必须 uptimeMillis）。 */
    private fun execFallbackTap(x: Int, y: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val planner = InjectPlanner()
        val down = planner.planDown(0, 0, x.toFloat(), y.toFloat(), now)
        var ok = true
        for (p in down) ok = VdInput.injectPlanned(p, displayId, planner) && ok
        val up = planner.planUp(0, x.toFloat(), y.toFloat(), now + TAP_DOWN_MS)
        Thread.sleep(TAP_DOWN_MS)
        for (p in up) ok = VdInput.injectPlanned(p, displayId, planner) && ok
        if (!ok) planner.onInjectFailed(down.first())
        return ok
    }

    override fun resetPassthrough() {
        // 后台模式无悬浮窗穿透概念——空操作（KDoc 见 ScanEngine.ActionGateway）。
    }

    /** 单指 tap：`input -d N tap x y`（系统原生 100ms 节奏）。 */
    override fun tap(x: Int, y: Int): Boolean =
        execInput("tap", x.toString(), y.toString()) || execFallbackTap(x, y)

    override fun click(x: Int, y: Int, durationMs: Long): Boolean =
        execInput("swipe", x.toString(), y.toString(), x.toString(), y.toString(),
            durationMs.coerceIn(80, 2000).toString()) ||
            execFallbackTap(x, y)

    /** 整段滑动：`input -d N swipe x1 y1 x2 y2 duration`（系统合成轨迹）。 */
    override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean {
        // 引擎默认滑动 ~780ms（swipeTotal）；input 原生 duration 参数直接对齐
        return execInput(
            "swipe",
            fromX.toString(), fromY.toString(), toX.toString(), toY.toString(), "780",
        )
    }

    /** 系统返回：`input keyevent BACK`。 */
    override fun back(): Boolean {
        val ok = execInput("keyevent", "4")
        if (!ok) {
            // 备通路：KeyEvent 进程内注入
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)
            val up = KeyEvent(now, now + 80, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0)
            return VdInput.setDisplayId(down, displayId) && VdInput.inject(down, async = false) &&
                VdInput.setDisplayId(up, displayId) && VdInput.inject(up, async = true)
        }
        return ok
    }
}
