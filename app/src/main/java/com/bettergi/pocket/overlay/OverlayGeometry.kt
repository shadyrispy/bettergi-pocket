package com.bettergi.pocket.overlay
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ScrollView
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Build
import android.view.animation.DecelerateInterpolator
import com.bettergi.pocket.overlay.OverlayWindowController.Companion.IDLE_DELAY_MS
import com.bettergi.pocket.overlay.OverlayWindowController.Companion.KEY_Y
import android.graphics.PixelFormat
import android.util.DisplayMetrics

/*
 * Stage 3.4：几何与窗口布局（从 OverlayWindowController.kt 抽出，扩展函数，函数体一字未改）。
 */

internal fun OverlayWindowController.overlayParams(
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
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        flags,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.x = x
        this.y = y
    }
}

internal fun OverlayWindowController.setupDrag(
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

internal fun OverlayWindowController.setupDragAndClick(
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
internal fun OverlayWindowController.snapToEdge(lp: WindowManager.LayoutParams, animate: Boolean) {
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
internal fun OverlayWindowController.constrainPanelHeight() {
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

internal fun OverlayWindowController.ensurePanelOnScreen(lp: WindowManager.LayoutParams) {
    clampToScreen(lp)
}

/** ★ 2026-09-14：主悬浮窗距右缘固定内边距（只纵向移动 ⇒ x 恒定）。 */
internal fun OverlayWindowController.overlaySafeTopY(): Int = Math.max(dp(140), (screenSize().second * 0.12f).toInt())

/**
 * ★ 2026-09-14：主悬浮窗位置规范化 —— x 钉右缘 + y 夹在 [按钮带下方, 屏底−导航栏] 之间。
 * 拖动/重定位/展开收起都走它，保证「只沿右缘纵向移动」在任何入口都成立。
 */
internal fun OverlayWindowController.clampOverlayPosition(lp: WindowManager.LayoutParams) {
    val view = rootView ?: return
    val screen = screenSize()
    val height = if (view.height > 0) view.height else dp(48)
    lp.x = overlayRightMarginPx()
    val minY = overlaySafeTopY()
    val maxY = (screen.second - height - navigationBarHeight() - dp(4)).coerceAtLeast(minY)
    lp.y = lp.y.coerceIn(minY, maxY)
}

internal fun OverlayWindowController.clampToScreen(lp: WindowManager.LayoutParams) {
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

internal fun OverlayWindowController.updateLayout(lp: WindowManager.LayoutParams) {
    val view = rootView ?: return
    try {
        windowManager.updateViewLayout(view, lp)
    } catch (_: Throwable) {
    }
}

internal fun OverlayWindowController.persistPosition(lp: WindowManager.LayoutParams) {
    // ★ 2026-09-16（审计 P2-2）：不再存 x —— 主窗 x 恒由 clampOverlayPosition 钉在右缘
    //   （gravity=END 下 x 是**距右缘偏移**），持久化它既无意义、又容易被误当作左坐标复用。
    prefs.edit().putInt(KEY_Y, lp.y).apply()
}

internal fun OverlayWindowController.wakeBubble() {
    mainHandler.removeCallbacks(idleFadeRunnable)
    fadeBubble(1f)
}

internal fun OverlayWindowController.scheduleIdleFade() {
    mainHandler.removeCallbacks(idleFadeRunnable)
    if (!expanded) {
        mainHandler.postDelayed(idleFadeRunnable, IDLE_DELAY_MS)
    }
}

internal fun OverlayWindowController.fadeBubble(alpha: Float) {
    val bubble = bubbleView ?: return
    if (expanded || bubble.visibility != View.VISIBLE) return
    bubble.animate().alpha(alpha).setDuration(220).start()
}

internal fun OverlayWindowController.startScreenWatch() {
    if (watchingScreen) return
    watchingScreen = true
    displayManager.registerDisplayListener(displayListener, mainHandler)
    context.registerComponentCallbacks(configCallbacks)
}

internal fun OverlayWindowController.stopScreenWatch() {
    if (!watchingScreen) return
    watchingScreen = false
    displayManager.unregisterDisplayListener(displayListener)
    context.unregisterComponentCallbacks(configCallbacks)
}

internal fun OverlayWindowController.rememberScreen() {
    val screen = screenSize()
    lastScreenW = screen.first
    lastScreenH = screen.second
}

internal fun OverlayWindowController.relocateOverlays(force: Boolean) {
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

internal fun OverlayWindowController.screenSize(): Pair<Int, Int> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = windowManager.maximumWindowMetrics.bounds
        return bounds.width() to bounds.height()
    }
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION")
    windowManager.defaultDisplay.getRealMetrics(metrics)
    return metrics.widthPixels to metrics.heightPixels
}

internal fun OverlayWindowController.navigationBarHeight(): Int {
    val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
    return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
}

internal fun OverlayWindowController.statusBarHeight(): Int {
    val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
    if (id > 0) {
        return context.resources.getDimensionPixelSize(id)
    }
    return dp(28)
}

internal fun OverlayWindowController.dp(value: Int): Int {
    return (value * context.resources.displayMetrics.density).toInt()
}
