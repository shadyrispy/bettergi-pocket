package com.bettergi.pocket.genshin

import com.bettergi.pocket.settings.TriggerSettingsRepository

class GenshinLaunchMonitor(
    private val settingsRepository: TriggerSettingsRepository,
    private val launcher: GenshinLauncher,
    private val isGenshinInForeground: () -> Boolean?,
    private val canAutoLaunch: () -> Boolean,
) {
    private var started = false
    private var attempted = false

    fun start() {
        if (started) return
        started = true
        if (!GenshinPackages.shouldAttemptAutoLaunch(
                enabled = settingsRepository.get().autoLaunchGenshinEnabled,
                genshinInForeground = isGenshinInForeground(),
                alreadyAttempted = attempted,
                allowed = canAutoLaunch(),
            )
        ) {
            return
        }
        attempted = true
        launcher.launch()
    }

    fun stop() {
        started = false
    }
}
