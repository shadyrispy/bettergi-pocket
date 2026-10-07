package com.bettergi.pocket.scan.flows

import com.bettergi.pocket.log.RecognitionLog
import com.bettergi.pocket.scan.PerfProbe
import com.bettergi.pocket.scan.ScanEngine
import org.json.JSONArray
import org.json.JSONObject

/*
 * simple 分支（2026-10-07）：五流程原生化 —— 原 flow JSON 的 `dict` / `vars` 块与共享助手。
 * 每条流程一个文件（WeaponFlow.kt / ArtifactFlow.kt / …），本文件只放跨流程共享的常量与工具。
 * 步骤常量一律用 JSONObject 字面量**逐字**平移原 flow params（spec §3「优先照旧构造 JSONObject，
 * 最小 diff」）—— 原生层只是不再从 flow 文件读。
 *
 * ══ P0/P1 对齐项登记（spec §3.6，gs-flow-step-diff §0.1）══
 *
 * 【P0 · 随转换保留（实现就在 do 原语层，原生流程直调同一实现，语义零改动）】
 * 1. 缺席证明门禁（emit 处 NotFound/Skipped 分流）：
 *    `ScanControlDomain.foreach` 的状态映射 —— abortItem ⇒ Failed、整轮停 ⇒ Skipped、
 *    网格到底未命中 ⇒ NotFound（不是 Skipped），出处 ManageStatus / emitManageSummary。
 *    证据：ManageStatusTest（含 flowStop/abortItem 分流用例）。
 * 2. 副词条重试链（solver 不可解→重解→兜底）：
 *    `RollSolver.solve` —— 原值 → level+10 候选重解 → 待激活两变体 → 全不可解时**原样导出**
 *    （宁缺不错，`ArtifactDomainPanels.parseArtifactPanel` 消费）。证据：RollSolverTest
 *    （聚合钉 8 + ambiguous init 单件用例）。
 * 3. 筛选三态（部分命中处理）：
 *    `ScanControlDomain.setFilter` —— 全命中 ⇒ 确认筛选；部分命中 ⇒ 确认带筛扫 + 逐格
 *    planMatch 兜；零命中/面板未开 ⇒ not_applied 放弃筛选扫全量（绝不盲点）。
 *    证据：GoodPlanTest（whyNot/pick 契约）+ FilterPageConsistencyTest。
 *
 * 【P1 · follow-up（转换期刻意不加，防归因混淆；待办按序）】
 * - 锁图标歧义带（2560 档 lock badge 与祝圣 yShift 的重叠带判读）
 * - 套装分组（导出/管理页按 set 分组展示）
 * - 页签交替（weapon/artifact 双页签连续跑时的入口交替幂等）
 * - skip_open（enterScreen 幂等跳过的显式声明位）
 * - 名称匹配护栏（NameOverrides 撞名告警阈值化）
 */

/** 原生顶层步的埋点壳：与 ScanEngine.executeStep 逐位同语义（RecognitionLog「步骤」+ PerfProbe top:op）。 */
internal suspend fun ScanEngine.nativeStep(op: String, block: suspend () -> Unit) {
    RecognitionLog.log(logTag, RecognitionLog.Level.I, "步骤 $op")
    val t0 = System.nanoTime()
    try {
        block()
    } finally {
        PerfProbe.addStep("top:$op", System.nanoTime() - t0)
    }
}

/** 共享 JSON 字面量帮助器（链项数组 / ocr 锚点 / do 骨架）。 */
internal object FlowDicts {
    /** 原流程 `chain` 数组。 */
    fun chainOf(vararg entries: String): JSONArray = JSONArray().apply { entries.forEach { put(it) } }

    /** 原流程 `anchor: {kind: ocr, rect, expect, retries}`。 */
    fun ocrAnchor(rect: String, expect: String, retries: Int): JSONObject = JSONObject()
        .put("kind", "ocr")
        .put("rect", rect)
        .put("expect", expect)
        .put("retries", retries)

    /** visit/顶层步的 `do` 骨架。 */
    fun step(doOp: String): JSONObject = JSONObject().put("do", doOp)
}
