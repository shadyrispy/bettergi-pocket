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
        /** 分步滑动的 MOVE 步长 px（每步一次注入事件，60fps 合成节奏的位移量）。 */
        private const val MOVE_STEP_PX = 24
        /** 分步滑动步数上限。 */
        private const val MOVE_STEPS_MAX = 60
        /** 分步滑动的步间停留 ms（16ms ≈ 60fps 一拍）。 */
        private const val MOVE_STEP_MS = 16L
        /** 格点击的最小按压时长 ms（对齐前台的 `durationMs.coerceAtLeast(120L)`）。 */
        private const val MIN_CLICK_MS = 120L
        /** 格点击的微滑位移 px（对齐前台 clickLocal 的 2px 微滑）。 */
        private const val CLICK_MICRO_PX = 2
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

    /**
     * 单指 tap：**进程内优先**，失败回退 exec。
     *
     * ★ 2026-10-18 根因研究定稿（BS 实测完整证据链）：
     * - 长期误判「进程内注入不落地」的**真因是多指 obtain 重载坐标被清零**
     *   （dumpsys input RecentQueue 里 `pointers=[0: (0.0, 0.0)]`，而 exec 的是真实坐标）。
     *   InjectPlanner 改走**单点标量 14 参 obtain**（反射选中）后，进程内注入立即在 VD 上
     *   落地（BS 实测：点「点击进入」成功把游戏从标题屏送进世界）。
     * - 另一条独立教训（主屏自点场景）：WAIT_FOR_FINISH 若从**被点的那个 UI 进程**发起，
     *   会自己等自己 → 5s ANR → 系统 CANCEL。生产路径（服务进程点另一个进程的游戏）
     *   不受影响；跨进程注入必须保持在下游进程里发起，别在主线程调。
     */
    override fun tap(x: Int, y: Int): Boolean =
        inProcTap(x, y) || execInput("tap", x.toString(), y.toString()) || execFallbackTap(x, y)

    /** 进程内 tap：DOWN（同步）→ 100ms → UP。坐标由 InjectPlanner 单点重载保留。 */
    private fun inProcTap(x: Int, y: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val planner = InjectPlanner()
        val down = planner.planDown(0, 0, x.toFloat(), y.toFloat(), now)
        var ok = true
        for (p in down) ok = VdInput.injectPlanned(p, displayId, planner) && ok
        if (!ok) return false
        Thread.sleep(TAP_DOWN_MS)
        val up = planner.planUp(0, x.toFloat(), y.toFloat(), now + TAP_DOWN_MS)
        for (p in up) ok = VdInput.injectPlanned(p, displayId, planner) && ok
        if (!ok) planner.onInjectFailed(down.first())
        return ok
    }

    /**
     * 格点击（`ScanEngine.visitClick` 走这条；`tap()` 只给显式 tap 模式用）。
     *
     * ★ 2026-10-19 对齐前台（**第二个独立缺陷的修法**）：前台的 `clickLocal` 早就把点击
     *   实现成「**2px 微滑 + ≥120ms 按压**」的 StrokeDescription，KDoc 写明理由（纯零位移
     *   tap 在真机 + 原神背包详情面板切换上不可靠；adb tap 可切而 a11y 手势不行）。
     *   而后台此前是「零位移 + 100ms」——**正是前台已判死的那种形态**。
     *   实测对照：前台全量 1002/1002 零漏、后台漏 12，点击形态差异是主要嫌疑。
     *   ⇒ 后台 click 改用同样的 2px 微滑 + ≥120ms，与前台形态对齐。
     */
    override fun click(x: Int, y: Int, durationMs: Long): Boolean {
        val dur = durationMs.coerceAtLeast(MIN_CLICK_MS)
        if (inProcClick(x, y, dur)) return true
        // 回退：exec input swipe（带 2px 微滑；起终点不同 x，避免被合成成纯 tap）
        return execInput(
            "swipe",
            x.toString(), y.toString(), (x + CLICK_MICRO_PX).toString(), y.toString(), dur.toString(),
        ) || execFallbackTap(x, y)
    }

    /** 进程内格点击：DOWN → 2px 微滑 MOVE → 驻留至 duration → UP（对齐前台 clickLocal）。 */
    private fun inProcClick(x: Int, y: Int, durationMs: Long): Boolean {
        if (!VdInput.init()) return false
        val planner = InjectPlanner()
        val t0 = SystemClock.uptimeMillis()
        val down = planner.planDown(0, 0, x.toFloat(), y.toFloat(), t0)
        for (p in down) if (!VdInput.injectPlanned(p, displayId, planner)) {
            planner.onInjectFailed(p)
            return false
        }
        // 2px 微滑（前台同款）：让系统/游戏都识别为一次稳定触摸，而非可被判成拖拽的纯 tap
        val moves = planner.planMove(
            0, (x + CLICK_MICRO_PX).toFloat(), y.toFloat(), SystemClock.uptimeMillis(),
        )
        for (p in moves) VdInput.injectPlanned(p, displayId, planner)
        val elapsed = SystemClock.uptimeMillis() - t0
        val rest = (durationMs - elapsed).coerceAtLeast(0L)
        if (rest > 0) Thread.sleep(rest)
        val up = planner.planUp(
            0, (x + CLICK_MICRO_PX).toFloat(), y.toFloat(), SystemClock.uptimeMillis(),
        )
        for (p in up) if (!VdInput.injectPlanned(p, displayId, planner)) return false
        return true
    }

    /**
     * 整段滑动：**exec `input swipe` + fling 距离压缩**（2026-10-18 全量对账定稿）。
     *
     * ★ 为什么不用进程内分步 MOVE（同日实测结论，别再走回头路）：
     *   - exec `input swipe` 单段合成带 fling：名义 834px（3 行）实际滚 3.95 行 ⇒ 全量漏 27%。
     *     用**距离压缩 0.7625**（834→636px）后实测 3.04 行/次、56 次到底，
     *     圣遗物覆盖率 **94.8%（950/1002）**——这是目前最优基线。
     *   - 进程内分步 MOVE（16ms/24px 步进 + 终点驻留）**理论上无抬手 fling**（前台
     *     swipeThreeSegment 的等价物），且坐标清零 bug 修好后确实能在 VD 落地（本轮实测
     *     「点标题屏进世界」成功）。但全量实测只到 **757 件**：分步的总时长更短
     *     （560ms vs exec 780ms）⇒ 平均速度更高 ⇒ **游戏列表内建惯性反而更大**
     *     （单次相位 φ=+129px vs exec 版 13~53px）。要用它必须重新标定步距/时长，
     *     不是"无 fling 就一定准"。
     *   - ⇒ 维持 exec+压缩为主通路；进程内 tap 仍是主通路（无 fork、坐标已修正）。
     */
    override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean {
        val dy = toY - fromY
        val compressedToY = fromY + (dy * 762L / 1000L).toInt()
        return execInput(
            "swipe",
            fromX.toString(), fromY.toString(), toX.toString(), compressedToY.toString(), "780",
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
