package com.bettergi.pocket.recognition.name

/**
 * 通用 OCR 文本 → GOOD key 模糊匹配器（全项目唯一一处名称匹配算法）。
 *
 * 合并自三方实现，取各家所长（对比数据见 NameMatcherTest 的压测用例）：
 * - GOODScanner `scanner/common/fuzzy_match.rs`：归一化 / OCR 混淆表 / 双向子串**取最长** /
 *   编辑距离 30% / 视觉相似组 tie-break / LCS 唯一性
 * - irminsul `domain/NameMapper.kt`：上述算法的 Kotlin 移植 + 组合混淆 + 词条表
 * - bettergi-pocket 原 `ArtifactSetDictionary`：单字 Dice 兜底（整名错字但字集合高度重合
 *   的场景，yas 各级都救不回来——pieces 表实测 Dice 贡献 +2.8pt）
 *
 * 分级（按序短路，命中即返回）：
 * | 级 | 策略 | 典型场景 |
 * |---|---|---|
 * | 0 | 归一化（全角→半角、西里尔形近字→ASCII、剔标点） | OCR 输出全角/西里尔 |
 * | 1 | OCR 混淆替换（单条 / 全量组合）→ 精确 | 拉鸟玛→菈乌玛、海染碟→海染砗磲 |
 * | 2 | 精确 | 正常 |
 * | 3 | 正向子串取**最长**键（OCR 加噪声前后缀） | ·神里绫华 |
 * | 4 | 反向子串（OCR 截断） | 昔日宗室之→昔日宗室之仪 |
 * | 5 | 编辑距离 ≤ max(1, 名称长度 30%)，并列按视觉相似组打分 | 渝告之钟→谕告之钟 |
 * | 6 | 最长公共连续子串 ≥3 且**唯一**命中 | 忆之注连→追忆之注连 |
 * | 7 | 单字 Dice ≥ 0.55（兜底） | 深廊的回秦之歌→深廊的回奏之歌 |
 *
 * ⚠️ 子串级必须**取最长**且迭代顺序无关：原实现用 `HashMap.firstOrNull` 命中即返回，
 * 结果依赖哈希表遍历顺序（不确定），characters 表实测准确率仅 77.8%（统一后 91.2%）。
 *
 * 纯 Kotlin（无 Android 依赖），JVM 可单测。
 */
object NameMatcher {

    enum class Tier(val label: String) {
        CONFUSION("ocr混淆"),
        CONFUSION_COMBINED("ocr混淆(组合)"),
        EXACT("精确"),
        SUBSTRING("子串"),
        SUBSTRING_REVERSE("反向子串"),
        LEVENSHTEIN("编辑距离"),
        LCS("公共子串唯一"),
        DICE("字集合Dice"),
    }

    data class MatchResult(
        /** GOOD key（武器/角色/套装 id，单件名则为所属套装 id）。 */
        val key: String,
        /** 命中的词典标准名（诊断用）。 */
        val name: String,
        /** 命中层级（诊断/埋点用）。 */
        val tier: Tier,
        /** 该层级的相似度/距离量（越大越可信；编辑距离层为归一化相似度）。 */
        val score: Double,
    )

    /**
     * OCR 混淆字对（误读字符 → 正确字符）。
     * 只在「误读字符不出现在任何合法名称中」时才可加，否则会破坏正确匹配。
     * 来源：GOODScanner fuzzy_match.rs（20260830 irminsul 移植同步）。
     */
    private val CONFUSIONS = listOf(
        "茲" to "兹", "兹" to "茲",
        "碟" to "磲",           // 海染砗磲 → 海染碟
        "蒙" to "曚", "朦" to "曚", // 曚云之月
        "稚" to "薙", "雉" to "薙", // 薙草之稻光
        "拉" to "菈", "鸟" to "乌", // 菈乌玛
    )

    /** 视觉相似字组：编辑距离并列时的 tie-break 依据。 */
    private val VISUAL_GROUPS = listOf(
        "闲闭问闪间门阅",
        "重里董量熏画童",
        "菈拉",
        "乌鸟",
        "薙稚雉",
        "磲碟",
        "兹茲",
        "云昙",
    ).map { it.toSet() }

    /** 西里尔形近字 → ASCII（OCR 常见混淆，如 е=U+0435 当 e=U+0065）。 */
    private val CYRILLIC = mapOf(
        'А' to 'a', 'а' to 'a', 'В' to 'B', 'в' to 'B', 'Е' to 'e', 'е' to 'e',
        'К' to 'K', 'к' to 'K', 'М' to 'M', 'м' to 'M', 'Н' to 'H', 'н' to 'H',
        'О' to 'o', 'о' to 'o', 'Р' to 'p', 'р' to 'p', 'С' to 'c', 'с' to 'c',
        'Т' to 'T', 'т' to 'T', 'У' to 'y', 'у' to 'y', 'Х' to 'x', 'х' to 'x',
    )

    // ---- 阈值（2615 样本扰动压测标定，见 NameMatcherTest 的守门用例）----
    /** 编辑距离阈值 = max(1, 名称长度 * 30%)。 */
    private const val LEV_RATIO = 0.30
    /** LCS 唯一性最短公共子串（≥3：irminsul 口径，实测负样本零误配）。 */
    private const val LCS_MIN = 3
    /** 单字 Dice 兜底阈值。 */
    /**
     * 字集合 Dice 兜底阈值。
     * ⚠️ 2026-09-17 曾试收紧到 0.80 以排查武器 `LionsRoar` 异常 —— **已回退**：
     * 实测那 22 条是"**同一把武器跨页重复入库**"（level/refine/lock/location 全同），
     * **不是名字误匹配** ✗；且收紧后 `NameMatcherTest` 的"单字混淆/扰动准确率"用例挂掉
     * ⇒ Dice 层是被单测守护的有效能力，保持 0.55。
     */
    private const val DICE_MIN = 0.55
    /** 短于此长度不参与子串/编辑/LCS（防误配）。 */
    private const val MIN_LEN_FOR_FUZZY = 2

    /**
     * 归一化：全角→半角、西里尔形近字→ASCII，只保留 CJK / 字母数字 / 「」 / %。
     * 必须在过滤**之前**做映射，否则全角/西里尔字符会被直接剔除。
     */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            val mapped = when (c.code) {
                in 0xFF01..0xFF5E -> (c.code - 0xFEE0).toChar()
                else -> CYRILLIC[c] ?: c
            }
            val keep = (mapped in '\u4E00'..'\u9FFF') ||
                mapped == '「' || mapped == '」' || mapped == '%' ||
                (mapped.code < 128 && (mapped in 'a'..'z' || mapped in 'A'..'Z' || mapped in '0'..'9'))
            if (keep) sb.append(mapped)
        }
        return sb.toString().trim()
    }

    /**
     * 在 [table]（中文名 → GOOD key）中匹配 [text]。
     *
     * @param allowFuzzy false = 只走归一化+精确（flow 的 `fuzzy: 0` 语义）
     */
    fun match(text: String, table: Map<String, String>, allowFuzzy: Boolean = true): MatchResult? {
        if (table.isEmpty()) return null
        val cleaned = normalize(text)
        if (cleaned.isEmpty()) return null

        // ── 1. OCR 混淆替换（单条）
        for ((from, to) in CONFUSIONS) {
            if (!cleaned.contains(from)) continue
            val alt = cleaned.replace(from, to)
            val hit = table[alt]
            if (hit != null) return MatchResult(hit, alt, Tier.CONFUSION, 1.0)
        }
        // ── 1b. 混淆同时叠加（OCR 多字同时误读，如「拉鸟玛」→「菈乌玛」）
        val combined = applyAllConfusions(cleaned)
        if (combined != cleaned) {
            val hit = table[combined]
            if (hit != null) return MatchResult(hit, combined, Tier.CONFUSION_COMBINED, 1.0)
        }

        // ── 2. 精确
        table[cleaned]?.let { return MatchResult(it, cleaned, Tier.EXACT, 1.0) }
        if (!allowFuzzy) return null
        if (cleaned.length < MIN_LEN_FOR_FUZZY) return null

        // ── 3. 正向子串：cleaned 包含词典键（OCR 加了噪声前后缀），取**最长**键
        var bestKey: String? = null
        var bestLen = 0
        for (key in table.keys) {
            if (key.length >= MIN_LEN_FOR_FUZZY && cleaned.contains(key) && key.length > bestLen) {
                bestKey = key
                bestLen = key.length
            }
        }
        if (bestKey != null) {
            return MatchResult(table.getValue(bestKey), bestKey, Tier.SUBSTRING, bestLen.toDouble())
        }

        // ── 4. 反向子串：词典键包含 cleaned（OCR 截断）
        bestKey = null
        bestLen = 0
        for (key in table.keys) {
            if (key.length >= MIN_LEN_FOR_FUZZY && key.contains(cleaned) && cleaned.length > bestLen) {
                bestKey = key
                bestLen = cleaned.length
            }
        }
        if (bestKey != null) {
            return MatchResult(table.getValue(bestKey), bestKey, Tier.SUBSTRING_REVERSE, bestLen.toDouble())
        }

        // ── 5. 编辑距离（阈值 = max(1, 名称长度*30%)）
        var minDist = Int.MAX_VALUE
        var candidates: MutableList<Pair<String, String>>? = null
        for ((key, value) in table) {
            val dist = levenshtein(cleaned, key)
            if (dist <= maxOf(1, (key.length * LEV_RATIO).toInt())) {
                if (dist < minDist) {
                    minDist = dist
                    candidates = mutableListOf(key to value)
                } else if (dist == minDist) {
                    candidates?.add(key to value)
                }
            }
        }
        val levCandidates = candidates
        if (!levCandidates.isNullOrEmpty()) {
            val picked = if (levCandidates.size == 1) {
                levCandidates.first()
            } else {
                // 并列：按视觉相似组打分（同形字替换更可能是 OCR 误读）
                levCandidates.maxByOrNull { (key, _) -> visualScore(key, cleaned) } ?: levCandidates.first()
            }
            val longest = maxOf(cleaned.length, picked.first.length)
            return MatchResult(
                picked.second, picked.first, Tier.LEVENSHTEIN,
                if (longest == 0) 1.0 else 1.0 - minDist.toDouble() / longest,
            )
        }

        // ── 6. 最长公共连续子串 ≥3 且唯一命中
        var bestLcs = 0
        var bestLcsKey: String? = null
        var unique = true
        for (key in table.keys) {
            val len = longestCommonSubstring(cleaned, key)
            if (len > bestLcs) {
                bestLcs = len
                bestLcsKey = key
                unique = true
            } else if (len == bestLcs && len > 0) {
                if (table[bestLcsKey] != table[key]) unique = false
            }
        }
        if (bestLcs >= LCS_MIN && unique && bestLcsKey != null) {
            return MatchResult(table.getValue(bestLcsKey), bestLcsKey, Tier.LCS, bestLcs.toDouble())
        }

        // ── 7. 单字 Dice 兜底（整名错字但字集合高度重合）
        var bestDice = 0.0
        var bestDiceKey: String? = null
        for (key in table.keys) {
            val score = unigramDice(cleaned, key)
            if (score > bestDice) {
                bestDice = score
                bestDiceKey = key
            }
        }
        if (bestDice >= DICE_MIN && bestDiceKey != null) {
            return MatchResult(table.getValue(bestDiceKey), bestDiceKey, Tier.DICE, bestDice)
        }
        return null
    }

    private fun applyAllConfusions(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            val cs = c.toString()
            sb.append(CONFUSIONS.firstOrNull { it.first == cs }?.second ?: cs)
        }
        return sb.toString()
    }

    private fun visualScore(candidate: String, ocr: String): Int {
        var score = 0
        val n = minOf(candidate.length, ocr.length)
        for (i in 0 until n) {
            val a = candidate[i]
            val b = ocr[i]
            if (a != b && VISUAL_GROUPS.any { a in it && b in it }) score++
        }
        return score
    }

    /** 字符级 Levenshtein（CJK 必须按字符而非字节）。 */
    internal fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** 最长公共**连续**子串长度。 */
    internal fun longestCommonSubstring(a: String, b: String): Int {
        var best = 0
        var prev = IntArray(b.length + 1)
        for (ca in a) {
            val cur = IntArray(b.length + 1)
            for (j in 1..b.length) {
                if (ca == b[j - 1]) {
                    cur[j] = prev[j - 1] + 1
                    if (cur[j] > best) best = cur[j]
                }
            }
            prev = cur
        }
        return best
    }

    /** 单字集合 Dice 系数：2*|A∩B| / (|A|+|B|)，忽略顺序（故仅作兜底）。 */
    internal fun unigramDice(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val sa = a.toSet()
        val sb = b.toSet()
        return 2.0 * sa.intersect(sb).size / (sa.size + sb.size)
    }
}
