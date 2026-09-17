package com.bettergi.pocket.overlay

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.bettergi.pocket.feature.autoskip.AutoSkipEvents

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
 */
class OverlayBridge(private val context: Context) : AutoSkipEvents {

    private val uri: Uri = Uri.parse("content://${context.packageName}$AUTHORITY_SUFFIX")

    fun show() {
        call(A11yOverlayRuntime.M_SHOW, null)
    }

    fun hide() {
        call(A11yOverlayRuntime.M_HIDE, null)
    }

    fun collapse() {
        call(A11yOverlayRuntime.M_COLLAPSE, null)
    }

    fun updateScanProgress(text: String) {
        call(A11yOverlayRuntime.M_PROGRESS, Bundle().apply { putString(A11yOverlayRuntime.K_TEXT, text) })
    }

    /**
     * 点击前把悬浮窗设为可穿透（命中窗口才需要）。
     *
     * ⚠️ 这是**逐点击**调用，是全桥唯一的"高频"路径。之所以仍走 IPC 而不是把它挪进无障碍进程的
     * 注入函数：旧实现里"是否需要穿透"依赖调用方拿到返回值来决定何时还原（扫描/自动对话两处逻辑不同），
     * 搬到注入侧会把这两处语义悄悄改掉。实测一次 binder 往返在毫秒级，相对点击间隔可忽略。
     */
    fun prepareClickPassthrough(x: Int, y: Int): Boolean =
        call(A11yOverlayRuntime.M_PT_PREPARE, Bundle().apply {
            putInt(A11yOverlayRuntime.K_X, x)
            putInt(A11yOverlayRuntime.K_Y, y)
        })?.getBoolean(A11yOverlayRuntime.K_OK) == true

    fun restoreClickPassthrough() {
        call(A11yOverlayRuntime.M_PT_RESTORE, null)
    }

    /** 扫描期整体切换穿透（低频：一轮扫描 1~2 次）。 */
    fun setScanClickThrough(enabled: Boolean, hidden: Boolean = false) {
        call(A11yOverlayRuntime.M_CLICK_THROUGH, Bundle().apply {
            putBoolean(A11yOverlayRuntime.K_ENABLED, enabled)
            putBoolean(A11yOverlayRuntime.K_HIDDEN, hidden)
        })
    }

    override fun onTalkHistoryMatched() {
        call(A11yOverlayRuntime.M_EVENT, Bundle().apply {
            putString(A11yOverlayRuntime.K_KIND, A11yOverlayRuntime.EVENT_TALK)
        })
    }

    override fun onChatIconsRecognized(count: Int, topX: Int, topY: Int) {
        call(A11yOverlayRuntime.M_EVENT, Bundle().apply {
            putString(A11yOverlayRuntime.K_KIND, A11yOverlayRuntime.EVENT_CHAT_ICONS)
            putInt(A11yOverlayRuntime.K_COUNT, count)
            putInt(A11yOverlayRuntime.K_X, topX)
            putInt(A11yOverlayRuntime.K_Y, topY)
        })
    }

    override fun onChatIconClicked(x: Int, y: Int) {
        call(A11yOverlayRuntime.M_EVENT, Bundle().apply {
            putString(A11yOverlayRuntime.K_KIND, A11yOverlayRuntime.EVENT_CHAT_CLICK)
            putInt(A11yOverlayRuntime.K_X, x)
            putInt(A11yOverlayRuntime.K_Y, y)
        })
    }

    private fun call(method: String, extras: Bundle?): Bundle? = try {
        context.contentResolver.call(uri, method, null, extras)
    } catch (e: Exception) {
        Log.w(TAG, "overlay bridge call $method failed", e)
        null
    }

    private companion object {
        const val TAG = "BetterGI.OverlayBridge"

        /** 与 `AccessibilityBridgeProvider` 的 authority 后缀一致（无障碍进程）。 */
        const val AUTHORITY_SUFFIX = ".a11y"
    }
}
