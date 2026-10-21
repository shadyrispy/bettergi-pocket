package com.bettergi.pocket.bridge

import android.content.Context
import android.util.Log

/**
 * **设置桥（`.settings`，主进程）的调用侧门面**（工单 D 收口）。
 *
 * 之前 `:a11y` 侧的 [com.bettergi.pocket.overlay.OverlayWindowController] 与
 * [com.bettergi.pocket.overlay.A11yOverlayRuntime] 各自直接 `contentResolver.call`
 * 拼 [SettingsBridgeProvider] 的 URI/method —— 绕过了 bridge 封装。现在统一走这里：
 * method 名、Bundle 键全部取自 [SettingsBridgeProvider] 的现有常量（**值逐字不变**），
 * 调用侧只认本门面的三个方法：
 * - 抓包指令（start/stop）
 * - 抓包会话状态快照（开面板时拉一次）
 * - 识别日志镜像快照（日志窗轮询）
 */
object SettingsBridgeClient {

    private const val TAG = "BetterGI.SettingsBridgeClient"

    /** 抓包会话状态快照（text + running）。桥不通时返回 null（调用方走既有降级）。 */
    data class CaptureSnapshot(val running: Boolean, val text: String)

    /** 面板开关 → 抓包开始/停止指令。返回是否被主进程受理（ok=true）。 */
    fun captureCommand(context: Context, start: Boolean): Boolean {
        val method =
            if (start) SettingsBridgeProvider.METHOD_CAPTURE_START
            else SettingsBridgeProvider.METHOD_CAPTURE_STOP
        return runCatching {
            context.contentResolver.call(
                SettingsBridgeProvider.uri(context), method, null, null,
            )?.getBoolean(SettingsBridgeProvider.KEY_OK) == true
        }.getOrDefault(false)
    }

    /** 开面板时拉一次当前会话状态；桥不通时返回 null。 */
    fun captureSnapshot(context: Context): CaptureSnapshot? {
        val bundle = runCatching {
            context.contentResolver.call(
                SettingsBridgeProvider.uri(context),
                SettingsBridgeProvider.METHOD_CAPTURE_SNAPSHOT, null, null,
            )
        }.getOrNull() ?: return null
        return CaptureSnapshot(
            running = bundle.getBoolean(SettingsBridgeProvider.KEY_RUNNING),
            text = bundle.getString(SettingsBridgeProvider.KEY_TEXT).orEmpty(),
        )
    }

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
