package com.bettergi.pocket.scan

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

    /** 按 visit 步骤类型（`do`）累计：[次数, 纳秒]。用于一次跑完拿到完整分解。 */
    private val steps = LinkedHashMap<String, LongArray>()

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
            " | total=${ms(clickNanos + swipeNanos + ocrNanos + frameNanos + voteNanos)}ms" +
            " || steps " + steps.entries.sortedByDescending { it.value[1] }
            .joinToString(" ") { "${it.key}=${it.value[0]}x/${ms(it.value[1])}ms" }
    }
}
