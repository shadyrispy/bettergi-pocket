package com.bettergi.pocket.scan

import com.bettergi.pocket.recognition.name.GoodNames
import com.bettergi.pocket.recognition.name.NameMatcher
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4 plan 契约回归测试（2026-09-10，实跑定谳后固化）。
 *
 * 契约来源：flow `vars.plan = list<{char, slot, target:GoodArtifact}>`；
 * auto_equip 的 `setFilter.selectByOcr` 用 [TaskMatch.targets] 选套装，
 * `stopWhen expr="panelMatch(target, tol=0.1)"` 用 [TaskMatch.hardMatch] 判命中。
 *
 * 真机验证（BlueStacks 2560×1440）：
 * - 正向 plan `{char:希诺宁, setName:烬城勇者绘卷, setKey:ScrollOfTheHeroOfCinderCity, slot:flower, level:20}`
 *   → `setFilter matched: ScrollOfTheHeroOfCinderCity (right)` +
 *     `stopWhen triggered | got(level=20,slot=flower,set=ScrollOfTheHeroOfCinderCity,main=hp)`（第一格即命中）
 * - 负向（`level:99`）→ 逐格 `panelMatch 未命中 ∵ level 20 ≠ 99`，全程不触发，收尾 `scan finished: exit`
 */
class PlanContractTest {

    private val flower = GoodArtifact(
        setKey = "ScrollOfTheHeroOfCinderCity",
        slotKey = "flower",
        level = 20,
        rarity = 5,
        mainStatKey = "hp",
        mainStatValue = 4780.0,
        substats = listOf(
            GoodSubStat("critRate_", 5.8),
            GoodSubStat("hp", 717.0),
            GoodSubStat("enerRech_", 12.4),
            GoodSubStat("eleMas", 23.0),
        ),
        lock = true,
        favorited = false,
        pieceName = "驯兽师的护符",
    )

    // ---- hardMatch：命中判据 ----

    @Test
    fun `full target matches the parsed artifact`() {
        val task = JSONObject(
            """{"char":"希诺宁","setKey":"ScrollOfTheHeroOfCinderCity","slot":"flower","level":20,
               "mainStatKey":"hp","substats":[{"key":"critRate_","value":5.8}]}""",
        )
        val why = StringBuilder()
        assertTrue(TaskMatch.hardMatch(task, flower, 0.1, why))
        assertTrue(why.toString().contains("全字段命中"))
    }

    @Test
    fun `bare task with only char trivially matches - documented footgun`() {
        // ⚠️ 刻意的宽松语义：任务侧缺字段即不比对 → 空任务恒 true。
        // 后果：裸 plan 会让 stopWhen 在第一格立刻触发（PHASE3 的 rounds=0 即此）。
        // 规则层必须注入真字段；本测试锁死"这是已知取舍而非回归"。
        val why = StringBuilder()
        assertTrue(TaskMatch.hardMatch(JSONObject("""{"char":"希诺宁"}"""), flower, 0.1, why))
    }

    @Test
    fun `level mismatch rejects and reports the reason`() {
        val task = JSONObject("""{"level":99}""")
        val why = StringBuilder()
        assertFalse(TaskMatch.hardMatch(task, flower, 0.1, why))
        assertTrue("原因需含实际值与期望值：$why", why.toString().contains("level 20 ≠ 99"))
    }

    @Test
    fun `setKey and slotKey aliases both work`() {
        // GOOD 名（setKey/slotKey）与中文别名（setName/slot）等价
        assertTrue(TaskMatch.hardMatch(
            JSONObject("""{"setKey":"ScrollOfTheHeroOfCinderCity"}"""), flower, 0.1,
        ))
        assertTrue(TaskMatch.hardMatch(
            JSONObject("""{"slot":"flower"}"""), flower, 0.1,
        ))
        assertFalse(TaskMatch.hardMatch(
            JSONObject("""{"slot":"goblet"}"""), flower, 0.1,
        ))
    }

    @Test
    fun `substat relative tolerance boundary`() {
        // critRate_ 实测 5.8；tol=0.1 → 相对容差 0.58 → [5.22, 6.38] 内通过，外拒绝
        val inTol = JSONObject("""{"substats":[{"key":"critRate_","value":6.3}]}""")
        val outTol = JSONObject("""{"substats":[{"key":"critRate_","value":7.0}]}""")
        val missing = JSONObject("""{"substats":[{"key":"critDMG_","value":10.0}]}""")
        assertTrue(TaskMatch.hardMatch(inTol, flower, 0.1))
        assertFalse(TaskMatch.hardMatch(outTol, flower, 0.1))
        val why = StringBuilder()
        assertFalse(TaskMatch.hardMatch(missing, flower, 0.1, why))
        assertTrue("缺词条需报出：$why", why.toString().contains("缺副词条 critDMG_"))
    }

    @Test
    fun `substat without value only checks presence`() {
        assertTrue(TaskMatch.hardMatch(JSONObject("""{"substats":[{"key":"eleMas"}]}"""), flower, 0.1))
        assertFalse(TaskMatch.hardMatch(JSONObject("""{"substats":[{"key":"critDMG_"}]}"""), flower, 0.1))
    }

    // ---- targets：筛选目标集合 ----

    @Test
    fun `targets reads setName from currentTask first`() {
        val got = TaskMatch.targets(JSONObject("""{"setName":"烬城勇者绘卷"}"""), null)
        assertEquals(setOf("烬城勇者绘卷"), got)
    }

    @Test
    fun `targets reads string and object entries from targets array`() {
        val task = JSONObject(
            """{"targets":["绝缘之旗印",{"setName":"深林的记忆"},"","x"]}""",
        )
        // "x" 非套装名但语法合法（归一失败由词典层负责），此处只验证提取规则
        assertEquals(listOf("绝缘之旗印", "深林的记忆", "x"), TaskMatch.targets(task, null).toList())
    }

    @Test
    fun `targets falls back to plan and dedupes preserving order`() {
        val plan = listOf(
            JSONObject("""{"char":"A","setName":"烬城勇者绘卷"}"""),
            JSONObject("""{"char":"B"}"""),
            JSONObject("""{"char":"C","setName":"烬城勇者绘卷"}"""), // 重复 → 去重
            JSONObject("""{"char":"D","setName":"逐影猎人"}"""),
        )
        val got = TaskMatch.targets(JSONObject("""{"setName":"深林的记忆"}"""), plan)
        assertEquals(listOf("深林的记忆", "烬城勇者绘卷", "逐影猎人"), got.toList())
    }

    @Test
    fun `targets is empty when nothing is injected`() {
        assertTrue(TaskMatch.targets(null, null).isEmpty())
        assertTrue(TaskMatch.targets(JSONObject("{}"), emptyList()).isEmpty())
        // 空 plan 项不应产生空串（否则会污染 setFilter 的 pending 集合）
        assertTrue(TaskMatch.targets(null, listOf(JSONObject("""{"char":"X"}"""))).isEmpty())
    }

    @Test
    fun `real account set name normalizes to the GOOD key used in the plan`() {
        // 实跑所依赖的机制：plan 写中文名 → setFilter 归一成 GOOD key → 与 OCR 侧 key 空间一致。
        // 这里锁死「plan 中文名 ⇄ GOOD id」这条链路的词典事实（真机与词典同源）。
        val names = GoodNames.fromJson(
            JSONObject(java.io.File(assetsDir(), "tools/good_names.json").readText()),
        )
        val r = NameMatcher.match("烬城勇者绘卷", names.table(GoodNames.Kind.SET))
        assertEquals("ScrollOfTheHeroOfCinderCity", r?.key)
        // 与 GoodArtifact.setKey 同空间 → hardMatch 的 setKey 比对才有意义
        assertEquals(flower.setKey, r?.key)
    }

    private fun assetsDir(): java.io.File {
        var dir = java.io.File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val c = java.io.File(dir, "src/main/assets/dsl")
            if (c.isDirectory) return c
            dir = dir.parentFile ?: return@repeat
        }
        error("src/main/assets/dsl not found")
    }
}
