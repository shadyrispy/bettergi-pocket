package com.bettergi.pocket.scan

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入需求推导的回归测试（2026-09-18）。
 *
 * 钉住两条**真机契约**：
 * - `artifact_lock` 的 `vars.targets: list<GoodArtifact>` ⇒ 吃 GOOD 的 `artifacts[]`（字段同构，恒等映射）；
 * - `auto_equip` 的 `vars.plan: list<{char,slot,target}>` ⇒ 只认 `plan[]`，GOOD 导出喂不了（缺 `char`）。
 */
class GoodPlanTest {

    private fun flow(vars: String) = JSONObject("""{"vars":$vars,"steps":[]}""")

    @Test
    fun demand_lock_wants_artifacts() {
        val d = GoodPlan.demandOf(flow("""{"targets":"list<GoodArtifact>"}"""))
        assertTrue(d.wantsArtifacts)
        assertFalse(d.wantsPlan)
        assertTrue(d.needsInput)
    }

    @Test
    fun demand_equip_wants_plan() {
        val d = GoodPlan.demandOf(flow("""{"plan":"list<{char, slot, target:GoodArtifact}>"}"""))
        assertTrue(d.wantsPlan)
        // 类型串里也含 GoodArtifact ⇒ wantsArtifacts 亦为真（计划文件同样能喂 targets）
        assertTrue(d.wantsArtifacts)
    }

    @Test
    fun demand_alone_is_not_the_gate() {
        // 只看 vars 会误判：扫描脚本的 results 也是 list<GoodArtifact>（那是**产出**）
        val d = GoodPlan.demandOf(flow("""{"total":"int","results":"list<GoodArtifact>"}"""))
        assertTrue(d.wantsArtifacts)
        // ⇒ 所以真正的闸是「声明了 import 动作」，见下一条用例
    }

    @Test
    fun needsInput_requires_import_action() {
        val lock = JSONObject(
            """{"vars":{"targets":"list<GoodArtifact>"},
               "ui":{"label":"圣遗物锁定","actions":[{"kind":"import"},{"kind":"run"}]}}""",
        )
        assertTrue(GoodPlan.needsInput(lock))

        val scan = JSONObject(
            """{"vars":{"total":"int","results":"list<GoodArtifact>"},
               "ui":{"label":"圣遗物扫描","actions":[{"kind":"export"},{"kind":"run"}]}}""",
        )
        assertFalse(GoodPlan.needsInput(scan))

        assertFalse(GoodPlan.needsInput(JSONObject("""{"vars":{"targets":"list<GoodArtifact>"}}""")))
        assertFalse(GoodPlan.needsInput(null))
    }

    @Test
    fun pick_artifacts_for_lock() {
        val file = JSONObject("""{"format":"GOOD","artifacts":[{"setKey":"a"},{"setKey":"b"}]}""")
        val got = GoodPlan.pick(file, GoodPlan.Demand(wantsPlan = false, wantsArtifacts = true))
        assertEquals(2, got!!.size)
        assertEquals("a", got[0].optString("setKey"))
    }

    @Test
    fun pick_plan_for_equip() {
        val file = JSONObject("""{"plan":[{"char":"希诺宁","slot":"flower"}]}""")
        val got = GoodPlan.pick(file, GoodPlan.Demand(wantsPlan = true, wantsArtifacts = true))
        assertEquals(1, got!!.size)
        assertTrue(GoodPlan.planHasChar(got))
    }

    @Test
    fun pick_good_export_cannot_feed_equip() {
        val good = JSONObject("""{"format":"GOOD","artifacts":[{"setKey":"a"}]}""")
        assertNull(GoodPlan.pick(good, GoodPlan.Demand(wantsPlan = true, wantsArtifacts = false)))
    }

    @Test
    fun pick_empty_file_returns_null() {
        assertNull(GoodPlan.pick(JSONObject("""{"format":"GOOD"}"""), GoodPlan.Demand(false, true)))
        assertNull(GoodPlan.pick(null, GoodPlan.Demand(false, true)))
    }

    @Test
    fun planHasChar_false_when_any_item_misses_char() {
        val plan = listOf(JSONObject("""{"char":"A"}"""), JSONObject("""{"slot":"flower"}"""))
        assertFalse(GoodPlan.planHasChar(plan))
        assertFalse(GoodPlan.planHasChar(emptyList()))
    }
}
