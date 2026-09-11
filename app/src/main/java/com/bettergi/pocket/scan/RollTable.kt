package com.bettergi.pocket.scan

import android.content.res.AssetManager
import android.util.Log
import com.bettergi.pocket.dsl.FlowSource
import org.json.JSONObject

/**
 * 副词条档位表（flow 的 `dict.subStats = "rollTable"` 所指）。
 *
 * 结构（`assets/dsl/tools/rollTable.json`，与 `dsl/tools` 工作区同源）：
 * ```
 * { "<稀有度 1-5>": { "<statKey FIGHT_PROP_*>": { "<显示值>": [[档位分解...], ...] } } }
 * ```
 * 用途：OCR 读出的副词条数值带误差（如 23.7），吸附到表里最近的标准显示档位（如 24），
 * 使导出的 GOOD 数值与游戏内一致，也便于按档位反推强化次数。
 *
 * 与 [GoodNames] / [TemplateMatcher] 同构：由 ScriptRunner 用 `attach(assets)` 注入，
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

    /**
     * 把 [value] 吸附到 [rarity] 星、词条 [statKey] 下最近的标准档位值。
     * @return 吸附后的值；表未加载/无该 rarity 或 statKey/表为空 → null（调用方保持原值）
     */
    fun snap(rarity: Int, statKey: String, value: Double): Double? {
        val tiers = table?.optJSONObject(rarity.toString())
            ?.optJSONObject(statKey) ?: return null
        var best: Double? = null
        var bestDiff = Double.MAX_VALUE
        val it = tiers.keys()
        while (it.hasNext()) {
            val tier = it.next().toDoubleOrNull() ?: continue
            val diff = Math.abs(tier - value)
            if (diff < bestDiff) {
                bestDiff = diff
                best = tier
            }
        }
        return best
    }

    /** 该 rarity+statKey 是否有档位表（用于诊断日志）。 */
    fun has(rarity: Int, statKey: String): Boolean =
        table?.optJSONObject(rarity.toString())?.has(statKey) == true
}
