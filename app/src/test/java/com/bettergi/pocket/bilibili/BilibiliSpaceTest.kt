package com.bettergi.pocket.bilibili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BilibiliSpaceTest {
    @Test
    fun `prefers the official app when several clients are installed`() {
        assertEquals(
            "tv.danmaku.bili",
            BilibiliSpace.preferredPackage(
                listOf("tv.danmaku.bilibilihd", "tv.danmaku.bili", "com.bilibili.app.in"),
            ),
        )
    }

    @Test
    fun `returns null when bilibili is not installed`() {
        assertNull(BilibiliSpace.preferredPackage(listOf("com.android.chrome")))
    }

    @Test
    fun `uses the requested space links`() {
        assertEquals("3546777483479879", BilibiliSpace.SPACE_ID)
        assertEquals("https://space.bilibili.com/3546777483479879", BilibiliSpace.WEB_URL)
        assertEquals("bilibili://space/3546777483479879", BilibiliSpace.APP_URI)
    }
}
