package com.bettergi.pocket.scan

import android.content.Context
import com.bettergi.pocket.dsl.FlowSource
import org.json.JSONObject
import java.io.File

/**
 * GOOD / 配装计划**输入仓库**（2026-09-18）。
 *
 * 背景（这是一条真缺陷的修复）：`ScriptRunner.startScan(..., plan)` 一直有 `plan` 参数，
 * 但**唯一注入点是 adb 调试通道**。于是从悬浮窗点「圣遗物锁定 / 自动装备」时 `plan = null`，
 * `ScanEngine.foreach` 见空直接 `Log.w("foreach: 'plan' not available or empty, skipped")` 返回 ——
 * 用户看到的是"跑了一遍界面，什么都没做"。
 *
 * 本类补上用户侧的输入通道：
 * - 落点 `filesDir/scripts/good/current.json`（**单文件**：三条扫描共用一份产物，两条执行脚本共用一份输入）；
 * - 需求由**脚本自己的 `vars` 声明**反推（见 [GoodPlan]），不在这里写死"哪条脚本要什么"；
 * - 文件坏了 / 形态不对 ⇒ 明确返回失败原因，不静默接受。
 */
object GoodRepository {

    private const val DIR = "scripts/good"
    private const val NAME = "current.json"
    private const val PREFS = "good_import"
    private const val KEY_SOURCE = "source_name"
    private const val KEY_AT = "imported_at"
    private const val KEY_LAST_EXPORT = "last_export"

    /** 当前输入的状态（管理器/悬浮窗用来显示"已选：xxx"）。 */
    data class State(val sourceName: String, val importedAt: Long, val bytes: Long)

    fun file(context: Context): File = File(context.filesDir, "$DIR/$NAME")

    fun read(context: Context): JSONObject? =
        runCatching { JSONObject(file(context).readText()) }.getOrNull()

    fun state(context: Context): State? {
        val f = file(context)
        if (!f.exists()) return null
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return State(
            sourceName = p.getString(KEY_SOURCE, NAME) ?: NAME,
            importedAt = p.getLong(KEY_AT, f.lastModified()),
            bytes = f.length(),
        )
    }

    /** 导入文本（SAF 选中的内容）。返回 (ok, message)。 */
    fun save(context: Context, sourceName: String, text: String): Pair<Boolean, String> {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return false to "不是合法 JSON"
        if (!looksLikeData(json)) return false to "这份文件里既没有 artifacts 也没有 plan"
        val dir = File(context.filesDir, DIR)
        if (!dir.exists() && !dir.mkdirs()) return false to "无法创建目录：${dir.path}"
        return runCatching {
            file(context).writeText(text)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_SOURCE, sourceName)
                .putLong(KEY_AT, System.currentTimeMillis())
                .apply()
            true to "已导入：$sourceName"
        }.getOrElse { false to "写入失败：${it.message}" }
    }

    /** 从设备上的文件导入（adb 调试链用：`/sdcard/...` 是 adb 可直接投放的位置）。 */
    fun importFrom(context: Context, src: java.io.File): Pair<Boolean, String> {
        if (!src.exists()) return false to "源文件不存在：${src.path}"
        // ⚠️ 分区存储：`/sdcard` 根目录下的文件应用读不到（实测 Permission denied），
        //    调试投放请用应用自己的外部目录 `Android/data/<pkg>/files/`。
        val text = runCatching { src.readText() }.getOrElse {
            return false to "读取失败（${it.javaClass.simpleName}）：请把文件放到 Android/data/<包名>/files/ 下"
        }
        return save(context, src.name, text)
    }

    /** 最近一次扫描导出的文件名（由前台服务在收到 `vars["file"]` 时写入）。 */
    fun lastExport(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_EXPORT, null)

    fun setLastExport(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_EXPORT, name)
            .apply()
    }

    fun clear(context: Context): Boolean {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        return file(context).delete()
    }

    /** 这条脚本是否需要用户提供输入（声明了 `import` 动作 且 `vars` 要数据）。 */
    fun needsInput(context: Context, flowKey: String): Boolean =
        GoodPlan.needsInput(flowJson(context, flowKey))

    /**
     * 取出能喂给该脚本的输入。
     * @return null = 没有文件 / 该脚本不需要输入 / 这份文件喂不了它 —— 三者都要求调用方**给出提示**，不要静默开跑。
     */
    fun planFor(context: Context, flowKey: String): List<JSONObject>? {
        val demand = GoodPlan.demandOf(flowJson(context, flowKey))
        if (!demand.needsInput) return null
        return GoodPlan.pick(read(context), demand)
    }

    /** 脚本显示名（`ui.label`），拿不到就退回 flowKey —— 给提醒文案用。 */
    fun labelOf(context: Context, flowKey: String): String =
        runCatching { org.json.JSONObject(
            FlowSource.open(context.assets, "dsl/flows/$flowKey.json").bufferedReader().use { it.readText() },
        ).optJSONObject("ui")?.optString("label", "") }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: flowKey

    private fun flowJson(context: Context, flowKey: String): JSONObject? = runCatching {
        JSONObject(
            FlowSource.open(context.assets, "dsl/flows/$flowKey.json").bufferedReader().use { it.readText() },
        )
    }.getOrNull()

    private fun looksLikeData(json: JSONObject): Boolean =
        (json.optJSONArray("artifacts")?.length() ?: 0) > 0 || (json.optJSONArray("plan")?.length() ?: 0) > 0
}
