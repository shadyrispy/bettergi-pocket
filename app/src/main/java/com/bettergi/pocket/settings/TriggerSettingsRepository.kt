package com.bettergi.pocket.settings

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 设置仓库（**主进程权威**，落 `SharedPreferences("trigger_settings")`）。
 *
 * 跨进程说明（2026-09-18 悬浮窗宿主迁移后）：无障碍进程里的悬浮窗不再直连本类，
 * 而是用 [BridgeSettingsRepository] 经 `ContentProvider` 代理；本类每次 [update] 成功后
 * 会 `notifyChange` 一次，无障碍侧据此重新拉取并刷新界面。
 *
 * ⚠️ **进程内单例**：主进程里 `TriggerForegroundService` 与设置桥 Provider 必须拿到**同一个实例**，
 * 否则两边各有一份 `current` 缓存 ⇒ 自己和自己打架。统一走 [app] 取。
 */
class TriggerSettingsRepository(context: Context) : SettingsGateway {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(TriggerSettings) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var current: TriggerSettings = readFromPrefs()

    override fun get(): TriggerSettings = current

    override fun addListener(listener: (TriggerSettings) -> Unit) {
        listeners.add(listener)
        mainHandler.post { listener(current) }
    }

    override fun removeListener(listener: (TriggerSettings) -> Unit) {
        listeners.remove(listener)
    }

    override fun setScreenShareEnabled(enabled: Boolean) {
        update { it.copy(screenShareEnabled = enabled) }
    }

    override fun setAutoPickEnabled(enabled: Boolean) {
        update { it.copy(autoPickEnabled = enabled) }
    }

    override fun setAutoSkipEnabled(enabled: Boolean) {
        update { it.copy(autoSkipEnabled = enabled) }
    }

    override fun setQuickSkipDialogueEnabled(enabled: Boolean) {
        update { it.copy(quickSkipDialogueEnabled = enabled) }
    }

    override fun setAutoLaunchGenshinEnabled(enabled: Boolean) {
        update { it.copy(autoLaunchGenshinEnabled = enabled) }
    }

    override fun setScanEnabled(enabled: Boolean) {
        update { it.copy(scanEnabled = enabled) }
    }

    override fun setScanFlow(flow: String) {
        update { it.copy(scanFlow = flow) }
    }

    override fun setScanMaxPages(pages: Int) {
        update { it.copy(scanMaxPages = pages) }
    }

    private fun update(transform: (TriggerSettings) -> TriggerSettings) {
        val newValue: TriggerSettings
        synchronized(lock) {
            val old = current
            val updated = transform(old)
            if (updated == old) return
            current = updated
            newValue = updated
            prefs.edit()
                .putBoolean(KEY_SCREEN_SHARE, updated.screenShareEnabled)
                .putBoolean(KEY_AUTO_PICK, updated.autoPickEnabled)
                .putBoolean(KEY_AUTO_SKIP, updated.autoSkipEnabled)
                .putBoolean(KEY_QUICK_SKIP, updated.quickSkipDialogueEnabled)
                .putBoolean(KEY_AUTO_LAUNCH_GENSHIN, updated.autoLaunchGenshinEnabled)
                .putBoolean(KEY_SCAN, updated.scanEnabled)
                .putString(KEY_SCAN_FLOW, updated.scanFlow)
                .putInt(KEY_SCAN_MAX_PAGES, updated.scanMaxPages)
                .apply()
        }
        // 无障碍进程观察这个 URI 来刷新悬浮窗（同 UID 跨进程，notifyChange 可送达）
        runCatching { appContext.contentResolver.notifyChange(settingsUri(appContext), null) }
        listeners.forEach { listener ->
            mainHandler.post { listener(newValue) }
        }
    }

    /**
     * `screenShareEnabled` **不读盘**：投影授权无法跨进程重启存活，进程重启后必须重新授权，
     * 因此这里恒定从 false 起（与迁移前语义一致）。
     */
    private fun readFromPrefs(): TriggerSettings = TriggerSettings(
        screenShareEnabled = false,
        autoPickEnabled = prefs.getBoolean(KEY_AUTO_PICK, false),
        autoSkipEnabled = prefs.getBoolean(KEY_AUTO_SKIP, false),
        quickSkipDialogueEnabled = prefs.getBoolean(KEY_QUICK_SKIP, true),
        autoLaunchGenshinEnabled = prefs.getBoolean(KEY_AUTO_LAUNCH_GENSHIN, false),
        scanEnabled = prefs.getBoolean(KEY_SCAN, false),
        scanFlow = prefs.getString(KEY_SCAN_FLOW, "artifact_scan") ?: "artifact_scan",
        scanMaxPages = prefs.getInt(KEY_SCAN_MAX_PAGES, 0),
    )

    companion object {
        const val AUTHORITY_SUFFIX = ".settings"
        private const val PREFS_NAME = "trigger_settings"
        private const val KEY_SCREEN_SHARE = "screenShareEnabled"
        private const val KEY_AUTO_PICK = "autoPickEnabled"
        private const val KEY_AUTO_SKIP = "autoSkipEnabled"
        private const val KEY_QUICK_SKIP = "quickSkipDialogueEnabled"
        private const val KEY_AUTO_LAUNCH_GENSHIN = "autoLaunchGenshinEnabled"
        private const val KEY_SCAN = "scanEnabled"
        private const val KEY_SCAN_FLOW = "scanFlow"
        private const val KEY_SCAN_MAX_PAGES = "scanMaxPages"

        fun settingsUri(context: Context): Uri =
            Uri.parse("content://${context.packageName}$AUTHORITY_SUFFIX/settings")

        @Volatile
        private var singleton: TriggerSettingsRepository? = null

        /** 主进程内的唯一实例（服务与设置桥 Provider 共用）。 */
        fun app(context: Context): TriggerSettingsRepository =
            singleton ?: synchronized(this) {
                singleton ?: TriggerSettingsRepository(context.applicationContext).also { singleton = it }
            }

        /** 设置桥 Provider 用；主进程尚未创建过则为 null（不主动造，避免与服务分叉）。 */
        fun currentAppInstance(): TriggerSettingsRepository? = singleton
    }
}
