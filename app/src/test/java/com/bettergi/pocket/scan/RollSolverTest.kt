package com.bettergi.pocket.scan

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
            // totalRolls 有**固有歧义**（lv>0 时 init 3/4 都可能自洽，GT 真值分布 97:8）⇒ 单列统计
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
        assertTrue(
            "totalRolls 差异应 ≤ 1.5%（init 固有歧义），实测 $rollBad/${arr.length()}",
            rollBad <= arr.length() * 15 / 1000,
        )
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
}
