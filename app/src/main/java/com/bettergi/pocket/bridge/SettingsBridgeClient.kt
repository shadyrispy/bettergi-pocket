package com.bettergi.pocket.bridge

import android.content.Context
import android.util.Log

/**
 * **设置桥（`.settings`，主进程）的调用侧门面**（工单 D 收口）。
 *
 * 之前 `:a11y` 侧的 [com.bettergi.pocket.overlay.OverlayWindowController] 与
 * [com.bettergi.pocket.overlay.A11yOverlayRuntime] 各自直接 `contentResolver.call`
 * 拼 [SettingsBridgeProvider] 的 URI/method —— 绕过了 bridge 封装。现在统一走这里。
 *
 * 抓包的两条 method（start/stop/snapshot）2026-10-03 随开关一起搬到管理器页后已删：
 * 管理器与主进程同进程，直接订阅 `CaptureSession.ui`、直接起服务，不需要过桥。
 */
object SettingsBridgeClient {

    private const val TAG = "BetterGI.SettingsBridgeClient"

    /** 识别日志镜像（编码后的条目数组）；失败返回 null（轮询侧静默跳过本轮）。 */
    fun logSnapshot(context: Context): Array<String>? = try {
        val bundle = context.contentResolver.call(
            SettingsBridgeProvider.uri(context),
            SettingsBridgeProvider.METHOD_LOG_SNAPSHOT, null, null,
        ) ?: return null
        bundle.getStringArray(SettingsBridgeProvider.KEY_ENTRIES)
    } catch (e: Exception) {
        Log.w(TAG, "log snapshot failed", e)
        null
    }
}
