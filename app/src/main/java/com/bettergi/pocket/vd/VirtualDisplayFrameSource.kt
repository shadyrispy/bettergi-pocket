package com.bettergi.pocket.vd

import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.bettergi.pocket.capture.CapturedBgrFrame
import com.bettergi.pocket.capture.FrameSource
import com.bettergi.pocket.core.IntRect
import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * ★ #310/M3：后台模式的帧源（虚拟显示器 ImageReader → BGR Mat）。
 *
 * 与前台 `ProjectionFrameSource` 的语义对齐点（引擎零改动的关键）：
 * - `frameGeneration()` = 最新缓存帧的时间戳 ns（同一帧两次采样必然相同——判稳
 *   `awaitGridStable`/签名就绪 `sampleSignature` 的"代数不变"判据直接成立）。
 * - `grabFresh(afterTimestampMs)`：`afterTimestampMs` 是 **SystemClock 基准的动作时刻**
 *   （前台模式 ImageReader 的 timestampNs 与 SystemClock 不同源，不可比，所以前台版用
 *   FrameFreshnessBook 的阈值快照绕开换算）。本实现取 **capture 时刻的 SystemClock 墙钟**
 *   作为帧时间戳（`SystemClock.elapsedRealtime() * 1_000`），与动作时刻同源可直比：
 *   动作后第一帧 = 时间戳严格晚于动作时刻的第一帧。语义与前台版等价，但**少一个簿记层**
 *   （不需要 FrameFreshnessBook 的阈值快照——墙钟同源后直接比）。
 *   ⚠️ 取舍：VD 挂起期间（虚拟屏休眠）没有新帧，grabFresh 按超时抛——与前台投影断流同形。
 * - `acquireLatestBgr/discardLatestImages`：TriggerEngine 实时触发语义的后台形态
 *   （M3 时 autoPick/autoSkip 仍在后台模式禁用 ⇒ 这两个方法只为接口完整，返回 null/空）。
 * - `sampleSignature`：分块 RGB 均值签名——复用缓存的 RGBA 快照，纯数组运算，零 Mat。
 *
 * 帧生命周期：`onImageAvailable`（取最新、丢积压）→ RGBA 快照进缓存槽（双缓冲）→
 * `grabFresh/acquireLatestBgr` 从缓存转 BGR。**双缓冲 + 单写者回调线程** ⇒ 读者拿到的
 * 快照要么旧要么新、无撕裂（同一思想：MAA 三重缓冲在本仓的规模下收敛为双缓冲——
 * 单消费者读缓存拷贝的耗时远小于帧间隔，第三槽无增益）。
 */
class VirtualDisplayFrameSource(
    private val displayId: Int,
    private val width: Int,
    private val height: Int,
) : FrameSource {

    companion object {
        private const val TAG = "BetterGI.VD"
        private const val MAX_IMAGES = 5

        /**
         * ★ #310/M5：黑帧的代数标记（正常代数 = capture 时刻 elapsedRealtime ns，恒 > 0；
         * 只有 [publishBlackFrame] 写入的快照才带 -1，消费方可据此识别"已知黑"）。
         */
        const val BLACK_FRAME_GENERATION: Long = -1L
    }

    private val readerThread = HandlerThread("vd-frame-reader").also { it.start() }
    private val readerHandler = Handler(readerThread.looper)
    private val reader: ImageReader =
        ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, MAX_IMAGES)

    // ── 缓存槽（回调线程写、消费线程读；@Volatile 引用切换 = 无撕裂）──
    class Snapshot(val rgba: ByteArray, val width: Int, val height: Int, val rowStride: Int)

    @Volatile
    private var cached: Snapshot? = null

    /** 最新缓存帧的"帧代数"（capture 时刻 elapsedRealtime 的 ns 表示）。 */
    @Volatile
    private var generation: Long = 0L

    @Volatile
    private var running = false

    /** 动作新鲜度阈值的专属锁（与前台 FrameFreshnessBook 同语义，见 [markActionAt]）。 */
    private val bookLock = Any()

    /** 最近一次 [markActionAt] 时刻的代数快照（"动作前最后一帧"）。 */
    private var actionThresholdNs = Long.MIN_VALUE

    private val listener = ImageReader.OnImageAvailableListener { r ->
        var img: Image? = null
        try {
            img = r.acquireLatestImage() ?: return@OnImageAvailableListener
            val plane = img.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            require(pixelStride == 4) { "RGBA_8888 但 pixelStride=$pixelStride" }
            val w = img.width
            val h = img.height
            val buf = java.nio.ByteBuffer.allocate(w * h * 4)
            // ⚠️ rowStride 可能 > w*4（对齐填充）⇒ 逐行拷，不整块 get
            val src = plane.buffer
            for (row in 0 until h) {
                src.position(row * rowStride)
                src.limit(row * rowStride + w * 4)
                buf.put(src.slice())
            }
            buf.rewind()
            val bytes = buf.array()
            cached = Snapshot(bytes, w, h, w * 4)
            generation = SystemClock.elapsedRealtime() * 1_000L
        } catch (e: Exception) {
            Log.w(TAG, "vd frame capture failed: ${e.message}")
        } finally {
            runCatching { img?.close() }
        }
    }

    fun start() {
        if (running) return
        running = true
        reader.setOnImageAvailableListener(listener, readerHandler)
        Log.i(TAG, "vd frame source started display=$displayId ${width}x$height")
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { reader.close() }
        readerHandler.looper.quitSafely()
        readerThread.quitSafely()
        Log.i(TAG, "vd frame source stopped")
    }

    /** Surface 供 VirtualDisplayManager.create 用（本 reader 的 surface）。 */
    fun surface(): android.view.Surface = reader.surface

    /**
     * ★ #310/M5：最新 RGBA 快照的**只读借用**（JPEG 预览用，避免为了压图再拷一遍全帧）。
     * ⚠️ 借用不拷贝：返回后回调线程可能随时换掉缓存槽引用（旧数组不被改写，读到的
     * 内容要么旧要么新、无撕裂——与缓存槽的 @Volatile 引用切换同一语义）。预览侧若
     * 撞上帧切换，至多 JPEG 内容混代（下一拍轮询自然修正），不值得为它加锁拷贝。
     */
    fun latestRgbaSnapshot(): Snapshot? = cached

    /**
     * ★ #310/M5：把缓存槽替换成**全 0 RGBA 黑帧**（VdBlackFrameGuard 确认游戏死透后的
     * 止血动作——下游看到的是一块已知黑，而不是无限旧的最后一帧）。
     *
     * TOCTOU 校验：进入时快照 [genBefore]，提交前再读一次当前代数——期间有新帧落地
     * （游戏又出帧了）⇒ 返回 false **不覆盖**（这正是"自然恢复"路径，黑帧不许把它盖掉）。
     * 成功发布 ⇒ generation = [BLACK_FRAME_GENERATION]（-1，特殊标记；guard 看到代数
     * 变化会清 streak，游戏回来后新帧自然覆盖黑帧）。
     *
     * @return true = 已发布；false = 帧源已停 / 无缓存帧（无内容可黑）/ TOCTOU 拒绝
     */
    fun publishBlackFrame(): Boolean {
        if (!running) return false
        val genBefore = generation
        if (genBefore == BLACK_FRAME_GENERATION) return false // 已经是黑帧，不重复发布
        val current = cached ?: return false // 无帧可黑（空缓存：预览本来就没有，无需止血）
        val black = ByteArray(current.rgba.size) // 全 0 = RGBA(0,0,0,255)? 否——0,0,0,0 透明。
        // ⚠️ RGBA 全 0 的 alpha=0，JPEG 压后仍是黑（JPEG 无 alpha，压前合成到黑底），
        //   BGR 转换后也是 (0,0,0)。保持全 0：分配即清零，最大快照 1280x720x4 ≈ 3.5MB，
        //   不额外再填一遍 alpha。
        val genAfter = generation
        if (genAfter != genBefore) {
            Log.d(TAG, "publishBlackFrame：TOCTOU 拒绝（gen $genBefore -> $genAfter，帧又动了）")
            return false
        }
        cached = Snapshot(black, current.width, current.height, current.rowStride)
        generation = BLACK_FRAME_GENERATION
        Log.w(TAG, "缓存槽已替换为黑帧（${current.width}x${current.height}，generation=-1）")
        return true
    }

    /** RGBA 快照的 BGR Mat 转换（像素运算独立成函数：可单测、可复用于签名）。 */
    private fun snapshotToBgr(s: Snapshot): Mat {
        val m = Mat(s.height, s.width, CvType.CV_8UC3)
        val dst = ByteArray(s.width * s.height * 3)
        val src = s.rgba
        var si = 0
        var di = 0
        // RGBA → BGR：每像素 4 进 3 出（行 stride 已在 Snapshot 归一为 w*4）
        val rows = s.height
        val cols = s.width
        var r = 0
        while (r < rows) {
            si = r * s.rowStride
            var c = 0
            while (c < cols) {
                dst[di] = src[si + 2]     // B
                dst[di + 1] = src[si + 1] // G
                dst[di + 2] = src[si]     // R
                si += 4
                di += 3
                c++
            }
            r++
        }
        m.put(0, 0, dst)
        return m
    }

    // ── FrameSource ──

    override suspend fun grabFresh(afterTimestampMs: Long, timeoutMs: Long): Mat {
        // ★ 2026-10-19 修（**后台漏件的真正根因**）：引擎调用恒为 `grabFresh(0L, …)`
        //   （见 ScanGridDomain.grabFrameOrNull），阈值完全靠 [markActionAt] 记录。
        //   本类此前 [markActionAt] 是空实现、且这里用 `afterTimestampMs * 1000 = 0` 判
        //   `g > 0` ⇒ **恒真**：点击后立刻返回缓存帧，而那一瞬缓存常仍是**上一张卡的面板**
        //   ⇒ 读到旧件 ⇒ contentKey 重复 ⇒ 被去重误杀 ⇒ 全量漏件（前台靠 FrameFreshnessBook
        //   记阈值，无此问题）。此处改为与前台同语义：阈值 = markActionAt 时刻的代数快照，
        //   取到"严格晚于该快照"的帧才算动作后新帧。
        val thresholdNs = if (afterTimestampMs > 0L) {
            afterTimestampMs * 1_000L
        } else {
            synchronized(bookLock) { actionThresholdNs }
        }
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val s = cached
            val g = generation
            if (s != null && g > thresholdNs) {
                return snapshotToBgr(s)
            }
            kotlinx.coroutines.delay(25)
        }
        throw com.bettergi.pocket.capture.FrameTimeoutException(afterTimestampMs, timeoutMs)
    }

    /**
     * 动作时刻登记：阈值快照 = 当前代数（动作前最后一帧）→ 后续 [grabFresh] 取严格更新的帧。
     * 与前台 `ProjectionFrameSource.markActionAt` 的 `FrameFreshnessBook.markAction()` 同语义。
     */
    override fun markActionAt(timestampMs: Long) {
        synchronized(bookLock) { actionThresholdNs = generation }
    }

    override fun newFreshnessScope(): FrameSource = this // 单消费者模式，无需隔离账本

    override fun sampleSignature(rect: IntRect, out: ByteArray, blocksX: Int, blocksY: Int): Boolean {
        val s = cached ?: return false
        // 与 VoteJudges.thumbChangedFraction 的分块均值格式对齐：每块 RGB 三字节
        val bw = s.width / blocksX
        val bh = s.height / blocksY
        var oi = 0
        for (by in 0 until blocksY) {
            for (bx in 0 until blocksX) {
                val x0 = bx * bw
                val y0 = by * bh
                var rs = 0L; var gs = 0L; var bs = 0L; var n = 0L
                // 块内抽样（步长=块宽/4，4x4=16 点）——签名只求稳定不追求全量均值
                var dy = 0
                while (dy < bh) {
                    val y = y0 + dy
                    var dx = 0
                    while (dx < bw) {
                        val si = y * s.rowStride + (x0 + dx) * 4
                        bs += s.rgba[si].toInt() and 0xFF
                        gs += s.rgba[si + 1].toInt() and 0xFF
                        rs += s.rgba[si + 2].toInt() and 0xFF
                        n++
                        dx += (bw / 4).coerceAtLeast(1)
                    }
                    dy += (bh / 4).coerceAtLeast(1)
                }
                if (oi + 3 > out.size) return false
                out[oi++] = (rs / n).toByte()
                out[oi++] = (gs / n).toByte()
                out[oi++] = (bs / n).toByte()
            }
        }
        return true
    }

    override fun frameGeneration(): Long = generation

    override fun acquireLatestBgr(): CapturedBgrFrame? {
        val s = cached ?: return null
        return CapturedBgrFrame(s.width, s.height, snapshotToBgr(s), generation)
    }

    override fun discardLatestImages() {
        runCatching { reader.acquireLatestImage()?.close() }
    }

    override fun capturedSize(): Pair<Int, Int>? = if (running) width to height else null

    override fun isRunning(): Boolean = running

    override fun release() {
        stop()
    }
}
