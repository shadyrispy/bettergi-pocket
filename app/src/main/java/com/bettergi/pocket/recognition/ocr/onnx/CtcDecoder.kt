package com.bettergi.pocket.recognition.ocr.onnx

import java.nio.FloatBuffer
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
 * GC P0（工单 B）：[decode] 现以 **FloatBuffer 视图**为主实现（ORT 输出免物化，逐位一致：
 * 读的是同一块内存、同样的比较/softmax 浮点次序），旧 FloatArray 签名零拷贝 wrap 后委托。
 * 移植自 irminsul `com.esc.irminsul.ocr.CtcDecoder`。
 */
object CtcDecoder {

    data class Result(val text: String, val confidence: Float)

    fun decode(logits: FloatArray, t: Int, c: Int, dict: List<String>, declaredOutput: String = "auto"): Result =
        decode(FloatBuffer.wrap(logits), t, c, dict, declaredOutput)

    fun decode(logits: FloatBuffer, t: Int, c: Int, dict: List<String>, declaredOutput: String = "auto"): Result {
        // 绝对索引基准 = 传入缓冲的 position（旧 FloatArray 语义从 0 读；
        // ORT 输出的 position 恒 0，这里按 position 归一化以兼容任意切片视图）。
        val base0 = logits.position()
        val sb = StringBuilder()
        var prev = -1
        var keep = 0
        var sum = 0.0
        for (ti in 0 until t) {
            val base = base0 + ti * c
            var best = 0
            var maxv = logits.get(base)
            for (ci in 1 until c) {
                val v = logits.get(base + ci)
                if (v > maxv) { maxv = v; best = ci }
            }
            if (best == 0) { prev = 0; continue }          // blank
            if (best == prev) continue                      // 相邻去重
            prev = best
            val idx = best - 1
            if (idx in dict.indices) sb.append(dict[idx])
            // P3：置信度来源由 manifest `recOutputType` 显式声明（OnnxModelAssets 读取）。
            // "softmax" = 导出已含 softmax，直接当概率；"logits" = 必做 softmax；
            // 缺省 "auto" 保持旧行为（按数值范围 0..1 猜）——该启发式只是兼容缺省，
            // 换模型后应在 manifest.json 显式声明，别再靠猜。
            val prob = when (declaredOutput) {
                "softmax" -> maxv.toDouble()
                "logits" -> softmaxAt(logits, base, c, maxv)
                else ->
                    if (maxv in 0f..1f) {
                        maxv.toDouble()                     // 导出已含 softmax（旧启发式）
                    } else {
                        softmaxAt(logits, base, c, maxv)
                    }
            }
            keep++
            sum += prob
        }
        return Result(sb.toString(), if (keep > 0) (sum / keep).toFloat() else 0f)
    }

    /** 对第 [base] 个时间步做真 softmax（logits 场景）。 */
    private fun softmaxAt(logits: FloatBuffer, base: Int, c: Int, maxv: Float): Double {
        var denom = 0.0
        for (ci in 0 until c) denom += exp(logits.get(base + ci).toDouble() - maxv.toDouble())
        return 1.0 / denom
    }
}
