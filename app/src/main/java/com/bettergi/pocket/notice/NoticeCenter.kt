package com.bettergi.pocket.notice

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.bettergi.pocket.log.RecognitionLog
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 通用提醒（2026-09-18）：**全应用唯一的提醒通路**。
 *
 * 为什么要有它：原先有 16 处 `Toast.makeText` 散在 5 个文件里，问题不是"不整齐"而是：
 * - **Android 12+ 后台应用的 Toast 会被系统压制** —— 而本应用的提醒大多来自后台服务与无障碍进程；
 * - 脚本（DSL 流程）无法用 Toast 对用户说重点信息；
 * - 无法分级、去重、回溯（不落日志，事后查不到）。
 *
 * 职责边界（本类只做"决策"，不做"画界面"）：
 * 1. 分级 [NoticeLevel]；
 * 2. **去重**：同一文本 2s 内只出一次；`onceKey` 一辈子只出一次（存 prefs）；
 * 3. **落日志**：复用识别日志（它已有跨进程镜像，所以无障碍进程的日志窗也能回溯）；
 * 4. 把 [Notice] 交给已注册的 [Sink]（悬浮窗 / 管理器横幅各自注册，**谁在前台谁显示**，
 *    由注册方自己判断，本类不猜）。
 *
 * ⚠️ 约定：**不要再新增裸 Toast**；一次性提示也走 `onceKey`。
 */
object NoticeCenter {

    /** 提醒级别。三档即可 —— 居中模态已被否决（会盖住游戏画面、挡操作）。 */
    enum class Level { INFO, WARN, ERROR }

    data class Notice(val level: Level, val text: String, val atMs: Long)

    /** 展示位。注册方负责"我是否在前台"，[NoticeCenter] 只负责把提醒推给它。 */
    interface Sink {
        fun show(notice: Notice)
    }

    private const val PREFS = "notice"
    private const val DEDUPE_MS = 2000L
    private const val MAX_DEDUPE_KEYS = 64

    @Volatile
    private var prefs: SharedPreferences? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sinks = CopyOnWriteArrayList<Sink>()
    private val lastAt = HashMap<String, Long>()

    /** 进程启动时注入一次（`PocketApplication.onCreate`）。 */
    fun install(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun addSink(sink: Sink) { sinks.add(sink) }

    fun removeSink(sink: Sink) { sinks.remove(sink) }

    /**
     * 发一条提醒。
     * @param onceKey 非空 ⇒ 该 key 一辈子只提示一次（存 prefs）；用于"首次引导"类提示。
     * @return true = 真的展示出去了；被去重 / onceKey 拦掉返回 false。
     */
    fun post(level: Level, text: String, onceKey: String? = null): Boolean {
        val body = text.trim()
        if (body.isEmpty()) return false

        if (onceKey != null) {
            val p = prefs ?: return false
            val k = "once_$onceKey"
            if (p.getBoolean(k, false)) return false
            p.edit().putBoolean(k, true).apply()
        }

        val now = System.currentTimeMillis()
        synchronized(lastAt) {
            val prev = lastAt[body]
            if (prev != null && now - prev < DEDUPE_MS) return false
            if (lastAt.size > MAX_DEDUPE_KEYS) lastAt.clear()
            lastAt[body] = now
        }

        // 单一日志源：走识别日志（已有跨进程镜像 ⇒ 无障碍进程日志窗可回溯）
        RecognitionLog.log(
            RecognitionLog.Tag.APP,
            if (level == Level.INFO) RecognitionLog.Level.I else RecognitionLog.Level.W,
            body,
        )

        val notice = Notice(level, body, now)
        mainHandler.post { sinks.forEach { runCatching { it.show(notice) } } }
        return true
    }

    /** 便捷重载：信息级。 */
    fun info(text: String) = post(Level.INFO, text)

    /** 便捷重载：告警级。 */
    fun warn(text: String) = post(Level.WARN, text)

    /** 便捷重载：错误级。 */
    fun error(text: String) = post(Level.ERROR, text)
}
