package com.bettergi.pocket.bridge

import android.content.Context
import android.net.Uri

/** 桥方法名与 Bundle 键（主进程 OverlayBridge 与 :a11y 侧 A11yOverlayRuntime 的共享协议，3.2 归拢）。 */
object A11yProtocol {
    const val M_SHOW = "overlay_show"
    const val M_HIDE = "overlay_hide"
    const val M_COLLAPSE = "overlay_collapse"
    const val M_PROGRESS = "overlay_progress"
    const val M_CAPTURE = "overlay_capture_status"
    const val M_PT_PREPARE = "overlay_pt_prepare"
    const val M_PT_RESTORE = "overlay_pt_restore"
    const val M_CLICK_THROUGH = "overlay_click_through"
    const val M_EVENT = "overlay_event"
    const val M_NOTICE = "notice_push"

    const val K_OK = "ok"
    /** ★ A32：错误文本键（unknown_method 等）。只加不改，旧调用方不读也不受影响。 */
    const val K_ERROR = "error"
    /** ★ A32：回显发起 method 名，便于跨进程排障。 */
    const val K_METHOD = "method"
    const val K_TEXT = "text"
    const val K_RUNNING = "running"
    const val K_X = "x"
    const val K_Y = "y"
    const val K_ENABLED = "enabled"
    const val K_HIDDEN = "hidden"
    const val K_KIND = "kind"
    const val K_COUNT = "count"
    const val K_LEVEL = "level"

    const val EVENT_TALK = "talk"
    const val EVENT_CHAT_ICONS = "chat_icons"
    const val EVENT_CHAT_CLICK = "chat_click"

    // ---- `.a11y` authority（AccessibilityBridgeProvider，主进程 → 无障碍进程）----
    // 工单 D：authority 后缀与 status/snapshot 方向的 method 名集中在此（值逐字不变），
    // 调用方（OverlayBridge / NoticeRouter / InputAccessibilityService）只 import，不再自拼。

    /** 与 `AccessibilityBridgeProvider` 的 authority 后缀一致（无障碍进程）。 */
    const val AUTHORITY_SUFFIX = ".a11y"

    /** 统一构建 `.a11y` Provider 的调用 URI（原先三处各写一遍字符串拼接）。 */
    fun a11yUri(context: Context): Uri =
        Uri.parse("content://${context.packageName}$AUTHORITY_SUFFIX")

    // status/snapshot 方向的 method 名（AccessibilityBridgeProvider 的方法表，值不变）：
    const val M_STATUS = "status"
    const val M_CLICK = "click"
    const val M_SWIPE = "swipe"
    const val M_SCAN_PROGRESS = "scan_progress"
    const val M_PROBE = "probe"
    const val M_PROBE_BAL = "probe_bal"
    const val M_BACK = "back"
}