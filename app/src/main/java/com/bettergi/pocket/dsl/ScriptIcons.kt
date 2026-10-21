package com.bettergi.pocket.dsl

import com.bettergi.pocket.R

/**
 * 脚本图标 / 动作图标 → drawable 的**唯一映射表**（2026-09-18）。
 *
 * 悬浮窗与管理器都要画同一套图标，早先各写一份 `when` ⇒ 必然漂移。这里收成一处：
 * 界面只问"这个 icon 名 / 这个动作 kind 画什么"，不再各自维护。
 * 图标本身是**白色描边/填充**的矢量（24dp viewBox），颜色由调用方 tint —— 因为同一个 drawable
 * 要同时服务深底的悬浮窗与跟随系统深浅色的管理器。
 */
object ScriptIcons {

    /** `ui.icon` → 脚本图标。未知名字回落到通用齿轮（与 `FlowValidator` 的回落一致）。 */
    fun script(icon: String): Int = when (icon) {
        "artifact" -> R.drawable.ic_script_artifact
        "weapon" -> R.drawable.ic_script_weapon
        "character" -> R.drawable.ic_script_character
        "lock" -> R.drawable.ic_script_lock
        "equip" -> R.drawable.ic_script_equip
        else -> R.drawable.ic_script_gear
    }

    /** `actions[].kind` → 动作图标。未知 kind 回落到「运行」（`FlowValidator` 已把未知 kind 挡在解析层）。 */
    fun action(kind: String): Int = when (kind) {
        "stop" -> R.drawable.ic_action_stop
        "export" -> R.drawable.ic_action_export
        "import" -> R.drawable.ic_action_import
        "config" -> R.drawable.ic_action_config
        "open" -> R.drawable.ic_action_open
        else -> R.drawable.ic_action_run
    }

    /** 动作的主色：破坏性用红，产出/运行用金，其余用常态色。 */
    fun actionTint(kind: String): Int = when (kind) {
        "stop" -> R.color.overlay_notice_bar_error
        "export", "run" -> R.color.overlay_gold
        else -> R.color.overlay_text
    }
}
