package com.bettergi.pocket.capture

import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class CapturePermissionActivity : AppCompatActivity() {
    /** 结果回调有没有走过一次（走过就一切照旧，销毁时不再补发）。 */
    private var delivered = false

    private val launcher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            delivered = true
            Log.i(TAG, "capture result code=${result.resultCode} data=${result.data}")
            // 工单 D：回传走 CaptureContract（不再 import service；action/extras 值逐字不变）
            val serviceIntent = CaptureContract.resultIntent(
                this,
                if (result.resultCode == RESULT_OK && result.data != null) {
                    CaptureContract.ACTION_CAPTURE_RESULT
                } else {
                    CaptureContract.ACTION_CAPTURE_DENIED
                },
            )
            if (result.resultCode == RESULT_OK && result.data != null) {
                serviceIntent.putExtra(CaptureContract.EXTRA_RESULT_CODE, result.resultCode)
                // 嵌套 Intent 的 IBinder extra 在 parcel 时会丢失 → 改走进程内单例
                CaptureResultHolder.pendingResultData = result.data
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ★ #250：第一行就落"我确实被创建过"这笔时刻，服务侧的探针读它判启动成没成。
        //   必须早于 launcher.launch —— 实测授权结果回调只比 onCreate 晚 136~254ms（秒授权快路），
        //   探针要是读到"没创建过"而结果其实已经回了，就会把一次成功的授权判成被 ROM 吞掉。
        launchedAtElapsed = SystemClock.elapsedRealtime()
        val manager = getSystemService(MediaProjectionManager::class.java)
        Log.i(TAG, "requesting media projection permission")
        launcher.launch(ScreenShare.createCaptureIntent(manager))
    }

    /**
     * ★ #250：中转页被划掉/随任务回收时，`StartActivityForResult` 的回调**永远不会再走**，
     * 于是既没有 RESULT 也没有 DENIED —— 发起标志只能等 60s 那句「无人应答」去复位，而
     * 「屏幕共享」这一路永远不会收敛。这里补一条与回调同形的 DENIED，走服务里既有的
     * `ACTION_CAPTURE_DENIED` 分支（复位标志 + 开关落回 false）。
     * 配置变更（转屏）不算失败：那种情况下回调注册会被新实例接管。
     */
    override fun onDestroy() {
        super.onDestroy()
        if (!delivered && !isChangingConfigurations) {
            Log.w(TAG, "capture relay 销毁而结果从未回调 ⇒ 补发 DENIED（开关落回）")
            ContextCompat.startForegroundService(
                this,
                CaptureContract.resultIntent(this, CaptureContract.ACTION_CAPTURE_DENIED),
            )
        }
    }

    companion object {
        const val TAG = "BetterGI.Capture"

        /**
         * #250（2026-10-13 华为 EML-AL00 实测定型）：本页 `onCreate` **真实跑过**的时刻
         * （elapsedRealtime；0 = 本进程里从没创建过）。
         *
         * 为什么要有这一笔：EMUI 把服务后台发起的 `startActivity` 拦下时**不抛异常**
         * （2026-10-11 打的是 `Background activity start … result: 102`，本次复核 ATMS 侧
         * `areBackgroundActivityStartsAllowed return false` 之后仍按华为自己的豁免放行），
         * 于是 `requestCapturePermission()` 的 `catch` 兜底不可达 —— 用户拨下开关只剩一个
         * 自顾自亮着的开关。判"这一次启动到底落没落地"最硬的正面证据就是本页确实被创建过。
         *
         * ⚠️ 同进程才读得到：本页与 `TriggerForegroundService` 在 AndroidManifest 里都**没有**
         * `android:process` ⇒ 与 `PendingHandoff` 同一前提（无障碍那条才在 `:a11y` 里，跨进程）。
         */
        @Volatile
        var launchedAtElapsed: Long = 0L
    }
}
