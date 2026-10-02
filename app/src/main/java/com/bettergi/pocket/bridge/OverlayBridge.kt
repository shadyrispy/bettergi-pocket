package com.bettergi.pocket.bridge

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.bettergi.pocket.feature.autoskip.AutoSkipEvents
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * **主进程侧的悬浮窗门面**（2026-09-18 宿主迁移）。
 *
 * 悬浮窗已搬到无障碍进程（`TYPE_ACCESSIBILITY_OVERLAY` 只有无障碍服务建得了），
 * 但扫描引擎 / 自动对话 / 前台服务都在主进程，且它们都依赖"悬浮窗"做三件事：
 * 收面板、报进度、**点击前把窗口设成可穿透**。
 *
 * 本类保持与旧 `OverlayWindowController` **同名的方法签名**，把调用转发到无障碍进程，
 * 于是调用点（`ScriptRunner` / `AccessibilityAutomationController` / `TriggerForegroundService`
 * / `AutoSkipFeature`）只需换类型，逻辑一行不动。
 *
 * 无障碍未连接时：`call` 返回 null ⇒ 各方法静默返回 false / Unit（此时也不会有悬浮窗遮挡，
 * 穿透请求本就无意义）。
 *
 * ★ A26 主线程同步 binder 限流：调用分两档——
 * - **异步 fire-and-forget**（[asyncCall]）：progress / capture 状态 / 自动对话事件这类
 *   高频低危调用。它们没有"必须拿到确认才能继续"的语义，改在单线程后台执行器上发，
 *   调用线程（扫描/自动对话的主线程）零等待；执行器**单线程**保序（talk → icons → click
 *   的先后到达顺序不变），并按"后到覆盖先到 + 相同值去重"缓存状态（[asyncCache]），
 *   对端繁忙时排队只有最新值有意义，堆积的旧值直接被去重丢弃。
 * - **同步确认**：click/restore 穿透与 show/hide/collapse 等低频控制。穿透是逐点击调用，
 *   返回值决定调用方何时还原窗口（语义见 [prepareClickPassthrough]），必须同步。
 *   同步链的等待上限由 :a11y 侧 [A11yOverlayRuntime] 的主线程转交 latch（已缩到 500ms）
 *   与 binder 自身超时兜底，超时表现为 ok=false ⇒ 调用方走既有重试，不会挂死。
 */
class OverlayBridge(private val context: Context) : AutoSkipEvents {

    private val uri: Uri = A11yProtocol.a11yUri(context)

    /** 异步桥调用的执行器：单线程（保序）+ daemon（不拦进程退出）。 */
    private val asyncExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "overlay-bridge-async").apply { isDaemon = true }
    }

    /** 异步调用的状态缓存：同 method 相同载荷直接跳过；后到覆盖先到。 */
    private val asyncCache = HashMap<String, String>()

    fun show() {
        call(A11yProtocol.M_SHOW, null)
    }

    fun hide() {
        call(A11yProtocol.M_HIDE, null)
    }

    fun collapse() {
        call(A11yProtocol.M_COLLAPSE, null)
    }

    /** 抓包会话状态推到面板（会话在主进程、面板在 `:a11y`，两边不共享对象）。★ A26：异步 + 去重。 */
    fun updateCaptureStatus(text: String, running: Boolean) {
        asyncCall(A11yProtocol.M_CAPTURE, Bundle().apply {
            putString(A11yProtocol.K_TEXT, text)
            putBoolean(A11yProtocol.K_RUNNING, running)
        })
    }

    /** ★ A26：异步 + 去重（扫描期每秒多次，相同文本无需重复过桥）。 */
    fun updateScanProgress(text: String) {
        asyncCall(A11yProtocol.M_PROGRESS, Bundle().apply { putString(A11yProtocol.K_TEXT, text) })
    }

    /**
     * 点击前把悬浮窗设为可穿透（命中窗口才需要）。
     *
     * ⚠️ 这是**逐点击**调用，是全桥唯一的"高频"路径。之所以仍走 IPC 而不是把它挪进无障碍进程的
     * 注入函数：旧实现里"是否需要穿透"依赖调用方拿到返回值来决定何时还原（扫描/自动对话两处逻辑不同），
     * 搬到注入侧会把这两处语义悄悄改掉。实测一次 binder 往返在毫秒级，相对点击间隔可忽略。
     * ★ A26：保留同步，但 :a11y 侧主线程转交 latch 已缩到 500ms —— 对端主线程忙时返回
     *   false（"对端忙"降级），调用方走既有重试路径，而不是挂满 2s 拖出 ANR。
     */
    fun prepareClickPassthrough(x: Int, y: Int): Boolean =
        call(A11yProtocol.M_PT_PREPARE, Bundle().apply {
            putInt(A11yProtocol.K_X, x)
            putInt(A11yProtocol.K_Y, y)
        })?.getBoolean(A11yProtocol.K_OK) == true

    fun restoreClickPassthrough() {
        call(A11yProtocol.M_PT_RESTORE, null)
    }

    /** 扫描期整体切换穿透（低频：一轮扫描 1~2 次）。 */
    fun setScanClickThrough(enabled: Boolean, hidden: Boolean = false) {
        call(A11yProtocol.M_CLICK_THROUGH, Bundle().apply {
            putBoolean(A11yProtocol.K_ENABLED, enabled)
            putBoolean(A11yProtocol.K_HIDDEN, hidden)
        })
    }

    // ---- 自动对话事件（AutoSkipEvents）：★ A26 改异步 —— 每秒多次、无确认语义；
    //      单线程执行器保序（talk → icons → click 的到达顺序不变）。 ----

    override fun onTalkHistoryMatched() {
        asyncCall(A11yProtocol.M_EVENT, Bundle().apply {
            putString(A11yProtocol.K_KIND, A11yProtocol.EVENT_TALK)
        })
    }

    override fun onChatIconsRecognized(count: Int, topX: Int, topY: Int) {
        asyncCall(A11yProtocol.M_EVENT, Bundle().apply {
            putString(A11yProtocol.K_KIND, A11yProtocol.EVENT_CHAT_ICONS)
            putInt(A11yProtocol.K_COUNT, count)
            putInt(A11yProtocol.K_X, topX)
            putInt(A11yProtocol.K_Y, topY)
        })
    }

    override fun onChatIconClicked(x: Int, y: Int) {
        asyncCall(A11yProtocol.M_EVENT, Bundle().apply {
            putString(A11yProtocol.K_KIND, A11yProtocol.EVENT_CHAT_CLICK)
            putInt(A11yProtocol.K_X, x)
            putInt(A11yProtocol.K_Y, y)
        })
    }

    /**
     * ★ A26：异步 fire-and-forget。对端（:a11y）繁忙时调用线程不再被同步 binder 拉住；
     * 同 method + 相同 Bundle 摘要（文本/布尔/坐标拼串）直接丢弃（后到覆盖先到的去重面）。
     * Bundle 键不改名 ⇒ 协议对 :a11y 侧完全兼容（:a11y 不感知调用是同步还是异步）。
     */
    private fun asyncCall(method: String, extras: Bundle) {
        val signature = extras.keySet().sorted().joinToString(",") { key ->
            "$key=${extras.get(key)}"
        }
        synchronized(asyncCache) {
            if (asyncCache[method] == signature) return  // 与上一条完全相同 ⇒ 去重
            asyncCache[method] = signature
        }
        asyncExecutor.execute {
            try {
                context.contentResolver.call(uri, method, null, extras)
            } catch (e: Exception) {
                // fire-and-forget：对端没起/无障碍断开时面板本就不存在，记日志降级即可
                Log.w(TAG, "async bridge call $method failed", e)
            }
        }
    }

    private fun call(method: String, extras: Bundle?): Bundle? = try {
        context.contentResolver.call(uri, method, null, extras)
    } catch (e: Exception) {
        Log.w(TAG, "overlay bridge call $method failed", e)
        null
    }

    private companion object {
        const val TAG = "BetterGI.OverlayBridge"
    }
}
