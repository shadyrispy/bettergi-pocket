package com.bettergi.pocket.capture

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bettergi.pocket.service.TriggerForegroundService

class CapturePermissionActivity : AppCompatActivity() {
    private val launcher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val serviceIntent = Intent(this, TriggerForegroundService::class.java).apply {
                if (result.resultCode == RESULT_OK && result.data != null) {
                    action = TriggerForegroundService.ACTION_CAPTURE_RESULT
                    putExtra(TriggerForegroundService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(TriggerForegroundService.EXTRA_RESULT_DATA, result.data)
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
        launcher.launch(ScreenShare.createCaptureIntent(manager))
    }
}
