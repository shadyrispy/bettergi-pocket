package com.bettergi.pocket.capture

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bettergi.pocket.service.TriggerForegroundService

class CapturePermissionActivity : AppCompatActivity() {
    private val launcher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            Log.i(TAG, "capture result code=${result.resultCode} data=${result.data}")
            val serviceIntent = Intent(this, TriggerForegroundService::class.java).apply {
                if (result.resultCode == RESULT_OK && result.data != null) {
                    action = TriggerForegroundService.ACTION_CAPTURE_RESULT
                    putExtra(TriggerForegroundService.EXTRA_RESULT_CODE, result.resultCode)
                    // 嵌套 Intent 的 IBinder extra 在 parcel 时会丢失 → 改走进程内单例
                    CaptureResultHolder.pendingResultData = result.data
                } else {
                    action = TriggerForegroundService.ACTION_CAPTURE_DENIED
                }
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
