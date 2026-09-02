package com.bettergi.pocket.scan

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 流程级离线一致性校验：用真实 assets 文件静态走查 ScanEngine 的全部 profile 路径引用，
 * 让「profile path ... is not a rect array」这类问题在构建期红掉，而不是真机跑一半炸。
 *
 * 背景：2026-09-02 真机两连炸（dsl/profiles.json 缺包 → tab/count 是 rect-object 而非数组），
 * 用户要求按流程完整自检——本类即该自检的固化。
 */
class ProfileFlowConsistencyTest {

    private fun assetsDir(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val candidate = File(dir, "src/main/assets/dsl")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("src/main/assets/dsl not found from ${System.getProperty("user.dir")}")
    }

    private fun profile(): ScreenProfile {
        val p = ScreenProfile(JSONObject(File(assetsDir(), "profiles.json").readText()))
        p.calibrate(3200, 1440)
        return p
    }

    private fun flow(): JSONObject = JSONObject(File(assetsDir(), "flows/artifact_scan.json").readText())

    private fun assertTrue(msg: String, cond: Boolean) = org.junit.Assert.assertTrue(msg, cond)

    // ---- ScanEngine.CHAIN_ANCHOR_PATHS（enterScreen 入口链）----
    @Test
    fun `enterScreen chain anchors resolve to rects`() {
        val p = profile()
        for (path in listOf(
            "screens.game_home.anchors.bagpack",
            "screens.game_home.anchors.character",
            "screens.artifact_backpack.tab",
        )) {
            val r = p.rect(path) // rect-object 自动下钻
            assertTrue("$path has non-positive size", r.width > 0 && r.height > 0)
        }
    }

    // ---- dualStateButton ref（五星开关）----
    @Test
    fun `fiveStarToggle pill exists`() {
        val p = profile()
        val obj = p.rawObject("screens.artifact_backpack.fiveStarToggle")
        assertTrue("fiveStarToggle missing", obj != null)
        // profiles 现行形态：pill 为纯 rect 数组 [x0,y0,x1,y1]（兼容 {rect:[...]} 对象形态）
        val pill = obj!!.opt("pill")
        val arr = when (pill) {
            is JSONArray -> pill
            is JSONObject -> pill.optJSONArray("rect")
            else -> null
        }
        assertTrue("fiveStarToggle.pill missing or malformed", arr != null && arr!!.length() == 4)
    }

    // ---- readCount / flow 引用路径：遍历全部 flow，递归收集 "$..." 引用并 resolve ----
    @Test
    fun `all dollar-ref paths in all flows resolve in profile`() {
        val p = profile()
        val flowsDir = File(assetsDir(), "flows")
        val flowFiles = flowsDir.listFiles { f -> f.extension == "json" }.orEmpty().toList()
        assertTrue("no flow json files under assets/dsl/flows", flowFiles.size >= 5)
        val missing = LinkedHashMap<String, String>()
        for (f in flowFiles) {
            val refs = LinkedHashSet<String>()
            collectRefs(JSONObject(f.readText()), refs)
            for (ref in refs) {
                // 运行时符号（非 profile 路径）：$cell.* 当前格中心、$plan 执行计划、纯变量 $locked/$rarity 等
                val isProfilePath = ref.split('.').firstOrNull() in setOf(
                    "screens", "grids", "panels", "zones", "dialogs", "panel", "assets",
                )
                if (!isProfilePath) continue
                val resolved = flowAliasToProfile(ref)
                if (!p.hasPath(resolved)) missing["${f.name}:$ref"] = resolved
            }
        }
        assertTrue("flow references missing in profiles.json: $missing", missing.isEmpty())
    }

    /**
     * flow 引用归一（与 ScreenProfile.normalizeFlowRef 同源）：
     * - flow 写 "panel"，profiles 顶层是 "panels"
     * - flow 写 "dialogs"，profiles 挂在 "screens.dialogs"
     */
    private fun flowAliasToProfile(path: String): String = profile().normalizeFlowRef(path)

    // ---- 每份 flow 的 profile 字段对齐 ----
    @Test
    fun `every flow targets the canonical profile`() {
        val flowsDir = File(assetsDir(), "flows")
        for (f in flowsDir.listFiles { x -> x.extension == "json" }.orEmpty()) {
            val flow = JSONObject(f.readText())
            assertTrue("${f.name} missing 'profile'", flow.optString("profile") == "android_3200x1440")
            assertTrue("${f.name} has no steps", flow.optJSONArray("steps")?.length() ?: 0 > 0)
        }
    }

    private fun collectRefs(node: Any?, out: LinkedHashSet<String>) {
        when (node) {
            is JSONObject -> for (k in node.keys()) collectRefs(node.opt(k), out)
            is JSONArray -> for (i in 0 until node.length()) collectRefs(node.opt(i), out)
            is String -> if (node.startsWith("$")) out += node.removePrefix("$")
            else -> Unit
        }
    }

    // ---- ScanEngine 固定引用路径（vote zones / parsePanel fields / pagedGrid）----
    @Test
    fun `scan engine hardcoded profile paths resolve`() {
        val p = profile()
        // zones（vote 原语）
        for (key in listOf(
            "artifact.card.lockBadge",
            "artifact.panel.zhusheng",
            "artifact.panel.lock",
            "artifact.panel.astral",
            "artifact.rarity",
        )) {
            val z = p.zone(key)
            assertTrue("zone '$key' missing in profiles.json", z != null)
        }
        assertTrue(p.zone("artifact.panel.lock")!!.has("rect"))
        assertTrue(p.zone("artifact.panel.astral")!!.has("rect"))
        assertTrue(p.zone("artifact.panel.zhusheng")!!.has("points"))
        assertTrue(p.zone("artifact.rarity")!!.has("banner"))
        assertTrue(p.zone("artifact.card.lockBadge")!!.has("rel"))

        // parsePanel 面板字段
        for (path in listOf(
            "panels.artifact_backpack.name",
            "panels.artifact_backpack.slot",
            "panels.artifact_backpack.mainName",
            "panels.artifact_backpack.mainValue",
            "panels.artifact_backpack.level",
        )) {
            p.rect(path)
        }
        val panel = p.rawObject("panels.artifact_backpack")
        assertTrue(panel!!.has("subStats") && panel.getJSONArray("subStats").length() >= 4)
        assertTrue(panel.optJSONObject("starBand") != null)

        // pagedGrid 网格参数
        for (field in listOf("cardOrigin", "cardSize", "pitch", "cols", "visibleRows", "traverseRows", "advance")) {
            assertTrue("grids.artifact_backpack.$field missing", p.rawObject("grids.artifact_backpack")!!.has(field))
        }
        val advance = p.rawObject("grids.artifact_backpack")!!.getJSONObject("advance")
        assertTrue(advance.getJSONArray("from").length() == 2 && advance.getJSONArray("to").length() == 2)

        // cell 坐标计算（21 格全部可达）
        for (i in 0 until 21) {
            val c = p.cellCenter("artifact_backpack", i)
            assertTrue(c.x > 0 && c.y > 0)
        }
        // 卡内 rel rect（lockBadge zone）
        val rel = intArrayOf(8, 6, 48, 46)
        p.cardRelRect("artifact_backpack", rel, col = 3, row = 2)
    }

    // ---- 祝圣 yShift 路径走查（crafted=true 时 shifted rect 不越界）----
    @Test
    fun `zhusheng yshift rects stay in frame bounds`() {
        val p = profile()
        val yShift = Math.round(63.0 * p.scaleY).toInt()
        // level 是单 rect；subStats 是嵌套数组，逐行取
        assertTrue(p.rect("panels.artifact_backpack.level").bottom + yShift <= ScreenProfile.BASE_HEIGHT)
        val subStats = p.rawObject("panels.artifact_backpack")!!.getJSONArray("subStats")
        for (i in 0 until subStats.length()) {
            val r = subStats.getJSONArray(i)
            assertTrue(r.getInt(3) + 63 <= ScreenProfile.BASE_HEIGHT)
        }
        val band = p.rawObject("panels.artifact_backpack")!!.optJSONObject("starBand")!!
        val y1 = band.getJSONArray("y").getInt(1)
        assertTrue(y1 + 63 <= ScreenProfile.BASE_HEIGHT)
    }
}
