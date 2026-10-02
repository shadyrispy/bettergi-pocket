package com.bettergi.pocket.recognition.ocr.onnx

import kotlin.math.exp

/**
 * CTC 贪心解码（纯 Kotlin，JVM 可单测真实路径）。
 *
 * 语义（与 PP-OCR / yas 反编译契约对齐）：
 * - 每个时间步对 logits[time][vocab] 取 argmax（vocab 的 index 0 = CTC blank）
 * - blank(0) 跳过；相邻重复去重；非 blank 类 id 按 `best - 1` 映射到 `dict[best-1]`
 * - 置信度 = 保留（非 blank）时间步的 argmax 概率均值；logits 未 softmax 时现场做真 softmax
 *
 * 输入为模型原始输出 logits，逻辑 shape [T, C]（行主序展平）。
 * 移植自 irminsul `com.esc.irminsul.ocr.CtcDecoder`。
 */
object CtcDecoder {

    data class Result(val text: String, val confidence: Float)

    fun decode(logits: FloatArray, t: Int, c: Int, dict: List<String>): Result {
        val sb = StringBuilder()
        var prev = -1
        var keep = 0
        var sum = 0.0
        for (ti in 0 until t) {
            val base = ti * c
            var best = 0
            var maxv = logits[base]
            for (ci in 1 until c) {
                val v = logits[base + ci]
                if (v > maxv) { maxv = v; best = ci }
            }
            if (best == 0) { prev = 0; continue }          // blank
            if (best == prev) continue                      // 相邻去重
            prev = best
            val idx = best - 1
            if (idx in dict.indices) sb.append(dict[idx])
            val prob = if (maxv in 0f..1f) {
                maxv.toDouble()                             // 导出已含 softmax
            } else {
                var denom = 0.0
                for (ci in 0 until c) denom += exp(logits[base + ci].toDouble() - maxv.toDouble())
                1.0 / denom
            }
            keep++
            sum += prob
        }
        return Result(sb.toString(), if (keep > 0) (sum / keep).toFloat() else 0f)
    }
}
