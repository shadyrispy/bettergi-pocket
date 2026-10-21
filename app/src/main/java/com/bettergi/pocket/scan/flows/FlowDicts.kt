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
 * ══ P0/P1 对齐项登记（spec §3.6，gs-flow-step-diff §0.1；2026-10-08 复核修订）══
 *
 * 【P0 · 按流程分列——「已实现」只对 equip 成立，lock/scan 侧是缺口】
 * 1. 缺席证明门禁（NotFound/Skipped 分流）：
 *    - equip（foreach）：✅ 已实现 —— `manageStatusOf` 五态（flowStop⇒Skipped、
 *      abortItem⇒Failed、网格到底未命中⇒NotFound），证据 ManageStatusTest。
 *    - lock（planMatch/planDone）：❌ 未实现 —— `planSummary()` 把从未 planDone 的项
 *      无条件补 NotFound（ScanJudgmentDomain），没有 flowStop/abort 证据分流；
 *      修复见 LockFlow/planSummary（2026-10-08，随 P0-A 落地）。
 * 2. 副词条重试链（solver 不可解→重解→兜底）：
 *    - solver **内**兜底：✅ level+10 候选 → 待激活两变体 → 全不可解原样导出（宁缺不错），
 *      证据 RollSolverTest。
 *    - solver **外**图像级重试（上游 9 组 (dy,dw)×双引擎 + -10/-20px 重裁 + 启发式兜底）：
 *      ❌ 未实现 —— 先以 logMiss 率观测（scan finished 摘要），有数据再定（spec §3.6）。
 * 3. 筛选三态（部分命中处理）：
 *    - `setFilter` 现状：全命中⇒确认；**部分命中⇒确认带筛扫（缺口，GS 上游是清筛改扫全量）**；
 *      零命中⇒not_applied 放弃筛选扫全量。修复见 setFilter（2026-10-08，随 P0-C 落地）。
 *
 * 【P1 · follow-up（转换期刻意不加，防归因混淆；待办按序）】
 * - 锁图标歧义带（2560 档 lock badge 与祝圣 yShift 的重叠带判读）
 * - 套装分组（导出/管理页按 set 分组展示）
 * - 页签交替（weapon/artifact 双页签连续跑时的入口交替幂等）
 * - skip_open（enterScreen 幂等跳过的显式声明位）
 * - 名称匹配护栏（`dist < key.length` 禁单字全替换；CONFUSIONS 替换表已实现）
 * - character_scan Phase 2（可疑重扫；描述背离已在 CharacterFlow 头注释如实标注）
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
