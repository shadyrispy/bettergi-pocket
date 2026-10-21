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
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.widget.ImageViewCompat
import com.bettergi.pocket.R
import com.bettergi.pocket.bilibili.BilibiliSpaceOpener
import com.bettergi.pocket.dsl.FlowValidator
import com.bettergi.pocket.dsl.ScriptIcons
import com.bettergi.pocket.dsl.ScriptStore
import com.bettergi.pocket.feature.autopick.AutoPickFeature
import com.bettergi.pocket.feature.autoskip.AutoSkipEvents
import com.bettergi.pocket.genshin.GenshinLaunchResult
import com.bettergi.pocket.genshin.GenshinLauncher
import com.bettergi.pocket.genshin.GenshinPackages
import com.bettergi.pocket.log.RecognitionLog
import com.bettergi.pocket.settings.TriggerSettings
import com.bettergi.pocket.bridge.SettingsBridgeClient
import com.bettergi.pocket.settings.SettingsGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ★ Stage 3.4 拆分地图（2026-10-01）：本文件曾 1825 行，按窗口组件拆出（均为**扩展函数**，
 * 函数体一字未改、调用点不变）：
 * - `OverlayLogWindow.kt`   —— 识别日志窗（show/hide/render/过滤 chip/拖动/布局）
 * - `OverlayGeometry.kt`    —— 几何与布局（screenSize / dp / clamp 系列 / relocateOverlays/屏幕监听/淡出）
 * - `OverlayScriptRows.kt`  —— 脚本行 / 流程按钮（applyFlowSelection/renderFlowButtons/onFlowAction）
 * - `OverlayNotice.kt`      —— 提醒条（showNotice）
 * 本文件保留：类与构造参数、状态字段、show / hide / setExpanded 装配、点击穿透、事件订阅。
 * （`OverlayWindowGeometry` 纯函数 object 已拆至 `OverlayWindowGeometry.kt` —— 它被 OverlayWindowGeometryTest 覆盖。）
 */
class OverlayWindowController(
    internal val context: Context,
    internal val settingsRepository: SettingsGateway,
    private val genshinLauncher: GenshinLauncher = GenshinLauncher(context),
    internal val inputGate: AccessibilityInputGate,
    private val onExit: () -> Unit = {},
    internal val onShareGoodRequested: () -> Unit = {},
) : AutoSkipEvents {
    internal val themedContext = ContextThemeWrapper(context, R.style.Theme_BetterGIPocket)
    internal val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    internal val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    internal val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    internal val mainHandler = Handler(Looper.getMainLooper())
    internal val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    internal var lastScreenW = 0
    internal var lastScreenH = 0
    internal var watchingScreen = false

    internal val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            relocateOverlays(force = false)
        }
    }

    internal val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            relocateOverlays(force = true)
        }

        override fun onLowMemory() = Unit
    }

    internal var rootView: View? = null
    internal var bubbleView: View? = null
    private var panelView: View? = null
    internal var panelScroll: View? = null
    private var statusDot: View? = null
    private var statusText: TextView? = null
    private var chatBadge: View? = null
    internal var params: WindowManager.LayoutParams? = null
    internal var snapAnimator: ValueAnimator? = null

    private var updatingUi = false
    internal var expanded = false
    private var transforming = false
    private var pendingCollapseOnOutside = false
    private var switchEnabled: SwitchCompat? = null
    private var switchAutoSkip: SwitchCompat? = null
    private var switchQuickSkip: SwitchCompat? = null
    private var switchAutoPick: SwitchCompat? = null
    private var switchAutoLaunch: SwitchCompat? = null
    private var scanProgress: TextView? = null
    private var launchHint: TextView? = null
    private var launchSubtitle: TextView? = null
    internal var logToggleButton: ImageButton? = null
    private var rowAutoSkip: View? = null
    private var rowQuickSkip: View? = null
    private var rowAutoPick: View? = null
    private var switchCapture: SwitchCompat? = null
    private var captureSubtitle: TextView? = null

    /** 抓包状态订阅：会话状态是 flow，面板只是它的一个读者。 */
    /**
     * ★ 3.4（裁决 3 的 P3）：原为 `val` 且**从不 cancel** ⇒ 每次 show/hide 都留着旧 scope 与
     * 其 IO 协程（桥调用悬挂时永不回收）。改为可重建：hide() cancel，show() 换新。
     */
    private var captureScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var rowLaunch: View? = null
    private var autoSkipExtras: View? = null
    private var autoSkipChevron: ImageView? = null
    private var autoSkipMenuExpanded = false
    /** 脚本行容器（一脚本一行）。 */
    internal var scriptGroup: LinearLayout? = null
    /** 脚本区「页数」徽标（点它展开输入行）。 */
    private var scriptPagesBadge: TextView? = null
    /** 页数输入行（默认收起）。 */
    private var scriptPagesRow: View? = null
    /** 脚本行（flowKey → 整行 View）：**由 DSL `ui` 段驱动重建**，见 [renderFlowButtons]。 */
    internal val flowViews = LinkedHashMap<String, View>()

    /** flowKey → 行内名称（运行中态改色用）。 */
    internal val flowLabelViews = LinkedHashMap<String, TextView>()

    /** flowKey → 该行的「运行/停止」按钮（运行中翻转图标用）。 */
    internal val flowRunButtons = LinkedHashMap<String, ImageView>()

    /** flowKey → 该行的全部动作按钮（运行期统一禁点/压暗用）。 */
    internal val flowActions = LinkedHashMap<String, MutableList<View>>()

    /** flowKey → 脚本自报按钮文字（`ui.label`）：常态/运行中态文案切换复用。 */
    internal val flowLabels = LinkedHashMap<String, String>()

    /** 脚本行容器（`overlay_script_group`）。 */
    private var flowGroup: LinearLayout? = null

    /**
     * 刷新脚本行态。
     * - 选中（= 当前 `scanFlow`）→ 整行金色底 + 名称金色；
     * - 「运行中」态：**仅当前流程**的「运行」按钮翻转为「停止」（红色 ■），其余行的动作一律禁点并压暗
     *   —— 避免扫描中途切流程。
     */


    private var scanMaxPagesEdit: EditText? = null
    private var launchExtras: View? = null
    private var launchChevron: ImageView? = null
    private var launchMenuExpanded = false
    internal var logHandleView: View? = null
    internal var logBodyView: View? = null
    internal var logTitle: TextView? = null
    internal var logText: TextView? = null
    internal var logScroll: ScrollView? = null
    internal var logHandleParams: WindowManager.LayoutParams? = null
    internal var logBodyParams: WindowManager.LayoutParams? = null
    /** §13：全局日志订阅句柄（缓冲已迁至 [RecognitionLog]，本类只负责渲染）。 */
    internal var logListener: ((List<RecognitionLog.Entry>) -> Unit)? = null
    internal var logWindowVisible = false
    private var talkingUntilMs: Long = 0L
    private val logTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    private val clearTalkingRunnable = Runnable { refreshStatus() }

    internal val idleFadeRunnable = Runnable { fadeBubble(IDLE_ALPHA) }
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
            applyFeatureEnabled(settings)
            // 运行中态：仅当前流程按钮转进行态（用户 2026-09-18 裁定②）
            applyFlowSelection(settings.scanFlow)
            refreshPagesBadge()
            refreshLaunchHint()
            refreshStatus()
        } finally {
            updatingUi = false
        }
    }

    fun show() {
        if (!captureScope.isActive) {
            captureScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        }
        if (rootView != null) return
        // 2026-09-18：宿主迁到无障碍进程后不再需要「显示在上层」授权。
        // 服务未连接时 A11yOverlayRuntime 根本不会构造本对象，故此处无需再自检。
        // ⚠️ 本类两个窗口（面板/日志）都必须用 TYPE_ACCESSIBILITY_OVERLAY，
        //    否则在无障碍进程里会被 WindowManager 以权限不足拒绝。

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
        val captureSwitch = root.findViewById<SwitchCompat>(R.id.overlay_switch_capture)
        val logToggle = root.findViewById<ImageButton>(R.id.overlay_log_toggle)

        bubbleView = bubble
        panelView = panel
        panelScroll = root.findViewById(R.id.overlay_panel_scroll)
        statusDot = root.findViewById(R.id.overlay_status_dot)
        statusText = root.findViewById<TextView>(R.id.overlay_status_text).also { text ->
            text.setOnClickListener {
                if (!inputGate.isOperational(themedContext)) {
                    inputGate.ensureEnabled(themedContext)
                }
            }
        }
        chatBadge = root.findViewById(R.id.overlay_chat_badge)
        noticeView = root.findViewById(R.id.overlay_notice)
        noticeAccent = root.findViewById(R.id.overlay_notice_bar)
        noticeLabel = root.findViewById(R.id.overlay_notice_text)
        switchEnabled = enabledSwitch
        switchAutoSkip = autoSkipSwitch
        switchQuickSkip = quickSkipSwitch
        switchAutoPick = autoPickSwitch
        switchAutoLaunch = autoLaunchSwitch
        switchCapture = captureSwitch
        captureSubtitle = root.findViewById(R.id.overlay_capture_subtitle)
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

        // 脚本区（2026-09-18 改造）：一脚本一行，行内动作由脚本的 `ui.actions` 决定。
        // ⚠️ 悬浮窗内不用系统 PopupMenu/Spinner（overlay 类型窗口无 Activity token → BadTokenException 风险）
        scriptGroup = root.findViewById(R.id.overlay_script_group)
        scriptPagesBadge = root.findViewById<TextView>(R.id.overlay_script_pages_badge).also { badge ->
            badge.setOnClickListener { togglePagesRow() }
        }
        scriptPagesRow = root.findViewById(R.id.overlay_scan_pages_row)
        renderFlowButtons()
        scanMaxPagesEdit = root.findViewById<EditText>(R.id.overlay_scan_max_pages).apply {
            val saved = settingsRepository.get().scanMaxPages
            setText(if (saved <= 0) "" else saved.toString())
            setOnFocusChangeListener { _, has -> setPanelFocusable(has) }
            setOnEditorActionListener { _, _, _ ->
                persistMaxPages()
                setPanelFocusable(false)
                togglePagesRow() // 提交即收起
                true
            }
        }
        refreshPagesBadge()

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
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
        // 长按日志按钮进脚本管理器（原「设置 · 长按」行已删除；教学只出现在首启引导页）
        logToggle.setOnLongClickListener {
            openScriptManager()
            true
        }
        rowAutoSkip?.setOnClickListener { setAutoSkipMenuExpanded(!autoSkipMenuExpanded) }
        rowLaunch?.setOnClickListener { setLaunchMenuExpanded(!launchMenuExpanded) }
        root.findViewById<View>(R.id.overlay_launch).setOnClickListener { launchGenshinFromButton() }
        root.findViewById<ImageButton>(R.id.overlay_bilibili).also { button ->
            ImageViewCompat.setImageTintList(button, null)
            button.setOnClickListener { openBilibiliSpace() }
        }
        root.findViewById<View>(R.id.overlay_exit).setOnClickListener { exitAssistant() }

        enabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            settingsRepository.setScreenShareEnabled(isChecked)
        }
        autoSkipSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            settingsRepository.setAutoSkipEnabled(isChecked)
            if (isChecked) {
                inputGate.ensureEnabled(themedContext, "请开启无障碍权限，才能模拟点击对话选项")
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
                inputGate.ensureEnabled(themedContext, "请开启无障碍权限，才能模拟点击拾取")
            }
        }
        autoLaunchSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            settingsRepository.setAutoLaunchGenshinEnabled(isChecked)
        }
        captureSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@setOnCheckedChangeListener
            // 抓包是一次性会话，不进 settingsRepository（不持久化）：重启不该自动开隧道。
            sendCaptureCommand(isChecked)
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

    /**
     * 远端日志出口（宿主搬到无障碍进程后由运行时注入）。
     * ⚠️ 必须**既写本地又发远端**：本地写是为了即时可见；发远端是为了不被日志镜像整体覆盖
     * （镜像的来源是主进程缓冲，见 A11yOverlayRuntime 的日志轮询）。
     */
    var remoteLogSink: ((String, String, String) -> Unit)? = null

    /** 日志窗是否可见（无障碍进程的运行时据此决定要不要拉取主进程的日志镜像）。 */
    fun isLogWindowVisible(): Boolean = logWindowVisible

    // ---- 通用提醒条（NoticeCenter 的悬浮窗展位）----

    internal var noticeView: View? = null
    internal var noticeAccent: View? = null
    internal var noticeLabel: TextView? = null
    internal var noticeHide: Runnable? = null

    /**
     * 显示一条提醒。**同一时间最多 1 条**（新的顶掉旧的）—— 面板只有 248dp 宽，堆叠会挤掉脚本区。
     * 收成球时面板整体 GONE，所以提醒自然不可见（信息不丢：已落识别日志）。
     */


    fun updateScanProgress(text: String) {
        val view = scanProgress ?: return
        view.text = text
        view.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
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
        // lp.x/y 的语义随 gravity 变化（END ⇒ 距右缘偏移、START ⇒ 左缘坐标），
        // 必须先换算出窗口实际左上角再做命中判定；换算抽在 [OverlayWindowGeometry]
        // （纯函数，两种 gravity 的换算公式与边界点由 JVM 单测钉死）。
        val screen = screenSize()
        return OverlayWindowGeometry.contains(
            screenWidth = screen.first,
            screenHeight = screen.second,
            lpX = lp.x,
            lpY = lp.y,
            windowWidth = width,
            windowHeight = height,
            gravity = lp.gravity,
            slop = dp(8),
            touchX = x,
            touchY = y,
        )
    }

    fun hide() {
        // ★ 3.4（裁决 3 的 P3）：面板与日志窗已拆，随之下线；挂着的桥调用协程一并取消。
        captureScope.cancel()
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
        panelScroll = null
        statusDot = null
        statusText = null
        chatBadge = null
        params = null
        switchEnabled = null
        switchAutoSkip = null
        switchQuickSkip = null
        switchAutoPick = null
        switchAutoLaunch = null
        scanProgress = null
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
        // ★ 3.4（裁决 3 的 P3）：以下 view 引用此前**漏清** ⇒ hide() 后仍持有已 removeView 的
        //   视图（阻止回收，且下次 show() 前若被误用会操作已脱离窗口树的 View）。
        switchCapture = null
        captureSubtitle = null
        scriptGroup = null
        scriptPagesBadge = null
        scriptPagesRow = null
        scanMaxPagesEdit = null
        flowGroup = null
        noticeView = null
        // flow 映射表是 show() 时重建的（见 show 里的 clear），此处一并清，避免持有旧视图
        flowViews.clear()
        flowLabels.clear()
        flowLabelViews.clear()
        flowRunButtons.clear()
        flowActions.clear()
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
                A11yOverlayRuntime.notice("warn", "未安装原神")
                refreshLaunchHint()
            }
            is GenshinLaunchResult.Failed -> {
                A11yOverlayRuntime.notice("error", "无法启动原神")
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
    /** 抓包开关指令（工单 D：改走 bridge/SettingsBridgeClient 门面，不再直接 contentResolver.call）。 */
    private fun sendCaptureCommand(start: Boolean) {
        captureScope.launch(Dispatchers.IO) {
            val ok = SettingsBridgeClient.captureCommand(themedContext, start)
            if (!ok) {
                // 桥不通（无障碍没连 / 主进程服务没起）⇒ 开关必须落回，
                // 不能停在玩家刚点亮的位置上骗他"已经在抓了"。
                mainHandler.post { applyCaptureStatus(running = false, text = "助手没在跑，抓包没启动") }
            }
        }
    }

    /**
     * 面板**打开时**拉一次会话快照。
     *
     * ⚠️ 这里刻意不订阅 `CaptureSession.ui`：面板跑在 `:a11y`，那份单例是**本进程**的，
     *    订阅它只会永远停在初值 —— 状态一律由主进程经 `M_CAPTURE` 推进来
     *    （见 `TriggerForegroundService.onCreate` 与 [updateCaptureStatus]）。
     */
    internal fun bindCaptureState() {
        captureScope.launch(Dispatchers.IO) {
            val snapshot = SettingsBridgeClient.captureSnapshot(themedContext) ?: return@launch
            val text = snapshot.text
            val running = snapshot.running
            mainHandler.post { applyCaptureStatus(running, text) }
        }
    }

    /** 主进程推进来的会话状态（经 `A11yOverlayRuntime`，已在主线程）。 */
    fun updateCaptureStatus(running: Boolean, text: String) {
        applyCaptureStatus(running, text)
    }

    private fun applyCaptureStatus(running: Boolean, text: String) {
        if (text.isEmpty()) return
        updatingUi = true
        switchCapture?.isChecked = running
        updatingUi = false
        captureSubtitle?.text = text
    }

    /** §13：订阅全局日志（开窗即回放全部历史）。 */
    private fun setAutoSkipMenuExpanded(expanded: Boolean, persist: Boolean = true) {
        autoSkipMenuExpanded = expanded
        if (persist) {
            prefs.edit().putBoolean(KEY_AUTO_SKIP_EXPANDED, expanded).apply()
        }
        autoSkipExtras?.visibility = if (expanded) View.VISIBLE else View.GONE
        autoSkipChevron?.animate()?.rotation(if (expanded) 90f else 0f)?.setDuration(160)?.start()
    }

    /** maxPages 输入提交：空/0 = 不限（service 侧转 Int.MAX_VALUE）。 */
    private fun persistMaxPages() {
        val raw = scanMaxPagesEdit?.text?.toString()?.trim().orEmpty()
        val pages = raw.toIntOrNull()?.coerceAtLeast(0) ?: 0
        settingsRepository.setScanMaxPages(pages)
        refreshPagesBadge()
    }

    /** 页数徽标：0（默认）时只写"页数"且用弱色，非默认时写"页数 N"并用金色。 */
    private fun refreshPagesBadge() {
        val pages = settingsRepository.get().scanMaxPages
        val badge = scriptPagesBadge ?: return
        badge.text = if (pages > 0) "页数 $pages" else "页数"
        badge.setTextColor(
            context.getColor(if (pages > 0) R.color.overlay_gold else R.color.overlay_text_muted),
        )
    }

    /** 展开/收起页数输入行（默认收起；没有 PopupMenu 可用，所以就地展开）。 */
    internal fun togglePagesRow() {
        val row = scriptPagesRow ?: return
        val show = row.visibility != View.VISIBLE
        row.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            scanMaxPagesEdit?.requestFocus()
        } else {
            setPanelFocusable(false)
        }
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
            IntentFilter(inputGate.stateChangedAction),
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
            inputGate.isDisconnected(themedContext)
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


    internal fun overlayRightMarginPx(): Int = dp(8)

    /** ★ 2026-09-14：纵向安全上界 = 游戏右上按钮带下方（1440 基准按钮带 y≈36..125px ⇒ 取 12%，下限 dp140）。 */


    internal companion object {
        internal const val TAG_OVERLAY = "BetterGI.Overlay"
        private const val PREFS_NAME = "overlay_window"
        internal const val KEY_Y = "y"
        internal const val KEY_LOG_X = "log_x"
        internal const val KEY_LOG_Y = "log_y"
        internal const val KEY_LOG_VISIBLE = "log_visible"
        private const val KEY_AUTO_SKIP_EXPANDED = "auto_skip_expanded"
        private const val KEY_LAUNCH_EXPANDED = "launch_expanded"
        internal const val LOG_WIDTH_DP = 260
        internal const val LOG_DEFAULT_HEIGHT_DP = 148
        private const val IDLE_ALPHA = 0.62f
        internal const val IDLE_DELAY_MS = 2400L
        private const val TALKING_HOLD_MS = 2000L
        private const val MAX_LOG_LINES = 16
    }
}
