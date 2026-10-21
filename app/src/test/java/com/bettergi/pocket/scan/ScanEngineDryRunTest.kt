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
/**
 * DSL 资产目录（`src/main/assets/dsl`）。**文件级**：嵌套类 [Harness] 与文件级常量都要用
 * （Harness 是嵌套类而非 inner ⇒ 拿不到外层实例的成员函数，历史上已踩过一次）。
 */
private fun dslDir(): File {
    var dir = File(System.getProperty("user.dir") ?: ".")
    repeat(4) {
        val candidate = File(dir, "src/main/assets/dsl")
        if (candidate.isDirectory) return candidate
        dir = dir.parentFile ?: return@repeat
    }
    error("src/main/assets/dsl not found")
}

private fun dslAsset(name: String): File = File(dslDir(), name)

// ── 网格参数：**一律从被加载的 profile 推导，不许写死**（2026-09-20 用户要求）──
//   缘由：本文件所有"每页件数 / 点击数 / 去重件数"断言都是 `cols × traverseRows` 的函数。
//   写死 21（或 42、row*7+col）会在**换档/改网格**时静默错判，用例名与失败信息还会误导人。
//   现状：三档（3200 / 2560 / 2244）**实测均为 7 列 × 3 遍历行 = 21 格/页、4 行可见**
//   —— 2244 档 profile 注释记录了实测列左缘 275/445/616/787/957/1127/1297；
//      2026-09-20 又用该机截图做像素复核：列左缘实测 275/446/616/786/957/1127/1298，逐列吻合。
//   ⚠️ "三档同构"是**实测结论**、不是保证 ⇒ 由 `grid params are profile-derived and identical across tiers` 用例守着。
private val PROFILE_JSON: JSONObject = JSONObject(dslAsset("profiles.json").readText())
private val GRID_CFG: JSONObject = PROFILE_JSON.getJSONObject("grids").getJSONObject("artifact_backpack")
private val GRID_COLS: Int = GRID_CFG.getInt("cols")
private val GRID_TRAVERSE_ROWS: Int = GRID_CFG.getInt("traverseRows")
private val GRID_VISIBLE_ROWS: Int = GRID_CFG.getInt("visibleRows")

/** 一页**遍历（点击）**的卡格数 = 列数 × 遍历行数（可见行数多 1 行：末行只是滑动锚，不遍历）。 */
private val CELLS_PER_PAGE: Int = GRID_COLS * GRID_TRAVERSE_ROWS

private val CARD_ORIGIN: IntArray =
    GRID_CFG.getJSONArray("cardOrigin").let { a -> IntArray(a.length()) { a.getInt(it) } }
private val CARD_PITCH: IntArray =
    GRID_CFG.getJSONArray("pitch").let { a -> IntArray(a.length()) { a.getInt(it) } }

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
    private fun assetsDir(): File = dslDir()

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
        //    pillState 取**中心 1/4 区**的金像素占比判态（>50% ⇒ on）⇒ 画成**深藏青底**（金像素≈0）
        //    ⇒ 判 "off"，与 flow 的 `dualStateButton ensure:"off"`（2026-09-14 加回：pill=ON 时
        //    漏斗/排序被游戏禁用，必须先切 OFF）幂等匹配 ⇒ 0 击，entry 链点击数保持 5。
        //    ⚠️ 若画成金底（旧做法，服务已删除的 ensure:"on" 步骤）⇒ 判 on≠off ⇒ 重试点击 3 次
        //    ⇒ 干跑断言的 clicks 全部 +3（2026-09-15 实测 4 个用例因此失败）。
        rect(m, 2009, 199, 2126, 260, Scalar(120.0, 60.0, 20.0))
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

    private class Harness(
        pages: List<Page>,
        /**
         * #48 夹具开关：`true` = **第 `entryClicks + 1` 次点击（即网格里第一个格）不改面板名**，
         * 模拟"打开背包时游戏已自动选中第一件"⇒ 就绪判据看不到变化 ⇒ 只能靠「本轮首格豁免」收尾。
         * 没有这个开关时，名字路径的豁免分支在干跑里永远不执行（每条用例的名字都照常变）。
         */
        val firstCellNameStuck: Boolean = false,
        /** 入口链的点击数（`clicks.size` 用它区分"前置链点击"与"第一个格"）。 */
        val entryClicks: Int = 0,
        /**
         * ★ A11 夹具：**面板冻结注入**。`N > 0` 时第 2..N+1 格的第一次抓帧返回**上一格保存帧的克隆**
         * （模拟：点格后详情面板停在上格、面板指纹闸门等待期间才渲染出新面板）。
         * OCR 夹具按**帧身份**回放该帧被抓时的面板内容 —— 真机上解析旧帧读到的就是旧面板，
         * 这是对"闸门等到了新帧、解析却用旧帧 ⇒ contentKey 撞已入库件被去重吞掉"的最贴近注入。
         * 局限（真机差异）：真机的"冻结"是像素级渲染停滞，本夹具靠帧身份侧信道复放旧内容；
         * 判稳信号/点击时序仍由引擎真实代码驱动。
         */
        val freezeCells: Int = 0,
        /** A10 夹具：weapon_backpack 面板的名字 OCR 回放（须为 mappings 词典内的武器名）。 */
        val weaponNames: List<String>? = null,
    ) {
        var pageIndex = 0
            private set

        val clicks = ArrayList<Pair<Int, Int>>()
        val swipes = ArrayList<Pair<Pair<Int, Int>, Pair<Int, Int>>>()
        val progress = ArrayList<Pair<String, Map<String, Any?>>>()
        var finished: String? = null

        // 按格递增的唯一件名（模拟真实网格每件不同）；页切换时重置（模拟跨页同件重叠 → 去重测试）
        private val NAME_RECT = FrameRect(2230, 220, 2630, 270)
        private val SUB0_RECT = FrameRect(2230, 794, 2990, 848)
        private val SUB0_RECT_CRAFTED = FrameRect(2230, 857, 2990, 911)
        // ★ A10：weapon_backpack 面板的 name/level ROI（profiles.json 3200 基坐标，calibrate 后不变）
        private val WEAPON_NAME_RECT = FrameRect(2221, 214, 2728, 273)
        private val WEAPON_LEVEL_RECT = FrameRect(2210, 709, 2455, 763)
        /** 当前「面板名」= 由**最后一次点击坐标**决定：同一格跨页同名（去重可判），
         *  且不受遍历前链式点击影响。初始值供首次点击前读取。 */
        private var lastNameCell = "晨光的明誓#0"

        /** 最近一次点击的格序（row*7+col，0..20）——夹具用它制造"每格词条不同"。 */
        private var cellIdx = 0

        // readNumber 可编程脚本（onZero 重试等按次序变化场景）；空则回退页静态值
        val numberScript = ArrayDeque<Int?>()

        // ── A11 冻结注入状态 ──
        /** 帧身份 → 该帧被抓时的 (格序, 面板名)。OCR 夹具据此回放"这一帧里显示的是哪一格"。 */
        private val frameOcrState = HashMap<Int, Pair<Int, String>>()
        /** 第 i 格 post-click 帧的克隆（第 i+1 格的"冻结面板"素材）。 */
        private val frozenFrames = HashMap<Int, Mat>()
        /** 已服务到的最大冻结格序（每格只在 visit 的第一帧服务一次）。 */
        private var servedFreezes = 0

        val frameSource = object : FrameSource {
            /**
             * ★ 2026-09-16：**面板区域随点击变化**（此前按页固定 ⇒ 同页 21 格面板像素全同，
             *   与"每格 OCR 文本递增"的 mock 语义**自相矛盾**：真实里每张卡的面板就是不同的）。
             *   现在在面板指纹区域内画一个随点击数变化的红块 ⇒ 面板指纹闸门（PanelFingerprint）
             *   能正确区分"不同卡"；跨页重复格由**内容键**去重兜底（本 harness 的既有断言语义不变）。
             *   位置 (2960..2988, 900..904) 落在指纹区（subStats x2230..2990 / y794..1040）内，
             *   且避开全部像素判据区（锁 2811..2845、收藏 2900..2947、星带 y599..640、祝圣点 y703）。
             */
            override suspend fun grabFresh(afterTimestampMs: Long, timeoutMs: Long): Mat {
                // ★ A11：冻结服务 —— 第 k 格（k ∈ 1..freezeCells）visit 的第一次抓帧返回
                //   第 k−1 格保存帧的**新克隆**（旧克隆会被引擎释放，故每次服务都克隆）。
                //   重访（定点重读）也照常服务 ⇒ 冻结场景下重访救不回（与真机页级冻结一致）。
                if (freezeCells > 0) {
                    val visitingCell = clicks.size - entryClicks - 1
                    if (visitingCell in 1..freezeCells && servedFreezes < visitingCell &&
                        frozenFrames.containsKey(visitingCell - 1)
                    ) {
                        servedFreezes = visitingCell
                        val src = frozenFrames.getValue(visitingCell - 1)
                        val clone = src.clone()
                        frameOcrState[System.identityHashCode(clone)] =
                            frameOcrState.getValue(System.identityHashCode(src))
                        return clone
                    }
                }
                val m = pages[pageIndex].frame.clone()
                val tick = clicks.size
                // ⚠️ harness 是**嵌套类**（非 inner）⇒ 拿不到外层测试类的 rect 辅助函数，直接调 Imgproc
                org.opencv.imgproc.Imgproc.rectangle(
                    m,
                    org.opencv.core.Point(2960.0, 900.0),
                    org.opencv.core.Point((2964 + (tick % 7) * 4).toDouble(), 904.0),
                    org.opencv.core.Scalar(0.0, 0.0, 255.0),
                    -1,
                )
                if (freezeCells > 0) {
                    frameOcrState.putIfAbsent(System.identityHashCode(m), cellIdx to lastNameCell)
                    val visitingCell = clicks.size - entryClicks - 1
                    // 第 k 格 post-click 的第一帧 = 它的 ctx 收敛帧 ⇒ 存为下一格的冻结素材
                    if (visitingCell in 0 until freezeCells && !frozenFrames.containsKey(visitingCell)) {
                        val saved = m.clone()
                        frozenFrames[visitingCell] = saved
                        // 克隆是新对象 ⇒ 帧状态映射要**同帧登记**（服务/重访时按克隆身份回放）
                        frameOcrState[System.identityHashCode(saved)] = cellIdx to lastNameCell
                    }
                }
                return m
            }

            override fun markActionAt(timestampMs: Long) = Unit
            override fun acquireLatestBgr() = throw FrameTimeoutException(0, 0) // dry-run 不走该路径
            override fun discardLatestImages() = Unit
            override fun capturedSize(): Pair<Int, Int>? = 3200 to 1440
            override fun isRunning(): Boolean = true
            override fun release() = Unit
        }

        val actions = object : ScanEngine.ActionGateway {
            override fun back(): Boolean = true

            // ★ 2026-09-19：引擎的**格点击已改纯 tap**（A 实验），故 tap 必须与 click 有同样的记账
            //   （记坐标 + lastNameCell + cellIdx）；否则夹具里"同一格跨页同名"的去重判据失效
            //   ⇒ 三个测试误挂（42→62 等）。两者对测试语义等价，直接委托。
            override fun tap(x: Int, y: Int): Boolean = click(x, y, 180L)

            override fun click(x: Int, y: Int, durationMs: Long): Boolean {
                clicks += x to y
                // ★ 名字由点击坐标决定（非全局自增）：同一格跨页同名 → 跨页去重可判，
                //   且与遍历前的 enterScreen/setFilter 链式点击无关（曾用自增序号 → 页 1 序号被前置点击偏移，
                //   页 2 重置后错位 → 去重漏 2 件：expected 21 but was 23）。
                lastNameCell = "晨光的明誓#${x * 10000 + y}"
                // 格序（row*cols+col）：供 sub0 制造"每格各不相同的真实字段差异"（名字已不参与去重键）
                // ⚠️ 用**被加载 profile 的真实网格几何**（本档 cardOrigin/pitch/cols/traverseRows）——
                //    早先误写 294（列距）⇒ 21 格里 3 格算出同一 (row,col) ⇒ 夹具件数 21→18 ✗。
                val col = ((x - CARD_ORIGIN[0]) / CARD_PITCH[0]).coerceIn(0, GRID_COLS - 1)
                val row = ((y - CARD_ORIGIN[1]) / CARD_PITCH[1]).coerceIn(0, GRID_TRAVERSE_ROWS - 1)
                cellIdx = row * GRID_COLS + col
                // #48：首格豁免夹具 —— 第一个格的点击**不**推进面板名（其余照旧）
                if (firstCellNameStuck && clicks.size == entryClicks + 1) return true
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
                    // ★ 2026-09-16（用户定稿"名字不能作为去重依据"后）：**名字出键** ⇒ 夹具若仍只靠
                    //   名字区分 21 格，去重会把它们并成 1 件（判据失真）⇒ 这里让 **sub0 也随之变化**
                    //   （由同一点击坐标派生 ⇒ 同一格跨页取同值、不同格不同值，与真实"每件词条不同"一致）。
                    // ★ A11：**按帧身份回放** —— 冻结帧（上一格保存帧的克隆）在真机里 OCR 读到的就是
                    //   旧面板 ⇒ 这里按帧被抓时的 (格序, 名字) 回放，使"解析旧帧 ⇒ 读到旧内容"成立。
                    //   未注入冻结时映射为空 ⇒ 与原行为逐位一致（cellIdx/lastNameCell 直取）。
                    val st = frameOcrState[System.identityHashCode(frame)]
                    val stateCell = st?.first ?: cellIdx
                    val stateName = st?.second ?: lastNameCell
                    val sub0 = "暴击率+%.1f%%".format(5.4 + stateCell * 0.1) // 每格一个不同值（$CELLS_PER_PAGE 格/页）
                    when (r) {
                        NAME_RECT -> stateName
                        WEAPON_NAME_RECT -> weaponNames?.getOrNull(stateCell % weaponNames.size)
                        WEAPON_LEVEL_RECT -> "Lv.90"
                        SUB0_RECT, SUB0_RECT_CRAFTED -> sub0
                        else -> pages[pageIndex].ocrLines[r]
                    }
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

    /**
     * ★ A10：weapon_scan 干跑的静态 OCR 行。名字/等级由 [Harness] 按**格序**回放
     * （[Harness.WEAPON_NAME_RECT] / [Harness.WEAPON_LEVEL_RECT]），这里只放位置静态的字段：
     *   · titleBar：weapon_scan 两段 enterScreen 的锚点（第一段 expect「背包」、第二段 expect「武器」）；
     *   · count [2603,60,2846,98]：readCount 走 readNumber 夹具（ocrNumber），不消费本行 —— 仅备查。
     */
    private fun weaponLines(): Map<FrameRect, String> = mapOf(
        FrameRect(157, 21, 1084, 136) to "背包/武器",
        FrameRect(2603, 60, 2846, 98) to "武器 ${CELLS_PER_PAGE}/${CELLS_PER_PAGE}",
    )

    /** ★ A10：从 mappings.json 取 CELLS_PER_PAGE 个真实武器名（词典必命中 ⇒ key != null）。 */
    private fun weaponDictNames(): List<String> {
        val arr = JSONObject(File(assetsDir(), "tools/mappings.json").readText())
            .getJSONArray("weapons")
        val names = (0 until arr.length())
            .map { arr.getJSONObject(it).getJSONObject("n").getString("zh") }
            .distinct()
        check(names.size >= CELLS_PER_PAGE) { "词典武器名不足 $CELLS_PER_PAGE 个" }
        return names.take(CELLS_PER_PAGE)
    }

    private data class RunResult(
        val engine: ScanEngine,
        val harness: Harness,
    )

    // ---- dry-run ----
    private fun runEngine(
        pages: List<Page>,
        dedupe: Boolean = false,
        numberScript: List<Int?> = emptyList(),
        maxPages: Int = Int.MAX_VALUE,
        /** #83：前台闸门探针。null = 不判（默认，保持既有用例行为不变）。 */
        foregroundOk: (() -> Boolean?)? = null,
        /** #48：把第一个格做成"名字不变"，用来逼出名字路径的**首格豁免**分支。 */
        firstCellNameStuck: Boolean = false,
        /** ★ A10：flow 文件（weapon_scan 回归用）；默认仍是 artifact_scan。 */
        flowFile: String = "flows/artifact_scan.json",
        /** ★ A9：flow JSON 变换（干跑注入 pageSkip 等；不改 assets 原文件）。 */
        flowTransformer: ((JSONObject) -> Unit)? = null,
        /** ★ A11：面板冻结注入格数（见 [Harness.freezeCells]）。 */
        freezeCells: Int = 0,
        /** ★ A10：weapon 面板名字回放表（须为 mappings 词典内武器名）。 */
        weaponNames: List<String>? = null,
        /** ★ P3-8：外部注入 plan（foreach over $plan 用；配合 flowTransformer 包 foreach）。 */
        plan: List<JSONObject>? = null,
    ): RunResult {
        // ⚠️ 合成帧里页与页之间**不是平移关系**（只是加/改一条灰带）⇒ fpband 落地条带测量无物理意义，
        //    会走 Reject → 自动回退特征锁（fail-safe，不补滑、不改滑动编排）⇒ "点击数/滑动数"断言不受影响。
        //    fpband/判据本身由 LandingShiftTest / LandingDecisionTest 用合成平移帧（纯函数）覆盖。
        //    此处直接调 `engine.run()`，不经过 `ScriptRunner.startScan`。
        // ★ 2026-09-19：**关掉入口幂等**——合成帧会让锚点直接命中 ⇒ 入口链点击被跳过 ⇒
        //   下面所有"点击数"断言（ENTER_CHAIN_CLICKS + 21 …）都会失配。夹具要的是"完整走一遍入口链"。
        TimingOverrides.entryIdempotent = false
        val profile = ScreenProfile(JSONObject(File(assetsDir(), "profiles.json").readText()))
        profile.calibrate(3200, 1440)
        val flow = JSONObject(File(assetsDir(), flowFile).readText())
        flowTransformer?.invoke(flow)
        // 单一名称词典（角色/武器/套装/单件/词条/部位）——与生产同路径
        // 词典解析失败**不许吞成 null**：那会让整批干跑静默换一条代码路径
        // （NameMatcher 全灭但断言照样绿），而随包词典就在那里、坏了一定是改坏了。
        val names = GoodNames.fromJson(JSONObject(File(assetsDir(), "tools/mappings.json").readText()))

        val h = Harness(pages, firstCellNameStuck, ENTER_CHAIN_CLICKS, freezeCells, weaponNames)
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
            // 2026-09-26：翻页重发回路删除后，`dedupe=false` 的用例不再有"指纹不变⇒收尾"这条出口
            // （去重链永不命中、计数器 1026 也达不到）⇒ 需要显式页数的用例用 maxPages 钉住。
            maxPages = maxPages,
            foregroundOk = foregroundOk,
            plan = plan,
        )
        // ⚠️ 护栏（2026-09-11）：engine.run() 出现过「无限等待」把整个单测任务挂死 1 小时+
        //    （jstack：Test worker TIMED_WAITING 停在 BlockingCoroutine.joinBlocking → 内部某处 delay 循环不退出）。
        //    加 withTimeout：卡死从「永久挂起」变成「失败 + 协程栈」，既防呆又能直接定位卡点。
        runBlocking { withTimeout(ENGINE_RUN_TIMEOUT_MS) { engine.run() } }
        return RunResult(engine, h)

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
        assertEquals(CELLS_PER_PAGE, engine.results.size)
        assertEquals(2, h.swipes.size)
        assertEquals("stopWhen", h.finished)
    }

    /**
     * ★ P3-8（语义裁决 2026-10-01）`resetScanAccumulation` 新语义：results 系列（results/
     * resultsWeapons/resultsCharacters）与 seenArtifactKeys **同进退、都不清** ——
     * GOOD 结果跨目标全局去重累加（不重复、不丢件）。
     *
     * 背景：`resetScanPerItem` 的旧实现清 results/resultsWeapons/resultsCharacters 但**不清**
     * seenArtifactKeys ⇒ 多目标（foreach）共用一套圣遗物时，目标 2 重扫的同件全部撞 seen 键
     * 被去重，而 results 已被清空 ⇒ 最终导出 0 件（丢件）。该"清 results 但留 seen"组合已被
     * 2026-10-01 裁决**禁止**：`artifact_lock` 多目标共用一套圣遗物时，锁定等动作逐目标执行；
     * 导出按物理件去重、跨目标累加。
     *
     * 本用例钉死新语义（双向）：
     *   · 目标 2 重扫同件 ⇒ 撞 seenArtifactKeys 去重，**不重复入库**（results 不会变 2×21）；
     *   · 目标 2 走查照常完整执行（止扫计数/页间游标每目标全新，回卷判据不被旧目标残留触发）；
     *   · 最终导出 = 物理件数（CELLS_PER_PAGE，21 件不丢不重）。
     * 旧"现状 0"断言已按裁决作废；若有人再改 reset 去**清** results 或 seenArtifactKeys，
     * 本用例会红（0 或 2×CELLS_PER_PAGE），强制先复核 foreach 多目标语义。
     */
    @Test
    fun `resetScanPerItem dedupes identical content across targets and keeps all physical pieces`() {
        val wrapInForeach: (JSONObject) -> Unit = { flow ->
            val steps = flow.getJSONArray("steps")
            val inner = org.json.JSONArray()
            for (i in 0 until steps.length()) inner.put(steps.get(i))
            val foreach = JSONObject()
                .put("do", "foreach")
                .put("over", "plan")
                .put("as", "task")
                .put("resetScanPerItem", true)
                .put("steps", inner)
            flow.put("steps", org.json.JSONArray().put(foreach))
        }
        val (engine, h) = runEngine(
            listOf(
                Page(syntheticFrame(5), pageLines("Lv.90"), 1026),
                Page(syntheticFrame(5, page = 1), pageLines("Lv.90"), 1030),
                Page(syntheticFrame(5, page = 2), pageLines("Lv.90"), 1034),
            ),
            dedupe = true,
            flowTransformer = wrapInForeach,
            plan = listOf(JSONObject(), JSONObject()), // 两个"相同内容"目标
        )
        // 裁决语义（2026-10-01）：目标 1 入库 21 件；目标 2 复扫同件全部撞 seen 键被去重
        // ⇒ results 不增长也不被清 ⇒ 最终 = 物理件数（21 件不丢不重）。
        // 双向锁：清 seenArtifactKeys ⇒ 2×CELLS_PER_PAGE（重复）；清 results ⇒ 0（丢件）。
        assertEquals(CELLS_PER_PAGE, engine.results.size)
        assertTrue("foreach 两个目标都应执行完", h.finished != null)
    }

    @Test
    fun `full flow produces one page of artifacts`() {
        // 计数器 = 一页件数 ⇒ 收尾走**新的主判据**（已入库 ≥ 计数器），不再靠"翻页指纹不变"那条
        // 已在 2026-09-26 删除的重发回路收尾（它当时顺带充当了本用例的终止条件）。
        val (engine, h) = runEngine(listOf(Page(syntheticFrame(5), pageLines("Lv.90"), CELLS_PER_PAGE)))
        assertEquals(CELLS_PER_PAGE, engine.results.size)
        assertEquals("completed", h.finished)
        // 点击数：enterScreen 链 4 击（bagpack + filterRoundBtn + filterPanel.reset + filterPanel.ok）+ 21 格 = 25
        // （2026-09-12：旧链的 artifact_tab 已失效，改为进背包后复位筛选）
        assertEquals(ENTER_CHAIN_CLICKS + CELLS_PER_PAGE, h.clicks.size)
        // ⚠️ 本用例断言**一次滑动都不该发生**（2026-09-26 方案 C 定稿）：
        //    计数器=一页件数 ⇒ 本页点完即「已入库 ≥ 计数器」，收尾判据在**发翻页滑动之前**就成立。
        //    旧断言是 4（1 正式翻页 + 3 次「指纹没变⇒重发」确认）—— 那条重发回路正是 09-26 小米 15 上
        //    静默跳页（一页滑两次 ⇒ 每次丢约 21 件）的源头，已删除；一次翻页有且只滑一次。
        assertEquals(0, h.swipes.size)
    }

    /**
     * #48：**名字路径**（干跑恒定走这条 —— `FrameSource.sampleSignature` 是接口默认实现返回 false
     * ⇒ `sigUsed=false`）原先缺签名路径的两条闸门。这里造出「打开背包时游戏已自动选中第一件」
     * 那个真机形态：点第一个格**面板名不会变** ⇒ 就绪判据看不到变化。
     *
     * 补闸门之前：这被当成"点击被吞" ⇒ 同坐标白烧 `CLICK_RETRY_MAX_ON_NOCHANGE(5)` 次重发
     * （点击数 = 5 + 21 + 5）；补之后走「本轮首格豁免」⇒ 一次点击都不多。
     * ⚠️ 本用例**只在豁免分支存在时**才成立：断言的是点击数，不是"有没有报错"，
     *    所以分支被删掉时会直接红（不是静默通过）。
     */
    @Test
    fun `name path exempts the pre-selected first cell without retrying`() {
        val (engine, h) = runEngine(
            listOf(Page(syntheticFrame(5), pageLines("Lv.90"), CELLS_PER_PAGE)),
            firstCellNameStuck = true,
        )
        assertEquals(ENTER_CHAIN_CLICKS + CELLS_PER_PAGE, h.clicks.size)
        assertEquals(CELLS_PER_PAGE, engine.results.size)   // 豁免 ≠ 跳过：首格仍要入库
    }

    /**
     * 断言依据守卫（2026-09-20 用户要求）：本文件所有"每页件数/点击数"断言都由
     * `CELLS_PER_PAGE = cols × traverseRows` 推导 ⇒ 该值必须与**当前被加载的 profile**一致。
     *
     * ★ 2026-09-24 改：原断言还要求**三档 cols 同构**，该前提已被实测推翻。
     *   2560 档的背包第 7 列是**幻影格**（列 x 1646..1842 压在详情面板左缘 1704 之下，点不到卡），
     *   留着会让圣遗物静默去重、武器产生重复条目（实测 21 把报告 / 18 把真实）⇒ 已改为 6 列。
     *   3200 与 2244 档实测确有 7 列。所以 cols **按档取值**，而行数（traverseRows/visibleRows）
     *   三档同构这条仍然承重 —— 行级闭环/翻页判据都按行数推导，任一档改动此用例先红。
     */
    @Test
    fun `grid params are profile-derived and identical across tiers`() {
        // 每档实测列数（2560=6 的理由见上；改档必须同步这里，逼出一次显式确认）
        val expectedCols = mapOf(
            "profiles.json" to 7,
            "profiles_2560x1440.json" to 6,
            "profiles_2244x1080.json" to 7,
        )
        for ((t, wantCols) in expectedCols) {
            val grids = JSONObject(File(assetsDir(), t).readText()).getJSONObject("grids")
            val g = grids.getJSONObject("artifact_backpack")
            assertEquals("$t: artifact cols", wantCols, g.getInt("cols"))
            // 行数三档同构（承重不变量）
            assertEquals("$t: traverseRows", GRID_TRAVERSE_ROWS, g.getInt("traverseRows"))
            assertEquals("$t: visibleRows", GRID_VISIBLE_ROWS, g.getInt("visibleRows"))
            // 武器背包与圣遗物同布局：#23 的幻影列两档同时中招，单独守一条防回归
            assertEquals("$t: weapon cols", wantCols, grids.getJSONObject("weapon_backpack").getInt("cols"))
        }
        // 可见行数必须 > 遍历行数（最后一行是滑动锚，只用于"翻页是否生效"的判据）
        assertTrue("visibleRows 应大于 traverseRows", GRID_VISIBLE_ROWS > GRID_TRAVERSE_ROWS)
        // 本文件其余断言的推导基准 = **被加载的那一档**（profiles.json）
        assertEquals("基准档 cols", GRID_COLS, expectedCols.getValue("profiles.json"))
        assertEquals("每页遍历格数", GRID_COLS * GRID_TRAVERSE_ROWS, CELLS_PER_PAGE)
    }

    @Test
    fun `first artifact fields complete and correct`() {
        // 计数器 = 一页件数 ⇒ 主判据在第 1 页末就成立（断言对象是 results[0]，页 1 已产出）。
        // ⚠️ 这里原先给 1026：夹具只喂 1 页 ⇒ "已入库 ≥ 计数器"永不成立 ⇒ 引擎再翻一页，
        //    而第 2 页 `pageIndex` 钳在末页 ⇒ 同格名字不变 ⇒ 每格都打满
        //    `PANEL_CHANGE_WAIT_MAX_MS(6000)` ⇒ 本用例实测 166.6s（改后 ~15s，与
        //    `full flow produces one page of artifacts` 同量级）。断言不变，只是不再白扫一页。
        val (engine, h) = runEngine(listOf(Page(syntheticFrame(5), pageLines("Lv.90"), CELLS_PER_PAGE)))
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
        // 夹具的 sub0 现按**格序**制造差异（格 0 ⇒ 5.4）；名字已不参与去重键（用户 2026-09-16 定稿）
        assertEquals(5.4, a.substats[0].value, 1e-9)
        assertEquals("hp", a.substats[1].key)
        assertEquals("enerRech_", a.substats[2].key)
        assertEquals("eleMas", a.substats[3].key)
    }

    @Test
    fun `set_key reverse-derived from piece name via dictionary`() {
        // 计数器同 `first artifact fields…`：给可达值，别让用例白扫一页（那页每格等满 6s 超时）。
        val (engine, h) = runEngine(listOf(Page(syntheticFrame(5), pageLines("Lv.90"), CELLS_PER_PAGE)))
        val a = engine.results[0]
        // "晨光的明誓" → pieceToSetId（词典 276 件）
        assertTrue("setKey should be reverse-derived, got ${a.setKey}", a.setKey != null && a.setKey.isNotEmpty())
    }

    @Test
    fun `readCount and enterScreen wiring`() {
        // 本用例只验入口链与首格坐标 ⇒ maxPages=1 钉住（计数器 1026 是断言对象，不能改小）
        val (engine, h) = runEngine(
            listOf(Page(syntheticFrame(5), pageLines("Lv.90"), 1026)),
            maxPages = 1,
        )
        assertEquals(1026, engine.vars.total)
        // 首击 = 背包锚点（2026-09-13 晚：BS@3200 实机复核后**回退为实测值** (2824,80)——交集中心 (2830,93) 只对 2244 档（双机）成立）；其后 3 击 = 筛选复位链（filterRoundBtn → filterPanel.reset → filterPanel.ok）
        assertEquals(2824 to 80, h.clicks[0])
        assertEquals(ENTER_CHAIN_CLICKS, h.clicks.size - CELLS_PER_PAGE)
        // 前置点击序列（profile 3200 基坐标中心）：
        //   0=背包(2824,80)（BS@3200 模板匹配 0.9970 实测） → 1=**圣遗物页签**(250,415) → 2=漏斗(399,1336) → 3=面板⟲重置(401,1337) → 4=面板确认(852,1337)
        // ⚠️ 第 1 击（页签）是 2026-09-13 新增：背包会**记住上次打开的类别**，不主动切页签就会落在「武器」页
        //    （实测 artifact_scan 入口因此中止）。顺序错了就会点到页面按钮（实测踩过「锁定辅助」全屏面板）。
        assertEquals(250 to 415, h.clicks[1])
        assertEquals(399 to 1336, h.clicks[2])
        assertEquals(401 to 1337, h.clicks[3])
        assertEquals(852 to 1337, h.clicks[4])
        // 第一次格点击 = cell(0,0)：x = 416+100 = 516；y = 297 + clickDy130 = **427**
        //   （★2026-09-25 #56：3200 档 artifact_backpack 标定出实测可点带 [0,260] ⇒ 锚从 cardH/2=126 挪到带中心 130）
        assertEquals(516 to 427, h.clicks[ENTER_CHAIN_CLICKS])
        // 翻页滑动 = **profile 字面 from/to**（产线默认路径）。
        // ⚠️ 2026-09-24 起 `geoAdvance` **只切换起点**：距离一律走 `planMainSwipe(advTarget, 1.0, drift, …)`。
        //   起点 = geoAdvance ? advanceStart（最左卡缝中点，本档 (638,1178)） : advance.from（(1614,1150)）。
        //   字面路径下 advTarget = |to.y − from.y| = 1150−274 = **876 = 3×292（恰 3 行）**，首页
        //   pageDrift=0 ⇒ cmd = min(876, maxCmd=1090) 再封顶 target = 876 ⇒ 终点 = 1150−876 = 274，
        //   与"照抄 from/to"逐像素相同 ⇒ 本断言钉的是**统一后仍等价**这条不变量。
        //   （`advance.extra`/`dist` 只挂在几何距离上，字面起点下会被忽略并打 warn。）
        assertEquals((1614 to 1150) to (1614 to 274), h.swipes[0])
    }

    @Test
    fun `crafted flag detected via purple banner`() {
        // 计数器同上：祝圣紫在页 1 的每一格里都会被投出来，不需要第二页。
        val (engine, h) = runEngine(listOf(Page(syntheticFrame(5), pageLines("Lv.90"), CELLS_PER_PAGE)))
        // 合成帧画了祝圣紫三点 → crafted=true；emit 进度含 crafted
        assertTrue(engine.vars.crafted)
        assertTrue(h.progress.isNotEmpty())
    }

    // ---- 3★ 纳入导出（2026-09-19 用户定稿）：3★ 正常解析入库、稀有度止扫已从流程撤掉 ----
    @Test
    fun `three-star pieces are parsed and emitted`() {
        // 计数器 = 两页件数 ⇒ 收尾走主判据（已入库 ≥ 计数器），且它是 break 不是 stopRequested
        // ⇒ 下面 `stopRequested == false` 的断言仍然成立。
        val page1 = Page(syntheticFrame(5), pageLines("Lv.90"), 2 * CELLS_PER_PAGE)
        // ★ 计数器是**背包全局总数**（`圣遗物 {n}/{cap}`），不随翻页变。引擎现在会在"件数达标"
        //   那一刻复读一次以挡住非零欠读（#84），所以两页必须给同一个数 —— 原先页2 给 1030
        //   等于让夹具扮演"总数会变大"，真机上不存在这种计数器。
        val page2 = Page(syntheticFrame(3, page = 1), pageLines("Lv.0"), 2 * CELLS_PER_PAGE)
        val (engine, h) = runEngine(listOf(page1, page2))

        println("DIAG stop=${engine.vars.stopRequested} finished=${h.finished} clicks=${h.clicks.size} swipes=${h.swipes.size} results=${engine.results.size} rarity=${engine.vars.rarity} level=${engine.vars.level} pagesSeen=${h.pageIndex + 1} rarities=${engine.results.map { it.rarity }.distinct()}")
        // 页 1：满页 5★；页 2：3★（starCount=3）→ 正常 emit ⇒ 共 2 页
        assertEquals(2 * CELLS_PER_PAGE, engine.results.size)
        assertEquals(listOf(3, 5), engine.results.map { it.rarity }.distinct().sorted())
        // 稀有度止扫已撤：3★ **不触发** stopRequested（收尾由「连续整页零新增 ⇒ 回卷止扫」负责）
        assertEquals(false, engine.vars.stopRequested)
    }

    // ---- 健壮性：onZero 重进重试 / anchor 断言失败终止 ----

    /** readCount 读到 0 → 重跑入口链重进界面 → 再读一次 → total=1026。 */
    @Test
    fun `readCount onZero reopens screen and retries`() {
        val (engine, h) = runEngine(
            listOf(Page(syntheticFrame(5), pageLines("Lv.90"), 1026)),
            numberScript = listOf(0, 1026),
            maxPages = 1,   // 同 `readCount and enterScreen wiring`：只验入口链，计数器 1026 是断言对象
        )
        assertEquals(1026, engine.vars.total)
        // 重进后 clicks 应多出 enterScreen 链一轮（1 击；filterReset 不重跑，见 ENTER_CHAIN_REENTRY_CLICKS）
        assertEquals(ENTER_CHAIN_CLICKS + ENTER_CHAIN_REENTRY_CLICKS + CELLS_PER_PAGE, h.clicks.size)
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

    /**
     * #83：页首前台闸门说"不是原神" ⇒ 整轮干净收尾（不是异常终止），已入库的件保留。
     *
     * 生产动机：用户把游戏切走/游戏被切到后台 ⇒ 注入侧已拦下动作，但引擎若不知情会按同页
     * 继续重试/回读，白烧几十秒且日志看不出原因。用户口径：**游戏切后台本来就会断线重登**
     * ⇒ 这一轮没有继续的意义。
     *
     * 探针是**逐格**调的（不只页首）：窗口切换事件异步到达 `:a11y`，页首那次取样常还在旧值上
     * （实测 page3 页首与 HOME 同秒 ⇒ 放行）。故本用例按**调用次数**编脚本：
     * 页首 1 次 + 首页 21 格 = 22 次放行，之后报 false。
     *
     * 断言：① 第 2 页页首即判 false ⇒ 第 2 页一格都不点（clicks 停在首页 21 格 + 入口链）；
     *      ② 首页已入库的 21 件仍在结果里（干净收尾，不是丢弃整轮）。
     */
    @Test
    fun `foreground gate failure stops run cleanly keeping emitted items`() {
        val allowed = 1 + CELLS_PER_PAGE // 页首 1 次 + 首页 21 格
        var probes = 0
        val (engine, h) = runEngine(
            listOf(
                // ⚠️ 计数器不能用 CELLS_PER_PAGE：那会让"已入库 ≥ 计数器"在首页末尾就成立
                //    ⇒ 永远到不了第 2 页的页首探针，本用例退化成空跑（首版就是这么写的）。
                Page(syntheticFrame(5), pageLines("Lv.90"), 1026),
                Page(syntheticFrame(6), pageLines("Lv.90"), 1026),
            ),
            foregroundOk = {
                probes += 1
                probes <= allowed
            },
        )
        assertEquals("首页应正常入库", CELLS_PER_PAGE, engine.results.size)
        // 第 2 页一格未点（只有入口链 + 首页 21 格）
        assertEquals(ENTER_CHAIN_CLICKS + CELLS_PER_PAGE, h.clicks.size)
        assertTrue("应正常收尾（非异常）", h.finished != null)
    }

    /**
     * #83（A 方案的核心场景）：用户**在一页的中途**把游戏切走 ⇒ 本页立即收尾，不再点后面的格。
     *
     * 这是逐格探针存在的理由 —— 只看页首的话，这一页剩下的 10 格会照样点下去（注入侧全被拦下，
     * 每格白等一轮 settle 超时）。脚本按调用次数：页首 1 次 + 前 10 格 = 11 次放行。
     */
    @Test
    fun `foreground gate failure mid page stops clicking remaining cells`() {
        val allowed = 1 + 10
        var probes = 0
        val (engine, h) = runEngine(
            listOf(
                Page(syntheticFrame(5), pageLines("Lv.90"), 1026),
                Page(syntheticFrame(6), pageLines("Lv.90"), 1026),
            ),
            foregroundOk = {
                probes += 1
                probes <= allowed
            },
        )
        assertEquals("只应入库已点过的 10 格", 10, engine.results.size)
        assertEquals(ENTER_CHAIN_CLICKS + 10, h.clicks.size)
        assertTrue("应正常收尾（非异常）", h.finished != null)
    }

    // ── A10：weapon_scan 收尾总数核对（旧代码只看 results.size ⇒ 武器轮必报假 total_mismatch）──

    /**
     * 计数器 = 一页件数 ⇒ 走「件数达标」正常收尾（completed）。旧实现 run() 收尾拿
     * `total != results.size`（results 只装圣遗物、恒 0）对账 ⇒ 每次武器扫描正常结束都报
     * WARN「总数不符」+ total_mismatch 进度。修法 = 对账口径与 pagedGrid 件数达标判据同源
     * （三容器之和；单轮只有一个 flow 域的容器在增长）。
     */
    @Test
    fun `weapon scan finishes without fake total mismatch`() {
        val (engine, h) = runEngine(
            listOf(Page(syntheticFrame(5), weaponLines(), CELLS_PER_PAGE)),
            flowFile = "flows/weapon_scan.json",
            weaponNames = weaponDictNames(),
        )
        assertEquals("武器应整页入库", CELLS_PER_PAGE, engine.resultsWeapons.size)
        assertTrue("武器轮不得产出圣遗物", engine.results.isEmpty())
        assertEquals(CELLS_PER_PAGE, engine.vars.total)
        assertEquals("completed", h.finished)
        assertTrue(
            "weapon_scan 正常收尾不得报 total_mismatch（stages=${h.progress.map { it.first }}）",
            h.progress.none { it.first == "total_mismatch" },
        )
    }

    // ── A11：面板指纹闸门等到新面板后必须**换帧解析**（旧代码解析仍用 ctx 旧帧 ⇒ 冻结格丢件）──

    /**
     * 面板冻结 2 格（第 2、3 格详情面板停在上格、闸门等待期间才渲染出新面板）：
     * 旧实现：闸门用 freshFrame 检出变化后只更新快照并 break，`parseArtifactPanel` 仍用 ctx 里
     * 那张旧帧 ⇒ 解析出上一格内容 ⇒ contentKey 撞已入库件被去重吞掉 ⇒ 21 件只剩 20 件。
     * 修法：闸门命中后把 ctx.frame 换成 f2 再解析 ⇒ 不丢件。
     *
     * ⚠️ 夹具局限见 [Harness.freezeCells]：真机的"冻结"是像素级渲染停滞，干跑用帧身份侧信道
     * 回放旧面板内容；指纹闸门/去重/重访链全部真实驱动。
     */
    @Test
    fun `frozen panel gate adopts fresh frame and does not drop the cell`() {
        val (engine, h) = runEngine(
            listOf(Page(syntheticFrame(5), pageLines("Lv.90"), CELLS_PER_PAGE)),
            dedupe = true,
            freezeCells = 2,
        )
        assertEquals("completed", h.finished)
        assertEquals(
            "面板冻结 2 格不得丢件（旧实现解析旧帧 ⇒ contentKey 撞已入库件被去重吞掉）",
            CELLS_PER_PAGE,
            engine.results.size,
        )
    }

    // ── A9：连续整页跳过（pageSkip）必须能干净收尾（计数器/页数上限双失灵时引擎自己终止）──

    /**
     * 干跑注入 `pageSkip.expr = "pageMinLevel < 20"`（不改动 assets 原文件）：
     * 页 0、页 1 正常扫描（页 1 全页 Lv.10 ⇒ pageMinLevel=10），之后每页整页跳过。
     * K=3（TimingOverrides.pageSkipLimit 默认）：页 2/3/4 连续 3 页全跳 ⇒ 第 4 页页首（抢帧前）
     * 干净收尾——已入库 42 件照常导出、正常 onFinished("stopWhen")、不再发第 5 次滑动。
     * ⚠️ 计数器给不可达的 1026（pagesByCount=54）、maxPages=6 只作红状态护栏：
     * 旧实现无 skip 上限 ⇒ 会翻到 maxPages 才以 "maxPages" 收尾（swipes=6），断言必红。
     */
    @Test
    fun `consecutive skipped pages terminate scan cleanly within limit`() {
        val pages = listOf(
            Page(syntheticFrame(5), pageLines("Lv.90"), 1026),           // 页 0：正常扫描
            Page(syntheticFrame(5, page = 1), pageLines("Lv.10"), 1026), // 页 1：正常扫描，pageMinLevel→10
            Page(syntheticFrame(5, page = 2), pageLines("Lv.10"), 1026), // 页 2：skip #1
            Page(syntheticFrame(5, page = 2), pageLines("Lv.10"), 1026), // 页 3：skip #2
            Page(syntheticFrame(5, page = 2), pageLines("Lv.10"), 1026), // 页 4：skip #3 ⇒ K=3 收尾
            Page(syntheticFrame(5, page = 2), pageLines("Lv.10"), 1026), // 页 5：红状态护栏页
        )
        val (engine, h) = runEngine(
            pages,
            maxPages = 6,
            flowTransformer = { flow ->
                val steps = flow.getJSONArray("steps")
                for (i in 0 until steps.length()) {
                    val s = steps.getJSONObject(i)
                    if (s.optString("do") == "pagedGrid") {
                        s.put("pageSkip", JSONObject().put("expr", "pageMinLevel < 20"))
                    }
                }
            },
        )
        assertEquals("前两页正常入库", 2 * CELLS_PER_PAGE, engine.results.size)
        assertEquals("应按正常止扫语义收尾（非报错/非 maxPages）", "stopWhen", h.finished)
        assertEquals(
            "页 0/1/2/3 各一次主滑，第 3 个连续 skip 页在页首抢帧前收尾（不再滑）",
            4,
            h.swipes.size,
        )
    }
}
