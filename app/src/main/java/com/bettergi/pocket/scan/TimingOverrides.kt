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

    // ── 引擎等待 ──
    var navSettleMs: Long = 900L              // NAVIGATE_PAGE_SETTLE_MS
    var panelPollMs: Long = 200L              // 点格后面板名轮询步长
    var panelStableFallbackMs: Long = 600L    // 名字未变但已稳定的兜底
    var settlePollMs: Long = 120L             // SETTLE_POLL_MS
    var settleStableMs: Long = 300L           // SETTLE_STABLE_MS

    // ── 三段式滑动（转发给 InputAccessibilityService）──
    var swipeFastSteps: Int = 2               // WP_FAST_STEPS
    var swipeFastMs: Long = 160L              // WP_FAST_MS
    var swipeSlowSteps: Int = 2               // WP_SLOW_STEPS
    var swipeSlowMs: Long = 180L              // WP_SLOW_MS
    var swipeBackMs: Long = 100L              // WP_BACK_MS

    /** 手势名义总时长（与 InputAccessibilityService.SWIPE_TOTAL_MS 同公式）。 */
    val swipeTotalMs: Long get() = swipeFastSteps * swipeFastMs + swipeSlowSteps * swipeSlowMs + swipeBackMs

    private const val D_NAV = 900L
    private const val D_POLL = 200L
    private const val D_PSTABLE = 600L
    private const val D_SPOLL = 120L
    private const val D_SSTABLE = 300L
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
            settleStableMs == D_SSTABLE && swipeFastSteps == D_SF_STEPS &&
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
                val v = kv.substring(i + 1).trim().toLongOrNull() ?: continue
                when (k) {
                    "nav" -> navSettleMs = v
                    "poll" -> panelPollMs = v
                    "pstable" -> panelStableFallbackMs = v
                    "spoll" -> settlePollMs = v
                    "sstable" -> settleStableMs = v
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
            "swFast=${swipeFastSteps}x$swipeFastMs,swSlow=${swipeSlowSteps}x$swipeSlowMs,swBack=$swipeBackMs," +
            "swipeTotal=$swipeTotalMs" + if (isDefault) " (default)" else ""
}
