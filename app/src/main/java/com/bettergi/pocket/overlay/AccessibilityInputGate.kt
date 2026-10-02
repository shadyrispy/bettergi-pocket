package com.bettergi.pocket.overlay

import android.content.Context

/**
 * 悬浮窗对输入侧的唯一依赖面（方案 3.2 overlay→input 反转）：接口住 overlay、实现住 input，
 * [A11yOverlayRuntime.attachService] 时注入 —— overlay 包不再 import input 包。
 */
interface AccessibilityInputGate {
    /** 无障碍状态变化广播 action（registerReceiver 用）。 */
    val stateChangedAction: String

    /** 无障碍未开启时拉起系统设置页（message = 提示文案；空串用服务的默认提示）。返回是否成功拉起。 */
    fun ensureEnabled(context: Context, message: String = ""): Boolean

    /** 服务已连接且可用（health == CONNECTED）。 */
    fun isOperational(context: Context): Boolean

    /** 服务在设置里可见但断开（health == DISCONNECTED，状态点红色档）。 */
    fun isDisconnected(context: Context): Boolean
}
