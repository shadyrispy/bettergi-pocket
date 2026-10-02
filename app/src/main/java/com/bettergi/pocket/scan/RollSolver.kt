package com.bettergi.pocket.scan

import android.util.Log

/**
 * 副词条 **roll solver**（按 GOODScanner `genshin/src/scanner/common/roll_solver.rs` 补齐，2026-09-16）。
 *
 * 作用：把 OCR 读出的副词条数值 + 星级 + 等级，解成「**每个词条强化了几次**」与「**初始档位值**」，
 * 并给出整件的 `totalRolls` —— 即 GOOD v3 里 Irminsul 会写的那几个字段
 * （GT 实测每条 substat 带 `initialValue`、每件带 `totalRolls`，我方此前**全缺**）。
 *
 * 判据（与原实现一致）：
 * - 每个词条的显示值必须能在 [RollTable] 的档位表里找到分解（⇒ 该词条的**合法强化次数集合**）；
 * - 星级 5 的等级 → 强化次数 `upgrades = level / 4`；初始词条数 `init ∈ {4,3}`（5★）/ `{3,2}`（4★），
 *   且 `level == 0` 时 `init` 优先取大（面板行数=初始条数）、`level > 0` 时优先取小（3 条更常见）；
 * - 所有词条的强化次数之和必须**恰好等于** `init + upgrades`（回溯求解，无解则整件放弃）；
 * - `initialValue` = 该词条**首次**强化档位的显示值（表里多条分解时取唯一值；并列歧义 → null）。
 */
object RollSolver {
    private const val TAG = "BetterGI.RollSolver"

    /** GOOD 副词条键 → rollTable 里的 `FIGHT_PROP_*` 键（游戏副词条全集，10 条）。 */
    private val TO_PROP = mapOf(
        "hp" to "FIGHT_PROP_HP",
        "hp_" to "FIGHT_PROP_HP_PERCENT",
        "atk" to "FIGHT_PROP_ATTACK",
        "atk_" to "FIGHT_PROP_ATTACK_PERCENT",
        "def" to "FIGHT_PROP_DEFENSE",
        "def_" to "FIGHT_PROP_DEFENSE_PERCENT",
        "enerRech_" to "FIGHT_PROP_CHARGE_EFFICIENCY",
        "eleMas" to "FIGHT_PROP_ELEMENT_MASTERY",
        "critRate_" to "FIGHT_PROP_CRITICAL",
        "critDMG_" to "FIGHT_PROP_CRITICAL_HURT",
    )

    /** GOOD 键 → 表键；未知键（含 5★ 专属之外的词条）返回 null。 */
    fun propKey(goodKey: String): String? = TO_PROP[goodKey]

    /** 百分比词条（GOOD 约定：以 `_` 结尾）。 */
    fun isPercent(goodKey: String): Boolean = goodKey.endsWith("_")

    /**
     * solver 输入项。`inactive` = 该行带「(待激活)」标记（**仅 lv0 可能**，见 GOODScanner
     * `AGENTS.md §Unactivated Substats`：其数值是**真档位值**，按普通词条参与校验，
     * 但**不计入 totalRolls**，且 lv0 的 init 直接 = 非待激活条数 ⇒ 歧义消失）。
     */
    data class In(val key: String, val value: Double, val inactive: Boolean = false)

    /** 解出的单词条：`rollCount` = 强化次数；`initialValue` = 首档显示值（歧义/不可解为 null）。 */
    data class SolvedSubstat(
        val key: String,
        val value: Double,
        val rollCount: Int,
        val initialValue: Double?,
        /** 原样透传输入标记 ⇒ 调用方据此拆 `substats` / `unactivatedSubstats`。 */
        val inactive: Boolean = false,
    )

    /** 整件解算结果。`totalRolls` = 初始条数 + 强化次数；`initialSubstatCount` = 初始词条数。 */
    data class Solution(
        val substats: List<SolvedSubstat>,
        val initialSubstatCount: Int,
        val totalRolls: Int,
        /**
         * 解出的**已激活词条条数**（= `substats` 里 `inactive == false` 的条数）。
         *
         * ⚠️ 2026-09-16 更正（GT 实测定案）：`+0` 且 `init < maxInit` 时游戏在词条块**末尾多显示
         * 一行「待激活」词条**，**Irminsul/GT 是导出的** —— 只是**单列**到 `unactivatedSubstats`
         * （894/894 件都有该字段；5★+0 实测 3 active + 1 待激活 ⇒ `totalRolls=3`）。
         * ⇒ 调用方**不要丢**，应拆成 `substats` / `unactivatedSubstats` 两个数组；
         * `activeCount` 仅在**没有显式标记**、只能靠 solver 推断"末尾一条是待激活"时用于切尾。
         */
        val activeCount: Int,
    )

    /**
     * 解一件圣遗物的副词条。
     * @return null = 不可解（星级非 4/5、表未加载、数值不在档位表、或总强化次数凑不出）
     *         —— 调用方**保持原样导出**（宁缺不错）。
     */
    fun solve(rarity: Int, level: Int, substats: List<In>): Solution? {
        if (rarity != 4 && rarity != 5) return null
        if (substats.isEmpty()) return null
        val maxLevel = if (rarity == 5) 20 else 16
        val maxInit = if (rarity == 5) 4 else 3

        // 等级候选：原值优先；再试 +10（OCR 常见「11 → 1」丢位）
        val lv0 = level.coerceIn(0, maxLevel)
        val candidates = LinkedHashSet<Int>().apply {
            add(lv0)
            if (level in 0 until 10 && level + 10 <= maxLevel) add(level + 10)
        }
        for (lv in candidates) {
            attempt(lv, rarity, maxLevel, maxInit, substats)?.let { return it }
        }
        return null
    }

    private fun attempt(
        level: Int,
        rarity: Int,
        maxLevel: Int,
        maxInit: Int,
        subs: List<In>,
    ): Solution? {
        val lv = level.coerceIn(0, maxLevel)
        val upgrades = lv / 4
        // ★ 显式「(待激活)」标记 ⇒ init 不再靠猜：init = 非待激活条数
        //   （GOODScanner `result_init = num_active_substats − count(inactive)`；GT 实测
        //    5★+0 = 3 active + 1 待激活 ⇒ totalRolls=3，而 4 active + 0 ⇒ 4 ✓ 二者不再混淆）
        val inactiveCount = subs.count { it.inactive }
        val initials = if (inactiveCount > 0) {
            intArrayOf(subs.size - inactiveCount)
        } else when {
            rarity == 5 && lv == 0 -> intArrayOf(4, 3)
            rarity == 5 -> intArrayOf(3, 4)
            lv == 0 -> intArrayOf(3, 2)
            else -> intArrayOf(2, 3)
        }
        for (init in initials) {
            val totalRolls = init + upgrades
            val adds = Math.max(0, Math.min(4 - init, upgrades))
            val baseExpected = init + adds
            // level==0 且 init 未满时，面板会多显示一条「待激活」词条 ⇒ 先按 init+1 条试，再退回
            // 有显式标记时不需要"多一条待激活"变体（标记已告诉我们哪条是待激活）
            val pendingInactive = lv == 0 && init < maxInit && inactiveCount == 0
            // ★ 2026-09-16（GT 实测定案）：**先试「不带待激活行」的变体**。
            //   真机 205 → 128 件"多读一条"全部是 `+0` 件的「待激活」行（未解锁的第 4 条），
            //   游戏会把它渲染得与真词条几乎一样 ⇒ OCR 必读进来。
            //   GT 真值：`5★+0` 里 3 条 = 158 件、4 条 = 77 件 ⇒ 先按 3 条解（4 条形必须落在 init=4 分支）。
            val variants = if (pendingInactive) {
                listOf(baseExpected to totalRolls, (baseExpected + 1) to (totalRolls + 1))
            } else {
                listOf(baseExpected to totalRolls)
            }
            for ((expectedActive, solveTotal) in variants) {
                // ⚠️ 2026-09-16（待激活修正）：**只对"已激活"行做次数分配** —— 待激活那条是
                //   "首次升级后才生效"的未来词条（值 = 单次强化档位），**不占 totalRolls**
                //   （GT：3 active + 1 待激活 ⇒ totalRolls=3）；它单独按"恰好 1 次强化"的显示值校验。
                //   曾经的错：用 `subs.size`（含待激活）算 maxPer ⇒ 3+1 件 maxPer=0 ⇒ 直接拒绝
                //   ⇒ 5★+0 的 158 件全部解不出（GT 全量对账实测 159 件无解）。
                val activeIdx = subs.indices.filter { !subs[it].inactive }
                val unactIdx = subs.indices.filter { subs[it].inactive }
                if (activeIdx.size != expectedActive) continue
                if (subs.map { it.key }.toSet().size != subs.size) continue // 同件不容两同名词条
                if (unactIdx.any { RollTable.rollCounts(rarity, subs[it].key, subs[it].value, 1).isEmpty() }) continue
                val maxPer = solveTotal - (activeIdx.size - 1)                // 每条已激活至少 1 次
                if (maxPer < 1) continue
                val counts = activeIdx.map { RollTable.rollCounts(rarity, subs[it].key, subs[it].value, maxPer) }
                if (counts.any { it.isEmpty() }) continue
                val assignment = assign(counts, solveTotal) ?: continue
                val solved = subs.indices.map { i ->
                    val sub = subs[i]
                    if (sub.inactive) {
                        // 待激活：rollCount = 1，首档即其显示值本身
                        SolvedSubstat(sub.key, sub.value, 1, sub.value, true)
                    } else {
                        val k = activeIdx.indexOf(i)
                        SolvedSubstat(
                            key = sub.key,
                            value = sub.value,
                            rollCount = assignment[k],
                            initialValue = RollTable.initialDisplayValue(rarity, sub.key, sub.value, assignment[k]),
                            inactive = false,
                        )
                    }
                }
                return Solution(solved, init, solveTotal, expectedActive)
            }
        }
        return null
    }

    /** 回溯：把 [solveTotal] 次强化分给各词条，每条取值落在自己的合法集合里。 */
    private fun assign(valid: List<List<Int>>, solveTotal: Int): List<Int>? {
        val out = IntArray(valid.size)
        fun rec(idx: Int, remaining: Int): Boolean {
            if (idx == valid.size) return remaining == 0
            var minOthers = 0
            for (i in idx + 1 until valid.size) minOthers += (valid[i].minOrNull() ?: 1)
            for (c in valid[idx]) {
                if (c > remaining) continue
                val left = remaining - c
                if (left < minOthers) continue
                out[idx] = c
                if (rec(idx + 1, left)) return true
            }
            return false
        }
        return if (rec(0, solveTotal)) out.toList() else null
    }

    /** 诊断用：整册成功率（仅日志）。 */
    internal fun logMiss(rarity: Int, level: Int, subs: List<In>) {
        Log.d(TAG, "roll solve 失败: rarity=$rarity level=$level subs=$subs")
    }
}
