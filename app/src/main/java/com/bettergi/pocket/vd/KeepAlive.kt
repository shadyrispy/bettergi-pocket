package com.bettergi.pocket.vd

import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * ★ #310/M2 后台模式：虚拟显示器的 keep-alive 调度器。
 *
 * ## 机制（MAA-Meow 验证过的思路，照思路重写）
 * 虚拟显示器上没有真实用户输入时，系统会按屏幕超时策略把它判成"闲"并休眠其
 * 内容（surface 停止出帧，游戏跟着挂起）。反制 = 周期性对**该显示器**发
 * `PowerManager.userActivity(displayId, whenMs, event)`（hidden API，提权进程可调；
 * app 进程的 PowerManager 壳方法只有 (long, boolean) 双参版且作用于主屏）。
 * 4s 周期：远小于最短屏幕超时（15s），也留出单次 binder 调用失败的容错余量
 * （连续失败只是丢拍，下一次照发，不累积状态）。
 *
 * ## 反射面
 * - `PowerManager#userActivity(long, int, int)`：SDK stub 里的签名是
 *   `(long when, int event, int flags)`（A15 实机 framework 是四参
 *   `(int displayId, long when, int event, int flags)` —— displayId 版是服务端
 *   binder 形态，客户端壳按 SDK 档位可能是三参或带 displayId 的变体）。本类
 *   **按参数个数自动探测**三参/四参两个反射目标，探测结果缓存，调用失败不炸。
 *   - 三参版：`userActivity(SystemClock.uptimeMillis(), USER_ACTIVITY_EVENT_OTHER, 0)`
 *     —— 在**调用进程所在默认显示器**上产生 userActivity（app 进程语义）。
 *   - 四参版：`userActivity(displayId, SystemClock.uptimeMillis(), event, 0)`
 *     —— 直接对目标 displayId（提权进程语义，M2 主路径）。
 *   全部 runCatching + 日志；提权进程里四参优先、三参兜底。
 * - 被 hidden-API 拦截（P+ 元数据限制）时由 [VirtualDisplayManager] 侧的
 *   HiddenApiBypass 前置豁免兜底（本类不重复引依赖，保持单一豁免入口）。
 *
 * ## 调度实现
 * HandlerThread（独立线程、自带 Looper）—— UserService 进程**没有主 Looper**
 * （非 app 进程，Looper.getMainLooper() 可用但无人驱动它的消息队列语义完全靠
 * 自觉），绑定自己的 HandlerThread 是唯一可靠形态。start/stop 幂等：
 * - 重复 start：no-op（已有活跃调度器）；
 * - 未 start 的 stop：no-op；
 * - destroy 后 start：重新起线程（stop 会退出 Looper）。
 * 周期拍子用 `postDelayed` 自续（相对时间，不受 `uptimeMillis` 绝对基准漂移影响）。
 */
class KeepAlive(
    private val displayId: Int,
    private val intervalMs: Long = INTERVAL_MS,
) {
    companion object {
        private const val TAG = "BetterGI.VD"

        /** 拍子间隔：4s（MAA 同档；< 最短屏幕超时 15s）。 */
        const val INTERVAL_MS: Long = 4_000L

        /** PowerManager.USER_ACTIVITY_EVENT_OTHER = 0（核自 A13/A15 framework）。 */
        private const val USER_ACTIVITY_EVENT_OTHER = 0
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /** ★ #310/M5：外部周期观察者（fps 估计器等共用本线程；handler 生命周期跟随 start/stop）。 */
    private val observers = mutableListOf<ObserverEntry>()

    private class ObserverEntry(val task: Runnable, val periodMs: Long)

    /** 活跃拍数（观测/测试用；@Volatile 供跨线程读）。 */
    @Volatile
    var ticks: Long = 0
        private set

    /** 是否正在跑（@Volatile 跨线程读）。 */
    @Volatile
    var isRunning: Boolean = false
        private set

    /**
     * 启动周期调度（幂等：已跑则 no-op）。
     *
     * @param kick 立即先拍一次（默认 true——启动时游戏刚投上屏，第一拍别等 4s）
     */
    @Synchronized
    fun start(kick: Boolean = true) {
        if (isRunning) {
            Log.d(TAG, "keep-alive 已在跑，start 幂等忽略")
            return
        }
        val t = HandlerThread("BgiVdKeepAlive").also { it.start() }
        thread = t
        handler = Handler(t.looper)
        isRunning = true
        // ★ #310/M5：start（重新）挂上已注册的观察者（stop 会摘掉它们的任务）
        for (o in observers) scheduleObserver(o, 0L)
        Log.i(TAG, "keep-alive 启动（displayId=$displayId, 每 ${intervalMs}ms）")
        if (kick) scheduleTick(0L) else scheduleTick(intervalMs)
    }

    /** 停止调度并退出线程（幂等：未跑则 no-op）。 */
    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        handler?.removeCallbacksAndMessages(null)
        handler = null
        thread?.quitSafely()
        thread = null
        Log.i(TAG, "keep-alive 停止（共 $ticks 拍）")
    }

    /**
     * ★ #310/M5：注册外部周期观察者（本 HandlerThread 上 postDelayed 自续，不新建线程）。
     * 注册时若已在跑则立即起拍；stop→start 循环会自动重挂。异常吞掉（观察者只做采样，
     * 不许拖垮 keep-alive 拍子）。
     */
    @Synchronized
    fun addPeriodicObserver(observer: (wallMs: Long) -> Unit, periodMs: Long) {
        val entry = ObserverEntry(
            task = Runnable {
                runCatching { observer(android.os.SystemClock.elapsedRealtime()) }
            },
            periodMs = periodMs,
        )
        observers += entry
        if (isRunning) scheduleObserver(entry, 0L)
    }

    private fun scheduleObserver(entry: ObserverEntry, delayMs: Long) {
        val h = handler ?: return
        h.postDelayed({
            entry.task.run()
            if (isRunning) scheduleObserver(entry, entry.periodMs)
        }, delayMs)
    }

    private fun scheduleTick(delayMs: Long) {
        val h = handler ?: return
        h.postDelayed({
            if (!isRunning) return@postDelayed
            tick()
            ticks++
            scheduleTick(intervalMs)
        }, delayMs)
    }

    /** 单拍：userActivity 反射调用（三参/四参自动探测，全 runCatching）。 */
    private fun tick() {
        val pm = powerManager() ?: return
        val now = SystemClock.uptimeMillis()
        // 四参版（displayId, when, event, flags）—— 提权进程主路径
        pm.javaClass.methods
            .firstOrNull {
                it.name == "userActivity" && it.parameterTypes.size == 4 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            ?.let { m4 ->
                runCatching {
                    m4.isAccessible = true
                    m4.invoke(pm, displayId, now, USER_ACTIVITY_EVENT_OTHER, 0)
                }.onSuccess {
                    return
                }.onFailure { e ->
                    Log.w(TAG, "userActivity(displayId,...) 失败: ${e.message}")
                }
            }
        // 三参版（when, event, flags）—— 兜底（app 进程语义，作用于默认显示器）
        runCatching {
            val m3 = pm.javaClass.methods.first {
                it.name == "userActivity" && it.parameterTypes.size == 3
            }
            m3.isAccessible = true
            m3.invoke(pm, now, USER_ACTIVITY_EVENT_OTHER, 0)
        }.onFailure { e ->
            Log.w(TAG, "userActivity(when,event,flags) 兜底也失败: ${e.message}")
        }
    }

    // ── 可注入面（单测 / 服务进程差异）────────────────────────────────────

    /**
     * PowerManager 获取器。生产默认从 DisplayManager 同源的 app context 拿；
     * 单测注入假实现（JVM 测不了 PowerManager，真身调用在设备上验证）。
     */
    var powerManagerProvider: () -> PowerManager? = { null }

    /**
     * 生产入口注入：displayId + 已绑定的 context（服务进程 Context 或
     * FakeContext——`getSystemService(POWER_SERVICE)` 在提权进程可用）。
     */
    fun attachContext(context: android.content.Context) {
        powerManagerProvider = {
            runCatching {
                context.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager
            }.getOrNull()
        }
    }

    private fun powerManager(): PowerManager? = try {
        powerManagerProvider()
    } catch (_: Throwable) {
        null
    }
}
