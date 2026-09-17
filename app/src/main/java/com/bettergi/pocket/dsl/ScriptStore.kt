package com.bettergi.pocket.dsl

import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.File

/**
 * 脚本清单（P3，2026-09-18）：**本地**脚本管理（导入 JSON + 启用开关）。
 *
 * 设计要点
 * - 内置脚本来自 `assets/dsl/flows/<name>.json`；导入的脚本写到 `filesDir/scripts/installed/dsl/flows/<name>.json —— 与
 *   [FlowSource] 的 override 路径**同源** ⇒ 导入即生效，无需重编 APK。
 * - 展示信息（按钮文字/图标/顺序）来自流程的 `ui` 段（见 P2）；非法 `ui` 由 [FlowValidator.validate] 列出并把 issue 显示在管理界面，而不是静默丢弃。
 * - 全部用本地文件与纯函数解析 ⇒ 无任何网络交互（本 app 断网）。
 * - 纯函数 [parseEntry] 便于 JVM 单测（Context 相关部分只做 IO：列表/开关/导入。
 */
object ScriptStore {

    const val ENABLED_FILE = "scripts/enabled.json"
    private const val FLOWS_DIR = "dsl/flows"

    /**
     * 清单变更广播（导入 / 开关 / 恢复内置）。
     * 用**显式包名广播**而非进程内回调：悬浮窗宿主在 P4b 会迁到 `:a11y`（另一个进程），
     * 同 UID 跨进程仍可送达；接收侧用 `RECEIVER_NOT_EXPORTED` 保持不对外暴露。
     */
    const val ACTION_SCRIPTS_CHANGED = "com.bettergi.pocket.action.SCRIPTS_CHANGED"

    private fun notifyChanged(context: Context) {
        runCatching { context.sendBroadcast(Intent(ACTION_SCRIPTS_CHANGED).setPackage(context.packageName)) }
    }

    data class Entry(
        val key: String,
        val label: String,
        val icon: String,
        val order: Int,
        val enabled: Boolean,
        val imported: Boolean,
        val issues: List<String>,
        /** 是否声明了 `ui` 段 ⇒ 是否**上悬浮窗**（未声明者只在管理器可见，如 2 个标定流程）。 */
        val hasUi: Boolean = false,
    )

    /** 纯函数：由流程 JSON 解析清单条目（无 Android 依赖，单测可覆盖）。 */
    fun parseEntry(json: JSONObject, key: String, imported: Boolean, enabled: Boolean): Entry {
        val issues = FlowValidator.validate(json).map { "${it.path}: ${it.message}" }
        val ui = FlowValidator.parseUi(json)
        return Entry(
            key = key,
            label = ui?.label ?: key,
            icon = ui?.icon ?: "gear",
            order = ui?.order ?: Int.MAX_VALUE,
            enabled = enabled,
            imported = imported,
            issues = issues,
            hasUi = ui != null,
        )
    }

    /** 内置 + 已导入，按 `ui.order` 升序（同序按 key）。 */
    fun list(context: Context): List<Entry> {
        val enabled = readEnabled(context)
        val out = ArrayList<Entry>()
        // 内置
        val names = context.assets.list(FLOWS_DIR)?.filter { it.endsWith(".json") } ?: emptyList()
        for (n in names) {
            val key = n.removeSuffix(".json")
            val json = runCatching {
                JSONObject(FlowSource.open(context.assets, "$FLOWS_DIR/$n").bufferedReader().use { it.readText() })
            }.getOrNull() ?: JSONObject()
            out.add(parseEntry(json, key, imported = false, enabled = enabled[key] ?: true))
        }
        // 已导入（override 目录）
        val dir = File(context.filesDir, "${FlowSource.OVERRIDE_ROOT}/$FLOWS_DIR")
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }?.forEach { f ->
            val key = f.name.removeSuffix(".json")
            val json = runCatching { JSONObject(f.readText()) }.getOrNull() ?: JSONObject()
            out.add(parseEntry(json, key, imported = true, enabled = enabled[key] ?: true))
        }
        // 同一 key 同时存在内置与导入 ⇒ 以导入为准（FlowSource 亦为 override 优先）。
        val merged = LinkedHashMap<String, Entry>()
        out.sortedWith(compareBy<Entry> { it.order }.thenBy { it.key }).forEach { merged[it.key] = it }
        return merged.values.toList()
    }

    fun setEnabled(context: Context, key: String, enabled: Boolean) {
        val map = readEnabled(context).toMutableMap()
        map[key] = enabled
        writeEnabled(context, map)
        android.util.Log.i("BetterGI.Scripts", "setEnabled key=$key enabled=$enabled")
        notifyChanged(context)
    }

    /** 导入：写入 override 目录；返回 (ok, message)。 */
    fun importFlow(context: Context, key: String, text: String): Pair<Boolean, String> {
        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: return false to "不是合法 JSON"
        val issues = FlowValidator.validate(json)
        if (issues.isNotEmpty()) {
            return false to issues.joinToString("; ") { "${it.path}: ${it.message}" }
        }
        val dir = File(context.filesDir, "${FlowSource.OVERRIDE_ROOT}/$FLOWS_DIR")
        if (!dir.exists() && !dir.mkdirs()) return false to "无法创建目录：${dir.path}"
        val target = File(dir, "$key.json")
        runCatching { target.writeText(text) }.getOrElse { return false to "写入失败：${it.message}" }
        notifyChanged(context)
        return true to "已导入：$key.json"
    }

    /** 恢复内置：删除 override 副本。 */
    fun resetFlow(context: Context, key: String): Boolean {
        val f = File(context.filesDir, "${FlowSource.OVERRIDE_ROOT}/$FLOWS_DIR/$key.json")
        val deleted = if (f.exists()) f.delete() else false
        if (deleted) notifyChanged(context)
        return deleted
    }

    fun readEnabled(context: Context): Map<String, Boolean> {
        val f = File(context.filesDir, ENABLED_FILE)
        if (!f.exists()) return emptyMap()
        val json = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, Boolean>()
        val it = json.keys()
        while (it.hasNext()) { val k = it.next(); out[k] = json.optBoolean(k, true) }
        return out
    }

    private fun writeEnabled(context: Context, map: Map<String, Boolean>) {
        val f = File(context.filesDir, ENABLED_FILE)
        f.parentFile?.mkdirs()
        val json = JSONObject()
        map.forEach { (k, v) -> json.put(k, v) }
        f.writeText(json.toString(2))
    }
}
