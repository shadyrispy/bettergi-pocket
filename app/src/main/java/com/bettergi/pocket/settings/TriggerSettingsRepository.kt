package com.bettergi.pocket.settings

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

class TriggerSettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(TriggerSettings) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var current: TriggerSettings = readFromPrefs()

    fun get(): TriggerSettings = current

    fun addListener(listener: (TriggerSettings) -> Unit) {
        listeners.add(listener)
        mainHandler.post { listener(current) }
    }

    fun removeListener(listener: (TriggerSettings) -> Unit) {
        listeners.remove(listener)
    }

    fun setScreenShareEnabled(enabled: Boolean) {
        update { it.copy(screenShareEnabled = enabled) }
    }

    fun setAutoPickEnabled(enabled: Boolean) {
        update { it.copy(autoPickEnabled = enabled) }
    }

    fun setAutoSkipEnabled(enabled: Boolean) {
        update { it.copy(autoSkipEnabled = enabled) }
    }

    fun setQuickSkipDialogueEnabled(enabled: Boolean) {
        update { it.copy(quickSkipDialogueEnabled = enabled) }
    }

    fun setAutoLaunchGenshinEnabled(enabled: Boolean) {
        update { it.copy(autoLaunchGenshinEnabled = enabled) }
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
                .apply()
        }
        listeners.forEach { listener ->
            mainHandler.post { listener(newValue) }
        }
    }

    private fun readFromPrefs(): TriggerSettings = TriggerSettings(
        screenShareEnabled = false,
        autoPickEnabled = prefs.getBoolean(KEY_AUTO_PICK, false),
        autoSkipEnabled = prefs.getBoolean(KEY_AUTO_SKIP, false),
        quickSkipDialogueEnabled = prefs.getBoolean(KEY_QUICK_SKIP, true),
        autoLaunchGenshinEnabled = prefs.getBoolean(KEY_AUTO_LAUNCH_GENSHIN, false),
    )

    private companion object {
        const val PREFS_NAME = "trigger_settings"
        const val KEY_SCREEN_SHARE = "screenShareEnabled"
        const val KEY_AUTO_PICK = "autoPickEnabled"
        const val KEY_AUTO_SKIP = "autoSkipEnabled"
        const val KEY_QUICK_SKIP = "quickSkipDialogueEnabled"
        const val KEY_AUTO_LAUNCH_GENSHIN = "autoLaunchGenshinEnabled"
    }
}
