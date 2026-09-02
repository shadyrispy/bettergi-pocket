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

/**
 * GOOD v3 导出（filesDir/good_export_<ts>.json）。
 * 与 irminsul 结果对齐为 P1 验收口径（含 setKey 经 artifactSetPieces 反推）。
 */
object GoodExporter {
    /** GOOD v3 JSON 构建（纯函数，可离线测试）。 */
    fun buildGoodJson(artifacts: List<GoodArtifact>): JSONObject {
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
        return root
    }

    fun export(context: Context, artifacts: List<GoodArtifact>): String {
        val fileName = "good_export_${System.currentTimeMillis()}.json"
        context.openFileOutput(fileName, Context.MODE_PRIVATE).use { out ->
            out.write(buildGoodJson(artifacts).toString(2).toByteArray(Charsets.UTF_8))
        }
        Log.i(TAG, "GOOD export: ${artifacts.size} artifacts -> $fileName")
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
        // 模糊兜底：OCR 缺字时按包含匹配（词典件名全局唯一）
        val fuzzy = pieceToSetId.entries.firstOrNull { (piece, _) ->
            piece.contains(pieceName) || pieceName.contains(piece)
        }
        return fuzzy?.value
    }

    companion object {
        fun load(assets: android.content.res.AssetManager): ArtifactSetDictionary {
            val text = assets.open("dsl/tools/artifactSetPieces.json").bufferedReader().use { it.readText() }
            return ArtifactSetDictionary(JSONObject(text))
        }
    }
}
