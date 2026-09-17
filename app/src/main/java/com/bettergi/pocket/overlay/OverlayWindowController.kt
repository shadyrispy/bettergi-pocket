package com.bettergi.pocket.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.widget.ImageViewCompat
import com.bettergi.pocket.MainActivity
import com.bettergi.pocket.R
import com.bettergi.pocket.bilibili.BilibiliSpaceOpener
import com.bettergi.pocket.dsl.ScriptStore
import com.bettergi.pocket.feature.autopick.AutoPickFeature
import com.bettergi.pocket.feature.autoskip.AutoSkipEvents
import com.bettergi.pocket.genshin.GenshinLaunchResult
import com.bettergi.pocket.genshin.GenshinLauncher
import com.bettergi.pocket.genshin.GenshinPackages
import com.bettergi.pocket.input.AccessibilityServiceHealth
import com.bettergi.pocket.input.InputAccessibilityService
import com.bettergi.pocket.log.RecognitionLog
import com.bettergi.pocket.settings.TriggerSettings
import com.bettergi.pocket.settings.TriggerSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OverlayWindowController(
    private val context: Context,
    private val settingsRepository: TriggerSettingsRepository,
    private val genshinLauncher: GenshinLauncher = GenshinLauncher(context),
    private val onExit: () -> Unit = {},
    private val onShareGoodRequested: () -> Unit = {},
) : AutoSkipEvents {
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_BetterGIPocket)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var lastScreenW = 0
    private var lastScreenH = 0
    private var watchingScreen = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            relocateOverlays(force = false)
        }
    }

    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            relocateOverlays(force = true)
        }

        override fun onLowMemory() = Unit
    }

    private var rootView: View? = null
    private var bubbleView: View? = null
    private var panelView: View? = null
    private var panelScroll: View? = null
    private var statusDot: View? = null
    private var statusText: TextView? = null
    private var chatBadge: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var snapAnimator: ValueAnimator? = null

    private var updatingUi = false
    private var expanded = false
    private var transforming = false
    private var pendingCollapseOnOutside = false
    private var switchEnabled: SwitchCompat? = null
    private var switchAutoSkip: SwitchCompat? = null
    private var switchQuickSkip: SwitchCompat? = null
    private var switchAutoPick: SwitchCompat? = null
    private var switchAutoLaunch: SwitchCompat? = null
    private var switchScan: SwitchCompat? = null
    private var scanProgress: TextView? = null
    private var launchHint: TextView? = null
    private var launchSubtitle: TextView? = null
    private var logToggleButton: ImageButton? = null
    private var rowAutoSkip: View? = null
    private var rowQuickSkip: View? = null
    private var rowAutoPick: View? = null
    private var rowLaunch: View? = null
    private var autoSkipExtras: View? = null
    private var autoSkipChevron: ImageView? = null
    private var autoSkipMenuExpanded = false
    private var scanExtras: View? = null
    private var scanChevron: ImageView? = null
    /** 脚本按钮视图（flowKey → TextView）：**由 DSL `ui` 段驱动重建**，见 [renderFlowButtons]。 */
    private val flowViews = LinkedHashMap<String, TextView>()

    /** flowKey → 脚本自报按钮文字（`ui.label`）：常态/运行中态文案切换复用。 */
    private val flowLabels = LinkedHashMap<String, String>()

    /** 脚本按钮容器（`overlay_scan_flow_group`）。 */
    private var flowGroup: LinearLayout? = null

    /**
     * 刷新脚本按钮态。
     * - 选中（= 当前 `scanFlow`）→ 金色；其余常态灰。
     * - 「运行中」态（用户 2026-09-18 裁定②）：**仅当前流程**转进行态（灰字 + 「▶ 运行中…」）；
     *   运行期其余按钮一并禁点（避免扫描中途切流程），alpha 压暗。
     */
    private fun applyFlowSelection(flow: String) {
        if (flowViews.isEmpty()) return
        val on = context.getColor(R.color.overlay_gold)
        val off = context.getColor(R.color.overlay_text)
        val muted = context.getColor(R.color.overlay_text_muted)
        val running = settingsRepository.get().scanEnabled
        flowViews.forEach { (k, v) ->
            val isRunning = running && k == flow
            v.text = if (isRunning) "\u25b6 运行中…" else (flowLabels[k] ?: k)
            v.setTextColor(if (isRunning) muted else if (k == flow) on else off)
            v.isEnabled = !running
            v.alpha = if (running && !isRunning) 0.45f else 1f
        }
    }

    /**
     * 重建脚本按钮区（P4a）：**唯一来源是脚本自身** —— 只渲染「已启用 且 声明了 `ui`」的流程，
     * 顺序取 `ui.order`（[ScriptStore.list] 已按 order 排好），文字取 `ui.label`。
     * ⇒ 「悬浮窗显示哪些功能」完全由脚本（JSON）决定，改脚本即改 UI。
     */
    private fun renderFlowButtons() {
        val container = flowGroup ?: return
        container.removeAllViews()
        flowViews.clear()
        flowLabels.clear()
        val entries = runCatching { ScriptStore.list(context) }
            .getOrDefault(emptyList())
            .filter { it.enabled && it.hasUi }
        if (entries.isEmpty()) {
            container.addView(
                TextView(themedContext).apply {
                    text = "（无启用脚本——长按「设置」导入/开启）"
                    setTextColor(context.getColor(R.color.overlay_text_muted))
                    textSize = 11f
                },
            )
            return
        }
        for (e in entries) {
            val button = TextView(themedContext).apply {
                text = e.label
                gravity = Gravity.CENTER
                minHeight = dp(40)
                textSize = 12f
                setBackgroundResource(R.drawable.bg_overlay_row_selectable)
                contentDescription = "脚本：${e.label}"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) }
                setOnClickListener { startScan(e.key) }
            }
            flowLabels[e.key] = e.label
            flowViews[e.key] = button
            container.addView(button)
        }
        applyFlowSelection(settingsRepository.get().scanFlow)
    }

    /**
     * 起流程（fix53 三件套保持不变：开扫描开关 + 无障碍自检 + 投影自开）。
     * @param flowKey 为空 ⇒ 用当前已选流程（「开始扫描」按钮走这条）。
     */
    private fun startScan(flowKey: String? = null) {
        if (flowKey != null) settingsRepository.setScanFlow(flowKey)
        settingsRepository.setScanEnabled(true)
        InputAccessibilityService.ensureEnabled(themedContext, "请开启无障碍权限，才能模拟扫描点击")
        if (!settingsRepository.get().screenShareEnabled) {
            settingsRepository.setScreenShareEnabled(true)
        }
    }

    /** 长按「设置」：拉起脚本管理器（MainActivity，[MainActivity.EXTRA_FROM_OVERLAY]）。 */
    private fun openScriptManager() {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            putExtra(MainActivity.EXTRA_FROM_OVERLAY, true)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            Toast.makeText(context, "无法打开管理器：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private var scanMaxPagesEdit: EditText? = null
    private var scanMenuExpanded = false
    private var launchExtras: View? = null
    private var launchChevron: ImageView? = null
    private var launchMenuExpanded = false
    private var logHandleView: View? = null
    private var logBodyView: View? = null
    private var logTitle: TextView? = null
    private var logText: TextView? = null
    private var logScroll: ScrollView? = null
    private var logHandleParams: WindowManager.LayoutParams? = null
    private var logBodyParams: WindowManager.LayoutParams? = null
    /** §13：全局日志订阅句柄（缓冲已迁至 [RecognitionLog]，本类只负责渲染）。 */
    private var logListener: ((List<RecognitionLog.Entry>) -> Unit)? = null
    private var logWindowVisible = false
    private var talkingUntilMs: Long = 0L
    private val logTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    private val clearTalkingRunnable = Runnable { refreshStatus() }

    private val idleFadeRunnable = Runnable { fadeBubble(IDLE_ALPHA) }
    private var a11yWarningReady = false
    private val refreshA11ySoon = Runnable { refreshStatus() }
    private val refreshA11yLater = Runnable {
        a11yWarningReady = true
        refreshStatus()
    }
    private var a11yReceiverRegistered = false
    private var scriptsReceiverRegistered = false

    /** 脚本清单变更（导入/开关/恢复内置）⇒ 重建按钮区。 */
    private val scriptsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            mainHandler.post { renderFlowButtons() }
        }
    }
    private val a11yReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshStatus()
        }
    }

    private val settingsListener: (TriggerSettings) -> Unit = { settings ->
        updatingUi = true
        try {
            switchEnabled?.isChecked = settings.screenShareEnabled
            switchAutoSkip?.isChecked = settings.autoSkipEnabled
            switchQuickSkip?.isChecked = settings.quickSkipDialogueEnabled
            switchAutoPick?.isChecked = settings.autoPickEnabled
            switchAutoLaunch?.isChecked = settings.autoLaunchGenshinEnabled
            switchScan?.isChecked = settings.scanEnabled
            applyFeatureEnabled(settings)
            // 运行中态：仅当前流程按钮转进行态（用户 2026-09-18 裁定②）
            applyFlowSelection(settings.scanFlow)
            refreshLaunchHint()
            refreshStatus()
        } finally {
            updatingUi = false
        }
    }

    fun show() {
        if (rootView != null) return
        if (!Settings.canDrawOverlays(context)) return

        val root = LayoutInflater.from(themedContext).inflate(R.layout.overlay_window, null)
        val bubble = root.findViewById<View>(R.id.overlay_bubble)
        val panel = root.findViewById<View>(R.id.overlay_panel)
        val collapse = root.findViewById<ImageButton>(R.id.overlay_collapse)
        val header = root.findViewById<View>(R.id.overlay_header)
        val enabledSwitch = root.findViewById<SwitchCompat>(R.id.overlay_switch_enabled)
        val autoSkipSwitch = root.findViewById<SwitchCompat>(R.id.overlay_switch_auto_skip)
        val quickSkipSwitch = root.findViewById<SwitchCompat>(R.id.overlay_switch_quick_skip)
        val autoPickSwitch = root.findViewById<SwitchCompat>(R.id.overlay_switch_auto_pick)
        val autoLaunchSwitch = root.findViewById<SwitchCompat>(R.id.overlay_switch_auto_launch)
        val scanSwitch = root.findViewById<SwitchCompat>(R.id.overlay_switch_scan)
        val logToggle = root.findViewById<ImageButton>(R.id.overlay_log_toggle)

        bubbleView = bubble
        panelView = panel
        panelScroll = root.findViewById(R.id.overlay_panel_scroll)
        statusDot = root.findViewById(R.id.overlay_status_dot)
        statusText = root.findViewById<TextView>(R.id.overlay_status_text).also { text ->
            text.setOnClickListener {
                if (InputAccessibilityService.health(themedContext) != AccessibilityServiceHealth.State.CONNECTED) {
                    InputAccessibilityService.ensureEnabled(themedContext)
                }
            }
        }
        chatBadge = root.findViewById(R.id.overlay_chat_badge)
        switchEnabled = enabledSwitch
        switchAutoSkip = autoSkipSwitch
        switchQuickSkip = quickSkipSwitch
        switchAutoPick = autoPickSwitch
        switchAutoLaunch = autoLaunchSwitch
        switchScan = scanSwitch
        scanProgress = root.findViewById(R.id.overlay_scan_progress)
        launchHint = root.findViewById(R.id.overlay_auto_launch_hint)
        launchSubtitle = root.findViewById(R.id.overlay_launch_subtitle)
        logToggleButton = logToggle
        rowAutoSkip = root.findViewById(R.id.overlay_row_auto_skip)
        rowQuickSkip = root.findViewById(R.id.overlay_row_quick_skip)
        rowLaunch = root.findViewById(R.id.overlay_row_launch)
        rowAutoPick = root.findViewById<View>(R.id.overlay_row_auto_pick).also { row ->
            row.visibility = if (AutoPickFeature.AVAILABLE) View.VISIBLE else View.GONE
            if (!AutoPickFeature.AVAILABLE) {
                settingsRepository.setAutoPickEnabled(false)
            }
        }
        autoSkipExtras = root.findViewById(R.id.overlay_auto_skip_extras)
        autoSkipChevron = root.findViewById(R.id.overlay_auto_skip_chevron)
        launchExtras = root.findViewById(R.id.overlay_launch_extras)
        launchChevron = root.findViewById(R.id.overlay_launch_chevron)

        // 扫描控制区（fix53：开始/停止/flow/maxPages/分享 GOOD 全部走悬浮窗，零通知依赖）
        scanExtras = root.findViewById(R.id.overlay_scan_extras)
        scanChevron = root.findViewById(R.id.overlay_scan_chevron)
        // 脚本按钮区（P4a）：**由 DSL `ui` 段驱动**——只渲染「已启用且声明 ui」的流程，按 ui.order 排。
        // ⚠️ 悬浮窗内不用系统 PopupMenu/Spinner（overlay 类型窗口无 Activity token → BadTokenException 风险）
        flowGroup = root.findViewById(R.id.overlay_scan_flow_group)
        renderFlowButtons()
        scanMaxPagesEdit = root.findViewById<EditText>(R.id.overlay_scan_max_pages).apply {
            val saved = settingsRepository.get().scanMaxPages
            setText(if (saved <= 0) "" else saved.toString())
            setOnFocusChangeListener { _, has -> setPanelFocusable(has) }
            setOnEditorActionListener { _, _, _ ->
                persistMaxPages()
                setPanelFocusable(false)
                true
            }
        }
        root.findViewById<View>(R.id.overlay_scan_start).setOnClickListener { startScan() }
        root.findViewById<View>(R.id.overlay_scan_stop).setOnClickListener {
            settingsRepository.setScanEnabled(false)
        }
        // 「开始导出」（内置，不写进脚本）：复用既有 GOOD 导出链（service → FileProvider 分享）。
        // 扫描进行中禁点 —— 避免导出半截数据（用户 2026-09-18 裁定④/§9.1）。
        root.findViewById<View>(R.id.overlay_scan_export).setOnClickListener {
            if (settingsRepository.get().scanEnabled) {
                Toast.makeText(context, "扫描进行中，导出请等本轮结束", Toast.LENGTH_SHORT).show()
            } else {
                onShareGoodRequested()
            }
        }
        // 「设置」（内置）：**长按**进入脚本管理器；单击给提示（用户 2026-09-18 需求②）。
        root.findViewById<View>(R.id.overlay_settings).also { settings ->
            settings.setOnClickListener {
                Toast.makeText(context, "长按「设置」进入脚本管理器", Toast.LENGTH_SHORT).show()
            }
            settings.setOnLongClickListener {
                openScriptManager()
                true
            }
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // ★ 2026-09-14（用户定稿）：悬浮窗**只允许沿屏幕右侧边缘纵向移动**，默认落在右上角
            //   游戏按钮带（背包/角色/返回，1440 基准 y≈36..125px）**下方**，避免误触。
            //   gravity 用 TOP|END ⇒ x = 距右缘偏移，钉死 dp(8)；wrap_content 无需先量宽度 ⇒ 无"先左后右"闪位。
            gravity = Gravity.TOP or Gravity.END
            x = overlayRightMarginPx()
            y = prefs.getInt(KEY_Y, 0)   // 0 ⇒ 首帧后由 clampOverlayPosition() 抬到按钮带下方
        }

        setupDragAndClick(bubble, layoutParams) {
            setExpanded(true)
        }
        setupDrag(header, layoutParams, snapOnRelease = false)
        collapse.setOnClickListener { setExpanded(false) }
        root.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                collapseOnOutsideTouch()
                true
            } else {
                false
            }
        }
        logToggle.setOnClickListener { setLogWindowVisible(!logWindowVisible) }
        rowAutoSkip?.setOnClickListener { setAutoSkipMenuExpanded(!autoSkipMenuExpanded) }
        rowLaunch?.setOnClickListener { setLaunchMenuExpanded(!launchMenuExpanded) }
        root.findViewById<View>(R.id.overlay_launch).setOnClickListener { launchGenshinFromButton() }
        root.findViewById<ImageButton>(R.id.overlay_bilibili).also { button ->
            ImageViewCompat.setImageTintList(button, null)
            button.setOnClickListener { openBilibiliSpace() }
        }
        root.findViewById<View>(R.id.overlay_exit).setOnClickListener { exitAssistant() }
        root.findViewById<View>(R.id.overlay_row_scan).also { row ->
            row.setOnClickListener { setScanMenuExpanded(!scanMenuExpanded) }
            // 双击语义冲突防护：chevron 与开关并排，点击行体展开；开关自身事件不冒泡
        }

        enabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            settingsRepository.setScreenShareEnabled(isChecked)
        }
        autoSkipSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            settingsRepository.setAutoSkipEnabled(isChecked)
            if (isChecked) {
                InputAccessibilityService.ensureEnabled(themedContext, "请开启无障碍权限，才能模拟点击对话选项")
            }
        }
        quickSkipSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            settingsRepository.setQuickSkipDialogueEnabled(isChecked)
        }
        autoPickSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            settingsRepository.setAutoPickEnabled(isChecked)
            if (isChecked) {
                InputAccessibilityService.ensureEnabled(themedContext, "请开启无障碍权限，才能模拟点击拾取")
            }
        }
        autoLaunchSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            settingsRepository.setAutoLaunchGenshinEnabled(isChecked)
        }
        scanSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            settingsRepository.setScanEnabled(isChecked)
            if (isChecked) {
                InputAccessibilityService.ensureEnabled(themedContext, "请开启无障碍权限，才能模拟扫描点击")
                if (!settingsRepository.get().screenShareEnabled) {
                    // 扫描硬前提：投影（P0 设计，API 29+ 门控由入口保证）
                    settingsRepository.setScreenShareEnabled(true)
                }
            }
        }
        rootView = root
        params = layoutParams
        windowManager.addView(root, layoutParams)
        // ★ 2026-09-14：wrap_content 宽度 layout 后才有 ⇒ 首帧后立刻规范化（右缘 + 按钮带下方，避免左缘闪位）
        root.post {
            clampOverlayPosition(layoutParams)
            updateLayout(layoutParams)
        }
        setLogWindowVisible(prefs.getBoolean(KEY_LOG_VISIBLE, false), persist = false)
        setAutoSkipMenuExpanded(prefs.getBoolean(KEY_AUTO_SKIP_EXPANDED, false), persist = false)
        setLaunchMenuExpanded(prefs.getBoolean(KEY_LAUNCH_EXPANDED, false), persist = false)
        setScanMenuExpanded(prefs.getBoolean(KEY_SCAN_EXPANDED, false), persist = false)
        applyFlowSelection(settingsRepository.get().scanFlow)
        settingsRepository.addListener(settingsListener)
        startScreenWatch()
        a11yWarningReady = false
        registerA11yReceiver()
        registerScriptsReceiver()
        mainHandler.postDelayed(refreshA11ySoon, 400L)
        mainHandler.postDelayed(refreshA11yLater, 2000L)
        root.post {
            rememberScreen()
            // ★ 2026-09-16（审计 P2-1）：改走 clampOverlayPosition —— 本类 gravity 已是 TOP|END，
            //   而 clampToScreen/snapToEdge 内部按「x = 左坐标」运算（snapToEdge 的
            //   `targetX = screen.first − width` 分支在 END 下会把窗推去屏幕左侧）⇒ 主窗一律不再经过它们。
            clampOverlayPosition(layoutParams)
            updateLayout(layoutParams)
            scheduleIdleFade()
        }
    }

    /**
     * 无障碍手势会先打到可触摸的悬浮窗。只有点击落在这些窗口上时才临时穿透，
     * 避免每次模拟点击都改 FLAG_NOT_TOUCHABLE 导致窗口闪烁。
     */
    fun prepareClickPassthrough(x: Int, y: Int): Boolean {
        // 扫描期常驻穿透（setScanClickThrough(true)）时无需逐点处理，也不允许 restore
        if (scanClickThrough) return false
        var needed = false
        if (windowContains(params, rootView, x, y)) {
            applyTouchPassthrough(params, rootView, passthrough = true)
            needed = true
        }
        if (windowContains(logHandleParams, logHandleView, x, y)) {
            applyTouchPassthrough(logHandleParams, logHandleView, passthrough = true)
            needed = true
        }
        return needed
    }

    fun restoreClickPassthrough() {
        if (scanClickThrough) return
        applyTouchPassthrough(params, rootView, passthrough = false)
        applyTouchPassthrough(logHandleParams, logHandleView, passthrough = false)
    }

    /**
     * 扫描期输入穿透开关。
     *
     * ⚠️ 根因修复（equip18-23 实证）：悬浮窗（悬浮球/日志把手）覆盖游戏界面左上区域时，
     * 逐点 prepare/restore 的 FLAG_NOT_TOUCHABLE 时序无法保证手势 UP 也穿透 → 游戏只见
     * DOWN 无 UP → 不触发 click（名册网格 9 格全停首格；非覆盖区右下按钮正常）。
     * 扫描期改为**常驻 NOT_TOUCHABLE**（窗仍可见，仅不可交互）；hidden=true 时直接 GONE
     * 用于排查对照。
     */
    @Volatile
    var scanClickThrough: Boolean = false
        private set

    fun setScanClickThrough(enabled: Boolean, hidden: Boolean = false) {
        scanClickThrough = enabled
        val runnable = {
            if (hidden && enabled) {
                rootView?.visibility = android.view.View.GONE
                logHandleView?.visibility = android.view.View.GONE
            } else {
                rootView?.visibility = android.view.View.VISIBLE
            }
            applyTouchPassthrough(params, rootView, passthrough = enabled)
            applyTouchPassthrough(logHandleParams, logHandleView, passthrough = enabled)
        }
        if (rootView?.handler?.looper == android.os.Looper.myLooper()) runnable()
        else rootView?.post(runnable) ?: Unit
    }

    /** 扫描进度副文本（主线程调用；P1-c 悬浮窗入口）。 */
    fun updateScanProgress(text: String) {
        scanProgress?.text = text
    }

    private fun setPanelFocusable(focusable: Boolean) {
        val lp = params ?: return
        val root = rootView ?: return
        val has = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0
        if (focusable == !has) return // 状态已是目标态
        lp.flags = if (focusable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        windowManager.updateViewLayout(root, lp)
    }

    private fun applyTouchPassthrough(
        lp: WindowManager.LayoutParams?,
        view: View?,
        passthrough: Boolean,
    ) {
        if (lp == null || view == null) return
        val hasFlag = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0
        if (passthrough == hasFlag) return
        lp.flags = if (passthrough) {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (_: Throwable) {
        }
    }

    private fun windowContains(
        lp: WindowManager.LayoutParams?,
        view: View?,
        x: Int,
        y: Int,
    ): Boolean {
        if (lp == null || view == null) return false
        val width = if (view.width > 0) view.width else return false
        val height = if (view.height > 0) view.height else return false
        val slop = dp(8)
        return x >= lp.x - slop &&
            x < lp.x + width + slop &&
            y >= lp.y - slop &&
            y < lp.y + height + slop
    }

    fun hide() {
        stopScreenWatch()
        unregisterA11yReceiver()
        unregisterScriptsReceiver()
        mainHandler.removeCallbacks(idleFadeRunnable)
        mainHandler.removeCallbacks(clearTalkingRunnable)
        mainHandler.removeCallbacks(refreshA11ySoon)
        mainHandler.removeCallbacks(refreshA11yLater)
        snapAnimator?.cancel()
        snapAnimator = null
        transforming = false
        expanded = false
        pendingCollapseOnOutside = false
        talkingUntilMs = 0L
        hideLogWindow()
        val view = rootView ?: return
        settingsRepository.removeListener(settingsListener)
        try {
            windowManager.removeView(view)
        } catch (_: Throwable) {
        }
        rootView = null
        bubbleView = null
        panelView = null
        statusDot = null
        statusText = null
        chatBadge = null
        params = null
        switchEnabled = null
        switchAutoSkip = null
        switchQuickSkip = null
        switchAutoPick = null
        switchAutoLaunch = null
        launchHint = null
        launchSubtitle = null
        logToggleButton = null
        rowAutoSkip = null
        rowQuickSkip = null
        rowAutoPick = null
        rowLaunch = null
        autoSkipExtras = null
        autoSkipChevron = null
        launchExtras = null
        launchChevron = null
    }

    private fun collapseOnOutsideTouch() {
        if (!expanded) return
        if (transforming) {
            pendingCollapseOnOutside = true
            return
        }
        setExpanded(false)
    }

    private fun openBilibiliSpace() {
        BilibiliSpaceOpener(themedContext).open()
        setExpanded(false)
    }

    private fun launchGenshinFromButton() {
        when (genshinLauncher.launch()) {
            is GenshinLaunchResult.Started -> setExpanded(false)
            GenshinLaunchResult.NotInstalled -> {
                Toast.makeText(themedContext, "未安装原神", Toast.LENGTH_SHORT).show()
                refreshLaunchHint()
            }
            is GenshinLaunchResult.Failed -> {
                Toast.makeText(themedContext, "无法启动原神", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshLaunchHint() {
        val pkg = genshinLauncher.resolveInstalledPackage()
        if (pkg != null) {
            val name = GenshinPackages.displayName(pkg)
            launchSubtitle?.text = "打开已安装的$name"
            launchHint?.text = "启动助手时若未检测到${name}则打开一次"
        } else {
            launchSubtitle?.text = "未安装原神"
            launchHint?.text = "未安装原神"
        }
    }

    private fun exitAssistant() {
        if (transforming) return
        settingsRepository.setScreenShareEnabled(false)
        val panel = panelView
        if (panel != null && expanded) {
            transforming = true
            panel.animate().cancel()
            panel.animate()
                .alpha(0f)
                .scaleX(0.9f)
                .scaleY(0.9f)
                .setDuration(160)
                .setInterpolator(PathInterpolator(0.22f, 1f, 0.36f, 1f))
                .withEndAction { onExit() }
                .start()
        } else {
            onExit()
        }
    }

    /** 扫描等自动化执行前收起面板（与滑动测试缩球对称）：避免面板盖住游戏左侧界面干扰用户观察。 */
    fun collapse() {
        setExpanded(false)
    }

    private fun setExpanded(value: Boolean) {
        if (expanded == value || transforming) return
        val bubble = bubbleView ?: return
        val panel = panelView ?: return
        val root = rootView ?: return
        expanded = value
        transforming = true
        if (!value) pendingCollapseOnOutside = false
        bubble.animate().cancel()
        panel.animate().cancel()
        val ease = PathInterpolator(0.22f, 1f, 0.36f, 1f)

        if (value) {
            refreshLaunchHint()
            mainHandler.removeCallbacks(idleFadeRunnable)
            panel.alpha = 0f
            panel.scaleX = 0.84f
            panel.scaleY = 0.84f
            panel.visibility = View.VISIBLE
            panelScroll?.visibility = View.VISIBLE
            root.post {
                constrainPanelHeight()
                params?.let { ensurePanelOnScreen(it) }
                applyPanelPivot(panel)
                bubble.animate()
                    .alpha(0f)
                    .scaleX(0.72f)
                    .scaleY(0.72f)
                    .setDuration(160)
                    .setInterpolator(ease)
                    .withEndAction {
                        bubble.visibility = View.GONE
                        bubble.scaleX = 1f
                        bubble.scaleY = 1f
                    }
                    .start()
                panel.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(280)
                    .setInterpolator(ease)
                    .withEndAction {
                        transforming = false
                        if (pendingCollapseOnOutside) {
                            pendingCollapseOnOutside = false
                            setExpanded(false)
                        }
                    }
                    .start()
            }
        } else {
            applyPanelPivot(panel)
            bubble.alpha = 0f
            bubble.scaleX = 0.72f
            bubble.scaleY = 0.72f
            bubble.visibility = View.VISIBLE
            panel.animate()
                .alpha(0f)
                .scaleX(0.88f)
                .scaleY(0.88f)
                .setDuration(200)
                .setInterpolator(ease)
                .withEndAction {
                    panel.visibility = View.GONE
                    // ScrollView 是 panel 的外层容器：不同步 GONE 会保留面板高度，
                    // 窗口（WRAP_CONTENT）不回缩 → 整条竖列吞触摸（fix54 引入的回归）
                    panelScroll?.visibility = View.GONE
                    panel.alpha = 1f
                    panel.scaleX = 1f
                    panel.scaleY = 1f
                }
                .start()
            bubble.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(240)
                .setInterpolator(ease)
                .withEndAction {
                    transforming = false
                    params?.let {
                        clampOverlayPosition(it)   // ★ 收起后回右缘（不再吸最近边）
                        updateLayout(it)
                    }
                    scheduleIdleFade()
                }
                .start()
        }
    }

    private fun applyPanelPivot(panel: View) {
        val lp = params ?: return
        val screen = screenSize()
        val onRight = lp.x + (rootView?.width ?: 0) / 2f > screen.first / 2f
        panel.pivotX = if (onRight) panel.width.toFloat() else 0f
        panel.pivotY = 0f
    }

    private fun applyFeatureEnabled(settings: TriggerSettings) {
        val shareOn = settings.screenShareEnabled
        val autoSkipOn = shareOn && settings.autoSkipEnabled
        switchAutoSkip?.isEnabled = shareOn
        switchAutoPick?.isEnabled = shareOn
        switchQuickSkip?.isEnabled = autoSkipOn
        rowAutoSkip?.alpha = if (shareOn) 1f else 0.45f
        rowAutoPick?.alpha = if (shareOn) 1f else 0.45f
        rowQuickSkip?.alpha = if (autoSkipOn) 1f else 0.45f
    }

    override fun onTalkHistoryMatched() {
        mainHandler.post {
            talkingUntilMs = System.currentTimeMillis() + TALKING_HOLD_MS
            mainHandler.removeCallbacks(clearTalkingRunnable)
            mainHandler.postDelayed(clearTalkingRunnable, TALKING_HOLD_MS)
            refreshStatus()
        }
    }

    override fun onChatIconsRecognized(count: Int, topX: Int, topY: Int) {
        appendLog("识别到对话选项 ${count} 个，最高位置 ($topX, $topY)")
    }

    override fun onChatIconClicked(x: Int, y: Int) {
        appendLog("点击对话选项 ($x, $y)")
    }

    /**
     * §13：写入端改为全局日志汇 [RecognitionLog]。
     * 旧实现有两大缺陷：①private，扫描/加锁/装备流程无法写入；②`if (!logWindowVisible) return`
     * 导致**关窗期间日志全丢**。现恒缓冲 + 开窗回放，任何流程皆可写。
     */
    private fun appendLog(message: String) {
        RecognitionLog.log(RecognitionLog.Tag.AUTOSKIP, RecognitionLog.Level.I, message)
    }

    /** §13：订阅全局日志（开窗即回放全部历史）。 */
    private fun bindRecognitionLog() {
        logListener?.let { RecognitionLog.removeListener(it) }
        logListener = RecognitionLog.addListener { entries -> renderLog(entries) }
        bindLogFilters()
    }

    /**
     * §13.5#6：按 Tag/级别着色渲染。W 级一律告警色（跨 Tag 高亮），其余按 Tag 主色。
     */
    private fun renderLog(entries: List<RecognitionLog.Entry>) {
        val tv = logText ?: return
        if (entries.isEmpty()) {
            tv.text = "等待识别…"
            return
        }
        val spannable = android.text.SpannableStringBuilder()
        entries.forEachIndexed { i, e ->
            if (i > 0) spannable.append("\n")
            val start = spannable.length
            spannable.append(e.render())
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(
                    ContextCompat.getColor(themedContext, colorFor(e)),
                ),
                start,
                spannable.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        tv.text = spannable
        logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun colorFor(e: RecognitionLog.Entry): Int = when (e.level) {
        RecognitionLog.Level.W -> R.color.overlay_log_warn
        else -> when (e.tag) {
            RecognitionLog.Tag.AUTOSKIP -> R.color.overlay_log_autoskip
            RecognitionLog.Tag.SCAN -> R.color.overlay_log_scan
            RecognitionLog.Tag.LOCK -> R.color.overlay_log_lock
            RecognitionLog.Tag.EQUIP -> R.color.overlay_log_equip
            RecognitionLog.Tag.CHAR -> R.color.overlay_log_char
        }
    }

    /** §13.5#6：过滤芯片——点击切换该 Tag 显隐，选中态用对应主色。 */
    private fun bindLogFilters() {
        val pairs = listOf(
            R.id.overlay_log_filter_autoskip to RecognitionLog.Tag.AUTOSKIP,
            R.id.overlay_log_filter_scan to RecognitionLog.Tag.SCAN,
            R.id.overlay_log_filter_lock to RecognitionLog.Tag.LOCK,
            R.id.overlay_log_filter_equip to RecognitionLog.Tag.EQUIP,
            R.id.overlay_log_filter_char to RecognitionLog.Tag.CHAR,
        )
        // 注意：bindRecognitionLog() 早于 logBodyView 赋值，故回退到 logText 的根视图查找
        val container = logBodyView ?: logText?.rootView ?: return
        for ((id, tag) in pairs) {
            val chip = container.findViewById<TextView>(id) ?: continue
            refreshFilterChip(chip, tag)
            chip.setOnClickListener {
                RecognitionLog.setVisible(tag, !RecognitionLog.isVisible(tag))
                refreshFilterChip(chip, tag)
            }
        }
    }

    private fun refreshFilterChip(chip: TextView, tag: RecognitionLog.Tag) {
        val on = RecognitionLog.isVisible(tag)
        chip.setTextColor(
            ContextCompat.getColor(
                themedContext,
                if (on) colorFor(RecognitionLog.Entry("", tag, RecognitionLog.Level.I, ""))
                else R.color.overlay_text_muted,
            ),
        )
        chip.alpha = if (on) 1f else 0.45f
    }

    private fun unbindRecognitionLog() {
        logListener?.let { RecognitionLog.removeListener(it) }
        logListener = null
    }

    private fun setLogWindowVisible(visible: Boolean, persist: Boolean = true) {
        if (persist) {
            prefs.edit().putBoolean(KEY_LOG_VISIBLE, visible).apply()
        }
        logWindowVisible = visible
        if (visible) {
            showLogWindow()
        } else {
            hideLogWindow()
        }
        refreshLogToggle()
    }

    private fun refreshLogToggle() {
        val button = logToggleButton ?: return
        button.isSelected = logWindowVisible
        val color = ContextCompat.getColor(
            themedContext,
            if (logWindowVisible) R.color.overlay_log_green else R.color.overlay_text_muted,
        )
        ImageViewCompat.setImageTintList(button, ColorStateList.valueOf(color))
    }

    private fun setAutoSkipMenuExpanded(expanded: Boolean, persist: Boolean = true) {
        autoSkipMenuExpanded = expanded
        if (persist) {
            prefs.edit().putBoolean(KEY_AUTO_SKIP_EXPANDED, expanded).apply()
        }
        autoSkipExtras?.visibility = if (expanded) View.VISIBLE else View.GONE
        autoSkipChevron?.animate()?.rotation(if (expanded) 90f else 0f)?.setDuration(160)?.start()
    }

    private fun setScanMenuExpanded(expanded: Boolean, persist: Boolean = true) {
        scanMenuExpanded = expanded
        if (persist) {
            prefs.edit().putBoolean(KEY_SCAN_EXPANDED, expanded).apply()
        }
        scanExtras?.visibility = if (expanded) View.VISIBLE else View.GONE
        scanChevron?.animate()?.rotation(if (expanded) 90f else 0f)?.setDuration(160)?.start()
    }

    /** maxPages 输入提交：空/0 = 不限（service 侧转 Int.MAX_VALUE）。 */
    private fun persistMaxPages() {
        val raw = scanMaxPagesEdit?.text?.toString()?.trim().orEmpty()
        val pages = raw.toIntOrNull()?.coerceAtLeast(0) ?: 0
        settingsRepository.setScanMaxPages(pages)
    }

    private fun setLaunchMenuExpanded(expanded: Boolean, persist: Boolean = true) {
        launchMenuExpanded = expanded
        if (persist) {
            prefs.edit().putBoolean(KEY_LAUNCH_EXPANDED, expanded).apply()
        }
        launchExtras?.visibility = if (expanded) View.VISIBLE else View.GONE
        launchChevron?.animate()?.rotation(if (expanded) 90f else 0f)?.setDuration(160)?.start()
    }

    private fun isTalking(): Boolean = System.currentTimeMillis() < talkingUntilMs

    private fun registerA11yReceiver() {
        if (a11yReceiverRegistered) return
        ContextCompat.registerReceiver(
            context,
            a11yReceiver,
            IntentFilter(InputAccessibilityService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        a11yReceiverRegistered = true
    }

    private fun unregisterA11yReceiver() {
        if (!a11yReceiverRegistered) return
        try {
            context.unregisterReceiver(a11yReceiver)
        } catch (_: Exception) {
        }
        a11yReceiverRegistered = false
    }

    private fun registerScriptsReceiver() {
        if (scriptsReceiverRegistered) return
        ContextCompat.registerReceiver(
            context,
            scriptsReceiver,
            IntentFilter(ScriptStore.ACTION_SCRIPTS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scriptsReceiverRegistered = true
    }

    private fun unregisterScriptsReceiver() {
        if (!scriptsReceiverRegistered) return
        try {
            context.unregisterReceiver(scriptsReceiver)
        } catch (_: Exception) {
        }
        scriptsReceiverRegistered = false
    }

    private fun refreshStatus() {
        val enabled = settingsRepository.get().screenShareEnabled
        val talking = isTalking()
        val a11yDisconnected = a11yWarningReady &&
            InputAccessibilityService.health(themedContext) == AccessibilityServiceHealth.State.DISCONNECTED
        val colorRes = when {
            a11yDisconnected -> R.color.overlay_status_warn
            talking -> R.color.overlay_status_on
            enabled -> R.color.overlay_status_on
            else -> R.color.overlay_status_off
        }
        val color = ContextCompat.getColor(themedContext, colorRes)
        (statusDot?.background?.mutate() as? GradientDrawable)?.setColor(color)
            ?: statusDot?.background?.let { drawable ->
                DrawableCompat.setTint(DrawableCompat.wrap(drawable).mutate(), color)
            }
        statusText?.text = when {
            a11yDisconnected -> "无障碍异常"
            talking -> "正在对话中"
            enabled -> "已启动"
            else -> "未启动"
        }
        statusText?.setTextColor(
            when {
                a11yDisconnected -> ContextCompat.getColor(themedContext, R.color.overlay_status_warn)
                talking || enabled -> ContextCompat.getColor(themedContext, R.color.overlay_status_on)
                else -> ContextCompat.getColor(themedContext, R.color.overlay_text_muted)
            },
        )
        logTitle?.text = if (talking) "正在对话中" else "识别日志"
        val badge = chatBadge
        if (badge != null) {
            val showBadge = talking
            if (showBadge && badge.visibility != View.VISIBLE) {
                badge.alpha = 0f
                badge.visibility = View.VISIBLE
                badge.animate().alpha(1f).setDuration(160).start()
            } else if (!showBadge && badge.visibility == View.VISIBLE) {
                badge.animate().alpha(0f).setDuration(160).withEndAction {
                    badge.visibility = View.GONE
                }.start()
            }
        }
    }

    private fun showLogWindow() {
        if (logHandleView != null || logBodyView != null) return
        val handle = LayoutInflater.from(themedContext).inflate(R.layout.overlay_log_handle, null)
        val body = LayoutInflater.from(themedContext).inflate(R.layout.overlay_log_body, null)
        logTitle = handle.findViewById(R.id.overlay_log_title)
        logText = body.findViewById(R.id.overlay_log_text)
        // §13：开窗即订阅全局日志并回放历史（历史保留在 RecognitionLog，不随关窗清除）
        bindRecognitionLog()
        logScroll = body.findViewById(R.id.overlay_log_scroll)

        val width = dp(LOG_WIDTH_DP)
        val (defaultX, defaultY) = defaultLogPosition()
        val minY = statusBarHeight()
        val x = prefs.getInt(KEY_LOG_X, defaultX)
        val savedY = prefs.getInt(KEY_LOG_Y, defaultY)
        val y = if (savedY < minY) defaultY else savedY

        val handleParams = overlayParams(
            width = width,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            touchable = true,
            x = x,
            y = y,
        )
        val bodyParams = overlayParams(
            width = width,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            touchable = false,
            x = x,
            y = y + dp(28),
        )

        logHandleView = handle
        logBodyView = body
        logHandleParams = handleParams
        logBodyParams = bodyParams
        setupLogDrag(handle.findViewById(R.id.overlay_log_drag), handleParams)
        handle.findViewById<View>(R.id.overlay_log_close).setOnClickListener {
            setLogWindowVisible(false)
        }
        try {
            windowManager.addView(body, bodyParams)
            windowManager.addView(handle, handleParams)
            handle.post { clampLogWindows() }
        } catch (_: Throwable) {
            hideLogWindow()
        }
    }

    private fun hideLogWindow() {
        listOf(logHandleView, logBodyView).forEach { view ->
            if (view != null) {
                try {
                    windowManager.removeView(view)
                } catch (_: Throwable) {
                }
            }
        }
        logHandleView = null
        logBodyView = null
        logHandleParams = null
        logBodyParams = null
        logTitle = null
        logText = null
        logScroll = null
        // §13：不再清空历史（旧实现收窗即 clear，关窗期间的识别明细永久丢失）；仅退订渲染
        unbindRecognitionLog()
    }

    private fun overlayParams(
        width: Int,
        height: Int,
        touchable: Boolean,
        x: Int,
        y: Int,
    ): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }

    private fun setupLogDrag(
        dragHandle: View,
        lp: WindowManager.LayoutParams,
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        dragHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (event.rawX - touchX).toInt()
                    lp.y = startY + (event.rawY - touchY).toInt()
                    clampLogWindows()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    persistLogPosition(lp)
                    true
                }
                else -> false
            }
        }
    }

    private fun clampLogWindows() {
        val handleLp = logHandleParams ?: return
        val bodyLp = logBodyParams ?: return
        val handle = logHandleView ?: return
        val body = logBodyView ?: return
        val screen = screenSize()
        val width = if (handle.width > 0) handle.width else dp(LOG_WIDTH_DP)
        val handleHeight = if (handle.height > 0) handle.height else dp(28)
        val minY = statusBarHeight()
        // body 限高：日志行多时 WRAP_CONTENT 可能超出屏幕（与面板限高同理），由内部 ScrollView 滚动
        val maxBodyH = (screen.second - minY - navigationBarHeight() - handleHeight - dp(8))
            .coerceAtLeast(dp(80))
        val measuredBody = body.height.takeIf { it > 0 } ?: dp(120)
        if (bodyLp.height != measuredBody.coerceAtMost(maxBodyH)) {
            bodyLp.height = measuredBody.coerceAtMost(maxBodyH)
        }
        val bodyHeight = bodyLp.height
        handleLp.x = handleLp.x.coerceIn(0, (screen.first - width).coerceAtLeast(0))
        handleLp.y = handleLp.y.coerceIn(
            minY,
            (screen.second - handleHeight - bodyHeight - navigationBarHeight()).coerceAtLeast(minY),
        )
        updateLogLayouts()
    }

    private fun updateLogLayouts() {
        val handleLp = logHandleParams ?: return
        val bodyLp = logBodyParams ?: return
        val handle = logHandleView ?: return
        val body = logBodyView ?: return
        val handleHeight = if (handle.height > 0) handle.height else dp(28)
        bodyLp.x = handleLp.x
        bodyLp.y = handleLp.y + handleHeight
        try {
            windowManager.updateViewLayout(handle, handleLp)
        } catch (_: Throwable) {
        }
        try {
            windowManager.updateViewLayout(body, bodyLp)
        } catch (_: Throwable) {
        }
    }

    private fun persistLogPosition(lp: WindowManager.LayoutParams) {
        prefs.edit().putInt(KEY_LOG_X, lp.x).putInt(KEY_LOG_Y, lp.y).apply()
    }

    private fun setupDrag(
        dragHandle: View,
        lp: WindowManager.LayoutParams,
        snapOnRelease: Boolean,
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        dragHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (lp === params) {
                        // ★ 主悬浮窗：只纵向（x 钉右缘）；日志窗 drag 行为不变
                        lp.y = startY + (event.rawY - touchY).toInt()
                        clampOverlayPosition(lp)
                        updateLayout(lp)
                    } else {
                        lp.x = startX + (event.rawX - touchX).toInt()
                        lp.y = startY + (event.rawY - touchY).toInt()
                        clampToScreen(lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    persistPosition(lp)
                    // ⚠️ 2026-09-16（审计 P2-1）：`snapOnRelease` 目前唯一调用点传 false（本窗不再左右吸边），
                    //   但此分支一旦放开就必须走 clampOverlayPosition —— 本类 gravity=TOP|END，
                    //   而 snapToEdge 内部按「x = 左坐标」运算 ⇒ 会把窗推去屏幕左侧。
                    if (snapOnRelease && !expanded) {
                        clampOverlayPosition(lp)
                        updateLayout(lp)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupDragAndClick(
        dragHandle: View,
        lp: WindowManager.LayoutParams,
        onClick: () -> Unit,
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        dragHandle.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    moved = false
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    wakeBubble()
                    dragHandle.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (!moved && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        moved = true
                    }
                    if (moved) {
                        // ★ 只纵向：忽略 dx（x 由 clampOverlayPosition 钉在右缘）
                        lp.y = startY + dy
                        clampOverlayPosition(lp)
                        updateLayout(lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    dragHandle.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (!moved) {
                        v.performClick()
                        onClick()
                    } else {
                        persistPosition(lp)
                        clampOverlayPosition(lp)
                        updateLayout(lp)   // ★ 不再左右吸边（只纵向移动）
                    }
                    if (!expanded) scheduleIdleFade()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragHandle.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (moved) {
                        // ★ 2026-09-16（审计 P2-1）：与 ACTION_UP 一致走 clampOverlayPosition
                        //   （原来漏改 ⇒ 拖球被打断时仍 snapToEdge，破坏「只沿右缘纵向移动」）
                        persistPosition(lp)
                        clampOverlayPosition(lp)
                        updateLayout(lp)
                    }
                    if (!expanded) scheduleIdleFade()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * ⚠️ 2026-09-16（审计 P2-1）：**已无调用点**（主窗改为「只沿右缘纵向移动」）。
     * 保留仅为历史参考 —— 它内部按 `gravity=START` 的「x = 左坐标」语义运算，
     * 与本类现行的 `gravity=TOP|END` 冲突，**禁止再对主窗调用**。
     */
    @Deprecated("主窗已改右缘纵向定位（clampOverlayPosition）；本函数按 START 语义运算，勿复用")
    private fun snapToEdge(lp: WindowManager.LayoutParams, animate: Boolean) {
        val view = rootView ?: return
        val screen = screenSize()
        val width = if (view.width > 0) view.width else dp(48)
        val targetX = if (lp.x + width / 2 < screen.first / 2) 0 else screen.first - width
        if (!animate || lp.x == targetX) {
            lp.x = targetX
            clampToScreen(lp)
            persistPosition(lp)
            return
        }
        snapAnimator?.cancel()
        val fromX = lp.x
        snapAnimator = ValueAnimator.ofInt(fromX, targetX).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                lp.x = animator.animatedValue as Int
                updateLayout(lp)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    persistPosition(lp)
                }
            })
            start()
        }
    }

    /**
     * 面板高度上限 = 可视高度（屏幕高 - 状态栏 - 上下留边）。横屏游戏可视高度可能小于面板内容高度，
     * 不限制会导致面板底部超出屏幕被裁（展开后退出/探针等按钮点不到），内容改由 ScrollView 滚动。
     */
    private fun constrainPanelHeight() {
        val scroll = panelScroll ?: return
        val screen = screenSize()
        val maxH = (screen.second - statusBarHeight() - dp(20)).coerceAtLeast(dp(120))
        scroll.measure(
            View.MeasureSpec.makeMeasureSpec(screen.first, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val contentH = scroll.measuredHeight
        val lp = scroll.layoutParams
        if (lp != null && lp.height != contentH.coerceAtMost(maxH)) {
            lp.height = contentH.coerceAtMost(maxH)
            scroll.layoutParams = lp
        }
    }

    private fun ensurePanelOnScreen(lp: WindowManager.LayoutParams) {
        clampToScreen(lp)
    }

    /** ★ 2026-09-14：主悬浮窗距右缘固定内边距（只纵向移动 ⇒ x 恒定）。 */
    private fun overlayRightMarginPx(): Int = dp(8)

    /** ★ 2026-09-14：纵向安全上界 = 游戏右上按钮带下方（1440 基准按钮带 y≈36..125px ⇒ 取 12%，下限 dp140）。 */
    private fun overlaySafeTopY(): Int = Math.max(dp(140), (screenSize().second * 0.12f).toInt())

    /**
     * ★ 2026-09-14：主悬浮窗位置规范化 —— x 钉右缘 + y 夹在 [按钮带下方, 屏底−导航栏] 之间。
     * 拖动/重定位/展开收起都走它，保证「只沿右缘纵向移动」在任何入口都成立。
     */
    private fun clampOverlayPosition(lp: WindowManager.LayoutParams) {
        val view = rootView ?: return
        val screen = screenSize()
        val height = if (view.height > 0) view.height else dp(48)
        lp.x = overlayRightMarginPx()
        val minY = overlaySafeTopY()
        val maxY = (screen.second - height - navigationBarHeight() - dp(4)).coerceAtLeast(minY)
        lp.y = lp.y.coerceIn(minY, maxY)
    }

    private fun clampToScreen(lp: WindowManager.LayoutParams) {
        val view = rootView ?: return
        val screen = screenSize()
        val width = if (view.width > 0) view.width else dp(48)
        val height = if (view.height > 0) view.height else dp(48)
        // 下/上限去掉状态栏与导航栏区域：窗口带 FLAG_LAYOUT_NO_LIMITS，
        // 允许绘制到系统栏下方，贴边会被系统栏吞掉触摸（竖屏球无法拖动/点击的根因）
        val minY = statusBarHeight() + dp(4)
        val maxY = (screen.second - height - navigationBarHeight() - dp(4)).coerceAtLeast(minY)
        lp.x = lp.x.coerceIn(dp(4), (screen.first - width - dp(4)).coerceAtLeast(dp(4)))
        lp.y = lp.y.coerceIn(minY, maxY)
        updateLayout(lp)
    }

    private fun updateLayout(lp: WindowManager.LayoutParams) {
        val view = rootView ?: return
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (_: Throwable) {
        }
    }

    private fun persistPosition(lp: WindowManager.LayoutParams) {
        // ★ 2026-09-16（审计 P2-2）：不再存 x —— 主窗 x 恒由 clampOverlayPosition 钉在右缘
        //   （gravity=END 下 x 是**距右缘偏移**），持久化它既无意义、又容易被误当作左坐标复用。
        prefs.edit().putInt(KEY_Y, lp.y).apply()
    }

    private fun wakeBubble() {
        mainHandler.removeCallbacks(idleFadeRunnable)
        fadeBubble(1f)
    }

    private fun scheduleIdleFade() {
        mainHandler.removeCallbacks(idleFadeRunnable)
        if (!expanded) {
            mainHandler.postDelayed(idleFadeRunnable, IDLE_DELAY_MS)
        }
    }

    private fun fadeBubble(alpha: Float) {
        val bubble = bubbleView ?: return
        if (expanded || bubble.visibility != View.VISIBLE) return
        bubble.animate().alpha(alpha).setDuration(220).start()
    }

    private fun startScreenWatch() {
        if (watchingScreen) return
        watchingScreen = true
        displayManager.registerDisplayListener(displayListener, mainHandler)
        context.registerComponentCallbacks(configCallbacks)
    }

    private fun stopScreenWatch() {
        if (!watchingScreen) return
        watchingScreen = false
        displayManager.unregisterDisplayListener(displayListener)
        context.unregisterComponentCallbacks(configCallbacks)
    }

    private fun rememberScreen() {
        val screen = screenSize()
        lastScreenW = screen.first
        lastScreenH = screen.second
    }

    private fun relocateOverlays(force: Boolean) {
        val root = rootView ?: return
        val screen = screenSize()
        if (!force && screen.first == lastScreenW && screen.second == lastScreenH) return
        snapAnimator?.cancel()
        root.post {
            rememberScreen()
            val lp = params ?: return@post
            if (expanded) constrainPanelHeight() // 旋转后面板高度上限重算（横竖屏可视高度不同）
            clampOverlayPosition(lp)   // ★ x 钉右缘 + y 避按钮带（替代 clampToScreen + snapToEdge）
            updateLayout(lp)
            persistPosition(lp)
            clampLogWindows()
            logHandleParams?.let { persistLogPosition(it) }
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun defaultLogPosition(): Pair<Int, Int> {
        val screen = screenSize()
        val height = dp(LOG_DEFAULT_HEIGHT_DP)
        val margin = dp(12)
        val x = margin
        val y = (screen.second - height - dp(48)).coerceAtLeast(statusBarHeight())
        return x to y
    }

    /** 系统导航栏/手势条高度（framework 资源读取，不可用时 0——全屏手势设备无实体导航栏）。 */
    private fun navigationBarHeight(): Int {
        val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    private fun statusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) {
            return context.resources.getDimensionPixelSize(id)
        }
        return dp(28)
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    private companion object {
        private const val PREFS_NAME = "overlay_window"
        private const val KEY_Y = "y"
        private const val KEY_LOG_X = "log_x"
        private const val KEY_LOG_Y = "log_y"
        private const val KEY_LOG_VISIBLE = "log_visible"
        private const val KEY_AUTO_SKIP_EXPANDED = "auto_skip_expanded"
        private const val KEY_LAUNCH_EXPANDED = "launch_expanded"
        private const val KEY_SCAN_EXPANDED = "scan_expanded"
        private const val LOG_WIDTH_DP = 260
        private const val LOG_DEFAULT_HEIGHT_DP = 148
        private const val IDLE_ALPHA = 0.62f
        private const val IDLE_DELAY_MS = 2400L
        private const val TALKING_HOLD_MS = 2000L
        private const val MAX_LOG_LINES = 16
    }
}
