package com.bettergi.pocket.recognition.name

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * NameMatcher 守门测试：统一模糊匹配算法（yas 5 级 + Dice 兜底）。
 *
 * 用例来源：
 * - GOODScanner `fuzzy_match.rs` 自带单测（移植：精确/子串/全角/混淆组合/编辑距离视觉
 *   tie-break/LCS 拒绝歧义）
 * - bettergi-pocket 模拟器实测错字对（旧 DictionaryFuzzyTest）
 * - 确定性扰动压测：各表准确率下限 + 负样本零误配
 *
 * 压测基线（Python 原型 2615 样本实测）：
 *   pieces 96.7% / weapons 90.2% / characters 91.2% / sets 97.2% / 合计 93.7%
 *   （对照：旧三套各自实现 90.5%，其中 characters 仅 77.8%——contains 走
 *    HashMap.firstOrNull 依赖遍历顺序且不取最长）
 */
class NameMatcherTest {

    // ---------- 合成小表用例（GOODScanner 移植）----------
    private val chongyun = mapOf("重云" to "Chongyun", "闲云" to "Xianyun")
    private val ayaka = mapOf("神里绫华" to "KamisatoAyaka")
    private val instructor = mapOf("教官" to "Instructor", "战狂" to "Berserker", "赌徒" to "Gambler")
    private val lauoma = mapOf("菈乌玛" to "Lauma", "芭芭拉" to "Barbara")

    @Test
    fun `exact match`() {
        assertEquals("KamisatoAyaka", NameMatcher.match("神里绫华", ayaka)?.key)
    }

    @Test
    fun `substring match strips noise around name`() {
        assertEquals("KamisatoAyaka", NameMatcher.match("·神里绫华", ayaka)?.key)
    }

    @Test
    fun `fullwidth char is normalized before match`() {
        assertEquals("Instructor", NameMatcher.match("教ｅ", instructor)?.key)
    }

    @Test
    fun `cyrillic lookalike is normalized to ascii`() {
        // е = U+0435（西里尔），OCR 常见混淆；必须在过滤前映射，否则被当非 ASCII 剔除
        assertEquals("Instructor", NameMatcher.match("教е", instructor)?.key)
    }

    @Test
    fun `levenshtein ties broken by visual similarity`() {
        // "里云" 距 重云/闲云 都是 1；"里" 与 "重" 同视觉组
        assertEquals("Chongyun", NameMatcher.match("里云", chongyun)?.key)
        // "问云" 距两者都是 1；"问" 与 "闲" 同视觉组
        assertEquals("Xianyun", NameMatcher.match("问云", chongyun)?.key)
    }

    @Test
    fun `combined ocr confusion fixes multi-char garble`() {
        // 拉→菈 与 鸟→乌 必须同时替换；单条替换都命中不了
        assertEquals("Lauma", NameMatcher.match("拉鸟玛", lauoma)?.key)
        // 且不能误伤：芭芭拉 走精确，拉→菈 单条替换不产生假命中
        assertEquals("Barbara", NameMatcher.match("芭芭拉", lauoma)?.key)
    }

    @Test
    fun `single ocr confusion fixes one-char garble`() {
        // 海染砗磲 → 海染碟（碟→磲 在混淆表内）
        val sets = mapOf("海染砗磲" to "OceanHuedClam", "苍白之火" to "PaleFlame")
        assertEquals("OceanHuedClam", NameMatcher.match("海染碟", sets)?.key)
    }

    @Test
    fun `lcs min 2 rescues the double-char garble the confusion table cannot`() {
        // #169 探针实测（2026-10-01 00:33）：筛选页把「海染砗磲」读成「海染碎碟」——
        // 砗→碎、磲→碟两个字都错，碟→磲 单对替换救不了；LCS_MIN=3 时公共子串只有
        // 「海染」=2 也救不了。对齐参考实现降到 2 后由 LCS 唯一性兜住。
        val n = names()
        val hit = n.match("海染碎碟", GoodNames.Kind.SET)
        assertEquals("OceanHuedClam", hit?.key)
        assertEquals(NameMatcher.Tier.LCS, hit?.tier)
    }

    @Test
    fun `lcs fallback rejects ambiguous candidates`() {
        // "之心" 被三个候选共享 → 不唯一 → 不匹配
        val hearts = mapOf(
            "守护之心" to "DefendersWill",
            "沉沦之心" to "HeartOfDepth",
            "勇士之心" to "BraveHeart",
        )
        assertNull(NameMatcher.match("乱码之心乱码", hearts))
    }

    @Test
    fun `empty and single char do not match`() {
        assertNull(NameMatcher.match("", ayaka))
        assertNull(NameMatcher.match("云", chongyun)) // 单字不参与子串/编辑/LCS（防误配）
    }

    @Test
    fun `fuzzy disabled means exact only`() {
        // 截断名走子串级 → 关掉模糊后不应命中
        assertNull(NameMatcher.match("神里绫", ayaka, allowFuzzy = false))
        assertEquals("KamisatoAyaka", NameMatcher.match("神里绫", ayaka)?.key)
        // 归一化剥掉装饰符号后仍是精确命中（与 GOODScanner「子串」用例同效）
        assertEquals(
            "KamisatoAyaka",
            NameMatcher.match("·神里绫华", ayaka, allowFuzzy = false)?.key,
        )
    }

    // ---------- A22：三层兜底的确定性 tie-break（数据序无关）----------

    /** 与 [linkedMapOf] 相同的条目、反转后的插入序（模拟词典数据序颠倒）。 */
    private fun <K, V> Map<K, V>.reversedOrder(): Map<K, V> {
        val out = LinkedHashMap<K, V>()
        for ((k, v) in entries.reversed()) out[k] = v
        return out
    }

    @Test
    fun `A22 tier4 反向子串取最长键且与数据序无关`() {
        // 西风系列：碎片 OCR 截断成 "西风" 时，剑/枪/秘典三个键都包含它。
        // 旧实现首键胜出 ⇒ 结果依赖词典构造序；KDoc 铁律是"取最长且迭代顺序无关"。
        val forward = linkedMapOf(
            "西风剑" to "FavoniusSword",
            "西风枪" to "FavoniusLance",
            "西风秘典" to "FavoniusCodex",
        )
        val reversed = forward.reversedOrder()
        val hitForward = NameMatcher.match("西风", forward)
        val hitReversed = NameMatcher.match("西风", reversed)
        assertEquals("FavoniusCodex", hitForward?.key)
        assertEquals("西风秘典", hitForward?.name)
        assertEquals(NameMatcher.Tier.SUBSTRING_REVERSE, hitForward?.tier)
        assertEquals("数据序反转后结果必须一致", hitForward, hitReversed)
    }

    @Test
    fun `A22 tier5 编辑距离全并列且视觉分全0时宁miss不误配`() {
        // "西风贝" 距 西风剑/西风枪 均为 1：视觉分 0、公共前缀 2、长度差 0 全并列
        // ⇒ 判据耗尽不命中（characters 91.2% 教训：歧义层宁可 miss）。旧实现取首键（依赖数据序）。
        val forward = linkedMapOf("西风剑" to "FavoniusSword", "西风枪" to "FavoniusLance")
        val reversed = forward.reversedOrder()
        assertNull(NameMatcher.match("西风贝", forward))
        assertNull(NameMatcher.match("西风贝", reversed))
        // 判据可分层时仍命中：唯一候选不经过并列链
        assertEquals("FavoniusSword", NameMatcher.match("西风剑镡", forward)?.key)
        // 视觉分打破并列的旧能力不回退（"里云" 用例见上）
        assertEquals("Chongyun", NameMatcher.match("里云", chongyun)?.key)
    }

    @Test
    fun `A22 tier7 Dice 打平不命中且与数据序无关`() {
        // LCS 降到 2（对齐 fuzzy_match.rs）后，旧 fixture「剑刃风/风刃剑」会在 LCS 层被
        // 2 字公共子串截走、落不到 Dice。改用交错序字形：与两键的公共连续子串都只有 1，
        // 字集合交集数相同 ⇒ Dice 打平（2*2/7≈0.571 ≥0.55）；编辑距离 2 超阈值。
        // 旧实现取首键（依赖数据序），新实现唯一性校验拦截（对齐 LCS 层先例）。
        val forward = linkedMapOf("甲乙丙丁" to "A", "甲乙戊己" to "B")
        val reversed = forward.reversedOrder()
        assertNull(NameMatcher.match("甲丙戊", forward))
        assertNull(NameMatcher.match("甲丙戊", reversed))
        // 唯一最大 + 超阈值仍命中（Dice 层是被单测守护的有效能力，不得误伤）：
        // 同输入对唯一键：LCS 1 <2、编辑距离 2 超阈值、Dice=2*2/7≈0.571 ≥0.55 → DICE 命中
        val solo = linkedMapOf("甲乙丙丁" to "SoloHit")
        val soloHit = NameMatcher.match("甲丙戊", solo)
        assertEquals("SoloHit", soloHit?.key)
        assertEquals(NameMatcher.Tier.DICE, soloHit?.tier)
    }

    // ---------- 真实词典用例（模拟器实测错字对）----------
    private fun names(): GoodNames = GoodNames.fromJson(dictJson())

    private fun dictJson(): JSONObject {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val candidate = File(dir, "src/main/assets/dsl")
            if (candidate.isDirectory) {
                return JSONObject(File(candidate, "tools/mappings.json").readText())
            }
            dir = dir.parentFile ?: return@repeat
        }
        error("src/main/assets/dsl not found")
    }

    /**
     * 2026-10-02 起这 9 套低星套已由 `gen_mappings.py` B' 段升为正式套装词典条目
     * （此前 GOOD 只列 4★/5★，这 9 套只存在于单件名段 ⇒ 套装筛选页「祭火之人」「冒险家」
     * 等行整页 key=null）。留这份名单当"低星段必须真的在词典里"的哨兵。
     */
    private val lowRaritySetIds = setOf(
        "Adventurer", "LuckyDog", "TinyMiracle", "TravelingDoctor",
        "PrayersForDestiny", "PrayersForIllumination", "PrayersForWisdom",
        "PrayersToSpringtime", "PrayersToTheFirmament",
    )

    @Test
    fun `dictionary counts stay in the recorded range`() {
        val n = names()
        // 条数下限取自 2026-10-02 实测（123/242/65/305）；`mappings.json` 里没有 `_meta`
        // 可以自证（gen_mappings.py 是 minified 输出，加日期字段会让"双侧逐字节一致"天天红），
        // 所以这道闸只能由测试侧记数。真缩水时先在这里响，而不是等到对账少件。
        assertTrue("characters ${n.characters.size} < 120", n.characters.size >= 120)
        assertTrue("weapons ${n.weapons.size} < 240", n.weapons.size >= 240)
        assertTrue("artifactPieces ${n.pieces.size} < 300", n.pieces.size >= 300)
        assertEquals("artifactSets 恒 65 套（GOOD 56 + B' 段低星 9）", 65, n.sets.size)
        assertEquals("slots", 5, n.slots.size)
        // 低星 9 套必须真的进套装段（见 [lowRaritySetIds] 的来历），且单件名段 ≥25 不回缩
        assertTrue(
            "低星 9 套必须都在 artifactSets 里（缺：${lowRaritySetIds - n.sets.values.toSet()}）",
            n.sets.values.containsAll(lowRaritySetIds),
        )
        val lowPieces = n.pieces.values.filter { it in lowRaritySetIds }
        assertTrue("3★/4★ 低星单件名段不得为空（实测 ≥25）", lowPieces.size >= 25)
        assertTrue("stats should include aliases", n.stats.size >= 16)
    }

    /**
     * 每件单件名必须指向套装词典里真实存在的套。2026-10-02 低星 9 套升级后，
     * 单件名段与套装段的集合差必须为空 —— 多出来的那个就是上游表变了要复核的信号。
     */
    @Test
    fun `every piece maps to a known artifact set`() {
        val n = names()
        val setIds = n.sets.values.toSet()
        val unknown = n.pieces.values.toSet() - setIds
        assertTrue(
            "单件名段出现套装词典之外的套 $unknown（多了=上游变了要复核）",
            unknown.isEmpty(),
        )
    }

    @Test
    fun `low star sets resolve from their filter page names`() {
        val n = names()
        // 2026-10-02 B' 段收录前，这几行在真机筛选页整页 key=null（20261002_0046 基线）
        assertEquals("PrayersForIllumination", n.match("祭火之人", GoodNames.Kind.SET)?.key)
        assertEquals("PrayersToSpringtime", n.match("祭冰之人", GoodNames.Kind.SET)?.key)
        assertEquals("PrayersToTheFirmament", n.match("祭风之人", GoodNames.Kind.SET)?.key)
        assertEquals("Adventurer", n.match("冒险家", GoodNames.Kind.SET)?.key)
        assertEquals("TinyMiracle", n.match("奇迹", GoodNames.Kind.SET)?.key)
    }

    @Test
    fun `real ocr typos match the correct entity`() {
        val n = names()
        // 单件名错字（模拟器 3200x1440 实测）
        assertEquals("谕告之钟", "NightOfTheSkysUnveiling", n.match("渝告之钟", GoodNames.Kind.PIECE)?.key)
        assertEquals("深廊的回奏之歌", "FinaleOfTheDeepGalleries", n.match("深廊的回秦之歌", GoodNames.Kind.PIECE)?.key)
        // 武器名错字
        assertEquals("万国诸海图谱", "MappaMare", n.match("万国诺海图谱", GoodNames.Kind.WEAPON)?.key)
        assertEquals("风信之锋", "MissiveWindspear", n.match("风信之鋒", GoodNames.Kind.WEAPON)?.key)
        // 词典 2026-09-27 才补进 SilverLight（上游 mappings.json 停在 7.0），且面板把「釭」读成同音的「缸」
        // ⇒ 两个缺口必须同时成立才不会漏件（模拟器 3200 实测原文，runlog 20260926_bs3200_planC:99076）
        assertEquals("银釭", "SilverLight", n.match("银缸", GoodNames.Kind.WEAPON)?.key)
    }

    @Test
    fun `garbage text does not fuzzy match`() {
        val n = names()
        val garbage = listOf(
            "認的条名浩成t的佐主類合坦4の",
            "哈哈哈呵呵呵",
            "12345678",
            "gfdskjhqwasdf",
        )
        for (g in garbage) {
            assertNull("garbage '$g' should not match pieces", n.match(g, GoodNames.Kind.PIECE))
            assertNull("garbage '$g' should not match characters", n.match(g, GoodNames.Kind.CHARACTER))
            assertNull("garbage '$g' should not match weapons", n.match(g, GoodNames.Kind.WEAPON))
            assertNull("garbage '$g' should not match sets", n.match(g, GoodNames.Kind.SET))
        }
    }

    @Test
    fun `lcs 2 treats a unique bigram as confident per reference tier5 semantics`() {
        // 原 garbage 用例成员「回gfdskjh秦之歌」：全 305 个单件名里「之歌」只出现在
        // 「深廊的回奏之歌」——按 fuzzy_match.rs 第 5 层的设计（唯一公共子串 ⇒ 置信命中）
        // 它**应该**命中；LCS_MIN=3 时代恰好判 null，降到 2 后行为改为正向记录在这里。
        val n = names()
        val hit = n.match("回gfdskjh秦之歌", GoodNames.Kind.PIECE)
        assertEquals("FinaleOfTheDeepGalleries", hit?.key)
        assertEquals(NameMatcher.Tier.LCS, hit?.tier)
    }

    // ---------- 确定性扰动压测（防阈值/分级日后漂移）----------
    private data class Stress(val kind: GoodNames.Kind, val table: Map<String, String>, val floor: Double)

    @Test
    fun `perturbation accuracy stays above measured floor`() {
        val n = names()
        // 下限 = 本实现实测值 - ~2.5pt 余量（种子固定，结果可复现）
        val targets = listOf(
            Stress(GoodNames.Kind.PIECE, n.pieces, 0.95),
            Stress(GoodNames.Kind.WEAPON, n.weapons, 0.89),
            Stress(GoodNames.Kind.CHARACTER, n.characters, 0.86),
            Stress(GoodNames.Kind.SET, n.sets, 0.91),
        )
        val pool = targets.flatMap { it.table.keys }.flatMap { it.toList() }.distinct()
        val rnd = Random(20260905) // 固定种子：结果可复现
        var totalOk = 0
        var total = 0
        val report = ArrayList<String>()
        for (target in targets) {
            var ok = 0
            var n2 = 0
            for ((name, key) in target.table) {
                repeat(4) {
                    val p = perturb(name, pool, rnd)
                    if (p == name || p.length < 2) return@repeat
                    n2++
                    if (NameMatcher.match(p, target.table)?.key == key) ok++
                }
            }
            val acc = ok.toDouble() / n2
            totalOk += ok
            total += n2
            report += "%-10s n=%-5d acc=%.1f%% (floor %.0f%%)".format(target.kind, n2, acc * 100, target.floor * 100)
            assertTrue(
                "${target.kind} 准确率回退到 ${"%.1f".format(acc * 100)}%（下限 ${"%.0f".format(target.floor * 100)}%）\n${report.joinToString("\n")}",
                acc >= target.floor,
            )
        }
        val overall = totalOk.toDouble() / total
        println("扰动压测：\n" + report.joinToString("\n") + "\n合计 %.1f%% (n=$total)".format(overall * 100))
        assertTrue("合计准确率回退到 ${"%.1f".format(overall * 100)}%", overall >= 0.92)
    }

    /** 模拟 OCR 扰动：替换 1~2 字 / 删字 / 插字 / 截断。 */
    private fun perturb(name: String, pool: List<Char>, rnd: Random): String {
        val s = name.toCharArray().toMutableList()
        when (rnd.nextInt(5)) {
            0, 1 -> { // 替换（35% 概率双字错）
                s[rnd.nextInt(s.size)] = pool[rnd.nextInt(pool.size)]
                if (rnd.nextDouble() < 0.35) s[rnd.nextInt(s.size)] = pool[rnd.nextInt(pool.size)]
            }
            2 -> if (s.size > 2) s.removeAt(rnd.nextInt(s.size))
            3 -> s.add(rnd.nextInt(s.size + 1), pool[rnd.nextInt(pool.size)])
            else -> if (s.size > 2) return s.take(s.size - 1).joinToString("")
        }
        return s.joinToString("")
    }

    // ---------- 归一化与算法组件 ----------
    @Test
    fun `normalize strips punctuation and keeps cjk alnum`() {
        assertEquals("神里绫华", NameMatcher.normalize("·神里绫华·"))
        assertEquals("暴击率58%", NameMatcher.normalize("暴击率+5.8%"))
        assertEquals("abc123", NameMatcher.normalize("ａｂｃ１２３"))
        assertTrue(NameMatcher.normalize("！！！").isEmpty())
    }

    @Test
    fun `levenshtein and lcs helpers behave`() {
        assertEquals(0, NameMatcher.levenshtein("重云", "重云"))
        assertEquals(1, NameMatcher.levenshtein("里云", "重云"))
        assertEquals(2, NameMatcher.longestCommonSubstring("海染碟", "海染砗磲"))
        assertEquals(0, NameMatcher.longestCommonSubstring("abc", "xyz"))
        // 渝告之钟 vs 谕告之钟：交集 {告,之,钟}=3 → 2*3/(4+4)=0.75
        assertEquals(0.75, NameMatcher.unigramDice("渝告之钟", "谕告之钟"), 1e-6)
    }

    @Test
    fun `match result carries tier for diagnostics`() {
        val n = names()
        val exact = n.match("谕告之钟", GoodNames.Kind.PIECE)
        assertNotNull(exact)
        assertEquals(NameMatcher.Tier.EXACT, exact!!.tier)
        val fuzzy = n.match("渝告之钟", GoodNames.Kind.PIECE)
        assertNotNull(fuzzy)
        assertTrue("should not be exact: ${fuzzy!!.tier}", fuzzy.tier != NameMatcher.Tier.EXACT)
    }
}
