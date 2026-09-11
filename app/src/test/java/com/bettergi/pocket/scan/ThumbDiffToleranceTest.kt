package com.bettergi.pocket.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缩略图差异度量的**量化容差**回归测试（2026-09-10 定谳）。
 *
 * 背景（真机掩码实证，见 ScanEngine.rosterFind 回顶探针）：
 * char_popup 弹层静止态下，`gridThumb` 的 4-bit 量化像素会因 MediaProjection 编解码噪声在
 * 量化边界附近来回跳 ±1 档。严格不等（tol=0）把这些抖动全记成"变化像素"，使 diff 在
 * 0.065~0.22 间乱跳、横跨 GRID_SIMILAR_DIFF(0.10) → 回顶判据时对时错（PHASE3 白滑 8 次）。
 * 修法：给差异度量加 4-bit 档位容差 [VoteJudges.THUMB_DIFF_TOL]=2，并给回顶判据独立的松阈值
 * [ScanEngine.GRID_END_DIFF]=0.35（噪声带 ≤0.22 vs 真实位移 ≥0.72，阈值落在安全带内）。
 */
class ThumbDiffToleranceTest {

    private val n = ScanEngine.THUMB_COLS * ScanEngine.THUMB_ROWS // 384 像素

    private fun thumb(f: (Int, Int) -> Int): ByteArray {
        val out = ByteArray(n * 3)
        var i = 0
        for (k in 0 until n) {
            val v = f(k / ScanEngine.THUMB_COLS, k % ScanEngine.THUMB_COLS).coerceIn(0, 15)
            out[i++] = v.toByte(); out[i++] = v.toByte(); out[i++] = v.toByte()
        }
        return out
    }

    @Test
    fun `identical thumbs are zero diff`() {
        val a = thumb { y, x -> (y * 3 + x) % 16 }
        assertEquals(0f, VoteJudges.thumbChangedFraction(a, a.copyOf())!!, 1e-6f)
    }

    @Test
    fun `plus_minus_one_level flicker everywhere is fully suppressed`() {
        // 全图 ±1 档抖动（最坏情况的量化边界抖动）→ 容差 2 应完全吸收 → 0
        val a = thumb { y, x -> 8 }
        val b = a.copyOf()
        for (k in 0 until n) {
            val d = if (k % 2 == 0) 1 else -1
            b[k * 3] = (8 + d).toByte(); b[k * 3 + 1] = (8 - d).toByte(); b[k * 3 + 2] = (8 + d).toByte()
        }
        assertEquals(0f, VoteJudges.thumbChangedFraction(a, b)!!, 1e-6f)
    }

    @Test
    fun `plus_minus_one_level flicker counted without tolerance`() {
        // 对照组：tol=0 时同一抖动被记成 100% 变化 —— 这正是修复前的行为
        val a = thumb { _, _ -> 8 }
        val b = a.copyOf()
        for (k in 0 until n) b[k * 3] = 9
        assertEquals(1f, VoteJudges.thumbChangedFraction(a, b, tol = 0)!!, 1e-6f)
    }

    @Test
    fun `delta exactly at tolerance is ignored above is counted`() {
        val a = thumb { _, _ -> 4 }
        val atTol = a.copyOf()
        for (k in 0 until n) atTol[k * 3] = (4 + VoteJudges.THUMB_DIFF_TOL).toByte()
        assertEquals(0f, VoteJudges.thumbChangedFraction(a, atTol)!!, 1e-6f)

        val over = a.copyOf()
        for (k in 0 until n) over[k * 3] = (4 + VoteJudges.THUMB_DIFF_TOL + 1).toByte()
        assertEquals(1f, VoteJudges.thumbChangedFraction(a, over)!!, 1e-6f)
    }

    @Test
    fun `real page scroll stays far above the top judge threshold`() {
        // 真实翻页 = 整块位移：一半画面被"换掉"（模拟滚动一整行）→ 应远超 GRID_END_DIFF
        val a = thumb { y, _ -> if (y < 8) 2 else 12 }
        val b = thumb { y, _ -> if (y < 4) 12 else 2 }
        val d = VoteJudges.thumbChangedFraction(a, b)!!
        assertTrue("shifted content diff=$d must exceed GRID_END_DIFF", d > ScanEngine.GRID_END_DIFF)
    }

    @Test
    fun `top judge threshold sits strictly between noise band and real movement`() {
        // 实测（2026-09-10 char_popup 回顶探针）：噪声带 ≤0.22，真实位移 ≥0.72
        val noiseCeiling = 0.22f
        val realMovementFloor = 0.72f
        assertTrue(
            "GRID_END_DIFF=${ScanEngine.GRID_END_DIFF} 必须高于噪声上界 $noiseCeiling",
            ScanEngine.GRID_END_DIFF > noiseCeiling,
        )
        assertTrue(
            "GRID_END_DIFF=${ScanEngine.GRID_END_DIFF} 必须低于真实位移下界 $realMovementFloor",
            ScanEngine.GRID_END_DIFF < realMovementFloor,
        )
        // settle 用的紧阈值仍严于回顶判据（settle 不能因放松而提前返回动画中间帧）
        assertTrue(ScanEngine.GRID_SIMILAR_DIFF < ScanEngine.GRID_END_DIFF)
    }

    @Test
    fun `null or size-mismatch is reported as unknown or full change`() {
        val a = thumb { _, _ -> 5 }
        assertEquals(null, VoteJudges.thumbChangedFraction(null, a))
        assertEquals(null, VoteJudges.thumbChangedFraction(a, null))
        assertEquals(1f, VoteJudges.thumbChangedFraction(a, ByteArray(3))!!, 1e-6f)
    }
}
