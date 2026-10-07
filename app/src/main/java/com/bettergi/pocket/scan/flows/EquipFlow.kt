package com.bettergi.pocket.scan.flows

import android.util.Log
import com.bettergi.pocket.scan.ScanEngine
import com.bettergi.pocket.scan.ScanEngine.Companion.TAG
import com.bettergi.pocket.scan.assertScreen
import com.bettergi.pocket.scan.clickChainEntry
import com.bettergi.pocket.scan.clickSlotTab
import com.bettergi.pocket.scan.dialog
import com.bettergi.pocket.scan.dualStateButton
import com.bettergi.pocket.scan.enterScreen
import com.bettergi.pocket.scan.exitStep
import com.bettergi.pocket.scan.forEachPlanItem
import com.bettergi.pocket.scan.navigate
import com.bettergi.pocket.scan.pagedGridNative
import com.bettergi.pocket.scan.rosterFind
import com.bettergi.pocket.scan.runVisitBody
import com.bettergi.pocket.scan.setFilter
import com.bettergi.pocket.scan.stopWhenRegisterDuplicateStreak
import com.bettergi.pocket.scan.visitClick
import com.bettergi.pocket.scan.visitParsePanel
import com.bettergi.pocket.scan.visitStopWhen
import org.json.JSONArray
import org.json.JSONObject

/*
 * 流程五：按装配指令把圣遗物穿到指定角色（原 flows/auto_equip.json 的原生平移）。
 * 步序与参数**逐字**取自 auto_equip.json v1.2.5。
 * 输入 = {equip:[{artifact, location}]}（GOODScanner POST /equip 契约；location 空串 = 卸下）
 * —— 注入通道不变（GoodRepository → startScan(plan)）。
 *
 * 结构：foreach(plan){ 16 个逐目标步 } + exit（每轮一次）。
 * 卸下路径 / 已正确跳过 / #107 点击后复核 全部在 do 原语层（dualStateButton/verifyUnequipped/
 * manageStatusOf），原生循环复用同一实现（P0 对齐项，见 FlowDicts 头注释）。
 */

internal object EquipFlow {
    /** snap 遍历网格（原 pagedGrid.grid；advance.type=snap 由 profile 决定）。 */
    const val SELECT_GRID = "select_3col"

    // ---- 逐目标步的原 JSON 参数（foreach.steps[...]）----

    /** steps[0]：回主界面→角色界面（标题条 `<元素>/<角色名>` 含「元素」= 到达判据）。 */
    val ITEM_ENTER_CHAR: JSONObject = FlowDicts.step("enterScreen")
        .put("target", "char_interface")
        .put("chain", FlowDicts.chainOf("game_home→character(2925,36)"))
        .put("anchor", FlowDicts.ocrAnchor("\$panels.char_profile.header", "元素", 3))

    /** steps[1]：复位到「属性」页（否则 rosterFind 的 name ROI 读到右侧面板）。 */
    val ITEM_NAV_ATTRIBUTES: JSONObject = FlowDicts.step("navigate")
        .put("leftMenu", FlowDicts.chainOf("属性"))

    /** steps[3]：rosterFind（char=$task.char 引用由 rosterFind 解；#125 filter:true 沙漏预筛）。 */
    val ITEM_ROSTER_FIND: JSONObject = FlowDicts.step("rosterFind")
        .put("char", "\$task.char")
        .put("grid", "char_popup")
        .put("filter", true)

    /** steps[7]：确认已到「圣遗物管理界面」（左上是角色名 ⇒ 必须用右上角 `圣遗物推荐`）。 */
    val ITEM_ASSERT_MANAGE: JSONObject = FlowDicts.step("assertScreen")
        .put("title", "\$screens._common.topRightHint")
        .put("expect", "圣遗物推荐")
        .put("retries", 3)
        .put("onFail", "back")

    /** steps[8]：点 artifact.slotKey 对应的槽位页签（P2⑪）。 */
    val ITEM_CLICK_SLOT_TAB: JSONObject = FlowDicts.step("clickSlotTab")

    /** steps[10]：排序钮点开后先确认「排序方式」弹窗真的开了（titleBar 只作 alts 兜底）。 */
    val ITEM_ASSERT_SORT_POPUP: JSONObject = FlowDicts.step("assertScreen")
        .put("expect", "排序方式")
        .put("rect", "\$screens.dialogs.sortPopup.title")
        .put(
            "alts",
            JSONArray().put(
                JSONObject().put("rect", "\$screens._common.titleBar").put("expect", "排序方式"),
            ),
        )
        .put("retries", 3)
        .put("onFail", "back")

    /** steps[12]：药丸直达套装筛选（#136 homeHint：左上是角色名 ⇒ 正向「家」锚）。 */
    val ITEM_SET_FILTER_PILL: JSONObject = FlowDicts.step("setFilter")
        .put("entry", "PILL")
        .put("chain", FlowDicts.chainOf("pill 直达：整条[171,1280,758,1360]"))
        .put(
            "selectByOcr",
            JSONObject().put("grid", "set_filter_popup")
                .put("dict", "mappings.artifactSets")
                .put("match", "exact"),
        )
        .put("homeHint", "圣遗物推荐")

    /** steps[13]：套装筛选链走完后必须回到「圣遗物管理界面」（onFail=abortItem，#136）。 */
    val ITEM_ASSERT_MANAGE_ABORT: JSONObject = FlowDicts.step("assertScreen")
        .put("title", "\$screens._common.topRightHint")
        .put("expect", "圣遗物推荐")
        .put("retries", 3)
        .put("onFail", "abortItem")

    // ---- snap 遍历的 visit 步（原 foreach pagedGrid.visit，按序）----

    /** pipe>0 时 job 携带的 stopWhen 步 = visit 里第一条 stopWhen（dup 登记）。 */
    val STOP_DUP: JSONObject = FlowDicts.step("stopWhen")
        .put("mode", "duplicateStreak")
        .put("streak", 3)
        .put("dupPageConfirm", 2)

    /** visit[1]：snap peek 点击（点击由 snapTraverse 代劳时该步降级 settle + 抓帧）。 */
    val CLICK_CELL: JSONObject = FlowDicts.step("click")
        .put("fast", JSONObject().put("at", "\$cell.center"))

    /** visit[2]：解析管理面板（set_name 可读直采；dict 平移：setName=mappings.artifactSets）。 */
    val PARSE_MANAGE: JSONObject = FlowDicts.step("parsePanel")
        .put("panel", "artifact_manage")
        .put("dict", JSONObject().put("setName", "mappings.artifactSets"))

    /** visit[3]：panelMatch(task, tol=0.1) 止扫（Expr 的 panelMatch 分支保留）。 */
    val STOP_PANEL_MATCH: JSONObject = FlowDicts.step("stopWhen")
        .put("expr", "panelMatch(task, tol=0.1)")

    // ---- foreach 之后的收尾步 ----

    /** steps 尾（foreach 外）：右上角返回/退出 + returnToHome（#132：return 走机读、字面回落）。 */
    val EXIT: JSONObject = FlowDicts.step("exit")
        .put("via", "return[2913,41]")
        .put("per", "char")

    // ---- dualStateButton / dialog 原参数 ----

    /** 左下动作钮（#93 文本判回执；替换/卸下两态语义在 EquipAction）。 */
    val ITEM_DUAL_LEFT_BTN: JSONObject = FlowDicts.step("dualStateButton")
        .put("ref", "\$screens.artifact_manage.leftBtn")

    /** 换装确认弹窗（条件出现；正判据 = `是否更换`）。 */
    val ITEM_DIALOG_EQUIP_CONFIRM: JSONObject = FlowDicts.step("dialog")
        .put("ref", "\$dialogs.equipConfirm")
        .put("onShow", "click confirm(1915,1082)")
        .put("trigger", "仅跨角色替换")
        .put("expect", "是否更换")
}

/**
 * auto_equip 原生入口（spec §3.5）。结构 = 原 flow JSON 的 steps 逐条直调：
 * foreach(plan){ 16 个逐目标步 } → exit。
 * top=2（foreach over $plan as task）、visit 交给 snap 遍历（advance.type=snap）。
 */
internal suspend fun ScanEngine.runEquipScanNative() {
    runPreamble()
    nativeStep("foreach") {
        val items = vars.plan
        if (items.isNullOrEmpty()) {
            Log.w(TAG, "foreach: 'plan' not available or empty, skipped")
        } else {
            forEachPlanItem(
                items = items,
                asName = "task",
                resetPerItem = false,
                steps = equipItemSteps(),
            )
        }
    }
    // 与 run() 的 JSON 步循环同规：整轮已停（exit/watchdog/safety）⇒ exit 不再执行
    if (!vars.stopRequested) {
        nativeStep("exit") { exitStep(EquipFlow.EXIT) }
    }
    runPostamble()
}

/**
 * foreach 的逐目标步序（原 foreach.steps 的 16 步，Kotlin 直排；每步自带与 executeStep
 * 同款的 top:op 埋点）。`_task` 参数 = 当前计划项（vars.currentTask 已由 forEachPlanItem 注入，
 * 各原语按既有路径读取）。
 */
internal fun equipItemSteps(): List<suspend ScanEngine.(JSONObject) -> Unit> = listOf(
    { _ -> nativeStep("enterScreen") { enterScreen(EquipFlow.ITEM_ENTER_CHAR) } },
    { _ -> nativeStep("navigate") { navigate(EquipFlow.ITEM_NAV_ATTRIBUTES) } },
    { _ -> nativeStep("clicks") { clickChainEntry("tian") } },
    { _ -> nativeStep("rosterFind") { rosterFind(EquipFlow.ITEM_ROSTER_FIND) } },
    { _ -> nativeStep("clicks") { clickChainEntry("弹层收起") } },
    { _ -> nativeStep("clicks") { clickChainEntry("圣遗物菜单(453,452)"); clickChainEntry("tihuan(2700,1291)") } },
    { _ -> nativeStep("assertScreen") { assertScreen(EquipFlow.ITEM_ASSERT_MANAGE) } },
    { _ -> nativeStep("clickSlotTab") { clickSlotTab(EquipFlow.ITEM_CLICK_SLOT_TAB) } },
    { _ -> nativeStep("clicks") { clickChainEntry("sortBtn(828,1320)") } },
    { _ -> nativeStep("assertScreen") { assertScreen(EquipFlow.ITEM_ASSERT_SORT_POPUP) } },
    { _ -> nativeStep("clicks") { clickChainEntry("sortPopupConfirm(655,1335)") } },
    { _ -> nativeStep("setFilter") { setFilter(EquipFlow.ITEM_SET_FILTER_PILL) } },
    { _ -> nativeStep("assertScreen") { assertScreen(EquipFlow.ITEM_ASSERT_MANAGE_ABORT) } },
    { _ -> nativeStep("pagedGrid") { equipSelectGrid() } },
    { _ -> nativeStep("dualStateButton") { dualStateButton(EquipFlow.ITEM_DUAL_LEFT_BTN) } },
    { _ -> nativeStep("dialog") { dialog(EquipFlow.ITEM_DIALOG_EQUIP_CONFIRM) } },
)

/** select_3col snap 遍历（原 pagedGrid 步：mode=snap、end=peek 消失指纹 + 补点遮挡槽）。 */
private suspend fun ScanEngine.equipSelectGrid() {
    pagedGridNative(EquipFlow.SELECT_GRID) { col, row, idx, prof ->
        runVisitBody(EquipFlow.SELECT_GRID, col, row, idx, prof, pipeEmit = null, pipeStop = EquipFlow.STOP_DUP) { ctx ->
            // ① 止扫登记
            stopWhenRegisterDuplicateStreak(3, 2)
            // ② snap peek 点击（snap 模式：点击由 snapTraverse 代劳，此步 settle + 抓帧）
            visitClick(EquipFlow.CLICK_CELL, EquipFlow.SELECT_GRID, col, row, idx, ctx, prof, snapMode = true)
            // ③ 解析管理面板
            visitParsePanel(EquipFlow.PARSE_MANAGE, EquipFlow.SELECT_GRID, col, row, idx, ctx, prof)
            // ④ panelMatch(task, tol=0.1) 止扫（#93：命中置 matchHit）
            visitStopWhen(EquipFlow.STOP_PANEL_MATCH)
        }
    }
}
