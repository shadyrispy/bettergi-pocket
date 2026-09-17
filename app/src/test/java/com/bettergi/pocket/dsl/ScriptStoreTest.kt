package com.bettergi.pocket.dsl

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P3：脚本清单条目解析（纯函数部分）。 */
class ScriptStoreTest {

    @Test
    fun parseEntry_readsUiFields() {
        val json = JSONObject("""{"flow":"artifact_scan","steps":[{"do":"click"}],"ui":{"label":"圣遗物扫描","icon":"artifact","order":10}}""")
        val e = ScriptStore.parseEntry(json, "artifact_scan", imported = false, enabled = true)
        assertEquals("artifact_scan", e.key)
        assertEquals("圣遗物扫描", e.label)
        assertEquals("artifact", e.icon)
        assertEquals(10, e.order)
        assertTrue(e.enabled)
        assertFalse(e.imported)
        assertTrue(e.issues.isEmpty())
        assertTrue("声明了 ui ⇒ 应上悬浮窗", e.hasUi)
    }

    @Test
    fun parseEntry_noUi_fallsBackToKeyAndGear_lastOrder() {
        val json = JSONObject("""{"flow":"x","steps":[{"do":"click"}]}""")
        val e = ScriptStore.parseEntry(json, "x", imported = true, enabled = false)
        assertEquals("x", e.label)
        assertEquals("gear", e.icon)
        assertEquals(Int.MAX_VALUE, e.order)
        assertFalse(e.enabled)
        assertTrue(e.imported)
        assertFalse("未声明 ui ⇒ 不上悬浮窗", e.hasUi)
    }

    @Test
    fun parseEntry_reportsIssues_notSilently_dropped() {
        // 缺 steps + icon 非法 + color 非法 ⇒ 三条 issue 都要能上报到管理界面
        val json = JSONObject("""{"ui":{"label":"x","icon":"nope","color":"7C4DFF"}}""")
        val e = ScriptStore.parseEntry(json, "bad", imported = true, enabled = true)
        assertTrue("应报 steps 缺失", e.issues.any { it.contains("steps") })
        assertTrue("应报 icon 非法", e.issues.any { it.contains("ui.icon") })
        assertTrue("应报 color 非法", e.issues.any { it.contains("ui.color") })
    }
}
