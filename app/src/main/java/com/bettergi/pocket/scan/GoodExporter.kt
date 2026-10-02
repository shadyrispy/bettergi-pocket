package com.bettergi.pocket.scan

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * GOOD v3 导出（filesDir/good_export_<ts>.json）。
 * 与 irminsul 结果对齐为 P1 验收口径（含 setKey 经 good_names.artifactPieces 反推）。
 */
object GoodExporter {
    /** GOOD v3 JSON 构建（纯函数，可离线测试）。artifacts + weapons + characters 分别导出。 */
    fun buildGoodJson(
        artifacts: List<GoodArtifact>,
        weapons: List<GoodWeapon> = emptyList(),
        characters: List<GoodCharacter> = emptyList(),
    ): JSONObject {
        val root = JSONObject()
        root.put("format", "GOOD")
        root.put("version", 3)
        root.put("source", "BetterGIPocket")
        val arr = org.json.JSONArray()
        for (a in artifacts) {
            arr.put(JSONObject().apply {
                put("setKey", a.setKey ?: "")
                put("slotKey", a.slotKey ?: "")
                put("level", a.level)
                put("rarity", a.rarity)
                put("mainStatKey", a.mainStatKey ?: "")
                put("mainStatValue", a.mainStatValue)
                put("location", a.location)
                put("lock", a.lock)
                val subs = org.json.JSONArray()
                for (s in a.substats) {
                    subs.put(JSONObject().apply {
                        put("key", s.key)
                        put("value", s.value)
                        // ★ 2026-09-16：GOOD v3 的 roll 字段（Irminsul 同格式）；解不出就不写
                        s.initialValue?.let { put("initialValue", it) }
                        if (s.rollCount > 0) put("rollCount", s.rollCount)
                    })
                }
                put("substats", subs)
                a.totalRolls?.let { put("totalRolls", it) }
                // ★ 2026-09-16：待激活词条单列（GT 894/894 件都有该字段，无则空数组）
                val unas = org.json.JSONArray()
                for (s in a.unactivatedSubstats) {
                    unas.put(JSONObject().apply {
                        put("key", s.key)
                        put("value", s.value)
                        s.initialValue?.let { put("initialValue", it) }
                        if (s.rollCount > 0) put("rollCount", s.rollCount)
                    })
                }
                put("unactivatedSubstats", unas)
                // ★ 2026-09-16：祝圣之霜标记（GT 20/941 为 true）；来源 = 紫横幅检测
                if (a.elixerCrafted) put("elixerCrafted", true)
            })
        }
        root.put("artifacts", arr)
        val warr = org.json.JSONArray()
        for (w in weapons) {
            warr.put(JSONObject().apply {
                // ★ A39（#156）：未解析身份**不写 key 字段**（null 缺省），绝不写空串 ——
                //   key="" 的多条记录会被下游（irminsul/GT 对账按 key 归并）并成一条，
                //   静默丢件。GOOD v3 消费端把「缺 key」当未知处理，不破坏契约。
                //   （武器侧 key=null 理论上到不了导出：parseWeaponPanel 对 key==null 早退；
                //     此处双保险同口径。）
                if (w.key != null) put("key", w.key)
                put("level", w.level)
                put("ascension", w.ascension)
                put("rarity", w.rarity)
                w.refine?.let { put("refinement", it) }
                put("location", w.location)
                put("lock", w.lock)
            })
        }
        root.put("weapons", warr)
        val carr = org.json.JSONArray()
        for (c in characters) {
            carr.put(JSONObject().apply {
                // ★ A39（#156）：同上 —— key 未知时不写字段；rawName 已在 `name`（parseCharacterPanel
                //   对三档解析全未命中的角色按原文入库），人工可核对、去管理器页补昵称（#105）。
                if (c.key != null) put("key", c.key)
                put("name", c.name)
                put("level", c.level)
                c.element?.let { put("element", it) }
                put("ascension", c.ascension)
                put("constellation", c.constellation)
                put("favor", c.favor)
                // GOOD 口径：talent = { auto, skill, burst }
                val t = JSONObject()
                val names = arrayOf("auto", "skill", "burst")
                for (i in 0 until 3) {
                    t.put(names[i], c.talents.getOrElse(i) { 0 })
                }
                put("talent", t)
                val locked = org.json.JSONArray()
                c.talentLocked.forEach { locked.put(it) }
                put("talentLocked", locked)
            })
        }
        root.put("characters", carr)
        return root
    }

    fun export(
        context: Context,
        artifacts: List<GoodArtifact>,
        weapons: List<GoodWeapon> = emptyList(),
        characters: List<GoodCharacter> = emptyList(),
    ): String {
        val fileName = "good_export_${System.currentTimeMillis()}.json"
        context.openFileOutput(fileName, Context.MODE_PRIVATE).use { out ->
            out.write(buildGoodJson(artifacts, weapons, characters).toString(2).toByteArray(Charsets.UTF_8))
        }
        Log.i(TAG, "GOOD export: ${artifacts.size}a ${weapons.size}w ${characters.size}c -> $fileName")
        return fileName
    }

    private const val TAG = "BetterGI.Scan"
}
