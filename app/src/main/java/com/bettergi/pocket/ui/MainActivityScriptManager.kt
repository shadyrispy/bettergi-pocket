package com.bettergi.pocket.ui

import android.content.Intent
import android.os.Handler
import android.os.Looper

import android.view.View
import android.widget.TextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Button
import androidx.core.content.ContextCompat
import com.bettergi.pocket.R
import com.bettergi.pocket.notice.NoticeCenter
import com.bettergi.pocket.dsl.ScriptStore
import com.bettergi.pocket.dsl.ScriptIcons
import com.bettergi.pocket.input.InputAccessibilityService
import com.bettergi.pocket.input.SwipeMethod
import com.bettergi.pocket.input.SwipeTestRunner
import com.bettergi.pocket.scan.GoodRepository
import com.bettergi.pocket.scan.NameOverrides
import com.bettergi.pocket.service.TriggerForegroundService
import com.esc.irminsul.capture.CaptureResult
import com.esc.irminsul.capture.IrminsulCapture
import com.esc.irminsul.capture.PermissionKind
import androidx.core.widget.doAfterTextChanged
import com.bettergi.pocket.capture.CapturePermissionActivity
import com.bettergi.pocket.ui.MainActivity.Companion.SWIPE_TEST_BACK_DELAY_MS

/*
 * Stage 3.6：脚本管理器屏（ScriptManager 屏）从 MainActivity.kt 抽出（扩展函数，函数体一字未改）。
 */

internal fun MainActivity.showScriptManager() {
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
    renderCaptureSection()
    renderNameSection()
    // 悬浮窗点了某个脚本的「导入」动作 ⇒ 进管理器后立刻开选择器
    if (pendingPickGood) {
        pendingPickGood = false
        launchGoodPicker()
    }
}

// ---- 渲染（全部由脚本清单驱动；界面不写死"哪条脚本有什么"）----

internal fun MainActivity.renderAll() {
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
internal fun MainActivity.renderStatus() {
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

/**
 * 「抓包权限」小节（C2 与 bp 授权 UI 的融合点）。
 *
 * 两条纪律：① 状态**一律由库判**（`refreshPermissions` 里已含 ROM 差异与"本 app 没声明
 * 通知权限就不算卡着"这类语义），界面不自己 checkPermission；② 「去开启」只调
 * `openFixSettings(kind)`，宿主不拼系统 intent —— 否则每家 ROM 的跳转链要在这里重写一遍。
 *
 * 整行可点（与顶部状态卡同一交互习惯），所以不为一个按钮再造样式。
 */
internal fun MainActivity.renderCaptureSection() {
    val list = findViewById<android.widget.LinearLayout?>(R.id.capture_list) ?: return
    val hint = findViewById<TextView?>(R.id.capture_hint)
    when (val support = IrminsulCapture.probeNativeSupport()) {
        is CaptureResult.Err -> {
            list.removeAllViews()
            hint?.text = "这台设备跑不了抓包（原生库只出 arm64-v8a）：${support.error}"
            return
        }
        is CaptureResult.Ok -> Unit
    }
    val p = IrminsulCapture.refreshPermissions(this)
    list.removeAllViews()
    list.addView(
        captureRow(
            "VPN 隧道",
            p.vpnPermissionGranted,
            "抓包要把游戏流量导进隧道",
            "未授权 · 点此授权",
            PermissionKind.Vpn,
        ),
    )
    if (!p.batteryOptimizationExempt) {
        list.addView(
            captureRow(
                "忽略电池优化",
                false,
                "",
                "系统可能中途杀掉长时间抓包 · 点此设置",
                PermissionKind.BatteryOptimization,
            ),
        )
    }
    if (p.needsAutoStart) {
        list.addView(
            captureRow(
                "允许自启动",
                false,
                "",
                "这台 ROM 不开自启隧道起不来 · 点此设置",
                PermissionKind.AutoStart,
            ),
        )
    }
    val tail = if (p.romHint.isBlank()) "" else "\n${p.romHint}"
    hint?.text = "抓包开关在悬浮窗「抓包采集」：解齐四段数据后自动入库并收回隧道，" +
        "不持久化、重启不会自己开$tail"
}

internal fun MainActivity.captureRow(
    title: String,
    granted: Boolean,
    okText: String,
    pendingText: String,
    kind: PermissionKind,
): android.view.View {
    return android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPadding(dp(14), dp(10), dp(10), dp(10))
        background = ContextCompat.getDrawable(activity(), R.drawable.bg_pocket_card_outline)
        layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
        addView(TextView(activity()).apply {
            text = title
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity(), R.color.pocket_text))
            layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
        })
        addView(TextView(activity()).apply {
            text = if (granted) okText.ifBlank { "已就绪" } else pendingText
            textSize = 12f
            setTextColor(
                ContextCompat.getColor(
                    activity(),
                    if (granted) R.color.pocket_ok else R.color.pocket_warn,
                ),
            )
        })
        if (!granted) setOnClickListener { openCaptureFix(kind) }
    }
}

/**
 * 「角色昵称」小节（#105）。旅行者/流浪者/奇偶的显示名由玩家自定义，词典以**官方中文名**为键
 * ⇒ 结构性命中不了；原来那张内置猜测表的方向和 GT 对不上，已删（理由见 [NameOverrides]）。
 *
 * 每改一个字就落盘：这一页其余开关都是即点即生效，没有"保存"按钮的交互习惯；而引擎是
 * **每次起扫现读**这张表 ⇒ 不存在"忘了点保存 ⇒ 白扫一轮"。
 */
internal fun MainActivity.renderNameSection() {
    val list = findViewById<android.widget.LinearLayout?>(R.id.name_override_list) ?: return
    val hint = findViewById<TextView?>(R.id.name_override_hint)
    val current = NameOverrides.load(this)
    list.removeAllViews()
    for ((field, label) in NameOverrides.FIELDS) list.addView(nameOverrideRow(label, field, current.valueOf(field)))
    hint?.text = "填了才会归一成 GOOD 键；留空则保留原文并在日志里报出来。" +
        "⚠️ 昵称优先于词典：填成别的角色的名字会把那位认成这位。"
}

internal fun MainActivity.nameOverrideRow(label: String, field: String, value: String?): android.view.View {
    return android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
        addView(TextView(activity()).apply {
            text = label
            textSize = 14f
            setTextColor(ContextCompat.getColor(activity(), R.color.pocket_text))
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(130), -2)
        })
        // 先 setText 再挂监听：否则回填会立刻触发一次"保存"，把空值写回自己。
        addView(EditText(activity()).apply {
            setText(value.orEmpty())
            textSize = 14f
            maxLines = 1
            layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
            doAfterTextChanged { e ->
                NameOverrides.save(
                    activity(),
                    NameOverrides.load(activity()).withField(field, e?.toString()?.trim()),
                )
            }
        })
    }
}

internal fun MainActivity.openCaptureFix(kind: PermissionKind) {
    if (IrminsulCapture.openFixSettings(this, kind) is CaptureResult.Err) {
        NoticeCenter.error("这台设备找不到「$kind」对应的设置页")
    }
}

// ---- 首次启动引导（只做竖屏：走到这里说明还没启动原神，设备是竖持的）----

internal fun MainActivity.openA11yIfNeeded() {
    if (InputAccessibilityService.isConnected()) return
    InputAccessibilityService.ensureEnabled(this)
}

internal fun MainActivity.bindCalibrateRow() {
    val list = findViewById<android.widget.LinearLayout>(R.id.calibrate_list)
    val chevron = findViewById<TextView>(R.id.calibrate_chevron)
    findViewById<android.view.View>(R.id.calibrate_row).setOnClickListener {
        val show = list.visibility != android.view.View.VISIBLE
        list.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
        chevron.text = if (show) "▴" else "▾"
    }
}

internal fun MainActivity.bindSwipeRow() {
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
internal fun MainActivity.renderScriptRows(entries: List<ScriptStore.Entry>, container: android.widget.LinearLayout?) {
    container ?: return
    container.removeAllViews()
    for (e in entries) container.addView(scriptRow(e))
}

internal fun MainActivity.scriptRow(e: ScriptStore.Entry): android.view.View {
    val row = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPadding(dp(14), dp(10), dp(10), dp(10))
        background = ContextCompat.getDrawable(activity(), R.drawable.bg_pocket_card_outline)
        layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
    }
    row.addView(android.widget.ImageView(this).apply {
        setImageResource(ScriptIcons.script(e.icon))
        imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(activity(), R.color.pocket_text),
        )
        layoutParams = android.widget.LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) }
    })
    row.addView(android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
        addView(TextView(activity()).apply {
            text = e.label
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity(), R.color.pocket_text))
        })
        addView(TextView(activity()).apply {
            text = subtitleOf(e)
            textSize = 12f
            maxLines = 2
            setTextColor(
                ContextCompat.getColor(
                    activity(),
                    if (e.issues.isEmpty()) R.color.pocket_text_muted else R.color.pocket_warn,
                ),
            )
        })
    })
    row.addView(android.widget.Switch(this).apply {
        isChecked = e.enabled
        setOnCheckedChangeListener { _, checked ->
            ScriptStore.setEnabled(activity(), e.key, checked)
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
internal fun MainActivity.subtitleOf(e: ScriptStore.Entry): String {
    val parts = ArrayList<String>()
    parts.addAll(e.actions.map { it.label })
    parts.add(if (e.imported) "已导入" else "内置")
    if (e.issues.isNotEmpty()) parts.add("⚠ " + e.issues.joinToString("; ").take(70))
    return parts.joinToString(" · ")
}

// ---- GOOD 数据区：扫描结果的导出 + 执行输入的导入 ----

internal fun MainActivity.bindGoodSection() {
    findViewById<android.widget.ImageView>(R.id.btn_good_export).setOnClickListener { shareLastGood() }
    findViewById<android.widget.ImageView>(R.id.btn_good_pick).setOnClickListener { launchGoodPicker() }
    findViewById<android.widget.ImageView>(R.id.btn_good_clear).setOnClickListener {
        GoodRepository.clear(this)
        NoticeCenter.info("已清除输入文件")
        renderGoodSection()
    }
}

internal fun MainActivity.renderGoodSection() {
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
internal fun MainActivity.shareLastGood() {
    val intent = Intent(this, TriggerForegroundService::class.java).apply {
        action = TriggerForegroundService.ACTION_SHARE_GOOD
    }
    ContextCompat.startForegroundService(this, intent)
}

internal fun MainActivity.launchGoodPicker() {
    runCatching { goodImportLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
        .onFailure { NoticeCenter.error("无文件选择器：${it.message}") }
}

internal fun MainActivity.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

// ---- 滑动测试（2026-09-18 由悬浮窗迁入：用户需求③「滑动测试功能也移动到 MainActivity」）----

/**
 * 绑定滑动测试卡片。参数落在 `swipe_test` SharedPreferences（与旧悬浮窗同源 ⇒ 旧值沿用）。
 *
 * ⚠️ 与悬浮窗版的差异：旧版靠「缩球」让手势落到游戏；管理器是**前台 Activity**，会吃掉手势 ⇒
 * 点击后先把本 Activity 退到后台，再延迟注入滑动。
 */
internal fun MainActivity.bindSwipeTest() {
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
