package com.bettergi.pocket.notice

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.bettergi.pocket.AppForeground
import com.bettergi.pocket.bridge.A11yProtocol
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 提醒的**展位路由**（app 进程）。
 *
 * 规则很简单：**谁在前台谁显示，绝不重复弹两次**。
 * - 管理器（Activity）在前台 ⇒ 交给管理器横幅（它自己注册/注销）；
 * - 否则 ⇒ 经无障碍桥推到悬浮窗提醒条（用户玩游戏时唯一能看见的地方）。
 *
 * 之所以不让两边都显示：管理器是半透明 Activity，悬浮窗还在它后面 —— 两边都收会重复。
 */
object NoticeRouter : NoticeCenter.Sink {

    private const val TAG = "BetterGI.Notice"

    @Volatile
    private var appContext: Context? = null

    private val managerSinks = CopyOnWriteArrayList<NoticeCenter.Sink>()

    /** 进程启动时调用一次。 */
    fun install(context: Context) {
        appContext = context.applicationContext
        NoticeCenter.addSink(this)
    }

    /** 管理器 `onResume` / `onPause` 时注册与注销。 */
    fun attachManager(sink: NoticeCenter.Sink) { managerSinks.add(sink) }

    fun detachManager(sink: NoticeCenter.Sink) { managerSinks.remove(sink) }

    override fun show(notice: NoticeCenter.Notice) {
        val sinks = managerSinks
        if (AppForeground.isForeground && sinks.isNotEmpty()) {
            sinks.forEach { runCatching { it.show(notice) } }
            return
        }
        pushToOverlay(notice)
    }

    /**
     * ★ A26：提醒条推送改异步 fire-and-forget + 同文本去重。show() 常在主线程被调
     * （NoticeCenter 的 sink 分发），同步 binder 会在 :a11y 忙时把主进程主线程拉住（ANR 面）。
     * 提醒没有确认语义（NoticeCenter 已做 2s 去重），后到覆盖先到即可；单线程执行器保序。
     */
    private val pushExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "notice-router-push").apply { isDaemon = true }
    }

    @Volatile
    private var lastPushed: String? = null

    private fun pushToOverlay(notice: NoticeCenter.Notice) {
        val ctx = appContext ?: return
        // 工单 D：authority URI 与 method 名改用 bridge/A11yProtocol 的常量与工厂（值不变）。
        val uri = A11yProtocol.a11yUri(ctx)
        val signature = "${notice.level.name}|${notice.text}"
        if (lastPushed == signature) return // 与上一条相同 ⇒ 去重（后到覆盖先到，重复值无意义）
        lastPushed = signature
        val extras = Bundle().apply {
            putString(A11yProtocol.K_LEVEL, notice.level.name)
            putString(A11yProtocol.K_TEXT, notice.text)
        }
        pushExecutor.execute {
            try {
                ctx.contentResolver.call(uri, A11yProtocol.M_NOTICE, null, extras)
            } catch (e: Exception) {
                // 无障碍没连上时悬浮窗本就不存在 —— 提醒已在日志里留痕，这里静默降级
                Log.w(TAG, "notice push failed: ${notice.text}", e)
            }
        }
    }
}
