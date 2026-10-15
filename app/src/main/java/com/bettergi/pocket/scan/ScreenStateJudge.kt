package com.bettergi.pocket.scan

import android.util.Log
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * **扫描前置的界面三态判据**（2026-10-15 定稿，跨三档实测）。
 *
 * 沿革：流程前置此前只有 [ScanEngine.atHomeScreen] 一个**两态**判据（是/不是主界面）。
 * 于是「标题屏」这类**点一下就能进**的画面只能被当成"非主界面"，交给 `returnToHome`
 * 的刹车链（点返回×3 → BACK → 才轮到点屏幕）—— 华为 2026-10-14 11:22 实测
 * **单步 41.2s**（`verify/runlogs/20261014_huawei_283/hw_session.log:17066-22536`），
 * 其中前 33 秒全是无效盲点。本类要的是**正面判据**：能指着画面说"这是标题屏，该点它"
 * 或"这是加载屏，一次都不能点"，而不是"这不是主界面"。
 *
 * ## 判据（数值全部为帧内比例 ⇒ **跨档免标定**）
 *
 * ```
 * WAIT   ⇔ near_white ≥ 0.90                              （版号屏/加载屏：等待，不可点）
 * TITLE  ⇔ strip_consist ≥ 0.60 ∧ strip_ratio ≤ 0.85 ∧ (below − strip) > 20
 * 其余    ⇒ OTHER                                          （回落既有刹车链，见调用方）
 * ```
 *
 * | 量 | 口径 | 出处 |
 * |---|---|---|
 * | `near_white` | 全帧 `min(R,G,B) > 228` 的占比 | `dsl/dev/title_home_248.py:271` |
 * | `strip_consist` | 压暗条 `y[.905,.955]×x[.15,.85]` 逐列均值 vs 参考带 `y[.800,.880]`，`d<−10` 的列占比 | 同上 `:241-259` |
 * | `strip_ratio` | `strip 均值 / above 均值` | 同上 |
 * | `below − strip` | 条**下方带** `y[.96,.99]` 均值 − 条均值 | ★ 2026-10-15 新增，见下 |
 *
 * ## 为什么必须有 `below − strip`（2026-10-15 补拍发现的真缺陷）
 *
 * 只有前两条时，**加载屏的进度条会被误判成标题屏**：进度条同样是"横贯全宽的暗条"，
 * 实测 `loading_progress100` 三档 `consist=1.000/0.999/0.936`、`ratio=0.752/0.757/0.699`
 * —— 完全落在 TITLE 区间。原 74 帧语料（`title-vs-home-20261013.md`）里**没有这种形态**，
 * 所以定稿时没暴露；是 2026-10-15 冷启动补拍抓到的。
 *
 * 机制：标题屏的压暗条**夹在亮游戏画面中间**（下方还是亮的地面/天空）；
 * 加载屏进度条**下方直接是黑底**（游戏尚未渲染）。实测三档：
 * 加载进度条 `below−strip = −17 / −18 / −8`，真标题屏最低 **+46** ⇒ 阈值取 **+20**，
 * 两侧余量 ≥26。
 *
 * ## 实测覆盖（审计口径，勿删）
 *
 * 98 帧离线审计 **0 不符、非标题屏误判 TITLE = 0**：含三档 27 张标题屏（24 张成立）、
 * 三档 3 张 100% 进度条（全部正确排除）、洞穴深色场景 6 帧（`consist` 最高 0.265，余量 ≥0.335）、
 * 常驻界面/弹窗负例 20 帧。唯一漏判是 `title_after_exiticon3.png`（整帧偏暗，
 * `below−strip=−5`）—— 它是 **fail-safe**：漏判 → 落回刹车链（慢但正确），不是新失效模式。
 *
 * ## 与 [ScanEngine.atHomeScreen] 的关系
 *
 * **刻意分开、互不影响**：`atHomeScreen` 的签名与语义一字未动（它被 `returnToHome` /
 * `enterScreen` / TITLE 轮询三处共用，且其 `null` 契约承载"判不了就跳过归位"）。
 * 本类只由 `returnToHome` 的**前置分流**消费，判不出 TITLE/WAIT 时行为与改动前逐位一致。
 *
 * ⚠️ **不要给 `atHomeScreen` 加 `hp_green` 合取**（定稿 HOME 判据是 `paimon ∧ hp_green`）：
 * `home_modal` 类（断线框/退出确认框，`hp_green=0`）按 `title-vs-home-20261013.md` §0 脚注
 * 是**设计允许判 HOME** 的（HUD 真在，问题在模态框，另案处理）；加了合取会让它走进
 * 刹车链，而刹车链对模态框无效 ⇒ 可能点出未知后果。留到 S4b 处置一起做。
 */
internal object ScreenStateJudge {

    private const val TAG = "BetterGI.Scan"

    /**
     * 加载**进度条**的判据：与 TITLE 同一组带量，但 `below − strip` 落在**下侧**。
     *
     * 背景（2026-10-15 上机暴露）：冷启动链是 `真黑 → 加载屏(近白) → 进度条 → 标题屏 → 世界`。
     * `near_white` 只能覆盖到"加载屏(近白)"那一段；**进度条那一段** `nw=0.03~0.05` 判不出，
     * 于是掉进 OTHER 走刹车链（BS 实测烧 3 次盲点返回）。它是**过渡态**，正确动作同样是"等"。
     *
     * 判据形状 = TITLE 的形状但 `below − strip ≤ 20`（标题屏是**下方还是亮画面** ⇒ >+46；
     * 进度条下方是暗底 ⇒ −18…−2）。`consist ≥ 0.90`（比 TITLE 的 0.60 更严）是关键的一刀：
     * 它把「圣遗物替换确认框」那类常驻界面排除在外（实测 consist=0.799，若只按 below 分会误吸）。
     *
     * ⚠️ **这两个数字（0.90 / 20）是 215 帧全量审计的结果**，不是估的：
     * 命中 5 帧全是过渡/进度条（`loading_progress100` 三档 + `*_after_tap` 两张点击后过渡），
     * 23 帧标题屏全部落在 `below − strip > 20` 侧。**改动此判据前重跑审计**
     * （审计口径见 `dsl/docs/startup-region-criteria-todo-20261015.md` §2.2 的"第三次修订"）。
     */
    const val PROGRESS_CONSIST_MIN = 0.90

    /** `near_white` 阈值：版号/加载屏实测 0.949–0.999，其余 ≤0.126。 */
    const val NEAR_WHITE_MIN = 0.90

    /**
     * `near_black` 阈值：**冷启动过渡期的纯黑帧**也属等待类。
     *
     * 出处：2026-10-15 BS 冷启动连拍抓到的 `loading_black_2244_c02.png` —— 全帧均值 0.13、
     * `max(R,G,B) < 24` 的像素占 **99.9%**（真黑，不是"暗场景"）。它出现在游戏进程刚起、
     * 还没开始渲染那一刻。不归到 WAIT 的后果：落进 OTHER ⇒ 归位链先烧 3 次盲点返回
     * （2026-10-15 上机实测复现：日志里 `盲点 1/3…3/3` 之后才判出等待类屏）。
     *
     * ⚠️ 阈值取 0.95 而不是更松：在盘帧里"暗场景"最高的是退出确认框压暗后的洞穴帧
     * （`near_black=0.354`）⇒ 0.95 与真实暗场景之间有 0.6 的余量，不会把普通暗画面吞进来。
     * 全黑时 `strip/consist` 等量都无意义（比不出任何结构），所以"推定为等待"是安全方向：
     * 它最多让归位多等几拍，而误点一个真黑屏只会把流程带进未知状态。
     */
    const val NEAR_BLACK_MIN = 0.95

    /** 压暗条被压暗的列占比阈值：标题屏 0.653–0.956，其余 ≤0.552。 */
    const val CONSIST_MIN = 0.60

    /** 条带相对暗度上限：标题屏最高 0.806（与 consist 取 ∧，单独用不成立）。 */
    const val RATIO_MAX = 0.85

    /**
     * `below − strip` 阈值（灰阶）：加载进度条 −18…−8，标题屏 ≥+46 ⇒ 取 +20，两侧余量 ≥26。
     * 见类 KDoc 的"为什么必须有 below − strip"。
     */
    const val BELOW_MINUS_STRIP_MIN = 20.0

    /** 「明显更暗」的灰阶门（同引擎其它亮度判据的量级；`title_home_248.py:245` 同值）。 */
    private const val STRIP_STEP = 10.0

    /** 三处条带的帧内比例区（y0,y1,x0,x1）——与 `dsl/dev/title_home_248.py:241-245` 逐值对齐。 */
    private val STRIP = doubleArrayOf(0.905, 0.955, 0.15, 0.85)
    private val REF_ABOVE = doubleArrayOf(0.800, 0.880, 0.15, 0.85)
    private val BELOW = doubleArrayOf(0.960, 0.990, 0.15, 0.85)

    /** 近白的通道门（`title_home_248.py:271` 的 `mn > 228` ⇒ 整数下等价 `min ≥ 229`）。 */
    private const val NEAR_WHITE_LO = 229.0

    /** 光度特征（一帧一次，供 [classify] 判定；可在日志里直接打印）。 */
    data class Photometric(
        val nearWhite: Double,
        val nearBlack: Double,
        val consist: Double,
        val ratio: Double,
        val belowMinusStrip: Double,
    ) {
        override fun toString(): String =
            "nearWhite=%.3f nearBlack=%.3f consist=%.3f ratio=%.3f below-strip=%.1f"
                .format(nearWhite, nearBlack, consist, ratio, belowMinusStrip)
    }

    enum class ScreenClass { WAIT, TITLE, OTHER }

    /**
     * 一帧的光度特征。空帧/尺寸为 0 ⇒ `null`（调用方按"判不了"处置，回落既有路径）。
     *
     * 实现一律走 OpenCV 原生算子：**不要写 JVM 逐像素遍历** —— 2244 档全帧 242 万像素，
     * 华为（A73 小核）JVM 循环实测 ~200ms 级，而本函数处在归位循环里（每轮都要跑）。
     * 目标 ≤20ms。
     */
    fun photometric(frame: Mat): Photometric? {
        if (frame.empty() || frame.cols() <= 0 || frame.rows() <= 0) return null
        val h = frame.rows()
        val w = frame.cols()
        val gray = Mat()
        try {
            Imgproc.cvtColor(frame, gray, Imgproc.COLOR_BGR2GRAY)
            val seam = seamMetrics(gray, h, w)
            return Photometric(
                nearWhite = nearWhiteFraction(frame),
                nearBlack = nearBlackFraction(frame),
                consist = seam.first,
                ratio = seam.second,
                belowMinusStrip = seam.third,
            )
        } catch (e: Exception) {
            // 判据层按"判不了"降级：不让一帧异常炸掉整轮归位
            Log.w(TAG, "ScreenStateJudge.photometric 异常 ⇒ 本帧判不了：${e.message}")
            return null
        } finally {
            gray.release()
        }
    }

    /**
     * `min(R,G,B) > 228` 的像素占比（等价 python `(mn > 228).mean()`）。
     *
     * 用 `Core.inRange` 而不是"三次 extractChannel + 两次 Core.min + compare"：后者的等价语义是
     * `min ≥ 229`（整数下 `>228` 即 `≥229`），而 `inRange` 一趟就够（少 6 遍全帧扫描 ——
     * 本函数在归位循环里每轮都跑，华为上 240 万像素每遍都是实数成本）。
     */
    private fun nearWhiteFraction(frame: Mat): Double {
        val mask = Mat()
        try {
            Core.inRange(frame, Scalar(229.0, 229.0, 229.0), Scalar(255.0, 255.0, 255.0), mask)
            return Core.countNonZero(mask).toDouble() / (frame.rows().toDouble() * frame.cols().toDouble())
        } finally {
            mask.release()
        }
    }

    /** `max(R,G,B) < 24` 的像素占比（真黑帧判据，见 [NEAR_BLACK_MIN]）。 */
    private fun nearBlackFraction(frame: Mat): Double {
        val mask = Mat()
        try {
            Core.inRange(frame, Scalar(0.0, 0.0, 0.0), Scalar(23.0, 23.0, 23.0), mask)
            return Core.countNonZero(mask).toDouble() / (frame.rows().toDouble() * frame.cols().toDouble())
        } finally {
            mask.release()
        }
    }

    /**
     * 条带三量：`(consist, ratio, below − strip)`。
     *
     * `consist` 是**逐列**均值差 `d = strip.mean(axis=0) − above.mean(axis=0)` 里 `d < −10`
     * 的列占比 —— 逐列而不是整条均值，是因为标题屏的压暗条只在**底部一条**上，
     * 而画面内容（云/角色）会污染整条均值。
     *
     * ⚠️ `Core.reduce` **必须显式给 `CV_32F`**：8U 输入的 `REDUCE_AVG` 会把列均值**截断成整数**
     * （Java/C++ 绑定与 numpy 的行为不同，numpy 的 `mean(axis=0)` 天生 float64）——
     * 截断会让 `d < −10` 的判定与 python 口径对不上，且误差方向随内容漂。
     */
    private fun seamMetrics(gray: Mat, h: Int, w: Int): Triple<Double, Double, Double> {
        val strip = roi(gray, STRIP, h, w)
        val above = roi(gray, REF_ABOVE, h, w)
        val below = roi(gray, BELOW, h, w)
        val colsStrip = Mat()
        val colsAbove = Mat()
        val diff = Mat()
        val mask = Mat()
        try {
            Core.reduce(strip, colsStrip, 0, Core.REDUCE_AVG, CvType.CV_32F)
            Core.reduce(above, colsAbove, 0, Core.REDUCE_AVG, CvType.CV_32F)
            Core.subtract(colsStrip, colsAbove, diff)
            Core.compare(diff, Scalar(-STRIP_STEP), mask, Core.CMP_LT)
            val nCols = colsStrip.cols().toDouble().coerceAtLeast(1.0)
            val consist = Core.countNonZero(mask).toDouble() / nCols
            val stripMean = Core.mean(strip).`val`[0]
            val aboveMean = Core.mean(above).`val`[0]
            val belowMean = Core.mean(below).`val`[0]
            val ratio = stripMean / aboveMean.coerceAtLeast(1.0)
            return Triple(consist, ratio, belowMean - stripMean)
        } finally {
            strip.release(); above.release(); below.release()
            colsStrip.release(); colsAbove.release(); diff.release(); mask.release()
        }
    }

    /** 按帧内比例取子区（自动 clamp；退化区返回空 Mat，由调用方按 0 处置）。 */
    private fun roi(src: Mat, p: DoubleArray, h: Int, w: Int): Mat {
        val x0 = (w * p[2]).toInt().coerceIn(0, w - 1)
        val x1 = (w * p[3]).toInt().coerceIn(x0 + 1, w)
        val y0 = (h * p[0]).toInt().coerceIn(0, h - 1)
        val y1 = (h * p[1]).toInt().coerceIn(y0 + 1, h)
        return Mat(src, Rect(x0, y0, x1 - x0, y1 - y0))
    }

    /**
     * 分类。**优先级 WAIT（近白/近黑/进度条）→ TITLE**。
     *
     * 三种 WAIT 都用"安全侧优先"：它们都是过渡态，等待永远比点击安全
     * （点击会打断渲染，或落在没有意义的位置）。
     */
    fun classify(p: Photometric): ScreenClass = when {
        p.nearWhite >= NEAR_WHITE_MIN -> ScreenClass.WAIT
        p.nearBlack >= NEAR_BLACK_MIN -> ScreenClass.WAIT
        // 进度条：形状同 TITLE 但 below−strip 在下侧（见 PROGRESS_CONSIST_MIN 的 KDoc）
        p.consist >= PROGRESS_CONSIST_MIN && p.ratio <= RATIO_MAX && p.belowMinusStrip <= BELOW_MINUS_STRIP_MIN ->
            ScreenClass.WAIT
        p.consist >= CONSIST_MIN && p.ratio <= RATIO_MAX && p.belowMinusStrip > BELOW_MINUS_STRIP_MIN ->
            ScreenClass.TITLE
        else -> ScreenClass.OTHER
    }
}
