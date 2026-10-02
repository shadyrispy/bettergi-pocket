package com.bettergi.pocket.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FrameFreshnessBook]（A5 从 ProjectionFrameSource 提出的「动作后取新帧」簿记）的纯 JVM 决策测试。
 *
 * 钉两条语义：
 * 1. **短路决策**（worthAcquiring）：缓存代数 ≤ 阈值 ⇒ 取帧 + 全帧 RGBA→BGR 必然白做
 *    （A5 要消灭的 2.5s 窗口 ~1.7GB 垃圾）；代数 0 = 无缓存帧，同样跳过。
 * 2. **簿记完整性**（observe）：跳过取帧的年代数也必须推进 lastSeen —— 否则下一次
 *    markAction 的阈值快照回退到更老的帧，动作前旧帧会被误判"新鲜"（等"动作后的新帧"
 *    语义被静默破坏，比浪费内存更严重）。
 *
 * ⚠️ 真机/仪器验证项（JVM 解耦不了，isReturnDefaultValues 下 ScreenCaptureController 依赖
 * Android framework 无法实例化）：
 * - grabFresh 循环端到端（deadline 到点抛 FrameTimeoutException、POLL_INTERVAL_MS 步长、
 *   与 TriggerEngine 实时路径并发推进 lastSeen）；
 * - controller 侧 stop 两阶段停机的标志序列（retire 先换代 → 锁内 close → 锁外 join）——
 *   投影开关压测 20 次（轨 A 设备）。
 */
class FrameFreshnessBookTest {

    @Test
    fun `mark before any frame makes every real frame fresh`() {
        val book = FrameFreshnessBook()
        book.markAction() // 无 lastSeen 时动作：阈值 = MIN（与旧实现逐位一致）
        assertTrue("阈值 MIN ⇒ 任何真实帧都算动作后新帧", book.isFresh(1L))
        assertTrue("同理值得走完整取帧路径", book.worthAcquiring(1L))
    }

    @Test
    fun `same generation as threshold is not fresh and not worth acquiring`() {
        val book = FrameFreshnessBook()
        book.observe(100L)
        book.markAction() // 阈值 = 100
        assertFalse("缓存帧就是动作前最后一帧 ⇒ 不新鲜", book.isFresh(100L))
        assertFalse("取回来也必然被 release ⇒ 整帧转换是白做（A5 短路本体）", book.worthAcquiring(100L))
    }

    @Test
    fun `generation at or below threshold never worth acquiring`() {
        val book = FrameFreshnessBook()
        book.observe(100L)
        book.markAction()
        assertFalse(book.worthAcquiring(99L))
        assertFalse(book.isFresh(99L))
    }

    @Test
    fun `newer generation after mark is worth acquiring and fresh`() {
        val book = FrameFreshnessBook()
        book.observe(100L)
        book.markAction()
        assertTrue("动作后新帧必须走完整路径", book.worthAcquiring(200L))
        assertTrue(book.isFresh(200L))
    }

    @Test
    fun `zero generation means no cached frame and is skipped`() {
        val book = FrameFreshnessBook()
        book.observe(100L)
        book.markAction()
        assertFalse("代数 0 = 缓存被复位/未出首帧 ⇒ acquireLatestBgr 必返 null，不值得问", book.worthAcquiring(0L))
    }

    @Test
    fun `observe is monotonic against out of order generations`() {
        val book = FrameFreshnessBook()
        book.observe(100L)
        book.observe(50L)
        assertTrue("乱序旧代数不得回退 lastSeen", book.lastSeenFrameNs == 100L)
    }

    @Test
    fun `observing skipped generations keeps the next threshold honest`() {
        // A5 的关键回归钉：markAction → (代数推进到 150 但判定不新鲜、跳过取帧) → 再 markAction。
        // 若跳过取帧时不同步 observe，阈值会停在 100 ⇒ 动作前的旧帧 120 被误判"新鲜"。
        val book = FrameFreshnessBook()
        book.observe(100L)
        book.markAction() // 第一次动作：阈值 = 100
        assertTrue(book.worthAcquiring(150L))
        book.observe(150L) // 模拟短路路径的零成本观察（没取帧）
        book.markAction() // 第二次动作：阈值必须推进到 150
        assertFalse("动作前旧帧 120 不得被误判新鲜", book.isFresh(120L))
        assertFalse("阈值帧自身不算新鲜", book.isFresh(150L))
        assertTrue("严格新于阈值的帧才算动作后新帧", book.isFresh(151L))
    }
}
