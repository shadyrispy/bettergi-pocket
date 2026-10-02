package com.bettergi.pocket

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * 应用前后台跟踪（Android 10+ 后台启动 Activity 受限——投影授权 Activity 需据此决定
 * 直接启动还是引导用户从通知触发）。
 */
object AppForeground {
    @Volatile
    private var resumed = 0

    val isForeground: Boolean get() = resumed > 0

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumed++
            }
            override fun onActivityPaused(activity: Activity) {
                resumed = (resumed - 1).coerceAtLeast(0)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
