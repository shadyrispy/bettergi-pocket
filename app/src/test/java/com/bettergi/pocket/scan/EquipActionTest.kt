package com.bettergi.pocket.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #93：左下动作钮文本 → 该做什么；#107：点完之后复核"到底换上没有"的判据。
 *
 * 点击判据来自参照实现 GOODScanner `ui_actions.rs::click_equip_button_safe_at`：
 * OCR `SEL_ACTION_BUTTON_RECT`，含「卸」⇒ 已装备不点；含「装/替」⇒ 点。
 * 我们 profile 的 `screens.artifact_manage.leftBtn.states` = {「替换」/「卸下」} 同构。
 * 复核那一半**上游没有**（它的 verify 是 no-op），见 [equipVerifyOf]。
 */
class EquipActionTest {

    @Test
    fun `unequip label means already equipped`() {
        assertEquals(EquipAction.ALREADY_EQUIPPED, equipActionOf("卸下"))
        assertEquals(EquipAction.ALREADY_EQUIPPED, equipActionOf("卸"))
        // OCR 常带噪：只要含「卸」即可
        assertEquals(EquipAction.ALREADY_EQUIPPED, equipActionOf("卸 下"))
    }

    @Test
    fun `equip and replace labels mean act`() {
        assertEquals(EquipAction.EQUIP, equipActionOf("替换"))
        assertEquals(EquipAction.EQUIP, equipActionOf("装备"))
        assertEquals(EquipAction.EQUIP, equipActionOf("替 换"))
    }

    @Test
    fun `unreadable text is unknown not a guess`() {
        // 读不出 ⇒ 不能猜"没装"（会静默不点、把能装的成功流程弄失效）
        assertEquals(EquipAction.UNKNOWN, equipActionOf(null))
        assertEquals(EquipAction.UNKNOWN, equipActionOf(""))
        assertEquals(EquipAction.UNKNOWN, equipActionOf("   "))
        // 两种关键词都不含（OCR 乱码）也算未知
        assertEquals(EquipAction.UNKNOWN, equipActionOf("★★"))
    }

    @Test
    fun `unequip wins when both keywords present`() {
        // 「卸下装」这类含噪串：先判「卸」= 已在身上的语义，比"点它"安全
        assertEquals(EquipAction.ALREADY_EQUIPPED, equipActionOf("卸下装备"))
    }

    // ---- 文本判据 → 动作 → 结果标签，整条串起来钉死（#93 的病根就是这三段各走各路）----

    @Test
    fun `equip label clicks, but the click alone is not success`() {
        val d = equipDecisionOf(equipActionOf("替换"))
        assertTrue("按钮写着替换就该点", d.click)
        // #107：点过了 ≠ 换上了。Success 从此要等点击后的独立复核（读回装备者栏）点头。
        assertEquals(
            "只点了没复核 ⇒ 不许报 Success",
            "ClickedUnverified",
            manageStatusOf(matchHit = true, actTried = d.actTried, actOk = d.actOk, verified = null, flowStop = false),
        )
        assertEquals(
            "复核确认生效 ⇒ 才是 Success",
            "Success",
            manageStatusOf(matchHit = true, actTried = d.actTried, actOk = d.actOk, verified = true, flowStop = false),
        )
    }

    @Test
    fun `unequip label clicks nothing and yields already correct`() {
        val d = equipDecisionOf(equipActionOf("卸下"))
        assertFalse("已穿在此角色身上 ⇒ 点下去就是卸下", d.click)
        assertEquals("AlreadyCorrect", manageStatusOf(true, d.actTried, d.actOk, verified = null, false))
    }

    @Test
    fun `unreadable label never clicks and can never report success`() {
        // 参照实现 `ui_actions.rs:2076` "Neither detected — bail"：判不出时**不发动作**。
        // 反证为什么必须这样：按钮真值很可能是「卸下」（只是没读出来），照点 = 卸掉用户的装备。
        for (txt in listOf(null, "", "★★", "   ")) {
            val d = equipDecisionOf(equipActionOf(txt))
            assertFalse("读不出 ('$txt') 绝不能点", d.click)
            assertEquals(
                "读不出 ('$txt') 必须落 Failed，不许静默成 AlreadyCorrect",
                "Failed",
                manageStatusOf(true, d.actTried, d.actOk, verified = null, false),
            )
        }
    }

    // ---- #107：点击后的复核判据（读「XX已装备」与目标角色比对）----

    /** 生产里 resolve 走 #105 昵称表 + 官方词典；测试给一张固定的小表，语义一样。 */
    private val resolveMap: (String) -> String? = {
        mapOf("珐露珊" to "Faruzan", "行秋" to "Xingqiu", "芙宁娜" to "Furina")[it]
    }

    @Test
    fun `verify passes only when the owner is the target character`() {
        assertEquals(
            EquipVerify.APPLIED,
            equipVerifyOf("珐露珊", "Faruzan", resolveMap),
        )
    }

    @Test
    fun `a different owner is an observed failure, not a missing observation`() {
        // 目标件还穿在行秋身上 ⇒ 我们**看见了**它没换上。这一档才允许判 Failed。
        val outcome = equipVerifyOf("行秋", "Faruzan", resolveMap)
        assertEquals(EquipVerify.NOT_APPLIED, outcome)
        assertEquals(
            "Failed",
            manageStatusOf(
                matchHit = true, actTried = true, actOk = true,
                verified = outcome.verifiedFlag(), flowStop = false,
            ),
        )
    }

    @Test
    fun `empty read is not evidence of failure`() {
        // 目标件本来就在背包里（无人装备）时，换装失败与"还没轮到它显示"给出的都是空格 ⇒
        // 只能记"没看见"。（`verifyEquippedBy` 因此对这一档重试若干轮后才下结论。）
        for (owner in listOf(null, "")) {
            assertEquals(EquipVerify.NOTHING_READ, equipVerifyOf(owner, "Faruzan", resolveMap))
        }
    }

    @Test
    fun `an owner we cannot name is not evidence either way`() {
        // 读到了字但归一不出 GOOD key —— #105 的昵称表没填是最常见的原因。
        // 判它 Failed 会把"用户没配昵称"显示成"装配在失败"，两种成因就再也分不开了。
        // ⚠️ 本用例第一版就是在这里把 OWNER_UNRESOLVED 折成了 `false` 而**自己报错**，
        //    即它要防的那个错误。映射现在单点在 [verifiedFlag]，两侧共用。
        val outcome = equipVerifyOf("崽崽", "Faruzan", resolveMap)
        assertEquals(EquipVerify.OWNER_UNRESOLVED, outcome)
        assertEquals(null, outcome.verifiedFlag())
        assertEquals(
            "ClickedUnverified",
            manageStatusOf(
                matchHit = true, actTried = true, actOk = true,
                verified = outcome.verifiedFlag(), flowStop = false,
            ),
        )
    }
}
