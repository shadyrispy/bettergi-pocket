package com.bettergi.pocket.capture

import com.bettergi.pocket.core.IntRect
import kotlinx.coroutines.delay
import org.opencv.core.Mat

/**
 * 投影流与「动作后取新帧」语义的时序基准：
 * ImageReader 帧时间戳（ns）与 SystemClock（ms）时间基准不同，不可换算。
 * [markActionAt] 记录动作时刻，并同时快照「动作前最后一帧」的时间戳作为阈值；
 * [grabFresh] 只要拿到 timestamp 严格大于该阈值的帧，即为动作后新帧。
 */
class FrameTimeoutException(
    val afterTimestampMs: Long,
    val timeoutMs: Long,
) : Exception("no fresh frame after $afterTimestampMs within ${timeoutMs}ms")

/**
 * 帧源抽象。扫描引擎唯一依赖：投影为唯一帧源（无障碍截图回退已删除，投影授权是扫描硬前提）。
 *
 * 帧币种统一为 BGR [Mat]（与 CaptureContent/TriggerEngine 一致），全链路不落 Bitmap。
 */
interface FrameSource {
    /**
     * 阻塞直到拿到时间戳晚于 [afterTimestampMs] 标记帧的新帧；超时抛 [FrameTimeoutException]。
     *
     * @param afterTimestampMs 此前调用 [markActionAt] 记录的动作时刻（SystemClock 基准）。
     */
    suspend fun grabFresh(afterTimestampMs: Long, timeoutMs: Long = 2500L): Mat

    /** 动作原语执行完成后调用，标记后续 [grabFresh] 应取此时刻之后的新帧。 */
    fun markActionAt(timestampMs: Long)

    /**
     * ★ 就绪信号直采（2026-09-12）：采样 [rect] 的**分块 RGB 均值签名**，写入 [out]
     * （长度 ≥ `blocksX*blocksY*3`；格式与 `VoteJudges.thumbChangedFraction` 对齐）。
     * **零 Mat、零分配**，成本约 1µs，用来取代「抓帧 + OCR」的就绪轮询。
     *
     * ⚠️ **默认实现返回 false = 不支持**（与 `ActionGateway.resetPassthrough` 同策略：
     * 默认不破坏既有实现/单测 mock）⇒ 调用方必须**回退旧路径**。
     */
    fun sampleSignature(
        rect: IntRect,
        out: ByteArray,
        blocksX: Int = 8,
        blocksY: Int = 4,
    ): Boolean = false

    /**
     * 帧代数（缓存帧时间戳 ns）。**同一帧的两次采样必然相同** ⇒ 用它排除
     * 「拿同一帧自己比自己」造成的**假稳定**（轮询步长 < 帧间隔时必现）。默认 0 = 不支持。
     */
    fun frameGeneration(): Long = 0L

    // ---- TriggerEngine 实时触发语义（保持既有行为，零变化）----
    fun acquireLatestBgr(): CapturedBgrFrame?
    fun discardLatestImages()
    fun capturedSize(): Pair<Int, Int>?
    fun isRunning(): Boolean

    /** 释放帧源内部资源；不负责底层投影启停（由持有 ScreenCaptureController 的服务管理）。 */
    fun release()
}

/**
 * 「动作后取新帧」的代数簿记（A5，从 [ProjectionFrameSource] 提出的**纯 JVM** 决策逻辑，便于单测；
 * 状态与语义与提出前逐位一致）。
 *
 * - [lastSeenFrameNs]：本对象见过的最新帧代数（= ImageReader 帧时间戳 ns，只前进不后退）。
 *   三条路径都会推进它：grabFresh 实际取到的帧、TriggerEngine 实时路径的 acquireLatestBgr、
 *   以及 A5 的**零成本代数观察**——controller 已发布新帧但判定"必然不新鲜"而跳过取帧时，
 *   也要把 lastSeen 推上去，否则下一次 markAction 会把阈值快照回退到更老的帧，
 *   动作前的旧帧就会被误判"新鲜"（这是短路优化必须配套的簿记，不是可有可无）。
 * - [thresholdFrameNs]：markAction 时刻的 lastSeen 快照（"动作前最后一帧"）；
 *   动作后第一帧严格新于它。
 */
internal class FrameFreshnessBook {
    var lastSeenFrameNs: Long = Long.MIN_VALUE
        private set
    var thresholdFrameNs: Long = Long.MIN_VALUE
        private set

    /** 观察到缓存代数 [gen]（单调：乱序到达的旧值忽略）。 */
    fun observe(gen: Long) {
        if (gen > lastSeenFrameNs) lastSeenFrameNs = gen
    }

    /** 动作受理：阈值快照 = 动作前最后见过的帧。 */
    fun markAction() {
        thresholdFrameNs = lastSeenFrameNs
    }

    /** 帧 [ts] 是否"动作后新帧"（严格新于阈值）。 */
    fun isFresh(ts: Long): Boolean = ts > thresholdFrameNs

    /**
     * ★ A5 短路决策：controller 缓存代数为 [gen] 时，这一轮**值不值得**做「取帧 + 全帧 RGBA→BGR」。
     *
     * false 的两种情形（frameGeneration 与缓存同源同锁 ⇒ 结论可靠）：
     * - `gen == 0`：无缓存帧，acquireLatestBgr 必返 null（0 只在复位/未出首帧时出现，
     *   ImageReader 真实帧时间戳不会是 0）；
     * - `gen <= thresholdFrameNs`：缓存帧仍在动作阈值之前，取回来也必然 isFresh=false 被 release。
     *   旧实现在 2.5s 超时窗口里每 25ms 白做一次全帧转换（最坏 ~100 次 ≈ 1.7GB 垃圾），
     *   现在只读一个代数就跳过。`gen > threshold` ⇒ 可能是新帧 ⇒ 走完整路径，由 isFresh 定夺。
     */
    fun worthAcquiring(gen: Long): Boolean = gen != 0L && gen > thresholdFrameNs
}

/**
 * [FrameSource] 唯一实现：包装 [ScreenCaptureController]（常驻丢帧流 acquireLatestBgr）。
 *
 * - TriggerEngine 路径：方法一一转发，零行为变化。
 * - 扫描路径 [grabFresh]：轮询取帧，命中「timestampNs > markActionAt 快照阈值」的新帧；
 *   投影流帧率 ≥30fps，动作后等待新帧延迟 <150ms（方案验收指标，真机验证）。
 *   ★ A5：取帧前先比 `frameGeneration()` 代数，代数没变（缓存还是动作阈值前那帧）就跳过
 *   「取帧 + 全帧 RGBA→BGR」，只推进簿记——deadline 与"等动作后新帧"的语义完全不变。
 */
class ProjectionFrameSource(
    private val controller: ScreenCaptureController,
) : FrameSource {
    private val bookLock = Any()
    private val book = FrameFreshnessBook()

    override suspend fun grabFresh(afterTimestampMs: Long, timeoutMs: Long): Mat {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            // ★ A5：先比代数再取帧。frameGeneration() 只读一个 volatile（零分配/零转换）；
            //   代数没变 ⇒ acquireLatestBgr 返回的必然还是那一帧（poller 只在新帧时更新缓存），
            //   取帧 + 全帧 RGBA→BGR + release 全是白做。
            val gen = controller.frameGeneration()
            if (book.worthAcquiring(gen)) {
                val frame = controller.acquireLatestBgr()
                if (frame != null) {
                    val fresh = synchronized(bookLock) {
                        book.observe(frame.timestampNs)
                        book.isFresh(frame.timestampNs)
                    }
                    if (fresh) {
                        return frame.bgr
                    }
                    frame.bgr.release()
                }
            } else if (gen != 0L) {
                // 不取帧也要把 lastSeen 推上去（见 FrameFreshnessBook.observe 的说明）：
                // 否则下一次 markActionAt 的阈值快照回退 ⇒ 动作前旧帧被误判"新鲜"。
                synchronized(bookLock) { book.observe(gen) }
            }
            if (System.currentTimeMillis() >= deadline) {
                throw FrameTimeoutException(afterTimestampMs, timeoutMs)
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    override fun markActionAt(timestampMs: Long) {
        synchronized(bookLock) {
            // 阈值 = 动作前最后一帧；动作后第一帧严格更新于它
            book.markAction()
        }
    }

    override fun acquireLatestBgr(): CapturedBgrFrame? {
        val frame = controller.acquireLatestBgr() ?: return null
        synchronized(bookLock) {
            book.observe(frame.timestampNs)
        }
        return frame
    }

    override fun discardLatestImages() = controller.discardLatestImages()

    override fun sampleSignature(rect: IntRect, out: ByteArray, blocksX: Int, blocksY: Int): Boolean =
        controller.sampleSignature(rect, out, blocksX, blocksY)

    override fun frameGeneration(): Long = controller.frameGeneration()

    override fun capturedSize(): Pair<Int, Int>? = controller.capturedSize()

    override fun isRunning(): Boolean = controller.isRunning()

    override fun release() {
        // 投影启停由 TriggerForegroundService 直接管理 ScreenCaptureController，此处无资源需释放
    }

    private companion object {
        const val POLL_INTERVAL_MS = 25L
    }
}
