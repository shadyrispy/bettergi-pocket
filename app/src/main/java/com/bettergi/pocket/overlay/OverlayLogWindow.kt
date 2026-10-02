package com.bettergi.pocket.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import android.content.res.ColorStateList
import com.bettergi.pocket.R
import com.bettergi.pocket.log.RecognitionLog
import com.bettergi.pocket.overlay.OverlayWindowController.Companion.KEY_LOG_VISIBLE
import com.bettergi.pocket.overlay.OverlayWindowController.Companion.KEY_LOG_X
import com.bettergi.pocket.overlay.OverlayWindowController.Companion.KEY_LOG_Y
import com.bettergi.pocket.overlay.OverlayWindowController.Companion.LOG_WIDTH_DP
import com.bettergi.pocket.overlay.OverlayWindowController.Companion.LOG_DEFAULT_HEIGHT_DP

/*
 * Stage 3.4：识别日志窗组件（LogWindow）从 OverlayWindowController.kt 抽出
 * （扩展函数，函数体一字未改）。
 */

internal fun OverlayWindowController.appendLog(message: String) {
    RecognitionLog.log(RecognitionLog.Tag.AUTOSKIP, RecognitionLog.Level.I, message)
    // 同时送回主进程的单一日志源（否则下一次镜像刷新会把这行冲掉）
    remoteLogSink?.invoke(
        RecognitionLog.Tag.AUTOSKIP.name,
        RecognitionLog.Level.I.name,
        message,
    )
}

/**
 * 抓包开关的两个方向都只**发指令**：会话、隧道、VPN 弹窗的发起全在主进程。
 *
 * 为什么不在这里直接 `startActivity(授权页)`：那也能弹（悬浮窗点击算用户交互），
 * 但发起逻辑就裂成两半 —— 投影那套是「服务发起 → 被拦则拉前台再发起 → 中转页回报服务」，
 * VPN 跟它同构才有统一的去重、提醒和开关落回。
 */
internal fun OverlayWindowController.bindRecognitionLog() {
    logListener?.let { RecognitionLog.removeListener(it) }
    logListener = RecognitionLog.addListener { entries -> renderLog(entries) }
    bindLogFilters()
}

/**
 * §13.5#6：按 Tag/级别着色渲染。W 级一律告警色（跨 Tag 高亮），其余按 Tag 主色。
 */
internal fun OverlayWindowController.renderLog(entries: List<RecognitionLog.Entry>) {
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

internal fun OverlayWindowController.colorFor(e: RecognitionLog.Entry): Int = when (e.level) {
    RecognitionLog.Level.W -> R.color.overlay_log_warn
    else -> when (e.tag) {
        RecognitionLog.Tag.AUTOSKIP -> R.color.overlay_log_autoskip
        RecognitionLog.Tag.SCAN -> R.color.overlay_log_scan
        RecognitionLog.Tag.LOCK -> R.color.overlay_log_lock
        RecognitionLog.Tag.EQUIP -> R.color.overlay_log_equip
        RecognitionLog.Tag.CHAR -> R.color.overlay_log_char
        RecognitionLog.Tag.APP -> R.color.overlay_log_app
    }
}

/** §13.5#6：过滤芯片——点击切换该 Tag 显隐，选中态用对应主色。 */
internal fun OverlayWindowController.bindLogFilters() {
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

internal fun OverlayWindowController.refreshFilterChip(chip: TextView, tag: RecognitionLog.Tag) {
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

internal fun OverlayWindowController.unbindRecognitionLog() {
    logListener?.let { RecognitionLog.removeListener(it) }
    logListener = null
}

internal fun OverlayWindowController.setLogWindowVisible(visible: Boolean, persist: Boolean = true) {
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

internal fun OverlayWindowController.refreshLogToggle() {
    val button = logToggleButton ?: return
    button.isSelected = logWindowVisible
    val color = ContextCompat.getColor(
        themedContext,
        if (logWindowVisible) R.color.overlay_log_green else R.color.overlay_text_muted,
    )
    ImageViewCompat.setImageTintList(button, ColorStateList.valueOf(color))
}

internal fun OverlayWindowController.showLogWindow() {
    if (logHandleView != null || logBodyView != null) return
    val handle = LayoutInflater.from(themedContext).inflate(R.layout.overlay_log_handle, null)
    val body = LayoutInflater.from(themedContext).inflate(R.layout.overlay_log_body, null)
    logTitle = handle.findViewById(R.id.overlay_log_title)
    logText = body.findViewById(R.id.overlay_log_text)
    // §13：开窗即订阅全局日志并回放历史（历史保留在 RecognitionLog，不随关窗清除）
    bindRecognitionLog()
    bindCaptureState()
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

internal fun OverlayWindowController.hideLogWindow() {
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

internal fun OverlayWindowController.setupLogDrag(
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

internal fun OverlayWindowController.clampLogWindows() {
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

internal fun OverlayWindowController.updateLogLayouts() {
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

internal fun OverlayWindowController.persistLogPosition(lp: WindowManager.LayoutParams) {
    prefs.edit().putInt(KEY_LOG_X, lp.x).putInt(KEY_LOG_Y, lp.y).apply()
}

internal fun OverlayWindowController.defaultLogPosition(): Pair<Int, Int> {
    val screen = screenSize()
    val height = dp(LOG_DEFAULT_HEIGHT_DP)
    val margin = dp(12)
    val x = margin
    val y = (screen.second - height - dp(48)).coerceAtLeast(statusBarHeight())
    return x to y
}

/** 系统导航栏/手势条高度（framework 资源读取，不可用时 0——全屏手势设备无实体导航栏）。 */
