package com.bettergi.pocket.scan

import org.json.JSONObject

/**
 * 「脚本要什么输入」的**纯逻辑**部分：无 IO、可离线单测。
 *
 * ## 输入契约已对齐 GOODScanner（2026-09-18 二次修订）
 *
 * 权威出处：`GOODScanner/docs/MANAGER_API.md`（`POST /manage`、`POST /equip`）+
 * `genshin/src/manager/models.rs`（`LockManageRequest` / `EquipInstruction`）。
 *
 * - **锁定**：`{ "lock": [GoodArtifact…], "unlock": [GoodArtifact…] }`
 *   —— **列表归属决定动作**，对象自身的 `lock` 字段**刻意忽略**
 *   （原文："The artifact's own `lock` field is ignored for determining intention —
 *   only list membership matters. This allows stale data to still express the correct intention."）。
 *   两键各自可选（`#[serde(default)]`），但**都空要拒绝**。
 * - **装配**：`{ "equip": [ { "artifact": GoodArtifact, "location": "角色key" } ] }`，
 *   `location: ""` 表示**卸下**（去 `artifact.location` 记录的当前持有者那里卸）。
 *
 * ⚠️ 旧的自造形态 `list<{char, slot, target}>`（出处 `dsl/docs/flow5-auto-equip.md`，2026-09-10，
 * 本项目早期自定、非 GOOD 契约）**已废弃**，不再兼容读。
 *
 * ## 归一：两种契约都落成**引擎既有的扁平计划项**
 *
 * 这样 DSL 与引擎都不必懂"两种文件形态"：
 * - 锁定项 = 圣遗物字段 + `wantLock: true|false`（`foreach` 里由 `ifMatch when "curLock != wantLock"` 消费）
 * - 装配项 = 圣遗物字段 + `char`（目标角色；空串 = 卸下）
 *
 * 「圣遗物字段」= `parsePanel` 解析产物同构的那组 GOOD 字段（`setKey`/`slotKey`/`rarity`/`level`/
 * `mainStatKey`/`substats`/…），所以 [TaskMatch.hardMatch] 能直接拿扁平项当目标比对。
 */
object GoodPlan {

    /** 锁定意图（**目标态**，不是"当前态"）：`true` = 应锁定，`false` = 应解锁。 */
    const val KEY_WANT_LOCK = "wantLock"

    /** 装配目标角色（沿用引擎既有的 `char` —— `rosterFind` 读 `$task.char`）。空串 = 卸下。 */
    const val KEY_CHAR = "char"

    /** 一条脚本对输入的诉求。 */
    data class Demand(
        /** 文件是 `{lock, unlock}` 两个意图清单（锁定类）。 */
        val wantsLockLists: Boolean = false,
        /** 文件是 `{equip:[{artifact, location}]}`（装配类）。 */
        val wantsEquip: Boolean = false,
        /** 直接吃圣遗物数组（`{"artifacts":[…]}`，扫描导出即可喂）。 */
        val wantsArtifacts: Boolean = false,
    ) {
        val needsInput: Boolean get() = wantsLockLists || wantsEquip || wantsArtifacts
    }

    /** 从流程 JSON 的 `vars` 反推输入诉求。 */
    fun demandOf(flowJson: JSONObject?): Demand {
        val vars = flowJson?.optJSONObject("vars") ?: return Demand()
        var lockLists = false
        var equip = false
        var artifacts = false
        val keys = vars.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val type = vars.optString(k, "")
            when {
                k == "lock" || k == "unlock" -> lockLists = true
                k == "equip" -> equip = true
                // ⚠️ 记录类型（含 `{`）里的 GoodArtifact 是**项内字段**，不代表"整体吃圣遗物数组"。
                //    2026-09-18 真机实测：这条漏判让 `auto_equip` 把纯 GOOD 导出当配装计划接受
                //    ⇒ 跑完一圈界面 `rosterFind 解析失败` + `panelMatch 未命中` + `cells=0`。
                k == "targets" || (type.contains("GoodArtifact") && !type.contains("{")) -> artifacts = true
            }
        }
        return Demand(wantsLockLists = lockLists, wantsEquip = equip, wantsArtifacts = artifacts)
    }

    /**
     * 从导入的文件里挑出能喂给该脚本的列表（已归一为扁平计划项）。
     *
     * @return null = 这份文件喂不了这条脚本（调用方应提示用户，而不是静默空跑）
     * @see whyNot 取「为什么喂不了」的用户可读原因
     */
    fun pick(fileJson: JSONObject?, demand: Demand): List<JSONObject>? {
        if (whyNot(fileJson, demand) != null) return null
        val f = fileJson!!
        if (demand.wantsLockLists) {
            // 绑定顺序对齐 GOODScanner："lock entries first, then unlock"
            return f.optJSONArray("lock").toList().map { withField(it, KEY_WANT_LOCK, true) } +
                f.optJSONArray("unlock").toList().map { withField(it, KEY_WANT_LOCK, false) }
        }
        if (demand.wantsEquip) {
            return f.optJSONArray("equip").toList().mapNotNull { ins ->
                val art = ins.optJSONObject("artifact") ?: return@mapNotNull null
                copyOf(art).apply { put(KEY_CHAR, ins.optString("location", "")) }
            }
        }
        val artifacts = f.optJSONArray("artifacts").toList()
        if (artifacts.isNotEmpty()) return artifacts
        // 锁定清单的项与 targets 项同构，也能喂
        val lock = f.optJSONArray("lock").toList()
        if (lock.isNotEmpty()) return lock
        return f.optJSONArray("plan").toList().ifEmpty { null }
    }

    /**
     * 这份文件**为什么**喂不了该脚本（null = 能喂）。
     *
     * 与 [pick] 共用同一组判据（单一事实源）：`pick` = `whyNot == null` 时取值。
     * 文案面向用户，直接进提醒（见 `TriggerForegroundService.startFlowIfReady`）。
     * ⚠️ 文案里**不要用 Markdown 粗体**：悬浮窗提醒条与识别日志都不解析。
     */
    fun whyNot(fileJson: JSONObject?, demand: Demand): String? {
        if (fileJson == null) return "还没有选输入文件"
        // 载荷合法性（对齐 GOODScanner 的 400 语义）：坏条目在这里就拦下，
        // 否则会一路跑到真机上，表现为"跑完了却什么都没做"
        validateArtifacts(fileJson)?.let { return "输入条目不合法：" + it }
        val lock = fileJson.optJSONArray("lock").toList()
        val unlock = fileJson.optJSONArray("unlock").toList()
        val equip = fileJson.optJSONArray("equip").toList()
        val artifacts = fileJson.optJSONArray("artifacts").toList()

        if (demand.wantsLockLists) {
            if (lock.isEmpty() && unlock.isEmpty()) {
                return "这份文件里没有 lock / unlock 清单" + guessShape(artifacts, equip)
            }
            // 同一件同时出现在两个清单里 ⇒ 意图自相矛盾，拒绝（否则先锁后解，结果不可预期）
            val both = lock.map { TaskMatch.identityKeyOf(it) }.toSet()
                .intersect(unlock.map { TaskMatch.identityKeyOf(it) }.toSet())
            if (both.isNotEmpty()) {
                return "同一件同时出现在 lock 与 unlock 里（${both.size} 件），意图矛盾 ⇒ 请先修正"
            }
            return null
        }
        if (demand.wantsEquip) {
            if (equip.isEmpty()) {
                return "这份文件里没有 equip 数组" + guessShape(artifacts, lock)
            }
            val bad = equip.indexOfFirst { it.optJSONObject("artifact") == null }
            if (bad >= 0) return "equip[" + bad + "] 里缺 artifact 对象"
            // ⚠️ location 必须**显式**给出：缺省会被当成空串 = 卸下 ⇒ 语义静默反转（危险）
            val noLoc = equip.indexOfFirst { !it.has("location") }
            if (noLoc >= 0) return "equip[" + noLoc + "] 缺 location（要卸下请显式写空串）"
            return null
        }
        if (demand.wantsArtifacts) {
            if (artifacts.isNotEmpty() || lock.isNotEmpty()) return null
            return "文件里没有 artifacts / lock 数组"
        }
        return null
    }

    /**
     * **载荷合法性**校验（空键 / rarity∉{3,4,5} / level∉[0,20]）。
     *
     * ⚠️ 上游 GOODScanner 的 400 语义只收 4★/5★，这里**刻意放宽到含 3★**（2026-09-22 定）：
     *   bp 的两条数据源都已含 3★（OCR 扫描 `df4b108`、抓包导出），导入侧再卡 4★
     *   就等于把自家产物判成不合法。计划要不要真用 3★ 归规则层决定，不归这里管。
     *
     * 与"能不能喂给这条脚本"（[whyNot]）是两件事：这个函数只问"条目本身合不合法"，
     * 所以**在导入时也能独立调用**（那时还不知道要给哪条脚本用）。
     */
    fun validateArtifacts(fileJson: JSONObject?): String? {
        if (fileJson == null) return "不是合法 JSON"
        val groups: List<Pair<String, List<JSONObject>>> = listOf(
            "artifacts" to fileJson.optJSONArray("artifacts").toList(),
            "lock" to fileJson.optJSONArray("lock").toList(),
            "unlock" to fileJson.optJSONArray("unlock").toList(),
            "plan" to fileJson.optJSONArray("plan").toList(),
            "equip" to fileJson.optJSONArray("equip").toList().mapNotNull { it.optJSONObject("artifact") },
        )
        for ((label, list) in groups) {
            list.forEachIndexed { i, a ->
                artifactProblem(a)?.let { return label + "[" + i + "]：" + it }
            }
        }
        return null
    }

    /** 单条圣遗物的合法性问题（null = 合法）。字段缺省视为"未提供"，不强制（手写最小计划可用）。 */
    private fun artifactProblem(a: JSONObject): String? {
        if (a.optString("setKey").isBlank()) return "缺 setKey"
        if (a.optString("slotKey", a.optString("slot")).isBlank()) return "缺 slotKey"
        if (a.optString("mainStatKey").isBlank()) return "缺 mainStatKey"
        val rarity = a.optInt("rarity", -1)
        if (rarity != -1 && rarity !in 3..5) return "rarity=" + rarity + "（只支持 3★~5★）"
        val level = a.optInt("level", -1)
        if (level != -1 && level !in 0..20) return "level=" + level + "（应在 0..20）"
        val subs = a.optJSONArray("substats")
        if (subs != null) {
            for (i in 0 until subs.length()) {
                if (subs.optJSONObject(i)?.optString("key").isNullOrBlank()) {
                    return "substats[" + i + "] 缺 key"
                }
            }
        }
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

    /** 计划项是否都带 `char`（装配必需；圣遗物导出没有这一项）。 */
    fun planHasChar(plan: List<JSONObject>?): Boolean {
        if (plan.isNullOrEmpty()) return false
        return plan.all { it.optString(KEY_CHAR, "").isNotBlank() }
    }

    // ---- 内部 ----

    /** 提示"你选的其实是哪种形态"，帮用户一次改对。 */
    private fun guessShape(artifacts: List<JSONObject>, other: List<JSONObject>): String = when {
        artifacts.isNotEmpty() -> "（这份是圣遗物扫描导出：只有 artifacts）"
        other.isNotEmpty() -> "（这份是另一类脚本用的形态）"
        else -> ""
    }

    /** 浅拷贝（保持字段顺序）。 */
    private fun copyOf(src: JSONObject): JSONObject {
        val out = JSONObject()
        val keys = src.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            runCatching { out.put(k, src.get(k)) }
        }
        return out
    }

    private fun withField(src: JSONObject, key: String, value: Any): JSONObject =
        copyOf(src).apply { put(key, value) }

    private fun org.json.JSONArray?.toList(): List<JSONObject> {
        if (this == null) return emptyList()
        val out = ArrayList<JSONObject>(length())
        for (i in 0 until length()) optJSONObject(i)?.let { out.add(it) }
        return out
    }
}
