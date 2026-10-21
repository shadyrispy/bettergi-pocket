package com.bettergi.pocket.scan

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.io.File

/**
 * 钳频 / 温度读数（#230）。
 *
 * 为什么要它：华为上「同一份代码 OCR 均时漂 1.8×、跨天 2.8×」的真因是 **EMUI 钳频**
 * （实测 `scaling_max_freq` 被压到 1690000，而硬件上限 `cpuinfo_max_freq=1844000`）。
 * 任何耗时 A/B 只要不带上**当时的频率上限**，读数就无法归因 —— 慢的那一侧可能只是被压了频。
 * 所以本摘要打在**起跑**与**收尾**两处，把整轮夹在中间。
 *
 * 口径（2026-10-06 在 EML-AL00 / EMUI 实测，非推断）：
 * - `scaling_cur_freq` / `scaling_max_freq` / `cpuinfo_max_freq`：应用域**可读**。
 * - 电池 sysfs 温度与 `/sys/class/thermal`：**Permission denied**
 *   ⇒ 电池温度只能走 `ACTION_BATTERY_CHANGED` 粘性广播（`EXTRA_TEMPERATURE`，单位 0.1 摄氏度）。
 * - 读不到一律打 `na`，**不打 0**：0 会被读成「频率为 0」，那是另一种假现象。
 */
object CpuTelemetry {
    private const val CPU_ROOT = "/sys/devices/system/cpu"

    @Volatile
    private var appContext: Context? = null

    /** 由启动侧注入一次；未注入时温度/热压打 `na`，频率照常读（干跑就走这条）。 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 一行摘要：`cpu=0-3:1690/1690,4-7:1863/1018MHz hw=1844 batt=39.0C thermal=0`。 */
    fun snapshot(): String =
        "cpu=${clusters()} hw=${mhz("cpu0/cpufreq/cpuinfo_max_freq")} " +
            "batt=${batteryTempC()} thermal=${thermalStatus()}"

    /** 每簇打 `上限/当前`（MHz），连续同值的核并成一簇（8 核通常 2~3 簇，否则行会被撑爆）。 */
    private fun clusters(): String {
        val sb = StringBuilder()
        var i = 0
        var runLabel: String? = null
        var runStart = 0
        while (File(CPU_ROOT, "cpu$i").isDirectory) {
            val label = "${mhz("cpu$i/cpufreq/scaling_max_freq")}/${mhz("cpu$i/cpufreq/scaling_cur_freq")}"
            if (label != runLabel) {
                appendCluster(sb, runStart, i - 1, runLabel)
                runLabel = label
                runStart = i
            }
            i++
        }
        appendCluster(sb, runStart, i - 1, runLabel)
        return if (sb.isEmpty()) "na" else sb.append("MHz").toString()
    }

    private fun appendCluster(sb: StringBuilder, from: Int, to: Int, label: String?) {
        if (label == null) return
        if (sb.isNotEmpty()) sb.append(',')
        sb.append(if (to > from) "$from-$to:$label" else "$from:$label")
    }

    /** sysfs 的 kHz 读数转 MHz 字符串；读不到打 `na`（不返回 0，见类注释）。 */
    private fun mhz(rel: String): String {
        val v = runCatching {
            File(CPU_ROOT, rel).readText().trim().toLongOrNull()
        }.getOrNull() ?: return "na"
        return if (v <= 0) "na" else (v / 1000).toString()
    }

    private fun batteryTempC(): String {
        val ctx = appContext ?: return "na"
        val intent = runCatching {
            ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return "na"
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        return if (tenths <= 0) "na" else "%.1fC".format(tenths / 10.0)
    }

    /** `PowerManager.currentThermalStatus`（API 29+）：0=NONE，越大越接近降频。 */
    private fun thermalStatus(): String {
        val ctx = appContext ?: return "na"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "na"
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return "na"
        return runCatching { pm.currentThermalStatus.toString() }.getOrDefault("na")
    }
}
