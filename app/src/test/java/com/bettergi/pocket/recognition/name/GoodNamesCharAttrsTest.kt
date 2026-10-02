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
 * **生成流水线**而不是运行时：`gen_mappings.py` / `gen_good_names.py` 一旦不再输出 wt，
 * 本用例立刻红。
 */
class GoodNamesCharAttrsTest {

    private fun names(json: String) = GoodNames.fromJson(JSONObject(json))

    private fun dict(characters: String, pieces: String = """[{"piece":"测试花","setId":"Test"}]""") =
        """{"characters":$characters,"weapons":[],"artifactSets":[],"artifactPieces":$pieces,"stats":[],"slots":[]}"""

    @Test
    fun `element and weapon type are exposed per character id`() {
        val n = names(
            dict(
                """[{"id":"Alhaitham","r":5,"e":"dendro","wt":"sword","zh":"艾尔海森"}]""",
            ),
        )
        assertEquals("dendro", n.charAttrs["Alhaitham"]?.element)
        assertEquals("sword", n.charAttrs["Alhaitham"]?.weapon)
    }

    @Test
    fun `missing attributes read as null rather than empty string`() {
        val n = names(dict("""[{"id":"X","r":4,"zh":"某"}]"""))
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
                """[{"id":"Traveler","r":5,"e":"anemo","wt":"sword","zh":"旅行者"},""" +
                    """{"id":"Manekin","r":5,"e":"anemo","wt":"sword","zh":"奇偶·男性"}]""",
            ),
        )
        assertEquals("anemo", n.charAttrs["Traveler"]?.element)
        assertEquals("anemo", n.charAttrs["Manekin"]?.element)
    }

    /** 随包词典必须每个角色都带 wt，否则预筛对那个角色永久失效且无人报错。 */
    @Test
    fun `shipped dictionary carries weapon type for every character`() {
        val file = File(
            "src/main/assets/dsl/tools/good_names.json",
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
}
