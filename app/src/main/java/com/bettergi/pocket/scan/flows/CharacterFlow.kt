package com.bettergi.pocket.scan.flows

import android.util.Log
import com.bettergi.pocket.scan.ScanEngine
import com.bettergi.pocket.scan.ScanEngine.Companion.TAG
import com.bettergi.pocket.scan.assertScreen
import com.bettergi.pocket.scan.enterScreen
import com.bettergi.pocket.scan.navigate
import com.bettergi.pocket.scan.pagedGridNative
import com.bettergi.pocket.scan.readConstellation
import com.bettergi.pocket.scan.runVisitBody
import com.bettergi.pocket.scan.stopWhenRegisterDuplicateStreak
import com.bettergi.pocket.scan.visitClick
import com.bettergi.pocket.scan.visitDedupeSkip
import com.bettergi.pocket.scan.visitEmit
import com.bettergi.pocket.scan.visitParsePanel
import org.json.JSONArray
import org.json.JSONObject

/*
 * 流程三：角色两阶段扫描（快扫 + 可疑重扫）（原 flows/character_scan.json 的原生平移）。
 * 步序与参数**逐字**取自 character_scan.json v1.2.0。
 * §0 描述背离说明：原 flow 的 visit 共 **12 步**（click/navigate/assertScreen×2/parsePanel/
 * dedupeSkip/navigate/readConstellation/navigate/assertScreen/stopWhen/emit）——
 * 早先文档写的"Phase 2 可疑重扫"在 Android 侧**不存在**（概览页判据直读，免除逐项天赋/命座点击）。
 */

internal object CharacterFlow {
    /** 网格键（原 pagedGrid.grid）：左侧头像条 1 列网格（自由滚动无吸附，引擎每轮重检槽位）。 */
    const val GRID = "char_strip"

    /** 原 steps[0]：进角色界面（标题条 `<元素>/<角色名>` 含「元素」= 到达判据）。 */
    val ENTER_CHAR: JSONObject = FlowDicts.step("enterScreen")
        .put("target", "char_interface")
        .put("chain", FlowDicts.chainOf("game_home→character(2925,36)"))
        .put("anchor", FlowDicts.ocrAnchor("\$panels.char_profile.header", "元素", 3))

    // ---- visit 步（原 steps[1].visit，按序）----

    /** pipe>0 时 job 携带的 stopWhen 步 = visit 里第一条 stopWhen（dup 登记；角色路径实际不 send job）。 */
    val STOP_DUP: JSONObject = FlowDicts.step("stopWhen")
        .put("mode", "duplicateStreak")
        .put("streak", 3)
        .put("dupPageConfirm", 2)

    /** 原 visit[0]：点头像（弹窗态角色名 OCR 兜底重击）。 */
    val CLICK_CELL: JSONObject = FlowDicts.step("click")
        .put("fast", JSONObject().put("at", "\$cell.center"))
        .put(
            "fallback",
            JSONArray().put(
                JSONObject().put("kind", "ocrAnchor").put("region", "\$panels.char_profile.popupName"),
            ),
        )

    /** 原 visit[1]：复位到「属性」页（上一格可能留在天赋页 ⇒ name ROI 读到天赋名）。 */
    val NAV_ATTRIBUTES: JSONObject = FlowDicts.step("navigate")
        .put("leftMenu", FlowDicts.chainOf("属性"))

    /** 原 visit[2]：页签级确认——属性页独有文本 `提升指南`（首字被裁角时退读 `升指南`）。 */
    val ASSERT_ATTRIBUTES: JSONObject = FlowDicts.step("assertScreen")
        .put("rect", "\$panels.char_profile.bottomBar")
        .put("expect", "提升指南|升指南")
        .put("retries", 2)
        .put("onFail", "continue")

    /** 原 visit[3]：确认仍在角色界面（标题条含「元素」）。 */
    val ASSERT_ELEMENT: JSONObject = FlowDicts.step("assertScreen")
        .put("expect", "元素")
        .put("retries", 2)
        .put("onFail", "continue")

    /** 原 visit[4]：解析角色概览面板（dict 平移：name=mappings.characters, fuzzy=1）。 */
    val PARSE_CHAR: JSONObject = FlowDicts.step("parsePanel")
        .put("panel", "char_profile")
        .put("dict", JSONObject().put("name", "mappings.characters").put("fuzzy", 1))

    /** 原 visit[6]：去命之座页（navigate 内含页确认 readConstellation()）。 */
    val NAV_CONSTELLATION: JSONObject = FlowDicts.step("navigate")
        .put("leftMenu", FlowDicts.chainOf("命之座"))

    /** 原 visit[7]：命座页确认（节点环亮度判据；只读不写 vars——命座数由 panels 判据产出）。 */
    val READ_CONSTELLATION: JSONObject = FlowDicts.step("readConstellation")
        .put("verifyPage", true)
        .put("onFail", "continue")

    /** 原 visit[8]：去天赋页。 */
    val NAV_TALENT: JSONObject = FlowDicts.step("navigate")
        .put("leftMenu", FlowDicts.chainOf("天赋"))

    /** 原 visit[9]：落点确认（两态判据 `演示|中战|战斗天赋` + alts 等级区 `Lv\.`）。 */
    val ASSERT_TALENT: JSONObject = FlowDicts.step("assertScreen")
        .put("rect", "\$panels.char_talent.bottomBar")
        .put("expect", "演示|中战|战斗天赋")
        .put(
            "alts",
            JSONArray().put(
                JSONObject().put("rect", "\$panels.char_talent.lvArea").put("expect", "Lv\\."),
            ),
        )
        .put("retries", 2)
        .put("onFail", "continue")

    /** 原 visit[11]：入库（GoodCharacter 止扫判据在 emitCharacter 求值）。 */
    val EMIT_CHAR: JSONObject = FlowDicts.step("emit").put("as", "GoodCharacter")
}

/**
 * character_scan 原生入口（spec §3.3）。步序 = 原 flow JSON 的 steps 逐条直调：
 * enterScreen(char_interface) → pagedGridNative(char_strip, scrollToTop)。
 * dedupeSkip 语义内联进 visitBody（key==null 不跳过；跳过后命之座/天赋两跳省略，
 * stopWhen/emit 照跑 = TAIL_RUN_ALWAYS 语义由 `if (!visitSkipTail)` 守卫自行保证）。
 */
internal suspend fun ScanEngine.runCharacterScanNative() {
    runPreamble()
    runCharacterSteps()
    runPostamble()
}

/** character_scan 步序主体（ScanEngine.run() 按 flowName 分派用）。 */
internal suspend fun ScanEngine.runCharacterSteps() {
    nativeStep("enterScreen") { enterScreen(CharacterFlow.ENTER_CHAR) }
    nativeStep("pagedGrid") {
        pagedGridNative(CharacterFlow.GRID, scrollToTop = true) { col, row, idx, prof ->
            runVisitBody(CharacterFlow.GRID, col, row, idx, prof, CharacterFlow.EMIT_CHAR, CharacterFlow.STOP_DUP) { ctx ->
                // ① 点头像
                visitClick(CharacterFlow.CLICK_CELL, CharacterFlow.GRID, col, row, idx, ctx, prof)
                // ② navigate 复位属性页（原 visit[1]；leftMenu 非空 ⇒ 无 read 数组兜底）
                navigate(CharacterFlow.NAV_ATTRIBUTES)
                // ③④（2026-10-10 合并进 parseCharacterPanel 同帧读）：属性页/元素判据内联，
                //    原 assertScreen×2 的 2 次 freshFrame+OCR 省去（header 原本就重复读）。
                // ⑤ 解析角色面板（char_profile 分支 = 取新帧）
                visitParsePanel(CharacterFlow.PARSE_CHAR, CharacterFlow.GRID, col, row, idx, ctx, prof)
                // ⑥ dedupeSkip（key==null 不跳过语义照旧）
                visitDedupeSkip(col, row, idx)
                if (!visitSkipTail) {
                    // ⑦ 命之座（navigate 内含页确认）+ ⑧ 命座页确认
                    navigate(CharacterFlow.NAV_CONSTELLATION)
                    readConstellation(CharacterFlow.READ_CONSTELLATION)
                    // ⑨ 天赋页 + ⑩ 落点确认
                    navigate(CharacterFlow.NAV_TALENT)
                    assertScreen(CharacterFlow.ASSERT_TALENT)
                } else {
                    Log.i(
                        TAG,
                        "visit cell($col,$row) idx=$idx 已判重 ⇒ 跳过 命之座/天赋 两跳（stopWhen/emit 照跑）",
                    )
                }
                // ⑪ 止扫登记（判据在 emitCharacter/格级闭环求值）
                stopWhenRegisterDuplicateStreak(3, 2)
                // ⑫ 入库（重复不入库语义在 emitCharacter 内）
                visitEmit(CharacterFlow.EMIT_CHAR)
            }
        }
    }
}
