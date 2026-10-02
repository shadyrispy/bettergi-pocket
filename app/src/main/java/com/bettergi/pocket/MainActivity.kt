package com.bettergi.pocket

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.bettergi.pocket.capture.CapturePermissionActivity
import com.bettergi.pocket.service.TriggerForegroundService

/**
 * 启动壳 + service 中转（fix53）：
 * - 正常路径：交棒悬浮窗后即退出（零常驻）
 * - service 兜底拉前台：EXTRA_AUTO_REQUEST_CAPTURE（投影授权，前台内发起）/ EXTRA_AUTO_SHARE（前台起分享 chooser）
 */
class MainActivity : AppCompatActivity() {
    private var permissionUiShown = false
    private var pendingCaptureRequest = false
    private var pendingShareFile: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        continueLaunch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_AUTO_REQUEST_CAPTURE, false)) {
            pendingCaptureRequest = true
        }
        intent.getStringExtra(EXTRA_AUTO_SHARE)?.let { pendingShareFile = it }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        if (Settings.canDrawOverlays(this)) {
            when {
                // 前台内发起投影授权（fix53：悬浮球路径的兜底，activity 前台时启动合法）
                pendingCaptureRequest -> {
                    pendingCaptureRequest = false
                    startActivity(
                        Intent(this, CapturePermissionActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                // 前台内起系统分享 chooser（service 后台起 chooser 依赖 SAW 豁免，不可靠）
                else -> {
                    val shareFile = pendingShareFile
                    if (shareFile != null) {
                        pendingShareFile = null
                        shareGood(shareFile)
                    } else {
                        launchOverlayAndExit()
                    }
                }
            }
        } else if (!permissionUiShown) {
            showOverlayPermissionUi()
        }
    }

    private fun shareGood(fileName: String) {
        val file = java.io.File(filesDir, fileName)
        if (!file.exists()) {
            Toast.makeText(this, "GOOD 文件不存在：$fileName", Toast.LENGTH_SHORT).show()
            launchOverlayAndExit()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "分享 GOOD 导出"))
        // 分享面板退出后交棒悬浮窗（onResume pending 已清 → launchOverlayAndExit）
    }

    private fun continueLaunch() {
        if (Settings.canDrawOverlays(this)) {
            if (pendingCaptureRequest || pendingShareFile != null) return // 有中转任务，onResume 处理
            launchOverlayAndExit()
            return
        }
        showOverlayPermissionUi()
    }

    private fun showOverlayPermissionUi() {
        if (permissionUiShown) return
        permissionUiShown = true
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.btn_request_overlay).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    private fun launchOverlayAndExit() {
        if (isFinishing) return
        val intent = Intent(this, TriggerForegroundService::class.java).apply {
            action = TriggerForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        finish()
    }

    companion object {
        /** service 兜底：拉前台后前台内发起投影授权。 */
        const val EXTRA_AUTO_REQUEST_CAPTURE = "auto_request_capture"

        /** service 兜底：拉前台后前台内起分享 chooser（值为 files 下文件名）。 */
        const val EXTRA_AUTO_SHARE = "auto_share"
    }
}
