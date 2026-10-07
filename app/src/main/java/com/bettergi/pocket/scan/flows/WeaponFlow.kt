package com.bettergi.pocket.scan.flows

import com.bettergi.pocket.scan.ScanEngine
import com.bettergi.pocket.scan.clickChainEntry
import com.bettergi.pocket.scan.enterScreen
import com.bettergi.pocket.scan.pagedGridNative
import com.bettergi.pocket.scan.readCount
import com.bettergi.pocket.scan.runVisitBody
import com.bettergi.pocket.scan.stopWhenRegisterDuplicateStreak
import com.bettergi.pocket.scan.stopWhenRarityAtMost2
import com.bettergi.pocket.scan.visitClick
import com.bettergi.pocket.scan.visitEmit
import com.bettergi.pocket.scan.visitParsePanel
import com.bettergi.pocket.scan.vote
import org.json.JSONObject

/*
 * 流程一：武器背包全量扫描 → GoodWeapon (GOOD v3)（原 flows/weapon_scan.json 的原生平移）。
 * 步序与参数**逐字**取自 weapon_scan.json v1.1.0（注释里的实测依据随原 JSON 留档，此处不重复）。
 */

internal object WeaponFlow {
    /** 网格键（原 pagedGrid.grid）。 */
    const val GRID = "weapon_backpack"

    // ---- 顶层步（原 steps[0..3]）----

    /** 原 steps[0]：入口链第一段——只开背包并确认标题条含「背包」。 */
    val ENTER_BAGPACK: JSONObject = FlowDicts.step("enterScreen")
        .put("target", "weapon_backpack")
        .put("chain", FlowDicts.chainOf("game_home→bagpack(2786,36)"))
        .put("anchor", FlowDicts.ocrAnchor("\$screens._common.titleBar", "背包", 3))

    /** 原 steps[1]：入口链第二段——切武器页签后确认标题条含「武器」（「武器」才是武器页独有判据）。 */
    val ENTER_WEAPON_TAB: JSONObject = FlowDicts.step("enterScreen")
        .put("target", "weapon_backpack")
        .put("chain", FlowDicts.chainOf("weapon_tab(210,196,290,321)"))
        .put("anchor", FlowDicts.ocrAnchor("\$screens._common.titleBar", "武器", 3))

    /** 排序两连击的链项（原 steps[2] clicks.chain；走 profile 键 screens.weapon_backpack.sort）。 */
    const val SORT_CHAIN_ENTRY = "weaponSort"

    /** 原 steps[3]：右上总数 OCR（0 件时重进界面重试）。 */
    val READ_COUNT: JSONObject = FlowDicts.step("readCount")
        .put("rect", "\$screens.weapon_backpack.count")
        .put("as", "total")
        .put("onZero", "reopenAndRetry")

    // ---- visit 步（原 steps[4].visit，按序）----

    /** 原 visit[0]：回卷止扫登记（判据在 emitCharacter/格级闭环求值）。 */
    const val STOP_DUP_STREAK = 3
    const val STOP_DUP_PAGE_CONFIRM = 1

    /** pipe>0 时 job 携带的 stopWhen 步 = visit 里**第一条** stopWhen（与 JSON 路径 firstOrNull 同源）。 */
    val STOP_DUP: JSONObject = FlowDicts.step("stopWhen")
        .put("mode", "duplicateStreak")
        .put("streak", STOP_DUP_STREAK)
        .put("dupPageConfirm", STOP_DUP_PAGE_CONFIRM)

    /** 原 visit[2]：vote 卡片锁徽（点击前读——toggle 判定输入）。 */
    val VOTE_LOCKED: JSONObject = FlowDicts.step("vote")
        .put("zone", "weapon.card.lockBadge")
        .put("as", "locked")

    /** 原 visit[3]：点格（面板名 OCR 兜底重击）。 */
    val CLICK_CELL: JSONObject = FlowDicts.step("click")
        .put("fast", JSONObject().put("at", "\$cell.center"))
        .put(
            "fallback",
            org.json.JSONArray().put(
                JSONObject().put("kind", "ocrAnchor").put("region", "\$panel.weapon_backpack.name"),
            ),
        )

    /** 原 visit[4]：vote 详情面板星行（必须排在 click 之后——面板显示的是选中件）。 */
    val VOTE_RARITY: JSONObject = FlowDicts.step("vote")
        .put("zone", "weapon.panel.starBand")
        .put("as", "rarity")

    /** 原 visit[5]：解析武器面板（dict 块平移：name=mappings.weapons, fuzzy=1）。 */
    val PARSE_PANEL: JSONObject = FlowDicts.step("parsePanel")
        .put("panel", "weapon_backpack")
        .put(
            "dict",
            JSONObject().put("name", "mappings.weapons").put("fuzzy", 1),
        )

    /** 原 visit[6]：入库/上报（GoodWeapon 实体在 parseWeaponPanel 组装，emit 步只上报快照）。 */
    val EMIT: JSONObject = FlowDicts.step("emit")
        .put("as", "GoodWeapon")
        .put("compose", JSONObject().put("rarity", "\$rarity").put("locked", "\$locked"))
}

/**
 * weapon_scan 原生入口（spec §3.1/§7）。步序 = 原 flow JSON 的 steps 逐条直调：
 * enterScreen×2 → clicks(weaponSort×2) → readCount → pagedGridNative(visitBody)。
 * maxPages 沿用引擎构造参数（ScriptRunner/DEBUG_SCAN_FLOW 与干跑夹具都从构造传入）。
 */
internal suspend fun ScanEngine.runWeaponScanNative() {
    runPreamble()
    nativeStep("enterScreen") { enterScreen(WeaponFlow.ENTER_BAGPACK) }
    nativeStep("enterScreen") { enterScreen(WeaponFlow.ENTER_WEAPON_TAB) }
    nativeStep("clicks") {
        clickChainEntry(WeaponFlow.SORT_CHAIN_ENTRY)
        clickChainEntry(WeaponFlow.SORT_CHAIN_ENTRY)
    }
    nativeStep("readCount") { readCount(WeaponFlow.READ_COUNT) }
    nativeStep("pagedGrid") {
        pagedGridNative(WeaponFlow.GRID) { col, row, idx, prof ->
            runVisitBody(WeaponFlow.GRID, col, row, idx, prof, WeaponFlow.EMIT, WeaponFlow.STOP_DUP) { ctx ->
                // ① 止扫登记（留在生产者；§12.1：登记是纯 vars 写，无帧依赖）
                stopWhenRegisterDuplicateStreak(WeaponFlow.STOP_DUP_STREAK, WeaponFlow.STOP_DUP_PAGE_CONFIRM)
                // ② 低星尾部守卫（expr "rarity <= 2" 的原生式，Expr 引擎对该条退役）
                stopWhenRarityAtMost2()
                // ③ vote 卡片锁徽（点击前）
                vote(WeaponFlow.VOTE_LOCKED, WeaponFlow.GRID, col, row, ctx, prof)
                // ④ 点格（fallback=ocrAnchor panel.weapon_backpack.name）
                visitClick(WeaponFlow.CLICK_CELL, WeaponFlow.GRID, col, row, idx, ctx, prof)
                // ⑤ vote 面板星行（点击后）
                vote(WeaponFlow.VOTE_RARITY, WeaponFlow.GRID, col, row, ctx, prof)
                // ⑥ 解析面板（含 S1 origin 裁片路径，与 JSON 路径同一实现）
                visitParsePanel(WeaponFlow.PARSE_PANEL, WeaponFlow.GRID, col, row, idx, ctx, prof)
                // ⑦ 入库/上报
                visitEmit(WeaponFlow.EMIT)
            }
        }
    }
    runPostamble()
}
