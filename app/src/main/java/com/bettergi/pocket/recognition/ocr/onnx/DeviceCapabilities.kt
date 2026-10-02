package com.bettergi.pocket.recognition.ocr.onnx

import android.os.Build

/**
 * OCR 的 EP / 线程数决策所需的**设备事实**（一次采集，纯数据，便于单测）。
 *
 * 为什么要单独一层：EP 的可用性**高度依赖具体 SoC 与 ROM**，而且失败方式不体面 ——
 * 已实测到两种：`addNnapi` 在 BlueStacks 上 **原生 SIGSEGV**（Java 层捕获不到，直接杀进程，
 * 且发生在启动即崩）；XNNPACK 在华为 Kirin 970 上 **createSession 挂起**。
 * ⇒ 所以候选序必须**按能力白名单生成**，绝不能"挨个试一遍看哪个不崩"。
 *
 * 数据出处见 `dsl/verify/_audit/HW-PERF-20260926.md` §6 与 [EpTierPicker] 的注释。
 */
data class DeviceCapabilities(
    /** 归一化后的 SoC 厂商标识：`qualcomm` / `hisilicon` / `mediatek` / `samsung` / `google` / `unknown` */
    val socVendor: String,
    /** 原始型号串（诊断日志用，可能为空串） */
    val socModel: String,
    /** 是否 arm64（QNN / NNAPI 的驱动只在这上面有意义；x86 模拟器一律走 CPU） */
    val isArm64: Boolean,
    /** 在线 CPU 总数 */
    val cpuCount: Int,
    /** **最高频那一簇**的核数（big.LITTLE 里的"大核簇"宽度）；取不到时为 0 */
    val bigCoreCount: Int,
    /** 大核簇的最高频率（kHz）；取不到时为 0 */
    val bigCoreMaxFreqKHz: Long,
    /** QNN 运行库是否可加载（`libQnnHtp.so` 等，需自行随包携带，AAR 不含） */
    val qnnLibsLoadable: Boolean,
) {

    /** 看起来是 x86 模拟器/开发机（BlueStacks、Genymotion、goldfish…） */
    val looksLikeEmulator: Boolean
        get() = !isArm64 ||
            listOf("goldfish", "ranchu", "vbox86", "bluestacks", "genymotion", "android_x86")
                .any { haystack().contains(it) }

    /** 用于日志的一行摘要 */
    fun summary(): String =
        "soc=$socVendor/$socModel arm64=$isArm64 cpus=$cpuCount big=$bigCoreCount@${bigCoreMaxFreqKHz}kHz " +
            "qnn=$qnnLibsLoadable emu=$looksLikeEmulator"

    private fun haystack(): String =
        (socVendor + socModel + Build.HARDWARE.orEmpty() + Build.FINGERPRINT.orEmpty()).lowercase()

    companion object {

        fun collect(): DeviceCapabilities {
            val (vendor, model) = readSoc()
            val freqs = readClusterMaxFreqs()
            val top = freqs.maxOrNull() ?: 0L

            return DeviceCapabilities(
                socVendor = vendor,
                socModel = model,
                isArm64 = abiIsArm64(),
                cpuCount = Runtime.getRuntime().availableProcessors(),
                bigCoreCount = topCount(freqs, caps0 = Runtime.getRuntime().availableProcessors()),
                bigCoreMaxFreqKHz = top,
                qnnLibsLoadable = probeQnnLibs(),
            )
        }

        /**
         * SoC 厂商/型号。API 31+ 有权威字段；更老的 ROM（含我们的 P20 = API 29 时代）回退到
         * `Build.HARDWARE`/`ro.hardware`/`ro.board.platform` 的关键词匹配。
         */
        private fun readSoc(): Pair<String, String> {
            val mfr = runCatching { Build.SOC_MANUFACTURER }.getOrNull().orEmpty().lowercase()
            val mdl = runCatching { Build.SOC_MODEL }.getOrNull().orEmpty()
            if (mfr.isNotEmpty() && mfr != "unknown") {
                return normalizeVendor(mfr) to mdl
            }
            val hw = sequenceOf(
                runCatching { Build.HARDWARE }.getOrNull(),
                readProp("ro.hardware"),
                readProp("ro.board.platform"),
            ).filterNotNull().joinToString(" ").lowercase()
            return normalizeVendor(hw) to mdl.ifEmpty { hw }
        }

        private fun normalizeVendor(s: String): String = when {
            s.contains("qcom") || s.contains("qualcomm") || s.contains("snapdragon") -> "qualcomm"
            s.contains("hisilicon") || s.contains("kirin") || s.contains("hi3") || s.contains("hi6") -> "hisilicon"
            s.contains("mediatek") || s.contains("mt6") || s.contains("mt8") || s.contains("helio") -> "mediatek"
            s.contains("samsung") || s.contains("exynos") || s.contains("s5p") || s.contains("gs") -> "samsung"
            s.contains("google") || s.contains("tensor") || s.contains("zuma") -> "google"
            s.contains("unisoc") || s.contains("spreadtrum") -> "unisoc"
            else -> "unknown"
        }

        private fun readProp(key: String): String? = runCatching {
            val c = Class.forName("android.os.SystemProperties")
            (c.getMethod("get", String::class.java).invoke(null, key) as? String)
        }.getOrNull()

        /**
         * **逐核**读 `cpuN/cpufreq/scaling_max_freq`（每核一个文件）。
         *
         * ⚠️ 别用 `cpufreq/policyN/`：policy 是**每簇一个目录**，不是每核一个。
         *    华为 P20 上只有 `policy0`/`policy1` 两个目录，按 policy 数出来的"大核数"会是 **1**
         *    而不是 4 —— 实测踩过，直接把 [bigCoreCount] 打成 1、让 intra 候选退化成单值，
         *    自动择优于是静默失效、悄悄退回写死的 2。
         */
        private fun readClusterMaxFreqs(): List<Long> = perCoreValues { n ->
            "/sys/devices/system/cpu/cpu$n/cpufreq/scaling_max_freq"
        } ?: perCoreValues { n -> "/sys/devices/system/cpu/cpu$n/cpu_capacity" } ?: emptyList()

        /** 按 cpu0..cpu15 逐个读一个整数；一个都读不到返回 null（区别于"读到了"的空表）。 */
        private fun perCoreValues(pathOf: (Int) -> String): List<Long>? {
            val out = ArrayList<Long>(16)
            for (n in 0..15) {
                val v = runCatching {
                    java.io.File(pathOf(n)).takeIf { it.canRead() }?.readText()?.trim()?.toLongOrNull()
                }.getOrNull() ?: continue
                out += v
            }
            return out.ifEmpty { null }
        }

        /**
         * 最高那一档占几个核。
         * - 只有一个值（同构核 / 读不到差异）⇒ 认为**全部核都是"大核"**，返回 [caps0]（在线核数）。
         *   同构机上把候选放宽到 {2,4,6} 是对的，反正后面有实测择优兜着。
         * - 多个值 ⇒ 数最高档的个数（big.LITTLE 的簇宽）。
         */
        internal fun topCount(freqs: List<Long>, caps0: Int): Int {
            if (freqs.isEmpty()) return 0 // 真读不到 ⇒ 不猜，调用方退回保守候选
            val top = freqs.max()
            val n = freqs.count { it == top }
            return if (freqs.distinct().size == 1) caps0.coerceAtLeast(n) else n
        }

        /**
         * QNN 的 HTP 运行库**不在 ORT 的 AAR 里**，要从高通 SDK 自行随包携带。
         * 这里只做"能不能加载"的探测：加载不了 ⇒ QNN 不进候选序。
         * ⚠️ `loadLibrary` 抛 `UnsatisfiedLinkError` 是可捕获的；真正的原生崩溃捕获不到，
         *    所以本探测**只加载库、绝不建会话**，且默认在 arm64 + 高通机型之外一律不试。
         */
        private fun probeQnnLibs(): Boolean {
            val (vendor, _) = readSoc()
            if (vendor != "qualcomm" || !abiIsArm64()) return false
            return listOf("QnnHtp", "QnnHtpPrepare", "QnnSystem").all { runCatching { System.loadLibrary(it) }.isSuccess }
        }
    }
}

/** 单独抽出来，避免 `Build.SUPPORTED_ABIS` 在旧 ROM 上为 null 时到处 try/catch。 */
internal fun abiIsArm64(): Boolean = runCatching {
    android.os.Build.SUPPORTED_ABIS.any { it.contains("arm64") }
}.getOrDefault(false)
