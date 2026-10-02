package com.bettergi.pocket.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.bettergi.pocket.log.RecognitionLog
import com.bettergi.pocket.settings.SettingsGateway
import com.bettergi.pocket.settings.SettingsWire
import com.bettergi.pocket.settings.TriggerSettingsRepository
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

    /**
     * ★ A32：`call()` 顶层兜底 catch —— 本 Provider 挂在主进程、对面是 `:a11y`，任何新增 method
     * 分支漏包 runCatching 都会变成跨进程崩溃。统一降级为错误 Bundle（`ok=false` + `error` +
     * `method`），调用方（BridgeSettingsRepository / OverlayWindowController 的 capture 快照读取）
     * 读不到预期键即走既有降级路径（不崩、保持缓存值）。
     * 同时 else（未知 method）从空 Bundle 改为明确 `ok=false, error=unknown_method` ——
     * 空 Bundle 与"成功但无载荷"同形，调用方无从判别失败。
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = try {
        dispatch(method, arg, extras)
    } catch (e: Exception) {
        Log.w(TAG, "bridge call failed: method=$method", e)
        Bundle().apply {
            putBoolean(KEY_OK, false)
            putString(KEY_ERROR, e.message ?: e.javaClass.simpleName)
            putString(KEY_METHOD, method)
        }
    }

    private fun dispatch(method: String, arg: String?, extras: Bundle?): Bundle = when (method) {
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
        // 抓包：会话与隧道只能在主进程（VPN + 进程静态队列交接），面板在 :a11y
        // ⇒ 开和停都是"过桥发指令"，发起授权那一步也在主进程做（与投影同一条路子）。
        METHOD_CAPTURE_START -> Bundle().apply {
            putBoolean(KEY_OK, forward(TriggerForegroundService.ACTION_CAPTURE_START))
        }
        METHOD_CAPTURE_STOP -> Bundle().apply {
            putBoolean(KEY_OK, forward(TriggerForegroundService.ACTION_CAPTURE_STOP))
        }
        // 开面板时拉一次当前会话状态；之后的变化由主进程经桥推进来
        METHOD_CAPTURE_SNAPSHOT -> Bundle().apply {
            val s = com.bettergi.pocket.pcdata.CaptureSession.ui.value
            putString(KEY_TEXT, s.brief())
            putBoolean(KEY_RUNNING, s.running)
        }
        // 识别日志镜像：日志缓冲在主进程，无障碍进程的日志窗按需拉一份
        METHOD_LOG_SNAPSHOT -> Bundle().apply {
            putStringArray(
                KEY_ENTRIES,
                RecognitionLog.snapshotAll().map { RecognitionLog.encode(it) }.toTypedArray(),
            )
        }
        else -> Bundle().apply {
            // ★ A32：未知 method 不再返回空 Bundle（调用方会把"无键"误当成功）——
            //   明确 ok=false + error=unknown_method，:a11y 侧据此走失败降级。
            putBoolean(KEY_OK, false)
            putString(KEY_ERROR, "unknown_method")
            putString(KEY_METHOD, method)
        }
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
        const val METHOD_CAPTURE_START = "capture_start"
        const val METHOD_CAPTURE_STOP = "capture_stop"
        const val METHOD_CAPTURE_SNAPSHOT = "capture_snapshot"
        const val KEY_TEXT = "text"
        const val KEY_RUNNING = "running"

        /** 供 `:a11y` 侧直接构建 URI（避免两侧各写一遍字符串）。 */
        fun uri(context: Context): Uri = TriggerSettingsRepository.settingsUri(context)

        const val KEY_SETTINGS = "settings"
        const val KEY_OK = "ok"
        /** ★ A32：错误文本键（unknown_method / 异常摘要）。只加不改，旧调用方不读也不受影响。 */
        const val KEY_ERROR = "error"
        /** ★ A32：回显发起 method 名，便于跨进程排障。 */
        const val KEY_METHOD = "method"
        const val KEY_VALUE = "value"
        const val KEY_ENTRIES = "entries"
        const val KEY_TAG = "tag"
        const val KEY_LEVEL = "level"
        const val KEY_MESSAGE = "message"
    }
}
