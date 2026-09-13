package com.bettergi.pocket.scan

import android.util.Log
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * 像素投票判据（业务原语 #4 vote 的执行体）。
 *
 * 色域/阈值固化自 dsl/json/profiles.json zones 的 judge 定义（judge 文本为语义说明，
 * 机读参数 rect/rel/points/阈值取自结构化字段；色域 RGB 谓词为代码内常量——P3 再做表达式化）。
 * 帧 Mat 为 BGR；profiles 色域按 RGB 描述，统一在谓词内换算。
 */
object VoteJudges {

    /** RGB 通道谓词（采样像素 RGB 全部命中闭区间才算匹配；可选跨通道约束 r-b>delta）。 */
    data class RgbPredicate(
        val rMin: Int, val rMax: Int,
        val gMin: Int, val gMax: Int,
        val bMin: Int, val bMax: Int,
        val requireRB: Int = 0,  // 0=无跨通道约束；>0=R-B > requireRB
    ) {
        fun matches(r: Int, g: Int, b: Int): Boolean =
            r in rMin..rMax && g in gMin..gMax && b in bMin..bMax &&
                (requireRB == 0 || r - b > requireRB)
    }

    // ---- 色域常量（profiles.json zones judge 逐字固化）----
    /** 金掩码（圣遗物星/锁/收藏共用）：R>170, G 120-220, B<150 */
    val GOLD = RgbPredicate(171, 255, 120, 220, 0, 149)

    /** 武器金条（weapon.card.starStrip 略宽域，R>170 G 130-255 B<110 R-B>70） */
    val GOLD_STRIP = RgbPredicate(171, 255, 130, 255, 0, 109, requireRB = 70)

    /** 粉色锁徽（artifact.card.lockBadge）：R>200, G 120-215, B 110-215, R-B>30 */
    val PINK_LOCK = RgbPredicate(201, 255, 120, 215, 110, 215)

    /** 祝圣紫横幅（三采样点 5x5 紫占比>0.6；紫≈R/B 高 G 低，实测带内取宽域） */
    val PURPLE_BANNER = RgbPredicate(120, 220, 60, 140, 200, 255)

    object Thresholds {
        const val CARD_LOCK_PINK = 60      // pink>60=已锁
        const val PANEL_LOCK_GOLD = 40     // gold>=40=已锁
        const val PANEL_LOCK_DARK = 400    // 未锁兜底：gold==0 且 dark>400
        const val ASTRAL_GOLD = 400        // gold>=400=已收藏
        const val BANNER_PURPLE_RATIO = 0.6
        const val BANNER_POINTS_REQUIRED = 2 // ≥2/3 点
    }

    /** vote 结果：matched + 命中像素计数（诊断用）。 */
    data class Result(val matched: Boolean, val count: Int)

    /**
     * 统计 rect 内命中谓词的像素数。frame 为 BGR Mat，rect 已是帧坐标。
     * ⚠️ Mat.get(row, col, byte[]) 是「单像素」重载——必须逐行 get(row, byte[]) 取整行再切片，
     * 否则整块缓冲只有首像素有效（真机上判据会近乎恒 0）。
     */
    fun countMatches(frame: Mat, rect: FrameRect, predicate: RgbPredicate): Int {
        val x0 = rect.left.coerceIn(0, frame.cols() - 1)
        val y0 = rect.top.coerceIn(0, frame.rows() - 1)
        val x1 = rect.right.coerceIn(0, frame.cols())
        val y1 = rect.bottom.coerceIn(0, frame.rows())
        if (x1 <= x0 || y1 <= y0) return 0
        val px = ByteArray(3)
        var count = 0
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                frame.get(y, x, px)
                val b = px[0].toInt() and 0xFF
                val g = px[1].toInt() and 0xFF
                val r = px[2].toInt() and 0xFF
                if (predicate.matches(r, g, b)) count++
            }
        }
        return count
    }

    /** 网格卡内锁徽 vote（zone artifact.card.lockBadge，rel 随卡平移）。 */
    fun cardLockBadge(frame: Mat, profile: ScreenProfile, gridKey: String, col: Int, row: Int): Result {
        val rel = intArrayOf(8, 6, 48, 46) // profiles zones artifact.card.lockBadge.rel
        val rect = profile.cardRelRect(gridKey, rel, col, row)
        val count = countMatches(frame, rect, PINK_LOCK)
        return Result(count > Thresholds.CARD_LOCK_PINK, count)
    }

    /**
     * 武器金条 vote：zone rel/divisor 优先读 profiles，缺省回退 3200x1440 base（[30,170,170,212], 555）。
     * 2244x1080 等 native profile 必须提供 device-native rel，否则硬编码 rel 会落出卡外。
     * counts 1/3/5★ 样本 ~561/1600/2700 像素（profiles.sample.samples 字段，未在代码内固化）。
     */
    fun weaponStarStrip(frame: Mat, profile: ScreenProfile, gridKey: String, col: Int, row: Int): Result {
        val zone = profile.zone("weapon.card.starStrip")
        val rel = if (zone != null && zone.has("rel")) {
            val arr = zone.getJSONArray("rel")
            intArrayOf(arr.getInt(0), arr.getInt(1), arr.getInt(2), arr.getInt(3))
        } else {
            intArrayOf(30, 170, 170, 212)
        }
        val divisor = zone?.optDouble("divisor", 555.0) ?: 555.0
        val rect = profile.cardRelRect(gridKey, rel, col, row)
        val count = countMatches(frame, rect, GOLD_STRIP)
        val stars = Math.round(count / divisor).coerceIn(0, 5).toInt()
        Log.d("BetterGI.Vote", "weaponStarStrip[$gridKey]($col,$row): count=$count divisor=$divisor stars=$stars")
        return Result(stars > 0, stars)
    }

    /**
     * 网格卡内锁徽 vote（参数化 zoneKey：artifact.card.lockBadge / weapon.card.lockBadge）——从 profile zone 读 rel。
     */
    fun cardLockBadgeByZone(frame: Mat, profile: ScreenProfile, gridKey: String, zoneKey: String, col: Int, row: Int): Result {
        val zone = profile.zone(zoneKey) ?: return Result(false, 0)
        val rel = zone.getJSONArray("rel")
        val rect = profile.cardRelRect(
            gridKey,
            intArrayOf(rel.getInt(0), rel.getInt(1), rel.getInt(2), rel.getInt(3)),
            col, row,
        )
        val count = countMatches(frame, rect, PINK_LOCK)
        return Result(count > Thresholds.CARD_LOCK_PINK, count)
    }

    /** 面板锁定 chip vote（zone artifact.panel.lock，[yShiftFrame] 为祝圣帧坐标下移量）。 */
    fun panelLock(frame: Mat, profile: ScreenProfile, yShiftFrame: Int = 0): Result {
        val obj = profile.zone("artifact.panel.lock") ?: return Result(false, 0)
        val r = obj.getJSONArray("rect")
        val rect = profile.scaleRect(r.getInt(0), r.getInt(1), r.getInt(2), r.getInt(3))
        val gold = countMatches(frame, rect.shiftedBy(yShiftFrame), GOLD)
        if (gold >= Thresholds.PANEL_LOCK_GOLD) return Result(true, gold)
        // 未锁判定：gold==0 且暗像素>400（深灰图形）——简化为 gold 低即未锁，dark 兜底 P1-b 补
        return Result(false, gold)
    }

    /** 面板收藏 chip vote（zone artifact.panel.astral）。 */
    fun panelAstral(frame: Mat, profile: ScreenProfile, yShiftFrame: Int = 0): Result {
        val obj = profile.zone("artifact.panel.astral") ?: return Result(false, 0)
        val r = obj.getJSONArray("rect")
        val rect = profile.scaleRect(r.getInt(0), r.getInt(1), r.getInt(2), r.getInt(3))
        val gold = countMatches(frame, rect.shiftedBy(yShiftFrame), GOLD)
        return Result(gold >= Thresholds.ASTRAL_GOLD, gold)
    }

    /** 祝圣横幅 vote（zone artifact.panel.zhusheng，三采样点 5x5 紫占比≥2/3）。 */
    fun panelZhusheng(frame: Mat, profile: ScreenProfile): Result {
        val obj = profile.zone("artifact.panel.zhusheng") ?: return Result(false, 0)
        val points = obj.getJSONArray("points")
        var hits = 0
        for (i in 0 until points.length()) {
            val p = points.getJSONArray(i)
            val cx = profile.scale(p.getInt(0), profile.scaleX)
            val cy = profile.scale(p.getInt(1), profile.scaleY)
            val rect = FrameRect(cx - 2, cy - 2, cx + 3, cy + 3)
            val area = 25
            val purple = countMatches(frame, rect, PURPLE_BANNER)
            if (purple.toDouble() / area > Thresholds.BANNER_PURPLE_RATIO) hits++
        }
        return Result(hits >= Thresholds.BANNER_POINTS_REQUIRED, hits)
    }

    /**
     * 稀有度分类（zone artifact.rarity，banner 色分类）。
     * @return 3/4/5，未知返回 -1（交由星带交叉校验，P1-b）
     */
    fun rarityFromBanner(frame: Mat, profile: ScreenProfile): Int {
        val obj = profile.zone("artifact.rarity") ?: return -1
        val arr = obj.optJSONArray("banner") ?: return -1
        val rect = profile.scaleRect(arr.getInt(0), arr.getInt(1), arr.getInt(2), arr.getInt(3))
        val x0 = rect.left.coerceIn(0, frame.cols() - 1)
        val y0 = rect.top.coerceIn(0, frame.rows() - 1)
        val x1 = rect.right.coerceIn(0, frame.cols())
        val y1 = rect.bottom.coerceIn(0, frame.rows())
        if (x1 <= x0 || y1 <= y0) return -1
        val rowBuf = ByteArray(3)
        var sumR = 0L; var sumG = 0L; var sumB = 0L
        var n = 0L
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                frame.get(y, x, rowBuf)
                sumB += rowBuf[0].toInt() and 0xFF
                sumG += rowBuf[1].toInt() and 0xFF
                sumR += rowBuf[2].toInt() and 0xFF
                n++
            }
        }
        if (n == 0L) return -1
        val r = (sumR / n).toInt(); val g = (sumG / n).toInt(); val b = (sumB / n).toInt()
        return when {
            b > r + 40 && b > 180 && r < 130 -> 3
            b > 180 && r > 130 -> 4
            r > b + 80 -> 5
            else -> -1
        }
    }

    /**
     * 网格区 ROI（裁剪到帧内）。两种几何写法统一（cardOrigin+pitch 与 colX/rowY）。
     * 非法（缺字段/越界）返回 null。
     */
    private fun gridRoiBounds(frame: Mat, profile: ScreenProfile, gridKey: String): IntArray? {
        val bounds = profile.gridBounds(gridKey)
        val x0: Int
        val y0: Int
        val x1: Int
        val y1: Int
        if (bounds != null) {
            x0 = bounds.left
            y0 = bounds.top
            x1 = bounds.right
            y1 = bounds.bottom
        } else {
            val grid = profile.rawObject("grids.$gridKey") ?: return null
            val origin = grid.getJSONArray("cardOrigin")
            val pitch = grid.getJSONArray("pitch")
            val cols = grid.getInt("cols")
            val rows = grid.getInt("visibleRows")
            val size = grid.getJSONArray("cardSize")
            x0 = profile.scale(origin.getInt(0), profile.scaleX)
            y0 = profile.scale(origin.getInt(1), profile.scaleY)
            x1 = profile.scale(
                origin.getInt(0) + (cols - 1) * pitch.getInt(0) + size.getInt(0),
                profile.scaleX,
            )
            y1 = profile.scale(
                origin.getInt(1) + (rows - 1) * pitch.getInt(1) + size.getInt(1),
                profile.scaleY,
            )
        }
        val fx0 = x0.coerceIn(0, frame.cols())
        val fy0 = y0.coerceIn(0, frame.rows())
        val fx1 = x1.coerceIn(fx0, frame.cols())
        val fy1 = y1.coerceIn(fy0, frame.rows())
        if (fx1 <= fx0 || fy1 <= fy0) return null
        return intArrayOf(fx0, fy0, fx1, fy1)
    }

    /**
     * 网格区缩略图（24×16，每像素 RGB 各 4-bit 量化存一字节，拷贝返回）。
     * 用于帧间差异判稳/到底——比精确哈希相等更容忍真机逐帧抖动与微动画。
     * 缩略图经 INTER_AREA 均值，抑制角色 portrait idle 等高频噪声，保留翻页 layout 变化。
     * 非法返回 null。
     */
    fun gridThumb(frame: Mat, profile: ScreenProfile, gridKey: String): ByteArray? {
        val roi = gridRoiBounds(frame, profile, gridKey) ?: return null
        val sub = frame.submat(roi[1], roi[3], roi[0], roi[2])
        val thumb = Mat()
        Imgproc.resize(sub, thumb, Size(24.0, 16.0), 0.0, 0.0, Imgproc.INTER_AREA)
        sub.release()
        val out = ByteArray(thumb.rows() * thumb.cols() * 3)
        val buf = ByteArray(3)
        var i = 0
        for (y in 0 until thumb.rows()) {
            for (x in 0 until thumb.cols()) {
                thumb.get(y, x, buf)
                out[i++] = ((buf[2].toInt() and 0xFF) shr 4).toByte() // R
                out[i++] = ((buf[1].toInt() and 0xFF) shr 4).toByte() // G
                out[i++] = ((buf[0].toInt() and 0xFF) shr 4).toByte() // B
            }
        }
        thumb.release()
        return out
    }

/**
     * 命座节点读数（**GOODScanner 移植**，见 profile `screens.char_constellation`）。
     *
     * 原理（上游 `refs/GOODScanner`：`pixel_utils.rs::sample_constellation_brightness` +
     * `constants.rs::CONSTELLATION_NODES`）：命座页 6 个节点沿一条 **S 形**排布，各节点取**环带**
     * （`ringInner..ringOuter`，**刻意避开图标中心** —— 中心在激活/锁定两态亮度相近）的平均亮度：
     * **锁定态 = 暗圆底 + 亮描边 + 白锁图标 ⇒ ring−center 大**；**激活态 = 技能图 + 辉光 ⇒ 小**。
     *
     * 本实现输出「环均值 / 中心均值 / 两者差」，并据此给两个结论：
     * ① [isConstellationPage]：**`lockedCount ≥ pageMinLocked` ⇒ 当前在命座页**
     *   —— 实测（2244，5 个角色）命座页命中 4~6 个，而**属性页/天赋页同坐标 0 个**（分离干净）。
     * ② [activeCount]：命座等级（激活节点数）—— ⚠️ **当前仍差 1**（阿罗夏 C4 读成 2），
     *   属节点中心/环半径未逐节点精修，**不要直接当数据用**，先看日志里的逐节点值。
     */
    data class ConstellationRead(
        /** 每节点 (ring 均值, center 均值, ring−center)。 */
        val nodes: List<Triple<Double, Double, Double>>,
        /** ring−center ≥ lockedContrastMin 的节点数。 */
        val lockedCount: Int,
        /** 激活节点数（= 6 − 锁定数）。⚠️ 当前精度不足，见类注释。 */
        val activeCount: Int,
        /** 锁定节点是否构成**后缀**（激活恒在锁定之前；非后缀 ⇒ 多半读数出错/不在该页）。 */
        val suffixPattern: Boolean,
        /** 是否认定为「在命座页」。 */
        val isConstellationPage: Boolean,
    ) {
        fun brief(): String = nodes.mapIndexed { i, n -> "C${i + 1}=%.0f/%+.0f".format(n.first, n.third) }
            .joinToString(" ")
    }

    /** 读命座页 6 节点（见 [ConstellationRead]）。帧不足/路径缺失返回 null。 */
    fun constellationNodes(frame: Mat, profile: ScreenProfile): ConstellationRead? {
        val obj = profile.rawObject("screens.char_constellation") ?: return null
        val nodesArr = obj.optJSONArray("nodes") ?: return null
        val sx = profile.scaleX
        val sy = profile.scaleY
        val rIn = obj.optInt("ringInner", 15) * sx
        val rOut = obj.optInt("ringOuter", 26) * sx
        val cMin = obj.optDouble("lockedContrastMin", 45.0)
        val pageMin = obj.optInt("pageMinLocked", 3)
        val gray = Mat()
        try {
            if (frame.channels() > 1) {
                Imgproc.cvtColor(frame, gray, Imgproc.COLOR_BGR2GRAY)
            } else {
                frame.copyTo(gray)
            }
            val out = ArrayList<Triple<Double, Double, Double>>(nodesArr.length())
            var locked = 0
            var seenLocked = false
            var suffix = true
            for (i in 0 until nodesArr.length()) {
                val p = nodesArr.getJSONArray(i)
                val cx = profile.scale(p.getInt(0), sx).toDouble()
                val cy = profile.scale(p.getInt(1), sy).toDouble()
                val (ring, center) = ringMeans(gray, cx, cy, rIn, rOut)
                val contrast = ring - center
                out += Triple(ring, center, contrast)
                if (contrast >= cMin) {
                    locked++
                    seenLocked = true
                } else if (seenLocked) {
                    suffix = false
                }
            }
            return ConstellationRead(
                nodes = out,
                lockedCount = locked,
                activeCount = nodesArr.length() - locked,
                suffixPattern = suffix,
                isConstellationPage = locked >= pageMin,
            )
        } finally {
            gray.release()
        }
    }

    /**
     * 以 (cx,cy) 为心的两点均值：[环带 r∈[rIn,rOut] 的平均亮度, 中心区 r ≤ rIn/2 的平均亮度]。
     * 逐像素采样（面积仅 ~1400px，代价可忽略）。
     */
    private fun ringMeans(gray: Mat, cx: Double, cy: Double, rIn: Double, rOut: Double): Pair<Double, Double> {
        val ri2 = rIn * rIn
        val ro2 = rOut * rOut
        val rc2 = (rIn * 0.5) * (rIn * 0.5)
        val x0 = Math.max(0, Math.floor(cx - rOut).toInt())
        val x1 = Math.min(gray.cols() - 1, Math.ceil(cx + rOut).toInt())
        val y0 = Math.max(0, Math.floor(cy - rOut).toInt())
        val y1 = Math.min(gray.rows() - 1, Math.ceil(cy + rOut).toInt())
        var ringSum = 0.0
        var ringN = 0
        var cenSum = 0.0
        var cenN = 0
        val buf = ByteArray(1)
        for (y in y0..y1) {
            val dy = y - cy
            for (x in x0..x1) {
                val dx = x - cx
                val d2 = dx * dx + dy * dy
                val isRing = d2 >= ri2 && d2 <= ro2
                val isCen = d2 <= rc2
                if (!isRing && !isCen) continue
                gray.get(y, x, buf)
                val v = (buf[0].toInt() and 0xFF).toDouble()
                if (isRing) {
                    ringSum += v
                    ringN++
                } else {
                    cenSum += v
                    cenN++
                }
            }
        }
        return (if (ringN > 0) ringSum / ringN else 0.0) to (if (cenN > 0) cenSum / cenN else 0.0)
    }

    /**
     * 网格区**逐行亮度剖面**
（只读探针，用于实测「翻页落地位移」）。
     *
     * 与 [gridThumb] 取同一 ROI（**不含右侧详情面板** —— 含面板会让互相关曲线全废）；
     * 分辨率 = 1 帧像素/行，配 [profileShift] 做纵向互相关 ⇒ 直接读出"这次滑动实际滚了多少 px"。
     * 这是把「指令距离 → 实际落地」这个开环改成闭环的唯一可信输入。
     */
    fun gridRowProfile(frame: Mat, profile: ScreenProfile, gridKey: String): DoubleArray? {
        val roi = gridRoiBounds(frame, profile, gridKey) ?: return null
        val x0 = roi[0]
        val y0 = roi[1]
        val x1 = roi[2]
        val y1 = roi[3]
        val h = y1 - y0
        val w = x1 - x0
        if (h < 32 || w < 32) return null
        val sub = frame.submat(y0, y1, x0, x1)
        val gray = Mat()
        Imgproc.cvtColor(sub, gray, Imgproc.COLOR_BGR2GRAY)
        val out = DoubleArray(h)
        val buf = ByteArray(w)
        for (row in 0 until h) {
            gray.get(row, 0, buf)
            var s = 0L
            for (i in 0 until w) s += (buf[i].toLong() and 0xFF)
            out[row] = s.toDouble() / w
        }
        gray.release()
        sub.release()
        return out
    }

    /**
     * 两条行剖面之间的**纵向位移**（first = px，**正 = 内容上移 = 列表向前推进**）。
     *
     * 判据：`after[i] ≈ before[i + s]`（内容上移 s 行）⇒ 取相关系数最大的 s。
     * 逐位移窗口内各自去均值（不同位移的重叠窗口不同，全局均值会引入偏置）。
     *
     * ⚠️ [score] < [PROFILE_SHIFT_MIN_SCORE] 时**不可信**（内容没平移 / 被局部变化污染）——
     * 调用方必须据此放弃本次测量，不要拿 dy 去纠偏。实测真实位移的 score ≥ 0.6（多为 0.8+），
     * 而"没动"时最高分只有 0.4 上下 ⇒ 这个门限能把两者分开。
     */
    fun profileShift(before: DoubleArray, after: DoubleArray, expectedPx: Int = 0): Pair<Int, Double> {
        val n = Math.min(before.size, after.size)
        if (n < 32) return 0 to -1.0
        val maxShift = n - 24
        // ★ 2026-09-13 周期歧义消解：卡片行距 204 **周期性** ⇒ s, s±204, s±408… 的互相关分数几乎并列，
        //   而"从 0 向上搜 + 严格大于"会**永远取最小周期候选** ⇒ 落地被少算整行（真值 526 报成 118）
        //   ⇒ 闭环误判"没滚够" ⇒ 每页多补滑 2~4 次（2026-09-13 用户指出，合成回归 ProfileShiftPeriodTest 锁死）。
        //   修法：记下全部分数；调用方给 expectedPx（=本次命令距离）时，在"与最优分差 ≤0.02"的候选里
        //   取**最接近期望**的那个。无先验时保持旧行为（取最小）。
        val scores = DoubleArray(maxShift + 1)
        var bestS = 0
        var bestC = -2.0
        for (s in 0..maxShift) {
            val lim = n - s
            var sa = 0.0
            var sb = 0.0
            for (i in 0 until lim) {
                sa += before[i + s]
                sb += after[i]
            }
            val ma = sa / lim
            val mb = sb / lim
            var dot = 0.0
            var aa = 0.0
            var bb = 0.0
            for (i in 0 until lim) {
                val v = before[i + s] - ma
                val u = after[i] - mb
                dot += v * u
                aa += v * v
                bb += u * u
            }
            val c = dot / (Math.sqrt(aa * bb) + 1e-9)
            scores[s] = c
            if (c > bestC) {
                bestC = c
                bestS = s
            }
        }
        if (expectedPx > 0) {
            val eps = 0.02
            var pick = bestS
            var bestDiff = Int.MAX_VALUE
            for (s in 0..maxShift) {
                if (scores[s] >= bestC - eps) {
                    val d = Math.abs(s - expectedPx)
                    if (d < bestDiff) {
                        bestDiff = d
                        pick = s
                    }
                }
            }
            return pick to scores[pick]
        }
        return bestS to bestC
    }

    /** [profileShift] 的可用门限：低于此值视为"测不到位移"（内容没动/被污染）。 */
    const val PROFILE_SHIFT_MIN_SCORE = 0.5

    /**
     * **2D 模板位移测量**（2026-09-13）：修复 [profileShift] 的**周期歧义**——
     * 卡片行距 204 的周期性 + 7 列平均 ⇒ 1D 行剖面在 s, s±204, s±408… 上分数几乎并列（实测 0.91+），
     * "从 0 向上搜 + 严格大于"会锁到错误周期 ⇒ 落地被多算/少算 1~3 行
     * （实测：同一帧对 1D 与 2D 真值差 = **恰好 ±204 的整数倍**：+3.12/+3.04/+2.98/−2.00/+1.00 行）。
     * 本方法用**卡片图案**做 2D 匹配（图案唯一 ⇒ 无歧义）；网格 ROI 1/4 下采样控制成本（~10ms）。
     * 模板取 before 的**底部带**（y 78%~98%）⇒ 前进 612px 后仍在屏内；搜索 = after 全 ROI。
     * 返回 (位移px, score)；score < 0.6 ⇒ null（不可信，调用方回退 1D）。
     */
    fun gridShift2D(before: Mat, after: Mat, profile: ScreenProfile, gridKey: String): Pair<Int, Double>? {
        val b = gridGrayQuarter(before, profile, gridKey) ?: return null
        val a = gridGrayQuarter(after, profile, gridKey) ?: return null
        try {
            val h = Math.min(b.rows(), a.rows())
            val w = Math.min(b.cols(), a.cols())
            if (h < 40 || w < 40) return null
            val tY0 = (h * 0.78).toInt()
            val tY1 = (h * 0.98).toInt()
            if (tY1 - tY0 < 12) return null
            val tmpl = Mat(b, org.opencv.core.Rect(0, tY0, w, tY1 - tY0))
            val res = Mat()
            Imgproc.matchTemplate(a, tmpl, res, Imgproc.TM_CCOEFF_NORMED)
            val mm = org.opencv.core.Core.minMaxLoc(res)
            res.release()
            if (mm.maxVal < 0.60) return null
            val dyQ = Math.round(tY0 - mm.maxLoc.y).toInt()
            return (dyQ * 4) to mm.maxVal
        } finally {
            b.release()
            a.release()
        }
    }

    /** 网格 ROI 灰度 1/4 图（[gridShift2D] 用；返回值归调用方 release）。 */
    private fun gridGrayQuarter(frame: Mat, profile: ScreenProfile, gridKey: String): Mat? {
        val r = profile.gridBounds(gridKey) ?: return null
        val g = Mat()
        return try {
            if (frame.channels() == 1) frame.copyTo(g)
            else Imgproc.cvtColor(frame, g, Imgproc.COLOR_BGR2GRAY)
            val x = Math.max(0, r.left)
            val y = Math.max(0, r.top)
            val w = Math.min(g.cols() - x, r.right - r.left)
            val h = Math.min(g.rows() - y, r.bottom - r.top)
            if (w < 40 || h < 40) return null
            val crop = Mat(g, org.opencv.core.Rect(x, y, w, h))
            try {
                val out = Mat()
                Imgproc.resize(crop, out, org.opencv.core.Size(0.0, 0.0), 0.25, 0.25, Imgproc.INTER_AREA)
                out
            } finally {
                crop.release()
            }
        } finally {
            g.release()
        }
    }

    /**
     * 网格区指纹（翻页/到底判据）：gridThumb 的 FNV-1a 哈希。
     * ⚠️ 仅作快速相等判据；真机逐帧抖动致精确相等几乎不成立，稳定/到底判定请改用
     * [thumbChangedFraction]（差异比例阈值）。
     */
    fun gridFingerprint(frame: Mat, profile: ScreenProfile, gridKey: String): Long {
        val t = gridThumb(frame, profile, gridKey) ?: return 0L
        var hash = 1469598103934665603L
        for (b in t) hash = (hash xor (b.toLong() and 0xFF)) * 1099511628211L
        return hash
    }

    /**
     * 两帧缩略图差异比例（0..1）：RGB 四比特通道 **差值 > [tol]** 才计为变化像素 / 总像素。
     * 任一为 null 返回 null（调用方按"未知"处理）。
     * 用于自适应 settle 与翻页到底/回顶判据，容忍真机逐帧抖动/微动画——原精确哈希相等在真机
     * 几乎不成立，导致 awaitGridStable 永不收敛（每次打满 SETTLE_MAX_MS）。
     *
     * ⚠️ [tol] 不可为 0（严格不等）。缩略图每像素是 ~28×68 原始块的均值再 4-bit 量化，
     * MediaProjection 的编解码噪声会让块均值在量化边界附近来回跳 **1 个 4-bit 档位**；
     * 严格不等把这些抖动全记成"变化像素"。2026-09-10 掩码实测（char_popup 回顶）：到顶后
     * 掩码是**全图零散单像素**（非整块位移），diff 在 0.065~0.22 之间乱跳，横跨 0.10 阈值 →
     * 同一静止状态时而判顶时而不判（PHASE3 8 次全不判顶的真因）。
     * 取 tol=2（4-bit 档位允许 ±2，即单通道 ±32/255）滤掉量化边界抖动；真实翻页是整块位移，
     * 块均值移动远超 2 档（实测 diff 0.72~1.00），不受影响。
     */
    fun thumbChangedFraction(a: ByteArray?, b: ByteArray?, tol: Int = THUMB_DIFF_TOL): Float? {
        if (a == null || b == null) return null
        if (a.size != b.size) return 1f
        val n = a.size / 3
        var diff = 0
        for (k in 0 until n) {
            val i = k * 3
            if (kotlin.math.abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)) > tol ||
                kotlin.math.abs((a[i + 1].toInt() and 0xFF) - (b[i + 1].toInt() and 0xFF)) > tol ||
                kotlin.math.abs((a[i + 2].toInt() and 0xFF) - (b[i + 2].toInt() and 0xFF)) > tol
            ) diff++
        }
        return diff.toFloat() / n
    }

    /** 缩略图差异的 4-bit 量化容差（见 [thumbChangedFraction]）：±2 档内视为同，滤量化边界抖动。 */
    const val THUMB_DIFF_TOL = 2
}
