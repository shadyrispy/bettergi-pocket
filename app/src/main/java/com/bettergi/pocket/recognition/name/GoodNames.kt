package com.bettergi.pocket.recognition.name

import android.content.res.AssetManager
import com.bettergi.pocket.core.FlowSource
import org.json.JSONObject

/**
 * 单一名称词典（**`dsl/tools/mappings.json`**）：角色 / 武器 / 套装 / **单件** / 词条 / 部位 / 元素。
 *
 * 由 `dsl/scripts/gen_mappings.py` 一步生成并同步进 app assets（全程无手写源）：
 * - characters / weapons / artifactSets 来自 GenshinTools 游戏数据（AnimeGameData 派生）
 * - artifactPieces（305 件）：中文名来自游戏 Reliquary 表（Snap.Metadata），
 *   套装 id 来自 ggartifact.com/good/data_cache.json 的 set_map（GOODScanner 同一张表）
 *   —— GOOD 未发布这一段，而背包详情面板 set_name 被遮挡，须由单件名反推套装
 * - stats / slots / elements 是静态表（词条名、部位名、七元素名）
 *
 * ★ 2026-10-01（#168）：以前这里读的是 `good_names.json` —— 同一套词典的**第二种形状**，
 *   由 `gen_good_names.py` 从 mappings.json re-encode 出来。两份形状就是两个真相，
 *   而 #155 正是这么丢的：那一层把 mappings 里一直有的 c3/c5 **静默丢掉**，
 *   运行时只能查一张 GT 反推的手表。现在只有一份，生成器与运行时同形。
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
    /** GOOD id → 元素/武器类型；角色弹层「沙漏」预筛据此换算勾选项（见 #125） */
    val charAttrs: Map<String, CharAttrs> = emptyMap(),
    /** 页头中文元素（'水'）→ GOOD 元素名（'Hydro'）；7 个，闭集 */
    val elementByZh: Map<String, String> = emptyMap(),
    /** 字典内部元素 key（'hydro'）→ GOOD 元素名（'Hydro'） */
    val elementByDictKey: Map<String, String> = emptyMap(),
) {

    /** 名称类别（flow 的 `dict` 字段映射）。 */
    enum class Kind { PIECE, WEAPON, CHARACTER, SET }

    data class StatEntry(val zh: String, val key: String, val percentKey: String?)

    data class PieceInfo(val setId: String, val setName: String?, val slot: String?)

    /**
     * 角色静态属性。`element` 对**旅行者/奇偶不可信**——他们的元素随旅行者当前元素变
     * （2026-09-29 实测：页头「草元素 / 崽崽」，而词典恒记 anemo），预筛必须跳过他们。
     *
     * [c3]/[c5] = 第 3 / 第 5 层命之座把哪一行天赋 +3：`"A"` 普通攻击、`"E"` 元素战技、
     * `"Q"` 元素爆发（GOODScanner `ConstBonus` 同一口径）。缺键 = 该角色没有这类加成
     * （旅行者走单独一条规则，见 `ScanEngine` 的命座加成注释）。
     */
    data class CharAttrs(
        val element: String?,
        val weapon: String?,
        val c3: String?,
        val c5: String?,
    )

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

    /**
     * 角色元素（GOOD 写法，如 'Hydro'）。★ #167：导出侧的元素**不再从 OCR 读**，
     * 只信字典 —— 旅行者系三个例外（他们的元素随游戏内实时变，字典恒为 anemo），
     * 那条路走 [elementByZh]。与 GOODScanner `ELEMENT_CHARACTERS` 同一划分。
     */
    fun elementOf(dictKey: String?): String? {
        if (dictKey == null) return null
        return charAttrs[dictKey]?.element?.let { elementByDictKey[it] }
    }

    companion object {
        const val ASSET_PATH = "dsl/tools/mappings.json"

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
            val charArr = json.getJSONArray("characters")
            val characters = nameMap(charArr)
            val attrs = LinkedHashMap<String, CharAttrs>()
            for (i in 0 until charArr.length()) {
                val o = charArr.getJSONObject(i)
                val id = o.getString("id")
                attrs[id] = CharAttrs(
                    element = o.optString("e").takeIf { it.isNotEmpty() },
                    weapon = o.optString("wt").takeIf { it.isNotEmpty() },
                    c3 = o.optString("c3").takeIf { it.isNotEmpty() },
                    c5 = o.optString("c5").takeIf { it.isNotEmpty() },
                )
            }
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

            val byZh = LinkedHashMap<String, String>()
            val byDictKey = LinkedHashMap<String, String>()
            val elArr = json.optJSONArray("elements") ?: org.json.JSONArray()
            for (i in 0 until elArr.length()) {
                val o = elArr.getJSONObject(i)
                val good = o.getString("good")
                byZh[o.getString("zh")] = good
                byDictKey[o.getString("key")] = good
            }

            return GoodNames(
                pieces, weapons, characters, sets, stats, slots, details, attrs,
                elementByZh = byZh, elementByDictKey = byDictKey,
            )
        }

        fun load(assets: AssetManager): GoodNames {
            val text = FlowSource.open(assets, ASSET_PATH).bufferedReader().use { it.readText() }
            return fromJson(JSONObject(text))
        }

        /** mappings.json 里名字在 `n.zh` 嵌套下（GOOD 规范的多语言段），不是平铺 `zh`。 */
        private fun nameMap(arr: org.json.JSONArray): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out[o.getJSONObject("n").getString("zh")] = o.getString("id")
            }
            return out
        }
    }
}
