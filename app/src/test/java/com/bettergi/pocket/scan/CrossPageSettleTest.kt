package com.bettergi.pocket.scan

import com.bettergi.pocket.capture.FrameSource
import com.bettergi.pocket.capture.FrameTimeoutException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * A40（#147）跨页判稳单测：`ScanEngine.awaitCrossPageSettle` 的**锚位移信号**行为。
 *
 * 背景：旧信号 = 固定 `delay(CROSS_PAGE_SETTLE_MS=250ms)`，看不见列表残余慢漂 ——
 * 「先注漂移、判稳必须等到漂移结束」这件事固定延时原理上做不到 ⇒ 用注入式帧源直接钉新信号：
 *   · 相邻帧底栏锚（grids.weapon_backpack.landingBand = 网格第 4 可见行条带）位移 > 阈值 ⇒ 不判稳；
 *   · 漂移停 after 连续 ANCHOR_SETTLE_STABLE_PAIRS 对帧位移 ≤ 阈值 ⇒ 判稳返回。
 * 旧实现（纯 delay、零抓帧）下「抓帧数 ≥ N」的断言全部红 —— 这就是红→绿证据。
 */
class CrossPageSettleTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCvNative() {
            nu.pattern.OpenCV.loadLocally()
            Mat(2, 2, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0)).release()
        }
    }

    /**
     * 合成帧源：3200×1440 白底，在 weapon_backpack 的 landingBand 区（y[1077,1235] × x[1200,2080]）
     * 内画一个随 [offset] 垂直移动的深色块 —— 每次抓帧后 offset 按 [step] 衰减到 0（模拟滑动残余慢漂衰减）。
     * 锚位移测量（模板匹配）读到的相邻帧位移 = step，> 阈值 ⇒ 判稳不得通过。
     */
    private class DriftingFrameSource(
        private val driftFrames: Int,
        private val step: Int = 6,
    ) : FrameSource {
        var grabs = 0
            private set
        val offsetXHistory = ArrayList<Int>()

        override suspend fun grabFresh(afterTimestampMs: Long, timeoutMs: Long): Mat {
            val offset = if (grabs < driftFrames) (driftFrames - grabs) * step else 0
            offsetXHistory.add(offset)
            grabs++
            val m = Mat(1440, 3200, CvType.CV_8UC3, Scalar(255.0, 255.0, 255.0))
            // 条带内内容块：y 基准 1100 + offset（在 landingBand y[1077,1235] 内、offset ≤ 36 时不越界）
            Imgproc.rectangle(
                m,
                Point(1300.0, (1100 + offset).toDouble()),
                Point(1500.0, (1160 + offset).toDouble()),
                Scalar(40.0, 40.0, 40.0),
                -1,
            )
            return m
        }

        override fun markActionAt(timestampMs: Long) = Unit
        override fun acquireLatestBgr() = throw FrameTimeoutException(0, 0)
        override fun discardLatestImages() = Unit
        override fun capturedSize(): Pair<Int, Int>? = 3200 to 1440
        override fun isRunning(): Boolean = true
        override fun release() = Unit
    }

    private class StubActions : ScanEngine.ActionGateway {
        override fun click(x: Int, y: Int, durationMs: Long) = true
        override fun tap(x: Int, y: Int) = true
        override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int) = true
        override fun back() = true
    }

    private class StubListener : ScanListener {
        override fun onProgress(stage: String, vars: Map<String, Any?>) = Unit
        override fun onFinished(reason: String) = Unit
    }

    /** weapon_backpack 档 profile（真 profiles.json，calibrate(3200,1440) 后 landingBand 即帧坐标）。 */
    private fun weaponProfile(): ScreenProfile {
        var dir = java.io.File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val f = java.io.File(dir, "src/main/assets/dsl/profiles.json")
            if (f.isFile) {
                val p = ScreenProfile(JSONObject(f.readText()))
                p.calibrate(3200, 1440)
                return p
            }
            dir = dir.parentFile ?: dir
        }
        error("profiles.json not found")
    }

    private fun engine(frameSource: FrameSource): ScanEngine =
        ScanEngine(
            flowJson = JSONObject().put("steps", org.json.JSONArray()),
            profile = weaponProfile(),
            frameSource = frameSource,
            actions = StubActions(),
            ocr = null,
            listener = StubListener(),
            clock = { System.nanoTime() / 1_000_000 },
        )

    /** 慢漂注入：前 3 次抓帧内容逐帧上移 6px（> 阈值 3px），之后静止 ⇒ 判稳必须等到静止后凑满 3 对稳定帧。 */
    @Test
    fun `anchor settle waits for slow drift to stop before declaring stable`() {
        val src = DriftingFrameSource(driftFrames = 3)
        val engine = engine(src)
        runBlocking {
            withTimeout(30_000) {
                engine.awaitCrossPageSettle("weapon_backpack")
            }
        }
        // 旧实现（固定 250ms、零抓帧）grabs == 0 ⇒ 红；新信号必须**抓帧看漂移**：
        // 对帧 (0,1)(1,2)(2,3) 位移 6px 全部拒绝，(3,4)(4,5)(5,6) 静止 ⇒ ≥7 次抓帧。
        assertTrue(
            "判稳应实际轮询锚位移（旧固定延时抓 0 帧）grabs=${src.grabs}",
            src.grabs >= 7,
        )
        // 退出时漂移必须已经结束（最后两帧同 offset = 静止）
        val n = src.offsetXHistory.size
        assertEquals(0, src.offsetXHistory[n - 1])
        assertEquals(0, src.offsetXHistory[n - 2])
    }

    /** 静止帧源：3 对稳定帧即判稳，不白等预算上限。 */
    @Test
    fun `anchor settle returns quickly on already static frames`() {
        val src = DriftingFrameSource(driftFrames = 0)
        val engine = engine(src)
        runBlocking {
            withTimeout(30_000) {
                engine.awaitCrossPageSettle("weapon_backpack")
            }
        }
        // 3 对稳定帧 = 4 次抓帧（首帧无前帧可比）；旧实现抓 0 帧 ⇒ 红
        assertTrue("静止画面应按 3 对稳定帧尽快放行 grabs=${src.grabs}", src.grabs in 4..6)
    }

    /** 无 landingBand 几何的网格 ⇒ 回退旧定时语义（不抓帧、快速返回），行为与改动前逐位一致。 */
    @Test
    fun `grid without landing band falls back to fixed delay`() {
        val src = DriftingFrameSource(driftFrames = 0)
        val engine = engine(src)
        runBlocking {
            withTimeout(30_000) {
                engine.awaitCrossPageSettle("char_strip")
            }
        }
        assertEquals("回退路径不得抓帧", 0, src.grabs)
    }
}
