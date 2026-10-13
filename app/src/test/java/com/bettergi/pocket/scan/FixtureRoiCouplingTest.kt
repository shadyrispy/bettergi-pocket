package com.bettergi.pocket.scan

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 夹具 ROI ↔ profile 的耦合不变式（#229；审计 §四-5 的固化）。
 *
 * 为什么要它：`ScanEngineDryRunTest` 的假 OCR 网关按**精确 FrameRect** 映射文本、
 * `PanelFingerprint.regions` 又直接从 `panels.*.subStats` 取指纹区 ⇒ profile 任何 ROI 收窄都会
 * 同时打中这两处夹具硬编码（2026-10-05 subStats 收窄：11 条 DryRun 红、9 条各白等 180s）。
 * 本类把「夹具字面量 = profile 现值」从口头约定变成构建期断言：表格**手抄**自
 * `ScanEngineDryRunTest.kt`（不做反射 —— 抄错一次就会红，比"自动跟随"更诚实），
 * 改 profile 不改夹具 ⇒ 本类先红，不再用 27 分钟干跑去发现。
 *
 * 覆盖口径：dry-run 夹具建模的是**基准档**（`profiles.json` + calibrate(3200,1440) ⇒ scale=1），
 * 故只对基准档逐位对账；2560/2244 两档专属 profile 的 ROI 不在覆盖内（dry-run 不加载它们）。
 * 也不覆盖 `readCount and enterScreen wiring` 等用例里的**点击点**期望（(2824,80)/(516,427)/
 * swipe from-to 等）—— 那些是显式断言，漂了会直接红，不属于"静默读空"这一类耦合。
 *
 * 祝圣派生 rect（yShift+63）不在 profile 里逐位出现，按「基 rect + `zhushengYShiftFrame`」派生对账。
 * 合成帧独有的 rect（翻页灰带 / 指纹标记 / 抹除块）在下方以显式豁免清单列出并注明原因。
 */
class FixtureRoiCouplingTest {

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

    /** 逐级走原始 JSON 键（不调用生产解析）：`[i]` 后缀取数组元，`zones.*` 顶层键按最长前缀整键命中。 */
    private fun nodeAt(root: JSONObject, path: String): Any? {
        if (path.startsWith("zones.")) {
            val zones = root.optJSONObject("zones") ?: return null
            val rest = path.removePrefix("zones.")
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
        for (seg in path.split('.')) {
            val open = seg.indexOf('[')
            if (open > 0 && seg.endsWith("]")) {
                val idx = seg.substring(open + 1, seg.length - 1).toInt()
                node = (node as? JSONObject)?.optJSONArray(seg.substring(0, open))?.opt(idx) ?: return null
            } else {
                node = (node as? JSONObject)?.opt(seg) ?: return null
            }
        }
        return node
    }

    /** 取任意长度整数数组（rect 4 元 / cardOrigin 2 元 / points 逐点 2 元）。 */
    private fun intsAt(root: JSONObject, path: String): List<Int>? {
        val arr = nodeAt(root, path) as? JSONArray ?: return null
        return (0 until arr.length()).map { arr.getInt(it) }
    }

    /** 一行 = 夹具里的一处 rect 字面量（手抄，`where` 注明出处）+ 它必须逐位等于的 profile 键。 */
    private class Row(val literal: List<Int>, val path: String, val where: String)

    /** 逐位等值表：**假 OCR 网关的精确 FrameRect 映射** + syntheticFrame 的判据色块（[x0,y0,x1,y1]）。 */
    private val exactRows = listOf(
        // — Harness 的具名 ROI / pageLines / weaponLines（readLines 按这些 FrameRect 精确回文）—
        Row(listOf(2230, 220, 2462, 270), "panels.artifact_backpack.name", "NAME_RECT ≡ pageLines「晨光的明誓」行"),
        Row(listOf(2264, 794, 2720, 848), "panels.artifact_backpack.subStats[0]", "SUB0_RECT ≡ pageLines sub0（原位）"),
        Row(listOf(2264, 858, 2720, 912), "panels.artifact_backpack.subStats[1]", "pageLines sub1（原位）"),
        Row(listOf(2264, 922, 2720, 976), "panels.artifact_backpack.subStats[2]", "pageLines sub2（原位）"),
        Row(listOf(2264, 986, 2720, 1040), "panels.artifact_backpack.subStats[3]", "pageLines sub3（原位）"),
        Row(listOf(2224, 444, 2340, 500), "panels.artifact_backpack.mainName", "pageLines mainName"),
        Row(listOf(2230, 716, 2340, 766), "panels.artifact_backpack.level", "pageLines level（未祝圣）"),
        Row(listOf(2674, 63, 2874, 95), "screens.artifact_backpack.count.rect", "pageLines count 行"),
        Row(listOf(179, 50, 419, 104), "screens.dialogs.filterPanel.anchorTitle", "pageLines「圣遗物筛选」（filterReset 面板开合判据）"),
        Row(listOf(145, 21, 1084, 136), "screens._common.titleBar", "pageLines / weaponLines 标题条"),
        Row(listOf(2231, 214, 2470, 273), "panels.weapon_backpack.name", "WEAPON_NAME_RECT"),
        Row(listOf(2210, 709, 2455, 763), "panels.weapon_backpack.level", "WEAPON_LEVEL_RECT"),
        Row(listOf(2603, 60, 2846, 98), "screens.weapon_backpack.count.rect", "weaponLines count 行（备查）"),
        // — syntheticFrame 画的判据色块：pillState / banner / vote 读到的就是这些像素 —
        Row(listOf(2009, 199, 2126, 260), "screens.artifact_backpack.fiveStarToggle.pill", "① 五星开关 pill（画成 off）"),
        Row(listOf(2680, 205, 2840, 285), "panels.artifact_backpack.banner", "⑥ 稀有度 banner（rarity 判据）"),
    )

    /**
     * 祝圣派生表：字面量必须 == profile 基 rect **整体下移 yShift**（基准档 = `zhushengYShiftFrame`，当前 63）。
     * 这些字面量不在 profile 里逐位出现（profile 只存横幅以上的基 rect），故按派生关系对账。
     */
    private val shiftRows = listOf(
        Row(listOf(2264, 857, 2720, 911), "panels.artifact_backpack.subStats[0]", "SUB0_RECT_CRAFTED ≡ pageLines sub0（crafted）"),
        Row(listOf(2264, 921, 2720, 975), "panels.artifact_backpack.subStats[1]", "pageLines sub1（crafted）"),
        Row(listOf(2264, 985, 2720, 1039), "panels.artifact_backpack.subStats[2]", "pageLines sub2（crafted）"),
        Row(listOf(2264, 1049, 2720, 1103), "panels.artifact_backpack.subStats[3]", "pageLines sub3（crafted）"),
        Row(listOf(2230, 779, 2340, 829), "panels.artifact_backpack.level", "pageLines level（crafted yShift+63）"),
        Row(listOf(2811, 793, 2845, 821), "zones.artifact.panel.lock.rect", "④ 面板 lock 金块（crafted 后画在 +63）"),
        Row(listOf(2900, 776, 2947, 820), "zones.artifact.panel.astral.rect", "⑤ 面板 astral 金块（crafted 后画在 +63）"),
    )

    @Test
    fun `dryrun fixture rect literals equal the pinned profile rects`() {
        val root = profileJson()
        val bad = ArrayList<String>()
        for (row in exactRows) {
            val got = intsAt(root, row.path)
            if (got != row.literal) bad += "${row.where}：夹具=${row.literal} vs ${row.path}=${got}"
        }
        assertTrue(
            "夹具 rect 字面量与 profile 漂移（改 ROI 必须同步 ScanEngineDryRunTest.kt；漂移会让干跑静默读空）：\n  " +
                bad.joinToString("\n  "),
            bad.isEmpty(),
        )
    }

    @Test
    fun `off-craft fixture rects equal profile base rect shifted by zhusheng yShift`() {
        val root = profileJson()
        val shift = root.optInt("zhushengYShiftFrame", 63)
        assertTrue("基准档 yShift 应为正数，实际 $shift", shift > 0)
        val bad = ArrayList<String>()
        for (row in shiftRows) {
            val base = intsAt(root, row.path)
            val want = base?.let { listOf(it[0], it[1] + shift, it[2], it[3] + shift) }
            if (want != row.literal) bad += "${row.where}：夹具=${row.literal} vs ${row.path}+$shift=${want}"
        }
        assertTrue(
            "祝圣派生 rect 与「profile 基 rect + yShift」不一致：\n  " + bad.joinToString("\n  "),
            bad.isEmpty(),
        )
    }

    /**
     * 星带是**逐格合成**的 rect 序列（`x0 = 2228 + i*56`、宽 46、y 取带），没有单个 FrameRect 字面量；
     * 这里对它的 4 个数字逐一对账（profile 未登记 `cell` ⇒ 生产缺省 46，见 ScreenProfile.panelCropRect）。
     */
    @Test
    fun `star band painting matches profile starBand geometry`() {
        val band = nodeAt(profileJson(), "panels.artifact_backpack.starBand") as JSONObject
        assertEquals("星带左缘（夹具 x0 = 2228 + i*56）", 2228, band.getInt("x0"))
        assertEquals("星带 pitch（夹具步长 56）", 56, band.getInt("pitch"))
        assertEquals("星带格宽（夹具画 46px；profile 未登记 cell ⇒ 生产缺省 46）", 46, band.optInt("cell", 46))
        val y = band.getJSONArray("y")
        assertEquals("星带 y 带（夹具画 599..640）", listOf(599, 640), listOf(y.getInt(0), y.getInt(1)))
    }

    /**
     * 祝圣三采样点：夹具在 (2390/2400/2410, 703) 各画 5×5（x±2 / y±2）紫；zone 的采样点表必须逐点相同。
     * 同时钉住 craftedByCell 的**抹除块** (2386,699)-(2414,707) 仍覆盖全部画块 —— 否则"抹掉横幅"会留残点。
     */
    @Test
    fun `zhusheng sample point painting matches profile zone points and stays erasable`() {
        val root = profileJson()
        val pts = nodeAt(root, "zones.artifact.panel.zhusheng.points") as JSONArray
        val got = (0 until pts.length()).map { i ->
            val pt = pts.getJSONArray(i)
            listOf(pt.getInt(0), pt.getInt(1))
        }
        assertEquals(
            "祝圣采样点（夹具 5×5 画块的中心）",
            listOf(listOf(2390, 703), listOf(2400, 703), listOf(2410, 703)),
            got,
        )
        val erase = listOf(2386, 699, 2414, 707)
        for (pt in got) {
            assertTrue(
                "采样点 ${pt[0]},${pt[1]} 的 5×5 画块（±2）超出抹除块 $erase",
                pt[0] - 2 >= erase[0] && pt[0] + 2 <= erase[2] &&
                    pt[1] - 2 >= erase[1] && pt[1] + 2 <= erase[3],
            )
        }
    }

    /**
     * 指纹标记（`frameSource.grabFresh` 里画的随点击推进的红块）必须落在 `PanelFingerprint.regions`
     * 从 profile 取出的区带内 —— 2026-10-05 subStats 收窄后标记落区外 ⇒ 闸门永远等不到「面板已换」，
     * 9 条用例各超时 180s。标记 x 右缘随 `tick % 7` 最多到 2328，故取**包络** (2300,900)-(2328,904)。
     * 反向亦钉：标记不得污染 vote 像素判据区（金块/紫点/星带/banner），否则干跑投票会被夹具自己改掉。
     */
    @Test
    fun `fingerprint marker stays inside the profile-derived fingerprint regions`() {
        val root = profileJson()
        val profile = ScreenProfile(root).also { it.calibrate(3200, 1440) }
        val regions = PanelFingerprint.regions(profile, "artifact_backpack")
        assertTrue("PanelFingerprint 指纹区不得为空（空 ⇒ 闸门恒判「没换」）", regions.isNotEmpty())
        // 指纹区必须仍由 panels.artifact_backpack.subStats 的 4 行组成（本测试的另一半耦合）
        val subStats = root.getJSONObject("panels").getJSONObject("artifact_backpack").getJSONArray("subStats")
        for (i in 0 until subStats.length()) {
            val r = subStats.getJSONArray(i)
            val want = FrameRect(r.getInt(0), r.getInt(1), r.getInt(2), r.getInt(3))
            assertTrue("指纹区应含 subStats[$i] $want；实际=$regions", regions.any { it == want })
        }
        val marker = FrameRect(2300, 900, 2328, 904)
        assertTrue(
            "指纹标记 $marker 不在指纹区内（区带=$regions）—— ROI 收窄时必须同步夹具里的标记坐标",
            regions.any {
                it.left <= marker.left && it.top <= marker.top &&
                    it.right >= marker.right && it.bottom >= marker.bottom
            },
        )
        // 判据区清单（全部来自 profile）—— 标记与它们必须不相交
        val zones = root.getJSONObject("zones")
        val judges = LinkedHashMap<String, FrameRect>()
        for (name in listOf("artifact.panel.lock", "artifact.panel.astral")) {
            val r = zones.getJSONObject(name).getJSONArray("rect")
            judges[name] = FrameRect(r.getInt(0), r.getInt(1), r.getInt(2), r.getInt(3))
        }
        val pts = zones.getJSONObject("artifact.panel.zhusheng").getJSONArray("points")
        var x0 = Int.MAX_VALUE
        var y0 = Int.MAX_VALUE
        var x1 = Int.MIN_VALUE
        var y1 = Int.MIN_VALUE
        for (i in 0 until pts.length()) {
            val p = pts.getJSONArray(i)
            x0 = minOf(x0, p.getInt(0) - 2)
            y0 = minOf(y0, p.getInt(1) - 2)
            x1 = maxOf(x1, p.getInt(0) + 2)
            y1 = maxOf(y1, p.getInt(1) + 2)
        }
        judges["artifact.panel.zhusheng(±2 画块)"] = FrameRect(x0, y0, x1, y1)
        val banner = root.getJSONObject("panels").getJSONObject("artifact_backpack").getJSONArray("banner")
        judges["panels.artifact_backpack.banner"] = FrameRect(banner.getInt(0), banner.getInt(1), banner.getInt(2), banner.getInt(3))
        val band = root.getJSONObject("panels").getJSONObject("artifact_backpack").getJSONObject("starBand")
        val bandY = band.getJSONArray("y")
        judges["panels.artifact_backpack.starBand"] = FrameRect(
            band.getInt("x0"), bandY.getInt(0),
            band.getInt("x0") + 5 * band.getInt("pitch"), bandY.getInt(1),
        )
        for ((name, j) in judges) {
            assertTrue(
                "指纹标记 $marker 与判据区 $name $j 相交（会改掉干跑的投票结果）",
                marker.right <= j.left || marker.left >= j.right || marker.bottom <= j.top || marker.top >= j.bottom,
            )
        }
    }

    /**
     * 显式豁免清单：**合成帧独有的 rect，profile 里不存在对应矩形**（列在这里让豁免可见，不静默跳过）；
     * 每条仍断言它与 profile 的那一条真实耦合。
     */
    @Test
    fun `synthetic-only fixture rects are exempt and stay tied to the profile`() {
        val root = profileJson()
        // ① 翻页灰带 (416,297)-(2080,550)（⑧/⑨ 步）：模拟"翻页后列表内容前进"，真机无此矩形。
        //    可钉的耦合 = 左上角必须起在网格原点 —— 起偏了会盖到/漏掉首卡，翻页判据失真。
        assertEquals(
            "翻页灰带左上角应等于 grids.artifact_backpack.cardOrigin",
            listOf(416, 297),
            intsAt(root, "grids.artifact_backpack.cardOrigin"),
        )
        // ② craftedByCell 抹除块 (2386,699,2414,707)：合成帧独有（抹掉祝圣紫点）——
        //    覆盖关系在 `zhusheng sample point painting…` 用例里断言。
        // ③ 指纹标记 (2300,900)-(2328,904)：合成帧独有 —— 包含关系在 `fingerprint marker…` 用例里断言。
        // ④ #260 帧身份条 (2332,905)-(2402,911)：合成帧独有 —— 包含/避开关系在
        //    `identityBandLivesInsideThePanelCropAndTouchesNoPixelJudge` 用例里断言。
    }

    /** 两矩形是否不相交（[a,b] = 左/上/右(不含)/下(不含)）。 */
    private fun disjoint(a: List<Int>, b: List<Int>): Boolean =
        a[2] <= b[0] || a[0] >= b[2] || a[3] <= b[1] || a[1] >= b[3]

    /** 同一条矩形在祝圣帧上的位置（整体下移 yShift）也要一起避——干跑会把 ROI 平移后读。 */
    private fun shifted(r: List<Int>, yShift: Int): List<Int> = listOf(r[0], r[1] + yShift, r[2], r[3] + yShift)

    /**
     * ★ #260 帧身份条（夹具 `ScanEngineDryRunTest.Harness` 的 `BAND_*` 常量组）与 profile 的耦合。
     *
     * 条必须**整条落在两个面板的裁片并集里**：解析读的是裁片（同步臂 `panelCropBuf`、管道臂
     * `job.crop`），条一出裁片 ⇒ `readIdentityBand` 恒 null ⇒ 夹具**静默**退回「按点击进度派生
     * 内容」那条老路——#260 白修而且不会红（这正是本类存在的理由：把静默漂移变成构建期红）。
     *
     * 反向必须**避开全部像素判据区**（星带逐格 / equipped 饱和门 / 锁 / 收藏 / 祝圣采样点 /
     * banner 稀有度 / 武器 refine），否则夹具自己改掉干跑的投票。
     *
     * 唯一允许压字的地方是 `panels.artifact_backpack.subStats` 行带（条正是靠它进裁片）——
     * 而墨色是**纯黑**：`VoteJudges.substatRowPending` 只数中性色 lum∈[90,150) 与 [150,215)，
     * 黑(0) 与白(255) 都不入账 ⇒ 副词条三态判据逐位不变。末段用真 Mat 复核这条，并给一块**同面积
     * 深灰**做正对照 ⇒ 谁把墨色改成灰，这里当场红（不是空跑）。
     */
    @Test
    fun identityBandLivesInsideThePanelCropAndTouchesNoPixelJudge() {
        nu.pattern.OpenCV.loadLocally()
        val root = profileJson()
        val profile = ScreenProfile(root).also { it.calibrate(3200, 1440) }
        val yShift = root.optInt("zhushengYShiftFrame", 63)
        // 包络手抄自夹具：12 槽 × 步距 6、槽宽 4 ⇒ 右缘 2332+11×6+4=2402；高 905..910（含）⇒ 记为不含 911
        val band = listOf(2332, 905, 2402, 911)

        for (key in listOf("artifact_backpack", "weapon_backpack")) {
            val crop = profile.panelCropRect(key)
            assertTrue("panels.$key 裁片并集为空（身份条无处可画）", crop != null)
            assertTrue(
                "身份条 $band 必须整条落在 panels.$key 的裁片并集 $crop 内" +
                    "（出裁片 ⇒ 读侧静默回落到点击进度，#260 白修）",
                crop!!.left <= band[0] && crop.top <= band[1] &&
                    crop.right >= band[2] && crop.bottom >= band[3],
            )
        }

        // —— 必须避开的像素判据区（全部由 profile 推导，含祝圣 +yShift 变体）——
        val mustAvoid = LinkedHashMap<String, List<Int>>()
        for (path in listOf(
            "panels.artifact_backpack.banner",
            "panels.artifact_backpack.equipped",
            "panels.artifact_backpack.level",
            "panels.weapon_backpack.equipped",
            "panels.weapon_backpack.level",
            "panels.weapon_backpack.refine",
            "panels.weapon_backpack.slot",
            "zones.artifact.panel.lock.rect",
            "zones.artifact.panel.astral.rect",
        )) {
            val r = intsAt(root, path) ?: continue
            mustAvoid[path] = r
            mustAvoid["$path(+yShift)"] = shifted(r, yShift)
        }
        for (key in listOf("artifact_backpack", "weapon_backpack")) {
            val band0 = nodeAt(root, "panels.$key.starBand") as? JSONObject ?: continue
            val y = band0.getJSONArray("y")
            val cell = band0.optInt("cell", 46)
            for (i in 0 until 5) {
                val x0 = band0.getInt("x0") + i * band0.getInt("pitch")
                mustAvoid["panels.$key.starBand[$i]"] = listOf(x0, y.getInt(0), x0 + cell, y.getInt(1))
            }
        }
        val pts = nodeAt(root, "zones.artifact.panel.zhusheng.points") as JSONArray
        for (i in 0 until pts.length()) {
            val p = pts.getJSONArray(i)
            // 生产按 ±8 取样（ScreenProfile.panelCropRect 同口径），夹具只画 5×5 ⇒ 用 ±8 从严
            mustAvoid["zones.artifact.panel.zhusheng.points[$i]±8"] =
                listOf(p.getInt(0) - 8, p.getInt(1) - 8, p.getInt(0) + 8, p.getInt(1) + 8)
        }
        val hit = mustAvoid.entries.filter { (_, r) -> !disjoint(band, r) }
            .joinToString("; ") { "${it.key}=${it.value}" }
        assertTrue("身份条 $band 与像素判据区相交（会改掉干跑投票）：$hit", hit.isEmpty())

        // —— 反向：条确实压在 subStats 行带上（这就是它进裁片的通道）——
        val sub1 = intsAt(root, "panels.artifact_backpack.subStats[1]")!!
        assertTrue(
            "身份条 $band 必须整条落在 subStats[1] $sub1 行带内（ROI 收窄/挪位时先在这里红）",
            band[0] >= sub1[0] && band[1] >= sub1[1] && band[2] <= sub1[2] && band[3] <= sub1[3],
        )

        // —— 墨色复核：纯黑不得进 substatRowPending 的账；同面积深灰必须进（正对照）——
        // ⚠️ 只开**一行**大的 Mat（456×54 = 74KB），不开整帧（3200×1440 = 13.8MB）：
        //    判据按整行宽取像素，行带外的像素它本来也不看 ⇒ 断言强度不变，
        //    但本类原先零 native 依赖，多留 13.8MB live 会把测试 worker 的收尾压进
        //    OpenCV 静态对象的析构竞态（实测 SIGABRT / exit 134，见 §16.16(c) 落地记录）。
        val rowW = sub1[2] - sub1[0]
        val rowH = sub1[3] - sub1[1]
        val m = org.opencv.core.Mat(rowH, rowW, org.opencv.core.CvType.CV_8UC3, org.opencv.core.Scalar(255.0, 255.0, 255.0))
        try {
            // 帧坐标 → 行内局部坐标（条画在行带里，判据读的就是这一行）
            val bx0 = band[0] - sub1[0]
            val by0 = band[1] - sub1[1]
            val bx1 = band[2] - sub1[0] - 1
            val by1 = band[3] - sub1[1] - 1
            org.opencv.imgproc.Imgproc.rectangle(
                m,
                org.opencv.core.Point(bx0.toDouble(), by0.toDouble()),
                org.opencv.core.Point(bx1.toDouble(), by1.toDouble()),
                org.opencv.core.Scalar(0.0, 0.0, 0.0),
                -1,
            )
            assertNull(
                "纯黑身份条被 substatRowPending 数进账了（三态判据会跟着夹具自己变）",
                VoteJudges.substatRowPending(m, FrameRect(0, 0, rowW, rowH)),
            )
            org.opencv.imgproc.Imgproc.rectangle(
                m,
                org.opencv.core.Point(bx0.toDouble(), by0.toDouble()),
                org.opencv.core.Point(bx1.toDouble(), by1.toDouble()),
                org.opencv.core.Scalar(120.0, 120.0, 120.0),
                -1,
            )
            assertNotNull(
                "正对照失效：同面积深灰也读不出文字 ⇒ 上面那条 assertNull 是空跑",
                VoteJudges.substatRowPending(m, FrameRect(0, 0, rowW, rowH)),
            )
        } finally {
            m.release()
        }
    }
}
