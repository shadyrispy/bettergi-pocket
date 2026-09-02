package com.bettergi.pocket.scan

import org.json.JSONObject
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
        "tools/artifactSetPieces.json",
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
    fun `set dictionary has pieceToSetId entries`() {
        val dict = JSONObject(File(assetsDir(), "tools/artifactSetPieces.json").readText())
        val map = dict.getJSONObject("pieceToSetId")
        assertTrue("pieceToSetId should have 276 entries", map.length() >= 270)
    }
}
