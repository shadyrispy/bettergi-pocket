package com.bettergi.pocket.scan

import org.json.JSONObject

/**
 * "脚本要什么输入" 的**纯逻辑**部分（2026-09-18）：无 IO、可离线单测。
 *
 * 为什么不让界面写死"哪条脚本要导入"：
 * 脚本自己已经在 `vars` 里声明了它要什么 ——
 * - `artifact_lock` 声明 `vars.targets: list<GoodArtifact>` ⇒ 要**圣遗物数组**；
 * - `auto_equip`   声明 `vars.plan: list<{char, slot, target}>` ⇒ 要**配装计划**（且必须带 `char`）。
 * 所以这里从 `vars` 反推需求，界面照结果渲染即可；以后新增脚本不用改界面代码。
 */
object GoodPlan {

    /** 一条脚本对输入的诉求。 */
    data class Demand(val wantsPlan: Boolean, val wantsArtifacts: Boolean) {
        val needsInput: Boolean get() = wantsPlan || wantsArtifacts
    }

    /** 从流程 JSON 的 `vars` 反推输入诉求。 */
    fun demandOf(flowJson: JSONObject?): Demand {
        val vars = flowJson?.optJSONObject("vars") ?: return Demand(false, false)
        val keys = vars.keys()
        var wantsPlan = false
        var wantsArtifacts = false
        while (keys.hasNext()) {
            val k = keys.next()
            if (k == "plan") wantsPlan = true
            // 类型串里出现 GoodArtifact（如 `list<GoodArtifact>`）或显式 `targets` ⇒ 吃圣遗物数组
            val type = vars.optString(k, "")
            if (k == "targets" || type.contains("GoodArtifact")) wantsArtifacts = true
        }
        return Demand(wantsPlan, wantsArtifacts)
    }

    /**
     * 从导入的文件里挑出能喂给该脚本的列表。
     *
     * 支持两种文件形态：
     * - **GOOD 导出**：`{"format":"GOOD","artifacts":[…]}` —— 字段与 plan 项同构，可直接喂 `targets`；
     * - **配装计划**：`{"plan":[{"char":…,"slot":…,"target":{…}}]}` —— 喂 `plan`（`char` 由计划给出）。
     *
     * @return null = 这份文件喂不了这条脚本（调用方应提示用户，而不是静默空跑）
     */
    fun pick(fileJson: JSONObject?, demand: Demand): List<JSONObject>? {
        if (fileJson == null) return null
        val plan = fileJson.optJSONArray("plan").toList()
        val artifacts = fileJson.optJSONArray("artifacts").toList()
        if (demand.wantsPlan && plan.isNotEmpty()) return plan
        if (demand.wantsArtifacts && artifacts.isNotEmpty()) return artifacts
        // 计划文件也能喂 targets（计划项本身带 setKey/slotKey 等字段）
        if (demand.wantsArtifacts && plan.isNotEmpty()) return plan
        return null
    }

    /** 脚本是否声明了 `import` 动作 —— **这才是"需要用户提供输入"的闸**。 */
    fun declaresImport(flowJson: JSONObject?): Boolean {
        val arr = flowJson?.optJSONObject("ui")?.optJSONArray("actions") ?: return false
        for (i in 0 until arr.length()) {
            if (arr.optJSONObject(i)?.optString("kind") == "import") return true
        }
        return false
    }

    /**
     * 这条脚本是否需要用户提供输入。
     *
     * ⚠️ 必须**两个条件同时成立**：脚本声明了 `import` 动作 **且** 它的 `vars` 里确实要数据。
     * 只看 `vars` 会误判 —— 扫描脚本的 `vars.results` 也是 `list<GoodArtifact>`（那是**产出**），
     * 只按 vars 判会让"圣遗物扫描"也要求先选文件。
     */
    fun needsInput(flowJson: JSONObject?): Boolean =
        declaresImport(flowJson) && demandOf(flowJson).needsInput

    /** 计划项是否都带 `char`（装配必需；GOOD 导出没有这一项）。 */
    fun planHasChar(plan: List<JSONObject>?): Boolean {
        if (plan.isNullOrEmpty()) return false
        return plan.all { it.optString("char", "").isNotBlank() }
    }

    private fun org.json.JSONArray?.toList(): List<JSONObject> {
        if (this == null) return emptyList()
        val out = ArrayList<JSONObject>(length())
        for (i in 0 until length()) optJSONObject(i)?.let { out.add(it) }
        return out
    }
}
