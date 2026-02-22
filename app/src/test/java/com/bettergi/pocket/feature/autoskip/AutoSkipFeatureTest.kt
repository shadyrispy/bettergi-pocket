package com.bettergi.pocket.feature.autoskip

import com.bettergi.pocket.recognition.area.Region
import com.bettergi.pocket.trigger.screenBottomCenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoSkipFeatureTest {
    @Test
    fun `selects the highest chat icon`() {
        val lower = Region(100, 400, 40, 20)
        val higher = Region(110, 120, 40, 20)
        val middle = Region(90, 250, 40, 20)
        val selected = AutoSkipFeature.selectTopChatIcon(listOf(lower, higher, middle))
        assertEquals(higher, selected)
    }

    @Test
    fun `empty hits yield no click target`() {
        assertNull(AutoSkipFeature.selectTopChatIcon(emptyList()))
    }

    @Test
    fun `skip click is bottom center of the screen`() {
        assertEquals(960 to 1060, screenBottomCenter(1920, 1080))
        assertEquals(1520 to 1884, screenBottomCenter(3040, 1904))
    }
}
