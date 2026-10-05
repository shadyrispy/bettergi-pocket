package com.bettergi.pocket.recognition.ocr

import java.util.concurrent.atomic.AtomicLong

/**
 * rec 单槽内部分段探针（2026-10-05，P0）：预处理 / ORT 推理 / CTC 解码各花多少。
 *
 * 为什么住在 recognition 而不是 [com.bettergi.pocket.scan.PerfProbe]：本仓依赖方向是
 * scan → recognition，`recognition` 包内文件至今**没有一条** import `scan` 包；分段计时只有
 * `OnnxPaddleOcrService.recognizeLine` 内部看得见，反向 import 会为一条记账打破分层。
 * ⇒ 这里自记，`PerfProbe.summary()` 在打印时把它拼进同一行。
 *
 * 口径：
 * - `preNanos` = crop 视图 + resize + convertTo + split + 3×JNI 整块读 + 3×arraycopy（∝ 输入宽）
 * - `runNanos` = 建输入张量 + ORT `session.run`（回调在张量 close 前触发 ⇒ 不含解码）
 * - `decNanos` = `CtcDecoder.decode` 的 T×C 逐元素扫（T = 宽/8，C = 6906）
 * - `widthPx`  = 本槽归一到高 48 后的输入宽；累计成 Σw 后可以直接算 **ms/px**
 *
 * 纯记账、不做任何决策；推理失败的槽不产生样本。
 */
object OcrStageProbe {
    private val calls = AtomicLong()
    private val preNanos = AtomicLong()
    private val runNanos = AtomicLong()
    private val decNanos = AtomicLong()
    private val widthPx = AtomicLong()

    fun add(pre: Long, run: Long, dec: Long, width: Int) {
        calls.incrementAndGet()
        preNanos.addAndGet(pre)
        runNanos.addAndGet(run)
        decNanos.addAndGet(dec)
        widthPx.addAndGet(width.toLong())
    }

    fun reset() {
        calls.set(0); preNanos.set(0); runNanos.set(0); decNanos.set(0); widthPx.set(0)
    }

    private fun ms(n: Long): Long = n / 1_000_000

    private fun perPx(n: Long): String =
        if (widthPx.get() <= 0) "-" else String.format("%.3f", ms(n).toDouble() / widthPx.get())

    /** 一行片段，供 `PerfProbe.summary()` 拼接；无样本时打 0（脚本解析口径稳定）。 */
    fun summary(): String =
        "ocrStage n=${calls.get()} Σw=${widthPx.get()}px" +
            " pre=${ms(preNanos.get())}ms run=${ms(runNanos.get())}ms dec=${ms(decNanos.get())}ms" +
            " perPx[pre=${perPx(preNanos.get())} run=${perPx(runNanos.get())} dec=${perPx(decNanos.get())}]ms"
}
