package com.bettergi.pocket.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bettergi.pocket.service.TriggerForegroundService

/**
 * adb 调试控制入口（仅 debug 包使用，便于端到端自动化脚本复用）：
 *
 * adb shell am broadcast -a com.bettergi.pocket.debug.SET_SCREEN_SHARE --ez enabled true
 * adb shell am broadcast -a com.bettergi.pocket.debug.SET_SCAN --ez enabled true
 * adb shell am broadcast -a com.bettergi.pocket.debug.STATUS
 *
 * ⚠️ 指令转发给 TriggerForegroundService 执行（不在此新建 TriggerSettingsRepository——
 * 多实例的内存缓存不同步，写入 prefs 不会触发 service 的 settingsListener）。
 */
class DebugControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
        val service = Intent(app, TriggerForegroundService::class.java).apply {
            when (intent.action) {
                ACTION_SET_SCREEN_SHARE -> {
                    action = TriggerForegroundService.ACTION_DEBUG_SET_SCREEN_SHARE
                    putExtra(TriggerForegroundService.EXTRA_ENABLED, enabled)
                }
                ACTION_SET_SCAN -> {
                    action = TriggerForegroundService.ACTION_DEBUG_SET_SCAN
                    putExtra(TriggerForegroundService.EXTRA_ENABLED, enabled)
                }
                ACTION_SET_PROBE -> action = TriggerForegroundService.ACTION_DEBUG_SET_PROBE
                ACTION_SWIPE_TEST -> {
                    action = TriggerForegroundService.ACTION_DEBUG_SWIPE_TEST
                    putExtra(TriggerForegroundService.EXTRA_START_Y, intent.getIntExtra(EXTRA_START_Y, 1150))
                    putExtra(TriggerForegroundService.EXTRA_DIST, intent.getIntExtra(EXTRA_DIST, 876))
                }
                ACTION_SCAN_FLOW -> {
                    action = TriggerForegroundService.ACTION_DEBUG_SCAN_FLOW
                    putExtra(
                        TriggerForegroundService.EXTRA_FLOW,
                        intent.getStringExtra(EXTRA_FLOW) ?: "artifact_scan",
                    )
                    putExtra(TriggerForegroundService.EXTRA_MAX_PAGES, intent.getIntExtra(EXTRA_MAX_PAGES, Int.MAX_VALUE))
                }
                ACTION_STATUS -> action = TriggerForegroundService.ACTION_DEBUG_STATUS
                else -> return
            }
        }
        try {
            androidx.core.content.ContextCompat.startForegroundService(app, service)
        } catch (e: Exception) {
            Log.w(TAG, "forward debug command failed", e)
        }
    }

    companion object {
        const val TAG = "BetterGI.Debug"
        const val ACTION_SET_SCREEN_SHARE = "com.bettergi.pocket.debug.SET_SCREEN_SHARE"
        const val ACTION_SET_SCAN = "com.bettergi.pocket.debug.SET_SCAN"
        const val ACTION_SET_PROBE = "com.bettergi.pocket.debug.SET_PROBE"
        const val ACTION_SWIPE_TEST = "com.bettergi.pocket.debug.SWIPE_TEST"
        const val ACTION_SCAN_FLOW = "com.bettergi.pocket.debug.SCAN_FLOW"
        const val ACTION_STATUS = "com.bettergi.pocket.debug.STATUS"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_START_Y = "startY"
        const val EXTRA_DIST = "dist"
        const val EXTRA_FLOW = "flow"
        const val EXTRA_MAX_PAGES = "maxPages"
    }
}
