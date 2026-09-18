package com.bettergi.pocket.notice

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.bettergi.pocket.AppForeground
import java.util.concurrent.CopyOnWriteArrayList

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
    private const val A11Y_AUTHORITY_SUFFIX = ".a11y"
    private const val METHOD_NOTICE_PUSH = "notice_push"
    private const val KEY_LEVEL = "level"
    private const val KEY_TEXT = "text"

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

    private fun pushToOverlay(notice: NoticeCenter.Notice) {
        val ctx = appContext ?: return
        val uri = Uri.parse("content://${ctx.packageName}$A11Y_AUTHORITY_SUFFIX")
        val extras = Bundle().apply {
            putString(KEY_LEVEL, notice.level.name)
            putString(KEY_TEXT, notice.text)
        }
        try {
            ctx.contentResolver.call(uri, METHOD_NOTICE_PUSH, null, extras)
        } catch (e: Exception) {
            // 无障碍没连上时悬浮窗本就不存在 —— 提醒已在日志里留痕，这里静默降级
            Log.w(TAG, "notice push failed: ${notice.text}", e)
        }
    }
}
