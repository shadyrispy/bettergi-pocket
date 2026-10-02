package com.bettergi.pocket.settings

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.bettergi.pocket.log.RecognitionLog
import com.bettergi.pocket.service.TriggerForegroundService

/**
 * **无障碍进程 → 主进程**的设置桥（2026-09-18 悬浮窗宿主迁移）。
 *
 * 方向说明（两个 Provider 分工，勿混）：
 * - `AccessibilityBridgeProvider`（`.a11y`，跑在无障碍进程）：**主进程 → 无障碍**，下发点击/滑动/悬浮窗指令。
 * - 本类（`.settings`，跑在主进程）：**无障碍 → 主进程**，读设置 / 写设置 / 请求导出 / 请求退出。
 *
 * 只做转发，不持有状态：设置权威仍在 [TriggerSettingsRepository]（进程内单例 [TriggerSettingsRepository.currentAppInstance]）。
 */
class SettingsBridgeProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = when (method) {
        METHOD_GET -> Bundle().apply {
            putBundle(KEY_SETTINGS, SettingsWire.toBundle(repo()?.get() ?: SettingsWire.DEFAULTS))
        }
        METHOD_SET -> Bundle().apply {
            putBoolean(KEY_OK, applyField(arg, extras))
        }
        METHOD_SHARE_GOOD -> Bundle().apply {
            putBoolean(KEY_OK, forward(TriggerForegroundService.ACTION_SHARE_GOOD))
        }
        METHOD_STOP -> Bundle().apply {
            putBoolean(KEY_OK, forward(TriggerForegroundService.ACTION_STOP))
        }
        // 无障碍进程写的日志：转发回主进程的单一日志源（否则会被镜像 replaceAll 冲掉）
        METHOD_LOG_APPEND -> Bundle().apply {
            putBoolean(
                KEY_OK,
                RecognitionLog.appendWire(
                    extras?.getString(KEY_TAG).orEmpty(),
                    extras?.getString(KEY_LEVEL).orEmpty(),
                    extras?.getString(KEY_MESSAGE).orEmpty(),
                ),
            )
        }
        // 识别日志镜像：日志缓冲在主进程，无障碍进程的日志窗按需拉一份
        METHOD_LOG_SNAPSHOT -> Bundle().apply {
            putStringArray(
                KEY_ENTRIES,
                RecognitionLog.snapshotAll().map { RecognitionLog.encode(it) }.toTypedArray(),
            )
        }
        else -> Bundle()
    }

    /**
     * 写设置。⚠️ 主进程尚未起过服务时 `currentAppInstance()` 为空 —— 此时**不新建实例**
     * （新建会和服务的实例分叉），直接返回失败；无障碍侧的下一次刷新会把它纠正回来。
     */
    private fun applyField(field: String?, extras: Bundle?): Boolean {
        val r = repo() ?: return false
        when (field) {
            SettingsWire.F_SCREEN_SHARE ->
                r.setScreenShareEnabled(extras?.getBoolean(KEY_VALUE, false) == true)
            SettingsWire.F_AUTO_PICK ->
                r.setAutoPickEnabled(extras?.getBoolean(KEY_VALUE, false) == true)
            SettingsWire.F_AUTO_SKIP ->
                r.setAutoSkipEnabled(extras?.getBoolean(KEY_VALUE, false) == true)
            SettingsWire.F_QUICK_SKIP ->
                r.setQuickSkipDialogueEnabled(extras?.getBoolean(KEY_VALUE, false) == true)
            SettingsWire.F_AUTO_LAUNCH ->
                r.setAutoLaunchGenshinEnabled(extras?.getBoolean(KEY_VALUE, false) == true)
            SettingsWire.F_SCAN ->
                r.setScanEnabled(extras?.getBoolean(KEY_VALUE, false) == true)
            SettingsWire.F_SCAN_FLOW -> r.setScanFlow(extras?.getString(KEY_VALUE, "artifact_scan") ?: "artifact_scan")
            SettingsWire.F_SCAN_MAX_PAGES -> r.setScanMaxPages(extras?.getInt(KEY_VALUE, 0) ?: 0)
            else -> return false
        }
        return true
    }

    /**
     * 把请求转成一次服务指令。服务此刻已在跑（前台服务）⇒ `startService` 不受后台启动限制；
     * 服务没在跑则忽略（悬浮窗此时也不存在）。
     */
    private fun forward(action: String): Boolean {
        val ctx = context ?: return false
        return try {
            ctx.startService(Intent(ctx, TriggerForegroundService::class.java).setAction(action))
            true
        } catch (e: Exception) {
            Log.w(TAG, "forward $action failed", e)
            false
        }
    }

    private fun repo(): TriggerSettingsRepository? = TriggerSettingsRepository.currentAppInstance()

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
        private const val TAG = "BetterGI.SettingsBridge"

        const val METHOD_GET = "settings_get"
        const val METHOD_SET = "settings_set"
        const val METHOD_SHARE_GOOD = "overlay_share_good"
        const val METHOD_STOP = "overlay_stop"
        const val METHOD_LOG_APPEND = "log_append"
        const val METHOD_LOG_SNAPSHOT = "log_snapshot"

        /** 供 `:a11y` 侧直接构建 URI（避免两侧各写一遍字符串）。 */
        fun uri(context: Context): Uri = TriggerSettingsRepository.settingsUri(context)

        const val KEY_SETTINGS = "settings"
        const val KEY_OK = "ok"
        const val KEY_VALUE = "value"
        const val KEY_ENTRIES = "entries"
        const val KEY_TAG = "tag"
        const val KEY_LEVEL = "level"
        const val KEY_MESSAGE = "message"
    }
}
