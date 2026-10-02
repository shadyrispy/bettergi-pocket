package com.bettergi.pocket.scan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ScriptRunner 抽出的**纯 JVM** 逻辑单测（轨 D1：A13/A14/A15，2026-09-30）。
 *
 * ⚠️ 范围说明：ScriptRunner 本体硬依赖 Context / Handler(Looper) / FrameSource 等 Android
 * 运行时，JVM 单测构造不出可用实例 ⇒ 三个工单的可测核心被抽成 ScriptRunner.kt 文件内的
 * 无 Android 依赖单元，本文件直接驱动：
 * - A14 游离点击 → [MainDispatchGate]（超时取消标记 / 原子终结权 CAS）
 * - A15 双引擎 → [ScanLifecycle]（单飞状态机：忙拒绝、有界 join、超时放行）
 * - A13 CME → [ResultsSnapshot]（有界重试快照）
 *
 * 「exportNow 整体 runCatching 不杀线程」无法在 JVM 稳定构造引擎桩（engine 是 ScanEngine
 * 具体类，构造需 assets/OCR 管线）——验证方式：真机 DEBUG_SCAN_FLOW 注入挂死触发看门狗
 * 导出，观察 logcat BetterGI.ScanRunner 无未捕获异常且导出完成；或 Stage 2D 引入
 * Robolectric/依赖注入后补桩测试。本文件覆盖 ResultsSnapshot 的重试语义（含中断容忍）。
 * 项目无 kotlinx-coroutines-test 依赖（Stage 2D 事项），协程用例用 runBlocking + 真实短延时。
 */
class ScriptRunnerTest {

    // ---- A14：超时后 runnable 不再执行（游离点击修复）----

    @Test
    fun `dispatch gate returns block result when dispatched in time`() {
        val result = MainDispatchGate.dispatch(
            post = { it.run() }, // 直通执行器：模拟主线程立即执行
            removePending = { },
            timeoutMs = 1_000,
            block = { 42 },
        )
        assertEquals(42, result)
    }

    @Test
    fun `dispatch gate timeout cancels queued task before it runs`() {
        val queue = ArrayList<Runnable>()
        var task: Runnable? = null
        var removed = false
        var executed = false
        var onTimeoutCancelledClean: Boolean? = null
        val t0 = System.nanoTime()
        val result = MainDispatchGate.dispatch(
            post = { queue.add(it); task = it }, // 只入队不执行：模拟主线程卡死
            removePending = { queue.remove(it); removed = true },
            timeoutMs = 100,
            onTimeout = { onTimeoutCancelledClean = it },
            block = { executed = true; "ok" },
        )
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        // 超时语义不变：返回 null，且确实等满超时窗口
        assertNull(result)
        assertTrue("should wait ~timeoutMs, was ${elapsedMs}ms", elapsedMs >= 90)
        // task 未开始执行 ⇒ 干净取消
        assertEquals(true, onTimeoutCancelledClean)
        // removePending 把未派发的 task 摘除（双重保险）
        assertTrue(removed)
        assertTrue(queue.isEmpty())
        // 核心断言：模拟「主线程此前已把 task 取出但尚未执行」的极端时序——此刻才 run
        // ⇒ 入口 CAS 失败 ⇒ block 不执行（A14 游离点击修复的核心性质）
        task!!.run()
        assertFalse(executed)
    }

    // ---- A15：单飞状态机（stop→start 原子化）----

    @Test
    fun `lifecycle refuses new job while previous one unfinished`() {
        val lc = ScanLifecycle(stopJoinTimeoutMs = 200)
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            runBlocking {
                val first = scope.launch { delay(2_000) }
                assertNull(lc.tryAttach(first))
                // 忙判据：运行中/收尾中都拒绝（收尾中 isActive 已是 false，isCompleted 才是准确口径）
                assertSame(first, lc.unfinishedJob())
                val loser = scope.launch { delay(2_000) }
                assertSame(first, lc.tryAttach(loser)) // 拒绝：返回未终结的旧 job（竞态输家自取消的依据）
                assertSame(first, lc.unfinishedJob()) // 内部引用未被顶掉
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `stop joins wind-down so immediate restart has no double engine`() {
        val lc = ScanLifecycle(stopJoinTimeoutMs = 2_000)
        val scope = CoroutineScope(Dispatchers.Default)
        // AtomicBoolean：跨线程写（Default worker）→ 读（测试线程），不用普通捕获 var 赌可见性
        val windDownDone = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            runBlocking {
                // ⚠️ launch 只是把协程入队；必须等协程体真正开始执行再 stop，
                // 否则 cancel 发生在启动前 ⇒ finally 不跑 ⇒ 测的不是「等收尾」
                val started = CountDownLatch(1)
                val first = scope.launch {
                    try {
                        started.countDown()
                        delay(60_000) // 模拟扫描中
                    } finally {
                        // ⚠️ 取消态下裸 delay 会立即抛 CancellationException（收尾根本不耗时），
                        // 必须用 NonCancellable 才能模拟「不可中断的收尾段」——join 等的正是它
                        withContext(NonCancellable) { delay(100) }
                        windDownDone.set(true)
                    }
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                assertNull(lc.tryAttach(first))
                val outcome = lc.stop()
                // 有界 join 成功：stop 返回时旧协程（含收尾）已真正终结 ⇒ 立即 start 无双引擎
                assertTrue(outcome is ScanLifecycle.StopOutcome.Settled)
                assertTrue(first.isCompleted)
                assertTrue(windDownDone.get())
                assertNull(lc.unfinishedJob())
                assertNull(lc.tryAttach(scope.launch { delay(1) })) // 停止后立即可重启
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `stop timeout releases start with documented residual window`() {
        val lc = ScanLifecycle(stopJoinTimeoutMs = 50)
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            runBlocking {
                val started = CountDownLatch(1) // 同上：等协程体开始执行再 stop，消除启动竞态
                val first = scope.launch {
                    try {
                        started.countDown()
                        delay(60_000)
                    } finally {
                        // NonCancellable：模拟不可中断收尾段（裸 delay 在取消态下会立即返回）
                        withContext(NonCancellable) { delay(500) } // 收尾远超 join 上限 ⇒ 必超时
                    }
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                assertNull(lc.tryAttach(first))
                val outcome = lc.stop()
                // 工单口径「超时兜底放行」：引用已清、立即 start 不被拒，
                // 代价是残余窗口（旧协程仍在收尾，至多推进到下一个取消检查点）——
                // LooseEnd 就是给调用方打日志用的，在此固化该语义。
                assertTrue(outcome is ScanLifecycle.StopOutcome.LooseEnd)
                assertFalse(first.isCompleted)
                assertNull(lc.unfinishedJob())
            }
        } finally {
            scope.cancel()
        }
    }

    // ---- A13：有界重试快照（真 CME 并发不稳定，用「第 N 次才成功」的桩模拟冲突）----

    @Test
    fun `snapshot retries concurrent modification then succeeds`() {
        val calls = AtomicInteger(0)
        val out = ResultsSnapshot.withRetry(retries = 3, retryDelayMs = 1) {
            if (calls.incrementAndGet() == 1) throw ConcurrentModificationException("simulated")
            listOf("a", "b")
        }
        assertEquals(listOf("a", "b"), out)
        assertEquals(2, calls.get())
    }

    @Test
    fun `snapshot rethrows after retries exhausted`() {
        // 耗尽后原样抛出 ⇒ 由 exportNow 的 runCatching 兜底，绝不裸抛杀线程/进程
        assertThrows(ConcurrentModificationException::class.java) {
            ResultsSnapshot.withRetry(retries = 3, retryDelayMs = 1) {
                throw ConcurrentModificationException("always conflicting")
            }
        }
    }

    @Test
    fun `snapshot survives interrupt during retry sleep`() {
        // 看门狗收尾 wd.interrupt() 恰好打断重试睡眠：不吞中断标记、立即补试仍能拿到快照
        Thread.currentThread().interrupt()
        try {
            val calls = AtomicInteger(0)
            val out = ResultsSnapshot.withRetry(retries = 3, retryDelayMs = 60_000) {
                // 中断标记已置 ⇒ sleep 立即抛 InterruptedException，验证补试路径
                if (calls.incrementAndGet() == 1) throw ConcurrentModificationException("simulated")
                listOf(1)
            }
            assertEquals(listOf(1), out)
            assertTrue(Thread.currentThread().isInterrupted) // 标记保留（不吞）
        } finally {
            Thread.interrupted() // 清标记，避免污染同线程后续测试
        }
    }
}
