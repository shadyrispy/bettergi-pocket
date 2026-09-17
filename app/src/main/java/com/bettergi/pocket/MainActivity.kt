package com.bettergi.pocket

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
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

    companion object {
        /** service 兜底：拉前台后前台内发起投影授权。 */
        const val EXTRA_AUTO_REQUEST_CAPTURE = "auto_request_capture"

        /** service 兜底：拉前台后前台内起分享 chooser（值为 files 下文件名）。 */
        const val EXTRA_AUTO_SHARE = "auto_share"

        /** 悬浮窗「设置」长按进入管理器时置 true（P3/P4）。 */
        const val EXTRA_FROM_OVERLAY = "from_overlay"

        private const val PREFS = "pocket"
        private const val KEY_FIRST_LAUNCH_DONE = "first_launch_done"
    }

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    /** 打开导入器（SAF，本地文件，不需要网络/存储权限）。 */
    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "读取失败", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val key = runCatching { org.json.JSONObject(text).optString("flow", "") }.getOrDefault("")
            .ifBlank { uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".json") ?: "imported" }
        val (ok, msg) = com.bettergi.pocket.dsl.ScriptStore.importFlow(this, key, text)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        if (ok) renderScriptRows()
    }

    private var pendingFromOverlay = false

    /** P3：管理器界面已展示 ⇒ onResume 不得再用旧的「悬浮窗授权」分支覆盖它。 */
    private var managerShown = false
    private var permissionUiShown = false
    private var pendingCaptureRequest = false
    private var pendingShareFile: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        val firstLaunch = !prefs.getBoolean(KEY_FIRST_LAUNCH_DONE, false)
        // 需求（用户 2026-09-18）：MainActivity **首次启动 app 时出现**；之后经悬浮窗长按设置进入。
        if (firstLaunch || pendingFromOverlay) {
            prefs.edit().putBoolean(KEY_FIRST_LAUNCH_DONE, true).apply()
            pendingFromOverlay = false
            showScriptManager()
            return
        }
        continueLaunch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        // ⚠️ Activity 已在运行时（例如刚从权限页/管理器返回后再长按悬浮窗），`am start` 只走 onNewIntent：
        //    这里必须也能切到管理器，否则「长按浮窗设置」在二次进入时失效（曾实测 ✗）。
        if (pendingFromOverlay) {
            pendingFromOverlay = false
            showScriptManager()
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_FROM_OVERLAY, false)) pendingFromOverlay = true
        if (intent.getBooleanExtra(EXTRA_AUTO_REQUEST_CAPTURE, false)) {
            pendingCaptureRequest = true
        }
        intent.getStringExtra(EXTRA_AUTO_SHARE)?.let { pendingShareFile = it }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        // P3：管理器界面优先，不能被「悬浮窗授权」分支顶掉（曾实测被覆盖 ⇒ 首启仍显示授权页 ✗）
        if (managerShown) return
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

    // ---- P3 脚本管理器：列表 + 开关（本地导入，不联网）----

    private fun showScriptManager() {
        managerShown = true
        setContentView(R.layout.activity_scripts)
        findViewById<Button>(R.id.btn_import).setOnClickListener {
            runCatching { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
                .onFailure { Toast.makeText(this, "无文件选择器：${it.message}", Toast.LENGTH_LONG).show() }
        }
        findViewById<Button>(R.id.btn_back_overlay).setOnClickListener { launchOverlayAndExit() }
        renderScriptRows()
    }

    private fun renderScriptRows() {
        val container = findViewById<android.widget.LinearLayout>(R.id.scripts_list) ?: return
        container.removeAllViews()
        val entries = com.bettergi.pocket.dsl.ScriptStore.list(this)
        if (entries.isEmpty()) {
            container.addView(TextView(this).apply { text = "（无脚本）" })
            return
        }
        for (e in entries) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(0, 18, 0, 18)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = iconGlyph(e.icon)
                textSize = 20f
                width = 64
            })
            row.addView(android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = e.label
                    textSize = 17f
                })
                val note = buildString {
                    append(e.key)
                    if (e.imported) append("　· 已导入（长按恢复内置）")
                    if (e.issues.isNotEmpty()) append("　· ⚠ ").append(e.issues.joinToString("; ").take(80))
                }
                addView(TextView(this@MainActivity).apply {
                    text = note
                    textSize = 11f
                    alpha = 0.7f
                })
            })
            row.addView(android.widget.Switch(this).apply {
                isChecked = e.enabled
                setOnCheckedChangeListener { _, checked ->
                    com.bettergi.pocket.dsl.ScriptStore.setEnabled(this@MainActivity, e.key, checked)
                }
            })
            if (e.imported) {
                row.isLongClickable = true
                row.setOnLongClickListener {
                    val ok = com.bettergi.pocket.dsl.ScriptStore.resetFlow(this, e.key)
                    Toast.makeText(this, if (ok) "已恢复内置：${e.key}" else "无导入副本", Toast.LENGTH_SHORT).show()
                    renderScriptRows()
                    true
                }
            }
            container.addView(row)
        }
    }

    private fun iconGlyph(icon: String): String = when (icon) {
        "artifact" -> "遗"
        "weapon" -> "武"
        "character" -> "角"
        "lock" -> "锁"
        "equip" -> "装"
        else -> "⚙"
    }

    private fun launchOverlayAndExit() {
        if (isFinishing) return
        val intent = Intent(this, TriggerForegroundService::class.java).apply {
            action = TriggerForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        finish()
    }

}
