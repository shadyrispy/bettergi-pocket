package com.bettergi.pocket.scan

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
        val wantSubs = task.optJSONArray("substats") ?: return true
        for (i in 0 until wantSubs.length()) {
            val w = wantSubs.optJSONObject(i) ?: continue
            val key = w.optString("key", "")
            if (key.isEmpty()) continue
            val got = a.substats.firstOrNull { it.key == key }
                ?: run { why?.append(" ∵ 缺副词条 $key"); return false }
            val value = w.optDouble("value", Double.NaN)
            if (!value.isNaN() && Math.abs(got.value - value) > tol * Math.abs(value)) {
                why?.append(" ∵ 副词条 $key ${got.value} ≠ $value (tol=$tol)"); return false
            }
        }
        why?.append(" → 全字段命中")
        return true
    }

    /**
     * 筛选目标集合：`currentTask.setName` → `currentTask.targets[]` → `plan[].setName`。
     * 返回的是**原始名**（中文显示名或 GOOD id 皆可），调用方须经词典归一后再与 OCR 侧比对。
     */
    fun targets(task: JSONObject?, plan: List<JSONObject>?): Set<String> {
        val out = LinkedHashSet<String>()
        if (task != null) {
            task.optString("setName").takeIf { it.isNotEmpty() }?.let(out::add)
            val arr = task.optJSONArray("targets")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    // ⚠️ 必须按类型分派：org.json 的 `optString(i,"")` 对 JSONObject 元素会返回
                    // **对象的 JSON 文本**（`{"setName":"…"}`）而非空串 → 原 `if (s.isNotEmpty()) add(s)`
                    // 会把整段 JSON 塞进目标集，词典归一必然失败 → 该目标静默永不匹配。
                    // （2026-09-10 由 PlanContractTest 抓出。）
                    when (val el = arr.opt(i)) {
                        is String -> if (el.isNotEmpty()) out.add(el)
                        is JSONObject -> el.optString("setName")
                            .takeIf { it.isNotEmpty() }?.let(out::add)
                    }
                }
            }
        }
        plan?.forEach { t -> t.optString("setName").takeIf { it.isNotEmpty() }?.let(out::add) }
        return out
    }
}
