package com.bettergi.pocket.scan

import com.bettergi.pocket.recognition.name.GoodNames
import com.bettergi.pocket.recognition.name.NameMatcher

/**
 * zh 词条名 → GOOD key 解析（移植自 GOODScanner stat_parser.rs，键风格一致：
 * hp/hp_/atk_/enerRech_/critRate_/critDMG_/eleMas/heal_/元素 dmg_；percent 值存百分数形式如 5.8）。
 */
object StatParser {

    sealed interface KeyEntry {
        val key: String
        data class Simple(override val key: String) : KeyEntry
        data class FlatPercent(val flat: String, val percent: String) : KeyEntry {
            override val key: String get() = flat
            fun resolve(hasPercent: Boolean): String = if (hasPercent) percent else flat
        }
    }

    // zh 名 → key（flat/percent 双键者由文本含 % 决定）
    private val ENTRIES: List<Pair<String, KeyEntry>> = listOf(
        "生命值" to KeyEntry.FlatPercent("hp", "hp_"),
        "攻击力" to KeyEntry.FlatPercent("atk", "atk_"),
        "防御力" to KeyEntry.FlatPercent("def", "def_"),
        "元素精通" to KeyEntry.Simple("eleMas"),
        "元素充能效率" to KeyEntry.Simple("enerRech_"),
        "暴击率" to KeyEntry.Simple("critRate_"),
        "暴击伤害" to KeyEntry.Simple("critDMG_"),
        "治疗加成" to KeyEntry.Simple("heal_"),
        "物理伤害加成" to KeyEntry.Simple("physical_dmg_"),
        "火元素伤害加成" to KeyEntry.Simple("pyro_dmg_"),
        "雷元素伤害加成" to KeyEntry.Simple("electro_dmg_"),
        "水元素伤害加成" to KeyEntry.Simple("hydro_dmg_"),
        "草元素伤害加成" to KeyEntry.Simple("dendro_dmg_"),
        "风元素伤害加成" to KeyEntry.Simple("anemo_dmg_"),
        "岩元素伤害加成" to KeyEntry.Simple("geo_dmg_"),
        "冰元素伤害加成" to KeyEntry.Simple("cryo_dmg_"),
    )

    /** zh 部位名 → GOOD slotKey。 */
    val SLOT_KEYS: List<Pair<String, String>> = listOf(
        "生之花" to "flower",
        "死之羽" to "plume",
        "时之沙" to "sands",
        "空之杯" to "goblet",
        "理之冠" to "circlet",
    )

    data class ParsedStat(val key: String, val value: Double, val inactive: Boolean)

    /**
     * 部位名 → slotKey。
     * @param names 注入名称词典时优先走 [NameMatcher]（模糊容错）；否则回退内置表。
     */
    fun slotKeyOf(text: String, names: GoodNames? = null): String? {
        val t = clean(text)
        if (names != null) {
            NameMatcher.match(t, names.slots)?.let { return it.key }
        }
        return SLOT_KEYS.firstOrNull { (zh, _) -> t.contains(zh) }?.second
    }

    /**
     * 解析 "暴击率+5.8%" / "生命值+717" / "元素充能效率+12.4%" 类文本。
     * @param names 注入名称词典时，词条**名**走 [NameMatcher]；数值解析逻辑不变。
     */
    fun parse(text: String, names: GoodNames? = null): ParsedStat? {
        val t = clean(text)
        if (t.isEmpty()) return null
        if (names != null) {
            val hasPercent = t.contains("%")
            val table = names.stats.associate { it.zh to it.key }
            val matched = NameMatcher.match(t, table)
            if (matched != null) {
                val entry = names.stats.firstOrNull { it.zh == matched.name }
                if (entry != null) {
                    val value = extractValue(t) ?: return null
                    val key = if (hasPercent && entry.percentKey != null) entry.percentKey else entry.key
                    return ParsedStat(key, value, t.contains("待激活"))
                }
            }
        }
        val entry = ENTRIES.firstOrNull { (zh, _) -> t.contains(zh) }
            ?: ENTRIES.firstOrNull { (zh, _) -> suffixMatch(t, zh) }
            ?: return null
        val hasPercent = t.contains("%")
        val key = when (val e = entry.second) {
            is KeyEntry.FlatPercent -> e.resolve(hasPercent)
            is KeyEntry.Simple -> e.key
        }
        val value = extractValue(t) ?: return null
        val scaled = if (hasPercent) value else value // flat 保持原值；percent 存百分数形式
        return ParsedStat(key, scaled, t.contains("待激活"))
    }

    /** 主词条值："4,780" → 4780.0；"46.6%" → 46.6；"Lv.90" → 90（完整数字优先，防 ".90" 被截胡）。 */
    fun extractValue(text: String): Double? {
        val t = fixDigits(clean(text))
        Regex("([0-9]+\\.?[0-9]*)").find(t)?.let { return it.groupValues[1].toDoubleOrNull() }
        // 缺整数部分容错（GOODScanner 同款：".7" → 0.7）
        Regex("\\.([0-9]+)").find(t)?.let { return it.groupValues[1].toDoubleOrNull() }
        return null
    }

    private fun suffixMatch(text: String, zh: String): Boolean {
        val suffix = zh.drop(1)
        return suffix.length >= 2 && text.contains(suffix)
    }

    /** 清洗：CJK 间空格去除、常见分隔符归一。 */
    fun clean(text: String): String {
        val sb = StringBuilder()
        val chars = text.toCharArray()
        for (i in chars.indices) {
            val c = chars[i]
            if (c == ' ' && i > 0 && i + 1 < chars.size && isCjk(chars[i - 1]) && isCjk(chars[i + 1])) {
                continue
            }
            if (c == ',') continue
            sb.append(if (c == '．' || c == '。' || c == '·') '.' else c)
        }
        return sb.toString().replace(Regex("(\\d)\\s*\\.\\s*(\\d)"), "$1.$2").trim()
    }

    private fun fixDigits(text: String): String = buildString {
        for (c in text) {
            when (c) {
                'n' -> append('0')
                'a' -> append('4')
                'l', 'I' -> append('1')
                'o', 'O' -> append('0')
                's', 'S' -> append('5')
                else -> append(c)
            }
        }
    }

    private fun isCjk(c: Char) = c in '\u4E00'..'\u9FFF'
}
