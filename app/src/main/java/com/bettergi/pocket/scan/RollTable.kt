package com.bettergi.pocket.scan

import android.content.res.AssetManager
import android.util.Log
import com.bettergi.pocket.dsl.FlowSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * 副词条档位表（flow 的 `dict.subStats = "rollTable"` 所指）。
 *
 * 结构（`assets/dsl/tools/rollTable.json`，与 `dsl/tools` 工作区同源）：
 * ```
 * { "<稀有度 1-5>": { "<FIGHT_PROP_*>": { "<显示值>": [[档位分解...], ...] } } }
 * ```
 * 每个「档位分解 = 一串**单次强化的内部值**」⇒ 数组长度 = 该显示值对应的**强化次数**，
 * 元素 = 每次强化的值（百分比词条为小数，如 0.0117 = 1.17%）。
 *
 * 用途：
 * 1. [snap] 把 OCR 读出的数值吸附到最近的标准显示档位（消除 ±0.05 级误差）；
 * 2. [rollCounts] / [initialDisplayValue] 供 [RollSolver] 解「强化次数 / 首档值」。
 *
 * ⚠️ **2026-09-16 修**：表内键是 `FIGHT_PROP_*`，而调用方传的是 GOOD 键（`hp` / `critRate_` …）
 * ⇒ 此前 [snap] 恒查不到 → 返回 null → **吸附静默失效**（导出数值一直是原始 OCR 值）。
 * 现由 [RollSolver.propKey] 统一换算。
 *
 * 与 [GoodNames] / [TemplateMatcher] 同构：由 ScriptRunner 用 [attach] 注入，
 * 未在 flow 中声明时不生效（保持原值）。
 */
object RollTable {
    private const val TAG = "BetterGI.RollTable"
    private const val ASSET_PATH = "dsl/tools/rollTable.json"

    @Volatile
    private var table: JSONObject? = null

    @Volatile
    private var loaded = false

    fun attach(assets: AssetManager) {
        if (loaded) return
        table = try {
            JSONObject(FlowSource.open(assets, ASSET_PATH).bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            Log.w(TAG, "rollTable.json 读取失败，副词条档位吸附不可用：${e.message}")
            null
        }
        loaded = true
    }

    /** 测试/非 Android 环境注入（JVM 单测读 assets 目录后调用）。 */
    fun attachJson(json: JSONObject) {
        table = json
        loaded = true
    }

    /** 该 rarity+statKey 是否有档位表（用于诊断日志）。 */
    fun has(rarity: Int, statKey: String): Boolean {
        val prop = RollSolver.propKey(statKey) ?: statKey
        return table?.optJSONObject(rarity.toString())?.has(prop) == true
    }

    /**
     * 把 [value] 吸附到 [rarity] 星、词条 [statKey]（**GOOD 键**）下最近的标准档位值。
     * @return 吸附后的值；表未加载/无该 rarity 或 statKey/表为空 → null（调用方保持原值）
     */
    fun snap(rarity: Int, statKey: String, value: Double): Double? {
        val tiers = tiersOf(rarity, statKey) ?: return null
        var best: Double? = null
        var bestDiff = Double.MAX_VALUE
        val it = tiers.keys()
        while (it.hasNext()) {
            val tier = numOf(it.next()) ?: continue
            val diff = Math.abs(tier - value)
            if (diff < bestDiff) {
                bestDiff = diff
                best = tier
            }
        }
        return best
    }

    /**
     * 该词条显示值为 [displayValue] 时**所有合法的强化次数**（≤ [maxPer]）。
     * @return 升序列表；空 = 该值不在档位表里（游戏不会产出该显示值）
     */
    fun rollCounts(rarity: Int, goodKey: String, displayValue: Double, maxPer: Int): List<Int> {
        val combos = combosOf(rarity, goodKey, displayValue)
        if (combos != null) {
            val out = sortedSetOf<Int>()
            for (i in 0 until combos.length()) {
                val n = combos.optJSONArray(i)?.length() ?: continue
                if (n in 1..maxPer) out.add(n)
            }
            if (out.isNotEmpty()) return out.toList()
        }
        // ⚠️ 2026-09-16：表**有个别显示值缺失**（GT 实测 5★ hp 1105 = 209.13+298.75×3 确实存在，
        //   但 rollTable.json 里查不到）⇒ 退化为**按档位枚举**（与 GOODScanner `enumerate_sums` 等价），
        //   否则该件整件判不可解（实测 5★+20 里因此丢 5 件）。
        val tiers = tierValues(rarity, goodKey) ?: return emptyList()
        if (tiers.isEmpty()) return emptyList()
        val pct = RollSolver.isPercent(goodKey)
        val out = sortedSetOf<Int>()
        for (n in 1..maxPer) if (canReach(tiers, n, 0.0, displayValue, pct)) out.add(n)
        return out.toList()
    }

    /**
     * 该词条在 [rollCount] 次强化下的**首档显示值**（= GOOD 的 `initialValue`）。
     *
     * ⚠️ 不可"取分解数组第一个元素"：表里每个显示值只给**规范序**分解
     * （如 4★ critDMG 11.8 只给 [[0.0559, 0.0621]]），而游戏的**真实首档**可能是另一序
     * （GT 实测该件 `initialValue = 6.2`）。⇒ 复刻 GOODScanner `compute_initial_value`：
     * 枚举「哪个档位当首档、其余 `rollCount-1` 次能否凑出显示值」，**唯一才返回**。
     *
     * @return null = 无该次数分解，或存在多个可行首档（歧义 ⇒ 不猜，导出省略该字段）
     */
    fun initialDisplayValue(rarity: Int, goodKey: String, displayValue: Double, rollCount: Int): Double? {
        val tiers = tierValues(rarity, goodKey) ?: return null
        if (tiers.isEmpty()) return null
        val pct = RollSolver.isPercent(goodKey)
        fun disp(internal: Double): Double = if (pct) round1dp(internal * 100.0) else roundInt(internal)
        if (rollCount <= 1) {
            return if (tiers.any { Math.abs(disp(it) - displayValue) < 0.01 }) displayValue else null
        }
        val rest = rollCount - 1
        val minRest = tiers.min() * rest
        val maxRest = tiers.max() * rest
        val out = sortedSetOf<Double>()
        for (t in tiers) {
            if (t + minRest > displayValue + 0.5) break          // 再大也够不到
            if (t + maxRest < displayValue - 0.5) continue        // 再小也够不到
            if (canReach(tiers, rest, t, displayValue, pct)) out.add(disp(t))
        }
        return if (out.size == 1) out.first() else null
    }

    /** 单次强化的档位内部值集合（= 表里"分解长度为 1"的那些值）。 */
    private fun tierValues(rarity: Int, goodKey: String): List<Double>? {
        val tiers = tiersOf(rarity, goodKey) ?: return null
        val out = sortedSetOf<Double>()
        val kit = tiers.keys()
        while (kit.hasNext()) {
            val combos = tiers.optJSONArray(kit.next()) ?: continue
            for (i in 0 until combos.length()) {
                val c = combos.optJSONArray(i) ?: continue
                if (c.length() == 1) {
                    val v = c.optDouble(0, Double.NaN)
                    if (!v.isNaN()) out.add(v)
                }
            }
        }
        return out.toList()
    }

    /** 递归：从 [tiers] 里取 [n] 个（可重复），判断能否与 [prefix] 之和落在显示值 [displayValue] 上。 */
    private fun canReach(
        tiers: List<Double>,
        n: Int,
        prefix: Double,
        displayValue: Double,
        pct: Boolean,
    ): Boolean {
        if (n == 0) {
            // ⚠️ 按**游戏语义（float32）**判显示值：档位值都是精确十进制，但逐次累加时 f32/f64 会在
            //   十进制 .x5 边界分道扬镳（实测 5★ def_ 0.0489+0.0698+0.0698：f32 = 0.1885 → 显示 18.9，
            //   f64 = 0.18849999… → 18.8）⇒ 表里缺该显示值走枚举回退时会误判"不可解"。
            //   GOODScanner 明文要求"apply the game's exact float32 arithmetic"。
            val shown = if (pct) round1dp(prefix * 100.0) else roundInt(prefix)
            return Math.abs(shown - displayValue) < 0.02
        }
        val lo = tiers.first() * n
        val hi = tiers.last() * n
        if (prefix + lo > displayValue + 0.5 || prefix + hi < displayValue - 0.5) return false
        // ★ float32 累加（见上）：`prefix` 已是 f32 结果转回 Double，本轮再按 f32 相加
        for (t in tiers) {
            val next = (prefix.toFloat() + t.toFloat()).toDouble()
            if (canReach(tiers, n - 1, next, displayValue, pct)) return true
        }
        return false
    }

    // ---- 内部 ----

    private fun tiersOf(rarity: Int, goodKey: String): JSONObject? {
        val prop = RollSolver.propKey(goodKey) ?: return null
        return table?.optJSONObject(rarity.toString())?.optJSONObject(prop)
    }

    /** 按显示值定位档位项（容差 0.05：显示值 1dp / 整数）。 */
    private fun combosOf(rarity: Int, goodKey: String, displayValue: Double): JSONArray? {
        val tiers = tiersOf(rarity, goodKey) ?: return null
        var bestKey: String? = null
        var bestDiff = 0.05
        val it = tiers.keys()
        while (it.hasNext()) {
            val k = it.next()
            val v = numOf(k) ?: continue
            val d = Math.abs(v - displayValue)
            if (d <= bestDiff) {
                bestDiff = d
                bestKey = k
            }
        }
        return bestKey?.let { tiers.optJSONArray(it) }
    }

    /** 表里的档位键可能是千分位写法（如 `"1, 016"`）⇒ 解析前剥掉逗号/空格。 */
    private fun numOf(raw: String): Double? =
        raw.replace(",", "").replace(" ", "").trim().toDoubleOrNull()

    // ⚠️ 2026-09-16：epsilon 由 1e-9 放宽到 **1e-6**，并把枚举累加改为 **float32**
    //   （GOODScanner 明文要求 "the game's exact float32 arithmetic + display rounding"）：
    //   档位值本身是精确十进制，但多次累加后 f32/f64 会在十进制 .x5 边界上分岔
    //   （实测 f32 0.0489+0.0698+0.0698 = 0.18849998 vs f64 = 0.1885），
    //   走"表缺值 → 档位枚举回退"路径时会因此判错显示值。
    private fun round1dp(v: Double): Double = Math.floor(v * 10.0 + 0.5 + 1e-6) / 10.0

    private fun roundInt(v: Double): Double = Math.floor(v + 0.5 + 1e-6)
}
