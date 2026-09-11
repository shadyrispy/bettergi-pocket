package com.bettergi.pocket.dsl

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
