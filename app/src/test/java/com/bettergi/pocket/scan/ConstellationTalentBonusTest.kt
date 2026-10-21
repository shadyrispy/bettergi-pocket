package com.bettergi.pocket.scan

import com.bettergi.pocket.recognition.name.GoodNames
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 命座天赋加成扣减（#155）。
 *
 * 守两件事：
 * 1. **与已删的 GT 监督手表等价** —— 那 51 条是"本账号当时那一档命座"下减对了的量，
 *    新的数据驱动规则必须在同样的 (角色, 命座档) 上给出同样的扣减量；
 * 2. **手表覆盖不到的档不再靠近似** —— 手表换个命座档就查不到，旧代码退到
 *    「c≥3⇒E、c≥5⇒Q」，而绫华 / 莫娜 / 琴 是 c3=Q、c5=E，方向正好相反。
 */
class ConstellationTalentBonusTest {

    private val dict: GoodNames by lazy {
        val f = File("src/main/assets/dsl/tools/mappings.json")
        assertTrue("找不到随包词典 ${f.absolutePath}", f.isFile)
        GoodNames.fromJson(JSONObject(f.readText()))
    }

    private fun attrsOf(key: String) = dict.charAttrs.getValue(key)

    /** 喂 [10,10,10]（三行都够减、不触下限、不报可疑），只看**扣了哪几行多少**。 */
    private fun bonusOf(key: String, c: Int): IntArray =
        applyConstellationTalentBonus(mutableListOf(10, 10, 10), key, attrsOf(key), c).first

    /** 旧手表（`ScanEngine.TALENT_BONUS`，本次改动之前）的 51 条，逐条回放。 */
    private val legacyTable = listOf(
        Triple("Aino", 6, intArrayOf(0, 3, 3)),
        Triple("Alyosha", 3, intArrayOf(0, 3, 0)),
        Triple("Barbara", 6, intArrayOf(0, 3, 3)),
        Triple("Beidou", 6, intArrayOf(0, 3, 3)),
        Triple("Bennett", 6, intArrayOf(0, 3, 3)),
        Triple("Candace", 6, intArrayOf(0, 3, 3)),
        Triple("Charlotte", 6, intArrayOf(0, 3, 3)),
        Triple("Chevreuse", 3, intArrayOf(0, 3, 0)),
        Triple("Chongyun", 5, intArrayOf(0, 3, 3)),
        Triple("Collei", 6, intArrayOf(0, 3, 3)),
        Triple("Dahlia", 6, intArrayOf(0, 3, 3)),
        Triple("Dehya", 3, intArrayOf(0, 0, 3)),
        Triple("Diluc", 5, intArrayOf(0, 3, 3)),
        Triple("Diona", 6, intArrayOf(0, 3, 3)),
        Triple("Dori", 6, intArrayOf(0, 3, 3)),
        Triple("Faruzan", 6, intArrayOf(0, 3, 3)),
        Triple("Fischl", 6, intArrayOf(0, 3, 3)),
        Triple("Freminet", 6, intArrayOf(3, 3, 0)),
        Triple("Gaming", 6, intArrayOf(0, 3, 3)),
        Triple("Gorou", 6, intArrayOf(0, 3, 3)),
        Triple("Ifa", 4, intArrayOf(0, 3, 0)),
        Triple("Illuga", 6, intArrayOf(0, 3, 3)),
        Triple("Jahoda", 6, intArrayOf(0, 3, 3)),
        Triple("Kachina", 4, intArrayOf(0, 3, 0)),
        Triple("Kaeya", 3, intArrayOf(0, 3, 0)),
        Triple("Kaveh", 3, intArrayOf(0, 0, 3)),
        Triple("Keqing", 3, intArrayOf(0, 0, 3)),
        Triple("Kirara", 3, intArrayOf(0, 3, 0)),
        Triple("KujouSara", 6, intArrayOf(0, 3, 3)),
        Triple("KukiShinobu", 6, intArrayOf(0, 3, 3)),
        Triple("LanYan", 3, intArrayOf(0, 3, 0)),
        Triple("Layla", 6, intArrayOf(0, 3, 3)),
        Triple("Lynette", 6, intArrayOf(0, 3, 3)),
        Triple("Mona", 6, intArrayOf(0, 3, 3)),
        Triple("Ningguang", 4, intArrayOf(0, 0, 3)),
        Triple("Noelle", 6, intArrayOf(0, 3, 3)),
        Triple("Prune", 3, intArrayOf(0, 0, 3)),
        Triple("Razor", 6, intArrayOf(0, 3, 3)),
        Triple("Rosaria", 6, intArrayOf(0, 3, 3)),
        Triple("Sayu", 6, intArrayOf(0, 3, 3)),
        Triple("Sethos", 6, intArrayOf(3, 0, 3)),
        Triple("ShikanoinHeizou", 3, intArrayOf(0, 3, 0)),
        Triple("Sucrose", 6, intArrayOf(0, 3, 3)),
        Triple("Tartaglia", 0, intArrayOf(1, 0, 0)),
        Triple("Thoma", 6, intArrayOf(0, 3, 3)),
        Triple("Xiangling", 6, intArrayOf(0, 3, 3)),
        Triple("Xingqiu", 6, intArrayOf(0, 3, 3)),
        Triple("Xinyan", 6, intArrayOf(0, 3, 3)),
        Triple("Yanfei", 6, intArrayOf(0, 3, 3)),
        Triple("Yaoyao", 3, intArrayOf(0, 3, 0)),
        Triple("YunJin", 6, intArrayOf(0, 3, 3))
    )

    @Test
    fun `数据驱动规则逐位复现已删的 GT 监督手表`() {
        val diff = legacyTable.map { (k, c, want) ->
            val got = bonusOf(k, c)
            if (got.toList() == want.toList()) null else "$k c$c want=${want.toList()} got=${got.toList()}"
        }.filterNotNull()
        assertTrue("与旧手表不一致：$diff", diff.isEmpty())
    }

    @Test
    fun `c3 抬元素爆发的角色不再被近似规则减错行`() {
        // 神里绫华 c3=Q / c5=E：只到 3 层时该减 burst，而不是 skill。旧近似规则给 [0,3,0]，两头都错。
        assertEquals("Q", attrsOf("KamisatoAyaka").c3)
        assertEquals("E", attrsOf("KamisatoAyaka").c5)
        assertEquals(listOf(0, 0, 3), bonusOf("KamisatoAyaka", 3).toList())
        assertEquals(listOf(0, 3, 3), bonusOf("KamisatoAyaka", 6).toList())
    }

    @Test
    fun `达达利亚的普攻加一与命座档无关`() {
        // 行序 = [A 普攻, E 元素战技, Q 元素爆发]；达达利亚 c3=E、c5=Q。
        // 旧表把 +1 塞进 c=0 那一格 ⇒ 达达利亚一旦有命座就丢掉这个 +1。
        assertEquals(listOf(1, 0, 0), bonusOf("Tartaglia", 0).toList())
        assertEquals(listOf(1, 3, 3), bonusOf("Tartaglia", 6).toList())
    }

    @Test
    fun `没读到的行不参与扣减`() {
        // 0 = 这一行没读到（不是读到 0）⇒ 不减，否则凭空造出等级。
        // 绫华 c3=Q / c5=E ⇒ c=6 时 E、Q 两行各减 3，普攻那行（没读到）保持 0。
        val talents = mutableListOf(0, 10, 10)
        val (bonus, _) = applyConstellationTalentBonus(talents, "KamisatoAyaka", attrsOf("KamisatoAyaka"), 6)
        assertEquals(listOf(0, 3, 3), bonus.toList())
        assertEquals(listOf(0, 7, 7), talents.toList())
    }

    @Test
    fun `该减加成的行读数过低时报可疑而不静默`() {
        // 绫华 c=3 抬的是 Q（下标 2）⇒ 把那行读成 3 就该报可疑。
        val (_, susLow) = applyConstellationTalentBonus(
            mutableListOf(10, 10, 3), "KamisatoAyaka", attrsOf("KamisatoAyaka"), 3,
        )
        assertTrue("burst 行读数 3 却该减 3 ⇒ 应报可疑", susLow)
        val (_, susOk) = applyConstellationTalentBonus(
            mutableListOf(10, 10, 10), "KamisatoAyaka", attrsOf("KamisatoAyaka"), 3,
        )
        assertFalse(susOk)
    }

    @Test
    fun `旅行者按元素后缀归一后仍能查到词条`() {
        // 词典只有一个 Traveler 条目；readTalent 侧把 TravelerDendro 归一成 Traveler 才查得到。
        assertFalse(dict.charAttrs.containsKey("TravelerDendro"))
        assertTrue(dict.charAttrs.containsKey("Traveler"))
        // c≥5 ⇒ E/Q 各减 3；不足 5 层时只有超过基础上限 10 的行才可能含加成。
        val t5 = mutableListOf(10, 13, 12)
        assertEquals(
            listOf(0, 3, 3),
            applyConstellationTalentBonus(t5, "Traveler", attrsOf("Traveler"), 5).first.toList(),
        )
        assertEquals(listOf(10, 10, 9), t5.toList())
        val t2 = mutableListOf(10, 10, 13)
        assertEquals(
            listOf(0, 0, 3),
            applyConstellationTalentBonus(t2, "Traveler", attrsOf("Traveler"), 2).first.toList(),
        )
        val tLow = mutableListOf(10, 8, 8)
        assertEquals(
            listOf(0, 0, 0),
            applyConstellationTalentBonus(tLow, "Traveler", attrsOf("Traveler"), 2).first.toList(),
        )
    }

    @Test
    fun `无命座系统的角色不减任何一行`() {
        for (k in listOf("Manekin", "Manekina")) {
            assertEquals("$k 不该有加成", listOf(0, 0, 0), bonusOf(k, 6).toList())
        }
    }
}
