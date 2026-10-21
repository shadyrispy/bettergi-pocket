package com.bettergi.pocket.scan

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * roll solver 对账（按 GOODScanner `roll_solver.rs` 移植，2026-09-16）。
 *
 * oracle = **真机账号的 Irminsul GOOD**（`app/src/test/resources/roll_solver_fixture.json`，
 * 894 件，覆盖 5★+20 / 5★+0 / 4★+16 / 4★+0）：每条 substat 带 `initialValue`、每件带
 * `totalRolls`，且 lv0 件的「待激活」条已按 GT 的 `unactivatedSubstats` 标成 `inactive=true`
 * ⇒ 直接当期望值。
 */
class RollSolverTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadTable() {
            RollTable.attachJson(JSONObject(File(assetsDir(), "tools/rollTable.json").readText()))
        }

        private fun assetsDir(): File {
            var dir = File(System.getProperty("user.dir") ?: ".")
            repeat(4) {
                val c = File(dir, "src/main/assets/dsl")
                if (c.isDirectory) return c
                dir = dir.parentFile ?: return@repeat
            }
            error("src/main/assets/dsl not found")
        }

        private fun fixture(): JSONArray {
            val txt = RollSolverTest::class.java.getResourceAsStream("/roll_solver_fixture.json")
                ?.bufferedReader()?.use { it.readText() }
                ?: error("fixture 缺失：app/src/test/resources/roll_solver_fixture.json")
            return JSONArray(txt)
        }
    }

    @Test
    fun `matches ground truth on the whole account`() {
        val arr = fixture()
        assertTrue("夹具样本数应 ≥ 800（全量真机账号），实测 ${arr.length()}", arr.length() >= 800)
        var unsolved = 0
        var hardBad = 0
        var rollBad = 0
        var agreeTot = 0
        var agreeMiss = 0
        var ambiguousOnly = 0
        val badList = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            val rarity = a.getInt("rarity")
            val level = a.getInt("level")
            val subs = mutableListOf<RollSolver.In>()
            val wantInit = mutableListOf<Double?>()
            val ja = a.getJSONArray("substats")
            for (j in 0 until ja.length()) {
                val s = ja.getJSONObject(j)
                subs += RollSolver.In(s.getString("key"), s.getDouble("value"), s.optBoolean("inactive"))
                wantInit += if (s.has("initialValue")) s.getDouble("initialValue") else null
            }
            val sol = RollSolver.solve(rarity, level, subs)
            if (sol == null) {
                unsolved++
                if (badList.size < 8) {
                    badList += "sample#$i rarity=$rarity level=$level 无解 subs=" +
                        subs.joinToString { "${it.key}=${it.value}${if (it.inactive) "(待激活)" else ""}" }
                }
                continue
            }
            // ★ 硬不变量 1：待激活拆分 —— activeCount 必须 = GT 的 substats 条数
            if (sol.activeCount != a.getInt("activeCount")) {
                hardBad++
                if (badList.size < 8) {
                    badList += "sample#$i activeCount 期望 ${a.getInt("activeCount")} 实得 ${sol.activeCount}"
                }
            }
            // ★ 硬不变量 2：initialValue 与 GT 逐条对齐。
            //   口径：**只在两边都给出值时比对** —— 多档分解下"首档"本就有歧义（GT 存真实顺序、
            //   我方无法从显示值恢复）⇒ 我方按 GOODScanner 同款策略返回 null（不猜），此时不计错。
            sol.substats.forEachIndexed { k, ss ->
                val want = wantInit[k]
                val got = ss.initialValue
                if (want != null && got != null) {
                    agreeTot++
                    if (Math.abs(got - want) > 0.001) {
                        agreeMiss++
                        if (badList.size < 8) {
                            badList += "sample#$i ${ss.key} initialValue 期望 $want 实得 $got"
                        }
                    }
                } else if (want == null && got != null) {
                    ambiguousOnly++
                }
            }
            // totalRolls 有**固有歧义**：lv>0 时 `init=3`（第 4 条由强化产生）与 `init=4` 可能都自洽，
            // 显示值本身**分不开**这两种历史。夹具 894 件里 105 件属于这种歧义。
            // ⇒ 只能选先验：现序（先取小）在夹具上错 8 件。这与参考实现**同序** ——
            //   `roll_solver.rs:459-464` 在 `rarity==5 && level>0` 时也取 `&[3, 4]`，注释原文
            //   "At level > 0, prefer lower init (better GT accuracy)"；它只给结论，
            //   本仓把它量化了：翻成先取大会错 97 件（见下方 `assertEquals(8, rollBad)`）。
            if (sol.totalRolls != a.getInt("totalRolls")) rollBad++
        }
        // ⚠️ 容许 ≤2 件不可解：离线全量实测 **1/894**（sample#449 `def_=18.9`）——
        //   5★ def_ 档位 = 5.1/5.8/6.6/7.3 ⇒ 3 次和的显示值只可能是 18.2 / 19.0 / 19.7
        //   （rollTable 里正是这三个键，**没有 18.9**）⇒ 该件的数值用本表档位**表示不出来**，
        //   属"表与 GT 个别不一致"（后续可用 GOODScanner `tools/gen_roll_table.py` 重造表核对），
        //   非解算逻辑错误。表缺值时另有档位枚举回退兜底。
        assertTrue("不可解件应 ≤2（表/GT 个别异常）：$badList", unsolved <= 2)
        assertEquals("activeCount / initialValue 硬不变量不得破：$badList", 0, hardBad)
        // 允许极少数**多重分解**歧义（同一显示值可拆成不同次数组合，GT 取真机顺序、我方取任一自洽解）
        // —— 离线全量实测 ≤3 条 / 3400+；其余必须逐条一致。
        assertTrue(
            "initialValue 可比对项应 ≥99.9% 一致（实测 ${agreeTot - agreeMiss}/$agreeTot）：$badList",
            agreeMiss <= 3,
        )
        // totalRolls 的歧义错判**钉成精确值**（#98，2026-09-28 用 scripts/rollsolver_probe.py 量出）：
        // 夹具 894 件里 105 件两解都自洽，现序（先取小 init）在其中错 8 件。
        // ⚠️ 别把这条"放宽成百分比"：现序与参考实现同序（`roll_solver.rs:459-464` 的 `&[3, 4]`，
        //   注释 "prefer lower init (better GT accuracy)"），把它翻成先取大在这份真值上错 **97** 件
        //   ⇒ 顺序一改此数就跳，正是要它跳。若哪天**重造了夹具**（换账号快照），这里的 8 要重量一次再改。
        assertEquals(
            "totalRolls 歧义错判必须 =8（现序=先取小 init；改序会变 97），见 dsl/scripts/rollsolver_probe.py",
            8, rollBad,
        )
    }

    /**
     * #98：**歧义消解顺序是一条策略，不是巧合** ⇒ 单独钉一条（聚合断言只说"错 8 件"，
     * 看不出这 8 件是"先取小 init"这条规则的代价）。
     *
     * 真机 GT 样例 `NoblesseOblige|flower|20|hp`：`atk_=4.1 / critRate_=3.5 / def=39 / enerRech_=25.9`。
     * 这组显示值**同时**自洽于 init=3（8 次）与 init=4（9 次）—— 面板上没有别的信息能区分这两种历史，
     * 所以 8/9 只能按先验选。选小的实测收益：夹具 894 件里歧义 105 件，先取小错 8、先取大错 97。
     * （GT 真值是 9 ⇒ 本用例断言的 8 **就是那 8 件已知错判之一**，钉的是"我们明知会错这 8 件、
     *   仍选错得更少的那一侧"，不是"这组值只能解出 8"。）
     */
    @Test
    fun `ambiguous init resolves to the smaller count by policy`() {
        val subs = listOf(
            RollSolver.In("atk_", 4.1),
            RollSolver.In("critRate_", 3.5),
            RollSolver.In("def", 39.0),
            RollSolver.In("enerRech_", 25.9),
        )
        val sol = RollSolver.solve(5, 20, subs)
        assertNotNull(sol)
        assertEquals("先取小的那条（3 + 5 次强化 = 8）", 3, sol!!.initialSubstatCount)
        assertEquals(8, sol.totalRolls)
        // 歧义前提**必须用真值证**，不许写成恒真断言：这组显示值在冻结夹具里确有 GT=9 的那一件
        // ⇒ 说明"9 也自洽"不是嘴上说说，而我们仍选了 8（换序会多错 89 件）。
        val arr = fixture()
        val truth = (0 until arr.length())
            .map(arr::getJSONObject)
            .filter { it.getInt("rarity") == 5 && it.getInt("level") == 20 }
            .firstOrNull { a ->
                val s = a.getJSONArray("substats")
                s.length() == subs.size && subs.all { want ->
                    (0 until s.length()).any { j ->
                        val o = s.getJSONObject(j)
                        o.getString("key") == want.key && Math.abs(o.getDouble("value") - want.value) < 0.001
                    }
                }
            }
        assertNotNull("夹具里找不到这件歧义样本 ⇒ 本用例的前提已失效（夹具被换过？）", truth)
        assertEquals("GT 真值必须是 9（否则这条链就不是歧义，#98 的成因得重写）", 9, truth!!.getInt("totalRolls"))
    }

    @Test
    fun `explicit unactivated marker fixes the init count at level 0`() {
        // 5★+0：3 条已激活 + 1 条「(待激活)」⇒ init=3、totalRolls=3（GT 实测 158 件都是这个形态）
        val withMark = RollSolver.solve(
            5, 0,
            listOf(
                RollSolver.In("atk_", 5.8),
                RollSolver.In("critRate_", 2.7),
                RollSolver.In("critDMG_", 7.0),
                RollSolver.In("hp_", 5.8, inactive = true),
            ),
        )
        assertEquals(3, withMark!!.initialSubstatCount)
        assertEquals(3, withMark.totalRolls)
        assertEquals(3, withMark.activeCount)
        assertEquals(1, withMark.substats.count { it.inactive })
        // 4 条都已激活 ⇒ init=4、totalRolls=4（GT 实测 77 件）
        val noMark = RollSolver.solve(
            5, 0,
            listOf(
                RollSolver.In("def_", 5.1),
                RollSolver.In("hp", 209.0),
                RollSolver.In("critDMG_", 6.2),
                RollSolver.In("critRate_", 3.1),
            ),
        )
        assertEquals(4, noMark!!.initialSubstatCount)
        assertEquals(4, noMark.totalRolls)
        assertEquals(4, noMark.activeCount)
    }

    @Test
    fun `unsolvable values return null instead of guessing`() {
        // ① 显示值不在档位表 ⇒ 不可解（暴击率档位是 7.4 / 7.8，**没有 7.7**）
        assertEquals(
            null,
            RollSolver.solve(
                5, 20,
                listOf(
                    RollSolver.In("critRate_", 7.7), RollSolver.In("hp", 239.0),
                    RollSolver.In("critDMG_", 10.9), RollSolver.In("hp_", 5.8),
                ),
            ),
        )
        // ② 星级不支持（表只覆盖 4/5★）
        assertEquals(null, RollSolver.solve(3, 20, listOf(RollSolver.In("hp", 239.0))))
        // ③ 词条数与等级不符（5★+20 应是 4 条）
        assertEquals(null, RollSolver.solve(5, 20, listOf(RollSolver.In("hp", 239.0))))
    }

    @Test
    fun `snap maps GOOD keys to fight-prop keys`() {
        // 2026-09-16 修：此前传 GOOD 键查 FIGHT_PROP_* 表 ⇒ 恒 null（吸附静默失效）
        assertEquals(5.4, RollTable.snap(5, "critRate_", 5.44)!!, 1e-9)
        assertEquals(239.0, RollTable.snap(5, "hp", 239.6)!!, 1e-9)
        assertEquals(10.9, RollTable.snap(5, "critDMG_", 10.93)!!, 1e-9)
    }

    // ★ A18（2026-09-30）：level+10 候选解出时，Solution 必须带回解出等级，
    //   否则导出 level（OCR 误读值）与 totalRolls（= init + solvedLevel/4）自相矛盾。
    @Test
    fun `level plus ten candidate reports solved level consistent with totalRolls`() {
        // OCR 丢位场景（+10 候选只在 level<10 时生成，见 RollSolver.solve）。四个显示值的
        // 最少强化次数都是 2（表内分解长度=2）⇒ 4 条最少 8 次：level=9（init+upgrades ≤ 4+2=6）
        // 无解；level+10=19（init4+4=8）恰可解。
        val subs = listOf(
            RollSolver.In("hp", 508.0),        // 2 次
            RollSolver.In("hp_", 9.9),         // 2 次
            RollSolver.In("critRate_", 7.8),   // 2 次
            RollSolver.In("critDMG_", 14.0),   // 2 次
        )
        val solved = RollSolver.solve(5, 9, subs) // 原值 9 无解（最少 8 > 6）⇒ +10 候选 19 解出
        assertNotNull(solved)
        assertEquals(19, solved!!.solvedLevel)
        // totalRolls 与 solvedLevel 自洽：init(4) + 19/4(4) = 8
        assertEquals(4, solved.initialSubstatCount)
        assertEquals(solved.initialSubstatCount + solved.solvedLevel / 4, solved.totalRolls)
        // 对照：原值本身可解时 solvedLevel == 原值（行为不变）
        val solvedOriginal = RollSolver.solve(5, 19, subs)
        assertEquals(19, solvedOriginal!!.solvedLevel)
        assertEquals(8, solvedOriginal.totalRolls)
    }
}
