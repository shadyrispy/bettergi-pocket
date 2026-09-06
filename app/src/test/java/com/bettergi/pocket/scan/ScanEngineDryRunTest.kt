package com.bettergi.pocket.scan

import com.bettergi.pocket.capture.FrameSource
import com.bettergi.pocket.capture.FrameTimeoutException
import com.bettergi.pocket.recognition.name.GoodNames
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * ScanEngine 全流程数字孪生 dry-run：合成帧 + mock 网关跑通真实 artifact_scan flow。
 * 验证解释器控制流 / pagedGrid 编排 / vote 判据 / parsePanel 集成 / stopWhen / GOOD 产物。
 * 真机前把流程逻辑全部过一遍——路径与解析层之外的最后一块离线覆盖。
 */
class ScanEngineDryRunTest {

    companion object {
        @JvmStatic
        @org.junit.BeforeClass
        fun loadOpenCvNative() {
            // 强制走 desktop 加载：OpenCvRuntime.ensureLoaded 的 loadAndroid 分支在 JVM 上会
            // 「假成功」（initLocal 返回 true 但 so 未绑定），必须直接调 openpnp loadLocally
            nu.pattern.OpenCV.loadLocally()
            // 冒烟验证：native 真正可用
            Mat(2, 2, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0)).release()
        }
    }

    // ---- 测试资产 ----
    private fun assetsDir(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val candidate = File(dir, "src/main/assets/dsl")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("src/main/assets/dsl not found")
    }

    // BGR 标量（profiles 色域按 RGB 描述，此处直接给 BGR 顺序）
    private val GOLD = Scalar(100.0, 180.0, 200.0)     // R200 G180 B100：金谓词命中
    private val PINK = Scalar(140.0, 150.0, 220.0)     // R220 G150 B140：粉锁徽命中
    private val PURPLE = Scalar(230.0, 120.0, 180.0)   // R180 G120 B230：祝圣紫命中
    private val ORANGE = Scalar(100.0, 150.0, 240.0)   // R240 B100：5★ 橙
    private val BLUE = Scalar(230.0, 150.0, 100.0)     // R100 B230：3★ 蓝
    private val WHITE = Scalar(255.0, 255.0, 255.0)

    private fun rect(m: Mat, x0: Int, y0: Int, x1: Int, y1: Int, color: Scalar) =
        Imgproc.rectangle(m, Point(x0.toDouble(), y0.toDouble()), Point(x1.toDouble(), y1.toDouble()), color, -1)

    /** 3200x1440 白底合成帧，按 profiles 基准坐标画判据色块（calibrate scale=1）。
     *  page>0 时网格区内内容变化（模拟翻页后列表前进）且不画锁徽。 */
    private fun syntheticFrame(rarity: Int, page: Int = 0): Mat {
        val m = Mat(1440, 3200, CvType.CV_8UC3, WHITE)
        // ① 五星开关 pill [2009,199,2126,260]：默认白底（非金）→ 判 off ✓（flow ensure=off，直接通过）
        // ② cell(0,0) 卡内锁徽 rel [8,6,48,46]：origin(416,297) → (424,303)——仅页 1
        if (page == 0) rect(m, 424, 303, 472, 349, PINK)
        // ③ 祝圣三采样点 5x5 紫（zone artifact.panel.zhusheng points y703）
        for (x in intArrayOf(2390, 2400, 2410)) rect(m, x - 2, 701, x + 2, 705, PURPLE)
        // ④ 面板 lock [2811,730,2845,758] 金 ≥40——crafted=true（祝圣紫已画）→ 实际位置 yShift+63
        rect(m, 2811, 730 + 63, 2845, 758 + 63, GOLD)
        // ⑤ 面板 astral [2900,713,2947,757] 金 ≥400——同上 yShift+63
        rect(m, 2900, 713 + 63, 2947, 757 + 63, GOLD)
        // ⑥ rarity banner [2680,205,2840,285]
        rect(m, 2680, 205, 2840, 285, if (rarity == 5) ORANGE else BLUE)
        // ⑦ 星带（不随祝圣 yShift 移动）：5★ 五格 / 3★ 三格
        val stars = if (rarity == 5) 5 else 3
        for (i in 0 until stars) {
            val x0 = 2228 + i * 56
            rect(m, x0, 599, x0 + 46, 640, GOLD)
        }
        // ⑧ page>0：网格区内画灰块——模拟翻页后列表前进（否则网格指纹不变会被判到底）
        if (page > 0) rect(m, 416, 297, 616, 550, Scalar(128.0, 128.0, 128.0))
        return m
    }

    // ---- 页序列 harness：swipe 联动切帧 + 切 OCR 文本 ----
    private class Page(val frame: Mat, val ocrLines: Map<FrameRect, String>, val ocrNumber: Int?)

    private class Harness(pages: List<Page>) {
        var pageIndex = 0
            private set

        val clicks = ArrayList<Pair<Int, Int>>()
        val swipes = ArrayList<Pair<Pair<Int, Int>, Pair<Int, Int>>>()
        val progress = ArrayList<Pair<String, Map<String, Any?>>>()
        var finished: String? = null

        // 按格递增的唯一件名（模拟真实网格每件不同）；页切换时重置（模拟跨页同件重叠 → 去重测试）
        private val NAME_RECT = FrameRect(2230, 220, 2630, 270)
        private var nameCounter = 0

        // readNumber 可编程脚本（onZero 重试等按次序变化场景）；空则回退页静态值
        val numberScript = ArrayDeque<Int?>()

        val frameSource = object : FrameSource {
            override suspend fun grabFresh(afterTimestampMs: Long, timeoutMs: Long): Mat =
                pages[pageIndex].frame.clone()

            override fun markActionAt(timestampMs: Long) = Unit
            override fun acquireLatestBgr() = throw FrameTimeoutException(0, 0) // dry-run 不走该路径
            override fun discardLatestImages() = Unit
            override fun capturedSize(): Pair<Int, Int>? = 3200 to 1440
            override fun isRunning(): Boolean = true
            override fun release() = Unit
        }

        val actions = object : ScanEngine.ActionGateway {
            override fun back(): Boolean = true

            override fun click(x: Int, y: Int, durationMs: Long): Boolean {
                clicks += x to y
                return true
            }

            override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean {
                swipes += (fromX to fromY) to (toX to toY)
                // 滑动后画面切到下一页（模拟翻页后的新内容；末页保持不变 → 指纹判到底）
                if (pageIndex < pages.size - 1) pageIndex++
                // 新页件名重新编号：跨页同件重叠场景由去重测试显式使用
                resetNameCounter()
                return true
            }
        }

        val ocr = object : OcrGateway {
            override suspend fun readNumber(frame: Mat, rect: FrameRect): Int? {
                if (numberScript.isNotEmpty()) return numberScript.removeFirst()
                return pages[pageIndex].ocrNumber
            }

            override suspend fun readLines(frame: Mat, rects: List<FrameRect>): List<String> =
                rects.mapNotNull { r ->
                    if (r == NAME_RECT) "晨光的明誓#${nameCounter++}" else pages[pageIndex].ocrLines[r]
                }
        }

        val listener = object : ScanListener {
            override fun onProgress(stage: String, vars: Map<String, Any?>) {
                progress += stage to vars
            }

            override fun onFinished(reason: String) {
                finished = reason
            }
        }

        fun resetNameCounter() {
            nameCounter = 0
        }
    }

    private fun pageLines(levelText: String, withCountLine: Boolean = true): Map<FrameRect, String> {
        val base = mutableMapOf(
            FrameRect(2230, 220, 2630, 270) to "晨光的明誓",            // name
            FrameRect(2210, 306, 2450, 364) to "死之羽",                // slot
            FrameRect(2210, 444, 2460, 500) to "生命值",                // mainName
            FrameRect(2210, 490, 2510, 590) to "4,780",                // mainValue
            FrameRect(2200, 716, 2400, 766) to levelText,              // level（未祝圣位置）
            FrameRect(2200, 779, 2400, 829) to levelText,              // level（crafted yShift+63 后）
            FrameRect(2230, 794, 2990, 848) to "暴击率+5.8%",           // sub0（原位）
            FrameRect(2230, 858, 2990, 912) to "生命值+717",            // sub1（原位）
            FrameRect(2230, 922, 2990, 976) to "元素充能效率+12.4%",     // sub2（原位）
            FrameRect(2230, 986, 2990, 1040) to "元素精通+23",           // sub3（原位）
            FrameRect(2230, 857, 2990, 911) to "暴击率+5.8%",           // sub0（crafted yShift+63）
            FrameRect(2230, 921, 2990, 975) to "生命值+717",            // sub1
            FrameRect(2230, 985, 2990, 1039) to "元素充能效率+12.4%",    // sub2
            FrameRect(2230, 1049, 2990, 1103) to "元素精通+23",          // sub3
        )
        if (withCountLine) base[FrameRect(2674, 63, 2874, 95)] = "圣遗物 1026/2400"
        return base
    }

    private data class RunResult(
        val engine: ScanEngine,
        val harness: Harness,
    )

    // ---- dry-run ----
    private fun runEngine(pages: List<Page>, dedupe: Boolean = false, numberScript: List<Int?> = emptyList()): RunResult {
        val profile = ScreenProfile(JSONObject(File(assetsDir(), "profiles.json").readText()))
        profile.calibrate(3200, 1440)
        val flow = JSONObject(File(assetsDir(), "flows/artifact_scan.json").readText())
        // 单一名称词典（角色/武器/套装/单件/词条/部位）——与生产同路径
        val names = try {
            GoodNames.fromJson(JSONObject(File(assetsDir(), "tools/good_names.json").readText()))
        } catch (_: Exception) {
            null
        }

        val h = Harness(pages)
        numberScript.forEach { h.numberScript.addLast(it) }
        // dedupe=false：dry-run 的 21 格 mock OCR 文本相同（人工场景），全量入库便于断言；
        // 真机每件内容不同，生产默认 true。
        // clock 注入真实时间：JVM 单测 SystemClock 被 returnDefaultValues 恒 0，会让 settle 轮询死循环
        val engine = ScanEngine(
            flowJson = flow,
            profile = profile,
            frameSource = h.frameSource,
            actions = h.actions,
            ocr = h.ocr,
            names = names,
            listener = h.listener,
            dedupe = dedupe,
            clock = { System.nanoTime() / 1_000_000 },
            // 合成帧不随点击变化：clickDelay 注入短值，避免每格白等固定延时
            clickDelayMs = 10L,
        )
        runBlocking { engine.run() }
        return RunResult(engine, h)
    }

    // ---- 入库去重（Q2 决策）：两页同件 → 第二次出现跳过 ----
    @Test
    fun `duplicate artifacts across pages are deduped`() {
        val (engine, h) = runEngine(
            listOf(
                Page(syntheticFrame(5), pageLines("Lv.90"), 1026),
                Page(syntheticFrame(5, page = 1), pageLines("Lv.90"), 1030),
            ),
            dedupe = true,
        )
        // 页 1 入 21 件；页 2 同内容 → 全部判重跳过；页 3（无帧变化）指纹判到底
        assertEquals(21, engine.results.size)
        assertEquals(2, h.swipes.size)
    }

    @Test
    fun `full flow produces 21 artifacts`() {
        val (engine, h) = runEngine(listOf(Page(syntheticFrame(5), pageLines("Lv.90"), 1026)))
        assertEquals(21, engine.results.size)
        assertEquals("completed", h.finished)
        // 点击数：enterScreen 2（bagpack + artifact_tab）+ 21 格 = 23；翻页 1 次（首页后指纹不变判到底）
        assertEquals(23, h.clicks.size)
        assertEquals(1, h.swipes.size)
    }

    @Test
    fun `first artifact fields complete and correct`() {
        val (engine, h) = runEngine(listOf(Page(syntheticFrame(5), pageLines("Lv.90"), 1026)))
        val a = engine.results[0]
        assertEquals("plume", a.slotKey)                       // "死之羽"
        assertEquals(5, a.rarity)                              // banner 橙 + 星带 5 格
        assertEquals(90, a.level)                              // "Lv.90"（extractValue 完整数字优先）
        assertEquals(true, a.lock)
        assertEquals(true, a.favorited)
        assertEquals("hp", a.mainStatKey)                      // "生命值" + "4,780"
        assertEquals(4780.0, a.mainStatValue, 1e-9)
        assertEquals(4, a.substats.size)
        assertEquals("critRate_", a.substats[0].key)
        assertEquals(5.8, a.substats[0].value, 1e-9)
        assertEquals("hp", a.substats[1].key)
        assertEquals("enerRech_", a.substats[2].key)
        assertEquals("eleMas", a.substats[3].key)
    }

    @Test
    fun `set_key reverse-derived from piece name via dictionary`() {
        val (engine, h) = runEngine(listOf(Page(syntheticFrame(5), pageLines("Lv.90"), 1026)))
        val a = engine.results[0]
        // "晨光的明誓" → pieceToSetId（词典 276 件）
        assertTrue("setKey should be reverse-derived, got ${a.setKey}", a.setKey != null && a.setKey.isNotEmpty())
    }

    @Test
    fun `readCount and enterScreen wiring`() {
        val (engine, h) = runEngine(listOf(Page(syntheticFrame(5), pageLines("Lv.90"), 1026)))
        assertEquals(1026, engine.vars.total)
        // 首两次点击 = enterScreen 链（bagpack 中心 2824,80 → artifact_tab 中心 250,415）
        assertEquals(2824 to 80, h.clicks[0])
        assertEquals(250 to 415, h.clicks[1])
        // 第一次格点击 = cell(0,0) 中心 (416+100, 297+126)=(516,423)
        assertEquals(516 to 423, h.clicks[2])
        // 翻页滑动 = §12.1 几何起点 (1858,1178) 上滑 dist=876 → (1858,302)
        // （旧写死坐标 (1614,1150)→(1614,274) 已由几何公式取代：落点从第 5 列卡中间移到末尾两卡间隙）
        assertEquals((1858 to 1178) to (1858 to 302), h.swipes[0])
    }

    @Test
    fun `crafted flag detected via purple banner`() {
        val (engine, h) = runEngine(listOf(Page(syntheticFrame(5), pageLines("Lv.90"), 1026)))
        // 合成帧画了祝圣紫三点 → crafted=true；emit 进度含 crafted
        assertTrue(engine.vars.crafted)
        assertTrue(h.progress.isNotEmpty())
    }

    // ---- 3★ 止扫闭环：第 2 页出现 3★+未强化 → stopWhen 触发 → 不入库、翻页停止 ----
    @Test
    fun `three-star stop marker halts after page completes`() {
        val page1 = Page(syntheticFrame(5), pageLines("Lv.90"), 1026)
        val page2 = Page(syntheticFrame(3, page = 1), pageLines("Lv.0"), 1030)
        val (engine, h) = runEngine(listOf(page1, page2))

        // 页 1：21 件 5★ 入库；页 2：rarity=3 → parsePanel 止扫不解析 → results 仍 21
        println("DIAG stop=${engine.vars.stopRequested} finished=${h.finished} clicks=${h.clicks.size} swipes=${h.swipes.size} results=${engine.results.size} rarity=${engine.vars.rarity} level=${engine.vars.level} pagesSeen=${h.pageIndex + 1} rarities=${engine.results.map { it.rarity }.distinct()}")
        assertEquals(21, engine.results.size)
        assertEquals(true, engine.vars.stopRequested)
        assertEquals("stopWhen", h.finished)
        // 页 1 翻页 1 次 + 页 2 遍历完后停止（不再 swipe）= 共 1 次
        assertEquals(1, h.swipes.size)
        // 页 2 仍完整遍历 21 格（scope=cell 本页后停）
        assertEquals(2 + 21 + 21, h.clicks.size)
        // 止扫页不入库：最后入库件仍是页 1 的 5★
        val lastArtifactProgress = h.progress.last { it.first == "artifact" }
        assertEquals(5, lastArtifactProgress.second["rarity"])
    }

    // ---- 健壮性：onZero 重进重试 / anchor 断言失败终止 ----

    /** readCount 读到 0 → 重跑入口链重进界面 → 再读一次 → total=1026。 */
    @Test
    fun `readCount onZero reopens screen and retries`() {
        val (engine, h) = runEngine(
            listOf(Page(syntheticFrame(5), pageLines("Lv.90"), 1026)),
            numberScript = listOf(0, 1026),
        )
        assertEquals(1026, engine.vars.total)
        // 重进后 clicks 应多出 enterScreen 链 2 次
        assertEquals(2 + 2 + 21, h.clicks.size)
    }

    /** anchor 断言 3 次重试仍失败 → ScanAbortedException 终止（不继续扫描）。 */
    @Test
    fun `anchor assertion failure aborts scan`() {
        // 页面无 count 文本 → anchor 正则不匹配 → 3 次重试后抛 ScanAbortedException
        val e = runCatching {
            runEngine(listOf(Page(syntheticFrame(5), pageLines("Lv.90", withCountLine = false), 1026)))
        }.exceptionOrNull()
        assertTrue("expected ScanAbortedException, got $e", e is ScanAbortedException)
    }
}
