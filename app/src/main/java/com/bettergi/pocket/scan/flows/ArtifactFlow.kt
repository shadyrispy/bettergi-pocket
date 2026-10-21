package com.bettergi.pocket.scan.flows

import com.bettergi.pocket.scan.ScanAbortedException
import com.bettergi.pocket.scan.ScanEngine
import com.bettergi.pocket.scan.assertScreen
import com.bettergi.pocket.scan.dualStateButton
import com.bettergi.pocket.scan.enterScreen
import com.bettergi.pocket.scan.filterReset
import com.bettergi.pocket.scan.pagedGridNative
import com.bettergi.pocket.scan.readCount
import com.bettergi.pocket.scan.runVisitBody
import com.bettergi.pocket.scan.setFilter
import com.bettergi.pocket.scan.stopWhenRegisterDuplicateStreak
import com.bettergi.pocket.scan.visitClick
import com.bettergi.pocket.scan.visitEmit
import com.bettergi.pocket.scan.visitParsePanel
import com.bettergi.pocket.scan.vote
import org.json.JSONArray
import org.json.JSONObject

/*
 * 流程二：圣遗物背包全量扫描 → GoodArtifact（原 flows/artifact_scan.json 的原生平移）。
 * 步序与参数**逐字**取自 artifact_scan.json v1.0.0。
 */

internal object ArtifactFlow {
    /** 网格键（原 pagedGrid.grid）。 */
    const val GRID = "artifact_backpack"

    // ---- 顶层步（原 steps[0..5]）----

    /** 原 steps[0]：入口链第一段——开背包 + 标题条确认「背包」。 */
    val ENTER_BAGPACK: JSONObject = FlowDicts.step("enterScreen")
        .put("target", "artifact_backpack")
        .put("chain", FlowDicts.chainOf("game_home→bagpack(2786,36)"))
        .put("anchor", FlowDicts.ocrAnchor("\$screens._common.titleBar", "背包", 3))

    /** 原 steps[1]：入口链第二段——补一击圣遗物页签 + 标题条确认「圣遗物」。 */
    val ENTER_ARTIFACT_TAB: JSONObject = FlowDicts.step("enterScreen")
        .put("target", "artifact_backpack")
        .put("chain", FlowDicts.chainOf("artifact_tab"))
        .put("anchor", FlowDicts.ocrAnchor("\$screens._common.titleBar", "圣遗物", 3))

    /** 原 steps[2]：五星 pill 幂等复位到 OFF（ON 时漏斗/排序被游戏禁用）。 */
    val DUAL_PILL_OFF: JSONObject = FlowDicts.step("dualStateButton")
        .put("ref", "\$screens.artifact_backpack.fiveStarToggle")
        .put("ensure", "off")

    /** 原 steps[3]：带校验的筛选复位（「圣遗物筛选」是唯一可靠判据）。 */
    val FILTER_RESET: JSONObject = FlowDicts.step("filterReset")
        .put("open", "\$screens.artifact_backpack.filterRoundBtn")
        .put("title", "\$screens.dialogs.filterPanel.anchorTitle")
        .put("titleExpect", "圣遗物筛选")
        .put("reset", "\$screens.dialogs.filterPanel.reset")
        .put("ok", "\$screens.dialogs.filterPanel.ok")
        .put("retries", 2)

    /** 原 steps[4]：筛选复位后确认已回到圣遗物背包（误开面板时按 BACK 清掉）。 */
    val ASSERT_ARTIFACT: JSONObject = FlowDicts.step("assertScreen")
        .put("expect", "圣遗物")
        .put("retries", 3)
        .put("onFail", "back")

    /** 原 steps[5]：右上总数 OCR（0 件时重进界面重试）。 */
    val READ_COUNT: JSONObject = FlowDicts.step("readCount")
        .put("rect", "\$screens.artifact_backpack.count")
        .put("as", "total")
        .put("onZero", "reopenAndRetry")

    // ---- visit 步（原 steps[6].visit，按序）----

    /** pipe>0 时 job 携带的 stopWhen 步 = visit 里第一条 stopWhen（dup 登记，幂等）。 */
    val STOP_DUP: JSONObject = FlowDicts.step("stopWhen")
        .put("mode", "duplicateStreak")
        .put("streak", 3)
        .put("dupPageConfirm", 1)

    /** 原 visit[1]：vote 卡片锁徽（点击前）。 */
    val VOTE_GRID_LOCKED: JSONObject = FlowDicts.step("vote")
        .put("zone", "artifact.card.lockBadge")
        .put("as", "gridLocked")

    /** 原 visit[2]：点格（面板名 OCR 兜底重击）。 */
    val CLICK_CELL: JSONObject = FlowDicts.step("click")
        .put("fast", JSONObject().put("at", "\$cell.center"))
        .put(
            "fallback",
            JSONArray().put(
                JSONObject().put("kind", "ocrAnchor").put("region", "\$panel.artifact_backpack.name"),
            ),
        )

    /** 原 visit[3]：vote 祝圣紫（craft 判据）。 */
    val VOTE_ZHUSHENG: JSONObject = FlowDicts.step("vote")
        .put("zone", "artifact.panel.zhusheng")
        .put("as", "crafted")

    /** 原 visit[4]：vote 星级 banner。 */
    val VOTE_RARITY: JSONObject = FlowDicts.step("vote")
        .put("zone", "artifact.rarity")
        .put("as", "rarity")

    /** 原 visit[5]：vote 面板锁态（双区判定）。 */
    val VOTE_PANEL_LOCK: JSONObject = FlowDicts.step("vote")
        .put("zone", "artifact.panel.lock")
        .put("as", "locked")

    /** 原 visit[6]：vote 收藏（astral，随祝圣 yShift）。 */
    val VOTE_ASTRAL: JSONObject = FlowDicts.step("vote")
        .put("zone", "artifact.panel.astral")
        .put("as", "favorited")

    /** 原 visit[7]：解析圣遗物面板（dict 平移：name=mappings.artifactPieces, subStats=rollTable）。 */
    val PARSE_PANEL: JSONObject = FlowDicts.step("parsePanel")
        .put("panel", "artifact_backpack")
        .put(
            "dict",
            JSONObject().put("name", "mappings.artifactPieces").put("subStats", "rollTable"),
        )

    /** 原 visit[8]：入库/上报（GoodArtifact 实体在 parseArtifactPanel 组装）。 */
    val EMIT: JSONObject = FlowDicts.step("emit").put("as", "GoodArtifact")
}

/**
 * artifact_scan 原生入口（spec §3.2）。步序 = 原 flow JSON 的 steps 逐条直调。
 * @param pageSkipExpr 页级 pageSkip 表达式（原 pagedGrid.pageSkip.expr；如
 *   `"pageMinLevel > max(plan.level)"`——core 内做 targetMaxLevel/targetMinRarity 文本替换；
 *   artifact_scan 原生（全量扫描）不声明，注入点供干跑/调试用）。
 * @param setFilterStep 非空 ⇒ 在 filterReset 之后先按该 step 应用套装筛选（`artifact_scan_set`）。
 */
internal suspend fun ScanEngine.runArtifactScanNative(
    pageSkipExpr: String? = null,
    setFilterStep: JSONObject? = null,
) {
    runPreamble()
    runArtifactSteps(pageSkipExpr, setFilterStep)
    runPostamble()
}

/** artifact_scan 步序主体（foreach 包装——resetScanPerItem 语义——与干跑夹具复用）。 */
internal suspend fun ScanEngine.runArtifactSteps(
    pageSkipExpr: String? = null,
    setFilterStep: JSONObject? = null,
) {
    nativeStep("enterScreen") { enterScreen(ArtifactFlow.ENTER_BAGPACK) }
    nativeStep("enterScreen") { enterScreen(ArtifactFlow.ENTER_ARTIFACT_TAB) }
    nativeStep("dualStateButton") { dualStateButton(ArtifactFlow.DUAL_PILL_OFF) }
    nativeStep("filterReset") { filterReset(ArtifactFlow.FILTER_RESET) }
    // ★ 2026-10-14：套装筛选后扫描（artifact_scan_set）。筛选**未生效**时**中止整轮**，
    //   绝不放行成全量扫描——否则"筛选后扫描"就静默退化成了全量扫（既慢又违背意图）。
    //   位置与 LockFlow 同构：filterReset → setFilter → assertScreen（确认已回到背包）。
    if (setFilterStep != null) {
        var applied = false
        nativeStep("setFilter") { applied = setFilter(setFilterStep) }
        if (!applied) {
            throw ScanAbortedException(
                "artifact_scan_set: 套装筛选未生效（not_applied）⇒ 中止，不退化全量扫描",
            )
        }
    }
    nativeStep("assertScreen") { assertScreen(ArtifactFlow.ASSERT_ARTIFACT) }
    // ★ 2026-10-14：**筛选流程不读表头总数**。右上「圣遗物 974/2700」是**全量持有量**，
    //   不随筛选变化（截图直证），而筛选后的网格只有本套装的 N 件 ⇒ 两者不是同一量纲。
    //   读进来会让三处 total 判据全部误判：①`#234` 页 0 格数上界（拿 974 当本页格数）；
    //   ②件数主判据 `collected >= total`（22 恒追不上 974，永不收尾）；③`run()` 的
    //   「总数不符」告警（stopName 分支强制核对 ⇒ 筛选下**恒**报 974≠22 的假阳性）。
    //   三处都以 `vars.total == null` 为「不启用」语义 ⇒ 这里不读即整体正确关闭。
    if (setFilterStep == null) {
        nativeStep("readCount") { readCount(ArtifactFlow.READ_COUNT) }
    }
    nativeStep("pagedGrid") {
        // ★ #219：dup 参数在**进 pagedGrid 之前**登记一次。判据（页级确认 dupPageConfirm）在
        //   格循环里就被读，而流程体内的那次登记要到第 0 格 visit 才生效 ⇒ 从页 0 起算的值
        //   才是流程真正声明的那个（流程体内保留同值登记，由守卫静默跳过）。
        stopWhenRegisterDuplicateStreak(3, 1)
        // ★ #312（2026-10-18 BS 全量实测）：**必须 scrollToTop** —— 背包列表跨会话保留滚动
        //   位置，而 artifact_scan 又没有武器/锁定流程那种「排序钮两次点击回顶」。实测：上轮
        //   停在 page43 后重跑，本次从**第 43 页位置**起扫 → 撞到「祝圣油膏」素材止扫，
        //   扫 727/1002 漏 280（漏的正是排在前面的高等级件）。character_scan 早就传了 true。
        pagedGridNative(ArtifactFlow.GRID, scrollToTop = true, pageSkipExpr = pageSkipExpr) { col, row, idx, prof ->
            runVisitBody(ArtifactFlow.GRID, col, row, idx, prof, ArtifactFlow.EMIT, ArtifactFlow.STOP_DUP) { ctx ->
                // ① 止扫登记（留在生产者；§12.1）
                stopWhenRegisterDuplicateStreak(3, 1)
                // ② vote 卡片锁徽（点击前）
                vote(ArtifactFlow.VOTE_GRID_LOCKED, ArtifactFlow.GRID, col, row, ctx, prof)
                // ③ 点格
                visitClick(ArtifactFlow.CLICK_CELL, ArtifactFlow.GRID, col, row, idx, ctx, prof)
                // ④⑤⑥⑦ 面板四判据（祝圣/星级/锁态/收藏）
                vote(ArtifactFlow.VOTE_ZHUSHENG, ArtifactFlow.GRID, col, row, ctx, prof)
                vote(ArtifactFlow.VOTE_RARITY, ArtifactFlow.GRID, col, row, ctx, prof)
                vote(ArtifactFlow.VOTE_PANEL_LOCK, ArtifactFlow.GRID, col, row, ctx, prof)
                vote(ArtifactFlow.VOTE_ASTRAL, ArtifactFlow.GRID, col, row, ctx, prof)
                // ⑧ 解析面板
                visitParsePanel(ArtifactFlow.PARSE_PANEL, ArtifactFlow.GRID, col, row, idx, ctx, prof)
                // ⑨ 入库/上报
                visitEmit(ArtifactFlow.EMIT)
            }
        }
    }
}
