package com.bettergi.pocket.settings

import android.os.Bundle

/**
 * 设置读写契约（2026-09-18 悬浮窗宿主迁移到无障碍进程时引入）。
 *
 * **为什么要这一层**：悬浮窗控制器（`OverlayWindowController`）的所有按钮只做三件事 ——
 * 读设置、写设置、观察设置回写；它**从不直接调** `TriggerForegroundService`。
 * 因此"跨进程"要搬的东西只有"设置"这一项，把它抽成接口后：
 * - 主进程：[TriggerSettingsRepository]（权威实现，落 SharedPreferences）
 * - 无障碍进程：[BridgeSettingsRepository]（经 ContentProvider 代理 + 变更观察）
 *
 * 这样控制器代码本身几乎不用改，只换构造参数类型。
 */
interface SettingsGateway {
    fun get(): TriggerSettings

    fun addListener(listener: (TriggerSettings) -> Unit)

    fun removeListener(listener: (TriggerSettings) -> Unit)

    fun setScreenShareEnabled(enabled: Boolean)

    fun setAutoPickEnabled(enabled: Boolean)

    fun setAutoSkipEnabled(enabled: Boolean)

    fun setQuickSkipDialogueEnabled(enabled: Boolean)

    fun setAutoLaunchGenshinEnabled(enabled: Boolean)

    fun setScanEnabled(enabled: Boolean)

    fun setScanFlow(flow: String)

    fun setScanMaxPages(pages: Int)
}

/**
 * 设置的跨进程线格式（字段名唯一事实源 —— 两侧共用，防止改一边忘一边）。
 */
object SettingsWire {

    // Bundle 传输键
    const val K_SCREEN_SHARE = "screenShareEnabled"
    const val K_AUTO_PICK = "autoPickEnabled"
    const val K_AUTO_SKIP = "autoSkipEnabled"
    const val K_QUICK_SKIP = "quickSkipDialogueEnabled"
    const val K_AUTO_LAUNCH = "autoLaunchGenshinEnabled"
    const val K_SCAN = "scanEnabled"
    const val K_SCAN_FLOW = "scanFlow"
    const val K_SCAN_MAX_PAGES = "scanMaxPages"

    // setter 字段名（call 的 arg 参数）
    const val F_SCREEN_SHARE = "screen_share"
    const val F_AUTO_PICK = "auto_pick"
    const val F_AUTO_SKIP = "auto_skip"
    const val F_QUICK_SKIP = "quick_skip"
    const val F_AUTO_LAUNCH = "auto_launch"
    const val F_SCAN = "scan"
    const val F_SCAN_FLOW = "scan_flow"
    const val F_SCAN_MAX_PAGES = "scan_max_pages"

    fun toBundle(s: TriggerSettings): Bundle = Bundle().apply {
        putBoolean(K_SCREEN_SHARE, s.screenShareEnabled)
        putBoolean(K_AUTO_PICK, s.autoPickEnabled)
        putBoolean(K_AUTO_SKIP, s.autoSkipEnabled)
        putBoolean(K_QUICK_SKIP, s.quickSkipDialogueEnabled)
        putBoolean(K_AUTO_LAUNCH, s.autoLaunchGenshinEnabled)
        putBoolean(K_SCAN, s.scanEnabled)
        putString(K_SCAN_FLOW, s.scanFlow)
        putInt(K_SCAN_MAX_PAGES, s.scanMaxPages)
    }

    fun fromBundle(b: Bundle): TriggerSettings = TriggerSettings(
        screenShareEnabled = b.getBoolean(K_SCREEN_SHARE, false),
        autoPickEnabled = b.getBoolean(K_AUTO_PICK, false),
        autoSkipEnabled = b.getBoolean(K_AUTO_SKIP, false),
        quickSkipDialogueEnabled = b.getBoolean(K_QUICK_SKIP, true),
        autoLaunchGenshinEnabled = b.getBoolean(K_AUTO_LAUNCH, false),
        scanEnabled = b.getBoolean(K_SCAN, false),
        scanFlow = b.getString(K_SCAN_FLOW, "artifact_scan") ?: "artifact_scan",
        scanMaxPages = b.getInt(K_SCAN_MAX_PAGES, 0),
    )

    /** 无障碍进程尚未拿到权威值时的占位（与主进程 [TriggerSettingsRepository.readFromPrefs] 默认值一致）。 */
    val DEFAULTS: TriggerSettings = TriggerSettings(
        screenShareEnabled = false,
        autoPickEnabled = false,
        autoSkipEnabled = false,
        quickSkipDialogueEnabled = true,
        autoLaunchGenshinEnabled = false,
        scanEnabled = false,
        scanFlow = "artifact_scan",
        scanMaxPages = 0,
    )
}
