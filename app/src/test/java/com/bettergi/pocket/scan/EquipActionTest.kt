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

    // ---- item5：卸下意图（GOOD 契约 location:"" = 卸下）----

    @Test
    fun `intent is unequip only when char blank and location present`() {
        assertEquals(EquipIntent.EQUIP, equipIntentOf("Tighnari", ""))
        assertEquals(EquipIntent.UNEQUIP, equipIntentOf("", "Tighnari"))
        // 两边都空 = 坏输入 ⇒ 判 EQUIP（让 rosterFind 响亮地中止，而不是猜一个持有者）
        assertEquals(EquipIntent.EQUIP, equipIntentOf("", ""))
        assertEquals(EquipIntent.EQUIP, equipIntentOf(null, null))
    }

    @Test
    fun `unequip intent clicks only on the unload button`() {
        // 「卸下」⇒ 这件穿在他身上 ⇒ 点它才卸得下来（装备侧在这一态是"不点"）
        assertEquals(
            EquipDecision(click = true, actTried = true, actOk = true),
            equipDecisionOf(EquipAction.ALREADY_EQUIPPED, EquipIntent.UNEQUIP),
        )
        // 「替换/装备」⇒ 这件不在他身上（与"已经卸干净"同形）⇒ 不点、且不算失败
        assertEquals(
            EquipDecision(click = false, actTried = false, actOk = false),
            equipDecisionOf(EquipAction.EQUIP, EquipIntent.UNEQUIP),
        )
        // 读不出 ⇒ 同样不点，但记 actTried 落 Failed
        assertEquals(
            EquipDecision(click = false, actTried = true, actOk = false),
            equipDecisionOf(EquipAction.UNKNOWN, EquipIntent.UNEQUIP),
        )
    }

    @Test
    fun `equip intent keeps today's decisions bit for bit`() {
        for (a in EquipAction.entries) {
            assertEquals(equipDecisionOf(a), equipDecisionOf(a, EquipIntent.EQUIP))
        }
    }

    @Test
    fun `unequip verify keys on the owner line not on emptiness`() {
        // owner=null（面板里没有「XX已装备」那行）⇒ 没人穿 ⇒ 卸干净了
        assertEquals(EquipVerify.APPLIED, unequipVerifyOf(readOk = true, owner = null))
        assertEquals(EquipVerify.APPLIED, unequipVerifyOf(readOk = true, owner = ""))
        // 还有主人 ⇒ 没卸掉
        assertEquals(EquipVerify.NOT_APPLIED, unequipVerifyOf(readOk = true, owner = "荒泷一斗"))
        // 取帧/OCR 失败 = 没看见 ⇒ 不等于成功
        assertEquals(EquipVerify.NOTHING_READ, unequipVerifyOf(readOk = false, owner = null))
    }

    @Test
    fun `unequip confirm reaches Success through the same status mapping`() {
        // 卸下侧走通到 Success 的完整链路：命中 + 点过 + 复核确认"那格空了"
        assertEquals(
            "Success",
            manageStatusOf(
                matchHit = true, actTried = true, actOk = true,
                verified = EquipVerify.APPLIED.verifiedFlag(), flowStop = false,
            ),
        )
        // 复核读到"还穿着" ⇒ Failed（不是 Success，也不是 AlreadyCorrect）
        assertEquals(
            "Failed",
            manageStatusOf(
                matchHit = true, actTried = true, actOk = true,
                verified = EquipVerify.NOT_APPLIED.verifiedFlag(), flowStop = false,
            ),
        )
    }
}
