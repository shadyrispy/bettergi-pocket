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
 * 流程三（角色扫描）产物。字段取自 `panels.char_profile` / `char_constellation` / `char_talent`。
 * - [constellation] = 已点亮命座数（0-6，白锁判据）；
 * - [talents] = 3 个战斗天赋等级（[0]=普攻 auto、[1]=元素战技 skill、[2]=元素爆发 burst）；
 * - [talentLocked] = 对应天赋是否处于「灰锁」未解锁态（图标框低饱和亮块判据）。
 */
data class GoodCharacter(
    val key: String?,
    val name: String,
    val level: Int,
    val element: String?,
    val favor: Int = 0,
    val constellation: Int = 0,
    val talents: List<Int> = emptyList(),
    val talentLocked: List<Boolean> = emptyList(),
    /** OCR 原文（未匹配词典时保留，便于人工核对）。 */
    val rawName: String = "",
)

/**
 * GOOD v3 导出（filesDir/good_export_<ts>.json）。
 * 与 irminsul 结果对齐为 P1 验收口径（含 setKey 经 artifactSetPieces 反推）。
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
        val carr = org.json.JSONArray()
        for (c in characters) {
            carr.put(JSONObject().apply {
                put("key", c.key ?: "")
                put("name", c.name)
                put("level", c.level)
                c.element?.let { put("element", it) }
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

// ---------------------------------------------------------------------------
// 名称词典（角色/武器/套装/圣遗物单件/词条/部位）已统一收敛到：
//   recognition/name/GoodNames.kt  —— 单一文件 dsl/tools/good_names.json 装载
//   recognition/name/NameMatcher.kt —— 唯一一处模糊匹配算法
// 原 ArtifactSetDictionary / WeaponDictionary / CharacterDictionary 三套各写一份
// 模糊逻辑（且 contains 走 HashMap.firstOrNull，结果依赖遍历顺序）已全部删除：
// characters 表实测准确率 77.8% → 91.2%，全表合计 90.5% → 93.7%（见 NameMatcherTest）。
// ---------------------------------------------------------------------------
