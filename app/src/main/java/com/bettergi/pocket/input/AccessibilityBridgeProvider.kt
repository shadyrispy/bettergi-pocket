package com.bettergi.pocket.input

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * 主进程 → 无障碍进程的指令桥（authority 后缀 `.a11y`，跑在 `:a11y` 进程）。
 *
 * ★ A32：`call()` 顶层兜底 catch —— 桥对面是另一个进程，`handleBridgeCall` / 转发链上任何一处
 * 未包 `runCatching` 的新增分支都会变成**跨进程崩溃**（抛到 binder 侧会把主进程调用线程一起拖炸）。
 * 统一降级为错误 Bundle：`ok=false` + `error`（异常摘要）+ `method`（方法名），调用方
 * （OverlayBridge / InputAccessibilityService.remoteCall）读不到 `ok=true` 即走既有失败/重试路径。
 */
class AccessibilityBridgeProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        return try {
            InputAccessibilityService.handleBridgeCall(method, extras)
        } catch (e: Exception) {
            Log.w(TAG, "bridge call failed: method=$method", e)
            errorBundle(method, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun errorBundle(method: String, error: String): Bundle = Bundle().apply {
        putBoolean(KEY_OK, false)
        putString(KEY_ERROR, error)
        putString(KEY_METHOD, method)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        private const val TAG = "BetterGI.A11yBridge"

        // 错误 Bundle 键（★ A32 只加不改；与 SettingsBridgeProvider 同名同义，协议注释对齐）
        const val KEY_OK = "ok"
        const val KEY_ERROR = "error"
        const val KEY_METHOD = "method"
    }
}
