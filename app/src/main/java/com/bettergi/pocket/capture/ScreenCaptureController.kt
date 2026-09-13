package com.bettergi.pocket.capture

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.bettergi.pocket.recognition.IntRect
import com.bettergi.pocket.recognition.opencv.MatOps
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

private data class DisplaySpec(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
)

class ScreenCaptureController(
    private val context: Context,
    private val onStoppedExternally: () -> Unit = {},
) {
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var callback: MediaProjection.Callback? = null
    private var densityDpi: Int = DisplayMetrics.DENSITY_DEVICE_STABLE
    private var hasCachedFrame = false
    private var cachedWidth = 0
    private var cachedHeight = 0
    private var cachedTimestampNs = 0L
    private var lastFrameElapsedMs = 0L
    private var lastRecoverElapsedMs = 0L
    private var displayCreatedElapsedMs = 0L
    private var firstFrameLogged = false

    private var cachedRgba = ByteArray(0)

    /**
     * 缓存帧的每像素字节步长（RGBA_8888 通常 = 4，但**不可硬编码**）。
     *
     * `copyRgbaImage` 已在 `rowStride != rowBytes` 时逐行压缩，故缓存内**行距 = pixelStride**。
     * 供「就绪信号直采」与帧路径基准使用（见 `dsl/verify/_audit/IMAGE-PATH-COST.md` §5.3 坑 1）。
     */
    @Volatile
    private var cachedPixelStride = 4
    @Volatile
    private var frameThreadRunning = false
    private var frameThread: Thread? = null

    /**
     * 独立消费线程：循环 acquireLatestImage()，始终把最新帧拷进缓存。
     *
     * 不依赖 OnImageAvailableListener —— 华为 EMUI 上该回调经常不触发，导致
     * 永远收不到首帧。poll 循环（scrcpy 同款范式）在所有 ROM 上都能稳定出帧：
     * 内容变化时系统持续产帧，我们持续消费；内容静态时不再产帧，缓存保留上一帧，
     * OCR 服务到的正是当前屏幕，符合预期。
     */
    private fun startFramePollerLocked() {
        if (frameThreadRunning) return
        frameThreadRunning = true
        val thread = Thread({
            var nullCount = 0
            while (frameThreadRunning) {
                val reader = synchronized(lock) { imageReader } ?: break
                val image = try {
                    reader.acquireLatestImage()
                } catch (_: Throwable) {
                    null
                }
                if (image != null) {
                    try {
                        val w = image.width
                        val h = image.height
                        val plane = image.planes.firstOrNull()
                        if (plane != null) {
                            val rowBytes = w * plane.pixelStride
                            val size = h * rowBytes
                            synchronized(lock) {
                                if (cachedRgba.size != size) cachedRgba = ByteArray(size)
                                MatOps.copyRgbaImage(image, cachedRgba)
                                cachedWidth = w
                                cachedHeight = h
                                cachedPixelStride = plane.pixelStride
                                cachedTimestampNs = image.timestamp
                                hasCachedFrame = true
                                lastFrameElapsedMs = SystemClock.elapsedRealtime()
                            }
                            if (!firstFrameLogged) {
                                firstFrameLogged = true
                                Log.i(TAG, "first frame acquired ${w}x$h ts=${image.timestamp}")
                            } else if (nullCount > 0) {
                                Log.d(TAG, "frame acquired ${w}x$h after $nullCount nulls")
                                nullCount = 0
                            }
                        }
                    } finally {
                        image.close()
                    }
                } else {
                    nullCount++
                    if (nullCount == 1 || nullCount % 100 == 0) {
                        Log.d(TAG, "frame poll null (count=$nullCount)")
                    }
                    try {
                        Thread.sleep(FRAME_POLL_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
            Log.d(TAG, "frame poller exited")
        }, "BetterGICaptureFrames")
        thread.start()
        frameThread = thread
    }

    private fun stopFramePollerLocked() {
        frameThreadRunning = false
        val t = frameThread
        frameThread = null
        if (t != null) {
            try {
                t.join(1000)
            } catch (_: Throwable) {
            }
        }
    }

    fun isRunning(): Boolean = synchronized(lock) {
        mediaProjection != null && virtualDisplay != null && imageReader != null
    }

    fun capturedSize(): Pair<Int, Int>? = synchronized(lock) {
        val reader = imageReader ?: return null
        if (reader.width <= 0 || reader.height <= 0) return null
        reader.width to reader.height
    }

    fun start(resultCode: Int, data: Intent) {
        synchronized(lock) {
            stopLocked()

            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = manager.getMediaProjection(resultCode, data) ?: return
            mediaProjection = projection

            val cb = object : MediaProjection.Callback() {
                override fun onStop() {
                    handleProjectionStoppedExternally()
                }

                override fun onCapturedContentResize(width: Int, height: Int) {
                    synchronized(lock) {
                        resizeCapturedContentLocked(width, height)
                    }
                }
            }
            callback = cb
            projection.registerCallback(cb, mainHandler)

            val spec = defaultDisplaySpec()
            densityDpi = spec.densityDpi
            createVirtualDisplayLocked(spec.width, spec.height, spec.densityDpi)
        }
    }

    fun stop() {
        synchronized(lock) {
            stopLocked()
        }
    }

    /**
     * 丢掉积压帧（保留缓存）。VirtualDisplay 仍按 vsync 产出，ImageReader 缓存上限内
     * 多余的由系统丢弃。poll 循环会立即补回最新一帧。
     */
    fun discardLatestImages() {
        synchronized(lock) {
            try {
                imageReader?.acquireLatestImage()?.close()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 返回后台 poll 线程已缓存的最新帧（RGBA → BGR）。
     *
     * 静态界面不再产帧 → 缓存保留上一帧，这里持续服务（不按年龄拒帧）：
     * 扫描背包/武器界面本来就是静态的，缓存帧即当前屏幕，正是 OCR 需要的。
     */
    fun acquireLatestBgr(): CapturedBgrFrame? = synchronized(lock) {
        maybeRecoverStalledReaderLocked()
        if (!hasCachedFrame) {
            Log.d(TAG, "acquireLatestBgr no cache yet")
            return null
        }
        Log.v(TAG, "acquireLatestBgr ${cachedWidth}x$cachedHeight age=${SystemClock.elapsedRealtime() - lastFrameElapsedMs}ms")
        return CapturedBgrFrame(
            width = cachedWidth,
            height = cachedHeight,
            bgr = MatOps.rgbaToBgr(cachedWidth, cachedHeight, cachedRgba),
            timestampNs = cachedTimestampNs,
        )
    }

    /**
     * ★ 就绪信号直采（2026-09-12）：从**当前缓存帧**直接采样 [rect] 的**分块 RGB 均值签名**。
     *
     * 取代「全帧解码 + OCR」的就绪轮询（见 `dsl/verify/_audit/IMAGE-PATH-COST.md` §5）：
     * **零 Mat、零分配**（写入调用方缓冲），成本 ≈ `blocksX*blocksY*9` 次字节读 ≈ **~1µs**；
     * 而被它取代的每轮轮询要付「全帧 RGBA→BGR（实测 2ms）+ 一次 rec（2~9ms）+ delay(120ms)」。
     *
     * 输出格式**刻意对齐 [VoteJudges.thumbChangedFraction]**：每 3 字节 = 1 个块（R,G,B 均值，0..255），
     * 长度 = `blocksX*blocksY*3` ⇒ 可直接复用既有的 `tol=THUMB_DIFF_TOL(2)` 容差经验。
     *
     * ⚠️ 两个坑：
     * 1. 行距用 [cachedPixelStride]（RGBA_8888 通常 4，但**不可硬编码**）；
     *    缓存内每行已由 `copyRgbaImage` 压缩为紧凑（row pitch = pixelStride）。
     * 2. **不量化**：现有容差 `tol=2` 是作用在 0..255 上的（0.8%）；若 `shr 4` 到 0..15 会让容差变成 13%，失真。
     *
     * @param out 长度须 ≥ `blocksX*blocksY*3`
     * @return false = 无缓存帧 / 参数非法（**调用方必须回退旧路径**）
     */
    fun sampleSignature(
        rect: IntRect,
        out: ByteArray,
        blocksX: Int = SIG_BLOCKS_X,
        blocksY: Int = SIG_BLOCKS_Y,
    ): Boolean {
        if (out.size < blocksX * blocksY * 3) return false
        var bufRef: ByteArray? = null
        var w = 0
        var h = 0
        var ps = 4
        synchronized(lock) {
            if (!hasCachedFrame) return false
            bufRef = cachedRgba
            w = cachedWidth
            h = cachedHeight
            ps = cachedPixelStride
        }
        val src = bufRef ?: return false
        if (w <= 0 || h <= 0 || ps < 3) return false
        val x0 = rect.x.coerceIn(0, w - 1)
        val y0 = rect.y.coerceIn(0, h - 1)
        val rw = rect.width.coerceIn(1, w - x0)
        val rh = rect.height.coerceIn(1, h - y0)

        var o = 0
        for (by in 0 until blocksY) {
            val ya = y0 + rh * by / blocksY
            val yb = (y0 + rh * (by + 1) / blocksY).coerceAtLeast(ya + 1)
            for (bx in 0 until blocksX) {
                val xa = x0 + rw * bx / blocksX
                val xb = (x0 + rw * (bx + 1) / blocksX).coerceAtLeast(xa + 1)
                var sr = 0
                var sg = 0
                var sb = 0
                var n = 0
                for (sy in 0 until SIG_SAMPLES_PER_AXIS) {
                    val y = (ya + (yb - ya) * sy / SIG_SAMPLES_PER_AXIS).coerceIn(0, h - 1)
                    for (sx in 0 until SIG_SAMPLES_PER_AXIS) {
                        val x = (xa + (xb - xa) * sx / SIG_SAMPLES_PER_AXIS).coerceIn(0, w - 1)
                        val p = (y * w + x) * ps
                        sr += src[p].toInt() and 0xFF       // RGBA：R 在前
                        sg += src[p + 1].toInt() and 0xFF
                        sb += src[p + 2].toInt() and 0xFF
                        n++
                    }
                }
                val k = if (n > 0) n else 1
                out[o] = (sr / k).toByte()
                out[o + 1] = (sg / k).toByte()
                out[o + 2] = (sb / k).toByte()
                o += 3
            }
        }
        return true
    }

    /**
     * 帧代数（= 缓存帧时间戳 ns）。**同一帧的两个样本必然相同** ⇒ 用它排除
     * 「拿同一帧自己比自己」造成的**假稳定**（轮询步长 40ms < 帧间隔 ~33ms 时必现）。
     */
    fun frameGeneration(): Long = synchronized(lock) { cachedTimestampNs }

    /**
     * ★ ROI **稳定性**探针（2026-09-12，只读）：对每个候选 ROI 连续采集 [frames] 个**跨帧**签名，
     * 统计「相邻样本在容差内完全相同」的比例与最长连续不变段。
     *
     * **为什么需要它**：就绪信号要求 ROI 在渲染完成后**跨帧像素恒等**；而"坐标标定"只保证
     * 那个位置是对的，不保证它静止（小文字 ROI 会因抗锯齿/亚像素抖动/页面动画永远在变 ——
     * 实测 `char_profile.name` / `char_talent.lvRois[0]` 在 700ms 内从未静止）。
     * 见 `dsl/verify/_audit/PIPELINE-FEASIBILITY.md` §11.3。
     *
     * 输出（每个 ROI 一段）：
     * `name same=NN.N% avgChanged=n.n maxRun=NN verdict=USABLE|UNSTABLE`
     * 判定：`same ≥ 95% 且 maxRun ≥ 15` ⇒ USABLE（足够做就绪锚）。
     *
     * ⚠️ 只在**当前显示的界面**上有意义：若 ROI 指向的界面没在屏上，量到的是别的内容（可能"假稳"）。
     */
    fun probeRoiStability(rois: List<Pair<String, IntRect>>, frames: Int = 60): String {
        if (rois.isEmpty()) return "no rois"
        val n = frames.coerceIn(10, 300)
        val bufA = ByteArray(SIG_BLOCKS_X * SIG_BLOCKS_Y * 3)
        val bufB = ByteArray(bufA.size)
        val sb = StringBuilder("roi stability: frames=$n rois=${rois.size}")
        for ((name, rect) in rois) {
            val a = ByteArray(bufA.size)
            val b = ByteArray(bufA.size)
            var prevRef: ByteArray? = null
            var writeIdx = 0
            var taken = 0
            var sameCnt = 0
            var changedSum = 0
            var run = 0
            var maxRun = 0
            var lastGen = Long.MIN_VALUE
            var ok = true
            val deadline = SystemClock.elapsedRealtime() + STABILITY_PROBE_MAX_MS
            while (taken < n && SystemClock.elapsedRealtime() < deadline) {
                val cur = if (writeIdx == 0) a else b
                if (!sampleSignature(rect, cur, SIG_BLOCKS_X, SIG_BLOCKS_Y)) {
                    ok = false
                    break
                }
                val gen = frameGeneration()
                if (gen == lastGen) {
                    Thread.sleep(STABILITY_PROBE_IDLE_MS) // 同一帧：等下一帧（跨帧才算一个样本）
                    continue
                }
                lastGen = gen
                val p = prevRef
                if (p != null) {
                    val d = changedBlocks(p, cur)
                    taken++
                    changedSum += d
                    if (d == 0) {
                        sameCnt++
                        run++
                        if (run > maxRun) maxRun = run
                    } else {
                        run = 0
                    }
                }
                prevRef = cur
                writeIdx = 1 - writeIdx // 下一轮写另一个缓冲（绝不复用 prevRef）
            }
            if (!ok) {
                sb.append("\n  ").append(name).append(" SAMPLE_FAILED")
                continue
            }
            val samePct = if (taken > 0) sameCnt * 100f / taken else 0f
            val avgChanged = if (taken > 0) changedSum.toFloat() / taken else 0f
            val usable = samePct >= 95f && maxRun >= 15
            sb.append("\n  ").append(name)
                .append(" same=").append("%.1f".format(samePct)).append("%")
                .append(" avgChanged=").append("%.2f".format(avgChanged))
                .append(" maxRun=").append(maxRun).append("/").append(taken)
                .append(" verdict=").append(if (usable) "USABLE" else "UNSTABLE")
        }
        return sb.toString()
    }

    /** 两签名之间「超出容差」的块数（容差语义与生产一致：单通道 |Δ|>2）。 */
    private fun changedBlocks(a: ByteArray, b: ByteArray, tol: Int = SIG_BLOCK_TOL): Int {
        if (a.size != b.size) return Int.MAX_VALUE / 2
        var d = 0
        var i = 0
        while (i + 2 < a.size) {
            if (kotlin.math.abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)) > tol ||
                kotlin.math.abs((a[i + 1].toInt() and 0xFF) - (b[i + 1].toInt() and 0xFF)) > tol ||
                kotlin.math.abs((a[i + 2].toInt() and 0xFF) - (b[i + 2].toInt() and 0xFF)) > tol
            ) {
                d++
            }
            i += 3
        }
        return d
    }

    /**
     * 帧路径基准（**只读探针**，不改任何状态、不干扰帧流）：用当前缓存帧拆测各段耗时。
     *
     * 回答两个问题（见 `dsl/verify/_audit/IMAGE-PATH-COST.md` §8）：
     * 1. `acquireLatestBgr()` 的 16.97MB 分配 / 26.7MB 流量**到底值多少 ms**；
     * 2. 「只把 ROI 转 BGR」相对全帧路径快多少倍。
     *
     * @param rois 真实 ROI 集合（帧坐标）；传空则跳过 ROI 段。由调用方从 profile 读出，避免跨层依赖。
     */
    /**
     * ★ 帧率探针（2026-09-12，**只读**）：高频轮询 [frameGeneration]，统计**相邻新帧的墙钟间隔**。
     *
     * 为什么需要：`sigWait` 里测到的"帧间隔"会与**轮询步长**混淆（步长 40ms 时量到的就是 40ms），
     * 无法判断"等待 80ms 是游戏只出 25fps"还是"我们采样太慢"。本探针以 [pollMs]（默认 2ms）
     * 独立轮询 ⇒ 量到的是**真实出帧间隔**。
     *
     * ⚠️ 静态画面时系统可能**不产新帧**（缓存保留上一帧）⇒ 报告里会出现超长间隔，属正常；
     *    判帧率要看**众数/中位**而不是 max。
     */
    fun probeFrameRate(durationMs: Long = 1200, pollMs: Long = 2): String {
        val dur = durationMs.coerceIn(200, 10_000)
        val step = pollMs.coerceIn(1, 50)
        val gaps = ArrayList<Long>(512)
        var lastGen = frameGeneration()
        var lastWall = SystemClock.elapsedRealtime()
        val t0 = lastWall
        while (SystemClock.elapsedRealtime() - t0 < dur) {
            val g = frameGeneration()
            val wall = SystemClock.elapsedRealtime()
            if (g != lastGen) {
                gaps.add(wall - lastWall)
                lastGen = g
                lastWall = wall
            }
            Thread.sleep(step)
        }
        if (gaps.isEmpty()) return "frameRate: 窗口 ${dur}ms 内**未产生任何新帧**（静态画面？）"
        val sorted = gaps.sorted()
        val med = sorted[sorted.size / 2]
        val hist = IntArray(13)
        gaps.forEach { hist[(it / 5).toInt().coerceIn(0, 12)]++ }
        val hs = (0 until hist.size).filter { hist[it] > 0 }
            .joinToString(" ") { "${it * 5}-${it * 5 + 4}ms:${hist[it]}" }
        return "frameRate: 窗口=${dur}ms 新帧=${gaps.size} fps=${"%.1f".format(gaps.size * 1000.0 / dur)} " +
            "间隔 min=${sorted.first()} 中位=$med p90=${sorted[(sorted.size * 9 / 10).coerceAtMost(sorted.size - 1)]} max=${sorted.last()}" +
            "\n  直方图(5ms 分箱): $hs"
    }

    fun benchFramePath(runs: Int = 30, rois: List<IntRect> = emptyList()): String {
        var bufRef: ByteArray? = null
        var w = 0
        var h = 0
        var ps = 4
        synchronized(lock) {
            if (!hasCachedFrame) return "no cached frame"
            bufRef = cachedRgba
            w = cachedWidth
            h = cachedHeight
            ps = cachedPixelStride
        }
        val buf = bufRef ?: return "no cached frame"
        if (w <= 0 || h <= 0) return "bad frame size ${w}x$h"
        val n = runs.coerceIn(3, 200)

        fun med(block: () -> Unit): Long {
            val t = LongArray(n) {
                val t0 = System.nanoTime()
                block()
                (System.nanoTime() - t0) / 1_000_000
            }
            return t.sorted()[t.size / 2]
        }

        // A) 拆段：4 通道 Mat 分配 / put(整帧) / cvtColor(整帧)
        val alloc4 = med { Mat(h, w, CvType.CV_8UC4).release() }
        val put4 = med {
            val m = Mat(h, w, CvType.CV_8UC4)
            m.put(0, 0, buf)
            m.release()
        }
        val rgbaMat = Mat(h, w, CvType.CV_8UC4)
        rgbaMat.put(0, 0, buf)
        val cvtFull = med {
            val b = Mat()
            Imgproc.cvtColor(rgbaMat, b, Imgproc.COLOR_RGBA2BGR)
            b.release()
        }
        rgbaMat.release()
        // B) 生产口径（= MatOps.rgbaToBgr，alloc+put+cvt 一条链）
        val full = med { MatOps.rgbaToBgr(w, h, buf).release() }

        // C) ROI 口径：Java 侧按行收集 → 小 4 通道 Mat → cvtColor（tmp 缓冲预分配，对优化版公平）
        var roiPx = 0
        var roiMs = -1L
        var roiBytes = 0
        if (rois.isNotEmpty()) {
            val clamped = rois.map { r ->
                val x = r.x.coerceIn(0, w - 1)
                val y = r.y.coerceIn(0, h - 1)
                val rw = r.width.coerceIn(1, w - x)
                val rh = r.height.coerceIn(1, h - y)
                intArrayOf(x, y, rw, rh)
            }
            roiPx = clamped.sumOf { it[2] * it[3] }
            roiBytes = roiPx * ps
            val maxRow = clamped.maxOf { it[2] } * ps
            val maxRows = clamped.maxOf { it[3] }
            val tmp = ByteArray(maxRow * maxRows)
            roiMs = med {
                for (r in clamped) {
                    val (x, y, rw, rh) = r
                    val rowBytes = rw * ps
                    for (row in 0 until rh) {
                        System.arraycopy(buf, ((y + row) * w + x) * ps, tmp, row * rowBytes, rowBytes)
                    }
                    val m4 = Mat(rh, rw, CvType.CV_8UC4)
                    m4.put(0, 0, tmp, 0, rowBytes * rh)
                    val b3 = Mat()
                    Imgproc.cvtColor(m4, b3, Imgproc.COLOR_RGBA2BGR)
                    m4.release()
                    b3.release()
                }
            }
        }
        return "frame=${w}x$h stride=$ps runs=$n | full: alloc4=$alloc4 put4=$put4 cvtFull=$cvtFull " +
            "prod=$full ms | roi: rects=${rois.size} px=$roiPx bytes=$roiBytes $roiMs ms" +
            (if (roiMs > 0) " (x${"%.1f".format(full.toDouble() / roiMs.toDouble())})" else "")
    }

    private fun handleProjectionStoppedExternally() {
        val notify = synchronized(lock) {
            if (mediaProjection == null) {
                false
            } else {
                val projection = mediaProjection
                val cb = callback
                mediaProjection = null
                callback = null
                releaseDisplayLocked()
                if (projection != null && cb != null) {
                    try {
                        projection.unregisterCallback(cb)
                    } catch (_: Throwable) {
                    }
                }
                true
            }
        }
        if (notify) {
            onStoppedExternally()
        }
    }

    private fun createVirtualDisplayLocked(width: Int, height: Int, densityDpi: Int) {
        val projection = mediaProjection ?: return
        releaseDisplayLocked()

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, IMAGE_READER_MAX_IMAGES)
        imageReader = reader
        displayCreatedElapsedMs = SystemClock.elapsedRealtime()
        hasCachedFrame = false
        firstFrameLogged = false

        virtualDisplay = projection.createVirtualDisplay(
            "BetterGIPocketShare",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null,
        )
        Log.i(TAG, "ImageReader+VirtualDisplay ready ${width}x$height")
        startFramePollerLocked()
    }

    /**
     * 同一 MediaProjection 只能 createVirtualDisplay 一次。
     * 选单个应用或旋转时，系统会回调新尺寸，只能 resize 现有 VirtualDisplay 并换 ImageReader。
     */
    private fun resizeCapturedContentLocked(width: Int, height: Int) {
        val display = virtualDisplay ?: return
        if (width <= 0 || height <= 0) return
        val current = imageReader
        if (current != null && current.width == width && current.height == height) return

        Log.i(TAG, "captured content resized to ${width}x$height")
        val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, IMAGE_READER_MAX_IMAGES)
        display.setSurface(newReader.surface)
        display.resize(width, height, densityDpi)
        imageReader = newReader
        lastRecoverElapsedMs = SystemClock.elapsedRealtime()
        hasCachedFrame = false
        firstFrameLogged = false
        try {
            current?.close()
        } catch (_: Throwable) {
        }
    }

    /**
     * 始终未收到首帧时（如 ImageReader 在某 ROM 下死掉），用同一 VirtualDisplay 换一块
     * surface。每个 MediaProjection 只能 createVirtualDisplay 一次，不能整段重建。
     * 注意：静态界面本就不产帧，hasCachedFrame 一旦为 true 绝不触发此处，避免无谓抖动。
     */
    private fun maybeRecoverStalledReaderLocked() {
        if (virtualDisplay == null || imageReader == null) return
        if (hasCachedFrame) return
        val stalledSince = if (displayCreatedElapsedMs > 0) displayCreatedElapsedMs else lastFrameElapsedMs
        if (stalledSince <= 0) return
        val now = SystemClock.elapsedRealtime()
        if (now - stalledSince < RECOVER_AFTER_MS) return
        if (now - lastRecoverElapsedMs < RECOVER_COOLDOWN_MS) return
        lastRecoverElapsedMs = now
        Log.w(TAG, "no first frame for ${now - stalledSince}ms, recreating ImageReader")
        val current = imageReader ?: return
        val width = current.width
        val height = current.height
        if (width <= 0 || height <= 0) return
        val display = virtualDisplay ?: return
        val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, IMAGE_READER_MAX_IMAGES)
        display.setSurface(newReader.surface)
        imageReader = newReader
        displayCreatedElapsedMs = now
        hasCachedFrame = false
        firstFrameLogged = false
        try {
            current.close()
        } catch (_: Throwable) {
        }
    }

    private fun stopLocked() {
        val projection = mediaProjection
        val cb = callback
        mediaProjection = null
        callback = null
        releaseDisplayLocked()
        if (projection != null && cb != null) {
            try {
                projection.unregisterCallback(cb)
            } catch (_: Throwable) {
            }
        }
        try {
            projection?.stop()
        } catch (_: Throwable) {
        }
    }

    private fun releaseDisplayLocked() {
        stopFramePollerLocked()

        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        } finally {
            virtualDisplay = null
        }

        try {
            imageReader?.close()
        } catch (_: Throwable) {
        } finally {
            imageReader = null
        }

        hasCachedFrame = false
        cachedWidth = 0
        cachedHeight = 0
        cachedTimestampNs = 0L
        lastFrameElapsedMs = 0L
        lastRecoverElapsedMs = 0L
        displayCreatedElapsedMs = 0L
        firstFrameLogged = false
        cachedRgba = ByteArray(0)
    }

    private fun defaultDisplaySpec(): DisplaySpec {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            ?: error("DEFAULT_DISPLAY is missing")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayContext = context.createDisplayContext(display)
            val windowManager = displayContext.getSystemService(WindowManager::class.java)
            val bounds = windowManager.maximumWindowMetrics.bounds
            return DisplaySpec(
                width = bounds.width(),
                height = bounds.height(),
                densityDpi = displayContext.resources.displayMetrics.densityDpi,
            )
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return DisplaySpec(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    private companion object {
        const val TAG = "BetterGI.Capture"
        const val IMAGE_READER_MAX_IMAGES = 2
        const val FRAME_POLL_MS = 8L
        const val RECOVER_AFTER_MS = 800L
        const val RECOVER_COOLDOWN_MS = 2500L

        // ── 就绪信号签名（sampleSignature）──
        /** 分块数（列×行）。8×4 = 32 块 ⇒ 签名 96 字节；块足够大 ⇒ 均值抗噪。 */
        const val SIG_BLOCKS_X = 8
        const val SIG_BLOCKS_Y = 4
        /** 每块每轴的采样点数（3 ⇒ 9 点/块，共 288 次字节读 ≈ 1µs）。 */
        const val SIG_SAMPLES_PER_AXIS = 3
        /** 签名比较容差（单通道 |Δ|>2 记为"变了"）；与 `VoteJudges.THUMB_DIFF_TOL` 同值。 */
        const val SIG_BLOCK_TOL = 2

        // ── 稳定性探针（probeRoiStability）──
        /** 单个 ROI 的采样总时限（超时即报已有结果，避免探针挂死）。 */
        const val STABILITY_PROBE_MAX_MS = 12_000L
        /** 同一帧时等待下一帧的间隔。 */
        const val STABILITY_PROBE_IDLE_MS = 3L
    }
}
