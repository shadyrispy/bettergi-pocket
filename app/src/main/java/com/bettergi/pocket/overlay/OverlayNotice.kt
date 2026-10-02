package com.bettergi.pocket.overlay
import android.graphics.Color
import com.bettergi.pocket.R
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/*
 * Stage 3.4：提醒条（从 OverlayWindowController.kt 抽出，扩展函数，函数体一字未改）。
 */

internal fun OverlayWindowController.showNotice(level: String, text: String) {
    val container = noticeView ?: return
    val accent = noticeAccent ?: return
    val label = noticeLabel ?: return
    if (text.isBlank()) return
    val (accentColor, bgColor, holdMs) = when (level.uppercase()) {
        "ERROR" -> Triple(R.color.overlay_notice_bar_error, R.color.overlay_notice_bg_error, 12_000L)
        "WARN" -> Triple(R.color.overlay_notice_bar_warn, R.color.overlay_notice_bg_warn, 6_000L)
        else -> Triple(R.color.overlay_notice_bar_info, R.color.overlay_notice_bg_info, 3_000L)
    }
    accent.setBackgroundColor(context.getColor(accentColor))
    container.setBackgroundColor(context.getColor(bgColor))
    label.text = text
    container.visibility = View.VISIBLE
    noticeHide?.let { mainHandler.removeCallbacks(it) }
    val hide = Runnable { noticeView?.visibility = View.GONE }
    noticeHide = hide
    mainHandler.postDelayed(hide, holdMs)
}

/** 扫描进度副文本（主线程调用；P1-c 悬浮窗入口）。 */
