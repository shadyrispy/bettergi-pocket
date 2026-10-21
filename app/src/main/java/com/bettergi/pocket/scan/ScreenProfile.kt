package com.bettergi.pocket.scan

import android.util.Log
import com.bettergi.pocket.core.FlowSource
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

        /**
         * 标定过 `clickBand` 的网格，点击平移上限 = 带半高 × 本比值。
         * 取 2/3 的依据是 run8 实测：条带读数单侧（φ=44..120 无负值、真值≈0），
         * 窗放到整半高时点击仍会贴到卡下沿（395+91=486 vs 下沿 500）并冻住整页。
         */
        const val CLICK_BAND_MARGIN_NUM = 2
        const val CLICK_BAND_MARGIN_DEN = 3

        /** 网格几何缺失时的历史卡片高兜底（与改动前 `?: 253` 逐位一致）。 */
        const val LEGACY_CARD_H = 253

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

    /**
     * 返回网格行整体平移 [dy] **帧**像素的轻量视图（共享同一 JSON）。测量类调用（GridAlign 相位参考）须用原始 profile。
     *
     * ⚠️ [gridRowOffset] 是加在**基准**坐标上、之后才被 `scale(...)` 的（见 cardRelRect / cellCenter /
     *   gridGeometry），而 φ 由帧像素量的落地残差算出 ⇒ 这里必须先除回 scaleY 换算成基准像素，
     *   否则非 1:1 档（如 1920x1080 回退到基准 profiles.json）实际偏移会变成 φ×scaleY。
     *   三档已标定分辨率 base==frame ⇒ scaleY=1，走快路径。
     */
    fun withGridRowOffset(dy: Int): ScreenProfile =
        ScreenProfile(root, frameWidth, frameHeight).also {
            it.gridRowOffset = if (scaleY == 1.0) dy else Math.round(dy / scaleY).toInt()
        }

    val scaleX: Double get() = frameWidth.toDouble() / baseWidth
    val scaleY: Double get() = frameHeight.toDouble() / baseHeight

    /**
     * 祝圣 yShift（帧像素，crafted 时作用于 level/lock/astral/sub1-4）。
     *
     * ⚠️ **作用范围就是这 4 类，一个都别多加**（用户 2026-09-16 定稿）：祝圣横幅只把**横幅以下**
     * 的内容下移 ⇒ 等级 / 4 条副词条 / 锁 / 收藏(astral 星标) 位移；
     * **单件名(name)、部位(slot)、主词条名(mainName)、主词条值(mainValue)、set_name 位置固定、不位移**。
     * 单件名槽位尤其不能动 —— setKey 靠「单件名→套装」反推（不读 set_name）。
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

    /**
     * 卡片内相对坐标 rel [dx0,dy0,dx1,dy1]（相对卡片左上，随卡片原点平移 + 缩放）。
     *
     * 原点一律向 [gridGeometry] 要 —— 它同时覆盖 `colX`/`rowY` 与 `cardOrigin`+`pitch` 两种写法，
     * 且 `gridRowOffset` 已在其中加过，与 [cellCenter] 同一套坐标。
     * ⚠️ 不能直接读 `cardOrigin`：`char_strip` / `char_popup` 这类 1 列·非等距网格只有 `colX`/`rowY`，
     *   裸读会让 `getJSONArray` 抛 `No value for cardOrigin` **打断整轮扫描**
     *   （2026-09-29 实测：character_scan 在第一个格 `cardRoi` 处就崩，一件都没扫到）。
     */
    fun cardRelRect(gridKey: String, rel: IntArray, col: Int, row: Int): FrameRect {
        val g = gridGeometry(gridKey) ?: error("grid '$gridKey' 几何不可用（缺 cardSize/cols/colX·rowY/cardOrigin·pitch）")
        // ★ P3（2026-09-30）：越界由"静默钳位到最后一列/行"改 fail-loud —— 钳位会把 ROI/点击
        //   落到错误卡片上（静默漏件/误点），越界本身就是数据或循环边界 bug，必须显式暴露。
        if (col !in g.colXs.indices || row !in g.rowYs.indices) {
            throw IllegalStateException(
                "cardRelRect('$gridKey') 行列越界：col=$col(合法 0..${g.colXs.size - 1}) " +
                    "row=$row(合法 0..${g.rowYs.size - 1})",
            )
        }
        val ox = g.colXs[col]
        val oy = g.rowYs[row]
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
            // ★ P3：col 由 `index % cols` 保证在界；row 越界（index 超出一页格数）改 fail-loud
            //   （原 `getOrElse{last()}` 会把点击静默钳到最后一行 ⇒ 点到错误卡片）。
            if (row !in g.rowYs.indices) {
                throw IllegalStateException(
                    "cellCenter('$gridKey', index=$index) 行越界：row=$row(合法 0..${g.rowYs.size - 1}，" +
                        "cols=${g.cols})",
                )
            }
            val x = g.colXs[col] + g.cardW / 2
            val y = g.rowYs[row] + g.clickDy
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
        // ★ 2026-09-14 修：改取**最左**卡间缝隙（原为「末尾两卡」缝隙）——
        //   2560 上后者算出 x=1627，正好贴住右侧**详情面板**左缘（面板 x≳1640）⇒ 拖拽被判成
        //   面板上的操作、网格**完全不滚**（`adb input swipe` 实测：x=1627 帧差 0.26%
        //   vs x=1041 的 28.64%，且与 a11y/弹窗/手势形态均无关）；3200 同公式得 1858，
        //   面板在 2100 外 ⇒ 所以**只有 2560 档**复现（也解释了"周四五 2244/3200 能全量扫"）。
        //   约束保持不变：必须落在**卡片之间的缝隙**（压在卡上会被判成拖卡，equip12 实证）、且避开幕布/面板。
        val pitchX = g.colXs[1] - g.colXs[0]
        val x = g.colXs[0] + g.cardW + (pitchX - g.cardW) / 2
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

    /**
     * 翻页落地测量条带（帧坐标，design-docs/swipe-landing-measure.md）：
     * `grids.<key>.landingBand = { x:[x0,x1], y:[y0,y1], searchY:[sy0,sy1] }`
     * y = 网格第4行**可见**条（视口底部硬裁剪之上的部分），x = 避开筛选状态条的后四列；
     * searchY = 翻页后搜索窗（含条带未滚动原位 ⇒ 多步补滑的中间落点也可测）。
     * 未登记返回 null ⇒ 引擎回退相邻帧 2D/相位法（不改变既有行为）。
     */
    fun landingBandFor(gridKey: String): LandingBand? {
        val obj = rawObject("grids.$gridKey.landingBand") ?: return null
        val x = obj.optJSONArray("x") ?: return null
        val y = obj.optJSONArray("y") ?: return null
        val s = obj.optJSONArray("searchY") ?: return null
        if (x.length() < 2 || y.length() < 2 || s.length() < 2) return null
        return LandingBand(
            x0 = scale(x.getInt(0), scaleX),
            y0 = scale(y.getInt(0), scaleY),
            x1 = scale(x.getInt(1), scaleX),
            y1 = scale(y.getInt(1), scaleY),
            sy0 = scale(s.getInt(0), scaleY),
            sy1 = scale(s.getInt(1), scaleY),
        )
    }

    /** [landingBandFor] 的几何（帧坐标）。 */
    class LandingBand(
        val x0: Int, val y0: Int, val x1: Int, val y1: Int,
        val sy0: Int, val sy1: Int,
    )

    /**
     * 翻页主滑的**注入缩放**（`grids.<key>.advance.touchScale`，缺省 1.0）。
     *
     * 语义：`有效滚动 ≈ 注入像素 × touchScale`。
     *
     * 实测（BlueStacks 2560，四点：命令 500/834/920/1950ms 慢滑）：比值恒 **0.905~0.911**，
     * 与分辨率配置（竖屏强制横屏 vs 原生横屏）和拖动速度（1069px/s vs 428px/s）**都无关**
     * ⇒ 一个恒定的乘性增益。即"命令 834 只滚 757"，每页恒定欠 77px，逼着相位补偿每页
     * 扛一个大偏移（实测每页残差恒负 −45..−117）。
     *
     * 引擎的**命令/残差/封顶/记账一律按"有效滚动"算**，只在这里把注入像素放大 1/touchScale
     * ⇒ 系统性欠程归零、`残差≈0`、相位补偿退化为末端微调。
     *
     * ⚠️ 为什么不直接把 `advance.distance` 调大：那样 `advTarget` 会跟着变大，引擎会把
     *   这 86px 当成欠滑**再补偿一次**（φ 反而多偏）。两者必须分开。
     */
    fun touchScaleFor(gridKey: String): Double {
        val v = rawObject("grids.$gridKey.advance")?.optDouble("touchScale", 1.0) ?: 1.0
        return if (v <= 0.0) 1.0 else v
    }

    /**
     * **点击可点带**（2026-09-24 新增，`grids.<key>.clickBand = [上沿偏移, 下沿偏移]`，基准 px）：
     * 一行之内"点下去确实落在本行那张卡"的 y 区间，**相对行顶**。
     *
     * 为什么需要它：锚点与安全窗此前都由 `cardSize[1]` 推，而 2560 档 `cardSize[1]=253` 大于
     * 实测可点带。冻结帧 20px 标尺量到：行顶 290、卡画下沿 500 ⇒ 可点带是 `[0, 210]`，
     * 卡中心 395；而旧锚点 = `290 + 253/2 = 416`（偏下 21px），旧安全窗 = `253/2 = 126`
     * （实际向下只剩 84px）⇒ 落地条带的系统偏差（run6 实测 φ=+24..+104）一路没被拦住，点击被推到
     * `416+103 = 519` = 卡下沿外 19px 的行间隙 ⇒ 整页 tap 落空 = 之前查不到因的「页级冻结」。
     *
     * 标定后：锚点 = 带中心（395）。**安全窗只取带半高的 2/3**（105 → 70）：run8 实测
     * 条带读数是**单侧**的（55 页 φ = 44..120，中位 71，无一次为负；两帧标尺证真值≈0），
     * 窗放到 105 时 `395+91=486` 仍贴着卡下沿 500，那一页就冻了 10 格 ⇒ 窗必须留出实测余量。
     * 未标定的网格（当前只剩角色弹窗等非背包网格 —— **六个背包网格已全部标定**，见 profiles×3）
     * **逐位保持旧值 `cardH/2`**：没量过就不改行为。
     */
    fun clickBandHalfFor(gridKey: String): Int {
        // ⚠️ 几何缺失时**必须**回退到与改动前同一个值（`253/2 = 126`），不能给 0：
        //   调用方拿它做 `φ.coerceIn(-窗, 窗)`，窗=0 会把每一页的平移静默清零 ⇒ 相位永不生效。
        val g = gridGeometryFor(gridKey) ?: return LEGACY_CARD_H / 2
        return scale(g.clickHalf, scaleY)
    }

    /**
     * 点击**允许被挪动**的上限（帧 px）：[clickBandHalfFor] × 安全余量。
     *
     * ⚠️ 与 [clickBandHalfFor] 是**两个不同的量**，别合并：
     * 带半高回答"这条读数还算不算数"（超过它连平移都救不回来 ⇒ 不平移、记账），
     * 本值回答"点击最多挪多远"（超过它就把 φ 钳在这里，仍然平移）。
     * 2560 圣遗物：带半高 105、本值 70。若两者都取 70，则 run8 实测中位 φ=71 的**一半页面**
     * 会从"平移"翻成"不平移"——那等于用一把已知有偏的尺子去做接受/拒绝判定，行为剧变且不可归因。
     *
     * ★★ 2026-09-25 改（#51）：**未标定 `clickBand` 的网格本值 = 0（一律不平移）**，
     *   不再"逐位保持旧值 cardH/2"。理由是两起真机事故都指向同一件事 —— 在没有实测可点带的前提下，
     *   任何平移都是拿没量过的窗去赌：
     *   ① run9（2560 圣遗物，标定**前**的 126 窗）：条带 φ 一路不被拦 ⇒ 4 页整页冻结、缺 39 件
     *      （后由 `labelAnchor` 绝对相位修到缺 3）。⚠️ 该段流传的像素细节（"推到卡下沿最后
     *      1..12px"、"距下沿约 78px"）与它自己给的常数**对不上**：按锚点 416 / 窗 126 /
     *      φ≤+119 最多到 535，而卡下沿 500；按锚点 395 / 钳 70 最多到 465，还在卡内。
     *      事件与结论（φ 来源错 → 整页冻）成立，**具体落点数字未复核**，别当依据引用（任务：查 run9 日志重述）。
     *   ② 华为真机（**当时** 2244 档未标定）：`fpband` 给 φ=−69 把行 0 点击从 y=250 顶到 y=181
     *      （该行卡顶 221 之上）⇒ 点到空白、整页重复（见 ScanEngine 的 `phiApply` 开关注释）。
     *      ⚠️ 2244 现已标定（#56），这条从"未标定保护"变成了**历史成因**；而它的上沿风险并未消失 ——
     *      见下面"已知未修"。
     *   而**未标定**网格的 φ 只有条带那一条来源，它给的是**相邻帧增量**、不是绝对相位
     *   （#38 定案：增量当绝对用 ⇒ 逐页累积并跨 ±pitch 折叠）⇒ 平移方向本身不可信。
     *   不平移 = 点击恒在名义行中心，误差只等于真实相位 |δ|≤139；平移 = 再叠一个符号都可能错的量。
     *   **代价**：|δ|>带半高的页仍会漏 —— 解是给该档量出 `clickBand`/`labelAnchor`，不是继续用窗赌。
     *   （六个背包网格已全部标定：#53 = 2560 武器，#56 = 3200/2244 两档四个网格。）
     *
     * ★ 2244 圣遗物 `clickBand` 上沿为 0 的例外（任务 #64，已修）：
     * `clickBand=[53,242]`，`cardOrigin.y=162` 比真实卡顶 215 高 53px。本函数的安全窗
     * 本身不受影响（shiftCap=clickHalf×2/3=62 ≪ clickDy−bandTop=94）；真正受影响的是
     * `ScanEngine` 的**点击下界**（`clickFloor`），改取 `−(clickDy − bandTop)` = 真实卡顶，
     * 本 [GridGeometry] 已带出 [GridGeometry.bandTop] 供其计算（旧值 `−clickDy` 会放行到
     * 名义行顶 162 = 真实卡顶之上 53px）。多数字段 / 未标定带 `bandTop=0`，`−(clickDy−0)=−clickDy`
     * 与旧值一致。
     */
    fun clickShiftCapFor(gridKey: String): Int {
        val g = gridGeometryFor(gridKey) ?: return 0
        if (!g.clickBandCalibrated) return 0
        return scale(g.clickHalf, scaleY) * CLICK_BAND_MARGIN_NUM / CLICK_BAND_MARGIN_DEN
    }

    /** 网格几何：统一 profiles 里两种写法 —— cardOrigin+pitch 与 colX/rowY 数组。 */
    private fun gridGeometry(gridKey: String): GridGeometry? {
        val grid = rawObject("grids.$gridKey") ?: return null
        val size = grid.optJSONArray("cardSize") ?: return null
        val cardW = size.optInt(0, -1)
        val cardH = size.optInt(1, -1)
        if (cardW <= 0 || cardH <= 0) return null
        // 可点带（相对行顶）：缺省 = 整卡 [0, cardH] ⇒ 锚点 cardH/2、半高 cardH/2（历史行为）
        // ⚠️ 必须是"**两个**数、0 ≤ top < bottom"才算标定成立。写错（长度不对 / 顺序颠倒 / 零宽 /
        //   负上沿）一律**当作没标定**：调用方拿半高做 `φ.coerceIn(-窗, 窗)`，窗为 0 会把平移静默清零、
        //   为负则直接抛 IllegalArgumentException 打断整轮扫描 ⇒ 宁退回历史行为也不接受坏值。
        // ★ `top ≥ 0` 不是洁癖，是**不变量**：clickDy=(top+bottom)/2、clickHalf=(bottom-top)/2，
        //   于是 `clickHalf ≤ clickDy ⟺ top ≥ 0`。而 clickShiftCap = clickHalf×2/3 必须 ≤ clickDy，
        //   否则"未标定安全窗"那条 `φ.coerceIn(-shiftCap, shiftCap)` 就能把行 0 的点击顶到**名义行顶之上**
        //   —— 那里是筛选行 / 5星开关，点上去会**改掉筛选条件**（run11 整轮塌成 930 件 5★ 的成因）。
        //   带被 `rowPhase` 直接信任为"点击最多挪 clickHalf"，所以坏值必须在进表之前挡掉。
        val band = grid.optJSONArray("clickBand")
        val bandOk = band != null && band.length() == 2 &&
            band.optInt(0) >= 0 && band.optInt(0) < band.optInt(1)
        val bandTop = if (bandOk) band.getInt(0) else 0
        val bandBottom = if (bandOk) band.getInt(1) else cardH
        val clickDy = if (bandOk) (bandTop + bandBottom) / 2 else cardH / 2
        val clickHalf =
            if (bandOk) minOf(clickDy - bandTop, bandBottom - clickDy).coerceAtLeast(1) else cardH / 2
        // 底栏锚（行顶 → 卡内"等级标签亮带"中心 的固定偏移，基准 px）。-1 = 未标定 ⇒ 绝对行相位不启用。
        // 见 GridAlign.rowPhase：那是目前唯一能给出**绝对**行顶位置的判据。
        val labelAnchor = grid.optInt("labelAnchor", -1)
        // set_filter_popup 的 cols 是 {left,right} 对象 → optInt 回退默认 → 拒绝（非卡片网格）
        // 1 列网格（char_strip 左列头像条）合法 → 门限为 <1 而非 <2
        val cols = grid.optInt("cols", -1)
        if (cols < 1) return null

        val colX = grid.optJSONArray("colX")
        val rowY = grid.optJSONArray("rowY")
        if (colX != null && rowY != null && colX.length() >= cols && rowY.length() >= 2) {
            return GridGeometry(
                cols = cols, cardW = cardW, cardH = cardH,
                clickDy = clickDy, clickHalf = clickHalf, bandTop = bandTop, clickBandCalibrated = bandOk,
                labelAnchor = labelAnchor,
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
            clickDy = clickDy, clickHalf = clickHalf, bandTop = bandTop, clickBandCalibrated = bandOk,
            labelAnchor = labelAnchor,
            colXs = IntArray(cols) { origin.getInt(0) + it * pitch.getInt(0) },
            rowYs = IntArray(vis) { origin.getInt(1) + gridRowOffset + it * pitch.getInt(1) },
        )
    }

    /** 网格几何（基准坐标）：列左边界数组 / 行上沿数组 / 卡片尺寸 / 列数。 */
    class GridGeometry(
        val cols: Int,
        val cardW: Int,
        val cardH: Int,
        /** 行顶 → 点击点 的偏移（基准 px）。见 [clickShiftCapFor] 的 `clickBand` 说明。 */
        val clickDy: Int,
        /** 可点带半高（基准 px）= 点击平移量 |φ| 的上限（标定过带时再乘安全余量）。 */
        val clickHalf: Int,
        /**
         * 可点带**上沿**相对行顶的偏移（基准 px）。`ScanEngine` 用它算 `clickFloor`：
         * 多数字段带卡在 0 ⇒ 地板 = `−clickDy`；2244 圣遗物带 = [53,242]（cardOrigin.y 比真实卡顶
         * 高 53px）⇒ 地板 = `−(clickDy − bandTop)`。**#64**：未标定带默认 = 0（沿用旧值）。
         */
        val bandTop: Int,
        /** 该网格是否标定过 `clickBand`（只有标定过的才施加安全余量）。 */
        val clickBandCalibrated: Boolean,
        /** 行顶 → 卡内底栏（等级标签亮带）中心 的偏移（基准 px）；-1 = 未标定 ⇒ [GridAlign.rowPhase] 不适用。 */
        val labelAnchor: Int,
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
