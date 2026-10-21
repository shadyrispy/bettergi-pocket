package com.bettergi.pocket.vd

/**
 * ★ #310/M2 后台模式（virtualdisplay 分支）：虚拟显示器 flag 的分层组合。
 *
 * ## 常量值来源（逐个核对过，不是抄简报）
 * AOSP `frameworks/base/core/java/android/hardware/display/DisplayManager.java`
 * 的真身用 **Robolectric android-all jar** 核对（Maven Central
 * `org.robolectric:android-all:13-robolectric-9030017` 与
 * `15-robolectric-12714715`，是真实 AOSP 框架的编译产物、含 hidden 成员；
 * android.googlesource.com / GitHub 镜像在本机网络下全部不可达）。
 * 13 与 15 两版逐常量比对，**值完全一致**（这些常量自引入后从未改值）：
 *
 * | 常量（AOSP 原名）                        | 值（十进制 / 十六进制） | 引入 API | public? |
 * |------------------------------------------|-------------------------|----------|---------|
 * | VIRTUAL_DISPLAY_FLAG_PUBLIC              | 1    / 0x0001           | 19(KitKat) | 是（SDK stub 核实）|
 * | VIRTUAL_DISPLAY_FLAG_PRESENTATION        | 2    / 0x0002           | 19       | 是 |
 * | VIRTUAL_DISPLAY_FLAG_SECURE              | 4    / 0x0004           | 19       | 是 |
 * | VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY    | 8    / 0x0008           | 21       | 是 |
 * | VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR         | 16   / 0x0010           | (hidden) | 否 |
 * | VIRTUAL_DISPLAY_FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD | 32 / 0x0020 | (hidden) | 否 |
 * | VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH      | 64   / 0x0040           | 23       | 是 |
 * | VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT| 128  / 0x0080           | (hidden) | 否 |
 * | VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL | 256 / 0x0100      | (hidden) | 否 |
 * | VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS | 512 / 0x0200 | 30+ (hidden) | 否 |
 * | VIRTUAL_DISPLAY_FLAG_TRUSTED             | 1024 / 0x0400           | 33 (hidden) | 否 |
 * | VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP   | 2048 / 0x0800           | 33 (hidden) | 否 |
 * | VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED     | 4096 / 0x1000           | 33 (hidden) | 否 |
 * | VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED | 8192 / 0x2000       | 33 (hidden) | 否 |
 * | VIRTUAL_DISPLAY_FLAG_OWN_FOCUS           | 16384 / 0x4000          | 34 (hidden) | 否 |
 * | VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP| 32768 / 0x8000          | 34 (hidden) | 否 |
 * | VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED | 65536 / 0x10000    | 34 (hidden) | 否 |
 *
 * 其中 PUBLIC / OWN_CONTENT_ONLY / SUPPORTS_TOUCH 三个同时用本机 SDK 36.1 的
 * android.jar（stub）交叉核实过；"引入 API" 档位按 AOSP DisplayManager.java 的
 * `@RequiresApi`/JavaDoc 注记（TRUSTED/OWN_DISPLAY_GROUP/ALWAYS_UNLOCKED/
 * TOUCH_FEEDBACK_DISABLED = Android 13 (T)；OWN_FOCUS/DEVICE_DISPLAY_GROUP/
 * STEAL_TOP_FOCUS_DISABLED = Android 14 (U)）。
 *
 * ⚠️ 初版任务简报给的值多处错误（PUBLIC 写成 1<<3=8、OWN_CONTENT_ONLY 写成
 * 1<<5=32、DESTROY_CONTENT_ON_REMOVAL 写成 1<<2=4 等）——按简报硬写会把
 * SECURE/AUTO_MIRROR 位误置进 flag。**本文件以核对后的真值为准**。
 *
 * ## 分层策略（照 MAA-Meow 验证过的思路重写）
 * - 基础组（全 API）：PUBLIC 让系统把它当真屏对待 + OWN_CONTENT_ONLY 不镜像主屏
 *   + SUPPORTS_TOUCH 让游戏接受触屏 + DESTROY_CONTENT_ON_REMOVAL 释放即销内容。
 *   PUBLIC 与 OWN_CONTENT_ONLY 组合 = 不镜像、独立内容（auto-mirror 冲突位由系统
 *   按 own-content 优先裁决）。
 * - Android 13+ 加 TRUSTED（系统信任的显示，游戏不会被识别为投屏）+
 *   OWN_DISPLAY_GROUP（独立 display group，主屏不跟随）+ ALWAYS_UNLOCKED
 *   （不受锁屏影响，后台保活关键）+ TOUCH_FEEDBACK_DISABLED（不画触点圈，
 *   截帧干净）。注意 TRUSTED 系 flag 在提权进程（shell uid）才被系统接受。
 * - Android 14+ 加 OWN_FOCUS（独立焦点栈，游戏可获得焦点）+
 *   DEVICE_DISPLAY_GROUP（跟随设备 display group，resume 时回到本屏）+
 *   STEAL_TOP_FOCUS_DISABLED（不抢主屏焦点——开了会打断用户手头操作）。
 *
 * ## 纯函数化
 * [baseFlags]/[tFlags]/[uFlags]/[flagsFor] 全部把 `sdkInt` 作参数注入
 * （不读 Build.VERSION.SDK_INT），纯 JVM 可锁值单测（DisplayFlagsTest）。
 */
object DisplayFlags {

    /** AOSP `VIRTUAL_DISPLAY_FLAG_PUBLIC` = 0x0001（1<<0）。 */
    const val FLAG_PUBLIC: Int = 1

    /** AOSP `VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY` = 0x0008（1<<3）。 */
    const val FLAG_OWN_CONTENT_ONLY: Int = 8

    /** AOSP `VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH` = 0x0040（1<<6）。 */
    const val FLAG_SUPPORTS_TOUCH: Int = 64

    /** AOSP `VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL` = 0x0100（1<<8）。 */
    const val FLAG_DESTROY_CONTENT_ON_REMOVAL: Int = 256

    /** AOSP `VIRTUAL_DISPLAY_FLAG_TRUSTED` = 0x0400（1<<10）。Android 13+。 */
    const val FLAG_TRUSTED: Int = 1024

    /** AOSP `VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED` = 0x1000（1<<12）。Android 13+。 */
    const val FLAG_ALWAYS_UNLOCKED: Int = 4096

    /** AOSP `VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP` = 0x0800（1<<11）。Android 13+。 */
    const val FLAG_OWN_DISPLAY_GROUP: Int = 2048

    /** AOSP `VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED` = 0x2000（1<<13）。Android 13+。 */
    const val FLAG_TOUCH_FEEDBACK_DISABLED: Int = 8192

    /** AOSP `VIRTUAL_DISPLAY_FLAG_OWN_FOCUS` = 0x4000（1<<14）。Android 14+。 */
    const val FLAG_OWN_FOCUS: Int = 16384

    /** AOSP `VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP` = 0x8000（1<<15）。Android 14+。 */
    const val FLAG_DEVICE_DISPLAY_GROUP: Int = 32768

    /** AOSP `VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED` = 0x10000（1<<16）。Android 14+。 */
    const val FLAG_STEAL_TOP_FOCUS_DISABLED: Int = 65536

    /**
     * 基础组（全 API 档）：
     * PUBLIC(0x1) | OWN_CONTENT_ONLY(0x8) | SUPPORTS_TOUCH(0x40) | DESTROY_CONTENT_ON_REMOVAL(0x100)
     * = 0x149 = 329。
     */
    fun baseFlags(sdkInt: Int): Int =
        FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or FLAG_DESTROY_CONTENT_ON_REMOVAL

    /**
     * Android 13+ 附加组：TRUSTED(0x400) | OWN_DISPLAY_GROUP(0x800) |
     * ALWAYS_UNLOCKED(0x1000) | TOUCH_FEEDBACK_DISABLED(0x2000) = 0x3C00 = 15360。
     */
    fun tFlags(sdkInt: Int): Int =
        FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED

    /**
     * Android 14+ 附加组：OWN_FOCUS(0x4000) | DEVICE_DISPLAY_GROUP(0x8000) |
     * STEAL_TOP_FOCUS_DISABLED(0x10000) = 0x1C000 = 114688。
     */
    fun uFlags(sdkInt: Int): Int =
        FLAG_OWN_FOCUS or FLAG_DEVICE_DISPLAY_GROUP or FLAG_STEAL_TOP_FOCUS_DISABLED

    /**
     * 按 SDK 分档返回 createVirtualDisplay 的 flags 完整组合。
     *
     * @param sdkInt 注入的 Build.VERSION.SDK_INT（纯函数化，单测锁各档值）
     */
    fun flagsFor(sdkInt: Int): Int {
        var flags = baseFlags(sdkInt)
        if (sdkInt >= 33) flags = flags or tFlags(sdkInt)
        if (sdkInt >= 34) flags = flags or uFlags(sdkInt)
        return flags
    }
}
