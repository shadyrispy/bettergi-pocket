package com.bettergi.pocket.shizuku

import android.os.IBinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ★ #310/M1b：BgiRemoteManager 状态机 + ping 握手规则全表单测（纯 JVM，无 Robolectric）。
 *
 * 三层覆盖（与实现的可注入面一一对应，全程不碰真 binder / Shizuku 静态面）：
 * 1. [isPongValid] —— pong == token+1 规则的边界（含 token=0/-1、pong=null 半死 binder）。
 * 2. [BgiRemoteManager.Machine] —— 状态转换表逐格验证（KDoc 图的每条边 + 非法事件幂等）。
 * 3. [BgiRemoteManager] 编排层 —— 假 [BgiRemoteManager.Deps]（注入面刻意不含
 *    Shizuku/UserServiceArgs 类型，JVM 存根构造不可依赖）+ 同步 ping executor：
 *    start 前置校验表、connected→CONNECTED→LOST、ping 失败降级、stop 幂等。
 */
class BgiRemoteStateTest {

    @Before
    fun resetSingleton() {
        // object 单例：不 reset 则上一条用例的 LOST/CONNECTED/conn 泄漏进本条
        BgiRemoteManager.resetForTest()
    }

    // ── 1. ping 握手规则：pong == token + 1 ────────────────────

    @Test
    fun pong_equal_token_plus_one_is_valid() {
        assertTrue(BgiRemoteManager.isPongValid(token = 100, pong = 101))
        assertTrue(BgiRemoteManager.isPongValid(token = 0, pong = 1))
    }

    @Test
    fun pong_mismatch_is_invalid() {
        // 原样回弹 / 少一 / 多二 / 完全无关值：全部判死 —— 半死 binder 的典型形态
        assertFalse(BgiRemoteManager.isPongValid(token = 100, pong = 100))
        assertFalse(BgiRemoteManager.isPongValid(token = 100, pong = 99))
        assertFalse(BgiRemoteManager.isPongValid(token = 100, pong = 102))
        assertFalse(BgiRemoteManager.isPongValid(token = 100, pong = -1))
    }

    @Test
    fun pong_null_is_invalid() {
        // ping 抛异常（远端进程已死）→ pong 收敛为 null → 判无效（防半死 binder）
        assertFalse(BgiRemoteManager.isPongValid(token = 7, pong = null))
    }

    @Test
    fun pong_rule_holds_for_negative_and_overflowish_tokens() {
        assertTrue(BgiRemoteManager.isPongValid(token = -1, pong = 0))
        assertTrue(BgiRemoteManager.isPongValid(token = Long.MAX_VALUE - 1, pong = Long.MAX_VALUE))
        // Long.MAX_VALUE 的 token + 1 回绕成 MIN_VALUE —— 仍按「严格等」规则判定
        assertFalse(BgiRemoteManager.isPongValid(token = Long.MAX_VALUE, pong = Long.MIN_VALUE + 2))
        assertTrue(BgiRemoteManager.isPongValid(token = Long.MAX_VALUE, pong = Long.MIN_VALUE))
    }

    // ── 2. Machine 状态转换表 ───────────────────────────────────

    private class RecordingMachine {
        val fired = mutableListOf<BgiRemoteManager.RemoteServiceState>()
        val machine = BgiRemoteManager.Machine { next -> fired += next }

        fun last(): BgiRemoteManager.RemoteServiceState? = fired.lastOrNull()
    }

    @Test
    fun machine_initial_state_is_stopped() {
        val m = RecordingMachine()
        assertEquals(BgiRemoteManager.RemoteServiceState.STOPPED, m.machine.state)
        assertTrue(m.fired.isEmpty())
    }

    @Test
    fun stopped_bind_request_goes_connecting() {
        val m = RecordingMachine()
        assertTrue(m.machine.onBindRequested())
        assertEquals(BgiRemoteManager.RemoteServiceState.CONNECTING, m.machine.state)
        assertEquals(listOf(BgiRemoteManager.RemoteServiceState.CONNECTING), m.fired)
    }

    @Test
    fun lost_bind_request_reconnects_via_connecting() {
        // LOST 允许重绑：LOST → CONNECTING（不自动重连，但手动重拉畅通）
        val m = RecordingMachine()
        m.machine.onBindRequested()
        m.machine.onLinkDown() // → LOST
        assertTrue(m.machine.onBindRequested())
        assertEquals(BgiRemoteManager.RemoteServiceState.CONNECTING, m.machine.state)
        assertEquals(
            listOf(
                BgiRemoteManager.RemoteServiceState.CONNECTING,
                BgiRemoteManager.RemoteServiceState.LOST,
                BgiRemoteManager.RemoteServiceState.CONNECTING,
            ),
            m.fired,
        )
    }

    @Test
    fun connecting_linkup_goes_connected() {
        val m = RecordingMachine()
        m.machine.onBindRequested()
        m.machine.onLinkUp()
        assertEquals(BgiRemoteManager.RemoteServiceState.CONNECTED, m.machine.state)
    }

    @Test
    fun linkup_outside_connecting_is_ignored() {
        // 迟到的 connected（stop 之后才到）：CONNECTED 不该凭空出现
        val m = RecordingMachine()
        m.machine.onLinkUp()
        assertEquals(BgiRemoteManager.RemoteServiceState.STOPPED, m.machine.state)

        val m2 = RecordingMachine()
        m2.machine.onBindRequested()
        m2.machine.onLinkUp()
        m2.machine.onLinkUp() // 已 CONNECTED，重复 connected 不再触发迁移
        assertEquals(BgiRemoteManager.RemoteServiceState.CONNECTED, m2.machine.state)
        assertEquals(2, m2.fired.size) // 只有 CONNECTING、CONNECTED 两次
    }

    @Test
    fun connected_linkdown_goes_lost() {
        val m = RecordingMachine()
        m.machine.onBindRequested()
        m.machine.onLinkUp()
        m.machine.onLinkDown()
        assertEquals(BgiRemoteManager.RemoteServiceState.LOST, m.machine.state)
    }

    @Test
    fun connecting_linkdown_goes_lost_not_stopped() {
        // 关键语义：CONNECTING 途中死也是 LOST —— 「服务进程挂了」不能伪装成
        // 「从没绑过」，否则 UI 分不清该重启 Shizuku 还是重按开关
        val m = RecordingMachine()
        m.machine.onBindRequested()
        m.machine.onLinkDown()
        assertEquals(BgiRemoteManager.RemoteServiceState.LOST, m.machine.state)
    }

    @Test
    fun lost_linkdown_stays_lost() {
        val m = RecordingMachine()
        m.machine.onBindRequested()
        m.machine.onLinkDown()
        m.machine.onLinkDown() // LOST 再断保持 LOST，且不重复发事件
        assertEquals(BgiRemoteManager.RemoteServiceState.LOST, m.machine.state)
        assertEquals(
            listOf(
                BgiRemoteManager.RemoteServiceState.CONNECTING,
                BgiRemoteManager.RemoteServiceState.LOST,
            ),
            m.fired,
        )
    }

    @Test
    fun stopped_linkdown_stays_stopped() {
        // stop 后迟到的 disconnected：不许把 STOPPED 拉去 LOST
        val m = RecordingMachine()
        m.machine.onLinkDown()
        assertEquals(BgiRemoteManager.RemoteServiceState.STOPPED, m.machine.state)
        assertTrue(m.fired.isEmpty())
    }

    @Test
    fun stop_from_any_state_goes_stopped() {
        for (path in listOf<(BgiRemoteManager.Machine) -> Unit>(
            { it.onBindRequested() },
            { it.onBindRequested(); it.onLinkUp() },
            { it.onBindRequested(); it.onLinkDown() },
        )) {
            val m = RecordingMachine()
            path(m.machine)
            m.machine.onStop()
            assertEquals(BgiRemoteManager.RemoteServiceState.STOPPED, m.machine.state)
        }
    }

    @Test
    fun stop_when_already_stopped_fires_nothing() {
        val m = RecordingMachine()
        m.machine.onStop()
        assertEquals(BgiRemoteManager.RemoteServiceState.STOPPED, m.machine.state)
        assertTrue(m.fired.isEmpty())
    }

    @Test
    fun bind_request_twice_is_rejected_while_linking() {
        // 幂等：CONNECTING/CONNECTED 期间再次 onBindRequested 必须拒绝（防重复 bind）
        val m = RecordingMachine()
        assertTrue(m.machine.onBindRequested())
        assertFalse(m.machine.onBindRequested())
        m.machine.onLinkUp()
        assertFalse(m.machine.onBindRequested())
        assertEquals(2, m.fired.size)
    }

    // ── 3. 编排层（假 Deps + 同步 ping executor）──────────────

    /** 可编程假 Deps：bind/unbind 可控成败，捕获 conn 以便手动投递回调。 */
    private class FakeDeps : BgiRemoteManager.Deps {
        var assessStatus: ShizukuStatus =
            ShizukuStatus(ShizukuPhase.READY, serverUid = 2000)
        var bindResult: Boolean = true
        var unbindResult: Boolean = true
        var bindCalls: Int = 0
        var unbindCalls: Int = 0
        var lastUnbindRemove: Boolean? = null
        var capturedConn: android.content.ServiceConnection? = null
        var fakeRemote: IBgiRemoteService? = null

        override fun bind(conn: android.content.ServiceConnection): Boolean {
            bindCalls++
            if (bindResult) capturedConn = conn
            return bindResult
        }

        override fun unbind(conn: android.content.ServiceConnection, remove: Boolean): Boolean {
            unbindCalls++
            lastUnbindRemove = remove
            return unbindResult
        }

        override fun remoteService(binder: IBinder?): IBgiRemoteService? = fakeRemote

        /** 模拟 Shizuku 侧回调 onServiceConnected（binder 面只经 [remoteService] 翻译）。 */
        fun deliverConnected(binder: IBinder? = null) {
            capturedConn!!.onServiceConnected(null, binder)
        }

        /** 模拟 Shizuku 侧回调 onServiceDisconnected。 */
        fun deliverDisconnected() {
            capturedConn!!.onServiceDisconnected(null)
        }
    }

    /** ping 行为可编程的假远端（替代真 AIDL Stub —— JVM 单测不碰 android.os.Binder）。 */
    private class FakeRemote(
        var pingAnswer: (Long) -> Long = { it + 1 },
        var pingThrows: Boolean = false,
    ) : IBgiRemoteService {
        var pingTokens = mutableListOf<Long>()

        override fun ping(token: Long): Long {
            if (pingThrows) error("远端进程已死（模拟 binder 抛 RuntimeException）")
            pingTokens += token
            return pingAnswer(token)
        }

        override fun serviceVersion(): String = "fake"
        override fun apiLevel(): Int = 35
        override fun destroy() = Unit

        // ★ #310/M3 AIDL v2 追加方法：测试里不可达（Fake 只喂 M1b 的链路），恒 fail-fast
        override fun createDisplay(): Int = error("not used in tests")
        override fun startAppOnDisplay(packageName: String?, displayId: Int): Boolean =
            error("not used in tests")
        override fun repinToDisplay(packageName: String?, displayId: Int): Boolean =
            error("not used in tests")
        override fun tapOnDisplay(displayId: Int, x: Int, y: Int): Boolean =
            error("not used in tests")
        override fun swipeOnDisplay(displayId: Int, fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean =
            error("not used in tests")

        // ★ #310/M5 AIDL v3 追加方法：同上，测试里不可达，恒 fail-fast
        override fun capturePreviewJpeg(): ByteArray? = error("not used in tests")
        override fun isGameAlive(): Boolean = error("not used in tests")
        override fun getGameFps(): Int = error("not used in tests")

        // IBinder 面：单测只打「翻译后的接口」，不会真走 binder，返回 null 即可
        override fun asBinder(): IBinder = null!!
    }

    private val syncExecutor = java.util.concurrent.Executor { it.run() }

    private fun newManagerWith(
        deps: FakeDeps,
    ): BgiRemoteManager {
        BgiRemoteManager.testDeps = deps
        BgiRemoteManager.testPingExecutor = syncExecutor
        return BgiRemoteManager
    }

    @Test
    fun start_with_shizuku_not_ready_never_binds_and_reports_error() {
        val deps = FakeDeps().apply {
            assessStatus = ShizukuStatus(ShizukuPhase.NOT_GRANTED, serverUid = null)
        }
        val states = mutableListOf<BgiRemoteManager.RemoteServiceState>()
        val errors = mutableListOf<String>()
        newManagerWith(deps).run {
            addStateListener { states += it }
            addErrorListener { errors += it }
            assertFalse(start { deps.assessStatus })
            assertEquals(BgiRemoteManager.RemoteServiceState.STOPPED, state)
        }
        assertEquals(0, deps.bindCalls)
        // 注册时 sticky 回放 STOPPED，校验失败无迁移 —— 全程只有这一个初值
        assertEquals(listOf(BgiRemoteManager.RemoteServiceState.STOPPED), states)
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("NOT_GRANTED"))
    }

    @Test
    fun start_ready_binds_and_connected_goes_connected_then_ping_ok() {
        val deps = FakeDeps()
        val fakeRemote = FakeRemote()
        deps.fakeRemote = fakeRemote
        val states = mutableListOf<BgiRemoteManager.RemoteServiceState>()
        val mgr = newManagerWith(deps)
        mgr.addStateListener { states += it }

        assertTrue(mgr.start { deps.assessStatus })
        assertEquals(BgiRemoteManager.RemoteServiceState.CONNECTING, mgr.state)
        deps.deliverConnected() // 同步 executor：connected + ping 全链路当场走完
        assertEquals(BgiRemoteManager.RemoteServiceState.CONNECTED, mgr.state)
        assertEquals(1, fakeRemote.pingTokens.size) // 握手 ping 恰好一发
        assertEquals(fakeRemote, mgr.service())
    }

    @Test
    fun ping_mismatch_demotes_connected_to_lost() {
        val deps = FakeDeps()
        deps.fakeRemote = FakeRemote(pingAnswer = { it }) // 半死 binder：原样回弹
        val mgr = newManagerWith(deps)
        mgr.start { deps.assessStatus }
        deps.deliverConnected()
        assertEquals(BgiRemoteManager.RemoteServiceState.LOST, mgr.state)
        assertNull(mgr.service()) // 降级时引用必须清掉
    }

    @Test
    fun ping_throw_demotes_connected_to_lost() {
        val deps = FakeDeps()
        deps.fakeRemote = FakeRemote(pingThrows = true)
        val mgr = newManagerWith(deps)
        mgr.start { deps.assessStatus }
        deps.deliverConnected()
        assertEquals(BgiRemoteManager.RemoteServiceState.LOST, mgr.state)
        assertNull(mgr.service())
    }

    @Test
    fun disconnected_after_connected_goes_lost() {
        val deps = FakeDeps()
        deps.fakeRemote = FakeRemote()
        val mgr = newManagerWith(deps)
        mgr.start { deps.assessStatus }
        deps.deliverConnected()
        assertEquals(BgiRemoteManager.RemoteServiceState.CONNECTED, mgr.state)
        deps.deliverDisconnected()
        assertEquals(BgiRemoteManager.RemoteServiceState.LOST, mgr.state)
        assertNull(mgr.service())
    }

    @Test
    fun bind_call_failure_falls_back_to_stopped_with_error() {
        val deps = FakeDeps().apply { bindResult = false }
        val errors = mutableListOf<String>()
        val mgr = newManagerWith(deps)
        mgr.addErrorListener { errors += it }
        assertFalse(mgr.start { deps.assessStatus })
        assertEquals(BgiRemoteManager.RemoteServiceState.STOPPED, mgr.state)
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("bindUserService"))
    }

    @Test
    fun start_is_idempotent_while_connecting_or_connected() {
        val deps = FakeDeps()
        deps.fakeRemote = FakeRemote()
        val mgr = newManagerWith(deps)
        assertTrue(mgr.start { deps.assessStatus })
        assertFalse(mgr.start { deps.assessStatus }) // CONNECTING 期间拒绝
        assertEquals(1, deps.bindCalls)
        deps.deliverConnected()
        assertFalse(mgr.start { deps.assessStatus }) // CONNECTED 期间拒绝
        assertEquals(1, deps.bindCalls)
    }

    @Test
    fun stop_unbinds_with_remove_true_and_goes_stopped() {
        val deps = FakeDeps()
        deps.fakeRemote = FakeRemote()
        val mgr = newManagerWith(deps)
        mgr.start { deps.assessStatus }
        deps.deliverConnected()
        mgr.stop()
        assertEquals(1, deps.unbindCalls)
        assertEquals(true, deps.lastUnbindRemove) // remove=true 才会真杀远端进程
        assertEquals(BgiRemoteManager.RemoteServiceState.STOPPED, mgr.state)
        assertNull(mgr.service())
    }

    @Test
    fun stop_is_idempotent_when_never_started() {
        val deps = FakeDeps()
        val mgr = newManagerWith(deps)
        mgr.stop() // 从未 start：no-op，不许打 unbind
        assertEquals(0, deps.unbindCalls)
        assertEquals(BgiRemoteManager.RemoteServiceState.STOPPED, mgr.state)
    }

    @Test
    fun lost_state_allows_rebind() {
        val deps = FakeDeps()
        deps.fakeRemote = FakeRemote()
        val mgr = newManagerWith(deps)
        mgr.start { deps.assessStatus }
        deps.deliverConnected()
        deps.deliverDisconnected() // → LOST
        assertEquals(BgiRemoteManager.RemoteServiceState.LOST, mgr.state)
        assertTrue(mgr.start { deps.assessStatus }) // LOST 允许重新 bind（不自动重连）
        assertEquals(BgiRemoteManager.RemoteServiceState.CONNECTING, mgr.state)
        assertEquals(2, deps.bindCalls)
    }

    @Test
    fun connected_then_stop_then_late_connected_is_ignored() {
        // stop 后迟到的 connected 回调：不许把 STOPPED 拉回 CONNECTED/LOST
        val deps = FakeDeps()
        deps.fakeRemote = FakeRemote()
        val mgr = newManagerWith(deps)
        mgr.start { deps.assessStatus }
        mgr.stop()
        deps.deliverConnected()
        assertEquals(BgiRemoteManager.RemoteServiceState.STOPPED, mgr.state)
        assertNull(mgr.service())
    }

    @Test
    fun state_listener_receives_transitions_and_sticky_initial() {
        val deps = FakeDeps()
        deps.fakeRemote = FakeRemote()
        val mgr = newManagerWith(deps)
        val seen = mutableListOf<BgiRemoteManager.RemoteServiceState>()
        mgr.addStateListener { seen += it } // sticky：当场回放 STOPPED
        assertEquals(listOf(BgiRemoteManager.RemoteServiceState.STOPPED), seen)
        mgr.start { deps.assessStatus } // → CONNECTING
        deps.deliverConnected() // → CONNECTED（同步 ping 通过，不再有后续迁移）
        assertEquals(
            listOf(
                BgiRemoteManager.RemoteServiceState.STOPPED,
                BgiRemoteManager.RemoteServiceState.CONNECTING,
                BgiRemoteManager.RemoteServiceState.CONNECTED,
            ),
            seen,
        )
    }

}
