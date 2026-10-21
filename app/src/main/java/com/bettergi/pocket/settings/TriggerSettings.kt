package com.bettergi.pocket.settings

data class TriggerSettings(
    val screenShareEnabled: Boolean,
    val autoPickEnabled: Boolean,
    val autoSkipEnabled: Boolean,
    val quickSkipDialogueEnabled: Boolean,
    val autoLaunchGenshinEnabled: Boolean = false,
    val scanEnabled: Boolean = false,
    /** 扫描流程（悬浮窗扫描控制区选择）。 */
    val scanFlow: String = "artifact_scan",
    /** 翻页早停页数（0 = 不限，悬浮窗输入）。 */
    val scanMaxPages: Int = 0,
)
