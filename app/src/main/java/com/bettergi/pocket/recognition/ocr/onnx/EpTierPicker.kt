package com.bettergi.pocket.recognition.ocr.onnx

/**
 * EP（Execution Provider）协商策略：三档分级 + 首启基准定档 + 运行期降档。
 *
 * 纯逻辑（无 Android/ORT 依赖），JVM 可直接单测。
 * 移植自 irminsul `com.esc.irminsul.ocr.EpTierPicker`（方案 §6.1）。
 */
object EpTierPicker {

    enum class Tier(val label: String) {
        NNAPI("NNAPI"),
        XNNPACK("XNNPACK"),
        CPU("CPU"),
    }

    /**
     * 档位优先级：越靠前越快（成功前提下）。
     *
     * ⚠️ **NNAPI 不进默认基准序**（2026-09-04 模拟器实证）：Bluestacks 上
     * `createSession(addNnapi)` 在 libonnxruntime.so 内原生段错误（SIGSEGV），
     * Java 层 try/catch 无法捕获，直接杀死整个进程——且发生在 app 启动即崩。
     * NNAPI 依赖厂商驱动，模拟器/部分 ROM 上不可靠；XNNPACK 是纯软件实现，无此风险。
     * 如需评估 NNAPI，请在真机上显式开启（后续可加 ocrEp 设置项）。
     */
    val DEFAULT_ORDER = listOf(Tier.XNNPACK, Tier.CPU)

    /** 首启基准：可用档中取中位延迟最小者；无可用数据则落 CPU */
    fun pickByBenchmark(
        mediansMs: Map<Tier, Long>,
        available: Set<Tier>,
    ): Tier {
        return DEFAULT_ORDER
            .filter { it in available && it in mediansMs }
            .minByOrNull { mediansMs.getValue(it) }
            ?: Tier.CPU
    }

    /** 运行期降档：当前档超时/异常 → 下一档；CPU 为兜底，不再降 */
    fun degrade(current: Tier): Tier? {
        val idx = DEFAULT_ORDER.indexOf(current)
        if (idx < 0 || idx >= DEFAULT_ORDER.lastIndex) return null
        return DEFAULT_ORDER[idx + 1]
    }
}
