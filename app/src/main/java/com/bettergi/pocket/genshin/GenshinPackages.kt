package com.bettergi.pocket.genshin

object GenshinPackages {
    val CANDIDATES: List<String> = listOf(
        "com.miHoYo.Yuanshen",
        "com.miHoYo.ys.bilibili",
        "com.miHoYo.GenshinImpact",
        "com.miHoYo.cloudgames.ys",
        "com.miHoYo.cloudgames.GenshinImpact",
        "com.miHoYo.ys.mi",
    )

    fun isGenshinPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (CANDIDATES.any { it.equals(packageName, ignoreCase = true) }) return true
        val lower = packageName.lowercase()
        return lower.contains("yuanshen") ||
            lower.contains("genshinimpact") ||
            lower.startsWith("com.mihoyo.ys.") ||
            lower.startsWith("com.mihoyo.cloudgames.ys")
    }

    /**
     * ★ #283：MiHoYo SDK 登录/切号/协议页的 Activity 类名 —— 这些页**与游戏同包名**
     * （`com.miHoYo.Yuanshen/com.miHoYo.sdk.platform.SdkActivity`，见
     * `dsl/verify/runlogs/ui_scan_20261014_064526_VED0218912000022__character_scan.logcat.log`
     * 06:10:26.591 那条 `window-state`），所以只按包名判前台会把 SDK 登录页误认成游戏：
     * 顶号时扫描照常起跑、把流程入口点到 SDK 页上，OCR 找不到锚点 `元素` ⇒ 整轮崩在 assertAnchor。
     *
     * ⚠️ 只放**实测见过**的类名。不能用 `pm dump` 反推：那份 Activity Resolver Table 只列
     * exported 组件（`com.miHoYo.sdk.platform.SchemeActivity` 在表里，`SdkActivity` 不在 ——
     * 从 shell `am start` 它直接被 `SecurityException: not exported from uid 10215` 拒掉），
     * 所以「表里没有」既不等于「不存在」，更不是编类名的依据。
     */
    val SDK_ACTIVITIES: Set<String> = setOf(
        "com.miHoYo.sdk.platform.SdkActivity",
    )

    /**
     * 类名是否是已知的 MiHoYo SDK 登录/切号页（与游戏同包名，需与游戏画面区分）。
     *
     * 前缀兜底只认 `com.mihoyo.sdk.`：实测确认这个命名空间下住的都是 SDK 协议/登录页
     * （`pm dump` 里 exported 的 `com.mihoyo.sdk.hoyolink.protocol.HoYoLinkProtocolActivity`、
     * `com.miHoYo.sdk.platform.SchemeActivity`），而游戏自己的画面类走
     * `com.miHoYo.GetMobileInfo.MainActivity` —— 二者不重叠，所以判成 SDK 页不会误伤游戏。
     * ⚠️ 绝不能放宽成 `com.mihoyo.`：那个前缀会把游戏主 Activity 一起吞进去。
     */
    fun isSdkActivity(className: String?): Boolean {
        if (className.isNullOrBlank()) return false
        if (SDK_ACTIVITIES.any { it.equals(className, ignoreCase = true) }) return true
        return className.lowercase().startsWith("com.mihoyo.sdk.")
    }

    /**
     * 窗口类名是否像「应用自己的 Activity」（排除 `android.widget.*` 这类 Activity 内的子窗口类）。
     * #283 用它决定「最近 Activity 类名」怎么动：游戏包也会发 `android.widget.RelativeLayout`
     * 这类子窗口事件。
     *
     * ⚠️ 曾经的注释说这类子窗口事件**不得**覆盖 SDK 页的类名（怕 SDK 页被洗白成游戏）。10-14
     * 华为实测把这条反过来了：从 SDK 页回到世界**只发一条非 Activity 事件**（SdkActivity →
     * RelativeLayout），此后**再无任何 Activity 事件** ⇒ 若保留旧类名，注入闸会把整轮正常扫描
     * 全拒（#283 的假阳性面）。所以现在的口径是：非 Activity 类名 ⇒ **置空**调用方的
     * `InputAccessibilityService.lastAppClass`（fail-open），而不是保留。
     */
    fun isActivityLikeClass(className: String?): Boolean {
        val c = className ?: return false
        if (!c.contains('.')) return false
        return !c.startsWith("android.") && !c.startsWith("androidx.")
    }

    /**
     * 前台是否**确实是游戏画面**（而非同包名的 MiHoYo SDK 登录/顶号页）。
     *
     * 判据：包名是原神，**且**类名不是已知 SDK Activity。类名缺失 / 非 Activity（`null`、
     * 子窗口类）时按「未知但非 SDK」**放行** —— 与全仓既有的 fail-open 口径一致：硬关会把
     * 正常游戏误拒（其窗口也会短暂报 `android.widget.RelativeLayout`）。
     *
     * @param packageName 前台包名（可为 null）
     * @param className   前台窗口类名（可为 null / 非 Activity 类名）
     */
    fun isGameForeground(packageName: String?, className: String?): Boolean {
        if (!isGenshinPackage(packageName)) return false
        return !isSdkActivity(className)
    }

    fun pickPreferred(installed: Collection<String>): String? {
        val set = installed.toSet()
        CANDIDATES.firstOrNull { it in set }?.let { return it }
        return installed.firstOrNull { isGenshinPackage(it) }
    }

    fun displayName(packageName: String): String = when (packageName) {
        "com.miHoYo.Yuanshen" -> "国服官服"
        "com.miHoYo.ys.bilibili" -> "Bilibili服"
        "com.miHoYo.GenshinImpact" -> "国际服"
        "com.miHoYo.cloudgames.ys" -> "云原神"
        "com.miHoYo.cloudgames.GenshinImpact" -> "云原神国际服"
        "com.miHoYo.ys.mi" -> "小米渠道服"
        else -> "原神"
    }

    fun shouldAttemptAutoLaunch(
        enabled: Boolean,
        genshinInForeground: Boolean?,
        alreadyAttempted: Boolean,
        allowed: Boolean,
    ): Boolean {
        if (!enabled || !allowed || alreadyAttempted) return false
        return genshinInForeground != true
    }
}
