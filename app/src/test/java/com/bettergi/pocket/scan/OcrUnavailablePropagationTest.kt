package com.bettergi.pocket.scan

import com.bettergi.pocket.capture.FrameSource
import com.bettergi.pocket.capture.FrameTimeoutException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar

/**
 * G（轨 E 集成）：`OcrUnavailableException`（OCR 引擎未就绪，OcrGatewayImpl 抛出）必须
 * **loud 失败**，不得被 ScanEngine 侧的吞异常点降级成「跳过该格 / 放弃重读」。
 *
 * 场景钉的是 `parseCharacterPanel` 的函数级 `catch (e: Exception)`：引擎没就绪时旧实现把它
 * 吞掉 ⇒ run() 正常收尾、导出里**静默缺角色**（比崩更难查）。放行后异常穿出 run()。
 */
class OcrUnavailablePropagationTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCvNative() {
            nu.pattern.OpenCV.loadLocally()
            Mat(2, 2, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0)).release()
        }
    }

    /** 所有 OCR 入口一律抛 OcrUnavailableException（引擎未就绪的网关桩）。 */
    private class UnavailableGateway : OcrGateway {
        override suspend fun readNumber(frame: Mat, rect: FrameRect): Int =
            throw OcrUnavailableException("stub: OCR 引擎未就绪")

        override suspend fun readLines(frame: Mat, rects: List<FrameRect>): List<String> =
            throw OcrUnavailableException("stub: OCR 引擎未就绪")

        override suspend fun readRois(frame: Mat, rects: List<FrameRect>): List<String> =
            throw OcrUnavailableException("stub: OCR 引擎未就绪")
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

    /**
     * 最小角色扫描流：pagedGrid(char_strip) × visit[parsePanel char_profile]。
     * 首格 parseCharacterPanel 的 `gateway.readRois` 即遇「引擎未就绪」。
     */
    private fun engine(): ScanEngine {
        var dir = java.io.File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val f = java.io.File(dir, "src/main/assets/dsl/profiles.json")
            if (f.isFile) {
                val p = ScreenProfile(JSONObject(f.readText()))
                p.calibrate(3200, 1440)
                val flow = JSONObject().put(
                    "steps",
                    JSONArray().put(
                        JSONObject()
                            .put("do", "pagedGrid")
                            .put("grid", "char_strip")
                            .put(
                                "visit",
                                JSONArray().put(
                                    JSONObject().put("do", "parsePanel").put("panel", "char_profile"),
                                ),
                            ),
                    ),
                )
                return ScanEngine(
                    flowJson = flow,
                    profile = p,
                    frameSource = TinyFrameSource(),
                    actions = StubActions(),
                    ocr = UnavailableGateway(),
                    listener = StubListener(),
                    maxPages = 1,
                    clock = { System.nanoTime() / 1_000_000 },
                )
            }
            dir = dir.parentFile ?: dir
        }
        error("profiles.json not found")
    }

    @Test
    fun `ocr unavailable propagates as loud failure instead of being swallowed`() {
        val engine = engine()
        val e = runCatching {
            runBlocking { withTimeout(60_000) { engine.run() } }
        }.exceptionOrNull()
        assertTrue(
            "引擎不可用必须 loud（旧实现被 parseCharacterPanel 吞成跳过该格，run() 正常收尾）；实际异常=$e",
            e is OcrUnavailableException,
        )
    }
}
