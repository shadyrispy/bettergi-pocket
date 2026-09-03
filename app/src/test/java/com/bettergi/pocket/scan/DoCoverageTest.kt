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

    private fun implementedDoes() = setOf(
        // executeStep 顶层
        "enterScreen", "dualStateButton", "readCount", "pagedGrid",
        "dialog", "ocrWithRetry", "navigate", "foreach", "setFilter",
        "exit", "verify", "emit",
        // runVisit visit 内
        "vote", "click", "parsePanel", "stopWhen", "ifMatch",
    )

    private fun collectDoes(node: Any?, acc: MutableSet<String>) {
        when (node) {
            is JSONObject -> {
                for (key in node.keys()) {
                    val v = node.get(key)
                    if (key == "do" && v is String) acc.add(v)
                    collectDoes(v, acc)
                }
            }
            is org.json.JSONArray -> for (i in 0 until node.length()) collectDoes(node.get(i), acc)
        }
    }

    @Test
    fun `all flow do primitives are implemented`() {
        val implemented = implementedDoes()
        val flows = flowsDir().listFiles { f -> f.extension == "json" } ?: error("no flows")
        assertTrue("no flow files found", flows.isNotEmpty())
        val missing = mutableMapOf<String, Set<String>>()
        for (f in flows) {
            val acc = mutableSetOf<String>()
            collectDoes(JSONObject(f.readText()), acc)
            val unknown = acc - implemented
            if (unknown.isNotEmpty()) missing[f.name] = unknown
        }
        assertTrue(
            "flows reference unimplemented do primitives: $missing (implement them or extend ScanEngine)",
            missing.isEmpty(),
        )
    }
}
