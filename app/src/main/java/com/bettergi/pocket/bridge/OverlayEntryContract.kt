package com.bettergi.pocket.bridge

import android.content.Context
import android.content.Intent

/**
 * **悬浮窗 → 管理器（MainActivity）入口契约**（工单 D 反向边反转）。
 *
 * 之前 `overlay/OverlayScriptRows.kt` 直接 import `ui/MainActivity` 并引用它的
 * EXTRA 常量拼 Intent ⇒ overlay → ui 反向依赖。现在 extras 键值与入口 Intent
 * 的组装下沉到本契约 object（键值**逐字不变**），overlay 与 ui/MainActivity 双侧都改引这里。
 */
object OverlayEntryContract {

    /** 悬浮窗「设置」长按进入管理器时置 true。 */
    const val EXTRA_FROM_OVERLAY = "from_overlay"

    /** 悬浮窗的 `import` 动作：进入管理器的同时打开输入文件选择器（SAF 必须由 Activity 发起）。 */
    const val EXTRA_PICK_GOOD = "pick_good"

    /** 从悬浮窗拉起脚本管理器（原 OverlayScriptRows.openScriptManager 的 Intent 组装，flags 不变）。 */
    fun managerIntent(context: Context, pickGood: Boolean): Intent =
        Intent(context, com.bettergi.pocket.ui.MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            putExtra(EXTRA_FROM_OVERLAY, true)
            if (pickGood) putExtra(EXTRA_PICK_GOOD, true)
        }
}
