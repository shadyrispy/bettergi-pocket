package com.bettergi.pocket.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #310 / M0「探针接口抽取」的验收单测（GenshinStateProbe）。
 *
 * 覆盖两件事：
 * 1. **多态分发**：两个实现返回不同值，消费方（ScanEngine 的 foregroundOk/sdkLoginOnTop
 *    lambda 形态）拿到对应值——这是 M0 抽缝的全部语义，后台实现（probeDisplay 轮询）
 *    未来只需实现同一接口。
 * 2. **ACCESSIBILITY 转发契约**：JVM 沙箱下无障碍未连接（instance/appContext 均 null）
 *    ⇒ 两个静态方法都走"判不了"分支返回 null，探针须原样透传 null、不吞不换
 *    （null 契约 = 消费方 `?: true` fail-open 的根基，换实现时不得破坏）。
 */
class GenshinStateProbeTest {

    /** 记录调用次数的桩实现（验证消费方拿到的值确实来自被注入的那个实现）。 */
    private class RecordingProbe(
        private val foreground: Boolean?,
        private val sdkLogin: Boolean?,
    ) : GenshinStateProbe {
        var foregroundCalls = 0
        var sdkLoginCalls = 0

        override fun isGameInForeground(): Boolean? {
            foregroundCalls++
            return foreground
        }

        override fun isSdkLoginOnTop(): Boolean? {
            sdkLoginCalls++
            return sdkLogin
        }
    }

    @Test
    fun `注入的实现返回什么 消费方lambda就拿到什么（多态分发）`() {
        val probe = RecordingProbe(foreground = false, sdkLogin = true)
        // 消费形态与 ScriptRunner 构造 ScanEngine 时逐字一致（含 `?: true` 的 fail-open 口径）
        val foregroundOk: () -> Boolean? = { probe.isGameInForeground() ?: true }
        val sdkLoginOnTop: () -> Boolean? = { probe.isSdkLoginOnTop() }
        assertTrue("probe 报非游戏前台 ⇒ 闸门必须读到 false（false 不被 ?: 折叠）", foregroundOk() == false)
        assertTrue("probe 报 SDK 登录页在顶 ⇒ 探针必须读到 true", sdkLoginOnTop() == true)
        assertEquals(1, probe.foregroundCalls)
        assertEquals(1, probe.sdkLoginCalls)
    }

    @Test
    fun `probe 返回 null 时 foregroundOk 折叠为 true（fail-open 口径不变）`() {
        val probe = RecordingProbe(foreground = null, sdkLogin = null)
        val foregroundOk: () -> Boolean? = { probe.isGameInForeground() ?: true }
        val sdkLoginOnTop: () -> Boolean? = { probe.isSdkLoginOnTop() }
        assertTrue("null = 判不了 ⇒ 放行（与 isGenshinInForeground 的 null 契约一致）", foregroundOk() == true)
        assertNull("sdkLoginOnTop 的 null 不折叠，原样上抛给引擎判", sdkLoginOnTop())
    }

    @Test
    fun `ACCESSIBILITY 在无障碍未连接的 JVM 沙箱下透传 null 不吞不换`() {
        // JVM 单测环境：companion 的 instance/appContext 均为 null
        // ⇒ isGenshinInForeground()/sdkLoginOnTop() 走"判不了"分支返回 null。
        // 探针必须把这个 null 原样转出去——任何包装/默认值都是行为漂移。
        assertNull(GenshinStateProbe.ACCESSIBILITY.isGameInForeground())
        assertNull(GenshinStateProbe.ACCESSIBILITY.isSdkLoginOnTop())
    }

    @Test
    fun `两个实现可并存且互不串值（换实现即换行为）`() {
        val inGame = RecordingProbe(foreground = true, sdkLogin = false)
        val sdkPage = RecordingProbe(foreground = false, sdkLogin = true)
        assertTrue(inGame.isGameInForeground()!!)
        assertFalse(inGame.isSdkLoginOnTop()!!)
        assertFalse(sdkPage.isGameInForeground()!!)
        assertTrue(sdkPage.isSdkLoginOnTop()!!)
        // 各自只被调用一次，没有串到另一个实现上
        assertEquals(1 to 1, inGame.foregroundCalls to inGame.sdkLoginCalls)
        assertEquals(1 to 1, sdkPage.foregroundCalls to sdkPage.sdkLoginCalls)
    }
}
