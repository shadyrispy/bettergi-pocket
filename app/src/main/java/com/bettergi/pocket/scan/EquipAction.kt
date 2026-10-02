package com.bettergi.pocket.scan

/**
 * 「圣遗物管理界面左下动作钮」文本 → 该做什么。纯决策，无 IO，可离线单测。
 *
 * ## 背景（#93）
 *
 * `auto_equip` 的动作链是：网格里选中目标件 → 点左下动作钮 → （跨角色时）确认弹窗。
 * 而结果契约（`foreach` 末尾的状态映射）要判 `matchHit` / `actTried` / `actOk` 三项，
 * 这条链此前**一个都不置** ⇒ 状态标签与实际做的事无关。
 *
 * 参照实现 GOODScanner（`ui_actions.rs::click_equip_button_safe_at`）的回执判据**就是
 * 读这颗按钮的文本**：
 * - 含「卸」⇒ 该件已在此角色身上 ⇒ `AlreadyCorrect`（**不点**）；
 * - 含「装 / 替」⇒ 点它（+ 确认）。
 *
 * ⚠️ 但"点了"不等于"换上了"（#107）：GOODScanner 那条链到此就返回成功，它的
 *   `verify_artifact_equipped` 是个返回 `Ok(true)` 的 no-op。我们这边 `Success` 从此还要
 *   [equipVerifyOf] 的正面证据 —— 这一条**有意偏离上游**，理由见该函数。
 *
 * 按钮的两态在 profile 里已有语义登记：`screens.artifact_manage.leftBtn.states`
 * = {「替换」: 默认, 「卸下」: 选中件 = 当前角色正穿戴}。
 *
 * ## 为什么"读不出文本"既不点、也不算已装备
 *
 * 该 ROI 只有 94×54 帧像素（3200 档），字体虽大但仍是小 ROI OCR，**实测未验证**读得准。
 * 读不出时不能猜，而两个方向里**只有一个会造成损失**：
 * - 不点 ⇒ 这一项最多是"没装成"， loudly 记 Failed，账号数据不动；
 * - 照点 ⇒ 按钮其实写着「卸下」（只是没 OCR 出来）时，会把该角色这件装备**卸下来**。
 *
 * ⇒ 与参照实现同 choice：`ui_actions.rs:2076` 那一支注释原文就是 "Neither detected — bail"，
 * 它连确认弹窗都不点，只存一张取证图然后返回 Err。
 */
internal enum class EquipAction {
    /** 按钮说「替换 / 装备」⇒ 点它。 */
    EQUIP,

    /** 按钮说「卸下」⇒ 目标件已在此角色身上，**不要点**。 */
    ALREADY_EQUIPPED,

    /** 文本读不出 / 两种关键词都不含 ⇒ 判不出该不该动 ⇒ **不点**，但要把这一项记成失败。 */
    UNKNOWN,
}

/**
 * 按钮语义 → 引擎的动作与回执位。抽成纯函数是为了能把**三档一路串到最终标签**测掉
 * （见 `EquipActionTest`：`equipDecisionOf` 的输出喂进 [manageStatusOf] 必须是
 * Success / AlreadyCorrect / Failed）—— #93 的病根正是"动作与标签各走各路"，
 * 只测文本判据测不到这一环。
 */
internal data class EquipDecision(
    val click: Boolean,
    val actTried: Boolean,
    val actOk: Boolean,
)

internal fun equipDecisionOf(action: EquipAction): EquipDecision = when (action) {
    EquipAction.EQUIP -> EquipDecision(click = true, actTried = true, actOk = true)
    // 不点、也不记"尝试过" ⇒ AlreadyCorrect（这一档**只**能由按钮真的写着「卸」得到）
    EquipAction.ALREADY_EQUIPPED -> EquipDecision(click = false, actTried = false, actOk = false)
    // 点了才对"有没有装成"负责，这里动作根本没发 ⇒ 不能记 Success；
    // 但确实处理过这一格且判不出 ⇒ 记 actTried（不记 actOk）落 Failed，进摘要异常点名。
    EquipAction.UNKNOWN -> EquipDecision(click = false, actTried = true, actOk = false)
}

internal fun equipActionOf(buttonText: String?): EquipAction {
    val t = buttonText?.trim().orEmpty()
    if (t.isEmpty()) return EquipAction.UNKNOWN
    // ⚠️ 先判「卸」：「卸下」不含「装/替」，但顺序写反也不会错；写在前是为了语义清楚。
    if (t.contains("卸")) return EquipAction.ALREADY_EQUIPPED
    if (t.contains("替") || t.contains("装")) return EquipAction.EQUIP
    return EquipAction.UNKNOWN
}

/**
 * #107：**点击之后**复核的四种结论。
 *
 * 判据是详情面板底部的「XX已装备」（`panels.artifact_manage.equipped`）—— 它说的就是
 * "这件在谁身上"这件事本身，不像"左下按钮翻没翻成卸下"那样是代理量（那颗钮与角色页右下角
 * 的「替换」几乎同高同带，页面一退回去就会给出假信号）。
 */
internal enum class EquipVerify {
    /** 装备者就是目标角色 ⇒ 真的换上了。这是 `Success` 的唯一入口。 */
    APPLIED,

    /** 读到的是**别人** ⇒ 确认没换上。 */
    NOT_APPLIED,

    /** 那一格空 / 文案里没有「已装备」⇒ **没看见**，不等于没发生。 */
    NOTHING_READ,

    /** 读到了名字但归一不出 GOOD key（#105 的昵称表没填是主因）⇒ 同样**没看见**。 */
    OWNER_UNRESOLVED,
}

/**
 * 纯决策（可离线单测）：装备者栏文本 + 目标角色 + 名字解析器 → 复核结论。
 *
 * ⚠️ `NOT_APPLIED` 与两种"没看见"必须分开：前者是**观察到失败**，后者是**没有观察**。
 * 合成一档的话，"复核器坏了/用户没填昵称"会伪装成"装配一直在失败"，久了没人再看这个标签。
 */
internal fun equipVerifyOf(
    owner: String?,
    expectChar: String,
    resolve: (String) -> String?,
): EquipVerify = when {
    owner.isNullOrEmpty() -> EquipVerify.NOTHING_READ
    else -> when (val key = resolve(owner)) {
        null -> EquipVerify.OWNER_UNRESOLVED
        expectChar -> EquipVerify.APPLIED
        else -> EquipVerify.NOT_APPLIED
    }
}

/**
 * 四档 → 结果契约里的 `verified: Boolean?`。**这条映射只此一份**：
 * 生产侧 [ScanEngine] 与单测都走它，否则"四档压成两档"这种错误会在某一侧悄悄发生
 * （写本条时单测就抓到了一次：把 `OWNER_UNRESOLVED` 折成 `false` ⇒ "用户没填昵称"
 * 会被显示成"装配在失败"）。
 */
internal fun EquipVerify.verifiedFlag(): Boolean? = when (this) {
    EquipVerify.APPLIED -> true
    EquipVerify.NOT_APPLIED -> false
    EquipVerify.NOTHING_READ, EquipVerify.OWNER_UNRESOLVED -> null
}
