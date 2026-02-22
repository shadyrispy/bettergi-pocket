package com.bettergi.pocket.bilibili

object BilibiliSpace {
    const val SPACE_ID = "3546777483479879"
    const val WEB_URL = "https://space.bilibili.com/$SPACE_ID"
    const val APP_URI = "bilibili://space/$SPACE_ID"

    val APP_PACKAGES: List<String> = listOf(
        "tv.danmaku.bili",
        "com.bilibili.app.in",
        "tv.danmaku.bilibilihd",
        "com.bilibili.app.blue",
    )

    fun preferredPackage(installed: Collection<String>): String? {
        val set = installed.toSet()
        return APP_PACKAGES.firstOrNull { it in set }
    }
}
