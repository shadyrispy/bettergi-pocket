package com.bettergi.pocket.scan

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入契约推导与归一化的回归测试。
 *
 * 契约来源（2026-09-18 二次修订，权威）：`GOODScanner/docs/MANAGER_API.md`
 * + `genshin/src/manager/models.rs`：
 * - **锁定** `{lock:[GoodArtifact], unlock:[GoodArtifact]}` —— **列表归属决定动作**；
 * - **装配** `{equip:[{artifact, location}]}` —— `location:""` = 卸下。
 *
 * ⚠️ 旧的自造形态 `list<{char, slot, target}>`（`dsl/docs/flow5-auto-equip.md`，2026-09-10）
 * **已废弃**，这里刻意用用例钉死"不再兼容"。
 */
class GoodPlanTest {

    private fun flow(vars: String) = JSONObject("""{"vars":$vars,"steps":[]}""")

    private val importUi = ""","ui":{"label":"X","actions":[{"kind":"import"},{"kind":"run"}]}"""

    private fun lockFlow() = JSONObject(
        """{"vars":{"lock":"list<GoodArtifact>","unlock":"list<GoodArtifact>"}$importUi}""",
    )

    private fun equipFlow() = JSONObject(
        """{"vars":{"equip":"list<{artifact:GoodArtifact, location:string}>"}$importUi}""",
    )

    private fun scanFlow() = JSONObject(
        """{"vars":{"total":"int","results":"list<GoodArtifact>"}
           ,"ui":{"label":"圣遗物扫描","actions":[{"kind":"export"},{"kind":"run"}]}}""",
    )

    private val ga = """{"setKey":"GladiatorsFinale","slotKey":"flower","rarity":5,"level":0,
                         "mainStatKey":"hp","substats":[{"key":"critRate_","value":3.9}]}"""

    // ---- demandOf ----

    @Test
    fun demand_lock_wants_lock_lists() {
        val d = GoodPlan.demandOf(lockFlow())
        assertTrue(d.wantsLockLists)
        assertTrue(d.needsInput)
    }

    /**
     * ★ 回归（真机缺陷）：`list<{artifact:GoodArtifact, location:string}>` 里的 GoodArtifact 是
     * **项内字段**，不代表"整体吃圣遗物数组"。旧实现按 `type.contains("GoodArtifact")` 判
     * ⇒ `auto_equip` 接受纯圣遗物导出 ⇒ 跑完一圈 `cells=0`。
     */
    @Test
    fun demand_equip_does_not_swallow_artifact_exports() {
        val d = GoodPlan.demandOf(equipFlow())
        assertTrue(d.wantsEquip)
        assertFalse("记录类型（含 `{`）不得判成吃圣遗物数组", d.wantsArtifacts)
    }

    @Test
    fun demand_scan_wants_artifacts_but_is_not_the_gate() {
        val d = GoodPlan.demandOf(scanFlow())
        assertTrue(d.wantsArtifacts)
        // 扫描脚本的 results 是**产出**不是输入 ⇒ 真正的闸是「声明了 import 动作」
        assertFalse(GoodPlan.needsInput(scanFlow()))
    }

    @Test
    fun needsInput_requires_import_action() {
        assertTrue(GoodPlan.needsInput(lockFlow()))
        assertTrue(GoodPlan.needsInput(equipFlow()))
        // 有 vars 但没有 import 动作 ⇒ 不要输入
        assertFalse(GoodPlan.needsInput(JSONObject("""{"vars":{"lock":"list<GoodArtifact>"}}""")))
        assertFalse(GoodPlan.needsInput(null))
    }

    // ---- pick：锁定 ----

    @Test
    fun pick_lock_lists_merges_with_intent_lock_first() {
        val file = JSONObject(
            """{"lock":[$ga],"unlock":[{"setKey":"EmblemOfSeveredFate","slotKey":"sands","rarity":5,
               "level":20,"mainStatKey":"enerRech_","substats":[]}]}""",
        )
        val got = GoodPlan.pick(file, GoodPlan.demandOf(lockFlow()))!!
        assertEquals(2, got.size)
        // 对齐 GOODScanner 的绑定顺序："lock entries first, then unlock"
        assertTrue(got[0].optBoolean("wantLock"))
        assertFalse(got[1].optBoolean("wantLock"))
        // 圣遗物字段保留在顶层 ⇒ TaskMatch.hardMatch 可直接消费
        assertEquals("GladiatorsFinale", got[0].optString("setKey"))
    }

    @Test
    fun pick_lock_accepts_single_side_list() {
        // 契约：两键各自可选（serde default），只给 lock 也合法
        val file = JSONObject("""{"lock":[$ga]}""")
        val got = GoodPlan.pick(file, GoodPlan.demandOf(lockFlow()))!!
        assertEquals(1, got.size)
        assertNull(GoodPlan.whyNot(file, GoodPlan.demandOf(lockFlow())))
    }

    @Test
    fun whyNot_rejects_contradictory_lock_lists() {
        val file = JSONObject("""{"lock":[$ga],"unlock":[$ga]}""")
        val why = GoodPlan.whyNot(file, GoodPlan.demandOf(lockFlow()))
        assertNotNull(why)
        assertTrue("需报出意图矛盾：$why", why!!.contains("矛盾"))
        assertNull(GoodPlan.pick(file, GoodPlan.demandOf(lockFlow())))
    }

    // ---- pick：装配 ----

    @Test
    fun pick_equip_flattens_artifact_and_carries_char() {
        val file = JSONObject("""{"equip":[{"artifact":$ga,"location":"Furina"}]}""")
        val got = GoodPlan.pick(file, GoodPlan.demandOf(equipFlow()))!!
        assertEquals(1, got.size)
        assertEquals("Furina", got[0].optString("char"))
        assertEquals("GladiatorsFinale", got[0].optString("setKey"))
        assertNull(GoodPlan.whyNot(file, GoodPlan.demandOf(equipFlow())))
    }

    @Test
    fun pick_equip_empty_location_means_unequip() {
        val file = JSONObject(
            """{"equip":[{"artifact":{"setKey":"GladiatorsFinale","slotKey":"flower","mainStatKey":"hp",
                                      "location":"RaidenShogun"},
                          "location":""}]}""",
        )
        val got = GoodPlan.pick(file, GoodPlan.demandOf(equipFlow()))!!
        assertEquals("", got[0].optString("char"))
        // 当前持有者仍在 artifact.location 上（卸下时用它找角色）
        assertEquals("RaidenShogun", got[0].optString("location"))
    }

    @Test
    fun whyNot_flags_equip_entry_without_artifact() {
        val file = JSONObject("""{"equip":[{"location":"Furina"}]}""")
        val why = GoodPlan.whyNot(file, GoodPlan.demandOf(equipFlow()))
        assertNotNull(why)
        assertTrue("需指出哪一条缺 artifact：$why", why!!.contains("equip[0]"))
    }

    // ---- 交叉形态：喂错文件要说清 ----

    @Test
    fun good_export_cannot_feed_lock_or_equip() {
        val good = JSONObject("""{"format":"GOOD","artifacts":[$ga]}""")
        val whyLock = GoodPlan.whyNot(good, GoodPlan.demandOf(lockFlow()))
        assertNotNull(whyLock)
        assertTrue("要提示这是扫描导出：$whyLock", whyLock!!.contains("artifacts"))
        assertNull(GoodPlan.pick(good, GoodPlan.demandOf(lockFlow())))

        val whyEquip = GoodPlan.whyNot(good, GoodPlan.demandOf(equipFlow()))
        assertNotNull(whyEquip)
        assertNull(GoodPlan.pick(good, GoodPlan.demandOf(equipFlow())))
    }

    @Test
    fun legacy_plan_shape_is_not_accepted() {
        // 旧自造形态已废弃：必须被拒绝，且给出可读原因
        val legacy = JSONObject("""{"plan":[{"char":"希诺宁","slot":"flower","target":$ga}]}""")
        val why = GoodPlan.whyNot(legacy, GoodPlan.demandOf(equipFlow()))
        assertNotNull("旧 {plan:[{char,slot,target}]} 形态应被拒绝", why)
    }

    // ---- validateArtifacts：载荷合法性（导入期拦坏条目） ----

    @Test
    fun validate_accepts_well_formed_files() {
        assertNull(GoodPlan.validateArtifacts(JSONObject("""{"lock":[$ga]}""")))
        assertNull(GoodPlan.validateArtifacts(JSONObject("""{"equip":[{"artifact":$ga,"location":"Furina"}]}""")))
        // 3★ 已纳入（OCR 扫描与抓包两条数据源都给 3★），导入侧别再把它判成不合法
        assertNull(
            GoodPlan.validateArtifacts(
                JSONObject("""{"lock":[{"setKey":"X","slotKey":"flower","mainStatKey":"hp","rarity":3,"level":12}]}"""),
            ),
        )
    }

    @Test
    fun validate_rejects_bad_entries_with_readable_reason() {
        val noSet = JSONObject("""{"lock":[{"slotKey":"flower","mainStatKey":"hp"}]}""")
        assertEquals("lock[0]：缺 setKey", GoodPlan.validateArtifacts(noSet))

        val badRarity = JSONObject("""{"lock":[{"setKey":"X","slotKey":"flower","mainStatKey":"hp","rarity":2}]}""")
        assertTrue("需报 rarity：${GoodPlan.validateArtifacts(badRarity)}",
            GoodPlan.validateArtifacts(badRarity)!!.contains("rarity"))

        val badLevel = JSONObject("""{"lock":[{"setKey":"X","slotKey":"flower","mainStatKey":"hp","level":25}]}""")
        assertTrue("需报 level：${GoodPlan.validateArtifacts(badLevel)}",
            GoodPlan.validateArtifacts(badLevel)!!.contains("level"))

        val badSub = JSONObject("""{"equip":[{"artifact":{"setKey":"X","slotKey":"flower","mainStatKey":"hp",
            "substats":[{"value":1.0}]},"location":"A"}]}""")
        assertTrue("需报副词条缺 key：${GoodPlan.validateArtifacts(badSub)}",
            GoodPlan.validateArtifacts(badSub)!!.contains("substats[0]"))
    }

    @Test
    fun validate_is_wired_into_whyNot() {
        val bad = JSONObject("""{"lock":[{"slotKey":"flower","mainStatKey":"hp"}]}""")
        val why = GoodPlan.whyNot(bad, GoodPlan.demandOf(lockFlow()))
        assertNotNull(why)
        assertTrue("whyNot 需透出合法性问题：$why", why!!.contains("不合法"))
    }

    @Test
    fun equip_instruction_must_state_location_explicitly() {
        // 缺 location 会被当成空串 = 卸下 ⇒ 语义静默反转，必须拒绝
        val file = JSONObject("""{"equip":[{"artifact":$ga}]}""")
        val why = GoodPlan.whyNot(file, GoodPlan.demandOf(equipFlow()))
        assertNotNull(why)
        assertTrue("需报缺 location：$why", why!!.contains("location"))
    }

    @Test
    fun whyNot_explains_missing_file() {
        assertNotNull(GoodPlan.whyNot(null, GoodPlan.demandOf(lockFlow())))
    }

    @Test
    fun scan_export_still_feeds_artifact_array_scripts() {
        // 声明 `targets` 的脚本仍吃圣遗物数组（这是"扫描导出 → 同类脚本"的既有通道）
        val d = GoodPlan.Demand(wantsArtifacts = true)
        val good = JSONObject("""{"format":"GOOD","artifacts":[$ga]}""")
        assertEquals(1, GoodPlan.pick(good, d)!!.size)
        assertNull(GoodPlan.whyNot(good, d))
        assertNotNull(GoodPlan.whyNot(JSONObject("""{"format":"GOOD"}"""), d))
        assertNull(GoodPlan.pick(null, d))
    }
}
