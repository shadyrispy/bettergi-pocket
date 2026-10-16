package com.bettergi.pocket.vd

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ★ #310/M2：DisplayFlags 的锁值单测。
 *
 * 值依据：AOSP framework 真身（Robolectric android-all 13/15 jar，Maven Central）
 * 逐常量 javap 核对——13 与 15 两版值一致；PUBLIC/OWN_CONTENT_ONLY/SUPPORTS_TOUCH
 * 三个同时与本机 SDK 36.1 stub 交叉核实。详见 DisplayFlags.kt 的来源表。
 *
 * 这里锁三件事：
 * 1. 每个常量的位值（防"手滑把 1<<n 写错"——简报初版就错了一半）；
 * 2. 各 SDK 档的组合值（基础组 / 33 加 TRUSTED 组 / 34 加全组）；
 * 3. 互不重叠（组合 flag 无意外置位）。
 */
class DisplayFlagsTest {

    // ── 单常量锁值 ─────────────────────────────────────────────────────

    @Test
    fun public_flag_is_bit0() {
        assertEquals(0x0001, DisplayFlags.FLAG_PUBLIC)
    }

    @Test
    fun own_content_only_is_bit3() {
        assertEquals(0x0008, DisplayFlags.FLAG_OWN_CONTENT_ONLY)
    }

    @Test
    fun supports_touch_is_bit6() {
        assertEquals(0x0040, DisplayFlags.FLAG_SUPPORTS_TOUCH)
    }

    @Test
    fun destroy_content_on_removal_is_bit8() {
        assertEquals(0x0100, DisplayFlags.FLAG_DESTROY_CONTENT_ON_REMOVAL)
    }

    @Test
    fun trusted_is_bit10() {
        assertEquals(0x0400, DisplayFlags.FLAG_TRUSTED)
    }

    @Test
    fun own_display_group_is_bit11() {
        assertEquals(0x0800, DisplayFlags.FLAG_OWN_DISPLAY_GROUP)
    }

    @Test
    fun always_unlocked_is_bit12() {
        assertEquals(0x1000, DisplayFlags.FLAG_ALWAYS_UNLOCKED)
    }

    @Test
    fun touch_feedback_disabled_is_bit13() {
        assertEquals(0x2000, DisplayFlags.FLAG_TOUCH_FEEDBACK_DISABLED)
    }

    @Test
    fun own_focus_is_bit14() {
        assertEquals(0x4000, DisplayFlags.FLAG_OWN_FOCUS)
    }

    @Test
    fun device_display_group_is_bit15() {
        assertEquals(0x8000, DisplayFlags.FLAG_DEVICE_DISPLAY_GROUP)
    }

    @Test
    fun steal_top_focus_disabled_is_bit16() {
        assertEquals(0x10000, DisplayFlags.FLAG_STEAL_TOP_FOCUS_DISABLED)
    }

    // ── 分层组合锁值 ───────────────────────────────────────────────────

    @Test
    fun base_group_matches_aosp_bits() {
        // PUBLIC | OWN_CONTENT_ONLY | SUPPORTS_TOUCH | DESTROY_CONTENT_ON_REMOVAL
        assertEquals(0x1 or 0x8 or 0x40 or 0x100, DisplayFlags.baseFlags(24))
        assertEquals(0b1_0100_1001, DisplayFlags.baseFlags(28)) // 0x149 = 329
    }

    @Test
    fun t_group_matches_aosp_bits() {
        // TRUSTED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED | TOUCH_FEEDBACK_DISABLED
        assertEquals(0x400 or 0x800 or 0x1000 or 0x2000, DisplayFlags.tFlags(33)) // 0x3C00
    }

    @Test
    fun u_group_matches_aosp_bits() {
        // OWN_FOCUS | DEVICE_DISPLAY_GROUP | STEAL_TOP_FOCUS_DISABLED
        assertEquals(0x4000 or 0x8000 or 0x10000, DisplayFlags.uFlags(34)) // 0x1C000
    }

    @Test
    fun below_33_gets_base_only() {
        // 24（minSdk）到 32（含 31 = Android 12）都只有基础组
        for (sdk in 24..32) {
            assertEquals("sdk=$sdk", 0x149, DisplayFlags.flagsFor(sdk))
        }
    }

    @Test
    fun api33_gets_base_plus_trusted_group() {
        assertEquals(0x149 or 0x3C00, DisplayFlags.flagsFor(33)) // 0x3D49 = 15689
        assertEquals(15689, DisplayFlags.flagsFor(33))
    }

    @Test
    fun api34_plus_gets_full_set() {
        assertEquals(0x149 or 0x3C00 or 0x1C000, DisplayFlags.flagsFor(34)) // 0x1FD49 = 130377
        assertEquals(130377, DisplayFlags.flagsFor(34))
        // 未来档位（36.1 = 本仓 compileSdk）不再加新位——锁死同一组合
        assertEquals(DisplayFlags.flagsFor(34), DisplayFlags.flagsFor(36))
    }

    // ── 位不重叠（组合 flag 无意外置位）─────────────────────────────────

    @Test
    fun groups_are_disjoint() {
        val base = DisplayFlags.baseFlags(24)
        val t = DisplayFlags.tFlags(33)
        val u = DisplayFlags.uFlags(34)
        assertEquals(0, base and t)
        assertEquals(0, base and u)
        assertEquals(0, t and u)
    }

    @Test
    fun full_set_has_no_unexpected_bits() {
        val full = DisplayFlags.flagsFor(34)
        val known = DisplayFlags.FLAG_PUBLIC or DisplayFlags.FLAG_OWN_CONTENT_ONLY or
            DisplayFlags.FLAG_SUPPORTS_TOUCH or DisplayFlags.FLAG_DESTROY_CONTENT_ON_REMOVAL or
            DisplayFlags.FLAG_TRUSTED or DisplayFlags.FLAG_OWN_DISPLAY_GROUP or
            DisplayFlags.FLAG_ALWAYS_UNLOCKED or DisplayFlags.FLAG_TOUCH_FEEDBACK_DISABLED or
            DisplayFlags.FLAG_OWN_FOCUS or DisplayFlags.FLAG_DEVICE_DISPLAY_GROUP or
            DisplayFlags.FLAG_STEAL_TOP_FOCUS_DISABLED
        assertEquals(known, full)
        // 明确不含的位：PRESENTATION(2) / SECURE(4) / AUTO_MIRROR(16) /
        // CAN_SHOW_WITH_INSECURE_KEYGUARD(32) / ROTATES_WITH_CONTENT(128) /
        // SHOULD_SHOW_SYSTEM_DECORATIONS(512)
        assertEquals(0, full and 2)
        assertEquals(0, full and 4)
        assertEquals(0, full and 16)
        assertEquals(0, full and 32)
        assertEquals(0, full and 128)
        assertEquals(0, full and 512)
    }
}
