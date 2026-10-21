package com.bettergi.pocket.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #105 昵称表的归一规则。
 *
 * 钉的是**两条路径口径不同**这件事：GT 的角色栏写 `TravelerCryo`（带元素），装备者栏写 `Traveler`
 * （不带）—— 本账号 92 件有主人的武器里唯一那条旅行者就是 `Traveler`。把昵称表同时喂给两条路径
 * 而不区分，就会有一侧必错，而且错得很像对（都是 Traveler 开头）。
 */
class NameOverridesTest {

    private val filled = NameOverrides(
        traveler = "崽崽",
        wanderer = "流浪喵",
        manekin = "随机姓",
        manekina = "随机人",
    )

    @Test
    fun `owner path gives the bare traveler key`() {
        assertEquals("Traveler", filled.ownerKeyOf("崽崽"))
        assertEquals("Wanderer", filled.ownerKeyOf("流浪喵"))
        assertEquals("Manekin", filled.ownerKeyOf("随机姓"))
        assertEquals("Manekina", filled.ownerKeyOf("随机人"))
    }

    @Test
    fun `character path gives the bare traveler key and the caller adds the element`() {
        // #146：这一格**故意**给不带元素的裸键 —— `ScanEngine.resolveKey` 拿到 `Traveler` 后
        // 会按角色面板读到的「X元素」补成 GT 要的 `Traveler<元素>`。
        // 旧契约是"这里干脆不认旅行者"，但那等于要求玩家每换一次元素就重填一次昵称表 ⇒ 改成裸键 + 调用方补后缀。
        assertEquals("Traveler", filled.characterKeyOf("崽崽"))
        assertEquals("Manekina", filled.characterKeyOf("随机人"))
    }

    @Test
    fun `empty table resolves nothing and never matches a blank read`() {
        // OCR 读空时 StatParser.clean 会给出 ""，而 traveler/wanderer… 全是 null。
        // 若按 `display == traveler` 直比，"" 不会撞上 null；但**两个留空的字段会互相撞上**，
        // 所以空值在 load 侧就被折成 null（见 trimmed），这里再确认一次空表的行为。
        assertNull(NameOverrides.EMPTY.ownerKeyOf("崽崽"))
        assertNull(NameOverrides.EMPTY.ownerKeyOf(""))
        assertNull(filled.ownerKeyOf(""))
    }

    @Test
    fun `a nickname that equals another character's name still resolves once`() {
        // 两个字段填了同一个显示名 ⇒ 取声明顺序里第一个（traveler 在前）。
        // 这不是"正确答案"，是**不崩**：用户填重了应当看到归一结果，而不是拿到 null 又去猜。
        val dup = NameOverrides(traveler = "小明", manekina = "小明")
        assertEquals("Traveler", dup.ownerKeyOf("小明"))
    }

    @Test
    fun `withField touches only the named slot`() {
        val updated = NameOverrides.EMPTY.withField("manekin", "随机姓").withField("traveler", "崽崽")
        assertEquals("崽崽", updated.traveler)
        assertEquals("随机姓", updated.manekin)
        assertNull(updated.wanderer)
        assertNull(updated.manekina)
        assertEquals("未知字段名不该改坏表", updated, updated.withField("nope", "x"))
    }
}
