package com.bettergi.pocket.core.image

import android.media.Image
import com.bettergi.pocket.core.ColorBgr
import com.bettergi.pocket.core.ColorConversion
import com.bettergi.pocket.core.IntRect
import com.bettergi.pocket.core.IntSize
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

object MatOps {
    fun copyRgbaImage(image: Image, dest: ByteArray) {
        val plane = image.planes.firstOrNull() ?: return
        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowBytes = width * pixelStride
        require(dest.size >= height * rowBytes) { "RGBA buffer too small" }
        val buffer = plane.buffer.duplicate()
        if (rowStride == rowBytes) {
            buffer.get(dest, 0, height * rowBytes)
            return
        }
        var destOffset = 0
        for (row in 0 until height) {
            buffer.position(row * rowStride)
            buffer.get(dest, destOffset, rowBytes)
            destOffset += rowBytes
        }
    }

    fun rgbaToBgr(width: Int, height: Int, rgba8888: ByteArray): Mat {
        val bgr = Mat()
        rgbaToBgrInto(width, height, rgba8888, bgr, rgbaStage = null)
        return bgr
    }

    /**
     * GC P0（工单 A）：写入给定 [dst] 的 rgbaToBgr 重载。
     *
     * 与旧 [rgbaToBgr] 的差别**只在缓冲来源**，像素输出逐位一致（同一 `COLOR_RGBA2BGR`）：
     * - [rgbaStage] 非空时复用为 4 通道中转 Mat（OpenCV `create()` 在尺寸不变时零重分配），
     *   消除旧路径每次调用分配又立即释放的 ~10-17MB RGBA 暂存（每秒数十次调用 ⇒ 每秒
     *   数百 MB 的头号 GC 源）。调用方必须保证同一时刻只有一个线程使用该 stage
     *   （ScreenCaptureController 里 poller 单线程独占，见其 KDoc）。
     * - [rgbaStage] 为 null 时退化为临时 stage（新建-即弃），语义与旧实现逐位一致。
     * - [dst] 由调用方提供生命周期（可为池化 Mat）；转换整块覆盖其内容，
     *   尺寸/类型不符时 cvtColor 会按 src 形状重建 dst（与旧实现行为一致）。
     */
    fun rgbaToBgrInto(width: Int, height: Int, rgba8888: ByteArray, dst: Mat, rgbaStage: Mat?) {
        val stage = rgbaStage ?: Mat(height, width, CvType.CV_8UC4)
        val ownedStage = rgbaStage == null
        try {
            stage.create(height, width, CvType.CV_8UC4)
            val written = stage.put(0, 0, rgba8888)
            if (written == 0) {
                throw IllegalStateException("Failed to copy RGBA into Mat ${width}x$height")
            }
            Imgproc.cvtColor(stage, dst, Imgproc.COLOR_RGBA2BGR)
        } finally {
            if (ownedStage) stage.release()
        }
    }

    fun bgrToGray(src: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
        return gray
    }

    fun createMask(src: Mat, maskColor: ColorBgr): Mat {
        val mask = Mat()
        val scalar = Scalar(maskColor.b, maskColor.g, maskColor.r)
        Core.inRange(src, scalar, scalar, mask)
        val inverted = Mat()
        Core.bitwise_not(mask, inverted)
        mask.release()
        return inverted
    }

    fun resize(src: Mat, target: IntSize, interpolation: Int = Imgproc.INTER_LINEAR): Mat {
        if (src.cols() == target.width && src.rows() == target.height) {
            return src
        }
        val dst = Mat()
        Imgproc.resize(src, dst, Size(target.width.toDouble(), target.height.toDouble()), 0.0, 0.0, interpolation)
        return dst
    }

    fun resize(src: Mat, scale: Double, interpolation: Int = Imgproc.INTER_LINEAR): Mat {
        if (abs(scale - 1.0) < 0.00001) {
            return src
        }
        return resize(
            src,
            IntSize(
                width = (src.cols() * scale).toInt().coerceAtLeast(1),
                height = (src.rows() * scale).toInt().coerceAtLeast(1),
            ),
            interpolation,
        )
    }

    fun convertColor(src: Mat, conversion: ColorConversion): Mat {
        if (conversion == ColorConversion.None) {
            return src
        }
        val dst = Mat()
        val code = when (conversion) {
            ColorConversion.None -> return src
            ColorConversion.BgrToRgb -> Imgproc.COLOR_BGR2RGB
            ColorConversion.BgrToHsv -> Imgproc.COLOR_BGR2HSV
            ColorConversion.BgrToGray -> Imgproc.COLOR_BGR2GRAY
        }
        Imgproc.cvtColor(src, dst, code)
        return dst
    }

    fun inRange(src: Mat, lower: ColorBgr, upper: ColorBgr): Mat {
        val dst = Mat()
        Core.inRange(
            src,
            Scalar(lower.b, lower.g, lower.r),
            Scalar(upper.b, upper.g, upper.r),
            dst,
        )
        return dst
    }

    fun binary(src: Mat, threshold: Int): Mat {
        val dst = Mat()
        Imgproc.threshold(src, dst, threshold.toDouble(), 255.0, Imgproc.THRESH_BINARY)
        return dst
    }

    fun roiView(src: Mat, roi: IntRect): Mat {
        return Mat(src, roi.toCvRect())
    }

    fun u8(mat: Mat, y: Int, x: Int): Int {
        val buf = ByteArray(1)
        mat.get(y, x, buf)
        return buf[0].toInt() and 0xFF
    }

    fun setU8(mat: Mat, y: Int, x: Int, value: Int) {
        mat.put(y, x, byteArrayOf(value.toByte()))
    }

    /**
     * P3：整行批量读（一次 JNI 拷贝），替代逐像素 [u8] 循环 —— NMS 的 suppress 区块
     * 逐像素 get/put 是同语义的慢速版。要求 (y, x..x+len-1) 在同一行内。
     */
    fun u8Row(mat: Mat, y: Int, xFrom: Int, out: ByteArray) {
        mat.get(y, xFrom, out)
    }

    /** P3：整行批量写（一次 JNI 拷贝），与 [u8Row] 配对。 */
    fun setU8Row(mat: Mat, y: Int, xFrom: Int, values: ByteArray) {
        mat.put(y, xFrom, values)
    }
}

fun IntRect.toCvRect(): Rect = Rect(x, y, width, height)
