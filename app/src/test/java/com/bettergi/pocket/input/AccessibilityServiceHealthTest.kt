package com.bettergi.pocket.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityServiceHealthTest {
    private val fullName = "com.bettergi.pocket/com.bettergi.pocket.input.InputAccessibilityService"
    private val shortName = "com.bettergi.pocket/.input.InputAccessibilityService"

    @Test
    fun `treats settings-on but unbound as disconnected`() {
        assertEquals(
            AccessibilityServiceHealth.State.DISCONNECTED,
            AccessibilityServiceHealth.state(connected = false, listedInSettings = true),
        )
    }

    @Test
    fun `connected wins over the settings listing`() {
        assertEquals(
            AccessibilityServiceHealth.State.CONNECTED,
            AccessibilityServiceHealth.state(connected = true, listedInSettings = true),
        )
    }

    @Test
    fun `not listed means disabled`() {
        assertEquals(
            AccessibilityServiceHealth.State.DISABLED,
            AccessibilityServiceHealth.state(connected = false, listedInSettings = false),
        )
    }

    @Test
    fun `matches full and short component names`() {
        assertTrue(AccessibilityServiceHealth.isListed("$shortName:other/service", fullName, shortName))
        assertTrue(AccessibilityServiceHealth.isListed(fullName, fullName, shortName))
        assertFalse(AccessibilityServiceHealth.isListed("other.pkg/.OtherService", fullName, shortName))
        assertFalse(AccessibilityServiceHealth.isListed(null, fullName, shortName))
        assertFalse(AccessibilityServiceHealth.isListed("", fullName, shortName))
    }
}
