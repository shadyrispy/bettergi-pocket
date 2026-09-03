package com.bettergi.pocket.scan

import android.util.Log
import org.json.JSONObject

/**
 * dsl/json/profiles.json 运行时载体（方案 §5.2.0：坐标唯一事实源，原语语义层零硬编码坐标）。
 *
 * 基准 3200x1440；运行时按捕获帧尺寸等比缩放（sx/sy 独立，容忍非严格等比）。
 * 点路径示例："screens.artifact_backpack.count"、"grids.artifact_backpack.cardOrigin"。
 */
class ScreenProfile(
    json: JSONObject,
    @Volatile private var frameWidth: Int = 3200,
    @Volatile private var frameHeight: Int = 1440,
) {
    private val root = json

    /** 该 profile 的基准尺寸（文件可带 "base":{"w":..,"h":..}；缺省 3200x1440）。 */
    private val baseWidth: Int
    private val baseHeight: Int

    init {
        val base = json.optJSONObject("base")
        baseWidth = base?.optInt("w", 3200) ?: 3200
        baseHeight = base?.optInt("h", 1440) ?: 1440
    }

    companion object {
        /** 宽高比失真告警阈值（2%——16:9 vs 20:9 是 25%，远超阈值）。 */
        const val ASPECT_DISTORTION_WARN = 0.02
        const val TAG = "BetterGI.Profile"

        /** 从 assets/dsl/profiles.json 构建。 */
        fun load(assets: android.content.res.AssetManager): ScreenProfile {
            val text = assets.open("dsl/profiles.json").bufferedReader().use { it.readText() }
            return ScreenProfile(JSONObject(text))
        }

        /**
         * 按帧尺寸选 profile：优先 dsl/profiles_<w>x<h>.json（分辨率专属标定），
         * 无则回退基准 profiles.json（aspectDistortion 警告交由 calibrate）。
         */
        fun loadFor(assets: android.content.res.AssetManager, frameWidth: Int, frameHeight: Int): ScreenProfile {
            val specific = "dsl/profiles_${frameWidth}x${frameHeight}.json"
            return try {
                val text = assets.open(specific).bufferedReader().use { it.readText() }
                Log.i(TAG, "using resolution-specific profile: $specific")
                ScreenProfile(JSONObject(text), frameWidth, frameHeight)
            } catch (e: java.io.FileNotFoundException) {
                Log.i(TAG, "no specific profile for ${frameWidth}x${frameHeight}, falling back to baseline")
                load(assets)
            }
        }
    }

    /** 扫描会话开始时按当前捕获帧尺寸校准缩放。 */
    fun calibrate(frameWidth: Int, frameHeight: Int) {
        require(frameWidth > 0 && frameHeight > 0) { "bad frame size ${frameWidth}x$frameHeight" }
        this.frameWidth = frameWidth
        this.frameHeight = frameHeight
        val distortion = aspectDistortion
        if (distortion > ASPECT_DISTORTION_WARN) {
            Log.w(
                TAG,
                "non-uniform scaling: frame ${frameWidth}x$frameHeight vs baseline " +
                    "${baseWidth}x${baseHeight} (sx=%.3f sy=%.3f distortion=%.0f%%) — " +
                    "UI layout aspect differs, coordinates may be misaligned".format(scaleX, scaleY, distortion * 100),
            )
        }
    }

    val scaleX: Double get() = frameWidth.toDouble() / baseWidth
    val scaleY: Double get() = frameHeight.toDouble() / baseHeight

    /**
     * 宽高比失真度 = |sx - sy| / min(sx, sy)。
     * 0 = 等比（UI 布局一致，坐标可靠）；>0.02 表示设备宽高比与基准 3200x1440 (20:9) 不同
     * （如 1920x1080 = 16:9 → sx≠sy，游戏 UI 横向分布不同，纯缩放坐标不可靠）。
     */
    val aspectDistortion: Double
        get() {
            val mn = minOf(scaleX, scaleY)
            return if (mn <= 0.0) 0.0 else kotlin.math.abs(scaleX - scaleY) / mn
        }

    /**
     * 基准 [x0,y0,x1,y1] → 帧坐标 rect。
     * 支持两种引用：路径直接指向 rect 数组，或指向含 "rect" 子键的对象（如 screens.artifact_backpack.count）——自动下钻。
     */
    fun rect(path: String): FrameRect {
        val node = resolve(path)
        val arr = (node as? org.json.JSONArray)
            ?: (node as? JSONObject)?.optJSONArray("rect")
            ?: error("profile path '$path' is not a rect array or rect-object")
        require(arr.length() == 4) { "profile path '$path' is not [x0,y0,x1,y1]" }
        return scaleRect(arr.getInt(0), arr.getInt(1), arr.getInt(2), arr.getInt(3))
    }

    /** 基准 [x,y] → 帧坐标点。 */
    fun point(path: String): FramePoint {
        val arr = resolve(path) as? org.json.JSONArray
            ?: error("profile path '$path' is not a point array")
        require(arr.length() == 2) { "profile path '$path' is not [x,y]" }
        return scalePoint(arr.getInt(0), arr.getInt(1))
    }

    fun rawObject(path: String): JSONObject? = resolve(path) as? JSONObject

    /** 路径存在性（flow "$..." 引用静态校验用）。 */
    fun hasPath(path: String): Boolean = resolve(path) != null

    /**
     * flow "$..." 引用归一：flow 词汇与 profiles 结构的别名/挂载差异。
     * - "$panel.x" → "panels.x"（flow 单数、profiles 复数）
     * - "$dialogs.x" → "screens.dialogs.x"（profiles 挂载在 screens 下）
     * 返回归一后的 profile 路径。
     */
    fun normalizeFlowRef(ref: String): String = when {
        ref.startsWith("panel.") -> "panels." + ref.removePrefix("panel.")
        ref.startsWith("dialogs.") -> "screens." + ref
        else -> ref
    }

    /** zones 顶层 key 自身含点号（如 artifact.panel.lock），不能走点路径，专用入口。 */
    fun zone(key: String): JSONObject? =
        root.optJSONObject("zones")?.optJSONObject(key)

    fun scaleRect(x0: Int, y0: Int, x1: Int, y1: Int): FrameRect = FrameRect(
        left = scale(x0, scaleX),
        top = scale(y0, scaleY),
        right = scale(x1, scaleX),
        bottom = scale(y1, scaleY),
    )

    fun scalePoint(x: Int, y: Int): FramePoint =
        FramePoint(scale(x, scaleX), scale(y, scaleY))

    /** 基准坐标 → 帧坐标缩放。 */
    fun scale(v: Int, s: Double): Int = Math.round(v * s).toInt()

    /** 卡片内相对坐标 rel [dx0,dy0,dx1,dy1]（相对卡片左上，随卡片原点平移 + 缩放）。 */
    fun cardRelRect(gridKey: String, rel: IntArray, col: Int, row: Int): FrameRect {
        val grid = rawObject("grids.$gridKey") ?: error("grid '$gridKey' missing")
        val origin = grid.getJSONArray("cardOrigin")
        val pitch = grid.getJSONArray("pitch")
        val ox = origin.getInt(0) + col * pitch.getInt(0)
        val oy = origin.getInt(1) + row * pitch.getInt(1)
        return FrameRect(
            left = scale(ox + rel[0], scaleX),
            top = scale(oy + rel[1], scaleY),
            right = scale(ox + rel[2], scaleX),
            bottom = scale(oy + rel[3], scaleY),
        )
    }

    /** 网格第 [index] 个 cell 中心（行主序：index = row*cols + col）。 */
    fun cellCenter(gridKey: String, index: Int): FramePoint {
        val grid = rawObject("grids.$gridKey") ?: error("grid '$gridKey' missing")
        val cols = grid.getInt("cols")
        val origin = grid.getJSONArray("cardOrigin")
        val pitch = grid.getJSONArray("pitch")
        val size = grid.getJSONArray("cardSize")
        val col = index % cols
        val row = index / cols
        val x = origin.getInt(0) + col * pitch.getInt(0) + size.getInt(0) / 2
        val y = origin.getInt(1) + row * pitch.getInt(1) + size.getInt(1) / 2
        return scalePoint(x, y)
    }

    fun gridInt(gridKey: String, field: String): Int =
        rawObject("grids.$gridKey")?.getInt(field) ?: error("grids.$gridKey.$field missing")

    private fun resolve(path: String): Any? {
        var node: Any? = root
        for (seg in path.split('.')) {
            node = when (node) {
                is JSONObject -> node.opt(seg)
                else -> return null
            }
        }
        return node
    }
}

/** 帧坐标 rect（缩放后）。 */
data class FrameRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    /** 纵向平移（祝圣 yShift 等场景）。 */
    fun shiftedBy(dy: Int): FrameRect = FrameRect(left, top + dy, right, bottom + dy)
}

data class FramePoint(val x: Int, val y: Int)
