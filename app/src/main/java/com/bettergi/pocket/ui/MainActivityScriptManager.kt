package com.bettergi.pocket.ui

import com.bettergi.pocket.vd.BackgroundSession
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
import com.bettergi.pocket.overlay.ScriptIcons
import com.bettergi.pocket.input.InputAccessibilityService
import com.bettergi.pocket.input.SwipeMethod
import com.bettergi.pocket.input.SwipeTestRunner
import com.bettergi.pocket.scan.GoodRepository
import com.bettergi.pocket.scan.NameOverrides
import com.bettergi.pocket.settings.RunMode
import com.bettergi.pocket.settings.TriggerSettings
import com.bettergi.pocket.settings.TriggerSettingsRepository
import com.bettergi.pocket.shizuku.BgiRemoteManager
import com.bettergi.pocket.shizuku.ShizukuInstallHelper
import com.bettergi.pocket.shizuku.ShizukuPhase
import com.bettergi.pocket.shizuku.ShizukuState
import com.bettergi.pocket.pcdata.CaptureSession
import com.bettergi.pocket.service.TriggerForegroundService
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.esc.irminsul.capture.CaptureResult
import com.esc.irminsul.capture.IrminsulCapture
import com.esc.irminsul.capture.PermissionKind
import androidx.core.widget.doAfterTextChanged
import com.bettergi.pocket.capture.CapturePermissionActivity
import com.bettergi.pocket.ui.MainActivity.Companion.SWIPE_TEST_BACK_DELAY_MS
import rikka.shizuku.Shizuku

/*
 * Stage 3.6：脚本管理器屏（ScriptManager 屏）从 MainActivity.kt 抽出（扩展函数，函数体一字未改）。
 */

internal fun MainActivity.showScriptManager() {
    managerShown = true
    // ⚠️ 两屏共用一个 Activity 实例，而 onResume 按 `onboardingShown` 优先重绘引导页。
    //    不在这儿放下引导标志 ⇒ 进过管理器再回前台，renderOnboarding() 会在管理器布局里
    //    找引导页的 id，findViewById 返回 null 直接 NPE（2026-10-03 dsl/dev/ui_check_bs.sh 实测崩）。
    onboardingShown = false
    setContentView(R.layout.activity_scripts)
    // ★ 2026-10-03（D2）：进管理器页先把悬浮面板收起。展开态面板占屏右 ~408px
    //   （3200 档实测帧 2792,441 起 396x909），而本页右列的控件正好铺到右缘 ——
    //   「抓包采集（测试）」开关实测中心 (3113,1307) 落在面板帧内，点它会打到面板的「退出」行。
    //   面板靠 ACTION_OUTSIDE 自动收起，但**落在面板内的第一下不会**（那不是 outside），
    //   所以必须在这里主动收，不能等用户点第二下。
    runCatching { com.bettergi.pocket.bridge.OverlayBridge(this).collapse() }
    findViewById<TextView>(R.id.btn_back_overlay).setOnClickListener { launchOverlayAndExit() }
    findViewById<android.view.View>(R.id.status_card).setOnClickListener { openA11yIfNeeded() }
    bindBackgroundModeSection()
    bindCalibrateRow()
    bindGoodSection()
    watchScanState()
    bindSwipeRow()
    bindSwipeTest()
    bindCaptureTest()
    bindCapturePermRow()
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
    hint?.text = "抓包开关在本页下方「抓包采集（测试）」：解齐四段数据后自动入库并收回隧道，" +
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

// ---- ★ #310/M4 后台模式区块（virtualdisplay 分支）：运行模式 + Shizuku 引导 + 虚拟屏监视 ----
//
// 区块结构见 activity_scripts.xml 的 bg_section 注释；三个绑定函数各管一段用户动线：
// [bindRunModeSection]（选模式）→ [bindShizukuCard]（装/启/授权引导）→ [bindVdMonitor]（就绪后起虚拟屏）。
// 全部 findViewById 走可空接收（?: return / ?.),与 bindCaptureTest 等既有绑定同惯例 ——
// 竖屏/横屏两份布局由 dsl 纪律保证 id 集合一致（M4 已同步插入两份），缺 id 时静默退化为不绑定。

/** 后台模式下的目标游戏包名（M4 硬编码原神国服，与悬浮窗既有启动语义一致）。 */
private const val BG_GAME_PACKAGE = "com.miHoYo.Yuanshen"

/** Shizuku 授权请求码（本页与结果监听配对用，任意正数即可，与官方 demo 的 0 同义）。 */
private const val SHIZUKU_PERMISSION_REQUEST_CODE = 31001

/** Shizuku 四态轮询节奏（1s，见 [bindShizukuCard] KDoc 的"轮询而非监听"论证）。 */
private const val SHIZUKU_POLL_INTERVAL_MS = 1_000L

/** 预览刷新节奏（M5：500ms 一拍，与 fps 显示同拍——一次轮询里一起取）。 */
private const val PREVIEW_POLL_INTERVAL_MS = 500L

/** 「启动虚拟屏」链路各步的等待上限（bind 握手/远端调用都走 binder，1s 内应答；给余量）。 */
private const val VD_STEP_TIMEOUT_MS = 10_000L

/** 监视区三态（M4：RUNNING = VD 已建且游戏已发上屏；PENDING/STANDBY 只渲染文案）。 */
private enum class VdUiState { STANDBY, PENDING, RUNNING }

/**
 * 后台模式区块的**本页 UI 态**（每 [MainActivity] 实例一份，随实例死亡自动清）。
 *
 * 为什么是 WeakHashMap 容器而不是 MainActivity 上的 var：M4 纪律**不动 MainActivity.kt**，
 * 而扩展属性没有 backing field；Activity 重建即丢、重新 showScriptManager 即重建，
 * 无跨实例泄漏面。
 */
private class BgUiState {
    /** 四态机最后一次评估结果（按钮分派与监视区可见性的依据）。 */
    var shizukuPhase: ShizukuPhase? = null

    /** 监视区渲染态。 */
    var vdState: VdUiState = VdUiState.STANDBY

    /** 「启动虚拟屏」链路在途标记（防重复点击）。 */
    var vdStarting = false

    /** createDisplay 返回的 displayId（-1 = 未创建/失败），repin / startAppOnDisplay 用。 */
    var vdDisplayId = -1

    /** Shizuku 四态轮询任务（onResume 起 / onPause 停）。 */
    var shizukuPoll: Runnable? = null

    /** 预览刷新轮询任务（M4 占位骨架，随绑定起）。 */
    var previewPoll: Runnable? = null

    /** ★ #310/M6-audit：当前后台会话（帧源在 app 进程；起扫装配与预览都取它）。 */
    var bgSession: com.bettergi.pocket.vd.BackgroundSession? = null
}

private val bgUiStates = java.util.WeakHashMap<MainActivity, BgUiState>()

private fun MainActivity.bgState(): BgUiState = bgUiStates.getOrPut(this) { BgUiState() }

/** 轮询共用的主线程 Handler（延迟初始化，见 [pollHandler]）。 */
private val mainPollHandler by lazy { Handler(Looper.getMainLooper()) }

private fun MainActivity.pollHandler(): Handler = mainPollHandler

/** 本页取设置仓库（bg_section 不在布局时返回 null，调用方安全退化）。 */
private fun MainActivity.settingsRepo(): TriggerSettingsRepository? =
    if (findViewById<LinearLayout?>(R.id.bg_section) != null) TriggerSettingsRepository.app(this) else null

/**
 * 「运行模式」行：初始态读设置仓库，RadioGroup 切换即落盘 + 切换 bg_section 主体可见性。
 *
 * ⚠️ 循环防线：`isChecked = ...` 回填也会触发监听器，与 nameOverrideRow 的
 * 「先 setText 再挂监听」同款先例 —— 这里先回填、后挂监听，杜绝「回填把自己写的值
 * 再写一遍」的回环（值虽然不变不会真落盘，但会多触发一轮监听分发）。
 */
internal fun MainActivity.bindRunModeSection() {
    val bg = findViewById<LinearLayout?>(R.id.bg_section) ?: return
    val fg = findViewById<RadioButton?>(R.id.rb_mode_foreground) ?: return
    val bk = findViewById<RadioButton?>(R.id.rb_mode_background) ?: return
    val group = findViewById<android.widget.RadioGroup?>(R.id.runmode_group) ?: return
    val repo = TriggerSettingsRepository.app(this)
    val applyVisibility = { mode: RunMode ->
        bg.visibility = if (mode == RunMode.BACKGROUND) View.VISIBLE else View.GONE
    }
    // 先回填初始态，再挂监听（防回填触发监听的回环，见 KDoc）
    when (repo.get().runMode) {
        RunMode.BACKGROUND -> bk.isChecked = true
        RunMode.FOREGROUND -> fg.isChecked = true
    }
    applyVisibility(repo.get().runMode)
    group.setOnCheckedChangeListener { _, checkedId ->
        val mode = if (checkedId == R.id.rb_mode_background) RunMode.BACKGROUND else RunMode.FOREGROUND
        repo.setRunMode(mode)
        applyVisibility(mode)
        NoticeCenter.info(
            if (mode == RunMode.BACKGROUND) "已切换到后台模式（虚拟显示器 + Shizuku 提权）" else "已切换到前台模式",
        )
    }
}

/**
 * Shizuku 引导卡：1s 轮询四态机，按 [ShizukuPhase] 渲染状态点 / 文案 / 按钮。
 *
 * 轮询而非监听的理由（M4 口径）：四态里的「安装」与「运行」没有 app 进程内的变更
 * 回调（装完 apk / 用户在 Shizuku 应用里点启动都发生在别的进程），binder 粘性监听
 * 只覆盖「服务连通」一段；1s 轮询一次 assess 便宜（一次 PM 查询 + 两次 binder 探测），
 * 换来全四态的统一刷新路径。轮询只在管理器页前台跑（onResume 起 / onPause 停，挂钩
 * 见 [attachBgSectionLifecycle]）。
 */
internal fun MainActivity.bindShizukuCard() {
    val card = findViewById<LinearLayout?>(R.id.shizuku_card) ?: return
    val dot = findViewById<View?>(R.id.shizuku_status_dot) ?: return
    val text = findViewById<TextView?>(R.id.shizuku_status_text) ?: return
    val btn = findViewById<Button?>(R.id.shizuku_action_btn) ?: return

    /** 按 phase 渲染整卡（文案/颜色规格见 M4 设计稿；READY 时卡片收起）。 */
    fun render(phase: ShizukuPhase) {
        card.visibility = if (phase == ShizukuPhase.READY) View.GONE else View.VISIBLE
        val (dotColor, statusText, action) = when (phase) {
            ShizukuPhase.NOT_INSTALLED -> Triple(
                R.color.pocket_text_muted,
                "Shizuku 未安装：后台模式需要它获得提权",
                "安装 Shizuku",
            )
            ShizukuPhase.NOT_RUNNING -> Triple(
                R.color.pocket_warn,
                "Shizuku 未运行：打开 Shizuku 应用启动服务（需无线调试配对，安卓 11+）",
                "打开 Shizuku 应用",
            )
            ShizukuPhase.NOT_GRANTED -> Triple(
                R.color.pocket_warn,
                "Shizuku 已运行但未授权本应用",
                "请求授权",
            )
            ShizukuPhase.READY -> Triple(
                R.color.pocket_ok,
                "Shizuku 已就绪（adb/root）",
                null,
            )
        }
        dot.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, dotColor),
        )
        text.text = statusText
        if (action != null) {
            btn.visibility = View.VISIBLE
            btn.text = action
        } else {
            btn.visibility = View.GONE
        }
    }

    btn.setOnClickListener {
        // 按钮语义由"最近一次轮询到的 phase"决定（渲染与点击之间状态可能再变，
        // 以轮询快照为准比以按钮文案为准更不撒谎 —— 文案就是那轮快照渲染的）。
        when (bgState().shizukuPhase) {
            ShizukuPhase.NOT_INSTALLED -> installShizuku()
            ShizukuPhase.NOT_RUNNING -> openShizukuApp()
            ShizukuPhase.NOT_GRANTED -> requestShizukuPermission()
            else -> Unit // READY 时按钮已 GONE，防御性 no-op
        }
    }

    startShizukuPoll { phase ->
        bgState().shizukuPhase = phase
        render(phase)
    }
}

/**
 * 四态轮询的启动/停止：onResume 起、onPause 停（本页前台才轮询）。挂在 lifecycle
 * 事件上（[attachBgSectionLifecycle] 注册一次），重复调用先摘旧任务防双跑。
 * M4 简化：onResume 不主动补拍 —— 绑定时的首拍已经覆盖"进页面"场景。
 */
private fun MainActivity.startShizukuPoll(onPhase: (ShizukuPhase) -> Unit) {
    val state = bgState()
    state.shizukuPoll?.let { mainPollHandler.removeCallbacks(it) }
    val task = object : Runnable {
        override fun run() {
            val phase = ShizukuState.assess(this@startShizukuPoll).phase
            onPhase(phase)
            mainPollHandler.postDelayed(this, SHIZUKU_POLL_INTERVAL_MS)
        }
    }
    state.shizukuPoll = task
    mainPollHandler.post(task)
}

private fun MainActivity.stopShizukuPoll() {
    bgState().shizukuPoll?.let { mainPollHandler.removeCallbacks(it) }
    bgState().shizukuPoll = null
}

/** 预览轮询的启动/停止（M4 占位骨架，函数体 TODO；onPause 一样要停）。 */
private fun MainActivity.startPreviewPoll() {
    val state = bgState()
    state.previewPoll?.let { mainPollHandler.removeCallbacks(it) }
    val task = object : Runnable {
        override fun run() {
            refreshPreview()
            mainPollHandler.postDelayed(this, PREVIEW_POLL_INTERVAL_MS)
        }
    }
    state.previewPoll = task
    mainPollHandler.post(task)
}

private fun MainActivity.stopPreviewPoll() {
    bgState().previewPoll?.let { mainPollHandler.removeCallbacks(it) }
    bgState().previewPoll = null
}

/**
 * 轮询生命周期挂钩（幂等，showScriptManager 里绑定时注册一次）：
 * RESUME ⇒ 四态轮询与预览轮询起；PAUSE ⇒ 双双停。用 LifecycleEventObserver 而不是
 * 覆写 MainActivity.onResume/onPause —— 本文件是扩展函数集合，宿主文件不动（M4 纪律）。
 */
private fun MainActivity.attachBgSectionLifecycle() {
    if (bgLifecycleHooked) return
    bgLifecycleHooked = true
    lifecycle.addObserver(LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> {
                startShizukuPoll { phase ->
                    bgState().shizukuPhase = phase
                    // phase 变化时若监视区已渲染，顺带刷新其可见性（READY ⇒ 显出 vd_monitor）
                    refreshMonitorVisibility()
                }
                startPreviewPoll()
            }
            Lifecycle.Event.ON_PAUSE -> {
                stopShizukuPoll()
                stopPreviewPoll()
            }
            else -> Unit
        }
    })
}

/** lifecycle 挂钩的幂等标记（进程级，Activity 重建后 lifecycle 是新实例，重挂无害）。 */
private var bgLifecycleHooked = false

/** 四态最新值驱动 vd_monitor 的显隐（READY 才显；与 bindVdMonitor 首绑共用一份逻辑）。 */
private fun MainActivity.refreshMonitorVisibility() {
    val monitor = findViewById<LinearLayout?>(R.id.vd_monitor) ?: return
    monitor.visibility =
        if (bgState().shizukuPhase == ShizukuPhase.READY) View.VISIBLE else View.GONE
}

/**
 * 「安装 Shizuku」：优先内置 apk 导出 + 系统安装器；资产缺失（[ShizukuInstallHelper.isBundled]
 * = false）退回 GitHub releases 页 —— 与 ShizukuInstallHelper KDoc 预留的 UI 侧回退路径一致。
 */
internal fun MainActivity.installShizuku() {
    if (ShizukuInstallHelper.isBundled(this)) {
        val exported = ShizukuInstallHelper.exportInstaller(this)
        if (exported != null && ShizukuInstallHelper.launchInstaller(this, exported)) {
            NoticeCenter.info("已导出安装包到下载目录，请在系统安装器中确认")
            return
        }
        NoticeCenter.warn("内置安装包导出失败，改跳浏览器下载")
    }
    val url = "https://github.com/RikkaApps/Shizuku/releases"
    runCatching {
        startActivity(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { NoticeCenter.error("打不开浏览器，请手动前往：$url") }
}

/** 「打开 Shizuku 应用」：拉起 Shizuku 主界面（用户在里头无线调试 / Root 启动服务）。 */
internal fun MainActivity.openShizukuApp() {
    val launch = packageManager.getLaunchIntentForPackage(ShizukuState.SHIZUKU_PACKAGE)
    if (launch == null) {
        NoticeCenter.warn("找不到 Shizuku 应用（可能刚卸载）")
        return
    }
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(launch) }
        .onFailure { NoticeCenter.error("拉起 Shizuku 失败：${it.message}") }
}

/**
 * 「请求授权」：`Shizuku.requestPermission` + 一次性结果监听（官方 API）。
 * 回调到达即自注销（监听器只服务这一次请求）；轮询 1s 内也会把授权结果刷进卡片，
 * 监听只负责「弹窗落定的那一刻」给一句明确反馈。
 */
internal fun MainActivity.requestShizukuPermission() {
    // 监听器要引用自身来注销 ⇒ object 表达式拿 `this` 即自身引用（SAM 转换 lambda 拿不到）
    val selfRef = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            if (requestCode != SHIZUKU_PERMISSION_REQUEST_CODE) return
            runCatching { Shizuku.removeRequestPermissionResultListener(this) }
            NoticeCenter.post(
                if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    NoticeCenter.Level.INFO
                } else {
                    NoticeCenter.Level.WARN
                },
                if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    "Shizuku 授权成功，虚拟屏监视区已就位"
                } else {
                    "Shizuku 授权被拒绝，后台模式用不了"
                },
            )
        }
    }
    runCatching { Shizuku.addRequestPermissionResultListener(selfRef) }
        .onFailure { NoticeCenter.error("Shizuku binder 未连接，稍后再试：${it.message}") }
        .onSuccess {
            runCatching { Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE) }
                .onFailure {
                    runCatching { Shizuku.removeRequestPermissionResultListener(selfRef) }
                    NoticeCenter.error("发起授权失败：${it.message}")
                }
        }
}

/**
 * 虚拟屏监视区：启动链（bind → createDisplay → startAppOnDisplay）、状态点渲染与
 * 预览占位。
 *
 * M4 边界（本里程碑刻意收窄，别"顺手"扩）：
 * - 预览：~~JPEG 帧传输走 AIDL 尚未加（M5），[refreshPreview] 留 TODO~~ ★ M5 已接
 *   （AIDL `capturePreviewJpeg`，500ms 轮询，见 [refreshPreview]）。
 * - 帧率：★ M5 已接（AIDL `getGameFps`，服务端 1s 采样差分，-- fps = 无样本）。
 * - 停止：**不显式 destroy**（会话收尾 = M5），只落日志 + 状态回待机；「关闭虚拟屏」
 *   在 M4 语义 = "不再盯它"，VD 与游戏进程保持原样。
 * - 「游戏调回前台」：发起即可，失败打 NoticeCenter（三级 repin 防漂移的服务端实现在
 *   VirtualDisplayManager.repin，本侧不做重试）。
 */
internal fun MainActivity.bindVdMonitor() {
    val dot = findViewById<View?>(R.id.vd_status_dot) ?: return
    val status = findViewById<TextView?>(R.id.vd_status_text) ?: return
    val fps = findViewById<TextView?>(R.id.vd_fps_text) ?: return
    val btnStart = findViewById<Button?>(R.id.btn_vd_start) ?: return
    val btnStop = findViewById<Button?>(R.id.btn_vd_stop) ?: return
    val btnFront = findViewById<Button?>(R.id.btn_vd_front) ?: return

    /** 三态渲染：运行中（VD 已建 + 游戏已发上屏）/ 待执行 / 待机。后两态 M4 只有文案。 */
    fun renderVd(state: VdUiState) {
        val (dotColor, text) = when (state) {
            VdUiState.RUNNING -> R.color.pocket_ok to "游戏运行中"
            VdUiState.PENDING -> R.color.pocket_warn to "待执行"
            VdUiState.STANDBY -> R.color.pocket_warn to "待机"
        }
        dot.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, dotColor),
        )
        status.text = text
        // ★ M5：帧率来自预览轮询（refreshPreview 每拍更新；此处只兜首帧前的占位）
        fps.text = "-- fps"
    }

    fun render() {
        refreshMonitorVisibility()
        renderVd(bgState().vdState)
        btnStart.isEnabled = !bgState().vdStarting
        btnStart.alpha = if (bgState().vdStarting) 0.4f else 1f
    }

    // 先回填当前态再挂监听（与 bindRunModeSection 的回环防线同理由）
    render()
    btnStart.setOnClickListener {
        if (bgState().vdStarting) {
            NoticeCenter.info("启动已在进行，稍等")
            return@setOnClickListener
        }
        startVdChain(::render)
    }
    btnStop.setOnClickListener {
        // ★ #310/M6-audit：真正收尾——停帧源 + 服务端 destroyDisplay + 清进程级会话。
        //   （M4 的"只落日志不 destroy"语义作废：装配接线后会话被起扫消费，必须真收。）
        runCatching {
            bgState().bgSession?.stop()
            BackgroundSession.current?.stop()
        }.onFailure { android.util.Log.w("BetterGI.VD", "stop session failed", it) }
        BackgroundSession.clear()
        bgState().bgSession = null
        bgState().vdDisplayId = -1
        bgState().vdState = VdUiState.STANDBY
        render()
    }
    btnFront.setOnClickListener {
        val svc = BgiRemoteManager.service()
        val displayId = bgState().vdDisplayId
        if (svc == null || displayId < 0) {
            NoticeCenter.warn("虚拟屏还没起来，先点「启动虚拟屏」")
            return@setOnClickListener
        }
        // AIDL repinToDisplay：把游戏 task 拉回 VD 显示（M4 简化：发起，失败打提醒）
        val ok = runCatching { svc.repinToDisplay(BG_GAME_PACKAGE, displayId) }
            .onFailure { android.util.Log.w("BetterGI.VD", "repinToDisplay failed", it) }
            .getOrDefault(false)
        if (ok) NoticeCenter.info("已发起：游戏调回虚拟屏前台")
        else NoticeCenter.error("游戏调回前台失败（详见日志）")
    }
}

/**
 * 「启动虚拟屏」全链路（M4 规格）：`BgiRemoteManager.start` → 等 CONNECTED（状态监听）
 * → `createDisplay` → `startAppOnDisplay(原神)`。每步结果都打 NoticeCenter；链路跑在
 * pageScope 协程里（binder 调用短平快，等 CONNECTED 用回调桥成挂起，不占主线程）。
 *
 * 失败即停在中断点：状态回待机 + 误差文案上横幅，用户重按按钮重来（BgiRemoteManager
 * 的幂等语义保证 start 可重复发起）。
 */
private fun MainActivity.startVdChain(render: () -> Unit) {
    val state = bgState()
    state.vdStarting = true
    state.vdState = VdUiState.PENDING
    render()
    pageScope.launch {
        var failure: String? = null
        // ① 前置：Shizuku 四态必须 READY（BgiRemoteManager.start 内部也校验，这里先给用户一句人话）
        val phase = state.shizukuPhase ?: ShizukuState.assess(this@startVdChain).phase
        if (phase != ShizukuPhase.READY) {
            failure = "Shizuku 未就绪（当前 $phase），先完成上面的引导"
        } else {
            // ② bindUserService；已连接时 start 返回 false 但服务可用 ⇒ 按"已在"继续
            val started = BgiRemoteManager.start(this@startVdChain)
            val alreadyConnected = !started && BgiRemoteManager.state == BgiRemoteManager.RemoteServiceState.CONNECTED
            if (!started && !alreadyConnected) {
                NoticeCenter.warn("远程服务绑定未发起：请确认 Shizuku 状态")
            }
            // ③ 等 CONNECTED（状态监听桥成挂起；已 CONNECTED 直接过）
            if (alreadyConnected || awaitRemoteConnected(VD_STEP_TIMEOUT_MS)) {
                val svc = BgiRemoteManager.service()
                if (svc == null) {
                    failure = "远程服务握手未完成（binder 为空），请重试"
                } else {
                    // ★ #310/M6-audit：改用 BackgroundSession——app 进程建 ImageReader（帧源），
                    //   Surface 经 AIDL 给服务进程建 VD ⇒ 扫描引擎直接吃 VD 帧（零跨进程传输）。
                    //   旧路径 svc.createDisplay()（服务端自建 Surface）保留给纯预览场景。
                    val s = BackgroundSession().also { bgState().bgSession = it }
                    val fail = withContext(Dispatchers.IO) { s.start() }
                    if (fail == null) {
                        state.vdDisplayId = s.displayId
                        state.vdState = VdUiState.RUNNING
                        NoticeCenter.info("虚拟屏已启动（displayId=${s.displayId}），原神已发上屏")
                    } else {
                        state.vdState = VdUiState.PENDING
                        failure = fail
                        s.stop()
                        state.bgSession = null
                    }
                }
            } else {
                failure = "远程服务连接超时（${VD_STEP_TIMEOUT_MS / 1000}s），请重试"
            }
        }
        failure?.let { NoticeCenter.error(it) }
        state.vdStarting = false
        render()
    }
}

/**
 * 等 BgiRemoteManager 进 CONNECTED（状态监听桥成挂起）：
 * - 注册即回放当前态（sticky）⇒ 已连接时立即完成；
 * - withTimeoutOrNull 包住挂起点：超时即取消挂起并经 invokeOnCancellation 注销监听，
 *   返回 false（调用方给"连接超时"文案）。
 */
private suspend fun MainActivity.awaitRemoteConnected(timeoutMs: Long): Boolean {
    val connected = withTimeoutOrNull(timeoutMs) {
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            // listener 要在自身回调里自注销 ⇒ object 表达式（lambda 自引用在初始化期不可见）
            val listener = object : (BgiRemoteManager.RemoteServiceState) -> Unit {
                override fun invoke(s: BgiRemoteManager.RemoteServiceState) {
                    if (s == BgiRemoteManager.RemoteServiceState.CONNECTED) {
                        BgiRemoteManager.removeStateListener(this)
                        if (cont.isActive) cont.resume(Unit, onCancellation = null)
                    }
                }
            }
            BgiRemoteManager.addStateListener(listener)
            cont.invokeOnCancellation { BgiRemoteManager.removeStateListener(listener) }
        }
    }
    return connected != null
}

/**
 * 预览刷新（★ #310/M5 接帧流）：AIDL `capturePreviewJpeg` → decodeByteArray → setImageBitmap。
 * - binder 调用走 pageScope 的 IO 分支（JPEG 传输 + 解码在主线程会卡顿）；
 * - 服务返回 null/空（VD 未建/帧缓存空/远端异常）⇒ 按「预览不可用」处理：清空 ImageView
 *   + hint 文案提示（不误显示成黑帧——黑帧是服务端**有意**发的全 0 帧，解码出来也是黑图，
 *   二者用 hint 文案区分语义）；
 * - fps 同拍顺带：`getGameFps()` → "N fps"（-1 = 尚无样本，显示 "-- fps"）。
 */
private fun MainActivity.refreshPreview() {
    // ★ #310/M6-audit：预览改从**扫描实际使用的 app 进程帧源**取（BackgroundSession），
    //   不再走服务端自建 Surface 的 AIDL JPEG——两者是不同的 VD（服务端那个只服务纯预览，
    //   装配接线后扫描用的是 app Surface 那条）。预览与扫描画面必须是同一块屏。
    val session = bgState().bgSession ?: BackgroundSession.current ?: return
    pageScope.launch(Dispatchers.IO) {
        val bmp = session.previewBitmap()
        val fps = session.fpsEstimate()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            val preview = findViewById<android.widget.ImageView?>(R.id.vd_preview)
            val hint = findViewById<TextView?>(R.id.vd_preview_hint)
            if (bmp != null) {
                preview?.setImageBitmap(bmp)
                hint?.text = "预览"
            } else {
                preview?.setImageBitmap(null)
                hint?.text = "预览不可用（虚拟屏未出帧）"
            }
            findViewById<TextView?>(R.id.vd_fps_text)?.text =
                if (fps >= 0) "$fps fps" else "-- fps"
        }
    }
}

/**
 * 绑定后台模式区块时的总入口（showScriptManager 调）：三个绑定 + lifecycle 挂钩。
 * 拆出来是因为挂钩只想做一次，而三个绑定函数会被独立调用（横竖屏重建路径）。
 */
internal fun MainActivity.bindBackgroundModeSection() {
    if (findViewById<LinearLayout?>(R.id.bg_section) == null) return
    attachBgSectionLifecycle()
    bindRunModeSection()
    bindShizukuCard()
    bindVdMonitor()
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

/** #204：「抓包权限」收在页底「抓包采集（测试）」下面，点行开合（与 swipe_row 同一套做法）。 */
internal fun MainActivity.bindCapturePermRow() {
    val body = findViewById<android.view.View>(R.id.capture_section)
    val chevron = findViewById<TextView>(R.id.capture_perm_chevron)
    findViewById<android.view.View>(R.id.capture_perm_row).setOnClickListener {
        val show = body.visibility != android.view.View.VISIBLE
        body.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
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
                    R.color.pocket_text_muted,
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
    return row
}

/** 副标题 = 该脚本声明的动作 + 来源 + 校验问题（问题必须显示出来，不能静默丢）。 */
internal fun MainActivity.subtitleOf(e: ScriptStore.Entry): String {
    val parts = ArrayList<String>()
    parts.addAll(e.actions.map { it.label })
    parts.add("内置")
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

/**
 * 订阅设置变化，让「导出 JSON」的可用性跟着扫描起止**当场**变（#203）。
 *
 * 只靠 onResume 重绘不够：用户停在管理器页等这一轮跑完时页面不会 resume，
 * 按钮就一直停在"可点"的状态。仓库的监听器统一 post 到主线程，可直接碰 View。
 */
internal fun MainActivity.watchScanState() {
    if (scanStateWatcher != null) return          // 本页可被反复打开，只订阅一次
    val w: (TriggerSettings) -> Unit = { if (managerShown) renderGoodSection() }
    scanStateWatcher = w
    TriggerSettingsRepository.app(this).addListener(w)
}

internal fun MainActivity.unwatchScanState() {
    scanStateWatcher?.let { TriggerSettingsRepository.app(this).removeListener(it) }
    scanStateWatcher = null
}

internal fun MainActivity.renderGoodSection() {
    val state = GoodRepository.state(this)
    findViewById<TextView>(R.id.good_input_name).text = state?.sourceName ?: "未选择"
    findViewById<android.widget.ImageView>(R.id.btn_good_clear).visibility =
        if (state != null) android.view.View.VISIBLE else android.view.View.GONE

    // ★ #203：扫描没跑完就导出 = 导出**半份数据**，所以这里事前禁用，不再等用户点下去才弹
    //   一句"运行中，导出请等本轮结束"（那是悬浮窗那条事后拦，见 OverlayScriptRows 的 export 分支）。
    //   判据与悬浮窗同源：`scanEnabled`（面板 ▶ 起跑即置真，收尾复位）。
    val scanning = TriggerSettingsRepository.app(this).get().scanEnabled
    findViewById<android.widget.ImageView>(R.id.btn_good_export).apply {
        isEnabled = !scanning
        alpha = if (scanning) 0.4f else 1f
    }
    val export = GoodRepository.lastExport(this)
    findViewById<TextView>(R.id.good_export_name).text =
        (export ?: "还没有导出") + if (scanning) " · 扫描中，结束后可导出" else ""

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

// ---- 抓包采集（2026-10-03 由悬浮窗迁入，与滑动测试同列**测试功能**）----

/**
 * 绑定「抓包采集（测试）」开关。
 *
 * 与旧悬浮窗版的根本差别：面板跑在 `:a11y`，而会话与隧道只能在主进程 ⇒ 那边要"过桥发指令 +
 * 主进程把状态推回来"。管理器与 [CaptureSession] **同进程**，所以这里直接起服务、直接订阅
 * `ui`，旧的三条桥 method 与整条推送通道一并删掉。
 *
 * 开关位置以**会话真实状态**为准，不以玩家点了什么为准：VPN 被拒时 `ui` 停在未抓包 ⇒ 开关自己落回。
 */
internal fun MainActivity.bindCaptureTest() {
    val sw = findViewById<SwitchCompat?>(R.id.capture_test_switch) ?: return
    val status = findViewById<TextView?>(R.id.capture_test_status)
    var applyingState = false
    sw.setOnCheckedChangeListener { _, checked ->
        if (applyingState) return@setOnCheckedChangeListener
        val intent = Intent(this, TriggerForegroundService::class.java).apply {
            action = if (checked) {
                TriggerForegroundService.ACTION_CAPTURE_START
            } else {
                TriggerForegroundService.ACTION_CAPTURE_STOP
            }
        }
        ContextCompat.startForegroundService(this, intent)
    }
    captureTestJob?.cancel()
    captureTestJob = pageScope.launch {
        CaptureSession.ui.collect { state ->
            applyingState = true
            sw.isChecked = state.running
            applyingState = false
            status?.text = state.brief()
        }
    }
}
