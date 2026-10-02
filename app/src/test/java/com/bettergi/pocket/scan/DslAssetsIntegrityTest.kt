package com.bettergi.pocket.scan

import org.json.JSONObject
import org.junit.Assert.assertEquals
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
        assertTrue("artifactPieces should have 306 entries", pieces.length() >= 300)
        assertTrue(
            "artifactPieces 每条须带 setId",
            (0 until pieces.length()).all { pieces.getJSONObject(it).has("setId") },
        )
    }

    /**
     * 3★/4★ 低星套单件名必须可反查 —— 2026-09-24 全量对账的实证缺口。
     *
     * 词典曾只覆盖 GOOD mappings 的 56 套（4★/5★），尾部 9 件 3★（冒险家 / 祭火礼冠）面板 OCR
     * 全对、只因为 `setKey not found` 就被"读失败"判据丢弃 ⇒ 缺口必须被单测钉住，
     * 否则下次再少一套仍然是静默丢件。
     */
    @Test
    fun `low rarity artifact piece names resolve to a set`() {
        val dict = JSONObject(File(assetsDir(), "tools/good_names.json").readText())
        val pieces = dict.getJSONArray("artifactPieces")
        val byPiece = HashMap<String, String>()
        for (i in 0 until pieces.length()) {
            val o = pieces.getJSONObject(i)
            byPiece[o.getString("piece")] = o.getString("setId")
        }
        val expect = mapOf(
            "冒险家之花" to "Adventurer",
            "冒险家金杯" to "Adventurer",
            "冒险家怀表" to "Adventurer",
            "祭火礼冠" to "PrayersForIllumination",
            "祭雷礼冠" to "PrayersForWisdom",
            "游医的银莲" to "TravelingDoctor",
            "奇迹耳坠" to "TinyMiracle",
            "幸运儿沙漏" to "LuckyDog",
        )
        for ((piece, setId) in expect) {
            assertEquals("低星单件名反查套装（$piece）", setId, byPiece[piece])
        }
    }

    /**
     * canonical（`dsl/json` + `dsl/tools`）与运行时（`assets/dsl`）**必须逐字节一致**
     * —— #47/#54（2026-09-25）把这条从"记得跑 check_dsl_sync.py"变成构建期守门。
     *
     * 为什么必须钉住：2560 档所有真机验证过的修复（cols 7→6、landingBand.x1 1840→1600、
     * `filterPanel.anchorTitle` 重标 + 去掉 `|圣遗物` 宽备选、zhusheng.points、clickBand、
     * labelAnchor）**只落在 assets**，canonical 落后了一整个 09-23~09-25。而
     * `check_dsl_sync.py --fix` 的方向是 canonical→assets ⇒ 谁顺手跑一次 --fix，就会
     * ① 把幻影第 7 列放回来（多插重复件）、② 让落地条带重新压到右侧详情面板（测量必不匹配）、
     * ③ 让筛选面板被判"已打开"而误点重置/确认 ⇒ 弹出全屏模态、后续点击与翻页全失效。
     * 三者都是**跑起来才看得见**的回归，而跑一轮要 10 分钟。
     *
     * 单独构建 app 模块（仓库里没有 dsl/ 目录）时本用例记为 **skipped**（不是 passed）——
     * 恒真的"通过"正是本项目反复踩的假象。
     */
    @Test
    fun `dsl canonical and shipped assets stay byte identical`() {
        var up = File(System.getProperty("user.dir") ?: ".")
        var canonJson: File? = null
        repeat(5) {
            val c = File(up, "dsl/json")
            if (File(c, "profiles.json").isFile) canonJson = c else up = up.parentFile ?: up
        }
        org.junit.Assume.assumeTrue("仓库内未找到 dsl/json ⇒ 本用例不适用（跳过，非通过）", canonJson != null)
        val json = canonJson!!
        val assets = assetsDir()
        val pairs = listOf(
            "profiles.json" to "profiles.json",
            "profiles_2560x1440.json" to "profiles_2560x1440.json",
            "profiles_2244x1080.json" to "profiles_2244x1080.json",
            "primitives.schema.json" to "primitives.schema.json",
        )
        val drifted = ArrayList<String>()
        for ((c, a) in pairs) {
            val lhs = File(json, c)
            val rhs = File(assets, a)
            if (lhs.readBytes().contentEquals(rhs.readBytes()).not()) drifted += "json/$c ≠ assets/$a"
        }
        for (rel in listOf("good_names.json", "rollTable.json", "char_talent_bonus.json")) {
            val lhs = File(json.parentFile, "tools/$rel")
            val rhs = File(assets, "tools/$rel")
            if (lhs.isFile && rhs.isFile && lhs.readBytes().contentEquals(rhs.readBytes()).not()) {
                drifted += "tools/$rel ≠ assets/tools/$rel"
            }
        }
        val cf = File(json, "flows"); val af = File(assets, "flows")
        if (cf.isDirectory && af.isDirectory) {
            for (f in cf.listFiles()?.filter { it.name.endsWith(".json") }.orEmpty()) {
                val g = File(af, f.name)
                if (!g.isFile || f.readBytes().contentEquals(g.readBytes()).not()) {
                    drifted += "flows/${f.name} ≠ assets/flows/${f.name}"
                }
            }
        }
        org.junit.Assert.assertTrue(
            "DSL 双侧漂移（跑 dsl/scripts/check_dsl_sync.py 看方向；本次已验证的修复在 assets 侧时用 --fix-rev）：\n  " +
                drifted.joinToString("\n  "),
            drifted.isEmpty(),
        )
    }
}
