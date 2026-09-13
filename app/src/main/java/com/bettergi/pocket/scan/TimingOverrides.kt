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
    var panelSigEnabled: Boolean = D_SIG

    /** 签名轮询步长（仅 [panelSigEnabled] 时生效）。默认 40ms —— 实测面板内容 133ms 就绪（中位）。 */
    var panelSigPollMs: Long = ScanEngine.PANEL_SIG_POLL_MS
    /**
     * ★ 就绪签名是否改用「靠后渲染的内容带」（等级/副属性/…）而非名字区。
     * `true`（默认）= 内容带 —— 名字最先渲染完，"名字稳定"≠"面板就绪"（曾致 20→18 件）。
     */
    var panelSigBand: Boolean = D_SIGBAND
    /**
     * 认定"已稳定"所需的**跨帧**连同样本数。
     * **默认 2**（const [ScanEngine.SIG_STABLE_SAMPLES]=3 是保守上界）：内容带**覆盖了全部要读的字段**
     * ⇒ 2 次跨帧一致即"整面板已就绪"，无需第 3 次（省 ~40ms/格）。2026-09-12 A/B 验证：
     * artifact 4 次、weapon 3 次跑，GOOD 逐字段一致。
     */
    var panelSigSamples: Int = D_SIGSAMPLES
    /**
     * 是否保留收尾的"名字非空"OCR 确认。**默认关**：内容带已含名字与全部字段 ⇒ 冗余（省 ~15ms/格）。
     * ⚠️ 关掉的前提是内容带**确实覆盖了所有读取字段**（曾因只收左列属性行、漏掉武器锁图标而出错）。
     */
    var panelSigConfirm: Boolean = D_SIGCONFIRM
    /**
     * ★ 「名字 ROI 是否可读」这个**流程级**门只求值一次（默认 `true`）：签名路径下不再逐格做
     * 点击前 OCR。`false` = 逐格求值（改动前行为）。
     */
    var panelSigGateCached: Boolean = D_SIGGATE
    /** 打印就绪等待的分段计时（`total / firstChange / afterChange / samples / frameGaps`）。 */
    var sigDebug: Boolean = false
    /** 翻页滑动距离**绝对覆盖**（帧坐标 px）；0 = 用几何推导值。标定"实际滚动量偏短"用。 */
    var advanceDistPx: Int = 0
    /** 翻页滑动距离**加偏置**（帧坐标 px，默认 0 = 原行为）。 */
    var advanceExtraPx: Int = 0

    /**
     * ★ 翻页**落地位移闭环**开关（默认 `1` = 开）。
     *
     * 为什么需要（2026-09-12 实测）：单次手势的**落地位移远小于指令距离**，且波动极大
     * （扫描前段 ~0.7×，后段可低到 ~0.05×）⇒「一页推进 3 行」靠单次滑动根本做不到；
     * 而自适应点击位置只能吸收 **±半卡高（≈126px）**。
     * 闭环 = 每次滑动后用 [VoteJudges.profileShift] 实测落地位移，不足则按**实测增益**补滑。
     * `0` = 回到单次滑动（原行为，便于 A/B）。
     */
    var advanceLoop: Int = 1

    /** 闭环容差（帧 px）；`0` = 自动取「行距 × 0.4」（≈82px，仍小于自适应能吸收的 126px）。 */
    var advanceTolPx: Int = 0

    /** 闭环里单页最多滑动次数（含首次）。 */
    var advanceMaxSteps: Int = 6

    /**
     * \u2605 单次滑动命令的**上限**（帧 px）；`0` = 不限制。
     *
     * 为什么需要（2026-09-12 A/B 实测）：落地量与命令是**分段**关系 ——
     *   命令 612 ⇒ 落地 **720**（触发游戏 fling，量不可控、常过冲）；
     *   命令 60  ⇒ 落地 **51**（≈1:1 跟随，**可控**）。
     * ⇒ 把每步命令压到 fling 阈值以下（~150~200px）多走几次，落点反而是**线性可控**的
     *   （例：4×150 ≈ 600 ≈ 3 行，误差 ~12px，远优于"单次 612 却落地 720"）。
     */
    var advanceCmdMaxPx: Int = 0
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

    private val D_NAV = ScanEngine.NAVIGATE_PAGE_SETTLE_MS
    private val D_POLL = ScanEngine.PANEL_POLL_MS
    private val D_PSTABLE = ScanEngine.PANEL_STABLE_FALLBACK_MS
    private val D_SPOLL = ScanEngine.SETTLE_POLL_MS
    private val D_SSTABLE = ScanEngine.SETTLE_STABLE_MS
    private const val D_SIG = true
    private val D_SIGPOLL = ScanEngine.PANEL_SIG_POLL_MS
    private const val D_SIGBAND = true
    /** 内容带 + 2 样本 + 免确认的 A/B 结论（见 panelSigSamples/panelSigConfirm 的 KDoc）。 */
    private const val D_SIGSAMPLES = 2
    private const val D_SIGCONFIRM = true
    private const val D_SIGGATE = true
    /** 0 = 不覆盖，按面板自动（见 `ScanEngine.sigBlocksFor`）。 */
    private const val D_SIGBX = 0
    private const val D_SIGBY = 0
    private const val D_SF_STEPS = 2
    private const val D_SF_MS = 160L
    private const val D_SS_STEPS = 2
    private const val D_SS_MS = 180L
    private const val D_SB_MS = 100L

    fun reset() {
        navSettleMs = D_NAV
        panelPollMs = D_POLL
        panelStableFallbackMs = D_PSTABLE
        settlePollMs = D_SPOLL
        settleStableMs = D_SSTABLE
        panelSigEnabled = D_SIG
        panelSigPollMs = D_SIGPOLL
        panelSigBand = D_SIGBAND
        panelSigSamples = D_SIGSAMPLES
        panelSigConfirm = D_SIGCONFIRM
        panelSigGateCached = D_SIGGATE
        sigDebug = false
        sigBlocksX = D_SIGBX
        sigBlocksY = D_SIGBY
        advanceDistPx = 0
        advanceExtraPx = 0
        advanceLoop = 1
        advanceTolPx = 0
        advanceMaxSteps = 6
        advanceCmdMaxPx = 0
        swipeFastSteps = D_SF_STEPS
        swipeFastMs = D_SF_MS
        swipeSlowSteps = D_SS_STEPS
        swipeSlowMs = D_SS_MS
        swipeBackMs = D_SB_MS
    }

    /** 是否为全默认（用于日志区分"基线轮"）。 */
    val isDefault: Boolean
        get() = navSettleMs == D_NAV && panelPollMs == D_POLL &&
            panelStableFallbackMs == D_PSTABLE && settlePollMs == D_SPOLL &&
            settleStableMs == D_SSTABLE && panelSigEnabled == D_SIG &&
            panelSigPollMs == D_SIGPOLL && panelSigBand == D_SIGBAND &&
            panelSigSamples == D_SIGSAMPLES && panelSigConfirm == D_SIGCONFIRM &&
            panelSigGateCached == D_SIGGATE && !sigDebug && sigBlocksX == D_SIGBX && sigBlocksY == D_SIGBY &&
            advanceDistPx == 0 && advanceExtraPx == 0 &&
            advanceLoop == 1 && advanceTolPx == 0 && advanceMaxSteps == 6 && advanceCmdMaxPx == 0 &&
            swipeFastSteps == D_SF_STEPS &&
            swipeFastMs == D_SF_MS && swipeSlowSteps == D_SS_STEPS &&
            swipeSlowMs == D_SS_MS && swipeBackMs == D_SB_MS

    /**
     * 解析 `"nav=650,poll=120,pstable=400,spoll=120,sstable=150,swFast=110,swFastMs=110,swSlow=110,swSlowMs=110,swBack=60"`
     * 未知键忽略；空串 → [reset]。
     * @return 生效值摘要（回写日志用）
     */
    fun apply(spec: String?): String {
        reset()
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
                    "advdist" -> advanceDistPx = v.toInt().coerceIn(50, 2000)
                    "advextra" -> advanceExtraPx = v.toInt().coerceIn(-500, 1000)
                    "advloop" -> advanceLoop = if (v != 0L) 1 else 0
                    "advtol" -> advanceTolPx = v.toInt().coerceIn(0, 400)
                    "advmax" -> advanceMaxSteps = v.toInt().coerceIn(1, 8)
                    "advcmdmax" -> advanceCmdMaxPx = v.toInt().coerceIn(0, 800)
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
            "siggate=${if (panelSigGateCached) 1 else 0}," +
            "advdist=$advanceDistPx,advextra=$advanceExtraPx," +
            "advloop=$advanceLoop,advtol=$advanceTolPx,advmax=$advanceMaxSteps,advcmdmax=$advanceCmdMaxPx," +
            "sigblocks=${if (sigBlocksX > 0) "${sigBlocksX}x$sigBlocksY" else "auto"}," +
            "swFast=${swipeFastSteps}x$swipeFastMs,swSlow=${swipeSlowSteps}x$swipeSlowMs,swBack=$swipeBackMs," +
            "swipeTotal=$swipeTotalMs" + if (isDefault) " (default)" else ""
}
