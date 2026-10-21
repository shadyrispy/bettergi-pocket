package com.bettergi.pocket.scan

import com.bettergi.pocket.recognition.name.GoodNames
import com.bettergi.pocket.recognition.name.NameMatcher

/**
 * zh 词条名 → GOOD key 解析（移植自 GOODScanner stat_parser.rs，键风格一致：
 * hp/hp_/atk_/enerRech_/critRate_/critDMG_/eleMas/heal_/元素 dmg_；percent 值存百分数形式如 5.8）。
 */
object StatParser {

    /** 副词条**全局上限**（游戏硬上限 4）。 */
    const val MAX_SUBS = 4

    /**
     * 读**连续块**副词条（唯一实现点，可单测）。
     *
     * 规则：跳过空行；**非空但解析不出 stat 的行 ⇒ 块结束**。面板里紧随词条块的是
     * 「套装名: 2件套：攻击力提高18%」这类行 —— 逐行 `mapNotNull` 会把它的数字读成**幻影词条**
     * （2026-09-16 GT 实测：5★+0 读成 4 条的 205 件 vs 真值 77 件）；
     * 而按稀有度猜条数（5★恒 4）同样错（真值 67% 的 5★+0 只有 3 条）。
     */
    fun parseBlock(subLines: List<String>, names: GoodNames? = null): List<ParsedStat> {
        val out = ArrayList<ParsedStat>(MAX_SUBS)
        // 守卫用的"已知套装名"集合（zh）。GoodNames.sets 的键/值都收，取长的（中文名 ≥2 字，ASCII id 不会误命中）
        val setNames = names?.sets?.let { m -> (m.keys + m.values) }.orEmpty()
            .filter { it.length >= 2 }
        for (raw in subLines) {
            val ln = raw.trim()
            if (ln.isEmpty()) continue
            // ★ 守卫①（移植 GOODScanner「2件套 stop marker」）：套装效果行 = 词条块结束。
            //   真机事故：3 条词条的 +0 件，第 4 行 ROI 压到「套装名: 2件套：攻击力提高18%」上，
            //   `StatParser.parse` 会从"攻击力提高18%"里读出 atk_ 18.0 ⇒ **幻影词条**（205 件 vs GT 77）。
            if (ln.contains("件套")) break
            // ★ 守卫②（移植 GOODScanner「Set Name Bleeding」）：套装名上移占了词条槽位 ⇒ 块结束。
            if (setNames.any { ln.contains(it) }) break
            // ★ 守卫③：非空但解析不出 stat ⇒ 块结束（不是词条行）。
            val st = parse(ln, names) ?: break
            out.add(st)
            if (out.size >= MAX_SUBS) break
        }
        return out
    }

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
     * 主词条名 → GOOD key（**只读名字**；mainValue 槽已删）。
     *
     * 名字槽文本**不含百分号**（% 在值槽），且同 slot 内 flat/percent 主词条名不碰撞
     * （27750 行真机面板原文验证：时之沙「生命值」只有 hp_、生之花「生命值」只有 hp，
     * (slot, 名字) → key 零歧义）⇒ flat/percent 由 [slotKey] 消歧，不需要读值。
     *
     * ROI 收窄到 3 字后 OCR 常见截断/尾字畸变（历史实读：'暴击伤'×288、'元素精'×212、
     * '火元素伤害加质'×196、'元素充'×130、'治疗加'×58…），用**双向前缀匹配**消化；
     * 元素伤害家族用**首字**定元素（'火元素伤害加质' 首字即判，7 字全名不必读出）。
     * 前缀匹配有歧义时（'暴击' 撞 暴击率/暴击伤害）用 [slotKey] 兜不住——但 3 字宽读不出
     * 2 字截断（OCR 按字块切割），历史 27696 样本该规则 0.00% 失败。
     *
     * @param text mainName 槽 OCR 原文（可能截断/带尾噪）
     * @param slotKey 由单件名反查的部位（flower/plume/sands/goblet/circlet）；null 也能判
     *        多数主词条，仅生之花/死之羽的固定主词条（hp/atk）依赖它
     * @param names 词典（未用时走内置表）
     */
    fun mainKeyOf(text: String, slotKey: String?, names: GoodNames? = null): String? {
        val t = clean(text)
        if (t.isEmpty()) return null
        // 生之花/死之羽主词条恒定（游戏规则），名字都不用读——但名字 miss 也别硬给：
        // 名字读出且是词条名才走常量（防御横幅件/素材面板读到别的东西）。
        val fixed = when (slotKey) {
            "flower" -> "hp"
            "plume" -> "atk"
            else -> null
        }
        if (fixed != null) {
            // 名字必须**像**一个词条名（前缀匹配任意一条）才信 slot 常量
            if (prefixMatchName(t) != null || names?.stats?.any { it.zh.startsWith(t) || t.startsWith(it.zh) } == true) {
                return fixed
            }
            return null
        }
        // 双向前缀唯一命中（'暴击伤'→暴击伤害；'元素精'→元素精通；'火元素伤害加质'→火元素伤害加成）
        val hit = prefixMatchName(t)
        if (hit != null) {
            val entry = ENTRIES.firstOrNull { it.first == hit }?.second ?: return null
            return when (entry) {
                is KeyEntry.FlatPercent -> when (slotKey) {
                    // flat 主词条只存在于生之花/死之羽（上面已处理）——其余部位全部是 percent 键
                    else -> entry.percent ?: entry.key
                }
                is KeyEntry.Simple -> entry.key
            }
        }
        // 词典通道（NameMatcher，容错更强）兜底
        if (names != null) {
            val table = names.stats.associate { it.zh to it.key }
            NameMatcher.match(t, table)?.let { m ->
                val entry = ENTRIES.firstOrNull { it.first == m.name }?.second ?: return null
                return when (entry) {
                    is KeyEntry.FlatPercent -> entry.percent ?: entry.key
                    is KeyEntry.Simple -> entry.key
                }
            }
        }
        return null
    }

    /** mainName 双向前缀匹配：返回唯一命中的词条标准名；歧义/无命中返回 null。 */
    private fun prefixMatchName(t: String): String? {
        val hits = ENTRIES.map { it.first }.filter { zh -> zh.startsWith(t) || t.startsWith(zh) }
        if (hits.size == 1) return hits[0]
        // 元素伤害家族首字判别（'火元素…'/'火元…' ⇒ 火元素伤害加成）
        if (t.isNotEmpty()) {
            val byHead = ELEMENT_HEADS[t[0].toString()]
            if (byHead != null && (t.contains("元素") || t.length <= 3)) return byHead
        }
        return null
    }

    /** 元素首字 → 伤害加成词条名（前缀判据的家族分支）。 */
    private val ELEMENT_HEADS: Map<String, String> = mapOf(
        "火" to "火元素伤害加成", "水" to "水元素伤害加成", "雷" to "雷元素伤害加成",
        "草" to "草元素伤害加成", "风" to "风元素伤害加成", "岩" to "岩元素伤害加成",
        "冰" to "冰元素伤害加成", "物" to "物理伤害加成",
    )

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

    /**
     * 主词条值："4,780" → 4780.0；"46.6%" → 46.6；"Lv.90" → 90（完整数字优先，防 ".90" 被截胡）。
     *
     * A12（2026-09-30，优化方案轨 D1）：OCR 丢整数位（"5.8%" → ".8%"）时首正则
     * `([0-9]+\.?[0-9]*)` 会截到 "8" 返回 8.0（**十倍错**）；原第二正则 `\.([0-9]+)` 排在其后
     * ⇒ **永不可达**（死代码，已删——首正则能匹配第二正则可匹配的任何位置）。
     * 修法：在首正则**之前**探测「点前非数字/字母」的小数形态，命中才按 0.x 解析。
     * ⚠️ 守卫约束（沿用原注释语义）：点前是字母/数字时**不能**按小数——`Lv.90` 的点前是
     *    字母，否则会被解析成 0.90；完整数字优先的既有行为保持不变。
     * 输入域核查（2026-09-30 全调用点）：StatParser.parse（词条/主词条行）、ScanEngine:7005/7009
     * （武器 Lv.90 / 精炼N阶）、ScanEngine:7216（圣遗物 +20 / Lv.x）——均为「点前是数字或字母」
     * 的正常形态，本分支不触发，不影响既有解析。
     */
    fun extractValue(text: String): Double? {
        val t = fixDigits(clean(text))
        Regex("\\.([0-9]+)").find(t)?.let { m ->
            val prev = if (m.range.first > 0) t[m.range.first - 1] else null
            if (prev == null || !prev.isLetterOrDigit()) {
                // ".8" / "+.8%"：整数位被 OCR 丢掉 ⇒ 按小数还原为 0.8
                return ("0" + m.value).toDoubleOrNull()
            }
        }
        Regex("([0-9]+\\.?[0-9]*)").find(t)?.let { return it.groupValues[1].toDoubleOrNull() }
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
