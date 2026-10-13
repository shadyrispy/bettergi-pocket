package com.bettergi.pocket.scan

import com.bettergi.pocket.capture.FrameSource
import com.bettergi.pocket.capture.FrameTimeoutException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar

/**
 * #233 单2：角色正序格「复位到属性页」那次冗余导航的判据（[characterOnAttributesPage]）。
 *
 * 为什么必须钉**正反两个方向**：这条改动的失效形态是"判据恒假 ⇒ 白 OCR 一次、行为退回旧版"，
 * 而旧行为本身是全绿的 ⇒ 只跑一套用例的全绿**证明不了新分支被走过**
 * （[[feedback-green-test-can-be-vacuous]]）。四条断言各自钉一个方向：
 * ① 读到属性页文本 ⇒ true（省导航的那一支）
 * ② 读到天赋页文本 / 读空 ⇒ false（照旧导航）
 * ③ 半字「升指南」⇒ true（本档 ROI 左沿实测会切掉「提」，见 profiles bottomBar_note）
 * ④ 引擎没就绪 ⇒ **loud 抛出**，不得降级成 false（同 [charPanelLooksWrongPage] 的 A7/G 口径）
 */
class NavRedundantSkipTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCvNative() {
            nu.pattern.OpenCV.loadLocally()
            Mat(2, 2, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0)).release()
        }
    }

    /** 每次 readRois 都回同一串文本（本函数只传 1 个矩形，所以不需要按矩形分派）。 */
    private class TextGateway(private val text: String) : OcrGateway {
        override suspend fun readNumber(frame: Mat, rect: FrameRect): Int = 0
        override suspend fun readLines(frame: Mat, rects: List<FrameRect>): List<String> = rects.map { text }
        override suspend fun readRois(frame: Mat, rects: List<FrameRect>): List<String> = rects.map { text }
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

    private class TinyFrameSource : FrameSource {
        override suspend fun grabFresh(afterTimestampMs: Long, timeoutMs: Long): Mat =
            Mat(64, 64, CvType.CV_8UC3, Scalar(255.0, 255.0, 255.0))

        override fun markActionAt(timestampMs: Long) = Unit
        override fun acquireLatestBgr() = throw FrameTimeoutException(0, 0)
        override fun discardLatestImages() = Unit
        override fun capturedSize(): Pair<Int, Int>? = 64 to 64
        override fun isRunning(): Boolean = true
        override fun release() = Unit
    }

    private fun engine(ocr: OcrGateway?): ScanEngine {
        var dir = java.io.File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val f = java.io.File(dir, "src/main/assets/dsl/profiles.json")
            if (f.isFile) {
                val p = ScreenProfile(JSONObject(f.readText()))
                p.calibrate(3200, 1440)
                return ScanEngine(
                    flowJson = JSONObject().put("steps", JSONArray()),
                    profile = p,
                    frameSource = TinyFrameSource(),
                    actions = StubActions(),
                    ocr = ocr,
                    listener = StubListener(),
                    maxPages = 1,
                    clock = { System.nanoTime() / 1_000_000 },
                )
            }
            dir = dir.parentFile ?: dir
        }
        error("profiles.json not found")
    }

    private fun check(text: String, ocrMissing: Boolean = false): Boolean {
        val engine = engine(if (ocrMissing) null else TextGateway(text))
        return runBlocking { engine.characterOnAttributesPage() }
    }

    @Test
    fun `属性页文本命中即判在属性页`() {
        assertTrue("读到「提升指南」必须放行省导航那一支", check("提升指南 上限突破"))
        // 左沿切掉「提」的半字形态：判据带 升指南 备选就是为它（同 parse 的 expect 口径）
        assertTrue("半字「升指南」也要判到，否则 ROI 一漂就整轮白付 700ms", check("是升指南"))
    }

    @Test
    fun `其它页与读空一律判不在属性页`() {
        assertFalse("天赋页宽条是「天赋演示」⇒ 必须照旧导航", check("天赋演示"))
        assertFalse("命座页宽条为空 ⇒ 必须照旧导航", check(""))
        assertFalse("读歪成别的文本也不能放行", check("元素"))
    }

    @Test
    fun `网关缺失时回落到旧行为`() {
        assertFalse("OCR 没接入 ⇒ 判据必须为假（走今天的盲导航），不许猜", check("", ocrMissing = true))
    }

    @Test
    fun `引擎未就绪是 loud 失败而不是降级成假`() {
        val e = runCatching { runBlocking { engine(OcrUnavailableTestGateway()).characterOnAttributesPage() } }
            .exceptionOrNull()
        assertTrue(
            "引擎不可用不得被吞成 false（吞掉就等于把「读不出来」和「确实不在属性页」糊成一件事）；实际=$e",
            e is OcrUnavailableException,
        )
    }

    private class OcrUnavailableTestGateway : OcrGateway {
        override suspend fun readNumber(frame: Mat, rect: FrameRect): Int =
            throw OcrUnavailableException("stub: OCR 引擎未就绪")

        override suspend fun readLines(frame: Mat, rects: List<FrameRect>): List<String> =
            throw OcrUnavailableException("stub: OCR 引擎未就绪")

        override suspend fun readRois(frame: Mat, rects: List<FrameRect>): List<String> =
            throw OcrUnavailableException("stub: OCR 引擎未就绪")
    }

    /** 对照杠杆本身：默认开，`navskip=0` 必须能退回今天的盲导航（A/B 基线臂靠它）。 */
    @Test
    fun `navskip 覆盖键生效且摘要里看得见`() {
        TimingOverrides.reset()
        assertTrue("默认必须是开（改动的收益就在默认路径上）", TimingOverrides.navSkipRedundant)
        val s0 = TimingOverrides.apply("navskip=0")
        assertFalse(TimingOverrides.navSkipRedundant)
        assertTrue("summary 不回显就没法给日志归因：$s0", s0.contains("navskip=0"))
        val s1 = TimingOverrides.apply("navskip=1")
        assertTrue(TimingOverrides.navSkipRedundant)
        assertTrue(s1.contains("navskip=1"))
        TimingOverrides.reset()
        assertTrue("reset 之后回默认（开）", TimingOverrides.navSkipRedundant)
    }
}
