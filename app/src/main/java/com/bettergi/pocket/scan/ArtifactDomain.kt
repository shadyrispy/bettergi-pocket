package com.bettergi.pocket.scan

import android.content.Context
import android.util.Log
import org.json.JSONObject

/** GOOD v3 圣遗物条目（键风格与 GOODScanner 导出一致）。 */
data class GoodSubStat(val key: String, val value: Double)

data class GoodArtifact(
    val setKey: String?,
    val slotKey: String?,
    val level: Int,
    val rarity: Int,
    val mainStatKey: String?,
    val mainStatValue: Double,
    val substats: List<GoodSubStat>,
    val lock: Boolean,
    val favorited: Boolean?,
    val location: String = "",
    /** OCR 原文单件名（词典 276 件全局唯一）——入库去重键，不参与 GOOD 导出。 */
    val pieceName: String = "",
)

/** GOOD v3 武器（结构比圣遗物简单：name+key/refine/level/rarity/lock） */
data class GoodWeapon(
    val key: String?,     // mappings.weapons id
    val level: Int,
    val rarity: Int,      // 1-5
    val refine: Int?,     // 精炼 1-5
    val lock: Boolean,
    val location: String = "",
)

/**
 * GOOD v3 导出（filesDir/good_export_<ts>.json）。
 * 与 irminsul 结果对齐为 P1 验收口径（含 setKey 经 artifactSetPieces 反推）。
 */
object GoodExporter {
    /** GOOD v3 JSON 构建（纯函数，可离线测试）。artifacts + weapons 列表分别导出。 */
    fun buildGoodJson(
        artifacts: List<GoodArtifact>,
        weapons: List<GoodWeapon> = emptyList(),
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
                    })
                }
                put("substats", subs)
            })
        }
        root.put("artifacts", arr)
        val warr = org.json.JSONArray()
        for (w in weapons) {
            warr.put(JSONObject().apply {
                put("key", w.key ?: "")
                put("level", w.level)
                put("rarity", w.rarity)
                w.refine?.let { put("refinement", it) }
                put("location", w.location)
                put("lock", w.lock)
            })
        }
        root.put("weapons", warr)
        return root
    }

    fun export(context: Context, artifacts: List<GoodArtifact>, weapons: List<GoodWeapon> = emptyList()): String {
        val fileName = "good_export_${System.currentTimeMillis()}.json"
        context.openFileOutput(fileName, Context.MODE_PRIVATE).use { out ->
            out.write(buildGoodJson(artifacts, weapons).toString(2).toByteArray(Charsets.UTF_8))
        }
        Log.i(TAG, "GOOD export: ${artifacts.size}a ${weapons.size}w -> $fileName")
        return fileName
    }

    private const val TAG = "BetterGI.Scan"
}

/**
 * set_name 词典反推（用户定稿 2026-09-01：背包面板 set_name 被遮挡不采集，
 * 由单件名经 dsl/tools/artifactSetPieces.json pieceToSetId 反推，276 件/56 套）。
 */
class ArtifactSetDictionary(json: JSONObject) {
    /** 件名 → GOOD setKey */
    private val pieceToSetId = HashMap<String, String>()

    init {
        val map = json.getJSONObject("pieceToSetId")
        for (key in map.keys()) {
            pieceToSetId[key] = map.getString(key)
        }
    }

    fun setKeyByPiece(pieceName: String): String? {
        pieceToSetId[pieceName]?.let { return it }
        // 一级模糊：OCR 缺字时按包含匹配（词典件名全局唯一）
        pieceToSetId.entries.firstOrNull { (piece, _) ->
            piece.contains(pieceName) || pieceName.contains(piece)
        }?.let { return it.value }
        // 二级模糊：单字 Dice 相似度（OCR 错字场景，模拟器实测 '回秦之歌'/'渝告之钟' 类错字
        // 与真名互不包含，contains 兜不住）。276 件名 4-7 字唯一性强，0.55 阈值误配风险低。
        var bestId: String? = null
        var bestScore = 0.0
        for ((piece, setId) in pieceToSetId) {
            val score = unigramDice(pieceName, piece)
            if (score > bestScore) {
                bestScore = score
                bestId = setId
            }
        }
        return if (bestScore >= FUZZY_DICE_THRESHOLD) bestId else null
    }

    companion object {
        /** 二级模糊阈值（模拟器实测错字对 '深廊的回秦之歌'≈0.67、'渝告之钟'≈0.60）。 */
        const val FUZZY_DICE_THRESHOLD = 0.55

        fun load(assets: android.content.res.AssetManager): ArtifactSetDictionary {
            val text = assets.open("dsl/tools/artifactSetPieces.json").bufferedReader().use { it.readText() }
            return ArtifactSetDictionary(JSONObject(text))
        }
    }
}

/**
 * 武器反查：weapon name (zh) → GOOD key (mappings.weapons id)。
 * 236 件武器（与 ArtifactSetDictionary 一致的 5★ 卡池 ID 规则）。
 */
class WeaponDictionary(json: JSONObject) {
    private val nameToId = HashMap<String, String>()

    init {
        val weapons = json.getJSONArray("weapons")
        for (i in 0 until weapons.length()) {
            val w = weapons.getJSONObject(i)
            val zh = w.getJSONObject("n").getString("zh")
            nameToId[zh] = w.getString("id")
        }
    }

    fun keyByName(name: String): String? {
        nameToId[name]?.let { return it }
        // 一级模糊：OCR 缺字包含匹配
        nameToId.entries.firstOrNull { (n, _) -> n.contains(name) || name.contains(n) }
            ?.let { return it.value }
        // 二级模糊：单字 Dice（与 ArtifactSetDictionary 同款；模拟器实测错字对
        // '万国诺海图谱'(0.83)/'风信之鋒'繁体(0.75) contains 兜不住）
        var bestId: String? = null
        var bestScore = 0.0
        for ((n, id) in nameToId) {
            val score = unigramDice(name, n)
            if (score > bestScore) {
                bestScore = score
                bestId = id
            }
        }
        return if (bestScore >= ArtifactSetDictionary.FUZZY_DICE_THRESHOLD) bestId else null
    }

    companion object {
        fun load(assets: android.content.res.AssetManager): WeaponDictionary {
            val text = assets.open("dsl/tools/mappings.json").bufferedReader().use { it.readText() }
            return WeaponDictionary(JSONObject(text))
        }
    }
}

/** 单字 Dice 系数：2*|A∩B| / (|A|+|B|)，A/B 为字符集合（词典模糊匹配共享）。 */
internal fun unigramDice(a: String, b: String): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val sa = a.toSet()
    val sb = b.toSet()
    val inter = sa.intersect(sb).size.toDouble()
    return 2.0 * inter / (sa.size + sb.size)
}

/**
 * 角色反查：character name (zh) → GOOD key (mappings.characters id)。
 * 121 角色。
 */
class CharacterDictionary(json: JSONObject) {
    private val nameToId = HashMap<String, String>()

    init {
        val characters = json.getJSONArray("characters")
        for (i in 0 until characters.length()) {
            val c = characters.getJSONObject(i)
            val zh = c.getJSONObject("n").getString("zh")
            nameToId[zh] = c.getString("id")
        }
    }

    fun keyByName(name: String, fuzzy: Int = 0): String? {
        nameToId[name]?.let { return it }
        if (fuzzy <= 0) return null
        val fuzzy_match = nameToId.entries.firstOrNull { (n, _) ->
            // 包含匹配 + 字符差异（简单 fuzzy）
            n.contains(name) || name.contains(n) ||
                (n.length > fuzzy && n.length - name.length in 0..fuzzy && n.take(name.length) == name)
        }
        return fuzzy_match?.value
    }

    companion object {
        fun load(assets: android.content.res.AssetManager): CharacterDictionary {
            val text = assets.open("dsl/tools/mappings.json").bufferedReader().use { it.readText() }
            return CharacterDictionary(JSONObject(text))
        }
    }
}
