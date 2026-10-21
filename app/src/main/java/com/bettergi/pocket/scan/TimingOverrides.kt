package com.bettergi.pocket.scan

/**
 * 运行时可覆盖的时序参数（**仅调试/标定用**）。
 *
 * 目的：把"等待稳定"的各个常量暴露出来，用 adb 按档位下探**最短可用值**
 * （见 `dsl/verify/_audit/PERF-timing.md` 的档位计划）。
 *
 * ⚠️ 三条硬约束：
 * 1. **空覆盖 = 与原实现逐位一致**（默认值就是原编译期常量）。
 * 2. 每轮扫描必须把**生效值**回写日志（`timing: …`），否则多档位日志无法归因。
 * 3. 只影响时延，**不影响任何判据语义**（阈值/坐标/词典均不在此）。
 */
object TimingOverrides {

    // ── 引擎等待（默认值**引用 ScanEngine 常量**，单一事实源，勿在此写死数字）──
    var navSettleMs: Long = ScanEngine.NAVIGATE_PAGE_SETTLE_MS
    var panelStableFallbackMs: Long = ScanEngine.PANEL_STABLE_FALLBACK_MS
    var settlePollMs: Long = ScanEngine.SETTLE_POLL_MS
    var settleStableMs: Long = ScanEngine.SETTLE_STABLE_MS

    /**
     * ★ 就绪信号直采（2026-09-12）：`true` = 面板就绪轮询改用 [ScanEngine.FrameSource.sampleSignature]
     * 的分块签名（零 Mat / 零 OCR），步长 [panelSigPollMs]；`false` = 回退为**固定 `delay(PANEL_REFRESH_MS)`**。
     * （2026-10-05：原先 `false` 回退到的「`delay` + `freshFrame` + OCR 名字轮询」分支已删 ——
     *   它在全部存档真机日志里 0 次命中，只留下误导。要 A/B 就绪判定请比 `sigpoll`/`sigsamples`。）
     */
    var panelSigEnabled: Boolean = DEFAULT_PANEL_SIG_ENABLED

    /**
     * S1（#69 流水线）：OCR/解析改读面板裁片（origin 平移），vote/指纹仍读整帧。
     * 默认关；`--es timing "crop=1"` 开。开与关的解析结果必须逐字段相同（真机 A/B 验）。
     */
    var panelCrop: Boolean = DEFAULT_PANEL_CROP

    /**
     * S2b（#69 流水线）：解析流水线深度。0=现状同步路径（新增代码零执行）；
     * 1..8=每格 parse 步只做「指纹闸+裁片拷贝+send」，OCR/解析/emit 在单消费者协程；
     * >0 时强制 crop=1（消费者只拿得到裁片，整帧路径与流水线路径不并存，防语义漂移）。
     * K 只决定"藏多深"（点击/门/翻页窗口藏进消费者积压），不增加并行度。
     */
    var pipeDepth: Int = DEFAULT_PIPE_DEPTH

    /**
     * ★ A③（#69 收尾，2026-10-13）：本轮 `pipe=` 是否**显式给过**。
     *
     * 管道深度改按**流程属性**给默认（见 [ScanEngine.PIPE_DEFAULT_BY_FLOW]）之后，这条是
     * 唯一能把"默认开着管道的流程"拉回同步臂的把手 —— 没有它，`--es timing "pipe=0"`
     * 会被流程默认覆写，#69 那种正反序 A/B 与"管道出问题先退回同步"的回退路同时失效。
     */
    var pipeExplicit: Boolean = false

    /**
     * ★ #260：管道"锚点早到交付"（#253）那次停等的**无进展轮询次数**上限，
     * 0 = 用 [ScanEngine.PIPE_ANCHOR_IDLE_POLLS]（45_000 次 × 2ms ≈ 90s 名义）。
     *
     * 这里曾经是**墙钟预算**（90s），而干跑里它量的是"这台机器有多忙"：满载复跑（load 12，
     * 8 个 busy 进程）实测被打满 ⇒ 锚定退回"照常访问" ⇒ 管道臂比同步臂**多击一整条重叠带**
     * （`expected 43 but was 50`）。当时的处置是给干跑设一个大值把它失效 —— 那是掩码，
     * 真机侧的静默降级（慢机 90s 内跑不完 2~3 个 job ⇒ 每页白多击数格）还在。
     * 现在上限改按"消费者有没有处理完 job"计（论证见 [ScanEngine.PIPE_ANCHOR_IDLE_POLLS]），
     * 负载只会让真实时长变长、不会变短，所以干跑不再需要任何覆盖值；本旋钮只留给
     * 真机上"想更早收手"的实验。
     */
    var pipeAnchorIdlePolls: Long = 0L

    /**
     * 武器面板那段盲等的覆盖值（`wpdelay=<ms>`，**默认 0 = 不等**）。
     * 2026-10-14 #233 把原先写死的 300ms 删了（它作用在抓帧之后 ⇒ 只是盲等），
     * 这个键留着纯粹是为了能把那段等待加回去做对照（照 `dupskip` 的先例）。
     *
     * 之所以要能运行时改：2026-10-14 读代码发现**这段延迟在抓帧之后**
     * （[ArtifactDomainPanels] 里 `frame` 在 :267 就 acquire 好了，:292 才 delay），
     * 所以它不可能在"等面板渲染完"，只是把 OCR 推迟到一张**已经定格的帧**之后 ⇒ 净效果 = 白等 300ms/格。
     * 这个结论要靠 A/B 定，不该由我一句话直接删常量。
     */
    var weaponPanelDelayMs: Long = 0L

    /**
     * ★ 2026-10-20（后台武器相邻格重复）：武器点击后，**面板内容带**从首次检出变化起
     * 至少再等这么久才允许判稳（`wpsettle=<ms>`，0 = 关，默认 [DEFAULT_WEAPON_PANEL_SETTLE_MS]）。
     *
     * 为什么需要：后台 VD 帧里面板切换会先冒一帧"仍像上一件"的稳定态（交叉淡入/旧帧），
     * 旧路径 2×20ms 就判稳 ⇒ 解析到上一把武器 ⇒ 同一身份相邻两格各入库一次（多在 24-25 对/轮），
     * 被挤掉的真件则表现为漏。GOODScanner 对武器用 FixedDelay 50ms + `ensure_panel_stable(100)`；
     * 后台实测坏帧在点击后 ~157ms 仍在，故取 150ms 起步。
     */
    var weaponPanelSettleMs: Long = DEFAULT_WEAPON_PANEL_SETTLE_MS

    /**
     * 圣遗物面板闭锁图标的最短点击龄（`locksettle=<ms>`，0 = 关，默认 [ScanEngine.PANEL_LOCK_SETTLE_MS]）。
     * 见该常量的取证：点击后早抓的收敛帧里锁徽可能仍是上一件状态。
     */
    var panelLockSettleMs: Long = ScanEngine.PANEL_LOCK_SETTLE_MS

    /**
     * 签名不可用时那条**固定等待**的覆盖值（`prefresh=<ms>`，`-1` = 用 [ScanEngine.PANEL_REFRESH_MS]）。
     * 角色流水每格都走这一档 ⇒ 这是"角色等待"唯一的旋钮；对齐方案里 550→400 那条取值决策
     * 原先没有 A/B 通道（纯 const，改一次重编一次），这个键就是为了补上它。
     */
    var panelRefreshMs: Long = -1L

    /** 签名轮询步长（仅 [panelSigEnabled] 时生效）。默认 40ms —— 实测面板内容 133ms 就绪（中位）。 */
    var panelSigPollMs: Long = ScanEngine.PANEL_SIG_POLL_MS
    /**
     * ★ 就绪签名是否改用「靠后渲染的内容带」（等级/副属性/…）而非名字区。
     * `true`（默认）= 内容带 —— 名字最先渲染完，"名字稳定"≠"面板就绪"（曾致 20→18 件）。
     */
    var panelSigBand: Boolean = DEFAULT_PANEL_SIG_BAND
    /**
     * 认定"已稳定"所需的**跨帧**连同样本数。
     * **默认 2**（const [ScanEngine.SIG_STABLE_SAMPLES]=3 是保守上界）：内容带**覆盖了全部要读的字段**
     * ⇒ 2 次跨帧一致即"整面板已就绪"，无需第 3 次（省 ~40ms/格）。2026-09-12 A/B 验证：
     * artifact 4 次、weapon 3 次跑，GOOD 逐字段一致。
     */
    var panelSigSamples: Int = DEFAULT_PANEL_SIG_SAMPLES
    /**
     * 是否保留收尾的"名字非空"OCR 确认。**默认关**：内容带已含名字与全部字段 ⇒ 冗余（省 ~15ms/格）。
     * ⚠️ 关掉的前提是内容带**确实覆盖了所有读取字段**（曾因只收左列属性行、漏掉武器锁图标而出错）。
     */
    var panelSigConfirm: Boolean = DEFAULT_PANEL_SIG_CONFIRM
    /**
     * ★ 「名字 ROI 是否可读」这个**流程级**门只求值一次（默认 `true`）：签名路径下不再逐格做
     * 点击前 OCR。`false` = 逐格求值（改动前行为）。
     */
    var panelSigGateCached: Boolean = DEFAULT_PANEL_SIG_GATE
    /** 打印就绪等待的分段计时（`total / firstChange / afterChange / samples / frameGaps`）。 */
    var sigDebug: Boolean = false
    /** 翻页滑动距离**绝对覆盖**（帧坐标 px）；0 = 用几何推导值。标定"实际滚动量偏短"用。 */
    var advanceDistPx: Int = 0
    /** 翻页滑动距离**加偏置**（帧坐标 px，默认 0 = 原行为）。 */
    var advanceExtraPx: Int = 0

    // ── ★ A9 两条**终止保护**上限（本文件唯一的非时延参数，破例原因：调试/标定时需要免重编
    //    下探"早停是否止血"，与 advdist 同一使用形态；默认值 = 生产行为）──
    /**
     * pagedGrid **连续整页跳过**（pageSkip 命中）的收尾上限 K。默认 **3**：
     * 背包按等级降序，连续 3 页全跳 ⇒ 余下所有页都低于目标（GOODScanner fast-mode page-skip
     * 同源止扫）；留 3 页容忍滑动欠滚/排序抖动造成的 1~2 页偶发误跳。
     */
    var pageSkipLimit: Int = DEFAULT_PAGE_SKIP_LIMIT
    /**
     * pagedGrid **总滑动次数**上限（独立于件数主判据与 maxPages）。
     * 2026-10-04 起它是「列表卡住一直翻」的**唯一**硬闸 —— 「按件数推算的翻页数上限」已删除
     * （它数的是页数而非进度，欠滚越多越早误截，详见 `ScanGridDomain.pagedGrid` 开头）。
     * 默认 240 ≈ 2× 满背包翻页量（2400 件 ÷ 21 件/页 ≈ 115 页）。
     */
    var maxTotalSwipes: Int = DEFAULT_SWIPE_CAP

    /**
     * ★ 就绪签名的**分块网格**（列×行）。块越小 ⇒ 对"淡入早期的小变化"越敏感 ⇒ 越早检出"已变"
     * （实测 `firstChange` 占整个等待的 83%）；块越大 ⇒ 均值越平滑、越不容易被噪声干扰。
     * 只增不减地夹到 [ScanEngine.SIG_BLOCKS_X_MAX]/[SIG_BLOCKS_Y_MAX]。
     */
    var sigBlocksX: Int = 0
    var sigBlocksY: Int = 0

    // ── 三段式滑动（转发给 InputAccessibilityService）──
    var swipeFastSteps: Int = 2               // WP_FAST_STEPS
    var swipeFastMs: Long = 160L              // WP_FAST_MS
    var swipeSlowSteps: Int = 2               // WP_SLOW_STEPS
    var swipeSlowMs: Long = 180L              // WP_SLOW_MS
    var swipeBackMs: Long = 100L              // WP_BACK_MS

    /** 手势名义总时长（与 InputAccessibilityService.SWIPE_TOTAL_MS 同公式）。 */
    val swipeTotalMs: Long get() = swipeFastSteps * swipeFastMs + swipeSlowSteps * swipeSlowMs + swipeBackMs

    private val DEFAULT_NAV_SETTLE_MS = ScanEngine.NAVIGATE_PAGE_SETTLE_MS
    private val DEFAULT_PAGE_STABLE_MS = ScanEngine.PANEL_STABLE_FALLBACK_MS
    private val DEFAULT_SCAN_POLL_MS = ScanEngine.SETTLE_POLL_MS
    private val DEFAULT_SCAN_STABLE_MS = ScanEngine.SETTLE_STABLE_MS
    private const val DEFAULT_PANEL_SIG_ENABLED = true
    private const val DEFAULT_PANEL_CROP = false
    private const val DEFAULT_PIPE_DEPTH = 0
    private val DEFAULT_PANEL_SIG_POLL_MS = ScanEngine.PANEL_SIG_POLL_MS
    private const val DEFAULT_PANEL_SIG_BAND = true
    /** 内容带 + 2 样本 + 免确认的 A/B 结论（见 panelSigSamples/panelSigConfirm 的 KDoc）。 */
    private const val DEFAULT_PANEL_SIG_SAMPLES = 2
    /** ★ P3（2026-09-30）：常量从 true 改为 false，对齐文档与 A/B 终局结论（证据见 KDoc）。 */
    private const val DEFAULT_PANEL_SIG_CONFIRM = false
    private const val DEFAULT_PANEL_SIG_GATE = true
    /** 0 = 不覆盖，按面板自动（见 `ScanEngine.sigBlocksFor`）。 */
    private const val DEFAULT_SIG_BLOCK_X = 0
    private const val DEFAULT_SIG_BLOCK_Y = 0
    private const val DEFAULT_SWIPE_FAST_STEPS = 2
    private const val DEFAULT_SWIPE_FAST_MS = 160L
    private const val DEFAULT_SWIPE_SLOW_STEPS = 2
    private const val DEFAULT_SWIPE_SLOW_MS = 180L
    private const val DEFAULT_SWIPE_BACK_MS = 100L
    /** A9：连续 skip 页收尾上限的默认值（依据见 [pageSkipLimit] KDoc）。 */
    private const val DEFAULT_WEAPON_PANEL_SETTLE_MS = 150L
    private const val DEFAULT_PAGE_SKIP_LIMIT = 3
    /** A9：总滑动次数上限的默认值（依据见 [maxTotalSwipes] KDoc）。 */
    private const val DEFAULT_SWIPE_CAP = 240

    fun reset() {
        navSettleMs = DEFAULT_NAV_SETTLE_MS
        panelStableFallbackMs = DEFAULT_PAGE_STABLE_MS
        settlePollMs = DEFAULT_SCAN_POLL_MS
        settleStableMs = DEFAULT_SCAN_STABLE_MS
        panelSigEnabled = DEFAULT_PANEL_SIG_ENABLED
        panelCrop = DEFAULT_PANEL_CROP
        pipeDepth = DEFAULT_PIPE_DEPTH
        pipeExplicit = false
        pipeAnchorIdlePolls = 0L
        weaponPanelDelayMs = 0L
        weaponPanelSettleMs = DEFAULT_WEAPON_PANEL_SETTLE_MS
        panelRefreshMs = -1L
        navSkipRedundant = true
        panelSigPollMs = DEFAULT_PANEL_SIG_POLL_MS
        panelSigBand = DEFAULT_PANEL_SIG_BAND
        panelSigSamples = DEFAULT_PANEL_SIG_SAMPLES
        panelSigConfirm = DEFAULT_PANEL_SIG_CONFIRM
        panelSigGateCached = DEFAULT_PANEL_SIG_GATE
        sigDebug = false
        sigBlocksX = DEFAULT_SIG_BLOCK_X
        sigBlocksY = DEFAULT_SIG_BLOCK_Y
        advanceDistPx = 0
        advanceExtraPx = 0
        pageSkipLimit = DEFAULT_PAGE_SKIP_LIMIT
        maxTotalSwipes = DEFAULT_SWIPE_CAP

        swipeFastSteps = DEFAULT_SWIPE_FAST_STEPS
        swipeFastMs = DEFAULT_SWIPE_FAST_MS
        swipeSlowSteps = DEFAULT_SWIPE_SLOW_STEPS
        swipeSlowMs = DEFAULT_SWIPE_SLOW_MS
        swipeBackMs = DEFAULT_SWIPE_BACK_MS
    }

    /** 是否为全默认（用于日志区分"基线轮"）。 */
    val isDefault: Boolean
        get() = navSettleMs == DEFAULT_NAV_SETTLE_MS &&
            panelStableFallbackMs == DEFAULT_PAGE_STABLE_MS && settlePollMs == DEFAULT_SCAN_POLL_MS &&
            settleStableMs == DEFAULT_SCAN_STABLE_MS && panelSigEnabled == DEFAULT_PANEL_SIG_ENABLED && panelCrop == DEFAULT_PANEL_CROP &&
            panelSigPollMs == DEFAULT_PANEL_SIG_POLL_MS && panelSigBand == DEFAULT_PANEL_SIG_BAND &&
            panelSigSamples == DEFAULT_PANEL_SIG_SAMPLES && panelSigConfirm == DEFAULT_PANEL_SIG_CONFIRM &&
            panelSigGateCached == DEFAULT_PANEL_SIG_GATE && !sigDebug && sigBlocksX == DEFAULT_SIG_BLOCK_X && sigBlocksY == DEFAULT_SIG_BLOCK_Y &&
            advanceDistPx == 0 && advanceExtraPx == 0 && panelSettleMs == 0L &&
            weaponPanelSettleMs == DEFAULT_WEAPON_PANEL_SETTLE_MS &&
            panelLockSettleMs == ScanEngine.PANEL_LOCK_SETTLE_MS &&
            pageSkipLimit == DEFAULT_PAGE_SKIP_LIMIT && maxTotalSwipes == DEFAULT_SWIPE_CAP &&

            swipeFastSteps == DEFAULT_SWIPE_FAST_STEPS &&
            swipeFastMs == DEFAULT_SWIPE_FAST_MS && swipeSlowSteps == DEFAULT_SWIPE_SLOW_STEPS &&
            swipeSlowMs == DEFAULT_SWIPE_SLOW_MS && swipeBackMs == DEFAULT_SWIPE_BACK_MS

    /**
     * 解析 `"nav=650,pstable=400,spoll=120,sstable=150,swFast=110,swFastMs=110,swSlow=110,swSlowMs=110,swBack=60"`
     * 未知键忽略；空串 → [reset]。
     * ★ 2026-10-05：`poll` 键已删 —— 它映射的 `PANEL_POLL_MS` 常量从未被任何生效路径引用
     *   （就绪轮询早改走签名直采 `sigpoll`，签名不可用时是固定 `PANEL_REFRESH_MS` 等待），
     *   留着只会误导"以为 A/B 过它"。传入 `poll=…` 现按未知键静默忽略。
     * @return 生效值摘要（回写日志用）
     */
    /** 是否**应用**页面相位平移（真机标定开关；`phi=0` 关闭）。 */
    var phiApply: Boolean = true

    /**
     * 入口「锚点就绪」轮询预算覆盖（ms；0 = 用引擎默认 ENTER_SETTLE+SCREEN_SETTLE=2700）。
     * 真机（华为 2244）实测：**开背包需 2~4s**，2700ms 预算常打满 ⇒ anchor 未命中 ⇒ 整轮 abort。
     */
    var anchorBudgetMs: Long = 0L

    /**
     * 角色筛选面板每步的等待（默认 0 = 用 `ScanEngine.PANEL_SETTLE_MS(1500)`）。
     *
     * 为什么要有这个开关：面板交互实测 11.3s 里约 10.5s 是纯等待（README D 段自记"取手工节奏、未收紧"），
     * 收紧它只能靠**对照跑**（同一条流程、只改这一个数），所以先做成可覆盖的，别再改常量。
     */
    var panelSettleMs: Long = 0L

    /** C' 身份锚定跳过（默认开；`oskip=0` 关闭，用于 A/B）。 */
    var overlapSkip: Boolean = true

    /**
     * ★ 2026-10-20：**OVERLAP 页补读第 4 行**（可见预览行）。默认开；`prb=0` 关闭（A/B 与回滚）。
     *
     * 机理：本页 `行级闭环` 判 OVERLAP（前进约 12/18）时，下一页首格可能低约一行（click y≈260），
     * 顶行（本页第 4 行）被跳过；r7/r9/r10 的 6 件级漏块都是这一形态。补读只读**当前页**
     * 第 4 行 6 格，不写 pageKeys，行级闭环仍按 18 格计算。
     */
    var previewRowBackfill: Boolean = true

    /**
     * P1（2026-10-06）：重复格（卡格指纹未变）跳过面板指纹门等待。对齐 GOODScanner
     * backpack_scanner.rs:1417 的 is_duplicate ⇒ 不进面板等待。
     * ★ 2026-10-10 默认改 **0（不跳过）**：BS 全量 A/B 实证同套装同部位同 lv0 的相邻两件
     *   卡格指纹相同 ⇒ 跳过等待后抓到的是上一件的面板（渲染延迟）⇒ 读出上一件词条 ⇒
     *   seen 判重 return ⇒ 第二件无痕丢失（每轮 ~0.1% 概率漏格，12:02/14:09 两轮各 1 件；
     *   dupskip=0 轮 952/952 零漏零多）。成本 ~120ms/重复格，perCell 差 <5ms。
     *   `--es timing "dupskip=1"` 回退到跳过档（A/B 与回滚用）。
     */
    var duplicateCellSkipWait: Boolean = false

    /**
     * ★ #233 单2（2026-10-14，默认 **开**）：角色正序格「复位到属性页」那一跳，先用一次正向页判定
     * 证明已经在属性页，判到就**不点也不等**（省 1 击 + `nav` 那一档）。
     * `--es timing "navskip=0"` 回退到今天的盲导航（A/B 与回滚用）。
     *
     * 与 [duplicateCellSkipWait] 的区别要说清：那条靠"卡格指纹没变"推断"面板还是上一件"，实测会
     *   抓到上一件的面板而**静默漏格**（已改回默认不跳）；本条靠**目标页自己的文本**做正向证明
     *   （bottomBar「提升指南」，三页互斥），且这一跳**根本不需要等页面切换** —— 它省的是"点了一下
     *   同一个已经亮着的页签"之后的动画等待，不是"页面还没到就不等"。判不到一律照旧导航。
     */
    var navSkipRedundant: Boolean = true

    /**
     * 入口幂等（开链前先短轮询锚点，命中即跳过入口链）。默认 **开**；
     * 干跑测试关掉它，因为测试夹具的合成帧会让锚点直接命中、从而不再记录入口链的点击（点击计数断言会变）。
     */
    var entryIdempotent: Boolean = true

    fun apply(spec: String?): String {
        reset()
        phiApply = true
        anchorBudgetMs = 0L
        overlapSkip = true
        previewRowBackfill = true
        duplicateCellSkipWait = false
        navSkipRedundant = true
        entryIdempotent = true
        panelSettleMs = 0L
        panelLockSettleMs = ScanEngine.PANEL_LOCK_SETTLE_MS
        if (!spec.isNullOrBlank()) {
            for (kv in spec.split(',')) {
                val i = kv.indexOf('=')
                if (i <= 0) continue
                val k = kv.substring(0, i).trim()
                val raw = kv.substring(i + 1).trim()
                // 复合值（单 long 装不下）：sigblocks=16x8（列x行）
                if (k == "sigblocks") {
                    Regex("^(\\d+)[xX:](\\d+)$").find(raw)?.let { m ->
                        sigBlocksX = m.groupValues[1].toInt().coerceIn(1, ScanEngine.SIG_BLOCKS_X_MAX)
                        sigBlocksY = m.groupValues[2].toInt().coerceIn(1, ScanEngine.SIG_BLOCKS_Y_MAX)
                    }
                    continue
                }
                val v = raw.toLongOrNull() ?: continue
                when (k) {
                    "nav" -> navSettleMs = v
                    "pstable" -> panelStableFallbackMs = v
                    "spoll" -> settlePollMs = v
                    "sstable" -> settleStableMs = v
                    // ★ 就绪信号直采 A/B 开关：sig=0 回退为固定 delay(PANEL_REFRESH_MS)（见字段 KDoc）
                    "sig" -> panelSigEnabled = v != 0L
                    // #233 单2：navskip=0 ⇒ 正序格照旧盲点一次复位页签（A/B 基线臂）
                    "navskip" -> navSkipRedundant = v != 0L
                    // S1 裁片开关（#69）：crop=1 时解析读裁片
                    "crop" -> panelCrop = v != 0L
                    // S2b 流水线深度（#69）：0=同步；1..8=消费者队列深度（>0 强制 crop=1）
                    "pipe" -> {
                        pipeDepth = v.toInt().coerceIn(0, 8)
                        pipeExplicit = true
                        if (pipeDepth > 0) panelCrop = true
                    }
                    // #260：锚点早到交付的预算（干跑给大值，真机留默认 90s）
                    // #260 后半：锚点停等的"无进展轮询次数"上限（原为墙钟预算，干跑不再需要覆盖）
                    "panchor" -> pipeAnchorIdlePolls = v.coerceAtLeast(0L)
                    "wpdelay" -> weaponPanelDelayMs = v.coerceIn(0L, 5000L)
                    "wpsettle" -> weaponPanelSettleMs = v.coerceIn(0L, 5000L)
                    "locksettle" -> panelLockSettleMs = v.coerceIn(0L, 5000L)
                    "prefresh" -> panelRefreshMs = v.coerceIn(-1L, 5000L)
                    "sigpoll" -> panelSigPollMs = v.coerceAtLeast(5L)
                    "sigband" -> panelSigBand = v != 0L
                    "sigsamples" -> panelSigSamples = v.toInt().coerceIn(1, 6)
                    "sigconfirm" -> panelSigConfirm = v != 0L
                    "siggate" -> panelSigGateCached = v != 0L
                    "sigdebug" -> sigDebug = v != 0L
                    "phi" -> phiApply = v != 0L
                    "anchor" -> anchorBudgetMs = v.coerceIn(0L, 60000L)
                    "panel" -> panelSettleMs = v.coerceIn(0L, 10000L)
                    "oskip" -> overlapSkip = v != 0L
                    "prb" -> previewRowBackfill = v != 0L
                    "dupskip" -> duplicateCellSkipWait = v != 0L
                    "advdist" -> advanceDistPx = v.toInt().coerceIn(50, 2000)
                    "advextra" -> advanceExtraPx = v.toInt().coerceIn(-500, 1000)
                    // A9 终止保护上限（见字段 KDoc）
                    "skiplimit" -> pageSkipLimit = v.toInt().coerceAtLeast(1)
                    "swipecap" -> maxTotalSwipes = v.toInt().coerceAtLeast(10)

                    "swFastSteps" -> swipeFastSteps = v.toInt().coerceAtLeast(1)
                    "swFastMs" -> swipeFastMs = v.coerceAtLeast(10L)
                    "swSlowSteps" -> swipeSlowSteps = v.toInt().coerceAtLeast(0)
                    "swSlowMs" -> swipeSlowMs = v.coerceAtLeast(10L)
                    "swBackMs" -> swipeBackMs = v.coerceAtLeast(0L)
                }
            }
        }
        return summary()
    }

    fun summary(): String =
        "nav=$navSettleMs,pstable=$panelStableFallbackMs," +
            "spoll=$settlePollMs,sstable=$settleStableMs," +
            "sig=${if (panelSigEnabled) 1 else 0},crop=${if (panelCrop) 1 else 0},pipe=$pipeDepth,sigpoll=$panelSigPollMs," +
            "sigband=${if (panelSigBand) 1 else 0},sigsamples=$panelSigSamples,sigconfirm=${if (panelSigConfirm) 1 else 0}," +
            "siggate=${if (panelSigGateCached) 1 else 0},phi=${if (phiApply) 1 else 0}," +
            "anchor=$anchorBudgetMs,oskip=${if (overlapSkip) 1 else 0},prb=${if (previewRowBackfill) 1 else 0}," +
            "navskip=${if (navSkipRedundant) 1 else 0},dupskip=${if (duplicateCellSkipWait) 1 else 0}," +
            "panel=$panelSettleMs," +
            "wpdelay=$weaponPanelDelayMs,wpsettle=$weaponPanelSettleMs,prefresh=$panelRefreshMs," +
            "locksettle=$panelLockSettleMs," +
            "advdist=$advanceDistPx,advextra=$advanceExtraPx," +
            "skiplimit=$pageSkipLimit,swipecap=$maxTotalSwipes," +

            "sigblocks=${if (sigBlocksX > 0) "${sigBlocksX}x$sigBlocksY" else "auto"}," +
            "swFast=${swipeFastSteps}x$swipeFastMs,swSlow=${swipeSlowSteps}x$swipeSlowMs,swBack=$swipeBackMs," +
            "swipeTotal=$swipeTotalMs" + if (isDefault) " (default)" else ""
}
