package com.bettergi.pocket.settings

/**
 * ★ #310/M0（virtualdisplay 分支，2026-10-16）：运行模式。
 *
 * - [FOREGROUND]：现网形态——MediaProjection 投影 + 无障碍注入，游戏必须前台（默认，行为零变化）。
 * - [BACKGROUND]：虚拟显示器 + Shizuku 提权（后续里程碑落地）；M0 只落数据通道，
 *   没有任何消费方——装配分叉（FrameSource/ActionGateway/Probe 三件套）在 M3 接线。
 */
enum class RunMode(val wire: String) {
    FOREGROUND("foreground"),
    BACKGROUND("background");

    companion object {
        fun fromWire(s: String?): RunMode = if (s == BACKGROUND.wire) BACKGROUND else FOREGROUND
    }
}

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
    /** ★ #310/M0：运行模式。缺省 FOREGROUND（旧 prefs 无此键 ⇒ 行为与改动前逐位一致）。 */
    val runMode: RunMode = RunMode.FOREGROUND,
)
