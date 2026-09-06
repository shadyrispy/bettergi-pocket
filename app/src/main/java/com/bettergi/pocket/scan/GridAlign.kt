package com.bettergi.pocket.scan

import org.opencv.core.Mat

/**
 * §12.2 翻页距离自适应（落地：逐列横向台阶 + 跨列众数 + 中段行 + p0 基准）。
 *
 * 检测器（B1，bench `swipe_edge_mode_benchmark.md` / 录屏 `swipe_recording_edge_validation.md` 定稿）：
 *   逐卡列取行亮度剖面，找「暗→亮」横向台阶
 *   （below=mean(y+2..y+8)、above=mean(y−8..y−2)、score=below−above），窗 ±WIN 取 argmax；
 *   单列 score<THR 剔除。跨列聚合：众数(2px分箱)为主、中位兜底。
 *   基准行改中段第2行（REF_ROW）以避开首行滑出顶边；并用「第3行−第2行≈行距」做周期自检守卫。
 *
 * 控制律：nextDistance = clamp(dist + drift, 584, 1168)
 *   drift = detected − p0（p0 = 顶对齐首帧检测基准；特征偏移 k 被抵消，与检测落在卡顶/星星无关）。
 *   未建 p0 时 drift = detected − expected（退化成旧行为，k 留在误差里，稳态仍收敛只是落点偏 k）。
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
    private const val THR = 10         // 单列台阶最小得分（对比阈值，不缩放）
    private const val MIN_VOTES = 6     // 共识门：有效列 ≥6/7（单列软边噪声由众数吸收）
    private const val PERIOD_TOL = 40  // 周期自检容差（|row3−row2−pitch|，帧坐标缩放）
    private const val REF_ROW = 2      // 检测基准行（中段第2行，避开顶边滑出）
    private const val LIMIT = 146      // 半 pitch 限幅（漂移）
    private const val MIN_DIST = 584   // 2 行
    private const val MAX_DIST = 1168  // 4 行

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

    fun nextDistance(
        frame: Mat,
        profile: ScreenProfile,
        gridKey: String,
        currentDist: Int,
        lastErr: Int? = null,
    ): Int {
        val drift = measureError(frame, profile, gridKey) ?: return currentDist
        // 连续性判据：与上一页 drift 相差超半行距 → 相位混叠（列表漂移超搜索窗，锁到邻行），拒绝采用
        if (lastErr != null && Math.abs(drift - lastErr) > rowPitch(profile, gridKey) / 2) return currentDist
        val sy = profile.scaleY
        return (currentDist + drift).coerceIn(profile.scale(MIN_DIST, sy), profile.scale(MAX_DIST, sy))
    }

    /** 行距（帧坐标），连续性判据阈值基准。 */
    fun rowPitch(profile: ScreenProfile, gridKey: String): Int {
        val g = profile.gridGeometryFor(gridKey) ?: return 0
        return profile.scale(g.rowPitch.toInt(), profile.scaleY)
    }

    /**
     * §12.2 翻页补滑用：基准行特征绝对位置（帧坐标）。
     * 与 [measureError] 的区别：不做 p0 参考换算、不做 ±146 限幅（相邻帧差分 + mod 行距求单页残差），
     * 但保留共识门/周期自检守卫。网格无几何或守卫不通过→null。
     */
    fun measureDetected(frame: Mat, profile: ScreenProfile, gridKey: String): Int? =
        detectRow(frame, profile, gridKey)

    /**
     * §12.5 相位偏移：当前帧网格相位相对 p0 基准（顶对齐首帧）的**累计漂移**（帧坐标，
     * 归一到 [−pitch/2, pitch/2)；正=内容偏下=累计少滚，负=偏上=多滚）。
     * 网格特征沿行距周期重复 → ±整行的错位被 mod 吸收，残差只反映半行距内的真实偏移；
     * 单页误差超过 ±半行距（146）时符号混叠——调用方须保证 |φ| 不逼近该边界（超卡片半高即补滑）。
     * 测量必须用**未偏移**的原始 profile（固定参考）；offset 视图会使 expected 随之平移。
     * @return null = 未建 p0 / 检测守卫不通过（共识门/周期自检），调用方沿用上一页偏移。
     */
    fun phaseOffset(frame: Mat, profile: ScreenProfile, gridKey: String): Int? {
        val det = detectRow(frame, profile, gridKey) ?: return null
        val p0 = baselines[gridKey] ?: return null
        return centeredMod(det - p0, rowPitch(profile, gridKey))
    }

    /** 单页残差：x 归一到 [−pitch/2, pitch/2)（正=少滚，负=多滚）。 */
    fun centeredMod(x: Int, pitch: Int): Int {
        if (pitch <= 0) return 0
        val m = ((x % pitch) + pitch) % pitch
        return if (m > pitch / 2) m - pitch else m
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
        if (base.validVotes < gate) return null

        // 周期自检：第3行（基准行 + 行距）应同样检出且间距≈行距（拒特征翻转/翻票）
        if (g.rowYs.size > REF_ROW + 1) {
            val third = detectStep(colProfiles, expected + pitch, win, gate)
            if (third.validVotes < gate) return null
            if (Math.abs(third.consensus - base.consensus - pitch) > profile.scale(PERIOD_TOL, sy)) return null
        }
        return base.consensus
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
}
