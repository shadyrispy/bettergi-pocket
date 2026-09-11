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
import com.bettergi.pocket.recognition.opencv.MatOps

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
    }
}
