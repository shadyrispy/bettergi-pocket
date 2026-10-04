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

    // ───────────────── 批0「分账」的机制测试（#198，2026-10-04）─────────────────
    //
    // 分账做的事很窄：`ProjectionFrameSource.newFreshnessScope()` 返回一个**自带一本 book** 的视图
    // （`FrameSource.kt:203`），于是常驻的 TriggerEngine（自动对话/自动拾取）与扫描各推各的阈值。
    //
    // ⚠️ 为什么把验证放在这一层：#198 原计划是"设备上开自动对话跑 2 页，比对超时/旧帧顶替计数"，
    //   但那条在 BlueStacks 上**必然是空跑** —— AutoSkipFeature 反应的是世界场景顶部的聊天气泡
    //   （它唯一的动作日志是 `click top chat icon at …`），而扫描期间画面在背包里，
    //   跳过点击根本不会发生 ⇒ 开与不开两个读数恒等，"没差异"证明不了任何事。
    //   设备侧真要验，得造出"扫描期间出现对话弹窗"的场景（小米 15 那台才有），仍挂在 #198。
    //   这里测的是机制本身，并且带**负对照**：共用一本 book 时干扰必须复现，
    //   否则"两本 book 互不影响"这条断言可以靠什么都不做就通过。

    @Test
    fun `SHARED book lets the trigger push the scan's threshold - the original bug`() {
        // 负对照：这就是批0 之前的形状。同一个 FrameSource 喂两个消费者 ⇒ 一本 book。
        val shared = FrameFreshnessBook()
        shared.observe(100L)
        shared.markAction() // 扫描点击被受理：阈值 = 100
        shared.observe(101L) // 投影推出动作后的第一帧 —— 这正是扫描要的那一帧
        assertTrue("扫描本该认为 101 是动作后新帧", shared.isFresh(101L))

        shared.observe(101L)
        shared.markAction() // 自动跳过也 mark 了同一本账：阈值被推到 101
        assertFalse(
            "干扰复现：扫描要的 101 被自动跳过推成『不新鲜』⇒ grabFresh 只能继续等帧，" +
                "超时后退回旧帧（= 拿动作之前的画面去 OCR）",
            shared.isFresh(101L),
        )
        assertFalse("连带短路：连取帧都不值得做", shared.worthAcquiring(101L))
    }

    // ⚠️ **这条负对照不证明隔离已经生效**。它只钉住"共用一本 book 会怎样"，
    //    这样将来重构 book 时那条干扰路径不会被人无意间重新造出来。
    //    真正的隔离是两处接缝：`FrameSource.kt:203` 的 `newFreshnessScope()` 返回自带新 book 的视图，
    //    以及 `TriggerForegroundService.kt:161` 确实调了它、并把 markActionAt 只发给那个视图。
    //    两处都在 JVM 里测不到：`ProjectionFrameSource` 要 `ScreenCaptureController`（依赖 Android
    //    framework，见本文件头的"真机/仪器验证项"），而服务装配那段没有 Robolectric 覆盖。
    //    ⇒ 别把上面这条绿读成"批0 分账已验证"。设备侧那半仍挂在 #198，要等能造出
    //    "扫描期间有对话弹窗"的那台机器（小米 15）才算数。
}
