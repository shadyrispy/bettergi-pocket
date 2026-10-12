package com.bettergi.pocket.scan.flows

import android.util.Log
import com.bettergi.pocket.scan.GoodPlan
import com.bettergi.pocket.scan.ScanEngine
import kotlinx.coroutines.CancellationException
import com.bettergi.pocket.scan.ScanEngine.Companion.TAG
import com.bettergi.pocket.scan.assertScreen
import com.bettergi.pocket.scan.clickChainEntry
import com.bettergi.pocket.scan.dualStateButton
import com.bettergi.pocket.scan.enterScreen
import com.bettergi.pocket.scan.filterReset
import com.bettergi.pocket.scan.pagedGridNative
import com.bettergi.pocket.scan.planDone
import com.bettergi.pocket.scan.planMatch
import com.bettergi.pocket.scan.planSummary
import com.bettergi.pocket.scan.readCount
import com.bettergi.pocket.scan.runVisitBody
import com.bettergi.pocket.scan.setFilter
import com.bettergi.pocket.scan.stopWhenRegisterDuplicateStreak
import com.bettergi.pocket.scan.verify
import com.bettergi.pocket.scan.visitClick
import com.bettergi.pocket.scan.visitParsePanel
import com.bettergi.pocket.scan.visitStopWhen
import com.bettergi.pocket.scan.vote
import org.json.JSONObject

/*
 * 流程四：按外部意图清单批量加/解锁（原 flows/artifact_lock.json 的原生平移）。
 * 步序与参数**逐字**取自 artifact_lock.json v1.0.0。
 * 输入 = {lock:[…], unlock:[…]} 两个 GoodArtifact 清单（GOODScanner POST /manage 契约；
 * 由 GoodPlan 归一成项内 wantLock）—— 注入通道不变（GoodRepository → startScan(plan)）。
 *
 * spec §3.4：`ifMatch.when` 表达式改 Kotlin 直判（Expr 引擎对该条退役）：
 * - 外层 `when="panelMatched"` → `vars.panelMatched`（= parsePanel.match 的求值结果）；
 * - 内层 `when="curLock != wantLock"` → `(curLock ?: false) != wantLock`（与 ScanVars.exprVars
 *   的 Boolean→0/1 映射逐位一致）。
 * 原 JSON pagedGrid 的 `stopWhenPageEnd` 键在引擎里**从无消费者**（死配置），无需平移。
 */

internal object LockFlow {
    /** 网格键（原 pagedGrid.grid）。 */
    const val GRID = "artifact_backpack"

    /** 原 pagedGrid.pageSkip.expr：末格 OCR 等级 → 全页跳过（GOODScanner fast-mode 同源）。 */
    const val PAGE_SKIP_EXPR = "pageMinLevel > max(plan.level)"

    // ---- 顶层步（原 steps[0..5]）----

    val ENTER_BAGPACK: JSONObject = FlowDicts.step("enterScreen")
        .put("target", "artifact_backpack")
        .put("chain", FlowDicts.chainOf("game_home→bagpack"))
        .put("anchor", FlowDicts.ocrAnchor("\$screens._common.titleBar", "背包", 3))

    val ENTER_ARTIFACT_TAB: JSONObject = FlowDicts.step("enterScreen")
        .put("target", "artifact_backpack")
        .put("chain", FlowDicts.chainOf("artifact_tab"))
        .put("anchor", FlowDicts.ocrAnchor("\$screens._common.titleBar", "圣遗物", 3))

    val DUAL_PILL_OFF: JSONObject = FlowDicts.step("dualStateButton")
        .put("ref", "\$screens.artifact_backpack.fiveStarToggle")
        .put("ensure", "off")

    val FILTER_RESET: JSONObject = FlowDicts.step("filterReset")
        .put("open", "\$screens.artifact_backpack.filterRoundBtn")
        .put("title", "\$screens.dialogs.filterPanel.anchorTitle")
        .put("titleExpect", "圣遗物筛选")
        .put("reset", "\$screens.dialogs.filterPanel.reset")
        .put("ok", "\$screens.dialogs.filterPanel.ok")
        .put("retries", 2)

    /** 原 steps[5]：套装筛选链走完后必须确认已回到背包（onFail=back 逐层退出子面板）。 */
    val ASSERT_BACKPACK: JSONObject = FlowDicts.step("assertScreen")
        .put("rect", "\$screens._common.titleBar")
        .put("expect", "背包")
        .put("retries", 3)
        .put("onFail", "back")

    /** 原 steps[6] 前置：套装筛选（selectByOcr 与 artifact_scan 同构；entry=BACKPACK 走筛选面板）。 */
    val SET_FILTER: JSONObject = FlowDicts.step("setFilter")
        .put("entry", "BACKPACK")
        .put("chain", FlowDicts.chainOf("filterRoundBtn(357,1294)", "filterPanel.setPlus(600,340)"))
        .put(
            "selectByOcr",
            JSONObject().put("grid", "set_filter_popup")
                .put("dict", "mappings.artifactSets")
                .put("match", "exact"),
        )

    // ---- visit 步（原 steps[6].visit，按序）----

    /** pipe>0 时 job 携带的 stopWhen 步 = visit 里第一条 stopWhen（dup 登记）。 */
    val STOP_DUP: JSONObject = FlowDicts.step("stopWhen")
        .put("mode", "duplicateStreak")
        .put("streak", 3)
        .put("dupPageConfirm", 2)

    /** 原 visit[1]：目标全部绑定完 ⇒ 提前收尾（单趟扫描）。 */
    val STOP_PLAN_REMAINING: JSONObject = FlowDicts.step("stopWhen")
        .put("expr", "planRemaining == 0")

    /** 原 visit[2]：点格（无 fallback）。 */
    val CLICK_CELL: JSONObject = FlowDicts.step("click")
        .put("fast", JSONObject().put("at", "\$cell.center"))

    /** 原 visit[3]：解析背包面板（无 dict —— match 已移除，命中判定归 planMatch）。 */
    val PARSE_PANEL: JSONObject = FlowDicts.step("parsePanel").put("panel", "artifact_backpack")

    /** 原 visit[4]：单趟扫描核心——本格与未绑定计划项比对（tol 平移）。 */
    val PLAN_MATCH: JSONObject = FlowDicts.step("planMatch").put("tol", 0.100001)

    /** 内层 then[0]：vote 面板锁态快照 as=curLock。 */
    val VOTE_CUR_LOCK: JSONObject = FlowDicts.step("vote")
        .put("zone", "artifact.panel.lock")
        .put("as", "curLock")

    /** 内层 then[1]：面板 lock 钮（$zones.artifact.panel.lock → lockClickAdaptive 机读路径）。 */
    val CLICK_LOCK: JSONObject = FlowDicts.step("click")
        .put("fast", JSONObject().put("at", "\$zones.artifact.panel.lock"))

    /** 内层 then[2]：翻转断言（期望与 curLock 快照取反）。 */
    val VERIFY_TOGGLE: JSONObject = FlowDicts.step("verify")
        .put("zone", "artifact.panel.lock")
        .put("expect", "toggle(\$curLock)")

    /** 内层 then[3]：动作已 verify 通过 ⇒ 记 Success（"真的改动了"的唯一出口）。 */
    val PLAN_DONE_SUCCESS: JSONObject = FlowDicts.step("planDone").put("status", "Success")

    /** 外层 then 尾：命中但判据未触发点击 ⇒ AlreadyCorrect（已绑定则 no-op）。 */
    val PLAN_DONE_ALREADY: JSONObject = FlowDicts.step("planDone").put("status", "AlreadyCorrect")
}

/**
 * artifact_lock 原生入口（spec §3.4）。步序 = 原 flow JSON 的 steps 逐条直调：
 * enterScreen×2 → dualStateButton → filterReset → setFilter → assertScreen
 * → clicks(排序钮+确认，#255 按钮回顶) → pagedGridNative(pageSkip, visitBody) → planSummary。
 */
internal suspend fun ScanEngine.runLockScanNative() {
    runPreamble()
    runLockSteps()
    runPostamble()
}

/** artifact_lock 步序主体（ScanEngine.run() 按 flowName 分派用；planSummary 在本体内）。 */
internal suspend fun ScanEngine.runLockSteps() {
    try {
        runLockStepsInner()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // ★ P0-A 缺席证明门禁：流程**异常中止**（enterScreen/assertScreen 未达、profile 缺失、
        //   zone 缺失等）⇒ 未绑定项在 planSummary 记 Skipped（这一轮不可信，需重跑），
        //   而不是谎报 NotFound（"这件不存在"，会误导用户重造方案）。
        //   正常止扫（stopWhen/maxPages/网格到底）不经过这里 ⇒ 缺席仍是 NotFound。
        //   补跑 planSummary（异常已跳过本体内的那次）后**重抛**——ScriptRunner 的
        //   exportNow("aborted") 导出与 error 回执语义不变，ManageSummary 汇总不缺席。
        vars.lockPlanAborted = true
        planSummary()
        throw e
    }
}

private suspend fun ScanEngine.runLockStepsInner() {
    nativeStep("enterScreen") { enterScreen(LockFlow.ENTER_BAGPACK) }
    nativeStep("enterScreen") { enterScreen(LockFlow.ENTER_ARTIFACT_TAB) }
    nativeStep("dualStateButton") { dualStateButton(LockFlow.DUAL_PILL_OFF) }
    nativeStep("filterReset") { filterReset(LockFlow.FILTER_RESET) }
    nativeStep("setFilter") { setFilter(LockFlow.SET_FILTER) }
    nativeStep("assertScreen") { assertScreen(LockFlow.ASSERT_BACKPACK) }
    // ★ #255 按钮回顶：点「1≡」开排序弹窗 → 点确认 ⇒ 游戏重排并把列表拉回顶部（BS 2244 现场验：
    //   无选中项时确认不改排序规则，只回顶）。换掉的是 pagedGrid 的拖拽式 `scrollToTop` ——
    //   那条判据是「连续两轮画面没变」，而 #144 实测它会**假阳性**：一次没生效的手势（被吞 / 帧源没更新）
    //   就把整册判成"已在顶部"，之后从**未知行号**起步 ⇒ 不是慢，是静默漏件。
    //   ⚠️ 3200 档没量过这个钮（无截图）⇒ 链项无字面坐标 ⇒ 打 W 跳过，那一档等于没回顶。
    nativeStep("clicks") {
        clickChainEntry("artifactSort")
        clickChainEntry("sortPopupConfirm")
    }
    nativeStep("pagedGrid") {
        pagedGridNative(LockFlow.GRID, pageSkipExpr = LockFlow.PAGE_SKIP_EXPR) { col, row, idx, prof ->
            runVisitBody(LockFlow.GRID, col, row, idx, prof, pipeEmit = null, pipeStop = LockFlow.STOP_DUP) { ctx ->
                // ① 止扫登记（阈值只作 snap/rosterFind 兜底；pagedGrid 内被引擎覆盖）
                stopWhenRegisterDuplicateStreak(3, 2)
                // ② planRemaining == 0 ⇒ 提前收尾（Expr 路径保留：planRemaining 在 exprVars）
                visitStopWhen(LockFlow.STOP_PLAN_REMAINING)
                // ③ 点格
                visitClick(LockFlow.CLICK_CELL, LockFlow.GRID, col, row, idx, ctx, prof)
                // ④ 解析面板
                visitParsePanel(LockFlow.PARSE_PANEL, LockFlow.GRID, col, row, idx, ctx, prof)
                // ⑤ planMatch：本格绑定未命中过的计划项
                planMatch(LockFlow.PLAN_MATCH)
                // ⑥ ifMatch（Kotlin 直判，见文件头注释）
                if (vars.currentTask == null) {
                    Log.i(TAG, "ifMatch: no plan injected (vars.currentTask null), then skipped")
                } else {
                    val whenPass = vars.panelMatched
                    if (!whenPass) {
                        Log.d(TAG, "ifMatch when='panelMatched' false, then skipped")
                    } else {
                        // then[0]：vote 面板锁态（as=curLock 快照）
                        vote(LockFlow.VOTE_CUR_LOCK, LockFlow.GRID, col, row, ctx, prof)
                        // 内层 ifMatch when="curLock != wantLock"（Kotlin 直判）
                        val wantLock = vars.currentTask?.optBoolean(GoodPlan.KEY_WANT_LOCK, true) ?: true
                        if ((vars.curLock ?: false) != wantLock) {
                            visitClick(LockFlow.CLICK_LOCK, LockFlow.GRID, col, row, idx, ctx, prof)
                            if (!vars.stopRequested) {
                                verify(LockFlow.VERIFY_TOGGLE)
                                planDone(LockFlow.PLAN_DONE_SUCCESS)
                            }
                        }
                        // then 尾：AlreadyCorrect（内层动作过则因"已绑定"no-op）
                        planDone(LockFlow.PLAN_DONE_ALREADY)
                    }
                }
            }
        }
    }
    // ★ 收尾：始终没被绑定的计划项补记 NotFound，并输出 ManageSummary
    //   （planSummary 是结果契约的出口，stopRequested 也不能跳过——与 run() 的 JSON 循环同规）
    nativeStep("planSummary") { planSummary() }
}
