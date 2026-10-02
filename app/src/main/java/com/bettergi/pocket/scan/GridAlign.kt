package com.bettergi.pocket.scan

import android.util.Log
import com.bettergi.pocket.core.IntRect
import org.opencv.core.Mat

/**
 * §12.2/§12.5 网格相位检测（落地：逐列横向台阶 + 跨列众数 + 中段行 + p0 基准）。
 *
 * 检测器（B1，bench `swipe_edge_mode_benchmark.md` / 录屏 `swipe_recording_edge_validation.md` 定稿）：
 *   逐卡列取行亮度剖面，找「暗→亮」横向台阶
 *   （below=mean(y+2..y+8)、above=mean(y−8..y−2)、score=below−above），窗 ±WIN 取 argmax；
 *   单列 score<THR 剔除。跨列聚合：众数(2px分箱)为主、中位兜底。
 *   基准行改中段第2行（REF_ROW）以避开首行滑出顶边；并用「第3行−第2行≈行距」做周期自检守卫。
 *
 * ⚠️ 2026-09-14（翻页整体改造）：§12.2 控制律 `nextDistance` 已删（09-05 `50a0f87` 移除调用、
 *   本轮删除死代码本体）——「残差进下一滑」语义以 **pageDrift 记账**形式回归 ScanEngine
 *   （fpband 落地条带超限 ⇒ 记账进下一次主滑距离，见 design-docs/swipe-landing-measure.md）。
 *   phaseOffset 现为**唯一回退**判据：fpband 不可测时仅平移点击坐标，**不再补滑**（用户定稿）。
 * ⚠️ 本检测器的固有误差带：锚点取「±150 窗内每列最强暗→亮台阶」argmax
 *   ⇒ 常锁到**卡内特征**（等级标签带 rowtop+208 / 星带 / 锁徽）而非卡框顶，偏移 k **帧间不稳**
 *   （2026-09-14 实测同一页两法差 71px，mod 行距）⇒ 判据余量（±146 混叠边界 − 126 卡片半高 = 20px）
 *   **小于**该误差带 ⇒ 单用它会误报"偏移"并多补一滑。
 *
 * 守卫：≥MIN_VOTES 列有台阶（拒翻票/无网格）；周期自检 |row3−row2−pitch|<PERIOD_TOL；
 *   |drift|>半pitch 限幅；与上一页 drift 相差>半pitch 判相位混叠拒采（连续性）。
 *
 * 耗时：3200×1440 原生逐列+聚合 ≈1~4ms/帧（bench 1.1，录屏分析 3.9），远低于帧间隔，可每帧实时测。
 */
object GridAlign {

    // ---- 台阶检测常量（基准 3200×1440 尺度；空间量 WIN/PERIOD_TOL/LIMIT 按 scaleY 缩放，
    //      台阶窗 STEP_* 为固定特征窗、对比阈值 THR 不缩放——与 bench 原生标定严格一致）----
    private const val STEP_NEAR = 2    // 距候选 y 的近边界（窗从 y±2 起，避开 y 自身）
    private const val STEP_BELOW = 8   // below 窗：y+2 .. y+8
    private const val STEP_ABOVE = 8   // above 窗：y−8 .. y−2
    private const val WIN = 150        // 搜索窗口（±，帧坐标缩放）
    private const val TAG = "BetterGI.Align"
    private const val THR = 10         // 单列台阶最小得分（对比阈值，不缩放）
    private const val MIN_VOTES = 6     // 共识门：有效列 ≥6/7（单列软边噪声由众数吸收）
    private const val PERIOD_TOL = 40  // 周期自检容差（|row3−row2−pitch|，帧坐标缩放）
    private const val REF_ROW = 2      // 检测基准行（中段第2行，避开顶边滑出）
    private const val LIMIT = 146      // 半 pitch 限幅（漂移）

    // ---- 底栏锚绝对行相位（2026-09-25）：空间量按 scaleY 缩放，亮度/对比度量为 0..255 不缩放 ----
    private const val BAND_MIN_RUN = 20      // 亮带最小宽度（2560 实测 36..68）
    private const val BAND_MAX_RUN = 96      // 亮带最大宽度（超过即认为是整片高亮而非一条带）
    private const val BAND_CONTRAST = 40     // 峰-中位 对比度下限
    private const val BAND_HI_NUM = 3
    private const val BAND_HI_DEN = 5        // 起带阈 = 中位 + 3/5 对比度
    private const val BAND_LO_NUM = 2
    private const val BAND_LO_DEN = 5        // 收带阈 = 中位 + 2/5 对比度（滞回）
    private const val BAND_TOL = 14          // 跨列/跨行 δ 一致性容差
    private const val BAND_MIN_COLS = 5      // 共识门：至少几列投出一致的带（6 列网格允许 1 列空/翻票）

    // ---- 逐行锚定的**文本行**检测（#169，筛选面板这类"每行没有标签亮带"的网格用）----
    //
    // 与上面的底栏锚 [rowPhase] 并列，共用同一套语义（profile `labelAnchor` =
    // 「行顶 → 本网格用于绝对行相位的行内特征中心」），只是**特征与聚合方式**不同：
    //   · 背包类网格：特征是**卡内等级标签亮带** ⇒ 逐**列**量带、跨列取众数（6~7 列，抗单列翻票）；
    //   · 筛选面板：特征是**套名文本行**（每行没有标签亮带）⇒ 逐**行**量墨迹带、以锚定心
    //     （2 列无法做"多数票"，而逐行独立测量天然有 8~16 份样本，判据放在"离锚多远"上）。
    private const val TEXT_QUANTILE = 0.95   // 行亮度取 95 分位：字在窄列里占比低，均值会被背景抹平
    private const val TEXT_CONTRAST_MIN = 12.0 // 剖面 峰−中位 下限（空带实测 ≤8；灰化字实测 ≥22；底栏带的门是 40）
    private const val TEXT_HI_NUM = 2
    private const val TEXT_HI_DEN = 5        // 起带阈 = 中位 + 2/5 对比度（离线标定值 0.40）
    private const val TEXT_AMBIG_MIN = 8     // 双候选带"离锚最近"至少要赢这么多 px，否则判没量准
    // 横条（行卡片分隔线）判据＝**最长一段上墨列 / ROI 宽**。2244 真机 38 帧 608 次逐行量测：
    // 文字带（2~7 字、列覆盖 0.25~0.86）此值恒 ≤0.13（≈一个字宽 / 名框宽，与分辨率同比例缩放）；
    // 横条/高亮块 0.30~1.00。覆盖率那条判据不行——同一根线被复选框打断时 cov 掉到 0.45~0.81。
    private const val TEXT_BAR_LONGEST = 0.30

    /** p0 基准：顶对齐首帧检测到的绝对位置（帧坐标）；null = 未建立 → drift 退化为 detected−expected。 */
    private val baselines = mutableMapOf<String, Int?>()

    /** 扫描会话开始 / 进背包顶对齐时调用：清 p0 基准（随后 captureBaseline 建立）。 */
    fun resetBaseline(gridKey: String? = null) {
        if (gridKey == null) baselines.clear() else baselines.remove(gridKey)
    }

    /** 顶对齐首帧建立 p0 基准：drift 自此抵消特征偏移 k（检测落卡顶/星星均无关）。 */
    fun captureBaseline(frame: Mat, profile: ScreenProfile, gridKey: String) {
        baselines[gridKey] = detectRow(frame, profile, gridKey)
    }

    /**
     * 测量基准行上沿相对 profiles 真值的误差（帧坐标）。
     * @return drift（= detected − p0，p0 未建则 detected − expected）；超半pitch/几何不足/守卫不通过→null。
     */
    fun measureError(frame: Mat, profile: ScreenProfile, gridKey: String): Int? {
        val detected = detectRow(frame, profile, gridKey) ?: return null
        val expected = refExpected(profile, gridKey) ?: return null
        val ref = baselines[gridKey] ?: expected
        val drift = detected - ref
        if (Math.abs(drift) > profile.scale(LIMIT, profile.scaleY)) return null
        return drift
    }

    /** 行距（帧坐标），相位/记账判据阈值基准。 */
    fun rowPitch(profile: ScreenProfile, gridKey: String): Int {
        val g = profile.gridGeometryFor(gridKey) ?: return 0
        return profile.scale(g.rowPitch.toInt(), profile.scaleY)
    }

    /**
     * §12.5 相位偏移：当前帧网格相位相对 p0 基准（顶对齐首帧）的**累计漂移**（帧坐标，
     * 归一到 [−pitch/2, pitch/2)；正=内容偏下=累计少滚，负=偏上=多滚）。
     * 网格特征沿行距周期重复 → ±整行的错位被 mod 吸收，残差只反映半行距内的真实偏移；
     * 单页误差超过 ±半行距（146）时符号混叠——调用方须保证 |φ| 不逼近该边界
     * （2026-09-16 起：超卡片半高**不再同页补滑**，改为「本页不平移 + 残差记账进下一次主滑」）。
     * 测量必须用**未偏移**的原始 profile（固定参考）；offset 视图会使 expected 随之平移。
     * @return null = 未建 p0 / 检测守卫不通过（共识门/周期自检），调用方沿用上一页偏移。
     */
    fun phaseOffset(frame: Mat, profile: ScreenProfile, gridKey: String): Int? {
        val det = detectRow(frame, profile, gridKey) ?: return null
        val p0 = baselines[gridKey] ?: return null
        return centeredMod(det - p0, rowPitch(profile, gridKey))
    }

    // ---- ★ 2026-09-16（审计 P0-2/P1-2 修）：翻页主滑规划与记账的**唯一实现点**（纯函数 ⇒ 可单测）----
    // 为什么抽出来：原逻辑内联在 ScanEngine 的页循环里，(a) 记账变量声明在循环内 ⇒ 写入即丢（P0-2）；
    // (b) 钳制余量与相位记账写同一个变量且都是赋值语义 ⇒ 谁后写谁生效（P1-2）。抽成纯函数后
    // 规则只有一处表述、且能被单测钉住。

    /**
     * 下一页**主滑命令**规划：`cmd = clamp(round(target/gain) + drift, minCmd, maxCmd)`。
     *
     * @param drift 上一页留下的记账（帧 px，正 = 需多滑以补欠量）
     * @return `(cmd, carry)`；`carry = cmdRaw − cmd` = **未能发出**的钳制余量，作兜底记账
     *         （仅当本页相位测不到时才有意义；测到则被 [driftAfterPage] 覆盖）。
     * @note `gain ≤ 0` 视为 1.0（防除零；调用方已夹在 0.25~1.6）。
     */
    fun planMainSwipe(target: Int, gain: Double, drift: Int, minCmd: Int, maxCmd: Int): Pair<Int, Int> {
        val g = if (gain <= 0.0) 1.0 else gain
        val cmdRaw = Math.round(target.toDouble() / g).toInt() + drift
        // ★ 2026-09-16 用户定稿：「不要加增益，宁愿重复点也不要漏件」⇒ **命令上界 = target（永不超滚）**。
        //   机理：过滚 ⇒ 内容推进 > 遍历行数 ⇒ 点击落到下一张卡 ⇒ **中间一行被静默跳过**
        //   （不产生重复件、逐页计数看不出来，只能靠 ground truth 对比才发现）；
        //   欠滚只会与上一页重叠 ⇒ 表现为**重复件**（可观测、可接受）。
        val cmdClamped = Math.max(minCmd, Math.min(maxCmd, cmdRaw))
        val cmd = Math.min(cmdClamped, target)
        // ⚠️ 被 **target 上限**削掉的部分**不记账**（remain=0）：该上限是"刻意不补偿欠量"，
        //   若把差额记进 pageDrift，drift 会只增不减（因为永远补不上）⇒ 失去语义。
        //   只有 minCmd/maxCmd 钳制造成的余量才继续记账（原语义）。
        val remain = if (cmd < cmdClamped) 0 else (cmdRaw - cmdClamped)
        return cmd to remain
    }

    /**
     * 落地残差 → **本页点击坐标的相位平移量**（帧 px）。纯函数，唯一符号来源（可单测）。
     *
     * ⚠️ 符号约定（2026-09-16 真机定案，别再写反）：`withGridRowOffset(φ)` 的实现是
     *   **`clickY = rowY + φ`**（正 = 点击下移）。而
     *     `residual = L − target`，少滚（L<target）⇒ 内容比理想位置**偏下** ⇒ 点击必须**下移**
     *   ⇒ 补偿量 = `target − L` = **`−residual`**。
     *   2026-09-14 的旧实现正是 `centeredMod(advTarget − 累计落地)`（即 −residual）✓；
     *   09-15 单滑模型改写时误写成 `centeredMod(residual)`，符号翻转 ⇒ 真机实测
     *   （3200/BS，L=801/target=876）把点击上移 75px 而非下移 75px，整页 21 格全部读到
     *   上一页内容（全重复）。⇒ 本函数把符号钉在一处，并由 GridAdvancePlanTest 守门。
     */
    fun phiFromLanding(residual: Int, pitch: Int): Int = centeredMod(-residual, pitch)

    /**
     * 相位消化后的**下一次主滑记账**（帧 px）。
     *
     * - `|phiMod| ≤ cardHalf`（本页靠平移点击坐标对齐）⇒ **0**（本页已消化，下一页按目标整页推进）；
     * - 否则（超半卡高，本页**不平移**）⇒ **−residual**：把欠量/过冲整体并入下一次主滑。
     *
     * ⚠️ 用 `residual`（未 mod 的 `L − target`）而非 `phiMod`：残差绝对值可能跨多行
     * （如只落地 191 ⇒ residual −685），那是**真实欠量**，必须整额补，不能按 mod 后的 φ 缩水。
     */
    fun driftAfterPage(phiMod: Int, residual: Int, cardHalf: Int): Int =
        if (Math.abs(phiMod) <= cardHalf) 0 else -residual

    /** 单页残差：x 归一到 [−pitch/2, pitch/2)（正=少滚，负=多滚）。 */
    fun centeredMod(x: Int, pitch: Int): Int {
        if (pitch <= 0) return 0
        val m = ((x % pitch) + pitch) % pitch
        return if (m > pitch / 2) m - pitch else m
    }

    /**
     * 本滑**实际推进的行数**（几何先验；#70）。
     *
     * ⚠️ 只能逐页取整，**不许累加**：三份真机日志逐页 `round(L/pitch)` 得到 39/39、57/57 页
     * 都等于遍历行数，而 `Σ L / pitch` 在 40 页上少算 10.46 行 —— 条带有每页 ~0.15–0.2 行的
     * 固定欠读，取整吸收得掉、累加吸收不掉。
     *
     * 它是**先验不是判决**：lockfix 那轮有 1 页 L/pitch=2.34，离线分不清"真滑空半页"还是量错，
     * 所以跳格仍由 identity 锚定（#66）把关。本值的用途是把两个估计摆进同一行日志，
     * 让"几何说推进了几行"在事后对账时可见。
     *
     * @return null = 行距不可用（未标定网格），调用方按"没测"处理。
     */
    fun rowsAdvanced(landingPx: Int, pitch: Int): Int? {
        if (pitch <= 0) return null
        return Math.round(landingPx.toDouble() / pitch).toInt()
    }

    // ==== ★ 2026-09-25 底栏锚定的**绝对**行相位（run9 像素自标定后换源）====================
    //
    // 为什么换：fpband 给的是**相邻两帧之间的位移**（L − target），而 `withGridRowOffset(φ)` 需要的是
    //   「本页行顶相对名义行顶的绝对偏移」。两者只在"上一页恰好落在名义位"时等价。run9 实测：
    //   条带每页都报 L=715..790（target=834）⇒ 每页都平移 φ=+44..+119（钳 70）**向下**；
    //   而同三张冻结帧逐帧像素量出的真实行顶是 189/213/219（名义 290，即内容**偏上** 71..101）。
    //   两者方向相反 ⇒ 点击被从卡中心 395 一路推到 439..465，落在卡下沿最后 1..12px 的死区
    //   （正常页同一条 φ 下点击距下沿约 78px，所以只有"运气差 25px"的那几页整页冻结）。
    //
    // 为什么这次能用卡内特征当锚（2026-09-24 的 detectRow 绝对相位被 run7 否决）：那次是**把锁到的
    //   特征当成卡顶**用（列均值按整卡宽取，台阶常落在底栏/星带上 ⇒ 逐页乱跳 ±137）。本检测器反过来
    //   **只认那条底栏**（等级标签亮带，6 列同时出现、边沿硬、亮度 200+ 对周边 120..160），
    //   并按 profiles 标定 `cardtop = 带中心 − labelAnchor` 折回行顶 ⇒ 特征偏移不再是误差而是锚。
    //   run9 三帧互验：带中心 428/398/422 ⇒ 行顶 219/189/213，与"间隙最低点−12−253/2"独立推法
    //   相差 ≤6px（三页一致）。

    /** 一次绝对行相位测量。[offset] = 实测行顶 − 名义行顶（帧 px，正=内容偏下/欠滚，负=偏上/多滚）。 */
    data class RowPhase(val offset: Int, val columns: Int, val votes: Int, val spread: Int, val bars: Int)

    /**
     * 底栏锚绝对行相位（帧 px）。守卫：每列须有 ≥2 条**间隔≈行距**的亮带（杀筛选栏等一次性亮带）；
     * 跨列共识 ≥ [BAND_MIN_COLS] 票且一致带 ≤ [BAND_TOL]×2。任一门不过 ⇒ null（调用方退回原判据）。
     *
     * ⚠️ 必须用**未偏移**的基准 profile（同 [phaseOffset] 的约束）：名义行顶要从 `g.rowYs[0]` 取，
     *   偏移视图会把上一次的结果喂回自己 ⇒ 闭环自证。
     */
    fun rowPhase(frame: Mat, profile: ScreenProfile, gridKey: String): RowPhase? {
        if (frame.empty()) return null
        val g = profile.gridGeometryFor(gridKey) ?: return null
        if (g.labelAnchor < 0 || g.rowYs.isEmpty()) return null
        val sy = profile.scaleY
        val pitch = profile.scale(g.rowPitch.toInt().coerceAtLeast(1), sy)
        if (pitch <= 0) return null
        val firstCenter = profile.scale(g.rowYs[0] + g.labelAnchor, sy)
        val minRun = profile.scale(BAND_MIN_RUN, sy)
        val maxRun = profile.scale(BAND_MAX_RUN, sy)
        val tol = profile.scale(BAND_TOL, sy)
        val yFrom = (firstCenter - pitch).coerceAtLeast(0)
        val yTo = (firstCenter + g.rowYs.size * pitch + maxRun).coerceAtMost(frame.rows() - 1)
        if (yTo <= yFrom + pitch) return null

        val barsByCol = ArrayList<List<Int>>(g.cols)
        var bars = 0
        for (c in 0 until g.cols) {
            val x0 = profile.scale(g.colXs[c], profile.scaleX).coerceIn(0, frame.cols() - 2)
            val x1 = profile.scale(g.colXs[c] + g.cardW, profile.scaleX)
                .coerceIn(x0 + 2, frame.cols())
            val prof = DoubleArray(frame.rows()) { rowMean(frame, it, x0, x1) }
            val kept = periodKeep(brightBands(prof, yFrom, yTo, minRun, maxRun), pitch, tol)
            if (kept.size >= 2) {
                bars += kept.size
                barsByCol.add(kept)
            }
        }
        val gate = minOf(
            profile.rawObject("grids.$gridKey")?.optInt("phaseMinCols", BAND_MIN_COLS) ?: BAND_MIN_COLS,
            g.cols,
        )
        val phase = phaseFromBars(barsByCol, firstCenter, pitch, tol, gate)
        if (phase == null) {
            Log.d(
                TAG,
                "phaseDiag[$gridKey] 守卫不过: gate=$gate cols=${barsByCol.size} bars=$bars " +
                    "centers=$barsByCol pitch=$pitch first=$firstCenter",
            )
        }
        return phase
    }

    /**
     * 纯函数（可单测）：各列带中心 → 绝对行相位。
     *
     * 每条带折一票 `δ = centeredMod(带中心 − firstCenter, pitch)`（`firstCenter` = 名义第 0 行亮带中心）；
     * 行序 r 不必枚举 —— 相差整数倍行距的候选经 `centeredMod` 自动同解。
     *
     * 聚合取**按"不同列数"计的众数**而不是全体中位：中位在"一半列锁到别的特征"时会给出
     * 一个看似自洽的错答案（真机 6 列里 3+3 对半分是中位法的经典失败），众数 + 列数门
     * 会直接拒测。三门：进入投票的列 ≥ [minCols]、众数群覆盖列数 ≥ [minCols]、群内一致带 ≤ 2·tol。
     */
    internal fun phaseFromBars(
        barsByCol: List<List<Int>>,
        firstCenter: Int,
        pitch: Int,
        tol: Int,
        minCols: Int,
    ): RowPhase? {
        if (pitch <= 0 || minCols <= 0) return null
        val voting = barsByCol.filter { it.size >= 2 }
        if (voting.size < minCols) return null
        val votes = ArrayList<Pair<Int, Int>>(voting.size * 2)
        voting.forEachIndexed { c, col -> col.forEach { b -> votes.add(centeredMod(b - firstCenter, pitch) to c) } }
        val all = votes.map { it.first }.sorted()
        val mid = all[all.size / 2]
        // 以每票为候选锚，统计 ±tol 内**覆盖的列数**（平票取更靠近全体中位的一侧）
        var bestCols = -1
        var bestDist = Int.MAX_VALUE
        var best: List<Pair<Int, Int>> = emptyList()
        for ((d, _) in votes) {
            val grp = votes.filter { Math.abs(it.first - d) <= tol }
            val cols = grp.map { it.second }.toSet().size
            val dist = Math.abs(d - mid)
            if (cols > bestCols || (cols == bestCols && dist < bestDist)) {
                bestCols = cols; bestDist = dist; best = grp
            }
        }
        if (bestCols < minCols) return null
        val ds = best.map { it.first }.sorted()
        val spread = ds.last() - ds.first()
        if (spread > tol * 2) return null
        return RowPhase(ds[ds.size / 2], bestCols, ds.size, spread, votes.size)
    }

    /**
     * 纯函数：一列行亮度剖面里的亮带中心（滞回起停 + 宽度门）。
     * 阈值自适应于该列自身（中位 + 3/5·对比度 起带、2/5 收带）⇒ 不随分辨率/整体明暗漂移。
     * 对比度 < [BAND_CONTRAST] 直接判无带（空列/无网格）。
     */
    internal fun brightBands(prof: DoubleArray, yFrom: Int, yTo: Int, minRun: Int, maxRun: Int): List<Int> {
        if (yTo <= yFrom) return emptyList()
        val win = prof.copyOfRange(yFrom, yTo + 1)
        val med = win.sortedArray()[win.size / 2]
        val mx = win.maxOrNull() ?: return emptyList()
        if (mx - med < BAND_CONTRAST) return emptyList()
        val hi = med + (mx - med) * BAND_HI_NUM / BAND_HI_DEN
        val lo = med + (mx - med) * BAND_LO_NUM / BAND_LO_DEN
        val out = ArrayList<Int>()
        var start = -1
        for (i in win.indices) {
            val v = win[i]
            if (start < 0) {
                if (v >= hi) start = i
            } else if (v < lo) {
                val w = i - start
                if (w in minRun..maxRun) out.add(yFrom + (start + i - 1) / 2)
                start = -1
            }
        }
        if (start >= 0 && win.size - start in minRun..maxRun) {
            out.add(yFrom + (start + win.size - 1) / 2)
        }
        return out
    }

    /** 纯函数：只保留"存在**相邻行距**（±pitch，容差 tol）伙伴"的带 ⇒ 一次性亮带（筛选栏/横幅/标题）被剔除。
     *  ⚠️ 只认 k=1 的邻居：某列若丢的是**中间**那条带，剩下两条相距 2·pitch ⇒ 互不认账 ⇒ 该列整体出局
     *     （保守方向 = 少投票，不是投错票；共识门 [BAND_MIN_COLS] 允许少数列这样掉队）。 */
    internal fun periodKeep(bars: List<Int>, pitch: Int, tol: Int): List<Int> {
        if (pitch <= 0 || bars.size < 2) return emptyList()
        return bars.filter { b -> bars.any { it != b && Math.abs(Math.abs(it - b) - pitch) <= tol } }
    }

    // ---- 内部 ----

    /** 基准行上沿绝对位置（帧坐标）：含 ≥MIN_VOTES 共识门 + 周期自检。守卫不通过/几何不足→null。 */
    private fun detectRow(frame: Mat, profile: ScreenProfile, gridKey: String): Int? {
        if (frame.empty()) return null
        val g = profile.gridGeometryFor(gridKey) ?: return null
        val sy = profile.scaleY
        val pitch = profile.scale(g.rowPitch.toInt(), sy)
        if (pitch <= 0) return null
        val expected = refExpected(profile, gridKey) ?: return null
        val win = profile.scale(WIN, sy)

        val colProfiles = buildColumnProfiles(frame, profile, g)
        // 共识门：有效列 ≥ min(MIN_VOTES, cols)（7 列网格允许 1 列翻票；3 列等小网格需全列一致）
        val gate = minOf(MIN_VOTES, g.cols)

        val base = detectStep(colProfiles, expected, win, gate)
        if (base.validVotes < gate) {
            logDiag(gridKey, g.cols, gate, expected, pitch, base, null, "base 共识门不通过")
            return null
        }

        // 周期自检：第3行（基准行 + 行距）应同样检出且间距≈行距（拒特征翻转/翻票）
        if (g.rowYs.size > REF_ROW + 1) {
            val third = detectStep(colProfiles, expected + pitch, win, gate)
            if (third.validVotes < gate) {
                logDiag(gridKey, g.cols, gate, expected, pitch, base, third, "third 共识门不通过")
                return null
            }
            val period = profile.scale(PERIOD_TOL, sy)
            if (Math.abs(third.consensus - base.consensus - pitch) > period) {
                logDiag(gridKey, g.cols, gate, expected, pitch, base, third, "周期自检超差")
                return null
            }
        }
        return base.consensus
    }

    /**
     * 失败诊断（D 级）：仅在检测失败时打，定位「为何 measureError 返回 null」
     * ——Bluestacks 等软渲染环境列台阶 score 普遍低于 THR 会致 votes<gate。
     */
    private fun logDiag(
        gridKey: String, cols: Int, gate: Int, expected: Int, pitch: Int,
        base: StepResult, third: StepResult?, why: String,
    ) {
        Log.d(
            TAG,
            "alignDiag[$gridKey] $why: cols=$cols gate=$gate THR=$THR expected=$expected pitch=$pitch " +
                "base(votes=${base.validVotes} y=${base.consensus}) " +
                "third(votes=${third?.validVotes} y=${third?.consensus})",
        )
    }

    /** 基准行期望 y（帧坐标）= 中段第2行（行数不足则末行）。 */
    private fun refExpected(profile: ScreenProfile, gridKey: String): Int? {
        val g = profile.gridGeometryFor(gridKey) ?: return null
        val refRow = minOf(REF_ROW, g.rowYs.size - 1).coerceAtLeast(0)
        if (refRow >= g.rowYs.size) return null
        return profile.scale(g.rowYs[refRow], profile.scaleY)
    }

    /** 逐卡列行亮度剖面（cols × height）。 */
    private fun buildColumnProfiles(frame: Mat, profile: ScreenProfile, g: ScreenProfile.GridGeometry): Array<DoubleArray> {
        val sx = profile.scaleX
        val h = frame.rows()
        val cols = g.cols
        val profiles = Array(cols) { DoubleArray(h) }
        for (c in 0 until cols) {
            val x0 = profile.scale(g.colXs[c], sx).coerceIn(0, frame.cols() - 1)
            val x1 = profile.scale(g.colXs[c] + g.cardW, sx).coerceIn(x0 + 1, frame.cols())
            val m = profiles[c]
            for (y in 0 until h) m[y] = rowMean(frame, y, x0, x1)
        }
        return profiles
    }

    private data class StepResult(val consensus: Int, val validVotes: Int)

    /** 在 [center±win] 内逐列找暗→亮台阶 argmax，跨列众数(2px分箱)/中位兜底聚合。 */
    private fun detectStep(profiles: Array<DoubleArray>, center: Int, win: Int, gate: Int): StepResult {
        val h = profiles[0].size
        val belowN = (STEP_BELOW - STEP_NEAR + 1).toDouble()
        val aboveN = (STEP_ABOVE - STEP_NEAR + 1).toDouble()
        val votes = ArrayList<Int>(profiles.size)
        for (c in profiles.indices) {
            val prof = profiles[c]
            var bestY = -1
            var bestScore = THR - 1e-9
            val lo = (center - win).coerceAtLeast(STEP_ABOVE + 1)
            val hi = (center + win).coerceAtMost(h - 1 - STEP_BELOW)
            for (y in lo..hi) {
                var below = 0.0
                for (k in STEP_NEAR..STEP_BELOW) below += prof[y + k]
                below /= belowN
                var above = 0.0
                for (k in STEP_NEAR..STEP_ABOVE) above += prof[y - k]
                above /= aboveN
                val score = below - above
                if (score > bestScore) {
                    bestScore = score
                    bestY = y
                }
            }
            if (bestY >= 0) votes.add(bestY)
        }
        if (votes.size < gate) return StepResult(0, votes.size)
        val median = votes.sorted()[votes.size / 2]
        val mode = modeBin(votes, 2) ?: median
        return StepResult(mode, votes.size)
    }

    /** 众数(2px分箱)：最大票箱的中心值（箱内中位）；平票取首个最大箱；空→null（由 votes 门把关）。 */
    private fun modeBin(votes: List<Int>, bin: Int): Int? {
        if (votes.isEmpty()) return null
        val counts = HashMap<Int, Int>()
        val members = HashMap<Int, MutableList<Int>>()
        for (v in votes) {
            val b = (v / bin) * bin
            counts[b] = (counts[b] ?: 0) + 1
            members.getOrPut(b) { ArrayList() }.add(v)
        }
        val maxCount = counts.values.maxOrNull()!!
        val bestBin = counts.entries.first { it.value == maxCount }.key
        val grp = members[bestBin]!!
        return grp.sorted()[grp.size / 2]
    }

    /** 行均值（BGR 三通道均值），整行 Mat.get 读一次。 */
    private fun rowMean(frame: Mat, y: Int, x0: Int, x1: Int): Double {
        val width = x1 - x0
        val buf = ByteArray(width * frame.channels())
        frame.get(y, x0, buf)
        var sum = 0L
        for (i in buf.indices) sum += buf[i].toLong() and 0xFF
        return sum.toDouble() / buf.size
    }

    // ================= #169 逐行锚定的文本行裁剪（与 rowPhase 同为"绝对行相位"家族）=================

    /** [crop] = 实际送 OCR 的框（帧坐标）；[phase] = 实测字心 − 名义字心（帧 px，正=内容偏下）。 */
    data class TextLineCrop(val crop: IntRect, val phase: Int)

    /**
     * 名义行带 → **实测文本行**的紧裁剪（+该行的绝对相位）。
     *
     * 用于 rec-only 槽位（没有 det 帮忙找行）且**名义行带远高于一行字**的网格。2244 筛选面板实测：
     * 行带 90px 里只有 [23,49] 那 27px 是字、且**贴带顶** ⇒ 列表落点 δ 一出 `[−27,+41]` 就把整页
     * 切成"半截字 + 大片空白"，压到 rec 的 48px 工作高后全是**确定性乱码**（同页两次读数一字不差；
     * 当初误判成"灰化低对比度 / 词典缺口"，#169 复查推翻）。离线复算（`_dev/probe_169_anchor.py`
     * 把真截图整体平移模拟落点）：δ=−30 时名义带 16 行只对 1 行、±40~50 全页归零。
     *
     * 为什么必须带 profile 锚 [anchor]：锚给出「这张卡的字在行带里的位置」，而**读数与点击由同一个
     * 相位驱动**（`clickY = rowCenterY + phase`，见 ScanJudgmentDomain.matchFilterRow）⇒ 量到相邻那张
     * 卡也不算错，两件事自洽即可。真正会出错的是"回退用名义行带读数、却按名义行中心点"：δ=−53 时
     * 名义带里只剩上一张卡的下半截字，点下去却是本行中心 ⇒ 读 B 点 A（静默选错套装）。
     * 与 [rowPhase] 同义：`labelAnchor` = 行顶 → 行内特征中心；背包那份的特征是等级标签亮带，
     * 本函数的名字叫法是套名文本行。
     *
     * 返回 null 的情形（未标定锚 / 窗内没有墨迹带 / 对比度不过门 / 两条候选离锚几乎一样近 /
     * 窗内只剩横条，见 [isBarShape]）—— 调用方**不许**拿名义行带凑数：这行不读，也不点。
     *
     * ⚠️ 真机 2026-10-02 三轮 2244 探针各暴露一条，全在尾页：
     * ① **行卡片的上边框线也是一条墨迹带**，且它比文字更靠锚（实测 f58：文字 ph=+31、边框 ph=−19）
     *    ⇒ 单看"离锚最近"会裁到一条线、rec 读空（表现为"整页空 13 行"）。
     * ② 横条还会**污染阈值本身**：它把 峰 抬到 200+、中位 抬到 100+，而尾页有些行的字 p95 只有 140
     *    ⇒ 起带阈 144~171 直接把那些行判成"没墨"（单遍写法即使认出了横条也救不回来，必须**摘掉横条
     *    重算阈**）。摘掉后 2244 f60 的四行 `谐律异想断章/沙上楼阁史话/回声之林夜话/来歆余响` 全部读正。
     * 线与字在**横向连续性**上完全可分：文字带最长上墨段 ≤0.13×框宽（≈一个字宽），横条 0.30~1.00；
     * 覆盖率不可分（同一根线被复选框打断时 cov 掉到 0.45~0.81，而 7 字密名 cov 高达 0.86）。
     * ③ **选取窗必须放满半个行距**：尾页实测 δ=−53（滑到底停不住，整页停在格点之外），±45 窗
     *    8 帧 × 16 格**一个都没量到**、半行距窗量到 96 个（`_dev/probe_169_tailpage.py --score`）。
     *
     * @param bandHeight 名义行带高（profile `rowHeight`）
     * @param pitch 行距（`rowPitch`）：决定选取窗（±半行距）与剖面窗（再外扩 1/4 行带）
     * @param anchor 名义行顶 → 行内特征中心（profile `labelAnchor`，负=未标定）
     */
    fun textLineCrop(
        src: Mat,
        x0: Int,
        width: Int,
        yTop: Int,
        bandHeight: Int,
        pitch: Int,
        anchor: Int,
    ): TextLineCrop? {
        if (src.empty() || anchor < 0 || width <= 0 || bandHeight <= 0 || pitch <= 0) return null
        val xFrom = x0.coerceIn(0, src.cols() - 1)
        val xTo = (x0 + width).coerceIn(xFrom + 1, src.cols())
        val center = yTop + anchor
        // 相位**同时**加到读数与点击（见 ScanJudgmentDomain.matchFilterRow：clickY = rowCenterY + phase），
        // 所以"量到哪张卡"不自洽才是错，量到**相邻**卡本身不错 —— 选取范围因此放满半个行距（±pitch/2），
        // 而剖面窗再外扩 1/4 行带：|δ|→半行距 时字带会贴到窗沿被截半，截半的图压到 48px 就是乱码。
        // 真机 2244 尾页实测 δ=−53（列表滑到底停不住整页）：±45 窗 **0/128 格**认得出，
        // 半行距窗 96/128（名义行带只有 16，且读的是**上一张卡被裁掉的下半截**）。
        val half = pitch / 2
        val slack = half + bandHeight / 4
        val yFrom = (center - slack).coerceAtLeast(0)
        val yTo = (center + slack).coerceAtMost(src.rows())
        if (yTo <= yFrom + 1) return null

        val prof = textRowProfile(src, xFrom, xTo, yFrom, yTo)
        val thr0 = inkThreshold(prof) ?: return null
        val minRun = maxOf(4, bandHeight / 16)
        // 上限：字带实测 ≈ 0.37×行带；放到半行带仍宽裕，再大就是"整窗皆墨"（高亮行/底色）而非一行字
        val maxRun = maxOf(minRun, minOf(bandHeight / 2, pitch / 2 - 1))
        // 第一遍**放开宽度门**，只为认出横条并把它们的行从阈值统计里摘掉：横条把 峰 抬到 200+、
        // 中位 抬到 100+，尾页那些"字比线暗"的行（字 p95≈140）就永远进不了起带阈 ⇒ 整行读空。
        // 真机 2244 f60 实测：y=769/871 四行就是这么丢的（单遍写法认得出横条、但阈已经被它污染）。
        val bar = BooleanArray(prof.size)
        for (run in inkRuns(prof, 1, prof.size, thr0)) {
            if (isBarShape(bandShape(src, xFrom, xTo, yFrom + run.first, yFrom + run.last + 1, thr0))) {
                for (i in run.first..run.last) bar[i] = true
            }
        }
        val clean = DoubleArray(prof.size - bar.count { it })
        var k = 0
        for (i in prof.indices) if (!bar[i]) clean[k++] = prof[i]
        val thr = inkThreshold(clean) ?: return null
        val near = inkRuns(prof, minRun, maxRun, thr)
            .filterNot { isBarShape(bandShape(src, xFrom, xTo, yFrom + it.first, yFrom + it.last + 1, thr)) }
            .map { it to yFrom + (it.first + it.last) / 2 - center }
            .filter { Math.abs(it.second) <= half }
        if (near.isEmpty()) return null
        val dists = near.map { Math.abs(it.second) }.sorted()
        // 行格不规则时（2560 的 rowYTop 步长 133 vs 实测 136）同一窗里会留下两条都进得了 ±pitch/2 的带；
        // 差距不到 TEXT_AMBIG_MIN 就判"这行没量准"（调用方不许拿名义带凑数，见该处注释）。
        if (dists.size >= 2 && dists[1] - dists[0] < TEXT_AMBIG_MIN) return null
        val (run, phase) = near.minByOrNull { Math.abs(it.second) } ?: return null
        val pad = maxOf(2, bandHeight / 16)
        val top = (yFrom + run.first - pad).coerceAtLeast(0)
        val bottom = (yFrom + run.last + 1 + pad).coerceAtMost(src.rows())
        if (bottom - top < minRun) return null
        return TextLineCrop(IntRect(xFrom, top, xTo - xFrom, bottom - top), phase)
    }

    /**
     * 纯函数：剖面的起带阈（自适应于该窗自身 ⇒ 不随分辨率/整体明暗漂移）。
     * 对比度不过门（空带/纯噪声）⇒ null，调用方按"没量到"处理。
     * [inkRuns] 与 [bandShape] **必须共用本值** —— 两处阈值不同源就会一处认为有带、另一处认为没墨。
     */
    internal fun inkThreshold(prof: DoubleArray): Double? {
        if (prof.isEmpty()) return null
        val med = prof.sortedArray()[prof.size / 2]
        val mx = prof.max()
        if (mx - med < TEXT_CONTRAST_MIN) return null
        return med + (mx - med) * TEXT_HI_NUM / TEXT_HI_DEN
    }

    /**
     * 纯函数：这条带像不像"一行字"。判据是**横向连续性**：横条（行卡片分隔线/边框/高亮块）
     * 有一整段连续上墨的列，文字带最长一段只到一个字宽。实测见 [TEXT_BAR_LONGEST] 处。
     */
    internal fun isBarShape(longestRunRatio: Double): Boolean = longestRunRatio >= TEXT_BAR_LONGEST

    /** 一条候选带的横向结构：最长一段连续上墨列 / 窗宽。阈值与 [inkRuns] 同一个。 */
    private fun bandShape(src: Mat, xFrom: Int, xTo: Int, yTop: Int, yBot: Int, thr: Double): Double {
        val width = xTo - xFrom
        val ch = src.channels()
        val buf = ByteArray(width * ch)
        val inked = BooleanArray(width)
        for (y in yTop until yBot) {
            src.get(y, xFrom, buf)
            for (p in 0 until width) {
                if (inked[p]) continue
                val o = p * ch
                var v = buf[o].toInt() and 0xFF
                for (c in 1 until ch) {
                    val b = buf[o + c].toInt() and 0xFF
                    if (b > v) v = b
                }
                if (v >= thr) inked[p] = true
            }
        }
        var longest = 0
        var cur = 0
        for (p in 0 until width) {
            if (inked[p]) {
                cur++
                if (cur > longest) longest = cur
            } else {
                cur = 0
            }
        }
        return longest.toDouble() / width
    }

    /** 逐行亮度剖面（每行一个 0..255 标量 = 该行像素亮度 95 分位；亮度取 BGR 通道最大值）。 */
    private fun textRowProfile(src: Mat, xFrom: Int, xTo: Int, yFrom: Int, yTo: Int): DoubleArray {
        val n = yTo - yFrom
        val ch = src.channels()
        val width = xTo - xFrom
        val buf = ByteArray(width * ch)
        val hist = IntArray(256)
        return DoubleArray(n) { i ->
            src.get(yFrom + i, xFrom, buf)
            hist.fill(0)
            for (p in 0 until width) {
                val o = p * ch
                var v = buf[o].toInt() and 0xFF
                for (c in 1 until ch) {
                    val b = buf[o + c].toInt() and 0xFF
                    if (b > v) v = b
                }
                hist[v]++
            }
            quantileFromHistogram(hist, width, TEXT_QUANTILE)
        }
    }

    /** 由直方图取分位数：最小的 q 使「≤q 的样本数 ≥ ratio·n」（与 numpy `percentile` 线性插值差 <1）。 */
    private fun quantileFromHistogram(hist: IntArray, n: Int, ratio: Double): Double {
        val target = Math.max(1, Math.round(n * ratio))
        var acc = 0
        for (v in hist.indices) {
            acc += hist[v]
            if (acc >= target) return v.toDouble()
        }
        return (hist.size - 1).toDouble()
    }

    /**
     * 纯函数：剖面 → 墨迹带（**闭**区间，索引相对 [prof] 起点），阈 [thr] 由 [inkThreshold] 给。
     * 宽度不在 `[minRun, maxRun]` 的碎片丢弃；"是不是横条"由调用方按 [isBarShape] 再筛。
     */
    internal fun inkRuns(prof: DoubleArray, minRun: Int, maxRun: Int, thr: Double): List<IntRange> {
        if (prof.isEmpty() || maxRun < minRun) return emptyList()
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < prof.size) {
            if (prof[i] >= thr) {
                var j = i
                while (j + 1 < prof.size && prof[j + 1] >= thr) j++
                if (j - i + 1 in minRun..maxRun) out.add(i..j)
                i = j + 1
            } else {
                i++
            }
        }
        return out
    }
}
