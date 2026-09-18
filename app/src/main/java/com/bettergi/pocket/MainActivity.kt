package com.bettergi.pocket

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import com.bettergi.pocket.dsl.ScriptIcons
import com.bettergi.pocket.dsl.ScriptStore
import com.bettergi.pocket.input.InputAccessibilityService
import com.bettergi.pocket.notice.NoticeCenter
import com.bettergi.pocket.notice.NoticeRouter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.bettergi.pocket.capture.CapturePermissionActivity
import com.bettergi.pocket.scan.GoodRepository
import com.bettergi.pocket.input.SwipeMethod
import com.bettergi.pocket.input.SwipeTestRunner
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

        /** 悬浮窗的 `import` 动作：进入管理器的同时打开输入文件选择器（SAF 必须由 Activity 发起）。 */
        const val EXTRA_PICK_GOOD = "pick_good"

        private const val PREFS = "pocket"
        private const val KEY_FIRST_LAUNCH_DONE = "first_launch_done"

        /** 滑动测试：退到后台到注入手势之间的等待（等系统把前台还给游戏）。 */
        private const val SWIPE_TEST_BACK_DELAY_MS = 700L
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
            NoticeCenter.error("读取失败")
            return@registerForActivityResult
        }
        val key = runCatching { org.json.JSONObject(text).optString("flow", "") }.getOrDefault("")
            .ifBlank { uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".json") ?: "imported" }
        val (ok, msg) = com.bettergi.pocket.dsl.ScriptStore.importFlow(this, key, text)
        NoticeCenter.post(if (ok) NoticeCenter.Level.INFO else NoticeCenter.Level.ERROR, msg)
        if (ok) renderAll()
    }

    /** 输入文件（GOOD / 配装计划）选择器——SAF 只能由 Activity 发起，所以悬浮窗只能"拉起本页再选"。 */
    private val goodImportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            NoticeCenter.error("读取失败")
            return@registerForActivityResult
        }
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "input.json"
        val (ok, msg) = com.bettergi.pocket.scan.GoodRepository.save(this, name, text)
        NoticeCenter.post(if (ok) NoticeCenter.Level.INFO else NoticeCenter.Level.ERROR, msg)
    }

    private var pendingFromOverlay = false
    private var pendingPickGood = false

    /** P3：管理器界面已展示 ⇒ onResume 不得再用旧的「悬浮窗授权」分支覆盖它。 */
    private var managerShown = false
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
        if (intent.getBooleanExtra(EXTRA_PICK_GOOD, false)) pendingPickGood = true
        if (intent.getBooleanExtra(EXTRA_AUTO_REQUEST_CAPTURE, false)) {
            pendingCaptureRequest = true
        }
        intent.getStringExtra(EXTRA_AUTO_SHARE)?.let { pendingShareFile = it }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        // 管理器在前台 ⇒ 提醒显示在本页横幅（而不是再弹一份到悬浮窗）
        com.bettergi.pocket.notice.NoticeRouter.attachManager(noticeSink)
        // 管理器界面优先，不能被下面两个中转分支顶掉（曾实测被覆盖 ⇒ 首启仍显示别的界面 ✗）
        if (managerShown) return
        // ⚠️ 2026-09-18：这里原先还有一个「显示在上层」授权分支 —— 悬浮窗搬到无障碍进程后
        //    （TYPE_ACCESSIBILITY_OVERLAY 零权限）已整体删除，只剩投影授权与分享两个中转。
        when {
            // 前台内发起投影授权（fix53：悬浮球路径的兜底，activity 前台时启动合法）
            pendingCaptureRequest -> {
                pendingCaptureRequest = false
                startActivity(
                    Intent(this, CapturePermissionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            // 前台内起系统分享 chooser（service 后台起 chooser 需要 Activity 上下文，不可靠）
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
    }

    private fun shareGood(fileName: String) {
        val file = java.io.File(filesDir, fileName)
        if (!file.exists()) {
            NoticeCenter.error("GOOD 文件不存在：$fileName")
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
        if (pendingCaptureRequest || pendingShareFile != null) return // 有中转任务，onResume 处理
        launchOverlayAndExit()
    }

    // ---- P3 脚本管理器：列表 + 开关（本地导入，不联网）----

    // ---- 提醒横幅（NoticeCenter 的管理器展位）----

    private val noticeSink = object : com.bettergi.pocket.notice.NoticeCenter.Sink {
        override fun show(notice: com.bettergi.pocket.notice.NoticeCenter.Notice) {
            val banner = findViewById<TextView>(R.id.notice_banner) ?: return
            val (bg, fg) = when (notice.level) {
                com.bettergi.pocket.notice.NoticeCenter.Level.ERROR ->
                    R.color.pocket_danger to R.color.pocket_text
                com.bettergi.pocket.notice.NoticeCenter.Level.WARN ->
                    R.color.pocket_warn to R.color.pocket_text
                else ->
                    R.color.pocket_accent to R.color.pocket_text
            }
            banner.text = notice.text
            banner.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this@MainActivity, bg))
            banner.setTextColor(androidx.core.content.ContextCompat.getColor(this@MainActivity, fg))
            banner.visibility = android.view.View.VISIBLE
            banner.removeCallbacks(hideNotice)
            banner.postDelayed(hideNotice, if (notice.level == com.bettergi.pocket.notice.NoticeCenter.Level.INFO) 3_000L else 6_000L)
        }
    }

    private val hideNotice = Runnable { findViewById<TextView>(R.id.notice_banner)?.visibility = android.view.View.GONE }

    private fun showScriptManager() {
        managerShown = true
        setContentView(R.layout.activity_scripts)
        findViewById<TextView>(R.id.btn_import).setOnClickListener {
            runCatching { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
                .onFailure { NoticeCenter.error("无文件选择器：${it.message}") }
        }
        findViewById<TextView>(R.id.btn_back_overlay).setOnClickListener { launchOverlayAndExit() }
        findViewById<android.view.View>(R.id.status_card).setOnClickListener { openA11yIfNeeded() }
        bindCalibrateRow()
        bindGoodSection()
        bindSwipeRow()
        bindSwipeTest()
        renderAll()
        // 悬浮窗点了某个脚本的「导入」动作 ⇒ 进管理器后立刻开选择器
        if (pendingPickGood) {
            pendingPickGood = false
            launchGoodPicker()
        }
    }

    // ---- 渲染（全部由脚本清单驱动；界面不写死"哪条脚本有什么"）----

    private fun renderAll() {
        renderStatus()
        val all = ScriptStore.list(this)
        val main = all.filter { it.hasUi }
        val calibrate = all.filter { !it.hasUi }
        renderScriptRows(main, findViewById(R.id.scripts_list))
        renderScriptRows(calibrate, findViewById(R.id.calibrate_list))
        findViewById<TextView>(R.id.scripts_count).text =
            "共 ${main.size} · 启用 ${main.count { it.enabled }}"
        findViewById<TextView>(R.id.calibrate_subtitle).text = "${calibrate.size} 条 · 开发用"
        renderGoodSection()
    }

    /** 状态卡：无障碍是否就绪（未就绪时给出「去开启」）。 */
    private fun renderStatus() {
        val connected = InputAccessibilityService.isConnected()
        findViewById<TextView>(R.id.status_text).text =
            if (connected) "无障碍已开启" else "无障碍未开启"
        findViewById<TextView>(R.id.status_action).visibility =
            if (connected) android.view.View.GONE else android.view.View.VISIBLE
        findViewById<android.view.View>(R.id.status_dot).backgroundTintList =
            android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, if (connected) R.color.pocket_ok else R.color.pocket_warn),
            )
    }

    private fun openA11yIfNeeded() {
        if (InputAccessibilityService.isConnected()) return
        InputAccessibilityService.ensureEnabled(this)
    }

    private fun bindCalibrateRow() {
        val list = findViewById<android.widget.LinearLayout>(R.id.calibrate_list)
        val chevron = findViewById<TextView>(R.id.calibrate_chevron)
        findViewById<android.view.View>(R.id.calibrate_row).setOnClickListener {
            val show = list.visibility != android.view.View.VISIBLE
            list.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
            chevron.text = if (show) "▴" else "▾"
        }
    }

    private fun bindSwipeRow() {
        val card = findViewById<android.view.View>(R.id.swipe_card)
        val chevron = findViewById<TextView>(R.id.swipe_chevron)
        findViewById<android.view.View>(R.id.swipe_row).setOnClickListener {
            val show = card.visibility != android.view.View.VISIBLE
            card.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
            chevron.text = if (show) "▴" else "▾"
        }
    }

    /**
     * 脚本行：`[图标] 名称 / 动作摘要 · 来源` + 开关。长按「已导入」的行恢复内置。
     * 图标与动作摘要都来自脚本自己的声明（`ui.icon` / `ui.actions`）。
     */
    private fun renderScriptRows(entries: List<ScriptStore.Entry>, container: android.widget.LinearLayout?) {
        container ?: return
        container.removeAllViews()
        for (e in entries) container.addView(scriptRow(e))
    }

    private fun scriptRow(e: ScriptStore.Entry): android.view.View {
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(14), dp(10), dp(10), dp(10))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_pocket_card_outline)
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
        }
        row.addView(android.widget.ImageView(this).apply {
            setImageResource(ScriptIcons.script(e.icon))
            imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this@MainActivity, R.color.pocket_text),
            )
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) }
        })
        row.addView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
            addView(TextView(this@MainActivity).apply {
                text = e.label
                textSize = 16f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.pocket_text))
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitleOf(e)
                textSize = 12f
                maxLines = 2
                setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (e.issues.isEmpty()) R.color.pocket_text_muted else R.color.pocket_warn,
                    ),
                )
            })
        })
        row.addView(android.widget.Switch(this).apply {
            isChecked = e.enabled
            setOnCheckedChangeListener { _, checked ->
                ScriptStore.setEnabled(this@MainActivity, e.key, checked)
                renderAll()
            }
        })
        if (e.imported) {
            row.isLongClickable = true
            row.setOnLongClickListener {
                val ok = ScriptStore.resetFlow(this, e.key)
                if (ok) NoticeCenter.info("已恢复内置：${e.label}") else NoticeCenter.warn("无导入副本")
                renderAll()
                true
            }
        }
        return row
    }

    /** 副标题 = 该脚本声明的动作 + 来源 + 校验问题（问题必须显示出来，不能静默丢）。 */
    private fun subtitleOf(e: ScriptStore.Entry): String {
        val parts = ArrayList<String>()
        parts.addAll(e.actions.map { it.label })
        parts.add(if (e.imported) "已导入" else "内置")
        if (e.issues.isNotEmpty()) parts.add("⚠ " + e.issues.joinToString("; ").take(70))
        return parts.joinToString(" · ")
    }

    // ---- GOOD 数据区：扫描结果的导出 + 执行输入的导入 ----

    private fun bindGoodSection() {
        findViewById<android.widget.ImageView>(R.id.btn_good_export).setOnClickListener { shareLastGood() }
        findViewById<android.widget.ImageView>(R.id.btn_good_pick).setOnClickListener { launchGoodPicker() }
        findViewById<android.widget.ImageView>(R.id.btn_good_clear).setOnClickListener {
            GoodRepository.clear(this)
            NoticeCenter.info("已清除输入文件")
            renderGoodSection()
        }
    }

    private fun renderGoodSection() {
        val state = GoodRepository.state(this)
        findViewById<TextView>(R.id.good_input_name).text = state?.sourceName ?: "未选择"
        findViewById<android.widget.ImageView>(R.id.btn_good_clear).visibility =
            if (state != null) android.view.View.VISIBLE else android.view.View.GONE

        val export = GoodRepository.lastExport(this)
        findViewById<TextView>(R.id.good_export_name).text = export ?: "还没有导出"

        // 可用性逐条列出：需要输入的脚本能不能被这份文件驱动
        val parts = ArrayList<String>()
        for (e in ScriptStore.list(this)) {
            if (!GoodRepository.needsInput(this, e.key)) continue
            val ok = GoodRepository.planFor(this, e.key) != null
            parts.add("${e.label} " + if (ok) "✓" else "✗")
        }
        val caps = findViewById<TextView>(R.id.good_caps)
        caps.visibility = if (parts.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        caps.text = if (parts.isEmpty()) "" else "可驱动：" + parts.joinToString(" · ")
    }

    /** 导出最近一次扫描结果：交给前台服务走既有导出链（它才知道文件名）。 */
    private fun shareLastGood() {
        val intent = Intent(this, TriggerForegroundService::class.java).apply {
            action = TriggerForegroundService.ACTION_SHARE_GOOD
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun launchGoodPicker() {
        runCatching { goodImportLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
            .onFailure { NoticeCenter.error("无文件选择器：${it.message}") }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ---- 滑动测试（2026-09-18 由悬浮窗迁入：用户需求③「滑动测试功能也移动到 MainActivity」）----

    /**
     * 绑定滑动测试卡片。参数落在 `swipe_test` SharedPreferences（与旧悬浮窗同源 ⇒ 旧值沿用）。
     *
     * ⚠️ 与悬浮窗版的差异：旧版靠「缩球」让手势落到游戏；管理器是**前台 Activity**，会吃掉手势 ⇒
     * 点击后先把本 Activity 退到后台，再延迟注入滑动。
     */
    private fun bindSwipeTest() {
        val startYEdit = findViewById<EditText>(R.id.swipe_start_y) ?: return
        val distEdit = findViewById<EditText>(R.id.swipe_dist) ?: return
        val three = findViewById<RadioButton>(R.id.swipe_method_three) ?: return
        val chain = findViewById<RadioButton>(R.id.swipe_method_chain) ?: return

        val saved = SwipeTestRunner.load(this)
        startYEdit.setText(saved.startY.toString())
        distEdit.setText(saved.dist.toString())
        if (saved.method == SwipeMethod.THREE_SEGMENT) three.isChecked = true else chain.isChecked = true

        findViewById<Button>(R.id.btn_swipe_start).setOnClickListener {
            val startY = startYEdit.text.toString().toIntOrNull()
            val dist = distEdit.text.toString().toIntOrNull()
            if (startY == null || dist == null || dist <= 0) {
                NoticeCenter.warn("参数无效：起点Y/距离须为正数")
                return@setOnClickListener
            }
            val method = if (three.isChecked) SwipeMethod.THREE_SEGMENT else SwipeMethod.WAYPOINT_CHAIN
            SwipeTestRunner.save(this, startY, dist, method)
            val params = SwipeTestRunner.Params(startY, dist, method)
            NoticeCenter.info(
                "退到后台，700ms 后执行：${SwipeTestRunner.methodLabel(method)} ${SwipeTestRunner.describe(params)}",
            )
            moveTaskToBack(true)
            Handler(Looper.getMainLooper()).postDelayed(
                { SwipeTestRunner.run(this, params) },
                SWIPE_TEST_BACK_DELAY_MS,
            )
        }
    }

    /**
     * 交棒前台服务后退出。
     * 悬浮窗现由无障碍服务承载（零权限）⇒ 这里不再做任何「显示在上层」检查；
     * 无障碍未开时由服务侧提示一次（`InputAccessibilityService.promptIfDisconnected`）。
     */
    override fun onPause() {
        super.onPause()
        com.bettergi.pocket.notice.NoticeRouter.detachManager(noticeSink)
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
