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
}
