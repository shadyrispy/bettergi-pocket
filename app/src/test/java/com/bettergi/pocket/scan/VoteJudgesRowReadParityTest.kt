package com.bettergi.pocket.scan

import nu.pattern.OpenCV
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.Random

/**
 * ★ P3（2026-10-01）逐像素读批量化改造的**逐位一致**守护（同 OCR 轨 parity 测试风格）：
 * 参照实现 = 改造前的逐点 `get(y, x, buf)` 读法，直接在本测试内固化；
 * 同输入（确定性伪随机帧）下新旧输出必须逐位一致。
 */
class VoteJudgesRowReadParityTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCvNative() {
            OpenCV.loadLocally()
            Mat(2, 2, CvType.CV_8UC3).release()
        }
    }

    /** 确定性伪随机 BGR 帧（种子固定 ⇒ 任何机器同输入）。 */
    private fun randomFrame(w: Int, h: Int, seed: Long): Mat {
        val m = Mat(h, w, CvType.CV_8UC3)
        val rnd = Random(seed)
        val buf = ByteArray(w * 3)
        for (y in 0 until h) {
            rnd.nextBytes(buf)
            m.put(y, 0, buf)
        }
        return m
    }

    /** 改造前 gridThumb 的逐点读参照实现（口径 1:1，含 submat + INTER_AREA + BGR→RGB 4bit 量化）。 */
    private fun gridThumbReference(frame: Mat, roi: IntArray): ByteArray? {
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

    /** 改造前 rarityFromBanner 的逐点读参照实现（含 clamp 与分类口径）。 */
    private fun rarityReference(frame: Mat, rect: IntArray): Int {
        val x0 = rect[0].coerceIn(0, frame.cols() - 1)
        val y0 = rect[1].coerceIn(0, frame.rows() - 1)
        val x1 = rect[2].coerceIn(0, frame.cols())
        val y1 = rect[3].coerceIn(0, frame.rows())
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

    @Test
    fun `gridThumb batched row read is bit identical to per pixel reference`() {
        val profile = ScreenProfile(JSONObject("""{"grids":{"artifact_backpack":{"bounds":[10,20,190,300]}}}"""))
        val frame = randomFrame(3200, 1440, seed = 20261001L)
        val actual = VoteJudges.gridThumb(frame, profile, "artifact_backpack")
        val expected = gridThumbReference(frame, intArrayOf(10, 20, 190, 300))
        assertArrayEquals(expected, actual)
        assertEquals(24 * 16 * 3, actual!!.size)
        frame.release()
    }

    @Test
    fun `rarityFromBanner batched row read is bit identical to per pixel reference`() {
        val profile = ScreenProfile(
            JSONObject("""{"zones":{"artifact.rarity":{"banner":[5,6,90,40]}}}"""),
        )
        val frame = randomFrame(100, 50, seed = 42L)
        assertEquals(rarityReference(frame, intArrayOf(5, 6, 90, 40)), VoteJudges.rarityFromBanner(frame, profile))
        frame.release()
    }

    @Test
    fun `rarityFromBanner classifies crafted purple as 3 parity guard`() {
        // 纯色紫横幅：B 高 R 低 → 3★（与 VoteJudgesRealDataTest 的 PURPLE_BANNER 色域口径互证）
        val profile = ScreenProfile(
            JSONObject("""{"zones":{"artifact.rarity":{"banner":[0,0,20,10]}}}"""),
        )
        // B=190>180, R=120<130 且 B>R+40 → 3★（落在 3★ 分支而非 b>180&&r>130 的 4★ 分支）
        val frame = Mat(10, 20, CvType.CV_8UC3, org.opencv.core.Scalar(190.0, 107.0, 120.0)) // B,G,R
        assertEquals(3, VoteJudges.rarityFromBanner(frame, profile))
        frame.release()
    }
}
