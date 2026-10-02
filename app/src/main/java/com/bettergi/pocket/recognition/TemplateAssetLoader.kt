package com.bettergi.pocket.recognition

import android.content.res.AssetManager
import com.bettergi.pocket.core.image.MatOps
import com.bettergi.pocket.recognition.opencv.OpenCvRuntime
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.IOException

/**
 * 从 `assets/recognition/{task}/{WxH}/file.png` 加载模板。
 * 没有精确分辨率目录时回退到 `1920x1080`，再回退到任务根目录。
 * 未启用参考搜索时，捕获宽 < 1920 会按 [CaptureScale.assetScale] 缩小。
 */
class TemplateAssetLoader(
    private val assets: AssetManager,
) {
    fun load(
        taskName: String,
        fileName: String,
        captureWidth: Int,
        captureHeight: Int,
        applyLegacyAssetScale: Boolean = true,
    ): Mat {
        if (!OpenCvRuntime.ensureLoaded()) {
            throw IllegalStateException("OpenCV is not loaded")
        }
        val resolution = resolveAssetPath(
            taskName,
            fileName,
            captureWidth,
            captureHeight,
            exists = ::exists,
        )
        val assetPath = resolution.path

        val bytes = assets.open(assetPath).use { it.readBytes() }
        val encoded = Mat(1, bytes.size, CvType.CV_8UC1)
        encoded.put(0, 0, bytes)
        val decoded = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR)
        encoded.release()
        if (decoded.empty()) {
            throw IllegalArgumentException("无法解码模板: $assetPath")
        }

        // A23：精确分辨率目录（`${w}x${h}/`）命中的模板尺寸与画面一致，
        // 不得再吃 legacy asset scale（×captureW/1920 二次缩小 ⇒ 模板与画面失配）。
        // legacy scale 只对回退到基准目录 / 任务根目录的模板生效。
        if (applyLegacyAssetScale && !resolution.exactHit && captureWidth < CaptureScale.BASELINE_WIDTH) {
            val scaled = MatOps.resize(decoded, captureWidth / CaptureScale.BASELINE_WIDTH.toDouble())
            if (scaled !== decoded) {
                decoded.release()
            }
            return scaled
        }
        return decoded
    }

    fun loadRecognitionObject(
        taskName: String,
        fileName: String,
        captureWidth: Int,
        captureHeight: Int,
    ): RecognitionObject {
        return RecognitionObject.templateMatch(load(taskName, fileName, captureWidth, captureHeight)).apply {
            name = fileName
        }
    }

    /** 资产路径解析结果：实际命中路径 + 是否命中精确分辨率目录（决定 legacy scale 是否生效）。 */
    internal data class AssetResolution(val path: String, val exactHit: Boolean)

    companion object {
        private fun path(taskName: String, resolution: String, fileName: String): String {
            return "recognition/$taskName/$resolution/$fileName"
        }

        /**
         * 纯函数（路径探测注入 [exists]，JVM 可单测）：
         * 精确分辨率目录 → 基准 1920x1080 目录 → 任务根目录，未命中抛 [IllegalArgumentException]。
         */
        internal fun resolveAssetPath(
            taskName: String,
            fileName: String,
            captureWidth: Int,
            captureHeight: Int,
            exists: (String) -> Boolean,
        ): AssetResolution {
            val exact = path(taskName, "${captureWidth}x$captureHeight", fileName)
            val fallback = path(taskName, "${CaptureScale.BASELINE_WIDTH}x${CaptureScale.BASELINE_HEIGHT}", fileName)
            val taskRoot = "recognition/$taskName/$fileName"
            return when {
                exists(exact) -> AssetResolution(exact, exactHit = true)
                exists(fallback) -> AssetResolution(fallback, exactHit = false)
                exists(taskRoot) -> AssetResolution(taskRoot, exactHit = false)
                else -> throw IllegalArgumentException("未找到 $taskName 中的 $fileName")
            }
        }
    }

    private fun exists(path: String): Boolean {
        return try {
            assets.open(path).close()
            true
        } catch (_: IOException) {
            false
        }
    }
}
