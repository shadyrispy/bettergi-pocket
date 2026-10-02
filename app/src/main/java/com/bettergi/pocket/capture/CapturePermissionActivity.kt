package com.bettergi.pocket.capture

import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class CapturePermissionActivity : AppCompatActivity() {
    private val launcher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
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
        val manager = getSystemService(MediaProjectionManager::class.java)
        Log.i(TAG, "requesting media projection permission")
        launcher.launch(ScreenShare.createCaptureIntent(manager))
    }

    private companion object {
        const val TAG = "BetterGI.Capture"
    }
}
