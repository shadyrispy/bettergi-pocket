package com.bettergi.pocket.scan

import org.json.JSONArray
import org.json.JSONObject

/**
 * §14 P1/P4 **plan 契约**的纯逻辑部分：无引擎状态、无 IO、可离线单测。
 *
 * 契约（flow `vars.plan: list<{char, slot, target:GoodArtifact}>`）：
 * - **筛选目标** [targets]：`currentTask.setName` → `currentTask.targets[]`（字符串或 `{setName}`）
 *   → `plan[].setName`，按优先级取并集。供 `setFilter` 勾选套装 checkbox。
 * - **命中判据** [hardMatch]：本格解析产物 vs 计划项。**任务侧缺字段即不参与比对**
 *   （宽松匹配，避免未标定字段误杀）——⚠️ 因此**空任务恒 true**，裸 plan（只给 `char`）
 *   会让 `stopWhen panelMatch` 在第一格立刻触发。契约完整性由规则层保证。
 *
 * 字段名与 GOOD 对齐：`setKey`/`slotKey`/`mainStatKey`/`substats[].key`（`slot`/`setName`/`level` 为别名）。
 */
object TaskMatch {

    /**
     * 命中判据。比对顺序同 flow 声明 `matchOrder: level→main→sub1-4→set_name`；
     * 副词条按相对容差 [tol]（artifact_lock 定稿 0.100001，auto_equip 0.1）。
     * @param why 非空时写入首个不匹配原因（可观测性：区分"判据不生效"与"确实不匹配"）。
     */
    fun hardMatch(task: JSONObject, a: GoodArtifact, tol: Double, why: StringBuilder? = null): Boolean {
        why?.append("got(level=${a.level},slot=${a.slotKey},set=${a.setKey},main=${a.mainStatKey})")
        val wantLevel = task.optInt("level", -1)
        if (wantLevel >= 0 && a.level != wantLevel) {
            why?.append(" ∵ level ${a.level} ≠ $wantLevel"); return false
        }
        // ★ 2026-09-18（P1⑥）：补 rarity 硬匹配（GOODScanner `matching::match_score` 里 rarity 是硬字段）。
        //   旧实现不比对 rarity ⇒ 4★ 与 5★ 同套同部位同副词条的件会被当成同一件（过匹配）。
        val wantRarity = task.optInt("rarity", -1)
        if (wantRarity >= 0 && a.rarity != wantRarity) {
            why?.append(" ∵ rarity ${a.rarity} ≠ $wantRarity"); return false
        }
        val wantSlot = task.optString("slotKey", task.optString("slot", ""))
        if (wantSlot.isNotEmpty() && a.slotKey != wantSlot) {
            why?.append(" ∵ slot ${a.slotKey} ≠ $wantSlot"); return false
        }
        val wantSet = task.optString("setKey", task.optString("setName", ""))
        if (wantSet.isNotEmpty() && a.setKey != wantSet) {
            why?.append(" ∵ set ${a.setKey} ≠ $wantSet"); return false
        }
        val wantMain = task.optString("mainStatKey", "")
        if (wantMain.isNotEmpty() && a.mainStatKey != wantMain) {
            why?.append(" ∵ main ${a.mainStatKey} ≠ $wantMain"); return false
        }
        // ★ 2026-09-18（P1⑥）：副词条改**严格**匹配 —— 对齐 GOODScanner `matching::substats_match`：
        //   数量相等 + 目标每个 key 都能在实际里找到 + 值差 ≤ tol*|目标值|。
        //   旧实现是"只比列出的那几个、不校验长度"的**宽松子集**比对 ⇒ 过匹配
        //   （一件只有 2 条副词条的件也能满足「要 4 条」的目标）⇒ 可能锁到不该锁的件。
        val wantSubs = task.optJSONArray("substats")
        if (wantSubs != null && !substatsMatchStrict(a.substats, wantSubs, tol, why)) return false
        // 未激活副词条（0 级件特有）：任务侧**显式给出**时才比对（扫描导出的 0 级件会写它）
        val wantUnact = task.optJSONArray("unactivatedSubstats")
        if (wantUnact != null && !substatsMatchStrict(a.unactivatedSubstats, wantUnact, tol, why, "未激活副")) return false
        // 祝圣之霜打造：GOODScanner 把 elixirCrafted 列为硬匹配字段
        if (task.has("elixirCrafted") && task.optBoolean("elixirCrafted") != a.elixerCrafted) {
            why?.append(" ∵ 祝圣 ${a.elixerCrafted} ≠ ${task.optBoolean("elixirCrafted")}"); return false
        }
        why?.append(" → 全字段命中")
        return true
    }

    /**
     * 副词条**严格**比对（对齐 GOODScanner `matching::substats_match`）。
     *
     * ⚠️ "数量相等"这条不能省：GOODScanner 原文是 "*All* substat keys must match exactly"，
     * 少了它就退化成子集匹配 —— 目标要 4 条、实际只有 2 条也会命中。
     */
    private fun substatsMatchStrict(
        actual: List<GoodSubStat>,
        want: JSONArray,
        tol: Double,
        why: StringBuilder?,
        label: String = "副词条",
    ): Boolean {
        if (actual.size != want.length()) {
            why?.append(" ∵ $label 数量 ${actual.size} ≠ ${want.length()}（严格匹配要求全列出）")
            return false
        }
        for (i in 0 until want.length()) {
            val w = want.optJSONObject(i) ?: run { why?.append(" ∵ $label[$i] 不是对象"); return false }
            val key = w.optString("key", "")
            if (key.isEmpty()) { why?.append(" ∵ $label[$i] 缺 key"); return false }
            val got = actual.firstOrNull { it.key == key }
                ?: run { why?.append(" ∵ 缺$label $key"); return false }
            val value = w.optDouble("value", Double.NaN)
            if (!value.isNaN() && Math.abs(got.value - value) > tol * Math.abs(value)) {
                why?.append(" ∵ $label $key ${got.value} ≠ $value (tol=$tol)"); return false
            }
        }
        return true
    }

    /**
     * 圣遗物**身份键**（"是不是同一件"的判据，字段集与 [hardMatch] 同构）：
     * `setKey|slotKey|rarity|level|mainStatKey|副词条(键+值)|elixirCrafted`。
     *
     * 用途：`GoodPlan` 检查「同一件既在 lock 又在 unlock」这类自相矛盾输入。
     * 值按 **0.1 取整**后再比 —— 与匹配容差 `0.100001` 同量级，避免 OCR 舍入把同一件拆成两条。
     */
    fun identityKeyOf(o: JSONObject): String {
        val subs = o.optJSONArray("substats")
        val subKey = if (subs == null) {
            ""
        } else {
            (0 until subs.length())
                .mapNotNull { subs.optJSONObject(it) }
                .map { "${it.optString("key")}=${Math.round(it.optDouble("value", 0.0) * 10.0) / 10.0}" }
                .sorted()
                .joinToString(",")
        }
        return listOf(
            o.optString("setKey"),
            o.optString("slotKey", o.optString("slot")),
            o.optInt("rarity", -1).toString(),
            o.optInt("level", -1).toString(),
            o.optString("mainStatKey"),
            subKey,
            o.optBoolean("elixirCrafted", false).toString(),
        ).joinToString("|")
    }

    /**
     * 筛选目标集合：`currentTask.setName` → `currentTask.setKey` → `currentTask.targets[]` → `plan[].setName/setKey`。
     * 返回的是**原始名**（中文显示名或 GOOD id 皆可），调用方须经词典归一后再与 OCR 侧比对。
     *
     * ⚠️ **必须同时读 `setKey`**：`setFilter` 比对的是词典 key（`tools/mappings.json` 的 `artifactSets[].id`，
     * 如 `GladiatorsFinale`），而 GOOD 导出的圣遗物项只有 `setKey`（`{"setKey":"GladiatorsFinale",…}`）、
     * **没有** `setName`。2026-09-18 真机实测：只读 `setName` 时，从管理器导入的 GOOD 文件
     * （`artifact_lock` 的输入形态）恒得到空目标集 ⇒ `setFilter: 无筛选目标` ⇒ 流程空跑。
     * `setKey` 本身已在归一后的空间里（`lookup` 失配会回落原文，正好是词典 key）。
     */
    fun targets(task: JSONObject?, plan: List<JSONObject>?): Set<String> {
        val out = LinkedHashSet<String>()
        if (task != null) {
            task.optString("setName").takeIf { it.isNotEmpty() }?.let(out::add)
            task.optString("setKey").takeIf { it.isNotEmpty() }?.let(out::add)
            val arr = task.optJSONArray("targets")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    // ⚠️ 必须按类型分派：org.json 的 `optString(i,"")` 对 JSONObject 元素会返回
                    // **对象的 JSON 文本**（`{"setName":"…"}`）而非空串 → 原 `if (s.isNotEmpty()) add(s)`
                    // 会把整段 JSON 塞进目标集，词典归一必然失败 → 该目标静默永不匹配。
                    // （2026-09-10 由 PlanContractTest 抓出。）
                    when (val el = arr.opt(i)) {
                        is String -> if (el.isNotEmpty()) out.add(el)
                        is JSONObject -> {
                            el.optString("setName").takeIf { it.isNotEmpty() }?.let(out::add)
                            el.optString("setKey").takeIf { it.isNotEmpty() }?.let(out::add)
                        }
                    }
                }
            }
        }
        plan?.forEach { t ->
            t.optString("setName").takeIf { it.isNotEmpty() }?.let(out::add)
            t.optString("setKey").takeIf { it.isNotEmpty() }?.let(out::add)
        }
        return out
    }
}
