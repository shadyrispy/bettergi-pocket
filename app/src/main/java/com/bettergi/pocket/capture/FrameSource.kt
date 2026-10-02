package com.bettergi.pocket.capture

import com.bettergi.pocket.recognition.IntRect
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
 * [FrameSource] 唯一实现：包装 [ScreenCaptureController]（常驻丢帧流 acquireLatestBgr）。
 *
 * - TriggerEngine 路径：方法一一转发，零行为变化。
 * - 扫描路径 [grabFresh]：轮询取帧，命中「timestampNs > markActionAt 快照阈值」的新帧；
 *   投影流帧率 ≥30fps，动作后等待新帧延迟 <150ms（方案验收指标，真机验证）。
 */
class ProjectionFrameSource(
    private val controller: ScreenCaptureController,
) : FrameSource {
    private val lock = Any()

    @Volatile
    private var thresholdFrameNs: Long = Long.MIN_VALUE

    @Volatile
    private var lastSeenFrameNs: Long = Long.MIN_VALUE

    override suspend fun grabFresh(afterTimestampMs: Long, timeoutMs: Long): Mat {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val frame = controller.acquireLatestBgr()
            if (frame != null) {
                val fresh = synchronized(lock) {
                    if (frame.timestampNs > lastSeenFrameNs) lastSeenFrameNs = frame.timestampNs
                    frame.timestampNs > thresholdFrameNs
                }
                if (fresh) {
                    return frame.bgr
                }
                frame.bgr.release()
            }
            if (System.currentTimeMillis() >= deadline) {
                throw FrameTimeoutException(afterTimestampMs, timeoutMs)
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    override fun markActionAt(timestampMs: Long) {
        synchronized(lock) {
            // 阈值 = 动作前最后一帧；动作后第一帧严格更新于它
            thresholdFrameNs = lastSeenFrameNs
        }
    }

    override fun acquireLatestBgr(): CapturedBgrFrame? {
        val frame = controller.acquireLatestBgr() ?: return null
        synchronized(lock) {
            if (frame.timestampNs > lastSeenFrameNs) lastSeenFrameNs = frame.timestampNs
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
