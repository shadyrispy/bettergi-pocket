package com.bettergi.pocket.scan

import android.util.Log

/**
 * ★ 2026-10-16 #309：**游戏死亡哨兵**（前台闸的连续拍确认层）——借鉴 MAA-Meow `AppWatchdog`
 * 的保守三原则，动作改走我们自己的干净收尾（不塞黑帧：引擎每步都查 stopRequested，
 * 置位即等效于它的"黑帧止血"，且省掉整套帧缓冲可写假设）。
 *
 * **动机**（华为 2244 实测）：游戏被系统杀/被顶号闪退后，投影流可能仍吐静止缓存帧，
 * `freshFrame` 的 #196 决策表（识别侧不回退旧帧）会让引擎反复取帧失败/重试，直到
 * 会话看门狗 120s 兜底——中间是两分钟的空转窗口，且有"对着死画面 OCR 出垃圾读数"的
 * 脏数据风险（若投影恰好还出帧）。本哨兵用**前台状态连续 2 拍非游戏**（≈10s）提前了断。
 *
 * **保守三原则**（逐条对照 MAA-Meow `AppWatchdog`/`StaleFrameGuard`/`ProcessLiveness`）：
 * 1. **判死要确认**：连续 [CONFIRM_POLLS] 拍 NOT_GAME 才算死——"MAA 登录失败重启游戏时
 *    进程会消失一两秒"的瞬态不触发（我们的 S4b reload / SDK 登录页同款瞬态真实存在）。
 * 2. **判不出 = 活着（不清零也不计次）**：UNKNOWN（无障碍断开/桥取不到/包名 null）既不推进
 *    死亡计数、也不清零——限频跳过的语义（MAA `observe()` 返回 null 的处理）。清零是
 *    IN_GAME 专属。
 * 3. **预热期不判死**：首轮见到 IN_GAME 之前**恒不判死**——起跑链里游戏本来就不在前台
 *    （S1 版号屏/S2 加载/S3 标题屏都是 NOT_GAME），没有"曾经活着"就没有"死了"可言
 *    （与 MAA 的 repin 宽限同思想，但更简单：看不到游戏在前台 = 还没开始）。
 *
 * **纯逻辑、无 Android 依赖**（除了 Log），判定表全部可单测；调用方（ScriptRunner 看门狗
 * 线程）每拍喂一次状态，按 [shouldAbort] 处置。
 */
internal class GameDeathSentinel(
    /** 连续多少拍 NOT_GAME 才判死。2 拍 × 5s/拍 ≈ 10s（MAA：2 次 × 5s 轮询）。 */
    private val confirmPolls: Int = CONFIRM_POLLS,
) {
    enum class Status { IN_GAME, NOT_GAME, UNKNOWN }

    private var confirmedInGameOnce = false
    private var notGameStreak = 0
    private var aborted = false

    /**
     * 喂一拍。返回 true = 应中止整轮（此后恒 true，幂等）。
     * ⚠️ 只做判定；置 stopRequested/导出/通知是调用方的事（与看门狗既有动作同路）。
     */
    fun onTick(status: Status): Boolean {
        if (aborted) return true
        when (status) {
            Status.IN_GAME -> {
                // 起跑预热结束的锚点：第一次见到游戏在前台，哨兵才"上岗"。
                confirmedInGameOnce = true
                notGameStreak = 0
            }
            Status.NOT_GAME -> {
                // 上岗前不判死（见类 KDoc 原则 3）。
                if (!confirmedInGameOnce) return false
                notGameStreak++
                if (notGameStreak >= confirmPolls) {
                    aborted = true
                    Log.e(
                        TAG,
                        "游戏死亡哨兵：连续 $notGameStreak 拍前台不是原神 ⇒ 判死亡/切走，" +
                            "中止本轮并抢救导出（每拍 ${POLL_HINT_MS}ms）",
                    )
                }
            }
            Status.UNKNOWN -> {
                // 判不出：不计次也不清零（原则 2）——但上岗锚点保持已有状态。
            }
        }
        return aborted
    }

    /** 仅供测试/诊断：当前死亡计数。 */
    fun notGameStreakForTest(): Int = notGameStreak

    companion object {
        private const val TAG = "BetterGI.ScanRunner"
        const val CONFIRM_POLLS = 2
        /** 喂拍节奏提示（实际由看门狗线程的 5s sleep 决定，这里只进日志文案）。 */
        const val POLL_HINT_MS = 5_000L

        /**
         * 把 [InputAccessibilityService.isGenshinInForeground] 的三态折叠进 [Status]。
         * null（无障碍未连接/未观察到窗口）⇒ UNKNOWN——**这正是原则 2 的入口**：
         * 无障碍服务本身掉线不是"游戏死了"的证据。
         */
        fun fold(foreground: Boolean?): Status = when (foreground) {
            true -> Status.IN_GAME
            false -> Status.NOT_GAME
            null -> Status.UNKNOWN
        }
    }
}
