package com.bettergi.pocket.scan

import com.bettergi.pocket.capture.CapturedBgrFrame
import com.bettergi.pocket.capture.FrameSource
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar

/**
 * #255 背包「按钮回顶」的接线：链项名 → profile 坐标 → 点击落点。
 *
 * 为什么钉这一条而不是整条 flow：`artifact_lock` 原来的回顶是 pagedGrid 里的**拖拽**，
 * 判据「连续两轮画面没变」在 #144 实测会**假阳性**（一次被吞的手势就把整册判成已在顶部，
 * 之后从未知行号起步 ⇒ 静默漏件）。换成「点排序钮 → 点确认」后，复位是否发生**只看这两下点没点到**，
 * 所以可测的就是落点；落点错了（或键被删）就退化成"没回顶"，而界面上一切照常。
 *
 * 坐标出处（2026-10-12 实测，`template_paixu_…` 在真帧上 cv2.matchTemplate）：
 *  · 2244×1080 BlueStacks 现场 0.980 / 华为 curated 截图 0.772 ⇒ 同矩形 (352,971)-(414,1033)；
 *  · 2560×1440 curated 截图 0.993 ⇒ (349,1294)-(433,1378)。
 *  · 3200 **没量过**（语料里没有 artifact_backpack 帧）⇒ 缺键 ⇒ 只许"打 W 跳过"，不许猜坐标。
 */
class ArtifactSortResetTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            nu.pattern.OpenCV.loadLocally()
            Mat(2, 2, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0)).release()
        }
    }

    private class NoopFrameSource : FrameSource {
        override fun markActionAt(timestampMs: Long) = Unit
        override suspend fun grabFresh(afterTimestampMs: Long, timeoutMs: Long): Mat =
            throw UnsupportedOperationException("按钮回顶不抓帧")

        override fun newFreshnessScope(): FrameSource = this
        override fun acquireLatestBgr(): CapturedBgrFrame? = null
        override fun discardLatestImages() = Unit
        override fun capturedSize(): Pair<Int, Int>? = null
        override fun isRunning(): Boolean = false
        override fun release() = Unit
    }

    private class RecordingActions : ScanEngine.ActionGateway {
        val clicks = ArrayList<Pair<Int, Int>>()
        override fun click(x: Int, y: Int, durationMs: Long): Boolean {
            clicks += x to y
            return true
        }

        override fun tap(x: Int, y: Int): Boolean {
            clicks += x to y
            return true
        }

        override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int) = true
        override fun back() = true
    }

    private class NoopListener : ScanListener {
        override fun onProgress(stage: String, data: Map<String, Any?>) = Unit
        override fun onNotice(level: String, text: String) = Unit
        override fun onFinished(summary: String) = Unit
    }

    private fun tierProfile(file: String, w: Int, h: Int): ScreenProfile {
        var dir = java.io.File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val f = java.io.File(dir, "src/main/assets/dsl/$file")
            if (f.isFile) {
                val p = ScreenProfile(JSONObject(f.readText()))
                p.calibrate(w, h)
                return p
            }
            dir = dir.parentFile ?: dir
        }
        error("$file not found")
    }

    private fun engine(profile: ScreenProfile, actions: RecordingActions) = ScanEngine(
        flowJson = JSONObject().put("flow", "artifact_lock"),
        profile = profile,
        frameSource = NoopFrameSource(),
        actions = actions,
        ocr = null,
        listener = NoopListener(),
    )

    @Test
    fun `排序钮与确认钮在量过的两档各自落在实测中心`() = runBlocking {
        val a2244 = RecordingActions()
        clickChainEntryOn(engine(tierProfile("profiles_2244x1080.json", 2244, 1080), a2244))
        assertEquals("2244 档两下点：排序钮 + 弹窗确认", listOf(383 to 1002, 447 to 1002), a2244.clicks)

        val a2560 = RecordingActions()
        clickChainEntryOn(engine(tierProfile("profiles_2560x1440.json", 2560, 1440), a2560))
        assertEquals("2560 档两下点：排序钮 + 弹窗确认", listOf(391 to 1336, 735 to 1337), a2560.clicks)
    }

    @Test
    fun `3200 缺键时只跳过，不拿别的档的坐标凑`() = runBlocking {
        val acts = RecordingActions()
        val engine = engine(tierProfile("profiles.json", 3200, 1440), acts)
        // 确认钮在 3200 有值 ⇒ 单独点它照常；这里只点排序钮：缺键 ⇒ 无字面坐标 ⇒ 不打点。
        clickChainEntryOn(engine, sortOnly = true)
        assertEquals("3200 档排序钮缺键 ⇒ 一下都不点", emptyList<Pair<Int, Int>>(), acts.clicks)
    }

    private suspend fun clickChainEntryOn(engine: ScanEngine, sortOnly: Boolean = false) {
        with(engine) {
            clickChainEntry("artifactSort")
            if (!sortOnly) clickChainEntry("sortPopupConfirm")
        }
    }
}
