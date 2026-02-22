package com.bettergi.pocket.capture

import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build

/**
 * 弹出系统共享选择器：整块屏幕，或指定单个应用。
 * Android 14+ 用 [MediaProjectionConfig.createConfigForUserChoice]；更低版本只有整屏。
 */
object ScreenShare {
    fun createCaptureIntent(manager: MediaProjectionManager): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
        } else {
            manager.createScreenCaptureIntent()
        }
    }
}
