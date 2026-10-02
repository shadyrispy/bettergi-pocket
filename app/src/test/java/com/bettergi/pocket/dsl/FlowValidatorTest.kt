package com.bettergi.pocket.dsl

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowValidatorTest {

    @Test
    fun parseInfo_v10_compat() {
        val json = JSONObject("""{"flow":"x","version":"1.0.0","steps":[]}""")
        val info = FlowValidator.parseInfo(json, "x")
        assertEquals("x", info.name)
        assertEquals("0.0.0", info.minHostVersion)
        assertEquals("pocket-script", info.type)
    }

    @Test
    fun parseInfo_v11() {
        val json = JSONObject(
            """{"info":{"name":"weapon_scan","version":2,"min_host_version":"1.2.0","type":"pocket-script"},"steps":[]}""",
        )
        val info = FlowValidator.parseInfo(json, "fallback")
        assertEquals("weapon_scan", info.name)
        assertEquals(2, info.version)
        assertEquals("1.2.0", info.minHostVersion)
    }

    @Test
    fun hostSatisfies_bounds() {
        assertTrue(FlowValidator.hostSatisfies("0.0.0", "1.0"))
        assertTrue(FlowValidator.hostSatisfies("1.0.0", "1.0"))
        assertTrue(FlowValidator.hostSatisfies("1.0", "1.0"))
        assertFalse(FlowValidator.hostSatisfies("2.0.0", "1.0"))
        assertFalse(FlowValidator.hostSatisfies("1.5.0", "1.0"))
        assertTrue(FlowValidator.hostSatisfies("1.0.0", "1.0.3"))
    }

    @Test
    fun validate_ok() {
        val json = JSONObject(
            """{"steps":[{"do":"enterScreen"},{"do":"pagedGrid","visit":[{"do":"click"}]}]}""",
        )
        assertTrue(FlowValidator.validate(json).isEmpty())
    }

    @Test
    fun validate_missingSteps() {
        val json = JSONObject("""{"flow":"x"}""")
        val issues = FlowValidator.validate(json)
        assertEquals(1, issues.size)
        assertEquals("steps", issues[0].path)
    }

    @Test
    fun validate_stepWithoutDo() {
        val json = JSONObject("""{"steps":[{"target":"a"},{"do":"click"}]}""")
        val issues = FlowValidator.validate(json)
        assertEquals(1, issues.size)
        assertEquals(0, issues[0].stepIndex)
        assertTrue(issues[0].path.contains("do"))
    }

    @Test
    fun validate_emptySteps() {
        val json = JSONObject("""{"steps":[]}""")
        val issues = FlowValidator.validate(json)
        assertEquals(1, issues.size)
        assertEquals("steps", issues[0].path)
    }

    // ---- P2：ui 段（悬浮窗按钮描述）----

    @Test
    fun parseUi_absent_returnsNull() {
        val json = JSONObject("""{"flow":"x","steps":[{"do":"click"}]}""")
        assertTrue(FlowValidator.validate(json).isEmpty())
        assertEquals(null, FlowValidator.parseUi(json))
    }

    @Test
    fun parseUi_valid() {
        val json = JSONObject(
            """{"steps":[{"do":"click"}],"ui":{"label":"圣遗物扫描","icon":"artifact","order":10,"color":"#7C4DFF","confirm":true,"hint":"长按设置"}}""",
        )
        val ui = FlowValidator.parseUi(json)
        assertNotNull(ui)
        ui!!
        assertEquals("圣遗物扫描", ui.label)
        assertEquals("artifact", ui.icon)
        assertEquals(10, ui.order)
        assertEquals("#7C4DFF", ui.color)
        assertTrue(ui.confirm)
        assertEquals("长按设置", ui.hint)
        assertTrue(FlowValidator.validate(json).isEmpty())
    }

    @Test
    fun parseUi_defaults_orderLast_confirmFalse() {
        val json = JSONObject("""{"steps":[{"do":"click"}],"ui":{"label":"武器扫描","icon":"weapon"}}""")
        val ui = FlowValidator.parseUi(json)
        assertNotNull(ui)
        ui!!
        assertEquals(Int.MAX_VALUE, ui.order)
        assertFalse(ui.confirm)
        assertEquals(null, ui.color)
    }

    @Test
    fun validate_ui_missingLabel_reportsIssue() {
        val json = JSONObject("""{"steps":[{"do":"click"}],"ui":{"icon":"artifact"}}""")
        val issues = FlowValidator.validate(json)
        assertTrue(issues.any { it.path == "ui.label" })
    }

    @Test
    fun validate_ui_unknownIcon_reported_andFallsBackToGear() {
        val json = JSONObject("""{"steps":[{"do":"click"}],"ui":{"label":"x","icon":"nope"}}""")
        val issues = FlowValidator.validate(json)
        assertTrue(issues.any { it.path == "ui.icon" })
        assertEquals("gear", FlowValidator.parseUi(json)?.icon)
    }

    @Test
    fun validate_ui_badColor() {
        val json = JSONObject("""{"steps":[{"do":"click"}],"ui":{"label":"x","icon":"gear","color":"7C4DFF"}}""")
        assertTrue(FlowValidator.validate(json).any { it.path == "ui.color" })
    }

    // ---- P4：ui.actions（行右侧动作，脚本自己声明）----

    @Test
    fun parseUi_actions_default_to_single_run() {
        val ui = FlowValidator.parseUi(JSONObject("""{"ui":{"label":"武器扫描"}}"""))
        assertNotNull(ui)
        assertEquals(1, ui!!.actions.size)
        assertEquals("run", ui.actions[0].kind)
        assertEquals("开始", ui.actions[0].label)
    }

    @Test
    fun parseUi_actions_keep_declared_order() {
        val ui = FlowValidator.parseUi(
            JSONObject(
                """{"ui":{"label":"圣遗物锁定","actions":[
                   {"kind":"import","label":"导入"},{"kind":"run","label":"开始"}]}}""",
            ),
        )
        assertEquals(listOf("import", "run"), ui!!.actions.map { it.kind })
        assertEquals(listOf("导入", "开始"), ui.actions.map { it.label })
    }

    @Test
    fun parseUi_actions_label_falls_back_by_kind() {
        val ui = FlowValidator.parseUi(
            JSONObject("""{"ui":{"label":"x","actions":[{"kind":"export"},{"kind":"open"}]}}"""),
        )
        assertEquals(listOf("导出", "打开"), ui!!.actions.map { it.label })
    }

    @Test
    fun parseUi_unknown_kind_is_dropped() {
        val json = JSONObject(
            """{"ui":{"label":"x","actions":[{"kind":"teleport"},{"kind":"run"}]}}""",
        )
        val ui = FlowValidator.parseUi(json)
        assertEquals(listOf("run"), ui!!.actions.map { it.kind })
        // 且必须报错（不静默丢弃）
        val issues = FlowValidator.validate(json)
        assertTrue(issues.any { it.path == "ui.actions[0].kind" })
    }

    @Test
    fun parseUi_empty_actions_falls_back_to_run_and_reports() {
        val json = JSONObject("""{"ui":{"label":"x","actions":[]},"steps":[]}""")
        val ui = FlowValidator.parseUi(json)
        assertEquals(listOf("run"), ui!!.actions.map { it.kind })
        assertTrue(FlowValidator.validate(json).any { it.path == "ui.actions" })
    }

    @Test
    fun validate_reports_actions_over_inline_limit() {
        val json = JSONObject(
            """{"ui":{"label":"x","actions":[
               {"kind":"export"},{"kind":"run"},{"kind":"config"}]},"steps":[]}""",
        )
        // 超限不截断（界面用「更多」子行承载），但要报出来
        assertEquals(3, FlowValidator.parseUi(json)!!.actions.size)
        assertTrue(FlowValidator.validate(json).any { it.path == "ui.actions" })
    }

    @Test
    fun validate_actions_must_be_array() {
        val json = JSONObject("""{"ui":{"label":"x","actions":"run"},"steps":[]}""")
        assertTrue(FlowValidator.validate(json).any { it.path == "ui.actions" })
    }
}
