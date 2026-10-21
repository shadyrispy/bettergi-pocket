package com.bettergi.pocket.scan

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** GOOD v3 导出结构验证（buildGoodJson 纯函数，dry-run 产物直测）。 */
class GoodExporterTest {

    @Test
    fun `good json structure matches v3`() {
        val artifacts = listOf(
            GoodArtifact(
                setKey = "WindbearerFool",
                slotKey = "plume",
                level = 20,
                rarity = 5,
                mainStatKey = "atk_",
                mainStatValue = 46.6,
                substats = listOf(
                    GoodSubStat("critRate_", 5.8),
                    GoodSubStat("hp", 717.0),
                ),
                lock = true,
                favorited = true,
            ),
            GoodArtifact(null, null, 0, 3, null, 0.0, emptyList(), false, null),
        )
        val root = GoodExporter.buildGoodJson(artifacts)
        assertEquals("GOOD", root.getString("format"))
        assertEquals(3, root.getInt("version"))
        assertEquals("BetterGIPocket", root.getString("source"))
        assertEquals(2, root.getJSONArray("artifacts").length())

        val first = root.getJSONArray("artifacts").getJSONObject(0)
        assertEquals("WindbearerFool", first.getString("setKey"))
        assertEquals("plume", first.getString("slotKey"))
        assertEquals(20, first.getInt("level"))
        assertEquals(5, first.getInt("rarity"))
        assertEquals("atk_", first.getString("mainStatKey"))
        assertEquals(46.6, first.getDouble("mainStatValue"), 1e-9)
        assertTrue(first.getBoolean("lock"))
        assertEquals(2, first.getJSONArray("substats").length())
        assertEquals("critRate_", first.getJSONArray("substats").getJSONObject(0).getString("key"))
        assertEquals(5.8, first.getJSONArray("substats").getJSONObject(0).getDouble("value"), 1e-9)

        // 词典缺失件：空字符串占位（导出不崩）
        val second = root.getJSONArray("artifacts").getJSONObject(1)
        assertEquals("", second.getString("setKey"))
        assertEquals(0, second.getJSONArray("substats").length())
    }

    /**
     * A39（#156）：未解析身份**不得导出 key=""** —— 空串 key 会让下游（对账/归并按 key 分组）
     * 把多条未知记录并成一条。修法 = key 未知时**不写 key 字段**（null 缺省，不破坏 GOOD v3 契约：
     * 消费端把「缺 key」当未知处理），已解析身份照常写。
     */
    @Test
    fun `unresolved identity omits key field instead of empty string`() {
        val chars = listOf(
            GoodCharacter(key = null, name = "随机姓", level = 80, element = null),
            GoodCharacter(key = null, name = "随机人", level = 70, element = "Cryo"),
            GoodCharacter(key = "Amber", name = "Amber", level = 70, element = "Pyro"),
        )
        val root = GoodExporter.buildGoodJson(emptyList(), emptyList(), chars)
        val arr = root.getJSONArray("characters")
        assertEquals(3, arr.length())
        for (i in 0..1) {
            val o = arr.getJSONObject(i)
            assertTrue(
                "未知身份记录 #$i 不得携带 key 字段（旧实现写空串 ⇒ 下游并条）",
                !o.has("key"),
            )
            // rawName 保留在 name（人工可核对、去管理器页补昵称）
            assertTrue(o.getString("name").isNotEmpty())
        }
        assertEquals("Amber", arr.getJSONObject(2).getString("key"))

        // 武器侧同口径（生产上 key=null 的武器在 parseWeaponPanel 就不入库，导出侧双保险）
        val weapons = listOf(
            GoodWeapon(key = null, level = 90, rarity = 5, refine = 1, lock = false),
            GoodWeapon(key = "SwordOfDescension", level = 90, rarity = 5, refine = 1, lock = false),
        )
        val wroot = GoodExporter.buildGoodJson(emptyList(), weapons)
        val warr = wroot.getJSONArray("weapons")
        assertTrue("未知身份武器不得携带 key 字段", !warr.getJSONObject(0).has("key"))
        assertEquals("SwordOfDescension", warr.getJSONObject(1).getString("key"))
    }
}
