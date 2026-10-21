package com.bettergi.pocket.recognition.name

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 角色静态属性（`e`/`wt`）——弹层「沙漏」预筛的唯一数据源（#125）。
 *
 * 缺了这一段，rosterFind 只会静默退回全量遍历（提速失效但不出错），所以这里守的是
 * **生成流水线**而不是运行时：`gen_mappings.py` 一旦不再输出 wt，
 * 本用例立刻红。
 */
class GoodNamesCharAttrsTest {

    private fun names(json: String) = GoodNames.fromJson(JSONObject(json))

    private fun dict(
        characters: String,
        pieces: String = """[{"piece":"测试花","setId":"Test"}]""",
        elements: String = """[{"key":"dendro","good":"Dendro","zh":"草"},{"key":"anemo","good":"Anemo","zh":"风"}]""",
    ) = """{"characters":$characters,"weapons":[],"artifactSets":[],"artifactPieces":$pieces,
        "stats":[],"slots":[],"elements":$elements}"""

    @Test
    fun `element and weapon type are exposed per character id`() {
        val n = names(
            dict(
                """[{"id":"Alhaitham","r":5,"e":"dendro","wt":"sword","n":{"zh":"艾尔海森"}}]""",
            ),
        )
        assertEquals("dendro", n.charAttrs["Alhaitham"]?.element)
        assertEquals("sword", n.charAttrs["Alhaitham"]?.weapon)
    }

    @Test
    fun `missing attributes read as null rather than empty string`() {
        val n = names(dict("""[{"id":"X","r":4,"n":{"zh":"某"}}]"""))
        assertNull(n.charAttrs["X"]?.element)
        assertNull(n.charAttrs["X"]?.weapon)
    }

    /**
     * 旅行者系/奇偶 的词典元素恒是 `anemo`，而游戏里他们**跟着旅行者的当前元素实时变**
     * （2026-09-29 实测页头「草元素 / 崽崽」）。ScanEngine 靠 id 前缀把他们排除在预筛之外，
     * 这里钉住"词典确实是静态 anemo"这一前提——若哪天生成器改成逐元素展开，前缀判据就该重估。
     */
    @Test
    fun `traveler family stays statically anemo in the dictionary`() {
        val n = names(
            dict(
                """[{"id":"Traveler","r":5,"e":"anemo","wt":"sword","n":{"zh":"旅行者"}},""" +
                    """{"id":"Manekin","r":5,"e":"anemo","wt":"sword","n":{"zh":"奇偶·男性"}}]""",
            ),
        )
        assertEquals("anemo", n.charAttrs["Traveler"]?.element)
        assertEquals("anemo", n.charAttrs["Manekin"]?.element)
    }

    /**
     * 随包词典必须带 `c3`/`c5`（第 3/5 层命座抬哪一行天赋）—— #155 的唯一数据源。
     * 少了它，运行时只能退回"按命座档近似"，而绫华/莫娜/琴 方向相反 ⇒ 成片少减 3。
     * 旅行者系与奇偶确实没有这两个键（旅行者命座按元素各有一套、奇偶无命座系统），故按人数下限守。
     */
    @Test
    fun `shipped dictionary carries constellation talent rows`() {
        val file = File("src/main/assets/dsl/tools/mappings.json")
        assertTrue("找不到随包词典 ${file.absolutePath}", file.isFile)
        val arr = JSONObject(file.readText()).getJSONArray("characters")
        val withBonus = (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .count { it.optString("c3").isNotEmpty() || it.optString("c5").isNotEmpty() }
        assertTrue("带 c3/c5 的角色过少($withBonus/${arr.length()}) ⇒ 生成流水线又把它们丢了", withBonus >= 110)
    }

    /** 随包词典必须每个角色都带 wt，否则预筛对那个角色永久失效且无人报错。 */
    @Test
    fun `shipped dictionary carries weapon type for every character`() {
        val file = File(
            "src/main/assets/dsl/tools/mappings.json",
        )
        assertTrue("找不到随包词典 ${file.absolutePath}", file.isFile)
        val arr = JSONObject(file.readText()).getJSONArray("characters")
        assertTrue("角色条目过少(${arr.length()})", arr.length() > 100)
        val missing = (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .filter { it.optString("wt").isEmpty() || it.optString("e").isEmpty() }
            .map { it.getString("id") }
        assertTrue("词典缺 e/wt 的角色: $missing", missing.isEmpty())
    }

    /**
     * #167：导出侧的元素**不再从 OCR 读**，改成按 key 查字典 ⇒ 字典必须给得出 GOOD 写法。
     * 触发样本：22:01 轮 Columbina 页头读成 `'永元素/…'`，element 就这么带着错字入库了。
     */
    @Test
    fun `element lookup resolves through the dictionary closed set`() {
        val n = names(
            dict(
                """[{"id":"Columbina","r":5,"e":"hydro","wt":"catalyst","n":{"zh":"哥伦比娅"}}]""",
                elements = """[{"key":"hydro","good":"Hydro","zh":"水"}]""",
            ),
        )
        assertEquals("Hydro", n.elementOf("Columbina"))
        assertEquals("Hydro", n.elementByZh["水"])
        assertNull("字典里没有的角色不给元素", n.elementOf("Nope"))
        assertNull("没解析出 key ⇒ 不给元素（调用方回退页头 OCR）", n.elementOf(null))
    }

    /**
     * 随包词典的 element 段必须齐 7 个、且**每个角色**的 `e` 都查得到 GOOD 写法。
     * 少一个元素 ⇒ 那一整列角色静默导出 null（不是少一条，是少一列），所以逐角色验。
     */
    @Test
    fun `shipped dictionary resolves every character element to a good name`() {
        val file = File("src/main/assets/dsl/tools/mappings.json")
        val root = JSONObject(file.readText())
        val els = root.getJSONArray("elements")
        assertEquals("七元素闭集", 7, els.length())
        val good = HashMap<String, String>()
        for (i in 0 until els.length()) {
            val o = els.getJSONObject(i)
            good[o.getString("key")] = o.getString("good")
            assertTrue("元素缺中文名", o.getString("zh").isNotEmpty())
        }
        val chars = root.getJSONArray("characters")
        val unresolved = ArrayList<String>()
        for (i in 0 until chars.length()) {
            val o = chars.getJSONObject(i)
            if (good[o.optString("e")] == null) unresolved += o.getString("id")
        }
        assertTrue("这些角色的 e 在 elements 里查不到 GOOD 写法: $unresolved", unresolved.isEmpty())
    }
}
