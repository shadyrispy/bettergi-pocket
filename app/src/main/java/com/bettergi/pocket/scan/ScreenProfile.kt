package com.bettergi.pocket.scan

import android.util.Log
import com.bettergi.pocket.dsl.FlowSource
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
        /** §12.1 起点 y 偏置：锚行上沿往下 5px（方案定稿，配合常量 BIAS=+5）。 */
        const val ADVANCE_Y_BIAS = 5
        /** 祝圣 yShift 基准值（3200x1440 帧像素）；分辨率专属 profile 用 zhushengYShiftFrame 覆盖。 */
        const val ZHUSHENG_YSHIFT_BASE = 63
        const val TAG = "BetterGI.Profile"

        /** 从 assets/dsl/profiles.json 构建。 */
        fun load(assets: android.content.res.AssetManager): ScreenProfile {
            val text = FlowSource.open(assets, "dsl/profiles.json").bufferedReader().use { it.readText() }
            return ScreenProfile(JSONObject(text))
        }

        /**
         * 按帧尺寸选 profile：优先 dsl/profiles_<w>x<h>.json（分辨率专属标定），
         * 无则回退基准 profiles.json（aspectDistortion 警告交由 calibrate）。
         */
        fun loadFor(assets: android.content.res.AssetManager, frameWidth: Int, frameHeight: Int): ScreenProfile {
            val specific = "dsl/profiles_${frameWidth}x${frameHeight}.json"
            return try {
                val text = FlowSource.open(assets, specific).bufferedReader().use { it.readText() }
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

    /**
     * §12.5 网格行偏移（翻页相位残差）：仅作用于**网格类**坐标
     * （gridGeometry.rowYs / cellCenter / cardRelRect / gridBounds / advanceStart），
     * panel rect/point/zone 等固定 UI 坐标不受影响。
     */
    @Volatile internal var gridRowOffset: Int = 0

    /** 返回网格行整体平移 [dy] px 的轻量视图（共享同一 JSON）。测量类调用（GridAlign 相位参考）须用原始 profile。 */
    fun withGridRowOffset(dy: Int): ScreenProfile =
        ScreenProfile(root, frameWidth, frameHeight).also { it.gridRowOffset = dy }

    val scaleX: Double get() = frameWidth.toDouble() / baseWidth
    val scaleY: Double get() = frameHeight.toDouble() / baseHeight

    /**
     * 祝圣 yShift（帧像素，crafted 时作用于 level/lock/astral/sub1-4）。
     * 分辨率专属 profile 可用顶层 "zhushengYShiftFrame" 直接给定（如 2244x1080 = 47）；
     * 缺省按基准 [ZHUSHENG_YSHIFT_BASE] × [scaleY] 换算。
     */
    val zhushengShiftPx: Int
        get() {
            val v = root.optInt("zhushengYShiftFrame", Int.MIN_VALUE)
            return if (v != Int.MIN_VALUE) v else Math.round(ZHUSHENG_YSHIFT_BASE * scaleY).toInt()
        }

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

    /**
     * 任意节点（JSONArray / JSONObject / 标量）。
     * ⚠️ [rawObject] 只认 JSONObject，数组型配置（如 `screens.artifact_manage.resetChain` 是 JSONArray）
     * 用它取恒 null → 复位链静默失效；数组/不确定类型一律走本函数。
     */
    fun rawAny(path: String): Any? = resolve(path)

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
        val oy = origin.getInt(1) + gridRowOffset + row * pitch.getInt(1)
        return FrameRect(
            left = scale(ox + rel[0], scaleX),
            top = scale(oy + rel[1], scaleY),
            right = scale(ox + rel[2], scaleX),
            bottom = scale(oy + rel[3], scaleY),
        )
    }

    /** 网格第 [index] 个 cell 中心（行主序：index = row*cols + col）。 */
    fun cellCenter(gridKey: String, index: Int): FramePoint {
        val g = gridGeometry(gridKey)
        if (g != null) {
            val col = index % g.cols
            val row = index / g.cols
            val x = g.colXs.getOrElse(col) { g.colXs.last() } + g.cardW / 2
            val y = g.rowYs.getOrElse(row) { g.rowYs.last() } + g.cardH / 2
            return scalePoint(x, y)
        }
        // 回退：仅 cardOrigin+pitch 写法（历史行为）。无 cardOrigin 时给明确报错（勿裸抛 JSONException）
        val grid = rawObject("grids.$gridKey") ?: error("grid '$gridKey' missing")
        val cols = grid.getInt("cols")
        val origin = grid.optJSONArray("cardOrigin")
            ?: error("grid '$gridKey' 既无 colX/rowY 几何也无 cardOrigin 回退")
        val pitch = grid.getJSONArray("pitch")
        val size = grid.getJSONArray("cardSize")
        val col = index % cols
        val row = index / cols
        val x = origin.getInt(0) + col * pitch.getInt(0) + size.getInt(0) / 2
        val y = origin.getInt(1) + gridRowOffset + row * pitch.getInt(1) + size.getInt(1) / 2
        return scalePoint(x, y)
    }

    /**
     * §12.1 起点规范化：翻页滑动起点 = 该行**最末尾两个卡片中间空隙的中点**，
     * y = 锚行（被底栏遮挡行，= visibleRows）上边缘往下 5px。
     *
     * 目的：落点避开卡内标签/图标（旧写死坐标 x 正好偏一个 pitch.x，落在第 5 列卡中间）。
     * 几何不足（缺 cardSize/列/行）返回 null → 调用方回退到 profiles.advance.from。
     */
    fun advanceStart(gridKey: String): FramePoint? {
        val g = gridGeometry(gridKey) ?: return null
        if (g.colXs.size < 2 || g.rowYs.size < 2) return null
        val lastPitchX = g.colXs.last() - g.colXs[g.colXs.size - 2]
        val x = g.colXs[g.colXs.size - 2] + g.cardW + (lastPitchX - g.cardW) / 2
        val anchorRow = g.rowYs.size // 锚行 = visibleRows（第 4 行，被底栏遮挡、不遍历）
        val y = g.rowYs[anchorRow - 1] + ADVANCE_Y_BIAS
        return scalePoint(x, y) // 与 cellCenter 一致：一律返回帧坐标
    }

    /**
     * §12.1 翻页距离 = traverseRows × 行距（行距：pitch.y 或 rowY 差分均值）。
     * 反验：artifact/weapon = 3×292 = 876、char_popup = 3×280.3 ≈ 841，与 profiles 硬编码
     * advance.distance 完全一致 → 几何模型可信。
     */
    fun advanceDistance(gridKey: String): Int? {
        val grid = rawObject("grids.$gridKey") ?: return null
        val g = gridGeometry(gridKey) ?: return null
        val rows = grid.optInt("traverseRows", -1)
        if (rows <= 0) return null
        return scale(Math.round(rows * g.rowPitch).toInt(), scaleY) // 同样返回帧坐标尺度
    }

    /**
     * 网格几何（对外暴露，供 GridAlign / VoteJudges 复用）：统一 profiles 里两种写法
     * —— cardOrigin+pitch 与 colX/rowY 数组。
     */
    fun gridGeometryFor(gridKey: String): GridGeometry? = gridGeometry(gridKey)

    /** 网格可见区（帧坐标）：首列左边界 → 末列右边界、首行上沿 → 末行下沿。几何不足返回 null。
     * 优先读 grids.<key>.bounds 显式矩形（如 set_filter_popup 双列结构）。 */
    fun gridBounds(gridKey: String): FrameRect? {
        val grid = rawObject("grids.$gridKey")
        val explicit = grid?.optJSONArray("bounds")
        if (explicit != null && explicit.length() == 4) {
            return FrameRect(
                left = scale(explicit.getInt(0), scaleX),
                top = scale(explicit.getInt(1), scaleY),
                right = scale(explicit.getInt(2), scaleX),
                bottom = scale(explicit.getInt(3), scaleY),
            )
        }
        val g = gridGeometry(gridKey) ?: return null
        return FrameRect(
            left = scale(g.colXs.first(), scaleX),
            top = scale(g.rowYs.first(), scaleY),
            right = scale(g.colXs.last() + g.cardW, scaleX),
            bottom = scale(g.rowYs.last() + g.cardH, scaleY),
        )
    }

    /** 网格几何：统一 profiles 里两种写法 —— cardOrigin+pitch 与 colX/rowY 数组。 */
    private fun gridGeometry(gridKey: String): GridGeometry? {
        val grid = rawObject("grids.$gridKey") ?: return null
        val size = grid.optJSONArray("cardSize") ?: return null
        val cardW = size.optInt(0, -1)
        val cardH = size.optInt(1, -1)
        if (cardW <= 0 || cardH <= 0) return null
        // set_filter_popup 的 cols 是 {left,right} 对象 → optInt 回退默认 → 拒绝（非卡片网格）
        // 1 列网格（char_strip 左列头像条）合法 → 门限为 <1 而非 <2
        val cols = grid.optInt("cols", -1)
        if (cols < 1) return null

        val colX = grid.optJSONArray("colX")
        val rowY = grid.optJSONArray("rowY")
        if (colX != null && rowY != null && colX.length() >= cols && rowY.length() >= 2) {
            return GridGeometry(
                cols = cols, cardW = cardW, cardH = cardH,
                colXs = IntArray(cols) { colX.getInt(it) },
                rowYs = IntArray(rowY.length()) { rowY.getInt(it) + gridRowOffset },
            )
        }
        val origin = grid.optJSONArray("cardOrigin") ?: return null
        val pitch = grid.optJSONArray("pitch") ?: return null
        val vis = grid.optInt("visibleRows", -1)
        if (vis < 2) return null
        return GridGeometry(
            cols = cols, cardW = cardW, cardH = cardH,
            colXs = IntArray(cols) { origin.getInt(0) + it * pitch.getInt(0) },
            rowYs = IntArray(vis) { origin.getInt(1) + gridRowOffset + it * pitch.getInt(1) },
        )
    }

    /** 网格几何（基准坐标）：列左边界数组 / 行上沿数组 / 卡片尺寸 / 列数。 */
    class GridGeometry(
        val cols: Int,
        val cardW: Int,
        val cardH: Int,
        val colXs: IntArray,
        val rowYs: IntArray,
    ) {
        /** 行距（末行-首行 差分均值）。 */
        val rowPitch: Double
            get() = if (rowYs.size < 2) 0.0 else (rowYs.last() - rowYs.first()).toDouble() / (rowYs.size - 1)
    }

    fun gridInt(gridKey: String, field: String): Int =
        rawObject("grids.$gridKey")?.getInt(field) ?: error("grids.$gridKey.$field missing")

    private fun resolve(path: String): Any? {
        // ⚠️ zones 顶层 key 自身含点号（char_popup.collapse / artifact.panel.lock …）→ 点分拆分前
        // 先按「最长前缀整键命中」试解，否则 "zones.char_popup.collapse.rect" 会拆成 zones→char_popup
        // → opt(null) 恒 null（旧行为：clicks 链静默跳过 / rect() 直接抛）。
        if (path.startsWith("zones.")) {
            val rest = path.removePrefix("zones.")
            val zones = root.optJSONObject("zones")
            if (zones != null) {
                var hit: Any? = null
                var hitLen = -1
                val it = zones.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    if ((rest == k || rest.startsWith("$k.")) && k.length > hitLen) {
                        var node: Any? = zones.opt(k)
                        if (rest != k) {
                            for (seg in rest.removePrefix(k).removePrefix(".").split('.')) {
                                node = (node as? JSONObject)?.opt(seg)
                                if (node == null) break
                            }
                        }
                        if (node != null) {
                            hit = node
                            hitLen = k.length
                        }
                    }
                }
                if (hit != null) return hit
            }
        }
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
