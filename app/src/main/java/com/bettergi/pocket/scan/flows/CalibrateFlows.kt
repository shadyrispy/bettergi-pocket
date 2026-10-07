package com.bettergi.pocket.scan.flows

import com.bettergi.pocket.scan.ScanEngine
import com.bettergi.pocket.scan.enterScreen
import com.bettergi.pocket.scan.pagedGridNative
import com.bettergi.pocket.scan.runVisitBody
import com.bettergi.pocket.scan.setFilter
import com.bettergi.pocket.scan.visitEmit
import org.json.JSONObject

/*
 * 两条**标定流**（原 flows/char_calibrate.json / setfilter_calibrate.json 的原生平移）。
 * 标定工具链用（不进 simple 的运行时判断）；steps 与参数逐字平移。
 */

internal object CalibrateFlows {
    /** char_calibrate 的 visit（唯一一步：emit as=noop —— pagedGrid 的 settle 标定只看遍历与日志）。 */
    val EMIT_NOOP: JSONObject = FlowDicts.step("emit").put("as", "noop")

    /** setfilter_calibrate 的入口（一条 enterScreen 带两段链 + count 数字锚）。 */
    val SETFILTER_ENTER: JSONObject = FlowDicts.step("enterScreen")
        .put("target", "artifact_backpack")
        .put("chain", FlowDicts.chainOf("game_home→bagpack", "artifact_tab"))
        .put(
            "anchor",
            FlowDicts.ocrAnchor("\$screens.artifact_backpack.count.rect", "\\d+\\s*/\\s*\\d+", 3),
        )

    /** setfilter_calibrate 的套装筛选（BACKPACK 链 + selectByOcr；plan 目标强制翻页）。 */
    val SETFILTER_STEP: JSONObject = FlowDicts.step("setFilter")
        .put("entry", "BACKPACK")
        .put("chain", FlowDicts.chainOf("filterRoundBtn(300,1002)", "filterPanel.setPlus(450,255)"))
        .put(
            "selectByOcr",
            JSONObject().put("grid", "set_filter_popup")
                .put("dict", "mappings.artifactSets")
                .put("match", "exact"),
        )
}

/**
 * char_calibrate 原生入口：临时标定 char_popup settle——当前已在角色属性页时直接翻页，不测导航。
 * 原 flow 显式 `returnHome: false` ⇒ 不做前置归位。
 */
internal suspend fun ScanEngine.runCharCalibrateNative() {
    runPreamble(returnHomeOverride = false)
    nativeStep("pagedGrid") {
        pagedGridNative("char_popup") { col, row, idx, prof ->
            runVisitBody("char_popup", col, row, idx, prof) { _ctx ->
                visitEmit(CalibrateFlows.EMIT_NOOP)
            }
        }
    }
    runPostamble()
}

/**
 * setfilter_calibrate 原生入口：经 setFilter 打开套装筛选并注入 plan 目标强制翻页
 * （settle 日志由 awaitGridStable 打出）。原 flow 未声明 returnHome ⇒ 真机启动路径默认 true。
 */
internal suspend fun ScanEngine.runSetFilterCalibrateNative() {
    runPreamble(returnHomeOverride = true)
    nativeStep("enterScreen") { enterScreen(CalibrateFlows.SETFILTER_ENTER) }
    nativeStep("setFilter") { setFilter(CalibrateFlows.SETFILTER_STEP) }
    runPostamble()
}
