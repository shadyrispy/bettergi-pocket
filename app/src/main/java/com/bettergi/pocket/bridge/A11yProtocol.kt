package com.bettergi.pocket.bridge

import android.content.Context
import android.net.Uri

/** 桥方法名与 Bundle 键（主进程 OverlayBridge 与 :a11y 侧 A11yOverlayRuntime 的共享协议，3.2 归拢）。 */
object A11yProtocol {
    const val M_SHOW = "overlay_show"
    const val M_HIDE = "overlay_hide"
    const val M_COLLAPSE = "overlay_collapse"
    const val M_PROGRESS = "overlay_progress"
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
    /**
     * 现问活动窗口包名。**单独一条 method，不并进 [M_STATUS]**：
     * 状态读是高频的（管理器页显示、每次派发前的连接自检都读），而现问要在 :a11y 主线程上做
     * 节点查询、binder 线程等它最多 1s —— 挂在 status 上等于让每次状态读都可能付这一秒。
     *
     * ★ #197（2026-10-04）调用频次口径：起跑闸会**有界重试**这条（至多 5 次、间隔 200ms、
     * 总预算 1200ms，参数与实测依据见 `scan/ScriptRunner.StartGateLiveAsk`），注入闸
     * `InputAccessibilityService.allowInject` 仍是每次一单直读。也就是最坏是"一次起跑 5 条"，
     * 不会变成高频轮询；重试的动机是华为 EML-AL00 上点 ▶ 那刻 rootInActiveWindow 恒空
     * （焦点切换中的瞬态），不是因为"多问几次更安全"。
     */
    const val M_ACTIVE_WINDOW = "active_window"
    const val M_CLICK = "click"
    const val M_SWIPE = "swipe"
    const val M_SCAN_PROGRESS = "scan_progress"
    const val M_PROBE = "probe"
    const val M_PROBE_BAL = "probe_bal"
    const val M_BACK = "back"
}