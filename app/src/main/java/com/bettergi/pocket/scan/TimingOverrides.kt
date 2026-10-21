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
    var panelPollMs: Long = ScanEngine.PANEL_POLL_MS
    var panelStableFallbackMs: Long = ScanEngine.PANEL_STABLE_FALLBACK_MS
    var settlePollMs: Long = ScanEngine.SETTLE_POLL_MS
    var settleStableMs: Long = ScanEngine.SETTLE_STABLE_MS

    /**
     * ★ 就绪信号直采（2026-09-12）：`true` = 面板就绪轮询改用 [ScanEngine.FrameSource.sampleSignature]
     * 的分块签名（零 Mat / 零 OCR），步长 [panelSigPollMs]；`false` = **完全回退**到旧的
     * 「`delay(panelPollMs)` + `freshFrame()` + OCR 名字」路径（用于 A/B 与回滚）。
     */
    var panelSigEnabled: Boolean = DEFAULT_PANEL_SIG_ENABLED

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
     * pagedGrid **总滑动次数**上限（A9 双保险，独立于计数器 pagesByCount 与 maxPages）。
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
    private val DEFAULT_PANEL_POLL_MS = ScanEngine.PANEL_POLL_MS
    private val DEFAULT_PAGE_STABLE_MS = ScanEngine.PANEL_STABLE_FALLBACK_MS
    private val DEFAULT_SCAN_POLL_MS = ScanEngine.SETTLE_POLL_MS
    private val DEFAULT_SCAN_STABLE_MS = ScanEngine.SETTLE_STABLE_MS
    private const val DEFAULT_PANEL_SIG_ENABLED = true
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
    private const val DEFAULT_PAGE_SKIP_LIMIT = 3
    /** A9：总滑动次数上限的默认值（依据见 [maxTotalSwipes] KDoc）。 */
    private const val DEFAULT_SWIPE_CAP = 240

    fun reset() {
        navSettleMs = DEFAULT_NAV_SETTLE_MS
        panelPollMs = DEFAULT_PANEL_POLL_MS
        panelStableFallbackMs = DEFAULT_PAGE_STABLE_MS
        settlePollMs = DEFAULT_SCAN_POLL_MS
        settleStableMs = DEFAULT_SCAN_STABLE_MS
        panelSigEnabled = DEFAULT_PANEL_SIG_ENABLED
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
        get() = navSettleMs == DEFAULT_NAV_SETTLE_MS && panelPollMs == DEFAULT_PANEL_POLL_MS &&
            panelStableFallbackMs == DEFAULT_PAGE_STABLE_MS && settlePollMs == DEFAULT_SCAN_POLL_MS &&
            settleStableMs == DEFAULT_SCAN_STABLE_MS && panelSigEnabled == DEFAULT_PANEL_SIG_ENABLED &&
            panelSigPollMs == DEFAULT_PANEL_SIG_POLL_MS && panelSigBand == DEFAULT_PANEL_SIG_BAND &&
            panelSigSamples == DEFAULT_PANEL_SIG_SAMPLES && panelSigConfirm == DEFAULT_PANEL_SIG_CONFIRM &&
            panelSigGateCached == DEFAULT_PANEL_SIG_GATE && !sigDebug && sigBlocksX == DEFAULT_SIG_BLOCK_X && sigBlocksY == DEFAULT_SIG_BLOCK_Y &&
            advanceDistPx == 0 && advanceExtraPx == 0 && panelSettleMs == 0L &&
            pageSkipLimit == DEFAULT_PAGE_SKIP_LIMIT && maxTotalSwipes == DEFAULT_SWIPE_CAP &&

            swipeFastSteps == DEFAULT_SWIPE_FAST_STEPS &&
            swipeFastMs == DEFAULT_SWIPE_FAST_MS && swipeSlowSteps == DEFAULT_SWIPE_SLOW_STEPS &&
            swipeSlowMs == DEFAULT_SWIPE_SLOW_MS && swipeBackMs == DEFAULT_SWIPE_BACK_MS

    /**
     * 解析 `"nav=650,poll=120,pstable=400,spoll=120,sstable=150,swFast=110,swFastMs=110,swSlow=110,swSlowMs=110,swBack=60"`
     * 未知键忽略；空串 → [reset]。
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
     * 入口幂等（开链前先短轮询锚点，命中即跳过入口链）。默认 **开**；
     * 干跑测试关掉它，因为测试夹具的合成帧会让锚点直接命中、从而不再记录入口链的点击（点击计数断言会变）。
     */
    var entryIdempotent: Boolean = true

    fun apply(spec: String?): String {
        reset()
        phiApply = true
        anchorBudgetMs = 0L
        overlapSkip = true
        entryIdempotent = true
        panelSettleMs = 0L
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
                    "poll" -> panelPollMs = v
                    "pstable" -> panelStableFallbackMs = v
                    "spoll" -> settlePollMs = v
                    "sstable" -> settleStableMs = v
                    // ★ 就绪信号直采 A/B 开关：sig=0 完全回退旧的「抓帧+OCR」轮询
                    "sig" -> panelSigEnabled = v != 0L
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
        "nav=$navSettleMs,poll=$panelPollMs,pstable=$panelStableFallbackMs," +
            "spoll=$settlePollMs,sstable=$settleStableMs," +
            "sig=${if (panelSigEnabled) 1 else 0},sigpoll=$panelSigPollMs," +
            "sigband=${if (panelSigBand) 1 else 0},sigsamples=$panelSigSamples,sigconfirm=${if (panelSigConfirm) 1 else 0}," +
            "siggate=${if (panelSigGateCached) 1 else 0},phi=${if (phiApply) 1 else 0}," +
            "anchor=$anchorBudgetMs,oskip=${if (overlapSkip) 1 else 0},panel=$panelSettleMs," +
            "advdist=$advanceDistPx,advextra=$advanceExtraPx," +
            "skiplimit=$pageSkipLimit,swipecap=$maxTotalSwipes," +

            "sigblocks=${if (sigBlocksX > 0) "${sigBlocksX}x$sigBlocksY" else "auto"}," +
            "swFast=${swipeFastSteps}x$swipeFastMs,swSlow=${swipeSlowSteps}x$swipeSlowMs,swBack=$swipeBackMs," +
            "swipeTotal=$swipeTotalMs" + if (isDefault) " (default)" else ""
}
