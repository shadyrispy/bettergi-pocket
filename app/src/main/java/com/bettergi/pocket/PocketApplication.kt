package com.bettergi.pocket

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import android.util.Log
import com.bettergi.pocket.input.InputAccessibilityService
import com.bettergi.pocket.recognition.ocr.OcrFactory
import com.bettergi.pocket.core.FlowSource
import com.bettergi.pocket.recognition.opencv.OpenCvRuntime
import java.io.File

class PocketApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        InputAccessibilityService.attach(this)
        FlowSource.install(this)
        // ★ P3：NoticeCenter 在 :a11y 进程也 install —— 它是对象单例，prefs 引用是进程内状态；
        //   原先只在主进程装，:a11y 里 `onceKey` 的"一辈子一次"去重因 prefs == null 直接失效
        //   （post 返回 false，提示被静默吞掉）。这里只装 NoticeCenter（prefs 用自家 context 即可）；
        //   NoticeRouter 仍只在主进程装 —— 它的展位路由依赖 AppForeground/管理器横幅，
        //   且 :a11y 侧的提醒出口是 A11yOverlayRuntime.notice（本地提醒条 + log_append 回流），
        //   不走 NoticeRouter 的跨进程推送，装了反而多一条环回路径。
        com.bettergi.pocket.notice.NoticeCenter.install(this)
        if (currentProcessName() != packageName) return
        AppForeground.install(this)
        // 提醒中心（全应用唯一提醒通路；prefs 用于 onceKey 去重）——主进程侧与展位路由
        com.bettergi.pocket.notice.NoticeRouter.install(this)
        if (!OpenCvRuntime.ensureLoaded()) {
            Log.e(TAG, "OpenCV initialization failed")
        }
        OcrFactory.init(this)
    }

    private fun currentProcessName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return getProcessName()
        }
        val pid = Process.myPid()
        val processes = (getSystemService(ACTIVITY_SERVICE) as? ActivityManager)?.runningAppProcesses
        processes?.firstOrNull { it.pid == pid }?.processName?.let { return it }
        return try {
            File("/proc/self/cmdline").readBytes()
                .takeWhile { it != 0.toByte() }
                .toByteArray()
                .toString(Charsets.UTF_8)
                .ifBlank { packageName }
        } catch (_: Exception) {
            packageName
        }
    }

    private companion object {
        private const val TAG = "BetterGI.App"
    }
}
