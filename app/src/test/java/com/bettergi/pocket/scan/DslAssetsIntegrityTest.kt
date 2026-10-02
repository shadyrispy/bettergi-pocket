package com.bettergi.pocket.scan

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DSL 资产完整性守门（防复发：2026-09-02 曾因 assets/dsl 文件静默丢失
 * 导致 APK 缺 profiles.json、真机报 error: dsl/profiles.json）。
 *
 * testDebugUnitTest 挂在 assembleDebug 前，文件缺失/损坏时构建直接红。
 */
class DslAssetsIntegrityTest {

    private fun assetsDir(): File {
        // AGP unit test 工作目录 = module 目录（app/）；防御性向上查找
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val candidate = File(dir, "src/main/assets/dsl")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("src/main/assets/dsl not found from ${System.getProperty("user.dir")}")
    }

    private fun requiredFiles() = listOf(
        "profiles.json",
        "flows/artifact_scan.json",
        // 单一名称词典（由 dsl/scripts/gen_good_names.py 生成）：GoodNames 运行时唯一依赖。
        // fix45 教训：文件放在 app/assets/（无 src/main 前缀）不进 APK → 词典全部 unavailable。
        "tools/good_names.json",
    )

    /** 运行时只吃上面那一份：mappings.json / artifactSetPieces.json 仅为 dsl/ 侧的生成源，不再拷进 app。 */
    private fun retiredFiles() = listOf(
        "tools/artifactSetPieces.json",
        "tools/mappings.json",
    )

    @Test
    fun `dsl assets exist and parse as json`() {
        val base = assetsDir()
        for (rel in requiredFiles()) {
            val f = File(base, rel)
            assertTrue("missing dsl asset: $rel (was it silently deleted again?)", f.isFile)
            assertTrue("empty dsl asset: $rel", f.length() > 2)
            // JSON 语法可解析
            JSONObject(f.readText())
        }
    }

    @Test
    fun `profiles json contains scan-required keys`() {
        val root = JSONObject(File(assetsDir(), "profiles.json").readText())
        for (path in listOf(
            "screens", "grids", "panels", "zones",
            "screens.artifact_backpack",
            "screens.game_home.anchors.bagpack",
            "grids.artifact_backpack",
            "panels.artifact_backpack",
        )) {
            var node: Any? = root
            for (seg in path.split('.')) {
                node = (node as? JSONObject)?.opt(seg)
                assertTrue("profiles.json missing '$path'", node != null)
            }
        }
    }

    @Test
    fun `flow json references supported profile`() {
        val flow = JSONObject(File(assetsDir(), "flows/artifact_scan.json").readText())
        assertTrue(flow.getString("profile") == "android_3200x1440")
        assertTrue(flow.getJSONArray("steps").length() > 0)
    }

    @Test
    fun `single name dictionary is the only tools asset`() {
        val base = assetsDir()
        for (rel in retiredFiles()) {
            assertFalse(
                "$rel 不应再出现在 app assets（运行时已统一为 good_names.json；dsl/ 侧仍保留为生成源）",
                File(base, rel).exists(),
            )
        }
    }

    @Test
    fun `name dictionary has artifact pieces entries`() {
        val dict = JSONObject(File(assetsDir(), "tools/good_names.json").readText())
        val pieces = dict.getJSONArray("artifactPieces")
        assertTrue("artifactPieces should have 276 entries", pieces.length() >= 270)
        assertTrue(
            "artifactPieces 每条须带 setId",
            (0 until pieces.length()).all { pieces.getJSONObject(it).has("setId") },
        )
    }
}
