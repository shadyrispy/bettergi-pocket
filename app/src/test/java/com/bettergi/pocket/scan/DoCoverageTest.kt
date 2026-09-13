package com.bettergi.pocket.scan

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * flow do 原语覆盖守门：遍历 5 个 flow JSON 所有 "do" 字段，
 * 断言 ⊆ ScanEngine 已实现集合——防止 flow 引用未实现原语被静默 skip（unknown step 只 log warn）。
 * 新增原语时同步更新 IMPLEMENTED_DOES。
 */
class DoCoverageTest {

    private fun flowsDir(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val candidate = File(dir, "src/main/assets/dsl/flows")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("dsl flows dir not found")
    }

    /** 顶层（executeStep）支持集。 */
    private fun topLevelDoes() = setOf(
        "enterScreen", "clicks", "dualStateButton", "filterReset", "assertScreen", "readConstellation", "readCount", "pagedGrid",
        "dialog", "ocrWithRetry", "navigate", "foreach", "setFilter",
        "rosterFind", "exit", "verify", "emit",
    )

    /** visit 内（executeVisitStep）支持集。 */
    private fun visitDoes() = setOf(
        "ifMatch", "vote", "click", "parsePanel", "navigate",
        "dialog", "verify", "assertScreen", "readConstellation", "emit", "stopWhen",
    )

    private fun implementedDoes() = topLevelDoes() + visitDoes()

    /**
     * 层级感知收集：`pagedGrid.visit` / `ifMatch.then` 下的 do 归 visit 层，
     * `foreach.steps` 回到顶层，其余归顶层。
     * ⚠️ 旧版把两集合合并成一个（层级盲区）——navigate 曾只挂顶层分发，
     * 在 visit 里静默落 unknown-step 而守门仍绿。现按层级分别校验。
     */
    private fun collectDoes(node: Any?, inVisit: Boolean, top: MutableSet<String>, visit: MutableSet<String>) {
        when (node) {
            is JSONObject -> {
                for (key in node.keys()) {
                    val v = node.get(key)
                    if (key == "do" && v is String) {
                        if (inVisit) visit.add(v) else top.add(v)
                    }
                    when (key) {
                        "visit" -> collectDoes(v, true, top, visit)      // pagedGrid.visit
                        "then" -> collectDoes(v, inVisit, top, visit)    // ifMatch.then 继承当前层
                        "steps" -> collectDoes(v, false, top, visit)     // foreach.steps 回顶层
                        else -> collectDoes(v, inVisit, top, visit)
                    }
                }
            }
            is org.json.JSONArray -> for (i in 0 until node.length()) {
                collectDoes(node.get(i), inVisit, top, visit)
            }
        }
    }

    @Test
    fun `all flow do primitives are implemented`() {
        val flows = flowsDir().listFiles { f -> f.extension == "json" } ?: error("no flows")
        assertTrue("no flow files found", flows.isNotEmpty())
        val missingTop = mutableMapOf<String, Set<String>>()
        val missingVisit = mutableMapOf<String, Set<String>>()
        for (f in flows) {
            val top = mutableSetOf<String>()
            val visit = mutableSetOf<String>()
            collectDoes(JSONObject(f.readText()), false, top, visit)
            (top - topLevelDoes()).takeIf { it.isNotEmpty() }?.let { missingTop[f.name] = it }
            (visit - visitDoes()).takeIf { it.isNotEmpty() }?.let { missingVisit[f.name] = it }
        }
        assertTrue(
            "flows reference unimplemented TOP-LEVEL do primitives: $missingTop (implement them or extend ScanEngine)",
            missingTop.isEmpty(),
        )
        assertTrue(
            "flows reference unimplemented VISIT-LEVEL do primitives: $missingVisit " +
                "(visit 层与顶层分发集合不同——曾因层级盲区让 navigate 静默失效)",
            missingVisit.isEmpty(),
        )
    }
}
