package com.bettergi.pocket.scan

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * 流程级离线一致性校验：用真实 profile 静态走查 flow 的全部 `$` 引用与引擎固定路径，
 * 让「profile path ... is not a rect array」这类问题在构建期红掉，而不是真机跑一半炸。
 * 背景：2026-09-02 真机两连炸（dsl/profiles.json 缺包 → tab/count 是 rect-object 而非数组），
 * 本类即该自检的固化。
 *
 * ★ #223 重做（本文件曾随 DSL 运行时下线一并删除，按审计 §三-2 以非恒真形态恢复）：
 *   ① `$ref` 校验**不再调用被测生产函数** `ScreenProfile.normalizeFlowRef` —— 旧写法用生产代码自己的
 *      归一函数再去查 profile，「归一写错 / 生产根本没调用它」与测试无关地恒真通过（#222 的 dialog
 *      断链就是这么漏掉的）；现在由本文件**手写词汇表**（`panel.*`/`dialogs.*` 两个别名）+ 逐级走
 *      原始 JSON 键独立解析，任何查不到的原样报出。
 *   ② flow JSON 已随 DSL 运行时下线（`assets/dsl/flows` 7 条已删）⇒ 本类读 canonical
 *      `dsl/json/flows`（与 DupPageConfirmFlowTest 同源；仓库里找不到该目录时 skip，不是 pass）。
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

    private fun profileJson(): JSONObject = JSONObject(File(assetsDir(), "profiles.json").readText())

    private fun profile(): ScreenProfile {
        val p = ScreenProfile(profileJson())
        p.calibrate(3200, 1440)
        return p
    }

    /** canonical flow 目录（`dsl/json/flows`）；单独构建 app 模块（仓库里没有 dsl/）时返回 null ⇒ 调用方 skip。 */
    private fun flowsDirOrNull(): File? {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(5) {
            val candidate = File(dir, "dsl/json/flows")
            if (File(candidate, "artifact_scan.json").isFile) return candidate
            dir = dir.parentFile ?: return null
        }
        return null
    }

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

    // ---- assertScreen 的通用标题条（2026-09-13）----
    @Test
    fun `screen confirm ROIs resolve to rects`() {
        val p = profile()
        for (path in listOf("screens._common.titleBar", "screens._common.topRightHint")) {
            val r = p.rect(path)
            assertTrue("$path has non-positive size", r.width > 0 && r.height > 0)
        }
        // 标题条必须够宽以覆盖实测包络（2244 实测 x[120,643] y[19,97] → 3200 基坐标换算约 x[171,917] y[25,129]）
        val tb = p.rect("screens._common.titleBar")
        assertTrue("titleBar 太窄: ${tb.width}", tb.width >= 800)
        assertTrue("titleBar 太矮: ${tb.height}", tb.height >= 100)
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

    // ---- flow 引用路径：遍历全部 flow，递归收集 "$..." 引用并**独立**解析 ----
    @Test
    fun `all dollar-ref paths in all flows resolve in profile`() {
        val flowsDir = flowsDirOrNull()
        Assume.assumeTrue("仓库内未找到 dsl/json/flows ⇒ 本用例不适用（跳过，非通过）", flowsDir != null)
        val root = profileJson()
        val flowFiles = flowsDir!!.listFiles { f -> f.extension == "json" }.orEmpty().toList()
        assertTrue("no flow json files under dsl/json/flows", flowFiles.size >= 5)
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
                if (resolveIndependently(root, ref) == null) {
                    missing["${f.name}:$ref"] = normalizedRef(ref)
                }
            }
        }
        assertTrue("flow references missing in profiles.json: $missing", missing.isEmpty())
    }

    /**
     * **独立解析**（#223）：不调用生产归一函数 —— 本文件自持 flow 词汇表并逐级走原始 JSON 键。
     *
     * 手写词汇表就是本测试对「flow 词汇 ↔ profile 结构」的期望本身（与生产实现各自独立）：
     * - `panel.*`   → `panels.*`（flow 单数 / profiles 复数）
     * - `dialogs.*` → `screens.dialogs.*`（profiles 把弹窗对象挂在 screens 下）
     * 表外路径按字面走；`zones.*` 顶层键自带点号，按最长前缀整键命中。
     *
     * @return 命中的节点；查不到返回 null（调用方原样报出该 ref）。
     */
    private fun resolveIndependently(root: JSONObject, rawRef: String): Any? {
        val ref = normalizedRef(rawRef)
        if (ref.startsWith("zones.")) {
            val zones = root.optJSONObject("zones") ?: return null
            val rest = ref.removePrefix("zones.")
            var best: String? = null
            val keys = zones.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (rest == k || rest.startsWith("$k.")) {
                    val cur = best
                    if (cur == null || k.length > cur.length) best = k
                }
            }
            val key = best ?: return null
            var node: Any? = zones.opt(key)
            val tail = rest.removePrefix(key).removePrefix(".")
            if (tail.isEmpty()) return node
            for (seg in tail.split('.')) {
                node = (node as? JSONObject)?.opt(seg) ?: return null
            }
            return node
        }
        var node: Any? = root
        for (seg in ref.split('.')) {
            node = (node as? JSONObject)?.opt(seg) ?: return null
        }
        return node
    }

    /** 词汇表归一（**本测试自持**，区别于生产的 `ScreenProfile.normalizeFlowRef`）。 */
    private fun normalizedRef(rawRef: String): String = when {
        rawRef.startsWith("panel.") -> "panels." + rawRef.removePrefix("panel.")
        rawRef.startsWith("dialogs.") -> "screens." + rawRef
        else -> rawRef
    }

    // ---- 每份 flow 的 profile 字段对齐 ----
    @Test
    fun `every flow targets the canonical profile`() {
        val flowsDir = flowsDirOrNull()
        Assume.assumeTrue("仓库内未找到 dsl/json/flows ⇒ 本用例不适用（跳过，非通过）", flowsDir != null)
        for (f in flowsDir!!.listFiles { x -> x.extension == "json" }.orEmpty()) {
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

        // parsePanel 面板字段（★ 2026-10-09 ROI 瘦身：slot/mainValue 两槽已删 —— slotKey 由单件名经
        //   artifactPieces 反查、mainStatKey 由 (slot, mainName 前缀) 查表 ⇒ 不再列入本清单）
        for (path in listOf(
            "panels.artifact_backpack.name",
            "panels.artifact_backpack.mainName",
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
        assertTrue(p.rect("panels.artifact_backpack.level").bottom + yShift <= 1440)
        val subStats = p.rawObject("panels.artifact_backpack")!!.getJSONArray("subStats")
        for (i in 0 until subStats.length()) {
            val r = subStats.getJSONArray(i)
            assertTrue(r.getInt(3) + 63 <= 1440)
        }
        val band = p.rawObject("panels.artifact_backpack")!!.optJSONObject("starBand")!!
        val y1 = band.getJSONArray("y").getInt(1)
        assertTrue(y1 + 63 <= 1440)
    }
}
