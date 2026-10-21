package com.bettergi.pocket.dsl

import android.content.Context
import android.content.Intent

/**
 * 脚本清单（P3 → simple 分支瘦身，2026-10-07）：**内置**脚本管理（启用开关）。
 *
 * ★ simple 阶段3（spec §1/§4）：DSL 运行时已下线 ——
 * - flow JSON（含 `ui` 段）已删除，清单由 [BUILT_INS] Kotlin 常量承载（label/icon/order/actions
 *   逐字平移原五条 flow 的 `ui` 块）；
 * - 「导入脚本」持久化通道删除（导入 UI / override 目录 / FlowValidator 校验一并下线）；
 * - 内置流程的启用开关改为 SharedPreferences **bitset**（一位 = 一条流程，1 = 启用；
 *   默认全启用——与旧 enabled.json 缺项语义一致）。
 * 全部用本地存储与纯常量 ⇒ 无任何网络交互（本 app 断网）。
 */
object ScriptStore {

    /**
     * 清单变更广播（开关切换）。用**显式包名广播**：悬浮窗宿主在 `:a11y`（另一个进程），
     * 同 UID 跨进程仍可送达；接收侧用 `RECEIVER_NOT_EXPORTED` 保持不对外暴露。
     */
    const val ACTION_SCRIPTS_CHANGED = "com.bettergi.pocket.action.SCRIPTS_CHANGED"

    /** 行内动作渲染上限；超出的部分由界面放进「更多」子行（面板只有 248dp 宽，放不下第 3 个）。 */
    const val MAX_ACTIONS = 2

    /** 悬浮窗按钮上的一个动作（原 FlowValidator.OverlayAction，随其删除并入此处）。 */
    data class OverlayAction(val kind: String, val label: String)

    /** 一条内置流程的静态清单（原 flow JSON `ui` 段的 Kotlin 平移）。 */
    data class NativeFlow(
        val key: String,
        val label: String,
        val icon: String,
        val order: Int,
        /** 是否声明了 `ui` 段 ⇒ 是否**上悬浮窗**（未声明者只在管理器可见，如 2 个标定流程）。 */
        val hasUi: Boolean,
        /** 该脚本声明的动作（`ui.actions`，按顺序）⇒ 悬浮窗行内按钮 / 管理器行副标题都由它派生。 */
        val actions: List<OverlayAction>,
    )

    /** 内置流程注册表（order 升序；两条标定流无 ui 段，只在管理器可见）。 */
    val BUILT_INS: List<NativeFlow> = listOf(
        NativeFlow("artifact_scan", "圣遗物扫描", "artifact", 10, true, listOf(OverlayAction("export", "导出"), OverlayAction("run", "开始"))),
        NativeFlow("weapon_scan", "武器扫描", "weapon", 20, true, listOf(OverlayAction("export", "导出"), OverlayAction("run", "开始"))),
        NativeFlow("character_scan", "角色扫描", "character", 30, true, listOf(OverlayAction("export", "导出"), OverlayAction("run", "开始"))),
        NativeFlow("artifact_lock", "圣遗物锁定", "lock", 40, true, listOf(OverlayAction("import", "导入"), OverlayAction("run", "开始"))),
        NativeFlow("auto_equip", "自动装备", "equip", 50, true, listOf(OverlayAction("import", "导入"), OverlayAction("run", "开始"))),
        NativeFlow("char_calibrate", "char_calibrate", "gear", Int.MAX_VALUE, false, emptyList()),
        NativeFlow("setfilter_calibrate", "setfilter_calibrate", "gear", Int.MAX_VALUE, false, emptyList()),
        // ★ 2026-10-14：套装筛选后扫描（adb 调试工具，无 ui 段 ⇒ 只在管理器「开发用」小节）。
        //   注册进 KEYS 是**必须**的：ScriptRunner 起跑校验 `flowName !in KEYS` 会静默回落
        //   artifact_scan（全量），那正是本流程要避免的。
        NativeFlow("artifact_scan_set", "artifact_scan_set", "artifact", Int.MAX_VALUE, false, emptyList()),
    )

    /** 全部 flowName 的**单一事实源**：run() 分派、ScriptRunner 起跑校验、UI 清单三处共用。 */
    val KEYS: List<String> = BUILT_INS.map { it.key }

    data class Entry(
        val key: String,
        val label: String,
        val icon: String,
        val order: Int,
        val enabled: Boolean,
        /** 是否声明了 `ui` 段 ⇒ 是否**上悬浮窗**（未声明者只在管理器可见，如 2 个标定流程）。 */
        val hasUi: Boolean,
        /** 该脚本声明的动作（`ui.actions`，按顺序）⇒ 悬浮窗行内按钮 / 管理器行副标题都由它派生。 */
        val actions: List<OverlayAction> = emptyList(),
    )

    /** 内置清单，按 `order` 升序。 */
    fun list(context: Context): List<Entry> =
        BUILT_INS.sortedWith(compareBy { it.order })
            .map { Entry(it.key, it.label, it.icon, it.order, isEnabled(context, it.key), it.hasUi, it.actions) }

    fun setEnabled(context: Context, key: String, enabled: Boolean) {
        val idx = KEYS.indexOf(key)
        if (idx < 0) return
        val prefs = prefs(context)
        val mask = prefs.getLong(MASK_KEY, ALL_ENABLED)
        val updated = if (enabled) mask or (1L shl idx) else mask and (1L shl idx).inv()
        prefs.edit().putLong(MASK_KEY, updated).apply()
        android.util.Log.i("BetterGI.Scripts", "setEnabled key=$key enabled=$enabled")
        runCatching { context.sendBroadcast(Intent(ACTION_SCRIPTS_CHANGED).setPackage(context.packageName)) }
    }

    /** 开关状态（bitset）：1 = 启用。缺项/旧安装默认全启用（与旧 enabled.json 缺项语义一致）。 */
    fun isEnabled(context: Context, key: String): Boolean {
        val idx = KEYS.indexOf(key)
        if (idx < 0) return false
        val mask = prefs(context).getLong(MASK_KEY, ALL_ENABLED)
        return (mask shr idx) and 1L == 1L
    }

    private const val MASK_KEY = "enabled_mask"

    /** 全启用掩码（低 [KEYS] 位全 1）。 */
    private val ALL_ENABLED: Long =
        if (KEYS.size >= 64) -1L else ((1L shl KEYS.size) - 1L)

    private fun prefs(context: Context) =
        context.getSharedPreferences("bp_scripts", Context.MODE_PRIVATE)
}
