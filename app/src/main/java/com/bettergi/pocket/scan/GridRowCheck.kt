package com.bettergi.pocket.scan

/**
 * **行级闭环**（2026-09-16）：用**内容键**直接量出「上一页 → 本页」实际前进了多少件，
 * 判定是否发生**跳行漏件** —— 比 fpband 落地条带更硬（不依赖图像测量、不受条带不可测影响）。
 *
 * ## 为什么需要它
 * 翻页若**过滚**，被跳过的那些件在**当前视图上方**（第 0 行以上不可见）⇒ 任何一次点击都够不到
 * ⇒ 且**不产生重复件** ⇒ 完全静默。fpband 只在 score/双峰等条件满足时给读数（实测有 15~40% 的页
 * 回退到特征锁甚至不可测），所以需要一个**与图像无关**的第二判据。
 *
 * ## 判据（只用相邻两页的内容键，零额外 IO）
 * 列表是**行主序固定网格**（`cols` 列）。设上一页的 21 格内容键为 `prevKeys`（**含空串占位**，
 * 占位必须保留 ⇒ 下标即全局序号），本页首格键在全局序列里的位置 = 上一页某格的位置 + 前进量 A：
 * ```
 * A = anchorIdx − curKeys.indexOf(prevKeys[anchorIdx])
 * ```
 * 实测：A = pageSize（= cols×traverseRows）⇒ 正常推进；A < pageSize ⇒ 欠滚重叠（无害，去重即消）；
 * **A ≥ pageSize 或锚点在下一页找不到 ⇒ 跳行**（≥1 行漏件）。
 * 用**最后两格**互为校验（单格读失败会造成误判）⇒ 两次估计一致才采信。
 *
 * ## 几何依据（阈值为什么正好是一页卡片数）
 * 每次点击只在**卡片半高**（126px @3200）内有效 ⇒ 单页覆盖带 = 2×行距 + 卡片高 = 837px；
 * 跨页空隙 = A − 837px。A ≤ 876px（= 3 行距）时空隙 ≤ 39px，正好落在**卡片间隙**里 ⇒ 无损；
 * A ≥ 21 件（≈ 900px 起）空隙超过一个卡片的有效区 ⇒ 必丢件。⇒ 阈值取 `pageSize` 即对应关系。
 */
object GridRowCheck {

    /**
     * 判定：正常推进 / 欠滚重叠（无害）/ **滑空（前进≈0，需回补）** / 跳行（需回补）/ 无法判定（锚点全丢）。
     *
     * ⚠️ 2026-09-16 补 [STALL]：原判据只防"前进太多"（SKIP），**滑空（前进≈0）被判 OVERLAP（"安全"）** ✗
     * —— 实测 v7 页日志有 3 次「开页清零（原 20）」（page 2/12/64：该页 21 格全重复 = 滑空页）。
     * 滑空页本身不直接丢件（内容不变 ⇒ 覆盖已含），但它意味着**页面相位可能整体错位一行**
     * ⇒ 该页 3 行的点击落在卡片缝里 ⇒ 那一行从未被点到（实测缺 7 件 = **恰好一行卡片数**）。
     * ⇒ 判 STALL 后按 SKIP 同路径回补（退 1 行重遍历 ⇒ 覆盖位移 ±1 行的并集 ✓）。
     */
    enum class Verdict { OK, OVERLAP, STALL, SKIP, UNKNOWN }

    /**
     * @param advance    实测前进量（件）；null = 锚点全部找不到（前进 ≥ 一页或读数全丢）
     * @param verdict    判定
     * @param skipped    估计漏件数（仅当 advance 可测时为 `advance − pageSize`，否则 null）
     */
    data class Result(val advance: Int?, val verdict: Verdict, val skipped: Int?)

    /**
     * @param prevKeys 上一页的键序列（**长度必须 = 上一页实际尝试的格数**，读失败用空串占位）
     * @param curKeys  本页的键序列（同上）
     * @param pageSize 一页卡片数（cols × traverseRows）
     */
    fun check(
        prevKeys: List<String>,
        curKeys: List<String>,
        pageSize: Int,
        /** 前进量 ≤ 此值即判 STALL（默认 = 半行卡片数，由调用方按 cols 传入）。 */
        stallBelow: Int = 0,
    ): Result {
        if (pageSize <= 1 || prevKeys.isEmpty() || curKeys.isEmpty()) return Result(null, Verdict.UNKNOWN, null)
        val ests = ArrayList<Int>(2)
        for (back in 1..2) {
            val ai = prevKeys.size - back
            if (ai < 0) continue
            val anchor = prevKeys[ai]
            if (anchor.isEmpty()) continue
            val m = curKeys.indexOf(anchor)
            if (m >= 0) ests += (ai - m)
        }
        if (ests.isEmpty()) {
            // 最后两格都读到了、却都不在本页 ⇒ 本页整体前进了 ≥ 一页 ⇒ 跳行
            val anchorsRead = (prevKeys.size - 1).let { it >= 0 && prevKeys[it].isNotEmpty() } ||
                (prevKeys.size - 2).let { it >= 0 && prevKeys[it].isNotEmpty() }
            return if (anchorsRead) Result(null, Verdict.SKIP, null) else Result(null, Verdict.UNKNOWN, null)
        }
        // 两次估计一致（或只有一次）才采信；不一致 ⇒ 以较小者为准并标记 UNKNOWN 由调用方决定
        val advance = ests.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
        val verdict = when {
            advance >= pageSize -> Verdict.SKIP
            stallBelow > 0 && advance <= stallBelow -> Verdict.STALL
            advance >= pageSize - 1 -> Verdict.OK
            else -> Verdict.OVERLAP
        }
        return Result(advance, verdict, if (verdict == Verdict.SKIP) advance - pageSize else 0)
    }
}
