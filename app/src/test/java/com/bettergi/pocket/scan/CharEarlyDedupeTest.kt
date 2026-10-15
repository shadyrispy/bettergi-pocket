package com.bettergi.pocket.scan

import com.bettergi.pocket.capture.FrameSource
import com.bettergi.pocket.capture.CapturedBgrFrame
import com.bettergi.pocket.recognition.name.GoodNames
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.opencv.core.Mat

/**
 * #308：角色**回卷区跳过格**的两条省功路径。
 *
 * 1. **earlyDedupe 判重短路**（正序格 parse）：回卷区重复件先只读 header 判重，命中已见
 *    key ⇒ 不再读其余 4 矩形（旧实现 5 矩形 + 名字重读循环 ≈1.5s/格，华为全量 170 格里
 *    77 格是跳过格）。未命中 / key=null ⇒ 照旧完整 parse。
 * 2. **反序跳过格免属性页补读**：重复件不入库，level 无消费者；但 charName 必须填——
 *    emitCharacter 开头「charName 为空 ⇒ return」在 noteDupAndMaybeStop（回卷止扫判据）
 *    **之前**，空着会让判据断粮、列表滚不停。
 *
 * 本测试用 OCR 调用计数钉第 1 条：短路命中时 `readRois` 只发生 1 次（header）。
 */
class CharEarlyDedupeTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            nu.pattern.OpenCV.loadLocally()
            Mat(2, 2, org.opencv.core.CvType.CV_8UC3, org.opencv.core.Scalar.all(0.0)).release()
        }
    }

    private val dict: GoodNames by lazy {
        val f = File("src/main/assets/dsl/tools/mappings.json")
        assertTrue("找不到随包词典 ${f.absolutePath}", f.isFile)
        GoodNames.fromJson(JSONObject(f.readText()))
    }

    private class NoOpActions : ScanEngine.ActionGateway {
        override fun click(x: Int, y: Int, durationMs: Long) = true
        override fun tap(x: Int, y: Int) = true
        override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int) = true
        override fun back() = true
    }

    private class NoOpListener : ScanListener {
        override fun onProgress(stage: String, vars: Map<String, Any?>) = Unit
        override fun onFinished(reason: String) = Unit
    }

    /** 帧桩：抓帧返回一张 3200x1440 白图（parse 的 ROI 读由 CountingOcr 桩接管，像素内容无关）。 */
    private class StubFrameSource : FrameSource {
        override suspend fun grabFresh(afterTimestampMs: Long, timeoutMs: Long): Mat =
            Mat(1440, 3200, org.opencv.core.CvType.CV_8UC3, org.opencv.core.Scalar.all(255.0))
        override fun markActionAt(timestampMs: Long) = Unit
        override fun acquireLatestBgr(): CapturedBgrFrame? = null
        override fun discardLatestImages() = Unit
        override fun capturedSize(): Pair<Int, Int>? = 3200 to 1440
        override fun isRunning(): Boolean = true
        override fun release() = Unit
    }

    /** 桩 OCR：每次 readRois 计数并回填固定行。header 判重只读 1 矩形。 */
    private class CountingOcr(private val rows: List<String>) : OcrGateway {
        val calls = AtomicInteger(0)
        override suspend fun readNumber(frame: Mat, rect: FrameRect): Int? = 0
        override suspend fun readLines(frame: Mat, rects: List<FrameRect>): List<String> {
            calls.incrementAndGet()
            return rows.take(rects.size)
        }
    }

    private fun engine(ocr: OcrGateway): ScanEngine {
        val p = ScreenProfile(JSONObject(File("src/main/assets/dsl/profiles.json").readText()))
        p.calibrate(3200, 1440)
        return ScanEngine(
            flowJson = JSONObject().put("steps", JSONArray()),
            profile = p,
            frameSource = StubFrameSource(),
            actions = NoOpActions(),
            ocr = ocr,
            names = dict,
            listener = NoOpListener(),
            maxPages = 1,
            clock = { System.nanoTime() / 1_000_000 },
        )
    }

    private val step = JSONObject()
        .put("panel", "char_profile")
        .put("earlyDedupe", true)
        .put("dict", JSONObject().put("name", "mappings.characters").put("fuzzy", 1))

    @Test
    fun `重复件短路后不再读剩余矩形`() {
        val ocr = CountingOcr(listOf("水元素/芭芭拉"))
        val e = engine(ocr)
        // 已入库同 key：模拟「本轮已见过 Barbara」
        e.resultsCharacters.add(
            GoodCharacter(key = "Barbara", name = "Barbara", level = 70, element = "Hydro"),
        )
        runBlocking { e.parseCharacterPanel(step, null) }
        assertEquals("命中已见 key ⇒ 只有 header 那 1 次 readRois", 1, ocr.calls.get())
        assertEquals("短路也要写 key/charName（判据与 emit 需要）", "Barbara", e.charKey)
        assertEquals("charName 用 header 显示名（emitCharacter 空名短路防判据断粮）", "芭芭拉", e.charName)
    }

    @Test
    fun `新角色照旧完整 parse（不短路）`() {
        // 桩回填 5 行：name/level/header/bottomBar/titleBar（正序完整路径读 5 矩形 + 可能的重读）
        val ocr = CountingOcr(listOf("芭芭拉", "等级70/80", "水元素/芭芭拉", "提升指南", "芭芭拉"))
        val e = engine(ocr)
        runBlocking { e.parseCharacterPanel(step, null) }
        assertTrue("新角色不得短路（earlyDedupe 只是跳过重复件）", ocr.calls.get() >= 2)
    }

    @Test
    fun `key 解析不出时照旧完整 parse`() {
        // header 读不到有效名字（OCR 空读）⇒ key=null ⇒ 必须走完整路径（宁可慢不可丢）
        val ocr = CountingOcr(listOf("", "等级70/80", "", "提升指南", ""))
        val e = engine(ocr)
        runBlocking { e.parseCharacterPanel(step, null) }
        assertTrue("key=null 不得短路", ocr.calls.get() >= 2)
    }
}
