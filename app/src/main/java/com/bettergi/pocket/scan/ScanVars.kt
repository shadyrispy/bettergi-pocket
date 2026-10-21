package com.bettergi.pocket.scan

import org.json.JSONObject

/** 扫描会话变量（flow vars + visit 产物）。 */
class ScanVars {
    var total: Int? = null
    /** 计数器所在的 rect 路径；`pagedGrid` 收尾前要凭它复读一次（见 `recountCounter`）。 */
    var totalRect: String? = null
    var gridLocked: Boolean? = null
    var crafted: Boolean = false
    var rarity: Int = -1
    var locked: Boolean? = null
    var favorited: Boolean? = null
    /** ocrWithRetry 命中写入（fuzzy 词典匹配 key）。 */
    var ocrMatch: String? = null
    /** vote as=curLock 快照（artifact_lock ifMatch 的 verify toggle 依据）。 */
    var curLock: Boolean? = null
    /**
     * `parsePanel.match` 的求值结果（★ 2026-09-18 新增）。
     *
     * 为什么必须补：`match` 此前**从未被消费** —— 于是引用它的 `ifMatch` 只能写成无条件，
     * 锁定流程就会对**任何**读到"未锁"的格子点锁钮，包括根本不在计划里的 4★ 件
     * （真机实测：套装过滤后走到该套装的 4★ 件便开始点击，靠 `verify FAILED` 才没落地）。
     * 现在由 flow 写 `ifMatch when="panelMatched"` 门控 ⇒ 只对命中目标动作。
     */
    var panelMatched: Boolean = false
    /**
     * 结果契约（★ 2026-09-18，对齐 GOODScanner 的 `InstructionStatus`）：**按计划项**记录
     * ① 是否命中（parsePanel.match）② 是否尝试过写入 ③ 写入后判据是否通过。
     * 由 `foreach` 每项开头复位、末尾汇集，最终出 `ManageSummary`。
     */
    var matchHit: Boolean = false
    var actTried: Boolean = false
    var actOk: Boolean = false

    /**
     * #107：**动作发出之后**有没有拿到"确实生效"的独立证据。
     * `null` = 该路径不复核 / 复核读不出；`true` = 复核确认生效；`false` = 复核确认**没**生效。
     *
     * 为什么必须和 [actOk] 分开：装配链的 `actOk` 含义只是"按钮文本判出来该点、并且点了"，
     * **不含任何点击后的回读** ⇒ `Success` 实际只证明"我们点过了"。2026-09-28 实测撞上：
     * 一次报 Success 的换装，事后逐格核对服务端仍是原件（参考实现 GOODScanner 的
     * `verify_artifact_equipped` 本身就是返回 `Ok(true)` 的 no-op ⇒ 这是同源缺陷，不是我们偏离上游）。
     */
    var actVerified: Boolean? = null

    /** #107：本项是否真点过装配动作钮（= 欠一次点击后复核）。只在 `dualStateButton` 的点击支置。 */
    var equipClicked: Boolean = false

    /**
     * **本项到此为止，别再碰 UI**（2026-09-29 #136）。由 `assertScreen onFail="abortItem"` 置，
     * `foreach` 见它就记 Failed、跳过本项余下步骤、复位后继续下一个目标。
     *
     * 为什么单独一个标志而不用 `stopRequested`：后者的语义是"整轮停"，而这里恰恰相反 ——
     * 屏幕已经不是我们以为的那个界面，**继续点下去比不点更糟**。
     * 真机事故：auto_equip 的套装筛选放弃后，收尾链连按 BACK 一路退出了角色页，
     * 后面的 `pagedGrid` 就对着大世界点了 15 格，最后误开「确认退出游戏」。
     * 而当时屏上根本没有任何"我还在不在圣遗物管理页"的判据在把关。
     */
    var abortItem: Boolean = false

    /**
     * **只有锁定流程**动过锁钮。[actTried] 原本兼任此职，但 #93 让装配链也开始置它 ⇒
     * 必须分开，否则装配点一次「替换」就会让 `parsePanel` 里的加锁确认框探测
     * （`if (actTried) dismissLockConfirm(...)`）在**装配流程**里跑起来 —— 那是"锁写入后才可能
     * 出现的框"，在装配流程里探测它等于在浅色画面上误判并盲点一下确认。
     */
    var lockWriteAttempted: Boolean = false
    /**
     * 本项这一趟 pagedGrid 是**网格到底**（连续重复 ⇒ 回卷/耗尽）结束的，不是命中目标结束的。
     * 与 `stopReason` 分开记，因为回卷止扫与命中止扫历史上共用 `"stopWhen"` 一个字串，
     * 改它会动到 `scan finished: <reason>` 日志与解析它的脚本。
     */
    var gridExhausted: Boolean = false

    /**
     * **绑定表**（★ 2026-09-18，单趟扫描用）：已绑定（处理过）的计划项下标。
     *
     * 为什么需要：`artifact_lock` 原来是 `foreach{ pagedGrid }` —— **每个目标重走一遍同一张网格**，
     * 而"走到底"的判据（回卷/重复件）全是**累积式**的 ⇒ 第 2 个目标起每一件都是"本轮已入库"，
     * 走查被立刻截断（真机实测：目标 1 走 182s，目标 2 只走 64s，真件被判 NotFound）。
     * 改成**单趟扫描**后：一次走查，每格用 [planMatchedIndex] 找出它对应哪个未绑定的计划项并处理，
     * 处理完加进本集合 ⇒ 既没有"重走"，也没有"累积冲突"，耗时还从 N× 降到 1×。
     */
    val consumedPlanIndexes: MutableSet<Int> = mutableSetOf()

    /** 本格绑定到的计划项下标（-1 = 未命中）。由 `planMatch` 写入，`planDone` 消费。 */
    var planMatchedIndex: Int = -1

    /** 尚未绑定的计划项数（供 flow 写 `stopWhen expr="planRemaining == 0"` 提前收尾）。 */
    fun planRemaining(): Int {
        val total = plan?.size ?: 0
        return (0 until total).count { it !in consumedPlanIndexes }
    }

    /** foreach：外部注入任务计划（P4 规则层注入；flow 内按 task 使用）。 */
    var plan: List<JSONObject>? = null
    var currentTask: JSONObject? = null
    var level: Int = 0
    var stopRequested: Boolean = false
    /** 连续重复角色计数（止扫判据观测用；0 = 未启用或上一个不是重复）。 */
    var charDupStreak: Int = 0
    /**
     * 终止原因（可观测性）。`stopRequested` 被三个不同的地方置位：`stopWhen` 命中、flow 的
     * `exit` 步、`pagedGrid` 的 maxPages 早停 —— 原先 `onFinished` 一律报 "stopWhen"，
     * 导致「止扫判据是否真的触发」在日志上不可辨（负向用例也会显示 stopWhen）。
     */
    var stopReason: String? = null

    fun snapshot(): Map<String, Any?> = mapOf(
        "total" to total,
        "gridLocked" to gridLocked,
        "crafted" to crafted,
        "rarity" to rarity,
        "locked" to locked,
        "favorited" to favorited,
        "level" to level,
        "stopRequested" to stopRequested,
        "charDupStreak" to charDupStreak,
    )

    /**
     * 表达式变量表 —— **唯一事实源**：`stopWhen` 与 `ifMatch` 都读这一份。
     *
     * ⚠️ 2026-09-18 统一：此前存在**两份** exprVars（`ScanVars` 只给 rarity/level/total，
     * `ScanEngine` 另给一份更全的），于是同一个表达式在 `stopWhen` 与 `ifMatch` 里结果可能不同
     * （例如 `locked`/`curLock` 在 stopWhen 里恒为 0），属"同一实体两处真值"类缺陷。现只留这一份。
     *
     * ★ A16：`Expr.lookup` 对**未知变量名抛 EvalException**（stopWhen/pageSkip/ifMatch 求值处
     *   runCatching 兜底为"按未命中处理"并记 warn），杜绝变量名拼错的静默判据反向；
     *   这张表是判据可见变量的唯一清单，新增引擎变量必须同步加在这里。
     */
    fun exprVars(): Map<String, Any?> = mapOf(
        "total" to (total ?: 0),
        "rarity" to rarity,
        "level" to level,
        "locked" to (locked ?: false),
        "gridLocked" to (gridLocked ?: false),
        "favorited" to (favorited ?: false),
        "crafted" to (crafted ?: false),
        "curLock" to (curLock ?: false),
        // 锁定**意图**（{lock,unlock} 清单归一出的项内 wantLock；缺省 true = 应锁定）
        "wantLock" to (currentTask?.optBoolean(GoodPlan.KEY_WANT_LOCK, true) ?: true),
        // 本条 parsePanel 是否命中当前计划项（parsePanel.match 的求值结果）
        "panelMatched" to panelMatched,
        "stopRequested" to stopRequested,
        "charDupStreak" to charDupStreak,
        // 单趟扫描：剩余未绑定的目标数（flow 用 `stopWhen expr="planRemaining == 0"` 提前收尾）
        "planRemaining" to planRemaining(),
        "planTotal" to (plan?.size ?: 0),
        // 计划派生量（函数式表达式经文本替换后引用；缺项时给 0 即"判据不生效"，方向安全）
        // （ScanVars 内就地算，避免依赖 ScanEngine 的私有方法）
        "targetMaxLevel" to (plan?.mapNotNull { t -> t.optInt("level", -1).takeIf { it >= 0 } }?.maxOrNull() ?: 0),
        "targetMinRarity" to (plan?.mapNotNull { t -> t.optInt("rarity", -1).takeIf { it > 0 } }?.minOrNull() ?: 0),
    )
}
