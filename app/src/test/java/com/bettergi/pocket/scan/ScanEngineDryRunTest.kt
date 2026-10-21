package com.bettergi.pocket.scan

import com.bettergi.pocket.capture.FrameSource
import com.bettergi.pocket.capture.FrameTimeoutException
import com.bettergi.pocket.recognition.name.GoodNames
import com.bettergi.pocket.scan.flows.runArtifactScanNative
import com.bettergi.pocket.scan.flows.runArtifactSteps
import com.bettergi.pocket.scan.flows.runWeaponScanNative
import com.bettergi.pocket.scan.forEachPlanItem
import kotlinx.coroutines.delay
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
    // R225 G160 B70：金谓词命中，同时满足面板锁的**暖色锁体**（R−G=65、R−B=155）——
    // 原 R200/G180 的 R−G 只有 20，是不存在于游戏锁徽上的低饱和金，会把锁票喂成未锁。
    private val GOLD = Scalar(70.0, 160.0, 225.0)
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
        /**
         * ★ S1/S2b 夹具：**面板裁片原点** (x0,y0)。
         * 开启 `panelCrop` 后，生产侧把每条 ROI 平移到**裁片坐标**（`sh()`），而本夹具的
         * [NAME_RECT] / [SUB0_RECT] / [pageLines] 键都是**帧坐标** ⇒ 裁片帧进来的 rect 要先加回
         * 原点才能查表。像素侧不用管：裁片是真 `copyTo` 出来的子图，星级/卡格读到的仍是同一片像素。
         * 传 (0,0) = 不裁（同步路径现状）。
         */
        val cropOrigin: Pair<Int, Int> = 0 to 0,
        /**
         * ★ #69 回归夹具：**逐格祝圣横幅状态**。非 null 时按 `cellIdx` 决定这一格的面板有没有
         * 祝圣紫横幅（false = 抹掉合成帧里的三个采样点）⇒ `vote(artifact.panel.zhusheng)`
         * 逐格读出 crafted 交替，才能暴露"生产者替下一格投票覆写了 vars.crafted"。
         * 周期必须是 4（不能是 2）：pipeDepth=2 时生产者的领先量正好是周期的一半，
         * 周期 2 会让串格读到的值**碰巧相同** ⇒ 用例空跑。
         */
        val craftedByCell: ((Int) -> Boolean)? = null,
        /**
         * ★ #253 夹具：**(页号, 页内格序) → 全局件号**。非 null 时面板名与 sub0 都改按件号派生
         * ⇒ 夹具能造出「页 2 前若干格 ≡ 页 1 尾若干格」这种**真重叠**（现状按点击坐标派生，
         * 页间同坐标 ⇒ 前进量恒 0，身份锚定只会给出被整行哨兵拒绝的 d）。null = 现状（按坐标）。
         */
        val pieceOf: ((pageIndex: Int, cellIdx: Int) -> Int)? = null,
        /**
         * ★ #260 注入器：**第 N 次格点击的抓帧完成后**，夹具替引擎再点一次「本格之后第 2 格」的坐标
         * （只点一下、不改引擎的控制流）。这就是管道臂在生产者领先时的真实形状——
         * 「一帧已经交出去了，harness 的点击进度又往前走了一格」。
         * 0 = 不注入。见 [strayClickDoesNotShiftFixtureContent]。
         */
        val strayClickAfterVisit: Int = 0,
    ) {
        var pageIndex = 0
            private set

        val clicks = ArrayList<Pair<Int, Int>>()
        val swipes = ArrayList<Pair<Pair<Int, Int>, Pair<Int, Int>>>()
        /**
         * ★ 2026-10-20：**真正推进页的 swipe**（只含“格点击之后的翻页滑”）。
         * `swipeGridToTop` 的 3 次回顶 swipe（#258：两轮 + 反向探针）发生在格点击之前，
         * 列进 [swipes] 供观察，但**不算翻页**、也不推进 mock 的页 —— 否则合成页会整体前移
         * （5★ 页读成 3★、42→21、首 swipe 方向反转那 8 条旧红）。断言翻页请用本表。
         */
        val advanceSwipes = ArrayList<Pair<Pair<Int, Int>, Pair<Int, Int>>>()
        private var gridClicked = false
        val progress = ArrayList<Pair<String, Map<String, Any?>>>()
        var finished: String? = null

        // 按格递增的唯一件名（模拟真实网格每件不同）；页切换时重置（模拟跨页同件重叠 → 去重测试）
        private val NAME_RECT = FrameRect(2230, 220, 2462, 270)   // 2026-10-09 ROI 瘦身定稿：只装 6 字，尾部由 NameMatcher 反向子串补
        private val SUB0_RECT = FrameRect(2264, 794, 2720, 848)   // ★ 2026-10-11 左缘裁到次字起点：= 2560 基准 [1744,2200]+520；行首圆点被裁、词条名完整保留
        private val SUB0_RECT_CRAFTED = FrameRect(2264, 857, 2720, 911)
        // ★ A10：weapon_backpack 面板的 name/level ROI（profiles.json 3200 基坐标，calibrate 后不变）
        private val WEAPON_NAME_RECT = FrameRect(2231, 214, 2470, 273)
        private val WEAPON_LEVEL_RECT = FrameRect(2210, 709, 2455, 763)
        /** 标定后的整帧宽度（与 [frameSource] 的 capturedSize 同源）；窄于此 ⇒ 收到的是裁片。 */
        private val fullFrameW = 3200
        /** 当前「面板名」= 由**最后一次点击坐标**决定：同一格跨页同名（去重可判），
         *  且不受遍历前链式点击影响。初始值供首次点击前读取。 */
        private var lastNameCell = "晨光的明誓#0"

        /** 最近一次点击的格序（row*7+col，0..20）——夹具用它制造"每格词条不同"。 */
        private var cellIdx = 0

        // readNumber 可编程脚本（onZero 重试等按次序变化场景）；空则回退页静态值
        val numberScript = ArrayDeque<Int?>()

        /**
         * ★ #260 后半：把"消费者正在干活但这一格慢"做成**定点**注入 —— 只有帧身份等于
         * [slowReadAt] = (页, 页内格序) 的**第一次** OCR 读取挂起 [slowReadMs] 毫秒。
         * 其余读取一律照旧。
         * ⚠️ 为什么必须定点而不是"每次都慢"：全线放慢会饿到格间就绪门
         * （`PANEL_CHANGE_WAIT_MAX_MS` 400ms 内读不到签名变化 ⇒ 判"点击被吞"⇒ 同坐标重发一次），
         * 实测全量并发跑下锚定臂因此多 1 击（43→44），量的又是套件并发度而不是本用例的语义。
         */
        var slowReadMs: Long = 0L
        var slowReadAt: Pair<Int, Int>? = null
        private var slowReadFired = false

        // ── A11 冻结注入状态 ──
        /** 帧身份 → 该帧被抓时的 (格序, 面板名)。OCR 夹具据此回放"这一帧里显示的是哪一格"。 */
        private val frameOcrState = HashMap<Int, Pair<Int, String>>()
        /** 第 i 格 post-click 帧的克隆（第 i+1 格的"冻结面板"素材）。 */
        private val frozenFrames = HashMap<Int, Mat>()
        /** 已服务到的最大冻结格序（每格只在 visit 的第一帧服务一次）。 */
        private var servedFreezes = 0

        // ── #260 多击注入状态 ──
        /** 注入只做一次（同一格会抓多帧）。 */
        private var strayDone = false

        /** 格序（row*cols+col）→ 格心坐标：与引擎同一套网格几何（本档 clickDy=130、列内 +100）。 */
        private fun cellCenter(cell: Int): Pair<Int, Int> {
            val col = cell % GRID_COLS
            val row = (cell / GRID_COLS).coerceIn(0, GRID_TRAVERSE_ROWS - 1)
            return (CARD_ORIGIN[0] + col * CARD_PITCH[0] + 100) to (CARD_ORIGIN[1] + row * CARD_PITCH[1] + 130)
        }

        // ── ★ #260 帧身份条（identity band）：内容随**帧**走，不随点击进度走 ──
        /**
         * 一行像素码画在**帧坐标** x 2332..2402 / y 905..910（12 槽、步距 6、槽宽 4）：
         * 槽 0 = MAGIC、槽 1..5 = 页内格序（LSB，31=无效）、槽 6 = MAGIC2、
         * 槽 7..10 = 页号（LSB，15=无效）、槽 11 = 那 9 个数据位的奇偶。
         *
         * **为什么只能走像素**：`parsePanel` 读的是**裁片**——同步臂是复用 Mat
         * （`ScanEngine.panelCropBuf`，逐格同一个 Java 对象）、管道臂的 `job.crop` 来自
         * `pipeJobPool`（归还后会被复用）⇒ `System.identityHashCode` **认不出**「这张裁片
         * 是哪一帧」，而 `frameOcrState` 那套帧身份侧信道在裁片路径上根本没有登记点。
         * 裁片是父帧的逐像素拷贝（`view.copyTo(...)`）、冻结帧是父帧的克隆 ⇒ 身份写进像素就能
         * 跟着进裁片、进克隆、进消费者，且**读侧零依赖**「读到这一刻为止点了几下」。
         *
         * 墨色取纯黑（BGR 0,0,0）：`VoteJudges.substatRowPending` 只数中性色
         * lum∈[90,150)（深灰=激活）与 [150,215)（中灰=待激活），黑(0) 与白(255) 都不入账
         * ⇒ 副词条三态判据逐位不变；`equippedRowPresent`(y1170..1210) 与 `countStars`(y599..640)
         * 也不在这条带上。`PanelFingerprint` 按原始字节比 ⇒ 只是多一个**逐格不同**的差异，
         * 闸门行为与既有红色 tick 标记一致（不变多、不变少）。
         * 位置：落在 `panels.artifact_backpack.subStats[1]`（2264..2720 × 858..912）行带内
         * ⇒ 同时在**两个面板的裁片并集**内；与既有 tick 标记包络 (2300..2328, 900..904) 不相交，
         * 也与武器 `refine` 的祝圣平移变体 (2285..2443, 842..900) 不相交（y 取 905..910 就是为了
         * 同时躲开这两块）。
         * 这些包含/不相交关系由 `FixtureRoiCouplingTest.identityBandLivesInsideThePanelCrop…` 钉住
         * （改 ROI 时会先在那里红，而不是让干跑静默退回点击进度读法）。
         */
        private val BAND_X0 = 2332
        private val BAND_TOP = 905
        private val BAND_BOT = 910
        private val BAND_PITCH = 6
        private val BAND_SLOT_W = 4
        private val BAND_SLOTS = 12
        private val CELL_BITS = 5
        private val PAGE_BITS = 4

        /** 编码上界：31 / 15 是「无效」哨兵 ⇒ 可编码 0..30 格、0..14 页（越界 ⇒ 不画 ⇒ 读侧回落到旧行为）。 */
        private val CELL_MAX = (1 shl CELL_BITS) - 2
        private val PAGE_MAX = (1 shl PAGE_BITS) - 2

        /** 把「这一帧显示的是哪一页哪一格」画进帧。越出编码范围 ⇒ 不画。 */
        private fun paintIdentityBand(m: Mat, page: Int, cell: Int) {
            if (cell < 0 || cell > CELL_MAX || page < 0 || page > PAGE_MAX) return
            val bits = bandBits(page, cell)
            for (i in 0 until BAND_SLOTS) {
                if (bits[i] != 1) continue
                val x = BAND_X0 + i * BAND_PITCH
                org.opencv.imgproc.Imgproc.rectangle(
                    m,
                    org.opencv.core.Point(x.toDouble(), BAND_TOP.toDouble()),
                    org.opencv.core.Point((x + BAND_SLOT_W - 1).toDouble(), BAND_BOT.toDouble()),
                    org.opencv.core.Scalar(0.0, 0.0, 0.0),
                    -1,
                )
            }
        }

        private fun bandBits(page: Int, cell: Int): IntArray {
            val bits = IntArray(BAND_SLOTS)
            bits[0] = 1
            bits[6] = 1
            var pop = 0
            for (i in 0 until CELL_BITS) {
                val b = (cell shr i) and 1
                bits[1 + i] = b
                pop += b
            }
            for (i in 0 until PAGE_BITS) {
                val b = (page shr i) and 1
                bits[7 + i] = b
                pop += b
            }
            bits[11] = pop % 2
            return bits
        }

        /**
         * 读帧自带的身份条 ⇒ (页号, 页内格序)。
         * 返回 null 的三种情形：帧里没有条（越出编码范围、或非 `grabFresh` 产出的帧）、
         * 条落在画外（收到的是比面板裁片更小的图）、magic/奇偶不符 ⇒ **回落到改前的读法**
         * （`frameOcrState` 帧身份 ⇒ 再回落 harness 活值），这样本改动对没画条的路径逐位无影响。
         */
        private fun readIdentityBand(frame: Mat): Pair<Int, Int>? {
            if (frame.empty() || frame.channels() < 3) return null
            val cropped = frame.cols() < fullFrameW
            val ox = if (cropped) cropOrigin.first else 0
            val oy = if (cropped) cropOrigin.second else 0
            val y = (BAND_TOP + BAND_BOT) / 2 - oy
            if (y < 0 || y >= frame.rows()) return null
            val bits = IntArray(BAND_SLOTS)
            for (i in 0 until BAND_SLOTS) {
                val x = BAND_X0 + i * BAND_PITCH + BAND_SLOT_W / 2 - ox
                if (x < 0 || x >= frame.cols()) return null
                val px = frame.get(y, x)
                bits[i] = if (px[0] < 64 && px[1] < 64 && px[2] < 64) 1 else 0
            }
            if (bits[0] != 1 || bits[6] != 1) return null
            var cell = 0
            var page = 0
            var pop = 0
            for (i in 0 until CELL_BITS) {
                val b = bits[1 + i]
                cell = cell or (b shl i)
                pop += b
            }
            for (i in 0 until PAGE_BITS) {
                val b = bits[7 + i]
                page = page or (b shl i)
                pop += b
            }
            if (bits[11] != pop % 2) return null
            if (cell > CELL_MAX || page > PAGE_MAX) return null
            return page to cell
        }

        val frameSource = object : FrameSource {
            /**
             * ★ 2026-09-16：**面板区域随点击变化**（此前按页固定 ⇒ 同页 21 格面板像素全同，
             *   与"每格 OCR 文本递增"的 mock 语义**自相矛盾**：真实里每张卡的面板就是不同的）。
             *   现在在面板指纹区域内画一个随点击数变化的红块 ⇒ 面板指纹闸门（PanelFingerprint）
             *   能正确区分"不同卡"；跨页重复格由**内容键**去重兜底（本 harness 的既有断言语义不变）。
             *   位置 (2300..2328, 900..904) 落在指纹区（subStats x2264..2720 / y794..1040）内，
             *   且避开全部像素判据区（锁 2811..2845、收藏 2900..2947、星带 y599..640、祝圣点 y703）。
             *   ⚠️ 2026-10-05 subStats ROI 收窄（2990→2660）后这里必须跟着挪：
             *   标记落在指纹区外 ⇒ 闸门永远等不到"面板已换"⇒ 本类 9 条用例各超时 180s。
             *   2026-10-10 #244 按 2560 基准把本档 ROI 摆回 [2238,2720]；2026-10-11 左缘再裁到 [2264,2720]
             *   ⇒ 标记仍在区内、也仍在锁/收藏区之外，无需再挪（标记左缘 2300 距 ROI 左缘 2264 有 36px）。
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
                // ★ #69 夹具：本格没有祝圣横幅 ⇒ 抹掉合成帧里的三个紫采样点（y703，
                //   在面板指纹区 subStats(y794+) 之外 ⇒ 不影响闸门，只影响 panelZhusheng 判据）
                if (craftedByCell != null && !craftedByCell(cellIdx)) {
                    org.opencv.imgproc.Imgproc.rectangle(
                        m,
                        org.opencv.core.Point(2386.0, 699.0),
                        org.opencv.core.Point(2414.0, 707.0),
                        org.opencv.core.Scalar(255.0, 255.0, 255.0),
                        -1,
                    )
                }
                // ⚠️ harness 是**嵌套类**（非 inner）⇒ 拿不到外层测试类的 rect 辅助函数，直接调 Imgproc
                org.opencv.imgproc.Imgproc.rectangle(
                    m,
                    org.opencv.core.Point(2300.0, 900.0),
                    org.opencv.core.Point((2304 + (tick % 7) * 4).toDouble(), 904.0),
                    org.opencv.core.Scalar(0.0, 0.0, 255.0),
                    -1,
                )
                // ★ #260：把「这一帧显示的是哪一页哪一格」**写进像素**（在冻结素材克隆之前画，
                //   克隆/裁片就都带着自己的身份 ⇒ 消费者晚读也不会读到别人家的读数）。
                paintIdentityBand(m, pageIndex, cellIdx)
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
                // ★ #260 注入点：本格帧**已经成型并交出**之后，替引擎多点一次「本格之后第 2 格」。
                //   这就是管道臂生产者领先时的真实形状：帧的内容属于本格，而 harness 的点击进度
                //   又往前走了一格。内容若按点击进度派生，从这里起**所有格的读数会整体平移**。
                //   （只动 harness 的状态，不动引擎的控制流 ⇒ 引擎照常按自己的序列点击。）
                if (strayClickAfterVisit > 0 && !strayDone &&
                    clicks.size - entryClicks == strayClickAfterVisit
                ) {
                    strayDone = true
                    val (sx, sy) = cellCenter((cellIdx + 2) % CELLS_PER_PAGE)
                    actions.click(sx, sy, 180L)
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
                // ★ 2026-10-20：区分「入口链点击」与「网格格点击」——只有后者之后的 swipe 才算翻页。
                //   回顶（swipeGridToTop）发生在格点击之前 ⇒ 不推进 mock 的 pageIndex。
                val inGrid = x in CARD_ORIGIN[0] until (CARD_ORIGIN[0] + GRID_COLS * CARD_PITCH[0]) &&
                    y in CARD_ORIGIN[1] until (CARD_ORIGIN[1] + GRID_TRAVERSE_ROWS * CARD_PITCH[1])
                if (inGrid) gridClicked = true
                // #48：首格豁免夹具 —— 第一个格的点击**不**推进面板名（其余照旧）
                if (firstCellNameStuck && clicks.size == entryClicks + 1) return true
                lastNameCell = "晨光的明誓#" +
                    (pieceOf?.invoke(pageIndex, cellIdx) ?: (x * 10000 + y))
                return true
            }

            override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean {
                swipes += (fromX to fromY) to (toX to toY)
                // ★ 2026-10-20：只有**格点击之后**的 swipe 才翻页（回顶的 3 次 swipe 在格点击之前）。
                //   滑动后画面切到下一页（模拟翻页后的新内容；末页保持不变 → 指纹判到底）
                if (gridClicked) {
                    advanceSwipes += (fromX to fromY) to (toX to toY)
                    // ★ 不回零：跳页页没有格点击，但它的“前进一页”仍是真翻页；
                    //   回零会让连续 skip 页的后续 swipe 不计（consecutive 用例 4→2）。
                    //   `gridClicked` 语义 = 本次扫描已经开始点格（回顶 swipe 全在其之前）。
                    if (pageIndex < pages.size - 1) pageIndex++
                }
                return true
            }
        }

        val ocr = object : OcrGateway {
            override suspend fun readNumber(frame: Mat, rect: FrameRect): Int? {
                if (numberScript.isNotEmpty()) return numberScript.removeFirst()
                // ★ #260：读数也按**帧自带的页号**回（活值 `pageIndex` 在管道臂/重滑场景里会比
                //   这一帧新 ⇒ 同一张帧能读出两个总数）。认不出条 ⇒ 维持旧的活值读法。
                return pages[readIdentityBand(frame)?.first ?: pageIndex].ocrNumber
            }

            override suspend fun readLines(frame: Mat, rects: List<FrameRect>): List<String> {
                // ★ #260（本轮改动的主断言点）：内容身份**优先从帧自带的身份条**读 ⇒
                //   「这一帧显示的是哪一页哪一格」是帧的属性，而不是「读到这一刻为止点了几下」。
                //   这一行代码把 #260 的后半（夹具身份模型耦合点击序列）与 §16.16(c) 里
                //   「一次多击 ⇒ 后面所有格读数整体平移」这条机理**断开**。
                val band = readIdentityBand(frame)
                // ★ #260 后半的**定点**注入：只把"这一页这一格"的第一次读取挂慢（见 [Harness.slowReadMs]）
                run {
                    val target = slowReadAt
                    if (slowReadMs > 0L && !slowReadFired && target != null &&
                        band?.first == target.first && band?.second == target.second
                    ) {
                        slowReadFired = true
                        delay(slowReadMs)
                    }
                }
                return rects.mapNotNull { r ->
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
                    //   #260 之后冻结帧的**像素**就带着上一格的条 ⇒ 这条映射只作为无条帧的回落。
                    val st = if (band != null) null else frameOcrState[System.identityHashCode(frame)]
                    val statePage = band?.first ?: pageIndex
                    val stateCell = band?.second ?: st?.first ?: cellIdx
                    // 名字后缀只是「这一格是谁」的标记（NameMatcher 走反向子串补全，不看后缀），
                    // 但必须与 `lastNameCell` 的初值不撞 ⇒ 条路径用 1000+格序（初值是 #0）。
                    val stateName = band?.let {
                        "晨光的明誓#" + (pieceOf?.invoke(statePage, stateCell) ?: (1000 + stateCell))
                    } ?: (st?.second ?: lastNameCell)
                    // #253 夹具：pieceOf 模式下 sub0 跟着**件号**走（名字后缀即件号），
                    // 同一件跨页读到同一身份；否则维持现状（按页内格序）。
                    val sub0Idx = pieceOf?.let { stateName.substringAfterLast('#').toIntOrNull() } ?: stateCell
                    val sub0 = "暴击率+%.1f%%".format(5.4 + sub0Idx * 0.1) // 每格一个不同值（$CELLS_PER_PAGE 格/页）
                    // ★ S1/S2b：裁片帧（宽 < 整帧）里的 rect 是**裁片坐标** ⇒ 平移回帧坐标再查表，
                    //   否则下面每一条具名 ROI 都匹配不上 ⇒ 全部读空（实测 pipe 轮 21 格 key 全 null）。
                    val rr = if (frame.cols() < fullFrameW) {
                        FrameRect(
                            r.left + cropOrigin.first, r.top + cropOrigin.second,
                            r.right + cropOrigin.first, r.bottom + cropOrigin.second,
                        )
                    } else {
                        r
                    }
                    when (rr) {
                        NAME_RECT -> stateName
                        WEAPON_NAME_RECT -> weaponNames?.getOrNull(stateCell % weaponNames.size)
                        WEAPON_LEVEL_RECT -> "Lv.90"
                        SUB0_RECT, SUB0_RECT_CRAFTED -> sub0
                        else -> pages[statePage.coerceIn(pages.indices)].ocrLines[rr]
                    }
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
        // ★ 2026-10-09 ROI 瘦身同步：rect 对齐 profiles.json 新标定（slot/mainValue 槽已删，
        //    slotKey 由「晨光的明誓」经 artifactPieces 反查，mainKey 由「生命值"+slot=plume」…）
        //    ⚠️ 夹具件名与 slot 的对应关系由词典决定：晨光的明誓 → circlet。
        // ★ 2026-10-11 左缘裁到次字起点：subStats 八条 x0 2238→2264（夹具与 profile 是**逐字相等**匹配，
        //    不改这里 ⇒ mock 返回空串）；name 槽保持 6 字定稿值不动。
        val base = mutableMapOf(
            FrameRect(2230, 220, 2462, 270) to "晨光的明誓",            // name（6字宽，定稿）
            FrameRect(2224, 444, 2340, 500) to "生命值",                // mainName（3字宽）
            FrameRect(2230, 716, 2340, 766) to levelText,              // level（未祝圣位置）
            FrameRect(2230, 779, 2340, 829) to levelText,              // level（crafted yShift+63 后）
            FrameRect(2264, 794, 2720, 848) to "暴击率+5.8%",           // sub0（原位）
            FrameRect(2264, 858, 2720, 912) to "生命值+717",            // sub1（原位）
            FrameRect(2264, 922, 2720, 976) to "元素充能效率+12.4%",     // sub2（原位）
            FrameRect(2264, 986, 2720, 1040) to "元素精通+23",           // sub3（原位）
            FrameRect(2264, 857, 2720, 911) to "暴击率+5.8%",           // sub0（crafted yShift+63）
            FrameRect(2264, 921, 2720, 975) to "生命值+717",            // sub1
            FrameRect(2264, 985, 2720, 1039) to "元素充能效率+12.4%",    // sub2
            FrameRect(2264, 1049, 2720, 1103) to "元素精通+23",          // sub3
        )
        if (withCountLine) base[FrameRect(2674, 63, 2874, 95)] = "圣遗物 1026/2400"
        // filterReset 的面板开合判据：标题「圣遗物筛选」位于 profiles.json 的
        // `dialogs.filterPanel.anchorTitle = [179,50,419,104]`（3200 基坐标；calibrate(3200,1440) 后不变）。
        // 不给这条 ⇒ `panelOpen()` 恒 false ⇒ 复位链走「BACK → 重试 → 放弃」，重置/确认都不会点。
        base[FrameRect(179, 50, 419, 104)] = "圣遗物筛选"
        // assertScreen 的标题条（profiles.json 3200 基坐标 `screens._common.titleBar` = [145,21,1084,136]；
        // ⚠️ 1d0629a 把 3200 titleBar 左沿 157→145，干跑夹具随之同步——否则锚点 OCR 全读空）：
        // 供「背包/圣遗物」⇒ artifact_scan 的标题锚点 / `assertScreen(expect="圣遗物")` 走通过路径。
        // ⚠️ **与 count 一起受 `withCountLine` 控制**：「anchor 失败应中止」那条用例靠"锚点必失败"验证
        //    abort 路径，而 artifact_scan 的**第一段锚点已改为标题条**（原为 count）⇒ 不喂它才会失败。
        if (withCountLine) base[FrameRect(145, 21, 1084, 136)] = "背包/圣遗物"
        return base
    }

    /**
     * ★ A10：weapon_scan 干跑的静态 OCR 行。名字/等级由 [Harness] 按**格序**回放
     * （[Harness.WEAPON_NAME_RECT] / [Harness.WEAPON_LEVEL_RECT]），这里只放位置静态的字段：
     *   · titleBar：weapon_scan 两段 enterScreen 的锚点（第一段 expect「背包」、第二段 expect「武器」）；
     *   · count [2603,60,2846,98]：readCount 走 readNumber 夹具（ocrNumber），不消费本行 —— 仅备查。
     */
    private fun weaponLines(): Map<FrameRect, String> = mapOf(
        FrameRect(145, 21, 1084, 136) to "背包/武器",
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
        /** ★ S2b：流水线队列深度（0=现状同步；>0 走生产者/消费者，强制 crop=1）。 */
        pipeDepth: Int = 0,
        /**
         * #69 口径修正：让**同步基线也开裁片**。不开的话 `pipeDepth>0` 一侧被强制 `crop=1`、
         * 另一侧 `crop=0` ⇒ "同序同值"同时在比「同步 vs 管道」和「不裁 vs 裁」两件事，
         * 失败无法归因（实际就误读过一次，见 dsl/docs/scan-pipeline-20261006.md §13.2）。
         */
        forceCrop: Boolean = false,
        /**
         * ★ simple 分支（spec §5）：非空 = 直调**原生流程入口**（不再走 engine.run()+flow JSON）。
         * 取值："weapon" / "artifact" / "character" / "lock" / "equip"；此时 flowFile 只用于
         * 裁片原点的面板键推导（JSON 资产删除后改为 nativePanelKey）。
         */
        nativeFlow: String? = null,
        /** 原生驱动时的面板键（裁片原点推导用）；null = 从 flowFile/nativeFlow 推。 */
        nativePanelKey: String? = null,
        /** 原生 artifact 的 pageSkip 注入（对应 JSON 路径的 flowTransformer 注入 pageSkip）。 */
        nativePageSkipExpr: String? = null,
        /** ★ #69 回归夹具：逐格祝圣横幅状态（见 [Harness.craftedByCell]）。 */
        craftedByCell: ((Int) -> Boolean)? = null,
        /** ★ #253 夹具：件号映射（见 [Harness.pieceOf]），造「页 2 前若干格 ≡ 页 1 尾若干格」的真重叠。 */
        pieceOf: ((Int, Int) -> Int)? = null,
        /** ★ #260 注入器：第 N 次格点击的抓帧完成后，夹具替引擎多点一格（见 [Harness.strayClickAfterVisit]）。 */
        strayClickAfterVisit: Int = 0,
        /** ★ #260 后半：把 (页, 页内格序) = [slowReadAt] 的第一次 OCR 挂慢 [slowReadMs] 毫秒。null/0 = 不注入。 */
        slowReadMs: Long = 0L,
        slowReadAt: Pair<Int, Int>? = null,
        /**
         * ★ 2026-10-20：默认把等待压到语义最小（干跑不依赖墙钟）。
         * 只有**依赖帧新鲜度/时序**的用例才传 true（frozen panel、S2b 锚点停等、strayClick）。
         * 只改等待，不改任何判据/坐标。
         */
        realTiming: Boolean = false,
    ): RunResult {
        // ⚠️ 合成帧里页与页之间**不是平移关系**（只是加/改一条灰带）⇒ fpband 落地条带测量无物理意义，
        //    会走 Reject → 自动回退特征锁（fail-safe，不补滑、不改滑动编排）⇒ "点击数/滑动数"断言不受影响。
        //    fpband/判据本身由 LandingShiftTest / LandingDecisionTest 用合成平移帧（纯函数）覆盖。
        //    此处直接调 `engine.run()`，不经过 `ScriptRunner.startScan`。
        // ★ 2026-09-19：**关掉入口幂等**——合成帧会让锚点直接命中 ⇒ 入口链点击被跳过 ⇒
        //   下面所有"点击数"断言（ENTER_CHAIN_CLICKS + 21 …）都会失配。夹具要的是"完整走一遍入口链"。
        // ★ 2026-10-20 干跑提速：语义用例不依赖墙钟，默认把等待压到 1ms/0。
        //   单页 21 格原来 ~21s 全花在 nav/pstable/spoll/sstable/prefresh 上；压后同一批断言不变。
        //   realTiming=true 的用例保留生产时序（帧新鲜度/管道进度那几条）。
        val prevNav = TimingOverrides.navSettleMs
        val prevPstable = TimingOverrides.panelStableFallbackMs
        val prevSpoll = TimingOverrides.settlePollMs
        val prevSstable = TimingOverrides.settleStableMs
        val prevSigpoll = TimingOverrides.panelSigPollMs
        val prevPrefresh = TimingOverrides.panelRefreshMs
        val prevPanelSettle = TimingOverrides.panelSettleMs
        val prevEntryIdem = TimingOverrides.entryIdempotent
        if (!realTiming) {
            TimingOverrides.navSettleMs = 1L
            TimingOverrides.panelStableFallbackMs = 1L
            TimingOverrides.settlePollMs = 1L
            TimingOverrides.settleStableMs = 1L
            TimingOverrides.panelSigPollMs = 1L
            TimingOverrides.panelRefreshMs = 0L
            TimingOverrides.panelSettleMs = 0L
        }
        TimingOverrides.entryIdempotent = false
        val profile = ScreenProfile(JSONObject(File(assetsDir(), "profiles.json").readText()))
        profile.calibrate(3200, 1440)
        val flow = if (nativeFlow != null) JSONObject() else JSONObject(File(assetsDir(), flowFile).readText())
        if (nativeFlow == null) flowTransformer?.invoke(flow)  // 原生驱动的注入走独立参数（如 nativePageSkipExpr）
        // 单一名称词典（角色/武器/套装/单件/词条/部位）——与生产同路径
        // 词典解析失败**不许吞成 null**：那会让整批干跑静默换一条代码路径
        // （NameMatcher 全灭但断言照样绿），而随包词典就在那里、坏了一定是改坏了。
        val names = GoodNames.fromJson(JSONObject(File(assetsDir(), "tools/mappings.json").readText()))

        val h = Harness(
            pages, firstCellNameStuck, ENTER_CHAIN_CLICKS, freezeCells, weaponNames,
            // ★ 裁片原点与生产**同源**（profile.panelCropRect）：parsePanel 的裁片分支就是以
            //   cropRect 的 left/top 作 origin 平移 ROI，夹具另算一份就会和实现对不上。
            cropOrigin = run {
                if (pipeDepth == 0 && !forceCrop) return@run 0 to 0
                val panelKey = nativePanelKey ?: when {
                    nativeFlow == "weapon" -> "weapon_backpack"
                    flowFile.contains("weapon") -> "weapon_backpack"
                    else -> "artifact_backpack"
                }
                profile.panelCropRect(panelKey)?.let { it.left to it.top } ?: (0 to 0)
            },
            craftedByCell = craftedByCell,
            pieceOf = pieceOf,
            strayClickAfterVisit = strayClickAfterVisit,
        )
        numberScript.forEach { h.numberScript.addLast(it) }
        h.slowReadMs = slowReadMs
        h.slowReadAt = slowReadAt
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
            clickDelayMs = if (realTiming) 10L else 0L,
            // 2026-09-26：翻页重发回路删除后，`dedupe=false` 的用例不再有"指纹不变⇒收尾"这条出口
            // （去重链永不命中、计数器 1026 也达不到）⇒ 需要显式页数的用例用 maxPages 钉住。
            maxPages = maxPages,
            foregroundOk = foregroundOk,
            plan = plan,
        )
        // ⚠️ 护栏（2026-09-11）：engine.run() 出现过「无限等待」把整个单测任务挂死 1 小时+
        //    （jstack：Test worker TIMED_WAITING 停在 BlockingCoroutine.joinBlocking → 内部某处 delay 循环不退出）。
        //    加 withTimeout：卡死从「永久挂起」变成「失败 + 协程栈」，既防呆又能直接定位卡点。
        val prevPipe = TimingOverrides.pipeDepth
        val prevCrop = TimingOverrides.panelCrop
        val prevExplicit = TimingOverrides.pipeExplicit
        val prevPrb = TimingOverrides.previewRowBackfill
        // 夹具只合成 3 行（18 格），没有第 4 行可补读；这一路径用真机全量轮次验收。
        TimingOverrides.previewRowBackfill = false
        TimingOverrides.pipeDepth = pipeDepth
        // ★ A③（#69 流程默认）：`ScanEngine.run()` 现在会按 flowName 给 artifact/weapon 填默认深度，
        //   把手就是 `pipeExplicit`。干跑里本用例集比的正是**传进来的那两档**（0 vs 2），
        //   所以必须声明成"显式给过"，否则 JSON 路径（nativeFlow=null，直接 engine.run()）的用例会
        //   被静默升成管道臂 —— 断言的还是同步期望，红法是随机的（#260 那类墙钟敏感假象的温床）。
        TimingOverrides.pipeExplicit = true
        // ★ #260 后半（2026-10-13）：这里**不再设 `panchor`**。锚点早到交付那条停等原来按墙钟计，
        //   满载复跑（load 12）实测会把预算烧光 ⇒ 管道臂多击一整条重叠带（`expected 43 but was 50`），
        //   当时是靠"测试里给一个等于不限的预算"遮掉的。现在它的上限改成"消费者连续多少轮询没有
        //   处理完任何 job"（ScanEngine.PIPE_ANCHOR_IDLE_POLLS 的 KDoc），负载只会让真实等待变长，
        //   所以干跑与真机用同一个默认值即可 —— 空载/满载两遍复跑是本条的验收。
        if (pipeDepth > 0 || forceCrop) TimingOverrides.panelCrop = true
        try {
            runBlocking {
                withTimeout(ENGINE_RUN_TIMEOUT_MS) {
                    if (nativeFlow != null) {
                        // ★ simple 分支（spec §5）：原生驱动——合成帧 + 原生流程入口直调。
                        when (nativeFlow) {
                            "weapon" -> engine.runWeaponScanNative()
                            "artifact" -> {
                                val p = plan
                                if (p != null) {
                                    // 对应 JSON 路径的 flowTransformer 包 foreach（resetScanPerItem 语义）
                                    engine.runPreamble()
                                    engine.forEachPlanItem(
                                        p, "task", resetPerItem = true,
                                        steps = listOf({ _ -> engine.runArtifactSteps(nativePageSkipExpr) }),
                                    )
                                    engine.runPostamble()
                                } else {
                                    engine.runArtifactScanNative(nativePageSkipExpr)
                                }
                            }
                            else -> error("未支持的原生驱动: $nativeFlow")
                        }
                    } else {
                        engine.run()
                    }
                }
            }
        } finally {
            TimingOverrides.pipeDepth = prevPipe
            TimingOverrides.panelCrop = prevCrop
            TimingOverrides.pipeExplicit = prevExplicit
            TimingOverrides.previewRowBackfill = prevPrb
            TimingOverrides.navSettleMs = prevNav
            TimingOverrides.panelStableFallbackMs = prevPstable
            TimingOverrides.settlePollMs = prevSpoll
            TimingOverrides.settleStableMs = prevSstable
            TimingOverrides.panelSigPollMs = prevSigpoll
            TimingOverrides.panelRefreshMs = prevPrefresh
            TimingOverrides.panelSettleMs = prevPanelSettle
            TimingOverrides.entryIdempotent = prevEntryIdem
        }
        return RunResult(engine, h)

    }

    // ---- S2b 流水线（#69）：pipe>0 与 pipe=0 必须同序同值 ----

    /**
     * 管道化**不得改变任何解析结果**：同一批夹具帧，pipe=0（同步）与 pipe=2（生产者/消费者）
     * 各跑一轮，断言 results 逐件相等、finished 相同、页数相同。
     * ★ 两臂都开 `crop=1`（`forceCrop`）：`pipeDepth>0` 会强制开裁片，基线若不开，这条就在
     *   同时比「同步 vs 管道」和「不裁 vs 裁」两件事 —— #69 那次因此把夹具 bug 误读成
     *   "页末排水时机"问题，白跑两轮（dsl/docs/scan-pipeline-20261006.md §13.2）。
     *   判据①（epoch 丢弃负对照 / 消费失败）由下面那条盯。
     */
    @Test
    fun `S2b 管道化与同步路径同序同值`() {
        fun run(pipe: Int) = runEngine(
            listOf(Page(syntheticFrame(5), pageLines("Lv.90"), CELLS_PER_PAGE)),
            pipeDepth = pipe,
            forceCrop = true,
            nativeFlow = "artifact",
        )
        val sync = run(0)
        val piped = run(2)
        val a = sync.engine.results.map { it.setKey to it.slotKey to it.substats.map { s -> s.key to s.value } }
        val b = piped.engine.results.map { it.setKey to it.slotKey to it.substats.map { s -> s.key to s.value } }
        assertEquals("同步 ${a.size} 件 vs 管道 ${b.size} 件", a.size, b.size)
        assertEquals("逐件内容（含顺序）必须一致", a, b)
        assertEquals(sync.harness.finished, piped.harness.finished)
    }

    /**
     * ★ #69 §16 根因回归：**逐格投票位不得串格**。
     *
     * 上面那条同序同值用例在真机缺陷面前是**空跑**的：合成帧每一格都画了祝圣紫横幅
     * ⇒ `vars.crafted` 恒 true ⇒ 生产者领先几格覆写的都是同一个值 ⇒ 两个臂照样相等。
     * 华为 2026-10-12 实测的就是这个字段被邻格覆写（dsl/docs/scan-pipeline-20261006.md §16）：
     * 消费者按上一格 `crafted=false` 却读到下一格的 true ⇒ 错加 47px 祝圣 yShift ⇒
     * 等级槽读到第一条副词条（`攻击力+16.3%`→`·攻式`）、副词条整体下移一行、末行读到套装名
     * ⇒ `level=0` + 少一条副词条 ⇒ 内容键变 ⇒ 去重当成新件（57 vs 56 件）。
     *
     * 夹具 [`Harness.craftedByCell`] 按**周期 4** 交替横幅（不是周期 2：pipeDepth=2 时生产者
     * 正好领先半周期，周期 2 会碰巧同值 ⇒ 又是一次空跑）。
     * 断言用 `crafted`/`favorited` 两个字段 —— 后随副词条文本在平移/非平移两处槽位都喂了同一份
     * mock 行，串格在"值"上不可见，只在**几何 + 这两位**上可见。
     */
    @Test
    fun `S2b 管道化不得把逐格投票位串到别的格`() {
        val craftedAt = { cell: Int -> cell % 4 < 2 }
        fun run(pipe: Int) = runEngine(
            listOf(Page(syntheticFrame(5), pageLines("Lv.90"), CELLS_PER_PAGE)),
            pipeDepth = pipe,
            forceCrop = true,
            nativeFlow = "artifact",
            craftedByCell = craftedAt,
        )
        fun facts(r: RunResult) = r.engine.results.map { (it.elixerCrafted to it.favorited) to it.level }

        val sync = facts(run(0))
        val piped = facts(run(2))
        // 正对照①：夹具确实让两种祝圣态都出现过（否则"两臂相等"恒真）
        assertTrue(
            "夹具应同时出现 crafted=true/false 两格，实际=$sync",
            sync.any { it.first.first } && sync.any { !it.first.first },
        )
        // 正对照②：同步臂的格数与两态配比（21 格 ⇒ 周期 4 里 11 真 / 10 假）
        assertEquals(CELLS_PER_PAGE, sync.size)
        assertEquals("同步臂 crafted 配比", 11, sync.count { it.first.first })
        // ★ 主断言：管道臂**逐格**同值（串格时这里会红：管道臂的 crafted 是邻格的）
        assertEquals("pipe=2 与 pipe=0 的逐格投票位/等级必须一致", sync, piped)
    }

    /**
     * S2b 判据①「epoch 丢弃负对照」（design-docs §10）：正常一轮里**不得有任何 job 被
     * 判过期/取消丢弃**，也不得有被 catch 吞掉的消费异常。
     *
     * 为什么需要它：#69 的根因一（纪元打了自增前的值 ⇒ `job.epoch < pipeSentEpoch[key]`
     * 对最新那件也恒成立 ⇒ 292 件全走 stale 分支）在"同序同值"那条上只表现为"件数不等"，
     * 归因不到丢弃；而干跑 `isReturnDefaultValues` 把 Android Log 全吞，`pipeProcess` 的
     * catch 与"读到空"原理上不可分 —— 这两点各由下面一条断言盯住。
     *
     * 两条断言的**可达性都做过故障注入实测**（不是"看着会红"）：
     * - 丢 1 件（`stale ||= job.id==3`）⇒ 红在第一条，且实测 results 变成 **41 而非 20**
     *   —— 漏一格会把页记账带偏成**重复入库**，比丢件更糟；
     * - 只加计数、job 照常处理（`addPipeDiscard()` 单注）⇒ 红在第二条
     *   （`expected:<0> but was:<1>`），证明第二条不是排在第一条后面就跑不到的死断言。
     */
    @Test
    fun `S2b 正常一轮不得丢弃 job 也不得消费失败`() {
        val r = runEngine(
            listOf(Page(syntheticFrame(5), pageLines("Lv.90"), CELLS_PER_PAGE)),
            pipeDepth = 2,
            nativeFlow = "artifact",
        )
        assertEquals("整页必须先全量入库，否则下面两条 0 是空跑出来的", CELLS_PER_PAGE, r.engine.results.size)
        assertEquals(
            "有 job 被判过期/取消丢弃 ⇒ 纪元打标或重访语义错",
            0L, PerfProbe.pipeDiscardCount(),
        )
        assertEquals(
            "消费者抛异常被 catch 吞掉 ⇒ 干跑里只有这条看得见",
            0L, PerfProbe.pipeFailCount(),
        )
    }

    /**
     * S2b 判据①「取消无泄漏」的**行为半边**（§6.1）：中途止扫（#83 逐格前台闸门）时管道里
     * 还有在途 job ⇒ 取消必须做到"只释放裁片、不 emit、不记账"。泄漏的表现就是
     * `results` 多于已点过的 10 格，所以这条盯的是**件数不被虚增**，不是"件数变少"。
     *
     * ⚠️ 别把这条当成"取消无泄漏全过了"：native Mat 是否真归还**不在本条观测面里**
     *   （JVM 干跑没有 Mat 泄漏哨兵），那一半只能靠真机 RSS / `pipeJobPool` 水位去量。
     *   两臂都开 `forceCrop` ⇒ 差异只剩 pipe 本身（见"同序同值"那条的口径说明）。
     */
    @Test
    fun `S2b 中途止扫不得把在途 job 记成入库件`() {
        val allowed = 1 + 10
        for (pipe in listOf(0, 2)) {
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
                pipeDepth = pipe,
                forceCrop = true,
                nativeFlow = "artifact",
            )
            // ★ 复核（2026-10-13）**收回**上一版把它放宽成区间 `[10-pipeDepth, 10]` 的改法：
            //   区间是想给"取消时丢弃在途 job"留余量，但今天核了一遍——**取消路径根本走不到**：
            //   `pipeCancel()` 全仓零调用（`pipeReset` 亦然），止扫只置 `stopRequested` 后 `break`，
            //   页末 `pipeJoinPage` 仍会把在途 job 全部消费完 ⇒ 这 10 格必然全 emit。
            //   少一件只可能来自真实丢失（消费者抛异常 / emit 缺失），而那正是本用例要抓的；
            //   放宽下界等于**把要抓的东西放过去**（还静默容忍 2 件丢失）。
            //   所以恢复 `== 10`，并把两条"少件只可能出自这里"的计数器一起断言，让红的时候直接归因：
            //   - `pipeDiscard` = 有过期/取消丢弃（纪元打标或重访语义错）；
            //   - `pipeFail` = 消费者抛异常被 catch 吞掉。
            //   满载跑曾见 `was 8`，那是 #253 锚点预算被打满导致点击数变少（前置探针多烧了几次）。
            //   #260 后半起那条预算不再是墙钟（改成"消费者有没有处理完 job"），干跑也不再设 `panchor`
            //   ⇒ 若这里再红且日志出现「锚点早到交付：连续 … 轮询没有处理完任何 job」，
            //   那就是消费者**真的**停了，不是机器忙。
            assertEquals("pipe=$pipe：只应入库已点过的 10 格（少了就是真丢件，不是「给了余量」）", 10, engine.results.size)
            assertEquals("pipe=$pipe：有过期/取消丢弃 ⇒ 纪元打标或重访语义错", 0L, PerfProbe.pipeDiscardCount())
            assertEquals("pipe=$pipe：消费者抛异常被吞 ⇒ 少件的唯一另一条路", 0L, PerfProbe.pipeFailCount())
            assertTrue("pipe=$pipe 应正常收尾（非异常）", h.finished != null)
        }
    }

    /**
     * ★ #253：pipe>0 时跨页重叠的**身份锚定**必须与同步臂一样命中。
     *
     * 缺陷形状：锚点读的生产者侧 `pageIdentities` 在 pipe>0 下按构造拿不到身份（解析在消费者里）
     * ⇒ 恒打「身份为空」⇒ 重叠格全部重点（真机实测每页多击数格 = +6.5% 功）。
     *
     * 夹具用 [Harness.pieceOf] 造出**真重叠**：页 2 的前 7 格 ≡ 页 1 的末 7 格（前进量 14）
     * ⇒ 锚点应命中 d=14 并**不点击**跳掉 idx3..6 共 4 格（idx0..2 是锚点本身，照常访问）。
     * ⚠️ 绝对点击数那条防「两臂一样烂」的空跑：修复前两臂若都不锚定，只比相等是绿的。
     * ⚠️ 每格真实挂钟 ~0.66s ⇒ 只跑 2 页、只断计数，不断言任何时序量。
     */
    @Test
    fun `S2b 管道臂跨页身份锚定与同步臂跳同一批格`() {
        val pieceOf = { page: Int, cell: Int ->
            when {
                page == 0 -> cell
                cell < GRID_COLS -> CELLS_PER_PAGE - GRID_COLS + cell // 页 2 前 7 格 ≡ 页 1 末 7 格
                else -> CELLS_PER_PAGE + cell - GRID_COLS
            }
        }
        fun run(pipe: Int) = runEngine(
            listOf(
                Page(syntheticFrame(5), pageLines("Lv.90"), 1026),
                Page(syntheticFrame(5, page = 1), pageLines("Lv.90"), 1026),
            ),
            pipeDepth = pipe,
            forceCrop = true,
            nativeFlow = "artifact",
            maxPages = 2,
            pieceOf = pieceOf,
        )
        val sync = run(0)
        val piped = run(2)
        val skipped = GRID_COLS - 3 // idx3..6：重叠带（7 格）扣掉锚点三格 ⇒ 4 格
        val expectedClicks = ENTER_CHAIN_CLICKS + 2 * CELLS_PER_PAGE - skipped
        assertEquals(
            "夹具前提：同步臂必须锚定命中并跳 $skipped 格（否则本用例是空跑）",
            expectedClicks, sync.harness.clicks.size,
        )
        assertEquals(
            "pipe=2 的做功必须与同步臂相同（锚定恒不命中时会多击 $skipped 格）",
            expectedClicks, piped.harness.clicks.size,
        )
        assertEquals("两臂必须跳掉同一批格（点击坐标逐格相同）", sync.harness.clicks, piped.harness.clicks)
        val a = sync.engine.results.map { it.setKey to it.slotKey to it.substats.map { s -> s.key to s.value } }
        val b = piped.engine.results.map { it.setKey to it.slotKey to it.substats.map { s -> s.key to s.value } }
        assertEquals("两臂入库逐件同序同值", a, b)
        assertEquals(sync.harness.finished, piped.harness.finished)
    }

    /**
     * ★ #260 后半的语义钉：锚点早到交付那条停等的放弃条件是"消费者有没有干活"，不是墙钟。
     *
     * 注入 = **定点**把页 2 第 0 格的第一次 OCR 挂慢 600ms（[runEngine] 的 `slowReadMs/slowReadAt`），
     * 作用是让"锚点问的那一刻在途必然 >0"变成确定事件，其余读取全速。两臂只差
     * `TimingOverrides.pipeAnchorIdlePolls`：
     * - **0（＝生产默认 45_000 次 ≈ 90s）** ⇒ 600ms 后那格处理完 ⇒ 有进展 ⇒ 不等满 ⇒
     *   锚定照常命中 ⇒ 页 2 不重访重叠带（做功与同步臂相同 = 43 击）；
     * - **1** ⇒ 进停等后第一次轮询看不到进展就收手 ⇒ 锚定退回「照常访问」⇒
     *   页 2 重访整条重叠带（43+4=47 击，正是历史上 load 12 那个 `43 vs 50` 的同形）。
     *
     * ⚠️ 为什么"定点"而不是"每次都慢"：全线放慢会饿到**格间就绪门**（400ms 内读不到签名变化 ⇒
     * 判点击被吞 ⇒ 同坐标重发），实测把锚定臂的绝对点击数从 43 抬成 44 —— 那量的又是套件并发度、
     * 不是本用例的语义（第一版这条在全量里就是这么红的）。断言因此**同时**钉总数与
     * "带内格的重数"，后者保证差值出自那条重叠带而不是随机多按了一下。
     *
     * ⚠️ 本用例不区分新旧规则（600ms 的单格延迟在旧的 90s 墙钟下同样不会烧穿预算）；
     * 新旧之差靠"进展计数随负载只松不紧"这个契约撑（`delay` 只会晚不会早，论证见
     * `ScanEngine.PIPE_ANCHOR_IDLE_POLLS` 的 KDoc），实证是空载/满载两遍全量
     * （dsl/docs/scan-pipeline-20261006.md §16.22，执行件 `dsl/dev/dryrun_load_ab.sh`）。
     */
    @Test
    fun `S2b 锚点停等按进展而不是墙钟决定放弃`() {
        val pieceOf = { page: Int, cell: Int ->
            when {
                page == 0 -> cell
                cell < GRID_COLS -> CELLS_PER_PAGE - GRID_COLS + cell
                else -> CELLS_PER_PAGE + cell - GRID_COLS
            }
        }
        fun run() = runEngine(
            listOf(
                Page(syntheticFrame(5), pageLines("Lv.90"), 1026),
                Page(syntheticFrame(5, page = 1), pageLines("Lv.90"), 1026),
            ),
            pipeDepth = 2,
            forceCrop = true,
            nativeFlow = "artifact",
            maxPages = 2,
            pieceOf = pieceOf,
            // 定点注入：页 2（索引 1）第 0 格的第一次 OCR 挂 600ms ⇒ 锚点问的那一刻在途必不为 0，
            // 而其余读取全速（"每次都慢"会饿到格间就绪门 ⇒ 多击一发，实测 43→44 就是这么来的）
            slowReadMs = 600L,
            slowReadAt = 1 to 0,
        )
        // 格序 → 格心坐标：与引擎/夹具同一套网格几何
        fun center(cell: Int): Pair<Int, Int> {
            val col = cell % GRID_COLS
            val row = (cell / GRID_COLS).coerceIn(0, GRID_TRAVERSE_ROWS - 1)
            return (CARD_ORIGIN[0] + col * CARD_PITCH[0] + 100) to (CARD_ORIGIN[1] + row * CARD_PITCH[1] + 130)
        }
        val band = (3 until GRID_COLS).toList()      // idx3..6：夹具造出的重叠带里"应被锚定跳掉"的那几格
        val anchorCell = 0                            // idx0：锚点本身，两臂都必须点（页 1 一次 + 页 2 一次）
        val prev = TimingOverrides.pipeAnchorIdlePolls
        try {
            TimingOverrides.pipeAnchorIdlePolls = 0L
            val alive = run()
            TimingOverrides.pipeAnchorIdlePolls = 1L
            val givenUp = run()
            val mult = { clicks: List<Pair<Int, Int>>, c: Pair<Int, Int> -> clicks.count { it == c } }
            val expected = ENTER_CHAIN_CLICKS + 2 * CELLS_PER_PAGE - band.size
            assertEquals(
                "默认上限：锚定命中 ⇒ 做功与同步臂相同（$expected 击）",
                expected, alive.harness.clicks.size,
            )
            assertEquals(
                "上限=1：放弃锚定 ⇒ 整条重叠带被重访（$expected+${band.size} 击）",
                expected + band.size, givenUp.harness.clicks.size,
            )
            // 对照：非带格的重数两臂必须相同 ⇒ 下面的差值不是"整臂都多按了几下"
            assertEquals(
                "对照格（锚点 idx0）重数必须相同，否则差值解释不成立",
                mult(alive.harness.clicks, center(anchorCell)),
                mult(givenUp.harness.clicks, center(anchorCell)),
            )
            for (idx in band) {
                val c = center(idx)
                assertEquals(
                    "带内格 idx$idx：放弃锚定后页 2 必须重访这一次（默认臂=$c 重数 " +
                        "${mult(alive.harness.clicks, c)}）",
                    mult(alive.harness.clicks, c) + 1,
                    mult(givenUp.harness.clicks, c),
                )
            }
            assertEquals(
                "整轮做功之差必须正好是那条重叠带（${band.size} 格）",
                alive.harness.clicks.size + band.size, givenUp.harness.clicks.size,
            )
            // 件数不受影响：跳过的是"同一件的第二面"，去重本来就不入库
            assertEquals(
                "两臂入库件数必须相同（锚定只省点击，不改内容）",
                alive.engine.results.size, givenUp.engine.results.size,
            )
        } finally {
            TimingOverrides.pipeAnchorIdlePolls = prev
        }
    }

    /**
     * ★ #260 的另一半：夹具的**逐格内容**必须是「这一帧显示的是哪一格」的函数，
     * 而不是「到目前为止点了几下」的函数。
     *
     * 旧夹具把件名/sub0 挂在 harness 的**活值**上（最后一次点击派生的 `cellIdx` / `lastNameCell`），
     * 于是任何一次多击——管道臂生产者领先、吞击重发、欠滑补滑——都会把**已经交出去的旧帧**
     * 的读数整体平移 ⇒ 满载复跑（load 12）下五条 S2b 用例红成「内容不一致」，
     * 量的其实是机器负载（dsl/docs/scan-pipeline-20261006.md §16.16(c)「没修的那半」）。
     *
     * 这里把「生产者领先一格点击」这件事**确定性化**：第 5 格的首帧成型后，夹具替引擎再点一次
     * 「本格之后第 2 格」（只推进 harness 状态，不动引擎的控制流）。
     * 断言三条：
     * ① 正对照——多击**确实发生了**（clicks 比对照轮多 1），否则这条是空跑；
     * ② 入库序列与对照轮**逐件相同**（旧夹具在 ①成立时必然红：第 5 格读到第 7 格的词条值）；
     * ③ 件数也相同（平移若撞上已入库的内容键，去重语义也会跟着变）。
     * 同步臂与管道臂各证一次。
     */
    @Test
    fun strayClickDoesNotShiftFixtureContent() {
        fun run(pipe: Int, stray: Int) = runEngine(
            listOf(Page(syntheticFrame(5), pageLines("Lv.90"), CELLS_PER_PAGE)),
            pipeDepth = pipe,
            forceCrop = true,
            nativeFlow = "artifact",
            strayClickAfterVisit = stray,
        )
        fun seq(r: RunResult) = r.engine.results.map {
            it.setKey to it.slotKey to it.substats.map { s -> s.key to s.value }
        }
        for (pipe in listOf(0, 2)) {
            val control = run(pipe, 0)
            val injected = run(pipe, 5)
            assertEquals(
                "夹具前提：第 5 格之后确实多了一次点击（否则本用例是空跑）",
                control.harness.clicks.size + 1, injected.harness.clicks.size,
            )
            assertEquals("pipe=$pipe：中途多一击不得改变入库序列（旧夹具挂在点击进度上 ⇒ 整体平移）", seq(control), seq(injected))
            assertEquals("pipe=$pipe：多一击也不得改变入库件数", control.engine.results.size, injected.engine.results.size)
        }
    }

    /**
     * #260 的**机理级**钉子（不起引擎、秒级）：**同一张帧再读一次，必须还是那一格的内容**，
     * 与「读到这一刻为止点了几下」无关。
     *
     * 上面那条端到端用例证明「多一击不改变入库序列」，但它要跑四轮 engine.run()；
     * 这条把同一件事钉在夹具自己身上，红的时候直接指出是哪一格被点心的进度带跑了。
     * 旧夹具在这里返回的是**最后一次点击**那格的 sub0（`cellIdx` 活值）⇒ 第二次读会平移 0.4%。
     */
    @Test
    fun fixtureContentFollowsTheFrameNotTheClickProgress() = runBlocking {
        val h = Harness(
            listOf(Page(syntheticFrame(5), pageLines("Lv.90"), CELLS_PER_PAGE)),
            entryClicks = ENTER_CHAIN_CLICKS,
        )
        // 与引擎同一套网格几何：格序 → 格心（本档 clickDy=130、列内 +100）
        fun center(cell: Int): Pair<Int, Int> {
            val col = cell % GRID_COLS
            val row = cell / GRID_COLS
            return (CARD_ORIGIN[0] + col * CARD_PITCH[0] + 100) to (CARD_ORIGIN[1] + row * CARD_PITCH[1] + 130)
        }
        val sub0Rect = FrameRect(2264, 794, 2720, 848)   // ≡ panels.artifact_backpack.subStats[0]
        val (x2, y2) = center(2)
        h.actions.click(x2, y2, 180L)
        val frame = h.frameSource.grabFresh(0L, 0L)
        val early = h.ocr.readLines(frame, listOf(sub0Rect))
        // 之后又点了别的格（进度前进），**重读同一张帧**
        val (x6, y6) = center(6)
        h.actions.click(x6, y6, 180L)
        val late = h.ocr.readLines(frame, listOf(sub0Rect))
        assertEquals("格 2 的 sub0 必须随**格身份**走（夹具按页内格序造值：5.4+2×0.1）", listOf("暴击率+5.6%"), early)
        assertEquals(
            "重读同一张帧不得跟着后续点击平移（旧夹具取最后一次点击的 cellIdx ⇒ 这里会读成格 6 的 6.0%）",
            early, late,
        )
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
            nativeFlow = "artifact",
        )
        // 页 1 入 21 件；页 2 同内容 → **整页零新增（第 1 次）⇒ 按 dupPageConfirm=2 不停止**，再翻一页；
        // 页 3 仍同内容（mock 页索引钳在末页）→ 连续第 2 个整页零新增 ⇒ 断言回卷 → stopWhen 停止。
        // （2026-09-12 语义变更：单次整页重复视为「滑空/半页重叠」，不再直接终止扫描
        //   —— 实测短推进会让重复计数跨页凑满阈值，导致 933 件的全量扫描在第 8 页误停；
        //   见 ScanEngine.dupRollbackConfirmed 的 KDoc 与 _audit/PIPELINE-FEASIBILITY.md §14.20）
        assertEquals(CELLS_PER_PAGE, engine.results.size)
        assertEquals(2, h.advanceSwipes.size)
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
            nativeFlow = "artifact",
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
        val (engine, h) = runEngine(
            listOf(Page(syntheticFrame(5), pageLines("Lv.90"), CELLS_PER_PAGE)),
            nativeFlow = "artifact",
        )
        assertEquals(CELLS_PER_PAGE, engine.results.size)
        assertEquals("completed", h.finished)
        // 点击数：enterScreen 链 4 击（bagpack + filterRoundBtn + filterPanel.reset + filterPanel.ok）+ 21 格 = 25
        // （2026-09-12：旧链的 artifact_tab 已失效，改为进背包后复位筛选）
        assertEquals(ENTER_CHAIN_CLICKS + CELLS_PER_PAGE, h.clicks.size)
        // ⚠️ 本用例断言**一次滑动都不该发生**（2026-09-26 方案 C 定稿）：
        //    计数器=一页件数 ⇒ 本页点完即「已入库 ≥ 计数器」，收尾判据在**发翻页滑动之前**就成立。
        //    旧断言是 4（1 正式翻页 + 3 次「指纹没变⇒重发」确认）—— 那条重发回路正是 09-26 小米 15 上
        //    静默跳页（一页滑两次 ⇒ 每次丢约 21 件）的源头，已删除；一次翻页有且只滑一次。
        assertEquals(0, h.advanceSwipes.size)
    }

    /**
     * #234：**表头计数小于一页格数 ⇒ 只点那么多格、一次都不翻**。
     * 立项依据是 BS 新账号（6 件圣遗物）实测：主判据拿 `collected`（已入库）比 `total`，
     * 而 1★/2★ 按设计不入库 ⇒ 永不达标 ⇒ 42 格点满、36 格是空格，
     * `吞击重发=150 点击放弃=30格`。上游 GOODScanner 用计数当**格数**上界
     * （`backpack_scanner.rs:1372-1379`），6 件 = 6 次点击、0 次滑动。
     * ⚠️ 本用例在**改动前必红**（旧版会点满 21 格），所以它同时是可达性证明：
     *   604 条全绿时 `计数上界` 那条日志在结果里一条都没有 ⇒ 分支根本没走到。
     */
    @Test
    fun countBelowOnePageBoundsTheClicksAndSkipsPaging() {
        // 第三参是 `ocrNumber`（readCount 的读数），格数由网格几何固定给 21 ⇒
        // "表头说 6 件、屏幕上一页 21 格"这个真机形状正好复现。
        val (engine, h) = runEngine(
            listOf(Page(syntheticFrame(5), pageLines("Lv.90"), 6)),
            nativeFlow = "artifact",
        )
        assertEquals(6, engine.results.size)
        assertEquals(ENTER_CHAIN_CLICKS + 6, h.clicks.size)
        assertEquals(0, h.advanceSwipes.size)
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
            nativeFlow = "artifact",
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
            // ★ 2026-10-19：1280x720 由 2560 换算 ⇒ 列数同 2560（同 16:9 布局，
            //   6 列的理由也同：第 7 列同样压在详情面板左缘下 = 幻影格）。
            "profiles_1280x720.json" to 6,
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
        val (engine, h) = runEngine(
            listOf(Page(syntheticFrame(5), pageLines("Lv.90"), CELLS_PER_PAGE)),
            nativeFlow = "artifact",
        )
        val a = engine.results[0]
        assertEquals("plume", a.slotKey)                       // 「晨光的明誓」经 artifactPieces 反查（slot 槽已删）
        assertEquals(5, a.rarity)                              // banner 橙 + 星带 5 格
        assertEquals(90, a.level)                              // "Lv.90"（extractValue 完整数字优先）
        assertEquals(true, a.lock)
        assertEquals(true, a.favorited)
        assertEquals("atk", a.mainStatKey)                     // 死之羽主词条恒 atk（slot 消歧，mainValue 槽已删）
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
        val (engine, h) = runEngine(
            listOf(Page(syntheticFrame(5), pageLines("Lv.90"), CELLS_PER_PAGE)),
            nativeFlow = "artifact",
        )
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
            nativeFlow = "artifact",
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
        assertEquals((1614 to 1150) to (1614 to 274), h.advanceSwipes[0])
    }

    @Test
    fun `crafted flag detected via purple banner`() {
        // 计数器同上：祝圣紫在页 1 的每一格里都会被投出来，不需要第二页。
        val (engine, h) = runEngine(
            listOf(Page(syntheticFrame(5), pageLines("Lv.90"), CELLS_PER_PAGE)),
            nativeFlow = "artifact",
        )
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
        val (engine, h) = runEngine(listOf(page1, page2), nativeFlow = "artifact")

        // 页 1：满页 5★；页 2：3★（starCount=3）→ 正常 emit ⇒ 共 2 页
        assertEquals(2 * CELLS_PER_PAGE, engine.results.size)
        assertEquals(listOf(3, 5), engine.results.map { it.rarity }.distinct().sorted())
        // 稀有度止扫已撤：3★ **不触发** stopRequested（收尾由「连续整页零新增 ⇒ 回卷止扫」负责）
        assertEquals(false, engine.vars.stopRequested)
    }

    // ---- 健壮性：onZero 重进重试 / anchor 断言失败终止 ----

    /**
     * ★ #219：dup 参数在本轮**就是 flow 声明的值**（artifact = (3,1)、weapon = (3,1)）。
     *
     * 为什么钉"值"而不是钉"时机"：判据（`dupRollbackConfirmed`，每页只判一次、`dupPageDecided` 闩住）
     * 是**在格循环里**读的，而各 flow 现在把登记写在 `pagedGridNative(...)` **前一行**（源码里可见），
     * 时机由此在构造上成立；干跑里没有"第 0 格 visit 之前"的注入点可以采样，所以这里只能钉住
     * "两条流程的声明值确实落到了引擎字段上"（配合 `stopWhenRegisterDuplicateStreak` 的同值静默守卫，
     * 流程体内那次重复调用不会把它改回去）。若哪天有人删掉流程开头的登记，这条会红在"登记彻底消失"上，
     * 而"登记晚了"仍只能靠读源码 —— 这条限制写在注释里，别当它覆盖了时机。
     */
    @Test
    fun `artifact 与 weapon 轮的页级确认数等于 flow 声明值`() {
        // ⚠️ 两条 flow 的夹具不同源：weapon 的入口锚点读「背包/武器」+「武器 n/n」计数行，
        //    拿 artifact 的 pageLines 去驱动 weapon 会先在 assertAnchor 抛 ScanAbortedException
        //    （首版就是这么红的），红在夹具上而不是 #219 上。
        for ((flow, lines) in listOf("artifact" to pageLines("Lv.90"), "weapon" to weaponLines())) {
            val (engine, _) = runEngine(
                listOf(Page(syntheticFrame(5), lines, CELLS_PER_PAGE)),
                nativeFlow = flow,
                maxPages = 1,
            )
            assertEquals("$flow：页级确认数必须是流程声明的 1", 1, engine.dupPageConfirm)
            assertEquals("$flow：连续重复阈值同样是流程声明值", 3, engine.charDupStreakLimit)
        }
    }

    /** readCount 读到 0 → 重跑入口链重进界面 → 再读一次 → total=1026。 */
    @Test
    fun `readCount onZero reopens screen and retries`() {
        val (engine, h) = runEngine(
            listOf(Page(syntheticFrame(5), pageLines("Lv.90"), 1026)),
            numberScript = listOf(0, 1026),
            maxPages = 1,   // 同 `readCount and enterScreen wiring`：只验入口链，计数器 1026 是断言对象
            nativeFlow = "artifact",
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
            runEngine(
                listOf(Page(syntheticFrame(5), pageLines("Lv.90", withCountLine = false), 1026)),
                nativeFlow = "artifact",
            )
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
            nativeFlow = "artifact",
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
            nativeFlow = "artifact",
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
            // ★ simple 分支：weapon 原生化后干跑直调 runWeaponScanNative（spec §5，17 场景语义逐条保留）
            nativeFlow = "weapon",
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

    /**
     * #259：武器轮的星级必须**随 job 交付**，不能留在生产者那侧。
     *
     * `vote()` 的 `weapon.panel.starBand` 分支原本写 `vars.rarity` 而不是 `sink.rarity`
     * （其余分支都走 sink）⇒ `pipe>0` 时快照取到的是 -1 哨兵，消费者开头 `applyTo(vars)`
     * 又把 -1 盖回真值 ⇒ `parseWeaponPanel` 见 `rarity<0` 直接 return ⇒
     * **BS 2244 实测 63 格全解析、0 件入库、`导出跳过（normal）：无已入库结果`**，
     * 并且连带把跨页锚点的身份读成空（身份是 parse 末尾才写的）。
     *
     * 这条用例与上一条同夹具，只多开 `pipeDepth=2` ⇒ 旧实现下 `resultsWeapons` 会是 **0**
     * 而不是 21，红得直接；它同时是"管道臂与同步臂入库件数必须相等"这条口径的武器侧落点。
     */
    @Test
    fun `S2b 管道臂武器轮必须与同步臂同样入库`() {
        fun run(pipe: Int) = runEngine(
            listOf(Page(syntheticFrame(5), weaponLines(), CELLS_PER_PAGE)),
            nativeFlow = "weapon",
            weaponNames = weaponDictNames(),
            pipeDepth = pipe,
        )
        val (sync, h0) = run(0)
        val (piped, h) = run(2)
        assertEquals("同步臂口径", CELLS_PER_PAGE, sync.resultsWeapons.size)
        assertEquals("管道臂武器入库数应与同步臂相同（整页）", CELLS_PER_PAGE, piped.resultsWeapons.size)
        assertTrue("武器轮不得产出圣遗物", piped.results.isEmpty())
        assertEquals("completed", h.finished)
        // 星级没随行交付的形态就是"每格都提前 return"⇒ 一条都不该有，但反过来全有才是修好的样子
        assertTrue(
            "每件都该有真实星级（>0），出现 -1 哨兵说明星级又留在生产者那侧了：" +
                piped.resultsWeapons.map { it.rarity },
            piped.resultsWeapons.all { it.rarity > 0 },
        )
        // ★ #261：`lock` 是 parse 内部读**面板像素**算的（`weaponLock(frame, profile, origin)`）——
        //   管道下 frame 是裁片，不走 origin 平移就恒 false（BS 实测一臂全 true、一臂全 false）。
        //   这里比整条记录（键/等级/精炼/锁/位置），逐件同值才算过。
        fun rec(w: com.bettergi.pocket.scan.GoodWeapon) =
            listOf(w.key, w.level.toString(), w.refine.toString(), w.lock.toString(), w.location.orEmpty())
        assertEquals(
            "管道臂与同步臂的武器记录必须逐件相同（★ lock 曾是管道臂恒 false 的那一格）",
            sync.resultsWeapons.map { rec(it) },
            piped.resultsWeapons.map { rec(it) },
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
            nativeFlow = "artifact",
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
     * ⚠️ 计数器给不可达的 1026（件数主判据永不满足）、maxPages=6 只作红状态护栏：
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
            nativeFlow = "artifact",
            // ★ simple 分支：pageSkip 注入改为原生参数（等价于 JSON 路径的 flowTransformer 注入）
            nativePageSkipExpr = "pageMinLevel < 20",
        )
        assertEquals("前两页正常入库", 2 * CELLS_PER_PAGE, engine.results.size)
        assertEquals("应按正常止扫语义收尾（非报错/非 maxPages）", "stopWhen", h.finished)
        assertEquals(
            "页 0/1/2/3 各一次主滑，第 3 个连续 skip 页在页首抢帧前收尾（不再滑）",
            4,
            h.advanceSwipes.size,
        )
    }
}
