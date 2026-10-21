package com.bettergi.pocket.dsl

import org.json.JSONObject

/**
 * §16.3 S2 包格式与门禁：flow JSON v1.1 校验（纯函数、无 Android 依赖 → JVM 单测可直接覆盖）。
 *
 * - [parseInfo]：v1.1 读 `info{name,version,min_host_version,type:"pocket-script"}`；
 *   v1.0 兼容（无 info，取顶层 `version` 字符串，min_host_version 默认 0.0.0）。
 * - [validate]：校验 `steps` 数组 + 每 step 含 `do` 原语名；报步骤序号与字段路径。
 * - [hostSatisfies]：min_host_version 语义比较当前宿主版本（BuildConfig.VERSION_NAME）。
 * - [parseUi]：P2 悬浮窗按钮描述 `ui{label,icon,order,color?,confirm?,hint?}` —— 缺省则不上窗；
 *   **P4 增 `actions[]`**：脚本自己声明本行右侧有哪些动作（`run/stop/export/import/config/open`），
 *   界面只按 `kind` 渲染图标 —— 不同脚本的交互差别（扫描要"导出+开始"、锁定要"导入+开始"）由脚本表达，不由界面写死。
 *   非法 `icon`/`color`/`kind` 一律在 [validate] 报错（避免静默丢弃）。
 */
object FlowValidator {
    const val DEFAULT_TYPE = "pocket-script"

    /** 悬浮窗按钮图标白名单（P2）。 */
    val ICONS: Set<String> = setOf("artifact", "weapon", "character", "lock", "equip", "gear")

    /**
     * 悬浮窗按钮动作白名单（P4）。
     * `kind` 决定点了做什么，界面只认 kind；`label` 退化为**无障碍名/长按提示**（按钮本身渲染成图标）。
     */
    val ACTION_KINDS: Set<String> = setOf("run", "stop", "export", "import", "config", "open")

    /** `notify` 原语的级别白名单（P4）。 */
    val NOTICE_LEVELS: Set<String> = setOf("info", "warn", "error")

    /** 行内动作渲染上限；超出的部分由界面放进「更多」子行（面板只有 248dp 宽，放不下第 3 个）。 */
    const val MAX_ACTIONS = 2

    /** `label` 缺省值（按 kind）。 */
    private val DEFAULT_ACTION_LABEL = mapOf(
        "run" to "开始",
        "stop" to "停止",
        "export" to "导出",
        "import" to "导入",
        "config" to "配置",
        "open" to "打开",
    )

    /** 缺省动作：只说 `ui` 没说 `actions` 的脚本 = 一个「开始」。 */
    private fun defaultActions(): List<OverlayAction> = listOf(OverlayAction("run", "开始"))

    /** 悬浮窗按钮上的一个动作（P4）。 */
    data class OverlayAction(val kind: String, val label: String)

    /** 颜色格式：#RRGGBB。 */
    private val COLOR_RE = Regex("^#[0-9A-Fa-f]{6}$")

    /** 悬浮窗按钮描述（P2）：由 DSL 的 `ui` 段解析；`label` 为空 ⇒ 不上窗。 */
    data class OverlayUi(
        val label: String,
        val icon: String,
        val order: Int,
        val color: String? = null,
        val confirm: Boolean = false,
        val hint: String? = null,
        /** 行右侧动作（P4）；缺省 = 一个「开始」。 */
        val actions: List<OverlayAction> = listOf(OverlayAction("run", "开始")),
    )

    data class FlowInfo(
        val name: String,
        val version: Int,
        val minHostVersion: String,
        val type: String,
    )

    data class Issue(
        val stepIndex: Int?,
        val path: String,
        val message: String,
    )

    fun parseInfo(json: JSONObject, fallbackName: String): FlowInfo {
        val info = json.optJSONObject("info")
        if (info != null) {
            return FlowInfo(
                name = info.optString("name", fallbackName),
                version = info.optInt("version", 1),
                minHostVersion = info.optString("min_host_version", "0.0.0"),
                type = info.optString("type", DEFAULT_TYPE),
            )
        }
        // v1.0 兼容：顶层 version 字符串（如 "1.0.0"）→ 取整版本号
        val top = json.optString("version", "1.0.0")
        return FlowInfo(
            name = fallbackName,
            version = parseVersionInt(top),
            minHostVersion = "0.0.0",
            type = DEFAULT_TYPE,
        )
    }

    /**
     * 解析 `ui` 段（P2）。规则：
     * - 无 `ui` 或 `label` 为空 ⇒ 返回 null（不上悬浮窗）；
     * - `icon` 非法 ⇒ 回落 `gear`（同时 [validate] 报 issue）；
     * - `order` 缺省 ⇒ Int.MAX_VALUE（排最后）。
     */
    fun parseUi(json: JSONObject): OverlayUi? {
        val ui = json.optJSONObject("ui") ?: return null
        val label = ui.optString("label", "")
        if (label.isBlank()) return null
        val rawIcon = ui.optString("icon", "gear")
        val icon = if (rawIcon in ICONS) rawIcon else "gear"
        val color = ui.optString("color", "").takeIf { it.isNotBlank() }
        val hint = ui.optString("hint", "").takeIf { it.isNotBlank() }
        return OverlayUi(
            label = label,
            icon = icon,
            order = ui.optInt("order", Int.MAX_VALUE),
            color = color,
            confirm = ui.optBoolean("confirm", false),
            hint = hint,
            actions = parseActions(ui),
        )
    }

    /**
     * 解析 `actions`（P4）。规则（[validate] 会同步报错，绝不静默）：
     * - 键缺失 ⇒ 缺省 `[{run,开始}]`；
     * - 单项 `kind` 不在白名单 ⇒ **丢掉该项**（不猜、不回落成 run，免得误触发运行）；
     * - 解析后为空（空数组 / 全非法）⇒ 回落缺省 `[{run,开始}]`；
     * - 数量 > [MAX_ACTIONS] ⇒ 全量保留，由界面用「更多」子行承载。
     */
    private fun parseActions(ui: JSONObject): List<OverlayAction> {
        val arr = ui.optJSONArray("actions") ?: return defaultActions()
        val out = mutableListOf<OverlayAction>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val kind = item.optString("kind", "")
            if (kind !in ACTION_KINDS) continue
            val label = item.optString("label", "").takeIf { it.isNotBlank() }
                ?: DEFAULT_ACTION_LABEL[kind]
                ?: kind
            out.add(OverlayAction(kind, label))
        }
        return out.ifEmpty { defaultActions() }
    }

    fun validate(json: JSONObject): List<Issue> {
        val issues = mutableListOf<Issue>()
        // ⚠️ 不做提前 return：steps 与 ui 的问题要**一次列全**（管理界面要一次性看到所有问题）。
        val steps = json.optJSONArray("steps")
        if (steps == null) {
            issues.add(Issue(null, "steps", "missing 'steps' array"))
        } else {
            if (steps.length() == 0) {
                issues.add(Issue(null, "steps", "empty 'steps' array"))
            }
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i)
                if (step == null) {
                    issues.add(Issue(i, "steps[$i]", "step is not a JSON object"))
                    continue
                }
                if (!step.has("do")) {
                    issues.add(Issue(i, "steps[$i].do", "missing primitive name 'do'"))
                } else if (step.optString("do") == "notify") {
                    // P4：notify 的 level 白名单（非法 ⇒ 报错，落到展示层会是 info）
                    val level = step.optString("level", "info")
                    if (level !in NOTICE_LEVELS) {
                        issues.add(
                            Issue(
                                i,
                                "steps[$i].level",
                                "unknown notify level '$level'; allowed: " + NOTICE_LEVELS.joinToString("|"),
                            ),
                        )
                    }
                    if (step.optString("text", "").isBlank()) {
                        issues.add(Issue(i, "steps[$i].text", "notify requires non-blank 'text'"))
                    }
                }
            }
        }
        // ---- ui（P2：悬浮窗按钮描述，可选；有则逐项校验，避免静默丢弃）----
        val ui = json.optJSONObject("ui")
        if (ui != null) {
            if (ui.optString("label", "").isBlank()) {
                issues.add(Issue(null, "ui.label", "missing 'ui.label' (required when 'ui' present)"))
            }
            val icon = ui.optString("icon", "gear")
            if (icon !in ICONS) {
                issues.add(
                    Issue(
                        null,
                        "ui.icon",
                        "unknown icon '$icon'; allowed: " + ICONS.sorted().joinToString("|"),
                    ),
                )
            }
            val color = ui.optString("color", "")
            if (color.isNotBlank() && !COLOR_RE.matches(color)) {
                issues.add(Issue(null, "ui.color", "invalid color '$color'; expected #RRGGBB"))
            }
            // ---- actions（P4：行右侧动作，脚本自己声明）----
            if (ui.has("actions")) {
                val arr = ui.optJSONArray("actions")
                if (arr == null) {
                    issues.add(Issue(null, "ui.actions", "'ui.actions' must be an array"))
                } else {
                    if (arr.length() == 0) {
                        issues.add(Issue(null, "ui.actions", "empty 'ui.actions' falls back to a single run action"))
                    }
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i)
                        if (item == null) {
                            issues.add(Issue(null, "ui.actions[$i]", "action is not a JSON object"))
                            continue
                        }
                        val kind = item.optString("kind", "")
                        if (kind !in ACTION_KINDS) {
                            issues.add(
                                Issue(
                                    null,
                                    "ui.actions[$i].kind",
                                    "unknown action kind '$kind'; allowed: " + ACTION_KINDS.sorted().joinToString("|"),
                                ),
                            )
                        }
                    }
                    if (arr.length() > MAX_ACTIONS) {
                        issues.add(
                            Issue(
                                null,
                                "ui.actions",
                                "${arr.length()} actions exceed inline limit $MAX_ACTIONS; the rest go into a 'more' sub-row",
                            ),
                        )
                    }
                }
            }
        }
        return issues
    }

    /** minHost <= appVersion → true（可跑）。 */
    fun hostSatisfies(minHost: String, appVersion: String): Boolean =
        compareVersion(appVersion, minHost) >= 0

    private fun parseVersionInt(v: String): Int =
        v.split(".").firstOrNull()?.toIntOrNull() ?: 1

    /** 语义比较 a vs b（major.minor.patch，缺失补 0，非数字段忽略）。a 更新返回 >0。 */
    fun compareVersion(a: String, b: String): Int {
        val pa = parseParts(a)
        val pb = parseParts(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun parseParts(v: String): List<Int> =
        v.split(".").map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
}
