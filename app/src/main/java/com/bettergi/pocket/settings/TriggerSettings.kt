package com.bettergi.pocket.settings

data class TriggerSettings(
    val screenShareEnabled: Boolean,
    val autoPickEnabled: Boolean,
    val autoSkipEnabled: Boolean,
    val quickSkipDialogueEnabled: Boolean,
    val autoLaunchGenshinEnabled: Boolean = false,
)
