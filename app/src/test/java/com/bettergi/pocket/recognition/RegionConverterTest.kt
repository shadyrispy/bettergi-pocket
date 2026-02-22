package com.bettergi.pocket.recognition

import com.bettergi.pocket.recognition.area.Region
import com.bettergi.pocket.recognition.area.ScaleConverter
import com.bettergi.pocket.recognition.area.TranslationConverter
import com.bettergi.pocket.recognition.area.convertPositionToTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionConverterTest {
    private class RootRegion : Region(0, 0, 2560, 1440)

    @Test
    fun `empty region is a miss`() {
        val miss = Region()
        assertTrue(miss.isEmpty())
        assertFalse(miss.isExist())
    }

    @Test
    fun `translation walks back to root`() {
        val root = RootRegion()
        val child = Region(200, 150, 32, 32, root, TranslationConverter(200, 150))
        val converted = convertPositionToTarget(0, 0, 32, 32, child, RootRegion::class.java)
        assertEquals(200, converted.x)
        assertEquals(150, converted.y)
        assertEquals(32, converted.width)
        assertEquals(32, converted.height)
    }

    @Test
    fun `1080p match scales back to native capture`() {
        val root = RootRegion()
        val scaled = Region(0, 0, 1920, 1080, root, ScaleConverter(2560 / 1920.0))
        val match = Region(200, 150, 32, 32, scaled, TranslationConverter(200, 150))
        val converted = convertPositionToTarget(0, 0, 32, 32, match, RootRegion::class.java)
        assertEquals(266, converted.x)
        assertEquals(200, converted.y)
        assertEquals(42, converted.width)
        assertEquals(42, converted.height)
    }
}
