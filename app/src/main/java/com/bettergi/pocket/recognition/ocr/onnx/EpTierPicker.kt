package com.bettergi.pocket.recognition.ocr.onnx

/**
 * EP（Execution Provider）协商策略：候选序 + 首启基准定档 + 运行期降档。
 *
 * 纯逻辑（无 Android/ORT 依赖），JVM 可直接单测；输入是 [DeviceCapabilities] 这份事实快照。
 * 移植自 irminsul `com.esc.irminsul.ocr.EpTierPicker`（方案 §6.1），2026-09-26 起改为**按硬件生成候选序**。
 */
object EpTierPicker {

    enum class Tier(val label: String) {
        /** 高通 Hexagon NPU。⚠️ 运行库不在 ORT 的 AAR 里，需自行随包携带高通 SDK 的 `libQnnHtp.so` 等。 */
        QNN("QNN"),

        /** 安卓通用 NPU 抽象。**默认不进候选**，见 [candidatesFor] 的 NNAPI 条。 */
        NNAPI("NNAPI"),
        XNNPACK("XNNPACK"),
        CPU("CPU"),
    }

    /**
     * 按设备事实给出**允许尝试**的档位（按优先级，CPU 恒为最后兜底）。
     *
     * 三条硬约束都来自实测，不是保守起见：
     * - **NNAPI 默认排除**：`createSession(addNnapi)` 在 BlueStacks 上**原生 SIGSEGV**（2026-09-04），
     *   Java 层 try/catch 抓不到 ⇒ 启动即崩；ppocr-bench 在华为 P20 上对 1.30 的 NNAPI 也记了同样的
     *   SIGSEGV。只有显式 [allowRisky] 且非模拟器才放回候选。
     * - **XNNPACK 也算"风险档"**：Kirin 970 真机 `createSession` **挂起**（不是抛异常，抓不到）。
     *   我们**没有任何一份"XNNPACK 在某机型上确实更快"的实测**，所以它同样要 [allowRisky] 才进候选；
     *   一旦挂起，后续建会话也可能被 ORT 的全局锁拖住 ⇒ 拿启动去赌不划算。
     * - **QNN 只在库真能加载时进候选**：AAR 不含高通运行库，[DeviceCapabilities.qnnLibsLoadable]
     *   为假时 `addQnn` 的行为未测，不去试。
     *
     * ⇒ 默认序就是 `[QNN?] + CPU`：**与升级前"直接选 CPU"的行为一致**，不新增任何启动风险。
     *    想在某机型上试 XNNPACK/NNAPI，用 `ocrEp=risky` 显式打开并**当场实测**。
     */
    fun candidatesFor(
        caps: DeviceCapabilities,
        allowRisky: Boolean = false,
    ): List<Tier> {
        val out = ArrayList<Tier>(4)
        // 双闸：厂商必须是高通 **且** 运行库真能加载。probeQnnLibs 已按厂商闸过一次，
        // 这里再闸一次是为了让纯函数自身不自相矛盾（单测直接构造假数据时也能守住）。
        if (caps.qnnLibsLoadable && caps.socVendor == "qualcomm") out += Tier.QNN
        if (allowRisky) {
            if (!caps.looksLikeEmulator && caps.socVendor != "hisilicon") out += Tier.NNAPI
            if (caps.socVendor != "hisilicon") out += Tier.XNNPACK
        }
        out += Tier.CPU
        return out
    }

    /**
     * intra-op 线程数的**候选集**（首启实测择优，见 `OnnxOcrEngine.benchmarkIntra`）。
     *
     * ★ 2026-09-26 真机复测把这里原先那句「1.30 把最优点从 2 翻到 4」**推翻了**，别照抄旧结论。
     * 同一台 Kirin 970、同一个 1.30 构建、4 槽一轮 ms（intra 1/2/4）：
     *   · 15:5x 装机后首测        660 / 384 / **254**  ← 唯一一次 4 更快
     *   · 16:54 游戏在世界里      778 / 643 / 1420
     *   · 16:55 同上                — / 646 / 1340
     *   · 17:08 游戏 force-stop   653 / 406 /  940
     * 四里有三轮 intra=4 比 2 **慢 2.2~2.3×**，且把游戏停干净也救不回来（不是"游戏抢大核"）。
     * ⇒ 1.20 的「超过 2 就急剧退化」在 1.30 上**依然成立**；那次 254ms 是孤例读数。
     *
     * 那为什么还要实测、不直接写死 2：① 上面这组数说明**跨会话绝对值能漂 ±60%**
     * （intra=2 在 406~818ms 之间），写死别的机型/版本没有依据；② 但既然实测本身这么抖，
     * 择优就必须带**余量**和**足够重复次数**，否则它会把 2 换成 4、白丢 2.3×
     * （15:57 那次就是这么选的）。⇒ 已由 #74 落地：`benchmarkIntra` runs 3→**15**，
     * 换档判据改走 [pickIntraOp]（挑战者必须比 incumbent 快 ≥20% 才换）。
     *
     * 上界取 `bigCoreCount`（读不到就退回 2，**不猜**）：小核上开满线程只会加剧同步开销。
     */
    fun intraOpCandidates(caps: DeviceCapabilities): List<Int> {
        val ceiling = if (caps.bigCoreCount > 0) caps.bigCoreCount.coerceAtMost(8) else 2
        return listOf(2, 4, 6).map { it.coerceAtMost(ceiling).coerceAtLeast(1) }.distinct()
    }

    /** 换档所需的最小优势（相对 incumbent 的中位耗时，%）。见 [pickIntraOp]。 */
    const val INTRA_SWITCH_MARGIN_PCT = 20

    /**
     * intra-op **换档**判据（#74）：默认留住 incumbent，除非有档位**快出余量**才换。
     *
     * 为什么不是"取最小中位数"：真机同机构建的实测里 `intra=4` 五轮有四轮比 2 慢 2.2~2.3×，
     * 而基准本身只有 3 次、无余量时，一次抖动读数（15:57 那次 254ms）就足以把生产路径
     * 永久调到慢档。换档是**不可逆的**（服务活多久就多久），所以偏向不动。
     *
     * 判式：`best` 为实测最小中位者；仅当 `best` 比 `incumbent` 快 ≥[INTRA_SWITCH_MARGIN_PCT]%
     * （即 `best·100 ≤ inc·(100−margin)`）才返回 `best`，否则返回 `incumbent`。
     *
     * 退化情形一律**保守**：
     * - 表空 / 只有 incumbent ⇒ incumbent；
     * - incumbent 没测到（或测得 `Long.MAX_VALUE` = 建会话失败）⇒ 取实测最小者（没得比，不动只会更糟）；
     * - 恰好等于门槛 ⇒ 不换（`≤` 里取严格更快才换，边界留给"不动"）。
     */
    fun pickIntraOp(
        mediansMs: Map<Int, Long>,
        incumbent: Int,
        marginPct: Int = INTRA_SWITCH_MARGIN_PCT,
    ): Int {
        if (mediansMs.isEmpty()) return incumbent
        val best = mediansMs.entries.minByOrNull { it.value }?.key ?: return incumbent
        val inc = mediansMs[incumbent] ?: return best      // incumbent 没测到 ⇒ 没基准可守
        val bestMs = mediansMs.getValue(best)              // ⚠️ 比的是**毫秒**；`best` 是线程数，别混用
        if (inc == Long.MAX_VALUE) return best             // incumbent 建会话失败 ⇒ 必须换
        if (best == incumbent) return incumbent
        if (bestMs * 100L >= inc * (100L - marginPct)) return incumbent   // 优势不足（含恰好等于门槛）⇒ 留住原档
        return best
    }

    /** 首启基准：可用档中取中位延迟最小者；无可用数据则落 CPU */
    fun pickByBenchmark(
        mediansMs: Map<Tier, Long>,
        available: Set<Tier>,
    ): Tier {
        return available
            .filter { it in mediansMs }
            .minByOrNull { mediansMs.getValue(it) }
            ?: Tier.CPU
    }

    /** 运行期降档：当前档超时/异常 → 下一档；CPU 为兜底，不再降 */
    fun degrade(current: Tier, order: List<Tier>): Tier? {
        val idx = order.indexOf(current)
        if (idx < 0 || idx >= order.lastIndex) return null
        return order[idx + 1]
    }
}
