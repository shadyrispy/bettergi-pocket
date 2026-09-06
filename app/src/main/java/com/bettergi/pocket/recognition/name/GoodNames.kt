package com.bettergi.pocket.recognition.name

import android.content.res.AssetManager
import org.json.JSONObject

/**
 * 单一名称词典（dsl/tools/good_names.json）：角色 / 武器 / 套装 / **单件** / 词条 / 部位。
 *
 * 由 `dsl/scripts/gen_good_names.py` 生成：
 * - characters / weapons / artifactSets 来自 GOOD 官方 mappings
 * - artifactPieces（276 件）来自我们梳理的 `dsl/tools/artifactSetPieces.json`
 *   —— GOOD 未覆盖这一段，而背包详情面板 set_name 被遮挡，须由单件名反推套装
 * - stats / slots 内置（原 StatParser 表 + irminsul NameMapper 别名）
 *
 * 匹配统一走 [NameMatcher]，本类只负责装载与按类型取表。
 */
class GoodNames private constructor(
    /** 圣遗物单件名 → 所属套装 GOOD id（276） */
    val pieces: Map<String, String>,
    /** 武器名 → GOOD id（236） */
    val weapons: Map<String, String>,
    /** 角色名 → GOOD id（121） */
    val characters: Map<String, String>,
    /** 套装名 → GOOD id（56） */
    val sets: Map<String, String>,
    /** 词条名 → GOOD key（含 flat/percent 双键） */
    val stats: List<StatEntry>,
    /** 部位名 → GOOD slotKey（5） */
    val slots: Map<String, String>,
    /** 单件名 → 详情（诊断/校验用） */
    val pieceDetails: Map<String, PieceInfo>,
) {

    /** 名称类别（flow 的 `dict` 字段映射）。 */
    enum class Kind { PIECE, WEAPON, CHARACTER, SET }

    data class StatEntry(val zh: String, val key: String, val percentKey: String?)

    data class PieceInfo(val setId: String, val setName: String?, val slot: String?)

    /** 按类别取表。 */
    fun table(kind: Kind): Map<String, String> = when (kind) {
        Kind.PIECE -> pieces
        Kind.WEAPON -> weapons
        Kind.CHARACTER -> characters
        Kind.SET -> sets
    }

    /** 按类别匹配（统一算法）。 */
    fun match(text: String, kind: Kind, allowFuzzy: Boolean = true): NameMatcher.MatchResult? =
        NameMatcher.match(text, table(kind), allowFuzzy)

    companion object {
        const val ASSET_PATH = "dsl/tools/good_names.json"

        /** flow 的 dict 名 → 类别；未知返回 null。 */
        fun kindOf(dictKey: String): Kind? = when (dictKey) {
            "mappings.artifactPieces", "mappings.artifactSets.pieces" -> Kind.PIECE
            "mappings.weapons" -> Kind.WEAPON
            "mappings.characters" -> Kind.CHARACTER
            "mappings.artifactSets" -> Kind.SET
            else -> null
        }

        fun fromJson(json: JSONObject): GoodNames {
            val pieces = LinkedHashMap<String, String>()
            val details = LinkedHashMap<String, PieceInfo>()
            val arr = json.getJSONArray("artifactPieces")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val piece = o.getString("piece")
                val setId = o.getString("setId")
                pieces[piece] = setId
                details[piece] = PieceInfo(
                    setId = setId,
                    setName = o.optString("set").takeIf { it.isNotEmpty() },
                    slot = o.optString("slot").takeIf { it.isNotEmpty() },
                )
            }

            val weapons = nameMap(json.getJSONArray("weapons"))
            val characters = nameMap(json.getJSONArray("characters"))
            val sets = nameMap(json.getJSONArray("artifactSets"))

            val stats = ArrayList<StatEntry>()
            val statsArr = json.getJSONArray("stats")
            for (i in 0 until statsArr.length()) {
                val o = statsArr.getJSONObject(i)
                stats += StatEntry(
                    zh = o.getString("zh"),
                    key = o.getString("key"),
                    percentKey = o.optString("percentKey").takeIf { it.isNotEmpty() },
                )
            }

            val slots = LinkedHashMap<String, String>()
            val slotsArr = json.getJSONArray("slots")
            for (i in 0 until slotsArr.length()) {
                val o = slotsArr.getJSONObject(i)
                slots[o.getString("zh")] = o.getString("key")
            }

            return GoodNames(pieces, weapons, characters, sets, stats, slots, details)
        }

        fun load(assets: AssetManager): GoodNames {
            val text = assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
            return fromJson(JSONObject(text))
        }

        private fun nameMap(arr: org.json.JSONArray): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out[o.getString("zh")] = o.getString("id")
            }
            return out
        }
    }
}
