package com.bettergi.pocket.dsl

import org.json.JSONObject

/**
 * §16.3 S2 包格式与门禁：flow JSON v1.1 校验（纯函数、无 Android 依赖 → JVM 单测可直接覆盖）。
 *
 * - [parseInfo]：v1.1 读 `info{name,version,min_host_version,type:"pocket-script"}`；
 *   v1.0 兼容（无 info，取顶层 `version` 字符串，min_host_version 默认 0.0.0）。
 * - [validate]：校验 `steps` 数组 + 每 step 含 `do` 原语名；报步骤序号与字段路径。
 * - [hostSatisfies]：min_host_version 语义比较当前宿主版本（BuildConfig.VERSION_NAME）。
 */
object FlowValidator {
    const val DEFAULT_TYPE = "pocket-script"

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

    fun validate(json: JSONObject): List<Issue> {
        val issues = mutableListOf<Issue>()
        val steps = json.optJSONArray("steps")
        if (steps == null) {
            issues.add(Issue(null, "steps", "missing 'steps' array"))
            return issues
        }
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
