package com.bettergi.pocket.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #97：锁钮首选位移没生效之后**该不该补点第二个坐标**。
 *
 * 真机实测背景（同帧双掩码，见 ScanEngine.probeLockPixels）：
 * `零位 gold=871 red=900 | +63 gold=0 red=0` ⇒ 对非祝圣件，`+63` 那个位置没有锁钮。
 * 旧实现无条件"换另一侧再点一次"，既会点到没标定的控件，又会把祝圣件刚点过的坐标再点一遍
 * （净效果归零 = #96 的真因）。
 */
class LockShiftChoiceTest {

    private val SH = 63 // 3200 档 zhushengShiftPx

    @Test
    fun `non crafted on screen with crafted first shift repoints to zero shift`() {
        // 计划说祝圣（首点 +63），屏幕投票说不是祝圣 ⇒ 真正该点的是 0
        assertEquals(0, followUpLockShift(firstShift = SH, zhushengShift = SH, craftedOnScreen = false))
    }

    @Test
    fun `crafted on screen with plain first shift repoints to shifted`() {
        // 计划没给 elixirCrafted（首点 0），屏幕投票说是祝圣 ⇒ 真正该点的是 +63
        assertEquals(SH, followUpLockShift(firstShift = 0, zhushengShift = SH, craftedOnScreen = true))
    }

    @Test
    fun `same side is never clicked twice`() {
        // 屏幕与首选位同侧 ⇒ 正确坐标已经点过一次，补点会把锁切回原态（#96）
        assertNull(followUpLockShift(firstShift = SH, zhushengShift = SH, craftedOnScreen = true))
        assertNull(followUpLockShift(firstShift = 0, zhushengShift = SH, craftedOnScreen = false))
    }

    @Test
    fun `unavailable craft judge never clicks an uncalibrated coordinate`() {
        // zone 未标定 / 取帧失败 ⇒ 无法证明哪一侧是真的锁钮 ⇒ 宁可直接失败
        assertNull(followUpLockShift(firstShift = 0, zhushengShift = SH, craftedOnScreen = null))
        assertNull(followUpLockShift(firstShift = SH, zhushengShift = SH, craftedOnScreen = null))
    }

    @Test
    fun `zero shift profile collapses both sides`() {
        // zhushengShiftPx=0 的档（未标定祝圣位移）：两侧本就是同一坐标 ⇒ 永不补点
        assertNull(followUpLockShift(firstShift = 0, zhushengShift = 0, craftedOnScreen = true))
        assertNull(followUpLockShift(firstShift = 0, zhushengShift = 0, craftedOnScreen = false))
    }
}
