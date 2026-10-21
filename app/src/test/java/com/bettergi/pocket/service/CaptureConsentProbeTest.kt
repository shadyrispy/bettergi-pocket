package com.bettergi.pocket.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * #250（2026-10-13）`CaptureConsentProbe` 的纯 JVM 单测 —— 判「投影授权页到底弹没弹」的判定核。
 * 探针 / 睡眠 / 时钟全部注入，不需要 Android 运行时（与 `scan/ScriptRunnerTest` 驱动
 * `StartGateLiveAsk` 同一套做法）。
 *
 * ⚠️ 覆盖范围只到**判定逻辑**：`armCaptureConsentProbe` 的 Handler 延迟、后台线程、以及
 * `InputAccessibilityService.activeWindowPackage()` 那条跨进程现问都得在真机上验
 * （JVM 侧构造不出 Looper / ContentProvider）。真机验法见
 * `dsl/docs/capture-consent-launch-20261013.md` 的「上线后怎么复核」。
 */
class CaptureConsentProbeTest {

    private class FakeClock {
        var now = 0L
            private set
        val sleeps = ArrayList<Long>()
        val clock: () -> Long = { now }

        /** 记一次重试间隔（生产侧对应 Thread.sleep）。 */
        fun sleep(ms: Long) {
            sleeps.add(ms)
            now += ms
        }

        /** 模拟「现问这一次本身花了 ms」——预算用例用它制造慢探针。 */
        fun advance(ms: Long) {
            now += ms
        }
    }

    private val own = "com.bettergi.pocket"

    /** 实测在中华为 EML-AL00 上占着 mCurrentFocus 的系统弹层（授权页 ty=SYSTEM_ALERT）。 */
    private val consent = setOf("android", "com.android.systemui")

    private val game = "com.miHoYo.Yuanshen"

    private fun ask(
        fake: FakeClock,
        launched: Boolean,
        stillPending: () -> Boolean = { true },
        probe: () -> String?,
    ) = CaptureConsentProbe.ask(
        stillPending = stillPending,
        relayLaunched = { launched },
        ownPackage = own,
        consentPackages = consent,
        sleep = fake::sleep,
        clockMs = fake.clock,
        probe = probe,
    )

    @Test
    fun `settled when the request flag is already down - never asks`() {
        val fake = FakeClock()
        val calls = AtomicInteger(0)
        val out = ask(fake, launched = false, stillPending = { false }, probe = { calls.incrementAndGet(); game })
        assertEquals(CaptureConsentProbe.Verdict.Settled, out.verdict)
        // 秒授权快路（实测 250ms 就回结果）必须在 1.2s 探针这里一次都不问，否则会误判被吞
        assertEquals(0, calls.get())
        assertEquals(0, out.attempts)
    }

    @Test
    fun `swallowed needs both signals - no relay creation and a foreign foreground`() {
        val fake = FakeClock()
        val out = ask(fake, launched = false, probe = { game })
        assertEquals(CaptureConsentProbe.Verdict.Swallowed, out.verdict)
        assertEquals(game, out.pkg)
        assertEquals(1, out.attempts)
        assertTrue(fake.sleeps.isEmpty()) // 问到值就不再重试
    }

    @Test
    fun `relay stamp alone blocks the swallowed verdict even when the ask names the game`() {
        val fake = FakeClock()
        // 这条是本轮实测换来的关键用例：授权页是 ty=SYSTEM_ALERT 窗，现问的 windows 兜底只挑
        // TYPE_APPLICATION ⇒ 弹窗开着时它可能把**游戏**读成活动应用窗。只凭现问就会抢前台。
        val out = ask(fake, launched = true, probe = { game })
        assertEquals(CaptureConsentProbe.Verdict.CameUp, out.verdict)
        assertTrue(out.relayLaunched)
    }

    @Test
    fun `consent dialog package counts as came up even without the stamp`() {
        val fake = FakeClock()
        val out = ask(fake, launched = false, probe = { "com.android.systemui" })
        assertEquals(CaptureConsentProbe.Verdict.CameUp, out.verdict)
        assertEquals("com.android.systemui", out.pkg)
    }

    @Test
    fun `own package in foreground is not evidence of a drop`() {
        val fake = FakeClock()
        val out = ask(fake, launched = false, probe = { own })
        assertEquals(CaptureConsentProbe.Verdict.CameUp, out.verdict)
    }

    @Test
    fun `all-null asks exhaust into unknown and never conclude dropped`() {
        val fake = FakeClock()
        val calls = AtomicInteger(0)
        // #197 实测：这台华为点 ▶ 那刻现问恒 null（焦点切换中）。问不到就按不动处理。
        val out = ask(fake, launched = false, probe = { calls.incrementAndGet(); null })
        assertEquals(CaptureConsentProbe.Verdict.Unknown, out.verdict)
        assertEquals(CaptureConsentProbe.ASK_ATTEMPTS_MAX, calls.get())
        assertEquals(listOf(CaptureConsentProbe.ASK_INTERVAL_MS), fake.sleeps)
    }

    @Test
    fun `second answer wins after a null transient`() {
        val fake = FakeClock()
        val calls = AtomicInteger(0)
        val out = ask(fake, launched = false) { if (calls.incrementAndGet() == 1) null else game }
        assertEquals(CaptureConsentProbe.Verdict.Swallowed, out.verdict)
        assertEquals(2, out.attempts)
        assertEquals(listOf(CaptureConsentProbe.ASK_INTERVAL_MS), fake.sleeps)
    }

    /**
     * ★ 上面那条 `Unknown` 就是这组用例存在的理由：被吞的典型成因（`:a11y` 不在）恰好让现问变哑
     * （2026-10-13 21:23 华为实测：`START …CapturePermissionActivity result: 102` 之后
     * `探针: 2次都没问到(243ms)⇒ 不动`）⇒ 探针这半边在最需要它的场景里是哑的。
     * 60s 那条改用**不依赖现问**的信号 1 收尾，这里钉它的三条边界。
     */
    @Test
    fun `timeout without relay creation concludes not launched`() {
        assertTrue(
            "有发起记录、而中转页在这之后从没创建 ⇒ 判这次启动没落地",
            CaptureConsentProbe.timedOutWithoutRelay(requestedAtElapsed = 5_000L, relayLaunched = false),
        )
        assertEquals(
            "中转页在这次发起之后创建过 ⇒ 页面出来了、只是没人答：不许抢前台",
            false,
            CaptureConsentProbe.timedOutWithoutRelay(requestedAtElapsed = 5_000L, relayLaunched = true),
        )
        assertEquals(
            "没有这笔发起记录（<=0，旧路径/已复位）⇒ 一律不判，宁可不兜",
            false,
            CaptureConsentProbe.timedOutWithoutRelay(requestedAtElapsed = 0L, relayLaunched = false),
        )
    }

    @Test
    fun `budget cut stops before the next ask`() {
        val fake = FakeClock()
        val calls = AtomicInteger(0)
        // 单次现问在 :a11y 主线程卡住时最长 1s：这里每次问 400ms ⇒ 一次之后 400+200 超预算
        val out = ask(fake, launched = false) { calls.incrementAndGet(); fake.advance(400); null }
        assertEquals(CaptureConsentProbe.Verdict.Unknown, out.verdict)
        assertEquals(1, calls.get())
        assertEquals(true, out.budgetCut)
        assertTrue(fake.sleeps.isEmpty())
    }
}
