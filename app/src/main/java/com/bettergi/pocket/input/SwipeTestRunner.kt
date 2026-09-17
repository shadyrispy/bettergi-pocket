package com.bettergi.pocket.input

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast

/**
 * 滑动测试（真机调翻页参数）——**2026-09-18 由悬浮窗迁入脚本管理器**（用户需求③）。
 *
 * 原实现内嵌在 `OverlayWindowController`（面板内嵌参数区 + 执行时「缩球防遮挡」）；
 * 迁到 MainActivity 后不再需要缩球 —— 管理器自身即前台，靠**退到后台**把前台让回游戏。
 *
 * 参数持久化沿用旧 SharedPreferences（`swipe_test`）⇒ 升级后旧值不丢；
 * 滑动 x 固定 1614（3200 档列表中心列，与 profiles 对齐）。
 */
object SwipeTestRunner {

    private const val PREFS = "swipe_test"
    private const val KEY_START_Y = "swipe_start_y"
    private const val KEY_DIST = "swipe_dist"
    private const val KEY_METHOD = "swipe_method"

    const val DEFAULT_START_Y = 1150
    const val DEFAULT_DIST = 876

    /** 滑动起点/终点 x（3200 档基准；三档同构，x 不随档位换算）。 */
    const val SWIPE_X = 1614

    data class Params(val startY: Int, val dist: Int, val method: SwipeMethod)

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): Params = Params(
        startY = prefs(context).getInt(KEY_START_Y, DEFAULT_START_Y),
        dist = prefs(context).getInt(KEY_DIST, DEFAULT_DIST),
        method = parseMethod(prefs(context).getString(KEY_METHOD, "waypoint_chain")),
    )

    fun save(context: Context, startY: Int, dist: Int, method: SwipeMethod) {
        prefs(context).edit()
            .putInt(KEY_START_Y, startY)
            .putInt(KEY_DIST, dist)
            .putString(KEY_METHOD, methodKey(method))
            .apply()
    }

    fun methodKey(method: SwipeMethod): String =
        if (method == SwipeMethod.THREE_SEGMENT) "three_segment" else "waypoint_chain"

    fun parseMethod(value: String?): SwipeMethod =
        if (value == "three_segment") SwipeMethod.THREE_SEGMENT else SwipeMethod.WAYPOINT_CHAIN

    fun methodLabel(method: SwipeMethod): String =
        if (method == SwipeMethod.THREE_SEGMENT) "三段式" else "路标链"

    /** 坐标文案（Toast 与日志共用，便于与真机截图对账）。 */
    fun describe(params: Params): String =
        "($SWIPE_X,${params.startY})→($SWIPE_X,${params.startY - params.dist})"

    /**
     * 执行一次翻页滑动（向上滑 [Params.dist] px）。
     * ⚠️ 调用前必须保证**游戏在前台**（否则手势被本 Activity 吃掉）—— 见 MainActivity 的 `moveTaskToBack`。
     * @return true = 已注入；false = 无障碍未连接。
     */
    fun run(context: Context, params: Params): Boolean {
        val ok = InputAccessibilityService.swipe(
            SWIPE_X,
            params.startY,
            SWIPE_X,
            params.startY - params.dist,
            method = params.method,
        )
        Toast.makeText(
            context,
            if (ok) {
                "滑动已执行（${methodLabel(params.method)}）${describe(params)}"
            } else {
                "滑动失败：无障碍未连接"
            },
            Toast.LENGTH_SHORT,
        ).show()
        return ok
    }
}
