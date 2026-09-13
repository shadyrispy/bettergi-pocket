package com.bettergi.pocket.scan

import com.bettergi.pocket.capture.FrameSource
import com.bettergi.pocket.capture.FrameTimeoutException
import com.bettergi.pocket.recognition.name.GoodNames
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    /**
     * 进入背包页的前置点击数（2026-09-13 起为 **5**）：
     * `enterScreen` #1 **1 击**（背包钮）+ `enterScreen` #2 **1 击**（圣遗物页签）
     * + `filterReset` **3 击**（漏斗 → 面板⟲重置 → 确认）。
     * ⚠️ 第 2 击（页签）是 2026-09-13 新增：实测**背包会记住上次打开的类别**（上一跑 weapon_scan 切到「武器」后，
     *    artifact_scan 点开背包直接落在武器页 ⇒ 原 `count` 锚点不成立、入口中止）。故固定补一击页签
     *    （选中态下重复点是 no-op）+ 用标题条确认「圣遗物」。
     */
    private val ENTER_CHAIN_CLICKS = 5

    /**
     * `readCount.onZero=reopenAndRetry` 重进时的点击数 = **1**（只重跑 `enterScreen` 的背包一击）。
     * ⚠️ 重进**刻意不重跑 `filterReset`**：筛选是游戏侧持久状态，进入本页时已复位过；
     *    重进再开合面板既多余，又多一次「点到页面按钮 ⇒ 弹出全屏模态面板」的风险敞口。
     */
    private val ENTER_CHAIN_REENTRY_CLICKS = 1


    companion object {
        /** engine.run() 单次 dry-run 上限（正常 <30s；超出即视为卡死，防整任务挂起）。 */
        private const val ENGINE_RUN_TIMEOUT_MS = 180_000L

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
        // ① 五星开关 pill（profiles.json 3200 基坐标 [2009,199,2126,260]，calibrate(3200,1440) 后不变）：
        //    pillState 取**中心 1/4 区**的金像素占比判态 ⇒ 画成金底 ⇒ 判 "on"，与 flow 的
        //    `dualStateButton ensure:"on"` 一致（直接通过、不点击 ⇒ 点击数断言不受影响）。
        //    ⚠️ 2026-09-12 实测：圣遗物背包该开关 **off/深藏青** 态 = 「只显示 5★」视图（列表仅 ~110 件），
        //    **on/金底** 态才是「全部圣遗物」⇒ 全量扫描必须 ensure=on（原写 off ⇒ 只扫到 110/933）。
        rect(m, 2009, 199, 2126, 260, Scalar(60.0, 180.0, 230.0))
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
        // ⑧ page>0：网格区首行整行画灰——模拟翻页后列表前进（否则网格指纹不变会被判到底）。
        //    ⚠️ 必须整行（x 到 2080）：翻页到底判据是「24×16 缩略图差异比例 ≤ GRID_SIMILAR_DIFF(0.10)」，
        //    只画单卡（200×253）差异仅 ~3% 会被误判到底（真实翻页位移 3/4 行 ≈ 75%）。
        if (page > 0) rect(m, 416, 297, 2080, 550, Scalar(128.0, 128.0, 128.0))
        // ⑨ page>=2：同一条灰带换成**更亮的灰**（几何完全不变 ⇒ 不影响相位测量/卡顶台阶），
        //    只为让「翻页到底」的指纹判据**不命中** ⇒ 才能测到「连续 2 个整页零新增才断言回卷」这条新路径
        //    （OCR 内容全同 ⇒ 第 3 页仍是重复件）。若两页像素全同，会被 reachedEnd 先收尾成 completed。
        if (page >= 2) rect(m, 416, 297, 2080, 550, Scalar(200.0, 200.0, 200.0))
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
        /** 当前「面板名」= 由**最后一次点击坐标**决定：同一格跨页同名（去重可判），
         *  且不受遍历前链式点击影响。初始值供首次点击前读取。 */
        private var lastNameCell = "晨光的明誓#0"

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

            override fun tap(x: Int, y: Int): Boolean {
                clicks += x to y
                return true
            }

            override fun click(x: Int, y: Int, durationMs: Long): Boolean {
                clicks += x to y
                // ★ 名字由点击坐标决定（非全局自增）：同一格跨页同名 → 跨页去重可判，
                //   且与遍历前的 enterScreen/setFilter 链式点击无关（曾用自增序号 → 页 1 序号被前置点击偏移，
                //   页 2 重置后错位 → 去重漏 2 件：expected 21 but was 23）。
                lastNameCell = "晨光的明誓#${x * 10000 + y}"
                return true
            }

            override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean {
                swipes += (fromX to fromY) to (toX to toY)
                // 滑动后画面切到下一页（模拟翻页后的新内容；末页保持不变 → 指纹判到底）
                if (pageIndex < pages.size - 1) pageIndex++
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
                    // ⚠️ 2026-09-11 修：原为 ${nameCounter++}（**每次读取**都变），而引擎
                    //    visit 的「面板已稳定」判据要求 `名字已变 && 连续两次读数一致` —— 每次读都变
                    //    使该条件永不成立 → 每格打满 PANEL_CHANGE_WAIT_MAX_MS(6000ms)。
                    //    21 格/页 × 6s ≈ 126s/页，多页用例 250~500s（实测 three-star 用例 498.8s），
                    //    表现为「单测跑不完」。改为**每次点击**推进（同格稳定、跨页因 swipe 重置而重号，
                    //    去重语义与原先完全一致）。
                    if (r == NAME_RECT) lastNameCell else pages[pageIndex].ocrLines[r]
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
        // filterReset 的面板开合判据：标题「圣遗物筛选」位于 profiles.json 的
        // `dialogs.filterPanel.anchorTitle = [179,50,419,104]`（3200 基坐标；calibrate(3200,1440) 后不变）。
        // 不给这条 ⇒ `panelOpen()` 恒 false ⇒ 复位链走「BACK → 重试 → 放弃」，重置/确认都不会点。
        base[FrameRect(179, 50, 419, 104)] = "圣遗物筛选"
        // assertScreen 的标题条（profiles.json 3200 基坐标 `screens._common.titleBar` = [157,21,1084,136]）：
        // 供「背包/圣遗物」⇒ artifact_scan 的标题锚点 / `assertScreen(expect="圣遗物")` 走通过路径。
        // ⚠️ **与 count 一起受 `withCountLine` 控制**：「anchor 失败应中止」那条用例靠"锚点必失败"验证
        //    abort 路径，而 artifact_scan 的**第一段锚点已改为标题条**（原为 count）⇒ 不喂它才会失败。
        if (withCountLine) base[FrameRect(157, 21, 1084, 136)] = "背包/圣遗物"
        return base
    }

    private data class RunResult(
        val engine: ScanEngine,
        val harness: Harness,
    )

    // ---- dry-run ----
    private fun runEngine(pages: List<Page>, dedupe: Boolean = false, numberScript: List<Int?> = emptyList()): RunResult {
        // ⚠️ 干跑**关闭翻页落地位移闭环**（`advloop=0`）：闭环靠 `VoteJudges.profileShift` 实测"这次滑动实际
        //    滚了多少 px"再补滑，而合成帧里页与页之间**不是平移关系**（只是加/改一条灰带）⇒ 互相关测出的
        //    位移没有物理意义 ⇒ 会凭空多出 1~3 次补滑，把"点击数/滑动数"这类**编排层断言**搅乱。
        //    闭环本身由 `VoteJudgesProfileShiftTest` 用**纯函数**（构造已知平移的剖面）覆盖。
        //    另：此处直接调 `engine.run()`，不经过 `ScriptRunner.startScan` 的 `TimingOverrides.apply`，
        //    故手动置位不会被复位；finally 里还原，避免污染其它用例。
        TimingOverrides.advanceLoop = 0
        try {
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
        // ⚠️ 护栏（2026-09-11）：engine.run() 出现过「无限等待」把整个单测任务挂死 1 小时+
        //    （jstack：Test worker TIMED_WAITING 停在 BlockingCoroutine.joinBlocking → 内部某处 delay 循环不退出）。
        //    加 withTimeout：卡死从「永久挂起」变成「失败 + 协程栈」，既防呆又能直接定位卡点。
        runBlocking { withTimeout(ENGINE_RUN_TIMEOUT_MS) { engine.run() } }
        return RunResult(engine, h)
        } finally {
            TimingOverrides.advanceLoop = 1
        }
    }

    // ---- 入库去重（Q2 决策）：两页同件 → 第二次出现跳过 ----
    @Test
    fun `duplicate artifacts across pages are deduped`() {
        val (engine, h) = runEngine(
            listOf(
                Page(syntheticFrame(5), pageLines("Lv.90"), 1026),
                Page(syntheticFrame(5, page = 1), pageLines("Lv.90"), 1030),
                Page(syntheticFrame(5, page = 2), pageLines("Lv.90"), 1034),
            ),
            dedupe = true,
        )
        // 页 1 入 21 件；页 2 同内容 → **整页零新增（第 1 次）⇒ 按 dupPageConfirm=2 不停止**，再翻一页；
        // 页 3 仍同内容（mock 页索引钳在末页）→ 连续第 2 个整页零新增 ⇒ 断言回卷 → stopWhen 停止。
        // （2026-09-12 语义变更：单次整页重复视为「滑空/半页重叠」，不再直接终止扫描
        //   —— 实测短推进会让重复计数跨页凑满阈值，导致 933 件的全量扫描在第 8 页误停；
        //   见 ScanEngine.dupRollbackConfirmed 的 KDoc 与 _audit/PIPELINE-FEASIBILITY.md §14.20）
        assertEquals(21, engine.results.size)
        assertEquals(2, h.swipes.size)
        assertEquals("stopWhen", h.finished)
    }

    @Test
    fun `full flow produces 21 artifacts`() {
        val (engine, h) = runEngine(listOf(Page(syntheticFrame(5), pageLines("Lv.90"), 1026)))
        assertEquals(21, engine.results.size)
        assertEquals("completed", h.finished)
        // 点击数：enterScreen 链 4 击（bagpack + filterRoundBtn + filterPanel.reset + filterPanel.ok）+ 21 格 = 25
        // （2026-09-12：旧链的 artifact_tab 已失效，改为进背包后复位筛选）
        assertEquals(ENTER_CHAIN_CLICKS + 21, h.clicks.size)
        // ⚠️ 单页 + 翻页后指纹不变 ⇒ 触发「翻页未生效」重发守卫：
        //    1 次正式翻页 + 3 次确认（GRID_END_RETRIES）= 4 次滑动。
        //    为什么需要（2026-09-12 实测）：约 1/17 页的翻页滑动**完全没落地**，而「指纹不变」无法区分
        //    「没落地」与「真的到底」⇒ 先用重发排除前者，连续 3 次不动才认定到底（否则整轮被提前收掉）。
        assertEquals(4, h.swipes.size)
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
        // 首击 = 背包锚点（2026-09-13 由 (2824,80) 改为交集中心 (2830,93)，兼容刘海机）；其后 3 击 = 筛选复位链（filterRoundBtn → filterPanel.reset → filterPanel.ok）
        assertEquals(2830 to 93, h.clicks[0])
        assertEquals(ENTER_CHAIN_CLICKS, h.clicks.size - 21)
        // 前置点击序列（profile 3200 基坐标中心）：
        //   0=背包(2830,93)（2026-09-13 跨设备修正：取两机图标交集中心） → 1=**圣遗物页签**(250,415) → 2=漏斗(399,1336) → 3=面板⟲重置(401,1337) → 4=面板确认(852,1337)
        // ⚠️ 第 1 击（页签）是 2026-09-13 新增：背包会**记住上次打开的类别**，不主动切页签就会落在「武器」页
        //    （实测 artifact_scan 入口因此中止）。顺序错了就会点到页面按钮（实测踩过「锁定辅助」全屏面板）。
        assertEquals(250 to 415, h.clicks[1])
        assertEquals(399 to 1336, h.clicks[2])
        assertEquals(401 to 1337, h.clicks[3])
        assertEquals(852 to 1337, h.clicks[4])
        // 第一次格点击 = cell(0,0) 中心 (416+100, 297+126)=(516,423)（索引 = 链长）
        assertEquals(516 to 423, h.clicks[ENTER_CHAIN_CLICKS])
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
        // 页 2 仍完整遍历 21 格（scope=cell 本页后停；⚠️ 回卷止扫才是立即停，见 ScanEngine 注释）
        assertEquals(ENTER_CHAIN_CLICKS + 21 + 21, h.clicks.size)
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
        // 重进后 clicks 应多出 enterScreen 链一轮（1 击；filterReset 不重跑，见 ENTER_CHAIN_REENTRY_CLICKS）
        assertEquals(ENTER_CHAIN_CLICKS + ENTER_CHAIN_REENTRY_CLICKS + 21, h.clicks.size)
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
