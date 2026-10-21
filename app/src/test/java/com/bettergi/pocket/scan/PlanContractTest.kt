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
 * 契约来源（2026-09-18 起）：GOODScanner —— 锁定 `{lock,unlock}`、装配 `{equip:[{artifact,location}]}`，
 * 由 [GoodPlan] 归一成扁平计划项；
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
               "mainStatKey":"hp","substats":[
                 {"key":"critRate_","value":5.8},{"key":"hp","value":717},
                 {"key":"enerRech_","value":12.4},{"key":"eleMas","value":23}]}""",
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

    /**
     * ★ 2026-09-18（P1⑥）副词条改**严格**：对齐 GOODScanner `matching::substats_match`
     * （数量相等 + 目标每个 key 都在实际里 + 值差 ≤ tol·|目标值|）。
     * 旧语义是宽松子集 ⇒ 目标只列 1 条也能命中 4 条的件（过匹配，可能锁错件）。
     */
    @Test
    fun `substats are strict - all listed, count equal, tolerance on values`() {
        val ok = JSONObject(
            """{"substats":[{"key":"critRate_","value":5.8},{"key":"hp","value":717},
               {"key":"enerRech_","value":12.4},{"key":"eleMas","value":23}]}""",
        )
        // critRate_ 实测 5.8；tol=0.1 → 相对容差 0.58 → [5.22, 6.38] 内通过，外拒绝
        val inTol = JSONObject(
            """{"substats":[{"key":"critRate_","value":6.3},{"key":"hp","value":717},
               {"key":"enerRech_","value":12.4},{"key":"eleMas","value":23}]}""",
        )
        val outTol = JSONObject(
            """{"substats":[{"key":"critRate_","value":7.0},{"key":"hp","value":717},
               {"key":"enerRech_","value":12.4},{"key":"eleMas","value":23}]}""",
        )
        val short = JSONObject("""{"substats":[{"key":"critRate_","value":5.8}]}""")
        val missing = JSONObject(
            """{"substats":[{"key":"critRate_","value":5.8},{"key":"hp","value":717},
               {"key":"enerRech_","value":12.4},{"key":"critDMG_","value":23}]}""",
        )
        assertTrue(TaskMatch.hardMatch(ok, flower, 0.1))
        assertTrue(TaskMatch.hardMatch(inTol, flower, 0.1))
        assertFalse(TaskMatch.hardMatch(outTol, flower, 0.1))
        val whyShort = StringBuilder()
        assertFalse(TaskMatch.hardMatch(short, flower, 0.1, whyShort))
        assertTrue("数量不符需报出：$whyShort", whyShort.toString().contains("数量"))
        val whyMiss = StringBuilder()
        assertFalse(TaskMatch.hardMatch(missing, flower, 0.1, whyMiss))
        assertTrue("缺词条需报出：$whyMiss", whyMiss.toString().contains("critDMG_"))
    }

    @Test
    fun `rarity and elixirCrafted are hard fields`() {
        assertTrue(TaskMatch.hardMatch(JSONObject("""{"rarity":5}"""), flower, 0.1))
        assertFalse(TaskMatch.hardMatch(JSONObject("""{"rarity":4}"""), flower, 0.1))
        // 未给 rarity ⇒ 不比对（手写的最小计划仍可用）
        assertTrue(TaskMatch.hardMatch(JSONObject("""{"level":20}"""), flower, 0.1))
        // 祝圣：显式给出时才比对
        assertTrue(TaskMatch.hardMatch(JSONObject("""{"elixirCrafted":false}"""), flower, 0.1))
        assertFalse(TaskMatch.hardMatch(JSONObject("""{"elixirCrafted":true}"""), flower, 0.1))
    }

    @Test
    fun `identityKeyOf groups identical pieces and separates different ones`() {
        val a = JSONObject(
            """{"setKey":"X","slotKey":"flower","rarity":5,"level":20,"mainStatKey":"hp",
               "substats":[{"key":"critRate_","value":5.8},{"key":"hp","value":717}]}""",
        )
        // 副词条顺序不同、值有 0.04 的舍入差 ⇒ 仍是同一件
        val same = JSONObject(
            """{"setKey":"X","slotKey":"flower","rarity":5,"level":20,"mainStatKey":"hp",
               "substats":[{"key":"hp","value":717.04},{"key":"critRate_","value":5.8}]}""",
        )
        val other = JSONObject(
            """{"setKey":"X","slotKey":"flower","rarity":5,"level":20,"mainStatKey":"hp",
               "substats":[{"key":"critRate_","value":5.8},{"key":"hp","value":717},{"key":"eleMas","value":1}]}""",
        )
        assertEquals(TaskMatch.identityKeyOf(a), TaskMatch.identityKeyOf(same))
        assertFalse(TaskMatch.identityKeyOf(a) == TaskMatch.identityKeyOf(other))
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

    /**
     * 2026-09-18 真机缺陷回归：GOOD 导出的圣遗物项只有 `setKey`（= 词典 `artifactSets[].id`），
     * **没有** `setName`。只读 `setName` 时，管理器导入的 GOOD 文件（`artifact_lock` 的输入形态）
     * 恒得到空目标集 ⇒ `setFilter: 无筛选目标（P4 未注入 setName/targets）` ⇒ 流程空跑。
     */
    @Test
    fun `targets reads setKey so GOOD exports can drive setFilter`() {
        val plan = listOf(JSONObject("""{"setKey":"GladiatorsFinale","slotKey":"flower","level":0}"""))
        assertEquals(listOf("GladiatorsFinale"), TaskMatch.targets(null, plan).toList())

        assertEquals(
            setOf("AubadeOfMorningstarAndMoon"),
            TaskMatch.targets(JSONObject("""{"setKey":"AubadeOfMorningstarAndMoon"}"""), null),
        )
        // targets[] 里的对象元素同样要读 setKey
        assertEquals(
            setOf("EmblemOfSeveredFate"),
            TaskMatch.targets(JSONObject("""{"targets":[{"setKey":"EmblemOfSeveredFate"}]}"""), null),
        )
        // setName 与 setKey 并存时都进集合（归一在词典层，去重交给 LinkedHashSet）
        assertEquals(
            listOf("烬城勇者绘卷", "ScrollOfTheHeroOfCinderCity"),
            TaskMatch.targets(
                JSONObject("""{"setName":"烬城勇者绘卷","setKey":"ScrollOfTheHeroOfCinderCity"}"""),
                null,
            ).toList(),
        )
    }

    @Test
    fun `real account set name normalizes to the GOOD key used in the plan`() {
        // 实跑所依赖的机制：plan 写中文名 → setFilter 归一成 GOOD key → 与 OCR 侧 key 空间一致。
        // 这里锁死「plan 中文名 ⇄ GOOD id」这条链路的词典事实（真机与词典同源）。
        val names = GoodNames.fromJson(
            JSONObject(java.io.File(assetsDir(), "tools/mappings.json").readText()),
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
