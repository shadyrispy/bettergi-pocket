package com.bettergi.pocket.scan

import com.bettergi.pocket.recognition.ocr.OcrStageProbe

/**
 * 只读性能探针（2026-09-12）：累计**单次扫描内**的关键调用耗时，供 `scan finished` 之后打印一行。
 *
 * 用途：为「就绪信号廉价化 / ROI 级 BGR 转换」这类改造提供**分量实测值**，
 * 见 `dsl/verify/_audit/IMAGE-PATH-COST.md` §8（探针 1/3 的"真实运行口径"）。
 *
 * ⚠️ 设计约束（勿破）：
 * - **不做任何行为决策**，不改变时序、不改变调用顺序；纯记账。
 * - 开销 = 每次调用 2 次 `System.nanoTime()`（~50ns），相对 click/OCR 的数 ms 可忽略。
 * - 用 `synchronized` 而非 atomic 数组：调用频率是"每格个位数"，不在热路径上。
 */
object PerfProbe {
    private val lock = Any()

    private var clicks = 0L
    private var clickNanos = 0L
    private var clickMax = 0L
    private var clickFail = 0L

    private var swipes = 0L
    private var swipeNanos = 0L
    private var swipeMax = 0L

    private var ocrCalls = 0L
    private var ocrNanos = 0L
    private var ocrMax = 0L

    private var frames = 0L
    private var frameNanos = 0L
    private var frameMax = 0L

    private var votes = 0L
    private var voteNanos = 0L
    private var voteMax = 0L

    /** S1 裁片拷贝（整帧→面板裁片）耗时；`crop=0` 时恒 0 次。 */
    private var crops = 0L
    private var cropNanos = 0L
    private var cropMax = 0L

    /** S2b 流水线（#69）：pipeWait=send 背压挂起/格；consOcr=消费者 OCR+解析/格；joinWait=页末 join/页。 */
    private var pipeWaits = 0L
    private var pipeWaitNanos = 0L
    private var consOcrs = 0L
    private var consOcrNanos = 0L
    private var joinWaits = 0L
    private var joinWaitNanos = 0L
    /**
     * #230/§13.5：消费者侧两个**静默分支**的计数。
     * - `pipeDiscards`：被判"过期/取消"而丢弃的 job。正常一轮**必须为 0** —— 纪元打错
     *   （#69 根因一：打了自增前的值）时它是 292 而日志一行看不到，"同序同值"只会以
     *   "件数不等"失败，归因不到这条上。
     * - `pipeFails`：`pipeProcess` 被 catch 吞掉的异常数。干跑里 `isReturnDefaultValues`
     *   把 Android Log 全吞 ⇒ 没有这个计数，"消费抛异常"与"读到空"**原理上不可分**。
     */
    private var pipeDiscards = 0L
    private var pipeFails = 0L

    /** 按 visit 步骤类型（`do`）累计：[次数, 纳秒]。用于一次跑完拿到完整分解。 */
    private val steps = LinkedHashMap<String, LongArray>()

    fun addPipeWait(nanos: Long) = synchronized(lock) {
        pipeWaits++
        pipeWaitNanos += nanos
    }

    fun addConsOcr(nanos: Long) = synchronized(lock) {
        consOcrs++
        consOcrNanos += nanos
    }

    fun addJoinWait(nanos: Long) = synchronized(lock) {
        joinWaits++
        joinWaitNanos += nanos
    }

    fun addPipeDiscard() = synchronized(lock) { pipeDiscards++ }

    fun addPipeFail() = synchronized(lock) { pipeFails++ }

    /** 单测断言入口（#69 判据①「epoch 丢弃负对照」）：正常一轮两者都必须是 0。 */
    fun pipeDiscardCount(): Long = synchronized(lock) { pipeDiscards }

    fun pipeFailCount(): Long = synchronized(lock) { pipeFails }

    fun addClick(nanos: Long, ok: Boolean) = synchronized(lock) {
        clicks++
        clickNanos += nanos
        if (nanos > clickMax) clickMax = nanos
        if (!ok) clickFail++
    }

    fun addSwipe(nanos: Long) = synchronized(lock) {
        swipes++
        swipeNanos += nanos
        if (nanos > swipeMax) swipeMax = nanos
    }

    fun addOcr(nanos: Long) = synchronized(lock) {
        ocrCalls++
        ocrNanos += nanos
        if (nanos > ocrMax) ocrMax = nanos
    }

    /**
     * `freshFrame()` 单次耗时。
     * ⚠️ 关键：它包含「**等一个新帧**」的等待（投影 ~30fps ⇒ 平均 ~33ms）
     * ——这部分**不计入** nav/panel/settle 任何等待计数器，是「其它」里最容易被漏掉的大块。
     */
    fun addFrame(nanos: Long) = synchronized(lock) {
        frames++
        frameNanos += nanos
        if (nanos > frameMax) frameMax = nanos
    }

    /** `vote` 像素判据单次耗时（含 `ctx.frame` 复用，通常无抓帧）。 */
    fun addVote(nanos: Long) = synchronized(lock) {
        votes++
        voteNanos += nanos
        if (nanos > voteMax) voteMax = nanos
    }

    /** S1 裁片拷贝单次耗时（#69）。`crop=0` 时不会被调用。 */
    fun addCrop(nanos: Long) = synchronized(lock) {
        crops++
        cropNanos += nanos
        if (nanos > cropMax) cropMax = nanos
    }

    /**
     * 按 visit 步骤类型累计耗时。
     * ⚠️ 嵌套步骤（`ifMatch.then`）会**重复计入**父步骤 ⇒ 读 `ifMatch` 时需注意；
     * 三个 scan 流程无 `ifMatch`，不受影响。
     */
    fun addStep(vop: String, nanos: Long) = synchronized(lock) {
        val e = steps.getOrPut(vop) { LongArray(2) }
        e[0]++
        e[1] += nanos
    }

    fun reset() = synchronized(lock) {
        clicks = 0; clickNanos = 0; clickMax = 0; clickFail = 0
        swipes = 0; swipeNanos = 0; swipeMax = 0
        ocrCalls = 0; ocrNanos = 0; ocrMax = 0
        frames = 0; frameNanos = 0; frameMax = 0
        votes = 0; voteNanos = 0; voteMax = 0
        crops = 0; cropNanos = 0; cropMax = 0
        pipeWaits = 0; pipeWaitNanos = 0; consOcrs = 0; consOcrNanos = 0
        joinWaits = 0; joinWaitNanos = 0
        pipeDiscards = 0; pipeFails = 0
        OcrStageProbe.reset()
        steps.clear()
    }

    private fun avg(sum: Long, n: Long): Long = if (n > 0) sum / n / 1_000_000 else 0

    private fun ms(nanos: Long): Long = nanos / 1_000_000

    /** 单行结构化摘要；无样本的分量仍打印（便于脚本稳定解析）。 */
    fun summary(): String = synchronized(lock) {
        "click n=$clicks avg=${avg(clickNanos, clicks)}ms max=${ms(clickMax)}ms fail=$clickFail" +
            " | swipe n=$swipes avg=${avg(swipeNanos, swipes)}ms max=${ms(swipeMax)}ms" +
            " | ocr n=$ocrCalls avg=${avg(ocrNanos, ocrCalls)}ms max=${ms(ocrMax)}ms" +
            " | frame n=$frames avg=${avg(frameNanos, frames)}ms max=${ms(frameMax)}ms" +
            " | vote n=$votes avg=${avg(voteNanos, votes)}ms max=${ms(voteMax)}ms" +
            " | crop n=$crops avg=${avg(cropNanos, crops)}ms max=${ms(cropMax)}ms" +
            // rec 内部分段住在 recognition 侧（依赖方向 scan→recognition），见 OcrStageProbe
            " | " + OcrStageProbe.summary() +
            // S2b 流水线（#69 §7）：pipe=0 时恒 0 次；口径见设计文档
            " | pipeWait n=$pipeWaits avg=${avg(pipeWaitNanos, pipeWaits)}ms" +
            " | consOcr n=$consOcrs avg=${avg(consOcrNanos, consOcrs)}ms" +
            " | joinWait n=$joinWaits avg=${avg(joinWaitNanos, joinWaits)}ms" +
            " | pipeDiscard n=$pipeDiscards | pipeFail n=$pipeFails" +
            " | total=${ms(clickNanos + swipeNanos + ocrNanos + frameNanos + voteNanos)}ms" +
            " || steps " + steps.entries.sortedByDescending { it.value[1] }
            .joinToString(" ") { "${it.key}=${it.value[0]}x/${ms(it.value[1])}ms" }
    }
}
