package com.bettergi.pocket.scan

import org.opencv.core.Mat

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
     * 武器金条 vote：zone rel 读自 profiles（[30,170,170,212]），金条命中数 → 星级 = round(count/555)。
     * counts 1/3/5★ 样本 ~561/1600/2700 像素（profiles.sample.samples 字段，未在代码内固化）。
     */
    fun weaponStarStrip(frame: Mat, profile: ScreenProfile, gridKey: String, col: Int, row: Int): Result {
        val rel = intArrayOf(30, 170, 170, 212)
        val rect = profile.cardRelRect(gridKey, rel, col, row)
        val count = countMatches(frame, rect, GOLD_STRIP)
        val stars = Math.round(count / 555.0f).coerceIn(0, 5).toInt()
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

    /** 网格区指纹（翻页位移校验）：对网格可见区做稀疏采样哈希。 */
    fun gridFingerprint(frame: Mat, profile: ScreenProfile, gridKey: String): Long {
        // 两种几何写法统一（cardOrigin+pitch 与 colX/rowY）——原实现只认前者，
        // char_popup 取指纹会抛 JSONException。
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
            val grid = profile.rawObject("grids.$gridKey") ?: return 0L
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
        var hash = 1469598103934665603L
        val stepX = ((x1 - x0) / 24).coerceAtLeast(1)
        val stepY = ((y1 - y0) / 16).coerceAtLeast(1)
        val buf = ByteArray(3)
        var y = y0
        while (y < y1.coerceAtMost(frame.rows())) {
            var x = x0
            while (x < x1.coerceAtMost(frame.cols())) {
                frame.get(y.coerceIn(0, frame.rows() - 1), x.coerceIn(0, frame.cols() - 1), buf)
                val v = (buf[0].toInt() and 0xFF) or
                    ((buf[1].toInt() and 0xFF) shl 8) or
                    ((buf[2].toInt() and 0xFF) shl 16)
                hash = (hash xor v.toLong()) * 1099511628211L
                x += stepX
            }
            y += stepY
        }
        return hash
    }
}
