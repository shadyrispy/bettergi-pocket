package com.bettergi.pocket.genshin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenshinPackagesTest {
    @Test
    fun `recognizes official and channel packages`() {
        assertTrue(GenshinPackages.isGenshinPackage("com.miHoYo.Yuanshen"))
        assertTrue(GenshinPackages.isGenshinPackage("com.miHoYo.ys.bilibili"))
        assertTrue(GenshinPackages.isGenshinPackage("com.miHoYo.GenshinImpact"))
        assertTrue(GenshinPackages.isGenshinPackage("com.miHoYo.ys.huawei"))
        assertFalse(GenshinPackages.isGenshinPackage("com.bettergi.pocket"))
        assertFalse(GenshinPackages.isGenshinPackage(null))
    }

    @Test
    fun `prefers official china client when several are installed`() {
        val picked = GenshinPackages.pickPreferred(
            listOf(
                "com.miHoYo.GenshinImpact",
                "com.miHoYo.Yuanshen",
                "com.miHoYo.ys.bilibili",
            ),
        )
        assertEquals("com.miHoYo.Yuanshen", picked)
    }

    @Test
    fun `falls back to the first recognized package`() {
        assertEquals(
            "com.miHoYo.ys.huawei",
            GenshinPackages.pickPreferred(listOf("com.miHoYo.ys.huawei")),
        )
        assertNull(GenshinPackages.pickPreferred(listOf("com.android.vending")))
    }

    @Test
    fun `auto launch only fires once when genshin is not already open`() {
        assertFalse(
            GenshinPackages.shouldAttemptAutoLaunch(
                enabled = false,
                genshinInForeground = false,
                alreadyAttempted = false,
                allowed = true,
            ),
        )
        assertFalse(
            GenshinPackages.shouldAttemptAutoLaunch(
                enabled = true,
                genshinInForeground = true,
                alreadyAttempted = false,
                allowed = true,
            ),
        )
        assertTrue(
            GenshinPackages.shouldAttemptAutoLaunch(
                enabled = true,
                genshinInForeground = false,
                alreadyAttempted = false,
                allowed = true,
            ),
        )
        assertTrue(
            GenshinPackages.shouldAttemptAutoLaunch(
                enabled = true,
                genshinInForeground = null,
                alreadyAttempted = false,
                allowed = true,
            ),
        )
        assertFalse(
            GenshinPackages.shouldAttemptAutoLaunch(
                enabled = true,
                genshinInForeground = false,
                alreadyAttempted = true,
                allowed = true,
            ),
        )
    }
}
