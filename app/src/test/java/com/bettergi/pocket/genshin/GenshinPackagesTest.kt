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

    // ---- #283：与游戏**同包名**的 MiHoYo SDK 登录/切号页必须与游戏画面分开 ----
    //
    // 案情（2026-10-14 华为 EML-AL00，runlogs/ui_scan_20261014_064526_..._character_scan.logcat.log）：
    // 顶号把 `com.miHoYo.Yuanshen/com.miHoYo.sdk.platform.SdkActivity` 拉到前台，扫描照常起跑，
    // 把 character 入口点到 SDK 页上 ⇒ anchor /元素/ 三次不达 ⇒ 整轮 abort。
    //
    // 负对照（旧实现怎么红）：#283 之前 `isGameForeground` 只做 `isGenshinPackage(pkg)`
    //   —— `sdk page is not the game foreground` 这条在旧实现上返回 true ⇒ 断言红。
    //   其余「游戏画面」用例是**反面对照**：证明新判据没有把正常游戏一起关掉（fail-open 不变）。

    @Test
    fun `sdk page is not the game foreground`() {
        // 逐字取自带案日志的 window-state 行
        assertFalse(
            GenshinPackages.isGameForeground(
                "com.miHoYo.Yuanshen",
                "com.miHoYo.sdk.platform.SdkActivity",
            ),
        )
        // 同命名空间下的其它 SDK 协议页（pm dump 里 exported 的 HoYoLink 一条）
        assertFalse(
            GenshinPackages.isGameForeground(
                "com.miHoYo.Yuanshen",
                "com.mihoyo.sdk.hoyolink.protocol.HoYoLinkProtocolActivity",
            ),
        )
    }

    @Test
    fun `game screen stays the game foreground`() {
        assertTrue(
            GenshinPackages.isGameForeground(
                "com.miHoYo.Yuanshen",
                "com.miHoYo.GetMobileInfo.MainActivity",
            ),
        )
        // 非 Activity 类名（游戏包也会发 android.widget.* 子窗口事件）⇒ 按未知放行
        assertTrue(GenshinPackages.isGameForeground("com.miHoYo.Yuanshen", "android.widget.RelativeLayout"))
        // 类名缺失（对面旧版没有这个键）⇒ 退回包名判据
        assertTrue(GenshinPackages.isGameForeground("com.miHoYo.Yuanshen", null))
    }

    @Test
    fun `non genshin package is never the game foreground`() {
        assertFalse(
            GenshinPackages.isGameForeground(
                "com.android.mms",
                "com.miHoYo.sdk.platform.SdkActivity",
            ),
        )
        assertFalse(GenshinPackages.isGameForeground(null, null))
    }

    @Test
    fun `sdk prefix is narrow enough not to swallow the game activity`() {
        // 反面对照：前缀要是被放宽成 `com.mihoyo.`，下面这条会红（游戏主 Activity 就是 com.miHoYo.*）
        assertFalse(GenshinPackages.isSdkActivity("com.miHoYo.GetMobileInfo.MainActivity"))
        assertFalse(GenshinPackages.isSdkActivity(null))
        assertFalse(GenshinPackages.isSdkActivity(""))
        assertTrue(GenshinPackages.isSdkActivity("com.miHoYo.sdk.platform.SdkActivity"))
    }

    @Test
    fun `activity like class keeps activities but drops android subwindows`() {
        assertTrue(GenshinPackages.isActivityLikeClass("com.miHoYo.sdk.platform.SdkActivity"))
        assertTrue(GenshinPackages.isActivityLikeClass("com.bettergi.pocket.ui.MainActivity"))
        assertFalse(GenshinPackages.isActivityLikeClass("android.widget.FrameLayout"))
        assertFalse(GenshinPackages.isActivityLikeClass("androidx.recyclerview.widget.RecyclerView"))
        assertFalse(GenshinPackages.isActivityLikeClass("没有点的类名"))
        assertFalse(GenshinPackages.isActivityLikeClass(null))
    }
}
