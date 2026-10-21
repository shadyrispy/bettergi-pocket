package com.bettergi.pocket.scan

/**
 * **点击形态开关**（调试/A-B 用，与 [TimingOverrides] 同风格）。
 *
 * 为什么需要：历史上「用微滑 click 还是纯 tap」的结论来自**华为真机 EMUI**
 * （`InputAccessibilityService.tap` 注释：char_popup 把 2px 微滑识别为拖拽、9 格全停首格，adb tap 9/9 切换）。
 * 那是**真机特性**，**不必然适用于 BlueStacks** ⇒ 需要一个免重编的开关，在模拟器上做受控 A/B。
 *
 * - `true`  ⇒ 格点击用**纯 tap**（零位移 stroke）
 * - `false` ⇒ 格点击用**微滑 click**（2px，历史默认）
 *
 * 由 `DEBUG_SET_CELL_CLICK` 调试动作切换（见 TriggerForegroundService）。
 */
object ClickModeOverrides {
    @Volatile
    var cellTap: Boolean = false

    fun summary(): String = "cellClick=${if (cellTap) "tap" else "swipe"}"
}
