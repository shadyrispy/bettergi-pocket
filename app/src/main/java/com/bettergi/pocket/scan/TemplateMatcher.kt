package com.bettergi.pocket.scan

import android.content.res.AssetManager
import android.util.Log
import org.json.JSONObject
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * 模板锚点匹配器（§14 复核补齐：flow 中 `anchor.kind="template"`）。
 *
 * **全 JSON 驱动**：模板清单、锚点组合、阈值、搜索外扩、NMS 半径皆读自
 * `assets/dsl/templates.json`，代码内不含任何坐标/阈值硬编码。
 *
 * 算法：OpenCV `matchTemplate`(TM_CCOEFF_NORMED) → 膨胀非极大值抑制（NMS）→ 取最高响应峰。
 * 搜索区域 = 模板标定 rect 外扩 margin（profiles 记「零偏移」，故可收窄 ROI 提速降误配），
 * 无 rect 或 ROI 越界则退化为全帧搜索。
 *
 * 帧与模板分辨率不同时（模板按 3200×1440 基准裁切），按 [ScreenProfile] 的 scaleX/scaleY 缩放模板。
 */
object TemplateMatcher {
    private const val TAG = "BetterGI.Template"
    private const val CONFIG_PATH = "dsl/templates.json"

    /** 单次匹配结果：是否命中 + 最高响应分 + 命中位置（基准坐标）。 */
    data class MatchResult(val matched: Boolean, val score: Double, val x: Int, val y: Int)

    @Volatile
    private var config: JSONObject? = null

    @Volatile
    private var assets: AssetManager? = null

    /** 模板位图缓存（解码一次，避免每帧 IO）。 */
    private val templateCache = HashMap<String, Mat>()

    private val lock = Any()

    /** 由 ScriptRunner 在扫描启动前注入 assets 并预读配置。 */
    fun attach(assetManager: AssetManager) {
        synchronized(lock) {
            assets = assetManager
            config = try {
                JSONObject(assetManager.open(CONFIG_PATH).bufferedReader().use { it.readText() })
            } catch (e: Exception) {
                Log.w(TAG, "templates.json 读取失败，模板锚点将不可用：${e.message}")
                null
            }
        }
    }

    private fun defaults() = config?.optJSONObject("defaults")

    private fun threshold(): Double = defaults()?.optDouble("threshold", 0.80) ?: 0.80
    private fun margin(): Int = defaults()?.optInt("margin", 48) ?: 48
    private fun nmsRadius(): Int = defaults()?.optInt("nmsRadius", 24) ?: 24

    /** 模板规格：文件 + 标定 rect（基准坐标）。 */
    private fun templateSpec(key: String): Pair<String, IntArray?>? {
        val t = config?.optJSONObject("templates")?.optJSONObject(key) ?: return null
        val file = t.optString("file")
        if (file.isEmpty()) return null
        val arr = t.optJSONArray("rect")
        val rect = if (arr != null && arr.length() >= 4) {
            intArrayOf(arr.getInt(0), arr.getInt(1), arr.getInt(2), arr.getInt(3))
        } else {
            null
        }
        return file to rect
    }

    private fun loadTemplate(key: String): Mat? {
        templateCache[key]?.let { return it }
        val (file, _) = templateSpec(key) ?: return null
        val am = assets ?: return null
        return try {
            val bytes = am.open(file).use { it.readBytes() }
            val mat = Imgcodecs.imdecode(MatOfByte(*bytes), Imgcodecs.IMREAD_COLOR)
            if (mat.empty()) {
                Log.w(TAG, "模板解码失败：$key ($file)")
                null
            } else {
                synchronized(lock) { templateCache[key] = mat }
                mat
            }
        } catch (e: Exception) {
            Log.w(TAG, "模板读取失败：$key ($file) ${e.message}")
            null
        }
    }

    /**
     * 单模板匹配：ROI 内 TM_CCOEFF_NORMED → 膨胀 NMS → 最高响应峰。
     * @return 未加载配置/模板 → [MatchResult](false, -1, 0, 0)（调用方按未命中处理）
     */
    fun match(frame: Mat, templateKey: String, profile: ScreenProfile): MatchResult {
        val spec = templateSpec(templateKey) ?: return MatchResult(false, -1.0, 0, 0)
        val tpl = loadTemplate(templateKey) ?: return MatchResult(false, -1.0, 0, 0)

        // 模板按基准分辨率裁切 → 缩放到当前帧分辨率
        val scaled = Mat()
        Imgproc.resize(
            tpl, scaled, Size(),
            profile.scaleX.toDouble(), profile.scaleY.toDouble(),
            Imgproc.INTER_AREA,
        )
        val (tw, th) = scaled.cols() to scaled.rows()
        if (tw <= 0 || th <= 0 || tw > frame.cols() || th > frame.rows()) {
            scaled.release()
            return MatchResult(false, -1.0, 0, 0)
        }

        // 搜索 ROI：标定 rect 外扩 margin（缩放后）；越界/无 rect → 全帧
        val m = margin()
        val roi = spec.second?.let { r ->
            val x0 = (profile.scale(r[0] - m, profile.scaleX)).coerceIn(0, frame.cols() - tw)
            val y0 = (profile.scale(r[1] - m, profile.scaleY)).coerceIn(0, frame.rows() - th)
            val x1 = (profile.scale(r[2] + m, profile.scaleX)).coerceIn(x0 + tw, frame.cols())
            val y1 = (profile.scale(r[3] + m, profile.scaleY)).coerceIn(y0 + th, frame.rows())
            Rect(x0, y0, x1 - x0, y1 - y0)
        } ?: Rect(0, 0, frame.cols(), frame.rows())

        val search = Mat(frame, roi)
        val result = Mat()
        return try {
            Imgproc.matchTemplate(search, scaled, result, Imgproc.TM_CCOEFF_NORMED)
            // 膨胀 NMS：峰 = 值 ≥ 阈值 且 ≥ 邻域膨胀值（抑制成片响应）
            val r = nmsRadius().coerceAtLeast(1)
            val kernel = Mat.ones(2 * r + 1, 2 * r + 1, CvType.CV_8U)
            val dilated = Mat()
            Imgproc.dilate(result, dilated, kernel)
            kernel.release()
            var best = -1.0
            var bx = 0
            var by = 0
            val thr = threshold()
            // 步长 1：result 尺寸 = ROI - 模板，部位 tab 场景约百余见方，开销可忽略
            for (y in 0 until result.rows()) {
                for (x in 0 until result.cols()) {
                    val v = result.get(y, x)[0]
                    if (v >= thr && v >= dilated.get(y, x)[0] && v > best) {
                        best = v
                        bx = x
                        by = y
                    }
                }
            }
            dilated.release()
            if (best < 0) {
                MatchResult(false, Core.minMaxLoc(result).maxVal, 0, 0)
            } else {
                MatchResult(true, best, roi.x + bx, roi.y + by)
            }
        } finally {
            result.release()
            search.release()
            scaled.release()
        }
    }

    /**
     * 锚点判定：`mode=all` 全部模板命中才算通过；`mode=any` 任一命中即通过。
     * 未配置该锚点 → false（调用方应记 warn，避免"未配置"被误读为"通过"）。
     */
    fun matchAnchor(frame: Mat, anchorRef: String, profile: ScreenProfile): MatchResult {
        val anchor = config?.optJSONObject("anchors")?.optJSONObject(anchorRef)
        if (anchor == null) {
            Log.w(TAG, "锚点 '$anchorRef' 未在 templates.json 配置")
            return MatchResult(false, -1.0, 0, 0)
        }
        val keys = anchor.optJSONArray("templates") ?: return MatchResult(false, -1.0, 0, 0)
        if (keys.length() == 0) return MatchResult(false, -1.0, 0, 0)
        val all = anchor.optString("mode", "all") == "all"
        var worst = Double.MAX_VALUE
        var bestScore = -1.0
        var hitCount = 0
        for (i in 0 until keys.length()) {
            val r = match(frame, keys.optString(i), profile)
            if (r.matched) {
                hitCount++
                worst = minOf(worst, r.score)
            }
            bestScore = maxOf(bestScore, r.score)
        }
        val ok = if (all) hitCount == keys.length() else hitCount > 0
        Log.i(
            TAG,
            "anchor '$anchorRef' mode=${if (all) "all" else "any"} hit=$hitCount/${keys.length()}" +
                " worst=${if (worst == Double.MAX_VALUE) "-" else "%.3f".format(worst)}" +
                " best=${"%.3f".format(bestScore)} → $ok",
        )
        return MatchResult(ok, if (all) worst else bestScore, 0, 0)
    }
}
