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

    // ---------- 真实词典用例（模拟器实测错字对）----------
    private fun names(): GoodNames {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val candidate = File(dir, "src/main/assets/dsl")
            if (candidate.isDirectory) {
                return GoodNames.fromJson(JSONObject(File(candidate, "tools/good_names.json").readText()))
            }
            dir = dir.parentFile ?: return@repeat
        }
        error("src/main/assets/dsl not found")
    }

    @Test
    fun `dictionary counts match generated meta`() {
        val n = names()
        assertEquals(276, n.pieces.size)
        assertEquals(236, n.weapons.size)
        assertEquals(121, n.characters.size)
        assertEquals(56, n.sets.size)
        assertEquals(5, n.slots.size)
        assertTrue("stats should include aliases", n.stats.size >= 16)
    }

    @Test
    fun `every piece maps to a known artifact set`() {
        val n = names()
        val setIds = n.sets.values.toSet()
        for ((piece, setId) in n.pieces) {
            assertTrue("piece '$piece' → unknown set '$setId'", setId in setIds)
        }
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
    }

    @Test
    fun `garbage text does not fuzzy match`() {
        val n = names()
        val garbage = listOf(
            "認的条名浩成t的佐主類合坦4の",
            "回gfdskjh秦之歌",
            "哈哈哈呵呵呵",
            "12345678",
        )
        for (g in garbage) {
            assertNull("garbage '$g' should not match pieces", n.match(g, GoodNames.Kind.PIECE))
            assertNull("garbage '$g' should not match characters", n.match(g, GoodNames.Kind.CHARACTER))
            assertNull("garbage '$g' should not match weapons", n.match(g, GoodNames.Kind.WEAPON))
            assertNull("garbage '$g' should not match sets", n.match(g, GoodNames.Kind.SET))
        }
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
