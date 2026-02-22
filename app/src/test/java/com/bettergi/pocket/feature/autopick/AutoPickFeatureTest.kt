package com.bettergi.pocket.feature.autopick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AutoPickFeatureTest {
    @Test
    fun `auto pick interval is 800ms`() {
        assertEquals(800L, AutoPickFeature.CLICK_INTERVAL_MS)
    }

    @Test
    fun `auto pick stays disabled while unavailable`() {
        val feature = AutoPickFeature()
        if (!AutoPickFeature.AVAILABLE) {
            assertFalse(
                feature.isEnabled(
                    com.bettergi.pocket.settings.TriggerSettings(
                        screenShareEnabled = true,
                        autoPickEnabled = true,
                        autoSkipEnabled = false,
                        quickSkipDialogueEnabled = true,
                    ),
                ),
            )
        }
    }

    @Test
    fun `auto pick does not request frames`() {
        val feature = AutoPickFeature()
        assertFalse(
            feature.needsFrame(
                com.bettergi.pocket.settings.TriggerSettings(
                    screenShareEnabled = true,
                    autoPickEnabled = true,
                    autoSkipEnabled = false,
                    quickSkipDialogueEnabled = true,
                ),
            ),
        )
    }
}
