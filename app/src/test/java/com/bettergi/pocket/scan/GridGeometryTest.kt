package com.bettergi.pocket.scan

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 网格几何 + §12.1 翻页起点规范化守门（方案 §12.1 / dsl/verify/swipe_adaptive_benchmark.md）。
 *
 * profiles 里存在**两种网格写法**，必须都支持：
 * - 风格 A：`cardOrigin` + `pitch`（artifact_backpack / weapon_backpack，7 列）
 * - 风格 B：`colX` / `rowY` 数组（char_popup / select_3col，3 列）
 * 旧 `cellCenter` 只认风格 A → char_popup 取 cell 中心会抛 JSONException（角色扫描潜在崩溃）。
 *
 * 翻页起点公式（2026-09-16 修订）：x = **最左**两卡**间隙中点**；y = 锚行（visibleRows，
 * 被底栏遮挡行）上沿 +5；dist = traverseRows × 行距。
 *
 * ⚠️ 起点取哪一段缝隙是**有讲究的**：原来取「末尾两卡缝隙」→ 2560 上算出 x=1627，恰好贴住右侧
 * 详情面板左缘（面板 x≳1640）⇒ 拖拽被判成面板操作、网格完全不滚（`adb input swipe` 定案：
 * x=1627 帧差 0.26% vs x=1041 28.64%）⇒ 改为**最左**一段（3200→638、2560→462），
 * 既仍落在卡缝里（不触发拖卡），又远离面板。实测起点与设备日志逐值一致。
 */
class GridGeometryTest {

    private fun profileOf(file: String, w: Int, h: Int): ScreenProfile {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val candidate = File(dir, "src/main/assets/dsl")
            if (candidate.isDirectory) {
                return ScreenProfile(JSONObject(File(candidate, file).readText())).apply {
                    calibrate(w, h) // 1:1，base 坐标 = 帧坐标
                }
            }
            dir = dir.parentFile ?: return@repeat
        }
        error("src/main/assets/dsl not found")
    }

    /** 基准档 = 3200 canonical（1:1）。 */
    private fun profile(): ScreenProfile = profileOf("profiles.json", 3200, 1440)

    // ---------- §12.1 起点（方案给定期望值）----------
    @Test
    fun `artifact backpack start point matches spec`() {
        val p = profile()
        // 最左卡缝：416 + cardW200 + (pitch244−200)/2 = 638（设备日志 几何起点 base=(638,1178) 逐值一致）
        assertEquals(638, p.advanceStart("artifact_backpack")!!.x)
        assertEquals(1178, p.advanceStart("artifact_backpack")!!.y)
        assertEquals(876, p.advanceDistance("artifact_backpack"))
    }

    @Test
    fun `weapon backpack start point matches spec`() {
        val p = profile()
        assertEquals(638, p.advanceStart("weapon_backpack")!!.x)
        assertEquals(1082, p.advanceStart("weapon_backpack")!!.y)
        assertEquals(876, p.advanceDistance("weapon_backpack"))
    }

    /** char_popup 方案里标"待标定"（无期望值），此处按几何推出并与硬编码距离互验。 */
    @Test
    fun `char popup start point derived from colX and rowY`() {
        val p = profile()
        // x = colX[0] + cardW + ((colX[1]-colX[0]) - cardW)/2 = 200 + 205 + (233-205)/2 = 419
        // ⚠️ 2026-09-16：与背包网格**统一取最左缝隙**（旧值 652 = colX[1] 那段）。char_popup 不受
        //    「避右侧面板」动机影响，但 419 仍在 col0(200..405) / col1(433..638) 之间 ⇒ 同样安全，
        //    且"取最左"这条规则只有一个分支、不按网格分叉。
        assertEquals(419, p.advanceStart("char_popup")!!.x)
        // y = rowY[visibleRows-1] + 5 = 1029 + 5
        assertEquals(1034, p.advanceStart("char_popup")!!.y)
        assertEquals(841, p.advanceDistance("char_popup"))
    }

    @Test
    fun `computed distance reproduces hardcoded advance distance`() {
        // 反验是几何模型正确性的关键证据：三个网格的计算距离必须与 profiles 写死值一致
        val p = profile()
        for (grid in listOf("artifact_backpack", "weapon_backpack", "char_popup")) {
            val hardcoded = p.rawObject("grids.$grid")!!.getJSONObject("advance").getInt("distance")
            val computed = p.advanceDistance(grid)
            assertEquals("grid '$grid' 计算距离应复现硬编码值", hardcoded, computed)
        }
    }

    /**
     * 起点必须落在**相邻两卡的缝隙**里（压在卡上会被判成拖卡 ⇒ 不滚，equip12 实证），
     * 且必须**远离右侧详情面板**（2560 踩过的坑）。这两条合起来才是 `advanceStart` 的不变量；
     * 至于取"第几段"缝隙由 KDoc 的动机说明负责，本测试只钉不变量本身。
     */
    @Test
    fun `start x falls inside the first card gap and clears the detail panel`() {
        val p = profile()
        val raw = p.rawObject("grids.artifact_backpack")!!
        val origin = raw.getJSONArray("cardOrigin")
        val pitch = raw.getJSONArray("pitch")
        val cardW = raw.getJSONArray("cardSize").getInt(0)
        val card0Left = origin.getInt(0)
        val card1Left = origin.getInt(0) + pitch.getInt(0)
        val x = p.advanceStart("artifact_backpack")!!.x
        assertTrue("起点 x=$x 必须落在第 1 列卡右缘 ${card0Left + cardW} 与第 2 列卡左缘 $card1Left 之间",
            x > card0Left + cardW && x < card1Left)
        // 面板左缘：3200 ≈2180/屏宽 0.68、2560 ≈1640/0.64 ⇒ 取 0.60 为安全上界
        assertTrue("起点 x=$x 应远离右侧详情面板（须 < 屏宽 60% = 1920）", x < 3200 * 0.60)
    }

    // ---------- 风格 B 修复（旧实现会抛异常）----------
    @Test
    fun `cell center supports colX and rowY grids`() {
        val p = profile()
        // char_popup 卡 205x242，colX=200/433/666，rowY=188/467/748/1029
        assertEquals(302, p.cellCenter("char_popup", 0).x) // 200 + 205/2
        assertEquals(309, p.cellCenter("char_popup", 0).y) // 188 + 242/2
        assertEquals(535, p.cellCenter("char_popup", 4).x) // index4 = row1,col1 → 433+102
        assertEquals(588, p.cellCenter("char_popup", 4).y) // 467+121
    }

    /**
     * 风格 A（cardOrigin+pitch）的点击锚 = `cardOrigin.y + clickDy`；x 仍是列中心（与
     * ScanEngineDryRunTest 的点击坐标期望一致）。★2026-09-25 起 3200 档 artifact_backpack 的
     * `clickDy` 来自**实测可点带**而不是 `cardH/2` ⇒ 本测试锁的是"公式"，带值本身由
     * `shipped 2560 weapon grid …` 与 profile note 锁。
     */
    @Test
    fun `cell center for origin pitch grids is origin plus clickDy`() {
        val p = profile()
        assertEquals(516, p.cellCenter("artifact_backpack", 0).x) // 416 + 200/2
        // 297 + clickDy130（★2026-09-25 #56：本档标定出 clickBand [0,260] 后，锚从「没量过时」的
        //   cardH/2=126 挪到**带中心** 130 ⇒ +4px。仍是"origin + pitch"风格网格，只是带已实测。）
        assertEquals(427, p.cellCenter("artifact_backpack", 0).y)
    }

    /**
     * #174：2560 档也注册成 2 列卡片网格（四帧互证：入库旧帧 + 现采三帧，竖描边 85/804/851/1570 逐位相同）。
     * ⚠️ 本档两列之间 x 809..845 那条亮带是「没有面板底衬」而不是卡缝阴影，见该键 geomNote。
     */
    @Test
    fun `set filter popup is a registered two column grid on 2560`() {
        val p = profileOf("profiles_2560x1440.json", 2560, 1440)
        val g = p.gridGeometryFor("set_filter_popup")!!
        assertEquals(2, g.cols)
        assertEquals(721, g.cardW)
        assertEquals(120, g.cardH)
        assertEquals(47, g.labelAnchor)   // 46 是"改格点时漏算一步"留下的 stale 值，见该键 labelAnchorNote 与审计 §12-G-3
        assertEquals(listOf(83, 849), g.colXs.toList())
        assertEquals(210, g.rowYs.first())
        assertEquals(1162, g.rowYs.last())
        assertEquals(826, p.advanceStart("set_filter_popup")!!.x)
        assertEquals(1167, p.advanceStart("set_filter_popup")!!.y)
        assertNull(p.advanceDistance("set_filter_popup"))
        assertEquals(60, p.clickBandHalfFor("set_filter_popup"))
        assertEquals(0, p.clickShiftCapFor("set_filter_popup"))
    }

    /**
     * 三档本键几何**齐了**之后新添一档的护栏：漏注册不会红任何运行日志（setFilter 不读这些键），
     * 只会在这里红 —— 因为 `gridGeometryFor` 返回 null 时 `clickBandHalfFor` 静默回退历史值 126。
     */
    @Test
    fun `every shipped profile registers the filter popup as a card grid`() {
        for ((file, w, h) in listOf(
            Triple("profiles.json", 3200, 1440),
            Triple("profiles_2560x1440.json", 2560, 1440),
            Triple("profiles_2244x1080.json", 2244, 1080),
        )) {
            val g = profileOf(file, w, h).gridGeometryFor("set_filter_popup")
            assertNotNull("$file: set_filter_popup 未注册卡片几何", g)
            assertEquals("$file: 本面板恒 2 列", 2, g!!.cols)
            assertEquals("$file: 行数应与 rowYTop 条目数一致", 8, g.rowYs.size)
        }
    }
    /**
     * #174：3200 档（canonical）也按**实测描边线位**注册成 2 列卡片网格。
     * 数值来源与口径见该键 `geomNote`（竖描边 204.5/1124.5/1171/2090，两帧互证）。
     */
    @Test
    fun `set filter popup is a registered two column grid on 3200`() {
        val p = profile()
        val g = p.gridGeometryFor("set_filter_popup")!!
        assertEquals(2, g.cols)
        assertEquals(921, g.cardW)
        assertEquals(120, g.cardH)   // = rowHeight（可点行高，不是画高 109）
        assertEquals(48, g.labelAnchor)
        assertEquals(listOf(203, 1169), g.colXs.toList())
        assertEquals(210, g.rowYs.first())
        assertEquals(1162, g.rowYs.last())
        // 起点 203+921+(966−921)/2 = 1146：落在两列之间的**缝**（实测描边 1126..1169）里
        assertEquals(1146, p.advanceStart("set_filter_popup")!!.x)
        assertEquals(1167, p.advanceStart("set_filter_popup")!!.y)
        // 本网格无 traverseRows ⇒ 距离无几何来源，advance 仍走字面 from/to，行为不变
        assertNull(p.advanceDistance("set_filter_popup"))
        // ⚠️ 注册会让 clickBandHalfFor 从「未标定回退值 126」变成 cardH/2=60 —— 本面板没有
        //   pagedGrid 调用点，所以今天无人读它；但这条**必须**钉住：将来谁把本面板接进平移逻辑，
        //   得先量 clickBand（没量过 ⇒ shiftCap=0，一律不平移）。
        assertEquals(60, p.clickBandHalfFor("set_filter_popup"))
        assertEquals(0, p.clickShiftCapFor("set_filter_popup"))
    }

    /**
     * #169：筛选面板 2244 档已按**实测**注册成 2 列卡片网格（不是把它硬塞进卡片公式，是它的格子
     * 本来就均匀：列边界 152/764 与 800/1411、行距 102、格高 = rowHeight 90）。
     * 注册后除了 `labelAnchor` 有地方放，`advanceStart` 也从 null 变有值 —— 期望值锁在下面。
     */
    @Test
    fun `set filter popup is a registered two column grid on 2244`() {
        val p = profileOf("profiles_2244x1080.json", 2244, 1080)
        val g = p.gridGeometryFor("set_filter_popup")!!
        assertEquals(2, g.cols)
        assertEquals(612, g.cardW)
        assertEquals(90, g.cardH)
        assertEquals(36, g.labelAnchor)
        assertEquals(listOf(152, 800), g.colXs.toList())
        assertEquals(157, g.rowYs.first())
        assertEquals(871, g.rowYs.last())
        // 起点 152+612+(648−612)/2 = 782：落在两列之间的**缝**（实测列缝 764..800）里，
        // 而不是字面 from.x=418（压在第一列卡上）—— 更合 advanceStart 的设计意图。
        assertEquals(782, p.advanceStart("set_filter_popup")!!.x)
        // 本网格无 traverseRows ⇒ 距离无几何来源，advance 仍走字面 from/to，行为不变
        assertNull(p.advanceDistance("set_filter_popup"))
    }

    @Test
    fun `scaling applies to derived start point`() {
        val p = profile()
        p.calibrate(2560, 1440) // scaleX = 0.8
        val base = 638
        assertEquals(Math.round(base * 0.8).toInt(), p.advanceStart("artifact_backpack")!!.x)
        // y 不缩放（1440 基准）
        assertEquals(1178, p.advanceStart("artifact_backpack")!!.y)
    }

    // ---------- 可点带 clickBand（2026-09-24 页级冻结归因后的标定） ----------

    private fun bandProfile(band: String): ScreenProfile {
        val grid = JSONObject(
            """{"cols":6,"cardSize":[196,253],"cardOrigin":[248,290],"pitch":[233,278],
               "visibleRows":4,"traverseRows":3$band}""",
        )
        return ScreenProfile(JSONObject("""{"grids":{"artifact_backpack":$grid}}""")).apply {
            calibrate(2560, 1440)
        }
    }

    @Test
    fun `2244 artifact bandTop surfaces in grid geometry for clickFloor fix 64`() {
        // ★ #64：clickBand=[53,242] 的 bandTop=53 必须带出 GridGeometry，否则 clickFloor
        //   只能用 −clickDy，会放行到名义行顶 = 真实卡顶之上 53px ⇒ 落到 UI 区。
        val g = bandProfile(""" ,"clickBand":[53,242]""").gridGeometryFor("artifact_backpack")!!
        assertEquals(53, g.bandTop)
        assertEquals(147, g.clickDy)      // (53+242)/2
        assertEquals(94, g.clickHalf)     // min(147−53, 242−147) = min(94,95)
        // ScanEngine clickFloor = −(clickDy − bandTop) = −(147−53) = −94 = 真实卡顶
        assertEquals(94, g.clickDy - g.bandTop)
        // 一致性：未标定带 bandTop=0 ⇒ clickFloor = −clickDy（旧值不变）
        val u = bandProfile("").gridGeometryFor("artifact_backpack")!!
        assertEquals(0, u.bandTop)
    }

    @Test
    fun `click band drives both the anchor and the safe window`() {
        val p = bandProfile(""" ,"clickBand":[0,210]""")
        // 锚点 = 带中心（相对行顶 105），不再用 cardH/2=126（那会把点击放到卡下沿外）
        assertEquals(290 + 105, p.cellCenter("artifact_backpack", 0).y)
        // 带半高（读数可接受门）= 105；点击上限 = 105 × 2/3 = 70（run8：放到 105 时点击仍贴卡下沿）
        assertEquals(105, p.clickBandHalfFor("artifact_backpack"))
        assertEquals(70, p.clickShiftCapFor("artifact_backpack"))
        // 第二行同样按带中心
        assertEquals(568 + 105, p.cellCenter("artifact_backpack", 6).y)
    }

    @Test
    fun `band derived from interior offsets keeps the tighter side`() {
        // 带 [20,200] ⇒ 中心 110，上下各余 90 ⇒ 带半高 90，再乘 2/3 安全余量 ⇒ 窗 60
        val p = bandProfile(""" ,"clickBand":[20,200]""")
        assertEquals(290 + 110, p.cellCenter("artifact_backpack", 0).y)
        assertEquals(90, p.clickBandHalfFor("artifact_backpack"))
        assertEquals(60, p.clickShiftCapFor("artifact_backpack"))
    }

    /**
     * ★ 2026-09-25 #51 改约：**未标定 clickBand ⇒ 可挪量上限 = 0（一律不平移）**。
     * 旧断言是 126（"没量过就不改行为"），但两起真机事故证明"照旧平移"本身就是行为改变：
     * run9 的 φ=+44..+119 把点击推到卡下沿最后 1..12px ⇒ 4 页整页冻结；
     * 华为真机（2244 未标定）φ=−69 把行 0 点击顶到卡顶之上 ⇒ 整页落空。
     * 锚点仍按 cardH/2 给（没量过就沿用历史值，不引入新错位），**只是不再挪**。
     */
    @Test
    fun `unbanded grid keeps the historical anchor but shifts nothing`() {
        val p = bandProfile("")
        assertEquals(290 + 126, p.cellCenter("artifact_backpack", 0).y)
        assertEquals(126, p.clickBandHalfFor("artifact_backpack"))
        assertEquals(0, p.clickShiftCapFor("artifact_backpack"))
    }

    @Test
    fun `missing geometry accepts the reading but moves nothing`() {
        // 带半高（读数接受门）仍回退历史值 253/2，避免"几何没解出来 ⇒ 整页读数全废"；
        // 可挪量 = 0（#51）：coerceIn(-0,0) 在这里是**期望语义**而不是静默清零 —— 没有实测
        // 可点带就没有资格挪点击。ScanEngine 侧会为该档打一条「未采用 ⇒ 本页不平移」的日志，
        // 不让它看起来像"偏移恰好为 0"。
        val p = bandProfile("")
        assertEquals(126, p.clickBandHalfFor("no_such_grid"))
        assertEquals(0, p.clickShiftCapFor("no_such_grid"))
    }

    @Test
    fun `malformed clickBand is treated as uncalibrated, never as a zero or negative window`() {
        // 顺序颠倒 / 零宽 / 长度不足 / **负上沿** / **多于两个数** 都不得产出 0 或负的窗：
        // 窗为负会让 φ.coerceIn(-窗, 窗) 直接抛 IllegalArgumentException 打断整轮扫描
        for (
            bad in listOf(
                """ ,"clickBand":[210,0]""",
                """ ,"clickBand":[105,105]""",
                """ ,"clickBand":[]""",
                // 负上沿 ⇒ clickHalf=(bottom-top)/2 会**大于** clickDy=(top+bottom)/2，
                // 于是安全窗能把行 0 的点击顶到名义行顶之上（那里是筛选行/5星开关，run11 塌列表）
                """ ,"clickBand":[-30,210]""",
                // 第三个数没有语义（带是"相对行顶的两个偏移"），多给一个说明标定写错了
                """ ,"clickBand":[0,105,210]""",
            )
        ) {
            val p = bandProfile(bad)
            assertEquals("坏值 $bad 必须退回历史锚点", 290 + 126, p.cellCenter("artifact_backpack", 0).y)
            assertEquals("坏值 $bad 必须当作未标定 ⇒ 不平移", 0, p.clickShiftCapFor("artifact_backpack"))
            assertEquals(126, p.clickBandHalfFor("artifact_backpack"))
        }
    }

    @Test
    fun `calibrated band keeps the shift window inside the row top`() {
        // 不变量：shiftCap ≤ clickDy，否则"最多挪这么多"会把点击挪出这一行（向上落到 UI 上）。
        // 对**任何**合法带（0 ≤ top < bottom）都成立，因为 clickHalf=(bottom-top)/2 ≤ (top+bottom)/2=clickDy。
        for (band in listOf("[0,210]", "[20,200]", "[0,256]", "[53,242]", "[0,6]")) {
            val p = bandProfile(""" ,"clickBand":$band""")
            val g = p.gridGeometryFor("artifact_backpack")!!
            val cap = p.clickShiftCapFor("artifact_backpack")
            val dy = p.scale(g.clickDy, p.scaleY)
            assertTrue("$band: 窗 $cap 必须 ≤ 行顶距 $dy", cap in 1..dy)
        }
    }

    @Test
    fun `shipped 2560 profile carries the measured click band`() {
        val dir = File(System.getProperty("user.dir") ?: ".", "src/main/assets/dsl")
        val p = ScreenProfile(JSONObject(File(dir, "profiles_2560x1440.json").readText()))
            .apply { calibrate(2560, 1440) }
        // 2026-09-24 冻结帧 20px 标尺：行顶 290、卡画下沿 500 ⇒ 锚点 395、窗 105
        assertEquals(395, p.cellCenter("artifact_backpack", 0).y)
        assertEquals(105, p.clickBandHalfFor("artifact_backpack"))
        assertEquals(70, p.clickShiftCapFor("artifact_backpack"))
        // 钳制后必须离卡下沿留余量：run8 里 φ=+91 未被拦住 ⇒ 486 贴住 500 冻了一页
        assertEquals(465, 395 + p.clickShiftCapFor("artifact_backpack"))
        assertTrue("最坏钳制点仍离卡下沿 ≥30px", 500 - 465 >= 30)
    }

    /**
     * ★2026-09-25 #53：2560 **武器**档的可点带 / 底栏锚是上机量出来的（不是从圣遗物档抄的），
     * 这里把导出的几何量钉死 —— 同步回退（#54 的 `--fix` 方向）或误改都会在这里红。
     * 量法与数据见 profile 的 `clickBandNote` / `labelAnchorNote`。
     */
    @Test
    fun `shipped 2560 weapon grid carries its own on-device measured geometry`() {
        val dir = File(System.getProperty("user.dir") ?: ".", "src/main/assets/dsl")
        val p = ScreenProfile(JSONObject(File(dir, "profiles_2560x1440.json").readText()))
            .apply { calibrate(2560, 1440) }
        // 落点实测：行顶 192、可点带 [0,256] ⇒ 锚 320、半高 128、可挪窗 128×2/3 = 85
        assertEquals(320, p.cellCenter("weapon_backpack", 0).y)
        assertEquals(128, p.clickBandHalfFor("weapon_backpack"))
        assertEquals(85, p.clickShiftCapFor("weapon_backpack"))
        // 最坏钳制落点 235..405，实测命中带 192..448 ⇒ 上下各余 43（≥40 才认为带标定真的在兜底）
        assertEquals(235, 320 - p.clickShiftCapFor("weapon_backpack"))
        assertEquals(405, 320 + p.clickShiftCapFor("weapon_backpack"))
        assertTrue("最坏落点离带边 ≥40px", 405 - 192 <= 256 - 40 && 235 - 192 >= 40)
        // 底栏锚：两面板卡片版式不同 ⇒ 锚必然不同（209 vs 227），互抄是这档的历史事故
        val w = p.gridGeometryFor("weapon_backpack")!!
        val a = p.gridGeometryFor("artifact_backpack")!!
        assertEquals(227, w.labelAnchor)
        assertEquals(209, a.labelAnchor)
        assertNotEquals("武器档锚不得等于圣遗物档（两面板不同构）", a.labelAnchor, w.labelAnchor)
    }

    /**
     * ★2026-09-28 #64：shipped 2244 **圣遗物**档的 `clickBand` 上沿不是 0（[53,242]），
     * `bandTop` 必须带出 GridGeometry 供 ScanEngine 算 `clickFloor`；同档 **武器**带 [0,176]
     * 上沿为 0 ⇒ `bandTop=0`，地板仍 = `−clickDy`（证明修复没顺手改掉无例外档的行为）。
     */
    @Test
    fun `shipped 2244 artifact carries bandTop for clickFloor while weapon stays at zero`() {
        val dir = File(System.getProperty("user.dir") ?: ".", "src/main/assets/dsl")
        val p = ScreenProfile(JSONObject(File(dir, "profiles_2244x1080.json").readText()))
            .apply { calibrate(2244, 1080) } // base 2244x1080 ⇒ scaleY=1，几何量 1:1
        val a = p.gridGeometryFor("artifact_backpack")!!
        assertEquals(53, a.bandTop)
        assertEquals(147, a.clickDy)
        assertEquals(94, a.clickHalf)
        // clickFloor = −(clickDy − bandTop) = −(147−53) = −94 = 真实卡顶（旧 −147 放行到名义行顶）
        assertEquals(94, a.clickDy - a.bandTop)
        // 安全窗本身不受影响（#64 改的是地板不是窗）：shiftCap=94×2/3=62 ≪ 94 ⇒ 落点仍在带内
        assertEquals(62, p.clickShiftCapFor("artifact_backpack"))
        // 武器档无例外 ⇒ bandTop=0，clickFloor 与旧值 −clickDy 逐位一致（无回归）
        val w = p.gridGeometryFor("weapon_backpack")!!
        assertEquals(0, w.bandTop)
        assertEquals(w.clickDy, w.clickDy - w.bandTop)
    }

    /**
     * ★2026-09-29：`cardRelRect` 原来裸读 `cardOrigin`，而 `char_strip`/`char_popup` 这类网格
     * **只有 `colX`/`rowY`** ⇒ `getJSONArray` 抛 `No value for cardOrigin`，把整轮 character_scan
     * 打断在第一个格（`ScanEngine.cardRoi` → 卡片选中框判据，#25 引入）。
     * 现在两种写法统一向 `gridGeometry` 要原点：这里①三档 × 两个 colX/rowY 网格全部取得到矩形，
     * ②矩形中心与 `cellCenter` 同坐标（错开一格会直接红），③`cardOrigin` 档的数值与旧公式逐位相同。
     */
    @Test
    fun `cardRelRect covers colX-rowY grids and keeps cardOrigin grids numerically identical`() {
        val dir = File(System.getProperty("user.dir") ?: ".", "src/main/assets/dsl")
        for ((file, w, h) in listOf(
            Triple("profiles.json", 3200, 1440),
            Triple("profiles_2560x1440.json", 2560, 1440),
            Triple("profiles_2244x1080.json", 2244, 1080),
        )) {
            val p = ScreenProfile(JSONObject(File(dir, file).readText()))
                .apply { calibrate(w, h) }
            for (gridKey in listOf("char_strip", "char_popup")) {
                val g = p.gridGeometryFor(gridKey)
                assertNotNull("$file: $gridKey 应能解析出几何", g)
                for (row in 0 until g!!.rowYs.size) {
                    val r = p.cardRelRect(gridKey, intArrayOf(0, 0, g.cardW, g.cardH), col = 0, row = row)
                    assertTrue("$file $gridKey row=$row 矩形退化", r.right > r.left && r.bottom > r.top)
                    // 与点击点同一坐标：cellCenter 用 clickDy，整卡矩形用 cardH/2（未标定带时二者同为 cardH/2）
                    val c = p.cellCenter(gridKey, row * g.cols)
                    assertEquals(p.scale(g.colXs[0] + g.cardW / 2, p.scaleX), (r.left + r.right) / 2)
                    assertTrue("$file $gridKey row=$row 中心与卡矩形错开: c.y=${c.y} rect=[${r.top},${r.bottom}]",
                        kotlin.math.abs(c.y - (r.top + r.bottom) / 2) <= 2)
                }
            }
        }
        // cardOrigin 档（3200 圣遗物）数值不变：origin(248,290) + col·pitch(206) / row·pitch(292)
        val base = ScreenProfile(JSONObject(File(dir, "profiles.json").readText())).apply { calibrate(3200, 1440) }
        val rel = base.cardRelRect("artifact_backpack", intArrayOf(8, 6, 48, 46), col = 3, row = 2)
        val origin = base.rawObject("grids.artifact_backpack")!!.getJSONArray("cardOrigin")
        val pitch = base.rawObject("grids.artifact_backpack")!!.getJSONArray("pitch")
        assertEquals(origin.getInt(0) + 3 * pitch.getInt(0) + 8, rel.left)
        assertEquals(origin.getInt(1) + 2 * pitch.getInt(1) + 6, rel.top)
    }

    // ★ P3（2026-09-30）：cardRelRect / cellCenter 行列越界由"静默钳位"改 fail-loud
    @Test
    fun `cardRelRect and cellCenter fail loud on out-of-range col row`() {
        val p = profile()
        val g = p.gridGeometryFor("artifact_backpack")!!
        try {
            p.cardRelRect("artifact_backpack", intArrayOf(0, 0, g.cardW, g.cardH), col = g.cols, row = 0)
            throw AssertionError("col 越界必须抛")
        } catch (e: IllegalStateException) {
            assertTrue("错误须含 key 与越界值", e.message!!.contains("artifact_backpack") && e.message!!.contains("col=${g.cols}"))
        }
        try {
            p.cardRelRect("artifact_backpack", intArrayOf(0, 0, g.cardW, g.cardH), col = 0, row = g.rowYs.size)
            throw AssertionError("row 越界必须抛")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("row=${g.rowYs.size}"))
        }
        try {
            p.cellCenter("artifact_backpack", index = g.cols * g.rowYs.size)
            throw AssertionError("index 超出一页格数必须抛")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("index=${g.cols * g.rowYs.size}"))
        }
        // 界内值仍正常
        assertNotNull(p.cardRelRect("artifact_backpack", intArrayOf(0, 0, g.cardW, g.cardH), col = 0, row = 0))
        assertNotNull(p.cellCenter("artifact_backpack", 0))
    }
}
