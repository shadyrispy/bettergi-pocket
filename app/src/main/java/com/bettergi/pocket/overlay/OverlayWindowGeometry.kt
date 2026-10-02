package com.bettergi.pocket.overlay

import android.view.Gravity

/**
 * 悬浮窗命中判定的**纯函数**（只吃 Int，不依赖 Android 框架对象 ⇒ JVM 单测可直接覆盖）。
 * 自 [OverlayWindowController.kt] 文件尾拆出（函数体一字未改，仍同包同可见性）。
 *
 * 约束：`WindowManager.LayoutParams.x` 的语义由 gravity 的横向分量决定 ——
 * 含 END（/RIGHT）时 x 是**距右缘偏移**，窗口左缘 = 屏宽 − x − 窗宽；
 * 含 START（/LEFT，以及未指定的默认态）时 x 才是窗口左缘；纵向分量同理（BOTTOM ⇔ 距底缘偏移）。
 * 本类主悬浮窗 = `TOP|END` + x=右缘内边距（见 [OverlayWindowController.show]），日志窗 = `TOP|START`。
 * 若不按 gravity 换算左上角，END 窗的命中区会被错算成「屏幕左缘一条带」：
 * 落在悬浮球上的模拟点击 prepareClickPassthrough 返回 false ⇒ 窗口不临时穿透，
 * 手势被自家 TYPE_ACCESSIBILITY_OVERLAY 吃掉（游戏只见 DOWN 无 UP）；
 * 同时屏幕左缘一带假阳性穿透。slop 外扩语义与换算无关：左/上缘含（≥）、右/下缘不含（<）。
 */
internal object OverlayWindowGeometry {

    /** gravity 横向分量 → 窗口实际左缘坐标。 */
    fun resolveWindowLeft(screenWidth: Int, lpX: Int, windowWidth: Int, gravity: Int): Int =
        when (gravity and Gravity.HORIZONTAL_GRAVITY_MASK) {
            // HORIZONTAL_GRAVITY_MASK 滤掉 START/END 的相对位后只剩 0/1/3/5：
            // END 滤完即 RIGHT，START 滤完即 LEFT（NO_GRAVITY 系统按 START 解析）
            Gravity.RIGHT -> screenWidth - lpX - windowWidth
            Gravity.CENTER_HORIZONTAL -> (screenWidth - windowWidth) / 2 + lpX
            else -> lpX
        }

    /** gravity 纵向分量 → 窗口实际顶缘坐标（本类两窗均为 TOP，此处保持语义完备）。 */
    fun resolveWindowTop(screenHeight: Int, lpY: Int, windowHeight: Int, gravity: Int): Int =
        when (gravity and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.BOTTOM -> screenHeight - lpY - windowHeight
            Gravity.CENTER_VERTICAL -> (screenHeight - windowHeight) / 2 + lpY
            else -> lpY
        }

    /** 触点 (touchX, touchY) 是否落在窗口矩形（含 [slop] 外扩）内。 */
    fun contains(
        screenWidth: Int,
        screenHeight: Int,
        lpX: Int,
        lpY: Int,
        windowWidth: Int,
        windowHeight: Int,
        gravity: Int,
        slop: Int,
        touchX: Int,
        touchY: Int,
    ): Boolean {
        val left = resolveWindowLeft(screenWidth, lpX, windowWidth, gravity)
        val top = resolveWindowTop(screenHeight, lpY, windowHeight, gravity)
        return touchX >= left - slop &&
            touchX < left + windowWidth + slop &&
            touchY >= top - slop &&
            touchY < top + windowHeight + slop
    }
}
