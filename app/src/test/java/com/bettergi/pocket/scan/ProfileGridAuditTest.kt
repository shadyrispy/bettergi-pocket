package com.bettergi.pocket.scan

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * 2026-09-10 全面审计的回归固化。四类：
 *
 * 1. **1 列网格**（`char_strip` 左列头像条）：`ScreenProfile.gridGeometry` 曾有 `cols < 2 → null` 硬门，
 *    致 cols=1 网格回退读 `cardOrigin`（该网格无此键）→ 抛 JSONException，character_scan 首格即崩。
 * 2. **zones 顶层键含点号**（`char_popup.collapse`）：`resolve` 曾按 `.` 硬拆 → `zones.char_popup` 恒 null
 *    → `CHAIN_ANCHOR_PATHS["弹层收起"]` 解不出 → 收弹层点击静默跳过。
 * 3. **网格 n−1 规则**：`traverseRows == visibleRows − 1`（第 n 行留给滑动翻页）。
 * 4. **advance 定律**：引擎 swipe 末速≈0 无 fling → 滚动量 = 手指净行程 → `distance == 行数 × pitch`。
 */
class ProfileGridAuditTest {

    private fun assetsDir(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val candidate = File(dir, "src/main/assets/dsl")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("src/main/assets/dsl not found")
    }

    private fun profile(file: String, w: Int, h: Int): ScreenProfile {
        val p = ScreenProfile(JSONObject(File(assetsDir(), file).readText()))
        p.calibrate(w, h)
        return p
    }

    private fun raw(file: String): JSONObject = JSONObject(File(assetsDir(), file).readText())

    private val profiles = listOf(
        Triple("profiles.json", 3200, 1440),
        Triple("profiles_2560x1440.json", 2560, 1440),
        Triple("profiles_2244x1080.json", 2244, 1080),
    )

    // ---- 1) 1 列网格可用 ----

    @Test
    fun `cols=1 grid resolves geometry and cellCenter`() {
        for ((file, w, h) in profiles) {
            val p = profile(file, w, h)
            val g = p.gridGeometryFor("char_strip")
            assertTrue("$file: char_strip gridGeometry 为 null（cols=1 未被支持）", g != null)
            assertTrue("$file: char_strip cols 应为 1", g!!.cols == 1)
            // 6 槽全可算中心，且落在帧内
            for (i in 0 until 6) {
                val c = p.cellCenter("char_strip", i)
                assertTrue("$file: char_strip cell($i) x=${c.x} 越界", c.x in 0 until w)
                assertTrue("$file: char_strip cell($i) y=${c.y} 越界", c.y in 0 until h)
            }
        }
    }

    @Test
    fun `char_strip cardH equals pitch`() {
        // 引擎相位校正阈值取 cardH/2，须与 pitch/2 混叠边界对齐 → cardH 必须 == pitch
        for ((file, _, _) in profiles) {
            val g = raw(file).getJSONObject("grids").getJSONObject("char_strip")
            val ch = g.getJSONArray("cardSize").getInt(1)
            val rowY = g.getJSONArray("rowY")
            val pitch = rowY.getInt(1) - rowY.getInt(0)
            assertTrue("$file: char_strip cardH=$ch != pitch=$pitch", ch == pitch)
        }
    }

    // ---- 2) 含点号 zone 键可解 ----

    @Test
    fun `dotted zone key resolves via path`() {
        for ((file, _, _) in profiles) {
            val p = profile(file, if (file.contains("2560")) 2560 else if (file.contains("2244")) 2244 else 3200,
                if (file.contains("1080")) 1080 else 1440)
            // 点分路径须能解出（char_popup.collapse 键自身含点号）
            val r = try {
                p.rect("zones.char_popup.collapse.rect")
            } catch (e: Exception) {
                fail("$file: zones.char_popup.collapse.rect 解析失败：${e.message}").let { return }
            }
            assertTrue("$file: collapse rect 尺寸非正", r.width > 0 && r.height > 0)
            // 与 zone() 专用入口一致
            val zr = p.zone("char_popup.collapse")!!.getJSONArray("rect")
            assertTrue("$file: 点分路径与 zone() 结果不一致", r.left == zr.getInt(0))
        }
    }

    // ---- 3) n−1 规则 + 4) advance 定律 ----

    @Test
    fun `grid traverseRows is visibleRows minus one or all rows`() {
        for ((file, _, _) in profiles) {
            val grids = raw(file).getJSONObject("grids")
            for (key in grids.keys()) {
                val g = grids.getJSONObject(key)
                if (!g.has("visibleRows") || !g.has("traverseRows")) continue
                val vis = g.getInt("visibleRows")
                val trv = g.getInt("traverseRows")
                // ★ 2026-09-17 放宽（旧断言 == visibleRows-1 已作废）：
                //   n−1 规则的**目的**是避开边界行（末行可能被裁）；但 `char_strip` 必须**点满**可见行——
                //   列表**到底**时最后一窗口是 `E−5..E`，只点 r0..r4 会永漏列表**末件**（实测 cver12
                //   覆盖 0..90 共 91 件、GT 差集里剩下的正好是末位的 Amber）⇒ 改成 6/6。
                //   不变量：`visibleRows-1 ≤ traverseRows ≤ visibleRows`（既避开边界、又允许点满这一必要例外）。
                assertTrue(
                    "$file::$key traverseRows=$trv 越界（应 ∈ [${vis - 1}, $vis]）",
                    trv >= vis - 1 && trv <= vis,
                )
            }
        }
    }

    @Test
    fun `swipe advance distance is between n-1 and n rows`() {
        for ((file, _, _) in profiles) {
            val grids = raw(file).getJSONObject("grids")
            for (key in grids.keys()) {
                val g = grids.getJSONObject(key)
                val adv = g.optJSONObject("advance") ?: continue
                if (adv.optString("type") != "swipe") continue
                val rowY = g.optJSONArray("rowY") ?: continue
                if (rowY.length() < 2) continue
                val trv = g.optInt("traverseRows", -1)
                if (trv < 1) continue
                val pitch = (rowY.getInt(rowY.length() - 1) - rowY.getInt(0)).toDouble() / (rowY.length() - 1)
                val dist = adv.optInt("distance", -1)
                // ★ 2026-09-17 定稿不变量（旧断言「恰好 trv×pitch」已作废）：
                //   上界 `trv×pitch` = **结构性免跳行的充要条件**：前进量 > 本页遍历跨度 ⇒
                //     相邻页之间会漏掉整行（静默无日志）。
                //   下界 `1×pitch` = 至少要真前进一整行，否则跨页指纹几乎不变、引擎会误判「到底」提前收尾。
                //   · 多数网格取上界（恰好 n 行，无重叠、吞吐最高）；
                //   · `char_strip` 取 **4 行 / 遍历 6 行**（640px）：实测单次滑动落地量不可控
                //     （目标 800 时实际落 4~6 行、平均 4.55 ⇒ 相位漂移 ⇒ 点落邻卡 ⇒ 漏件）
                //     ⇒ 刻意留 2 行重叠，把「跳行」变成结构性不可能。
                //   ⚠️ 且 `char_strip.traverseRows` 已 = visibleRows = 6（不再遵守 n−1）：
                //     列表**到底**时最后一窗口是 `E−5..E`，漏点末行会**永漏列表末件**。
                val lo = pitch
                val hi = trv * pitch
                assertTrue(
                    "$file::$key advance.distance=$dist 越界（应 ∈ [${lo.toInt()}, ${hi.toInt()}] = [1, trv]×pitch$pitch）",
                    dist >= lo - 1 && dist <= hi + 1,
                )
                // from→to 净位移亦须与 distance 自洽
                val f = adv.optJSONArray("from"); val t = adv.optJSONArray("to")
                if (f != null && t != null) {
                    val net = abs(f.getInt(1) - t.getInt(1))
                    assertTrue("$file::$key distance=$dist 与 from→to 净位移 $net 打架", abs(net - dist) <= 4)
                }
            }
        }
    }

    // ---- 5) 流程网格引用存在 ----

    @Test
    fun `flow grid names exist in 2560 profile`() {
        val grids = raw("profiles_2560x1440.json").getJSONObject("grids")
        val flowsDir = File(assetsDir(), "flows")
        for (f in flowsDir.listFiles { x -> x.name.endsWith(".json") }?.sorted() ?: emptyList()) {
            val root = JSONObject(f.readText())
            val text = root.toString()
            // 粗粒度：抓 "grid": "xxx"
            val re = Regex("\"grid\"\\s*:\\s*\"([^\"]+)\"")
            for (m in re.findAll(text)) {
                val name = m.groupValues[1]
                assertTrue("${f.name}: 引用 grids.$name 但 2560 profile 无此网格", grids.has(name))
            }
        }
    }

    @Test
    fun `character_scan uses char_strip and auto_equip uses char_popup`() {
        val cs = File(assetsDir(), "flows/character_scan.json").readText()
        val ae = File(assetsDir(), "flows/auto_equip.json").readText()
        assertTrue("character_scan 应走 char_strip", cs.contains("\"char_strip\""))
        assertTrue("character_scan 不应再直接遍历 char_popup", !cs.contains("\"grid\": \"char_popup\""))
        assertTrue("auto_equip 应走 char_popup", ae.contains("\"char_popup\""))
        assertTrue("auto_equip 须先开弹层（clicks[tian]）", ae.contains("\"tian\""))
        assertTrue("auto_equip 须用弹层收起钮（田不可收层）", ae.contains("弹层收起"))
    }

    @Test
    fun `json configs are byte-identical between dsl and assets`() {
        // dsl/json 为源，assets/dsl 为产物；drift 会导致真机与文档不一致
        val dsl = File(assetsDir().parentFile!!.parentFile!!.parentFile!!.parentFile!!, "dsl/json")
        if (!dsl.isDirectory) return   // CI 下无 dsl/ 时跳过
        for (name in listOf("profiles.json", "profiles_2560x1440.json", "profiles_2244x1080.json")) {
            val a = File(dsl, name); val b = File(assetsDir(), name)
            if (a.exists() && b.exists()) {
                assertTrue("$name: dsl/json 与 assets/dsl 不一致", a.readText() == b.readText())
            }
        }
    }
}
