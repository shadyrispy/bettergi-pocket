package com.bettergi.pocket.scan

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import nu.pattern.OpenCV
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * §12.2 翻页距离自适应守门（合成帧，JVM 可跑）。
 *
 * 合成网格结构对齐方案实测数据：卡内亮度 124、卡底/间隙带 207~255，
 * 卡 253px + 行距 292px。算法应在平移 0/±20/±40/±80/±120 时把 err 测出来
 * （方案实测：误差全 0、漏检 0、MAE 0.0px）。
 */
class GridAlignTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            nu.pattern.OpenCV.loadLocally()
            Mat(2, 2, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0)).release()
        }

        private const val W = 3200
        private const val H = 1440
    }

    /** 每测试前清 p0 基准，避免用例间状态泄漏（p0 由 captureBaseline 显式建立，测试不建立→退化为 detected−expected）。 */
    @Before
    fun resetState() {
        GridAlign.resetBaseline()
    }

    private fun profile(): ScreenProfile {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val candidate = File(dir, "src/main/assets/dsl")
            if (candidate.isDirectory) {
                return ScreenProfile(JSONObject(File(candidate, "profiles.json").readText())).apply {
                    calibrate(W, H) // 1:1，base 坐标 = 帧坐标
                }
            }
            dir = dir.parentFile ?: return@repeat
        }
        error("src/main/assets/dsl not found")
    }

    /**
     * 合成背包网格：暗面板背景（40，模拟游戏库存面板）+ 每行均匀暗卡体 253px（124）。
     * 卡顶 = 最强「暗→亮」台阶（面板 40 → 卡 124，+84），与真机一致；卡底（卡→面板）为负台阶被 argmax 排除。
     * 首行上沿 = [top]（可整体平移模拟翻页后残留偏移）。列间留空隙（不画卡）以模拟真实 7 列网格。
     */
    private fun syntheticGrid(top: Int, rows: Int = 4, cardW: Int = 200, pitchX: Int = 244, pitchY: Int = 292): Mat {
        val m = Mat(H, W, CvType.CV_8UC3, Scalar(40.0, 40.0, 40.0))
        val g = profile().gridGeometryFor("artifact_backpack")!!
        for (r in 0 until rows) {
            val y = top + r * pitchY
            if (y >= H) break
            for (c in 0 until g.cols) {
                val x = g.colXs[c]
                if (x + cardW > W) continue
                // 卡体：均匀暗（卡顶即最强暗→亮台阶，与真机一致；无亮带以免与卡顶竞争）
                val cardBottom = (y + 253).coerceAtMost(H)
                if (cardBottom > y) {
                    Imgproc.rectangle(m, org.opencv.core.Point(x.toDouble(), y.toDouble()),
                        org.opencv.core.Point((x + cardW).toDouble(), cardBottom.toDouble()),
                        Scalar(124.0, 124.0, 124.0), -1)
                }
            }
        }
        return m
    }

    /**
     * 绝对偏置：合成帧的卡底/间隙亮度分布与真机帧不完全一致，故 err 含一个**恒定**偏置
     * （方案在真机帧上把它标定为 −5，输出 +5 即真实上沿）。这里先在 shift=0 处量出该偏置，
     * 再据此断言**相对精度**——那才是距离自适应真正依赖的性质（err 随漂移线性变化）。
     */
    private fun bias(p: ScreenProfile): Int {
        val expected = p.gridGeometryFor("artifact_backpack")!!.rowYs.first()
        val frame = syntheticGrid(expected)
        val err = GridAlign.measureError(frame, p, "artifact_backpack") ?: 0
        frame.release()
        return err
    }

    @Test
    fun `bias is constant and small`() {
        val p = profile()
        val b = bias(p)
        println("合成帧恒定偏置 = $b（真机标定为 0；此处为合成卡剖面差异所致）")
        assertTrue("偏置应远小于半 pitch(146)，实测 $b", Math.abs(b) <= 20)
    }

    /** 方案实测场景：平移 0/±20/±40/±80/±120 → 误差全 0（此处按恒定偏置折算后应为 0）。 */
    @Test
    fun `shifts within half pitch are measured linearly`() {
        val p = profile()
        val expected = p.gridGeometryFor("artifact_backpack")!!.rowYs.first()
        val b = bias(p)
        val results = ArrayList<String>()
        var worst = 0
        for (shift in intArrayOf(-120, -80, -40, 0, 20, 40, 80, 120)) {
            val frame = syntheticGrid(expected + shift)
            val err = GridAlign.measureError(frame, p, "artifact_backpack")
            val residual = Math.abs((err ?: Int.MAX_VALUE) - shift - b)
            if (err != null) worst = maxOf(worst, residual)
            results += "shift=$shift → err=$err (残差 $residual)"
            frame.release()
        }
        println(results.joinToString("\n"))
        assertTrue("各平移量的残差应 ≤3px（实测最大 $worst）", worst <= 3)
    }

    /**
     * 已知局限（方案未覆盖）：网格是周期信号（周期=行距），搜索窗只有 ±150，
     * 真偏移超出窗口时相位会锁定到邻行（实测 +200px 被测成 −85，仍能通过 |err|≤146 限幅）。
     * 此处钉死「不会输出荒谬值」的底线；兜底已是 fpband 落地条带 + pageDrift 记账
     * （2026-09-14 翻页整体改造，nextDistance 控制律已删）。
     */
    @Test
    fun `aliased measurement stays within guard`() {
        val p = profile()
        val expected = p.gridGeometryFor("artifact_backpack")!!.rowYs.first()
        val frame = syntheticGrid(expected + 200) // 超出搜索窗 → 混叠
        val err = GridAlign.measureError(frame, p, "artifact_backpack")
        println("超出搜索窗 → err=$err（混叠，非真实偏移 200）")
        assertTrue("混叠结果仍需落在限幅内或被判 null：err=$err", err == null || Math.abs(err!!) <= 146)
        frame.release()
    }

    @Test
    fun `non card grid has no alignment`() {
        val p = profile()
        // 合成帧画的是 **artifact_backpack** 的 7 列卡（x 取 g.colXs、pitchY=292），对 set_filter_popup
        //   而言只有 2 列、行距 136 ⇒ `detectRow` 的列共识门 + 周期自检都过不去 ⇒ null。
        // ⚠️ 这条**不是**"该档没注册卡片几何"（那是 #174 之前的事实，3200/2244 现在都注册了）；
        //   "未注册 ⇒ null"由 GridGeometryTest.`2560 filter grid is still unregistered` 钉着。
        val frame = syntheticGrid(297)
        assertNull(GridAlign.measureError(frame, p, "set_filter_popup"))
        frame.release()
    }

    /** §12.5 withGridRowOffset：网格类坐标随行偏移平移，panel 类坐标与 rowPitch 不变。 */
    @Test
    fun `grid row offset shifts grid coords but not panel rects`() {
        val p = profile()
        val dy = 100
        val off = p.withGridRowOffset(dy)
        val g0 = p.gridGeometryFor("artifact_backpack")!!
        val g1 = off.gridGeometryFor("artifact_backpack")!!
        assertArrayEquals(g0.rowYs.map { it + dy }.toIntArray(), g1.rowYs)
        assertEquals(g0.rowPitch, g1.rowPitch, 0.001)
        // cellCenter（index 3 = row0 col3）随偏移平移，x 不变
        val c0 = p.cellCenter("artifact_backpack", 3)
        val c1 = off.cellCenter("artifact_backpack", 3)
        assertEquals(c0.x, c1.x)
        assertEquals(c0.y + dy, c1.y)
        // panel 固定坐标不偏移
        assertEquals(p.rect("screens.artifact_backpack.count"), off.rect("screens.artifact_backpack.count"))
        // 偏移不影响原 profile（视图隔离）
        assertEquals(g0.rowYs.toList(), p.gridGeometryFor("artifact_backpack")!!.rowYs.toList())
    }

    /**
     * 相位 φ 是**帧**像素（由帧像素量的落地残差算出），而 `gridRowOffset` 加在**基准**坐标上再统一
     * scale ⇒ [ScreenProfile.withGridRowOffset] 必须换算回基准像素，否则非 1:1 档实际平移 = φ×scaleY
     * （点击落到卡缝 ⇒ 读邻卡 ⇒ 静默漏件）。三档已标定分辨率 scale=1，只有回退档会踩。
     */
    @Test
    fun `grid row offset is in frame pixels at any scale`() {
        val p = profile().apply { calibrate(W, 1080) } // base 3200x1440 → scaleY = 0.75
        assertEquals(0.75, p.scaleY, 1e-9)
        val phi = 75 // 帧像素
        val off = p.withGridRowOffset(phi)
        val c0 = p.cellCenter("artifact_backpack", 3)
        val c1 = off.cellCenter("artifact_backpack", 3)
        assertEquals("φ 必须原样落到帧坐标，不受 scaleY 影响", c0.y + phi, c1.y)
        // 网格行同样：基准偏移 = round(φ / scaleY)
        val g0 = p.gridGeometryFor("artifact_backpack")!!
        val g1 = off.gridGeometryFor("artifact_backpack")!!
        assertEquals(Math.round(phi / 0.75).toInt(), g1.rowYs[0] - g0.rowYs[0])
    }

    @Test
    fun `char popup alignment works with colX rowY geometry`() {        val p = profile()
        val g = p.gridGeometryFor("char_popup")!!
        // 3 列网格；暗面板背景 + 均匀暗卡（卡顶即最强暗→亮台阶，与 artifact 合成一致）
        val m = Mat(H, W, CvType.CV_8UC3, Scalar(40.0, 40.0, 40.0))
        for (r in 0 until g.rowYs.size) {
            val y = g.rowYs[r]
            for (c in 0 until g.cols) {
                val x = g.colXs[c]
                Imgproc.rectangle(m, org.opencv.core.Point(x.toDouble(), y.toDouble()),
                    org.opencv.core.Point((x + g.cardW).toDouble(), (y + g.cardH).toDouble()),
                    Scalar(124.0, 124.0, 124.0), -1)
            }
        }
        val err = GridAlign.measureError(m, p, "char_popup")
        println("char_popup 未平移 → err=$err")
        assertTrue("char_popup 应能测相位（几何用 colX/rowY），实测 $err", err != null && Math.abs(err!!) <= 8)
        m.release()
    }

    // ============ #226 实测（audit：先量再改）：整帧行均值的调用频率、单价与改法等价性 ============
    //
    // 频率取自调用图（不是估的）：
    //   · [GridAlign.rowPhase] 只有一个生产调用点 = ScanGridDomain.kt:1137，在 pagedGrid 的
    //     `while (true)`（:185）里**每翻一页 1 次**；满背包一轮 ≈115 页（2400 件 ÷ 每页 21 格，
    //     A9 硬闸 240 次滑动见 TimingOverrides.DEFAULT_SWIPE_CAP）。
    //   · 整帧逐行的 `buildColumnProfiles`（GridAlign.kt:423）在 detectRow 里，经
    //     captureBaseline 每轮**1 次**（ScanGridDomain.kt:731 `if (!capturedBaseline)`）、
    //     经 phaseOffset 只在底栏锚与落地条带**都**没给数时补 1 次（ScanGridDomain.kt:1168）；
    //     measureError 生产上只剩 ScriptRunner.kt:536 的调试命令。
    //   · rowMean 没有任何每格调用点（grep 全仓仅 GridAlign.kt:247/423/480）。

    private var benchSink = 0

    private fun medianNs(reps: Int, body: () -> Any?): Long {
        val ts = LongArray(reps) {
            val t0 = System.nanoTime()
            benchSink += body()?.hashCode() ?: 0
            System.nanoTime() - t0
        }
        ts.sort()
        return ts[reps / 2]
    }

    private fun dslDir(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val candidate = File(dir, "src/main/assets/dsl")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("src/main/assets/dsl not found")
    }

    private fun profileAt(asset: String, w: Int, h: Int): ScreenProfile =
        ScreenProfile(JSONObject(File(dslDir(), asset).readText())).apply { calibrate(w, h) }

    /** 背包帧替身：暗面板 40 + 卡体 124 + 卡内底栏亮带（labelAnchor 处、带宽 45，对齐三档标定）。
     *  先画满卡体再画带 —— 2244 档 `labelAnchor(219) > cardSize[1](177)`，带会跨到下一行的卡上，
     *  同一趟画就被盖掉 ⇒ 守卫不过，量到的就不是真实负载。
     *  rowPhase 的逐列整帧循环与画面内容无关，但内容必须让守卫**真过**，否则量的是提前返回。 */
    private fun benchFrame(p: ScreenProfile, gridKey: String, w: Int, h: Int): Mat {
        val g = p.gridGeometryFor(gridKey)!!
        val m = Mat(h, w, CvType.CV_8UC3, Scalar(40.0, 40.0, 40.0))
        for (pass in 0..1) {
            for (r in g.rowYs.indices) {
                val y = p.scale(g.rowYs[r], p.scaleY)
                val bandT = (y + p.scale(g.labelAnchor - 22, p.scaleY)).coerceAtLeast(0)
                val bandB = (y + p.scale(g.labelAnchor + 23, p.scaleY)).coerceAtMost(h)
                val cardB = (y + p.scale(g.cardH, p.scaleY)).coerceAtMost(h)
                val y0 = if (pass == 0) y else bandT
                val y1 = if (pass == 0) cardB else bandB
                val color = if (pass == 0) Scalar(124.0, 124.0, 124.0) else Scalar(228.0, 232.0, 236.0)
                if (y1 <= y0) continue
                for (c in 0 until g.cols) {
                    val x = p.scale(g.colXs[c], p.scaleX).toDouble()
                    val xr = p.scale(g.colXs[c] + g.cardW, p.scaleX).coerceAtMost(w).toDouble()
                    Imgproc.rectangle(
                        m, org.opencv.core.Point(x, y0.toDouble()), org.opencv.core.Point(xr, y1.toDouble()), color, -1,
                    )
                }
            }
        }
        return m
    }

    private fun fmt(v: Double, digits: Int): String = String.format(java.util.Locale.US, "%.${digits}f", v)

    @Test
    fun `rowPhase 每页单价与整帧行浪费实测`() {
        val pagesPerScan = 115
        val cellsPerPage = 21
        for ((asset, w, h) in listOf(
            Triple("profiles.json", 3200, 1440),
            Triple("profiles_2244x1080.json", 2244, 1080),
        )) {
            val p = profileAt(asset, w, h)
            val g = p.gridGeometryFor("artifact_backpack")!!
            val frame = benchFrame(p, "artifact_backpack", w, h)
            try {
                val phase = GridAlign.rowPhase(frame, p, "artifact_backpack")
                assertNotNull("合成帧应测到绝对行相位，否则这里量的是提前返回而非真实负载", phase)
                // 行窗按 rowPhase 同式复算（BAND_MAX_RUN=96 是 GridAlign 私有常量，此处同值写死）
                val pitch = p.scale(g.rowPitch.toInt().coerceAtLeast(1), p.scaleY)
                val firstCenter = p.scale(g.rowYs[0] + g.labelAnchor, p.scaleY)
                val yFrom = (firstCenter - pitch).coerceAtLeast(0)
                val yTo = (firstCenter + g.rowYs.size * pitch + p.scale(96, p.scaleY)).coerceAtMost(h - 1)
                val read = (yTo - yFrom + 1).coerceAtLeast(0)
                val calls = g.cols.toLong() * h
                val bytesPerCall = g.cardW.toLong() * 3
                repeat(10) { GridAlign.rowPhase(frame, p, "artifact_backpack") }
                val rpMs = medianNs(9) { GridAlign.rowPhase(frame, p, "artifact_backpack") } / 1e6
                repeat(4) { GridAlign.measureError(frame, p, "artifact_backpack") }
                val deMs = medianNs(5) { GridAlign.measureError(frame, p, "artifact_backpack") } / 1e6
                println(
                    "#226 ${w}x${h} cols=${g.cols} cardW=${g.cardW}：rowPhase 每页 " +
                        "${fmt(rpMs, 2)}ms（rowMean ${calls} 次/页、短命 ByteArray ${fmt(calls * bytesPerCall / 1e6, 1)}MB/页）" +
                        "；detectRow 那条（每轮 1 次 + 回退每页 ≤1 次）${fmt(deMs, 2)}ms/次" +
                        " ⇒ 外推每轮 ${pagesPerScan} 页 ≈ ${fmt(rpMs * pagesPerScan, 0)}ms / ${fmt(calls * bytesPerCall * pagesPerScan / 1e6, 0)}MB" +
                        "；每格 ${fmt(rpMs / cellsPerPage, 3)}ms" +
                        "；整帧扫 ${h} 行而 brightBands 只读 ${read} 行（白算 ${fmt(100.0 * (h - read) / h, 1)}%）"
                )
                // 上限：这是**每页**单价。若哪天被挪进每格循环（21 倍）或行均单价暴涨，这条会红；
                // 25ms 相对实测 1.7~3.4ms 留 7~15 倍余量，慢机器上也不误报。
                assertTrue("rowPhase 应是每页毫秒级，实测 ${fmt(rpMs, 2)}ms/页", rpMs < 25.0)
                assertTrue("整帧循环确实多算了行（#226 的现象），只读 ${read}/${h}", read < h)
            } finally {
                frame.release()
            }
        }
    }

    /**
     * #226 的两条候选改法**必须逐位不变**（行剖面喂绝对行相位 ⇒ 数值一动就静默挪点击坐标），
     * 本用例把等价性钉住：复用 grow-only 缓冲、以及把行扫描限定到 yFrom..yTo。
     * 生产代码本轮**未改**（实测单价见上一条用例），改的人可直接把这两条当验收。
     */
    @Test
    fun `复用缓冲与限定行窗都逐位等价`() {
        val w = 600
        val h = 240
        val m = Mat(h, w, CvType.CV_8UC3, Scalar(40.0, 40.0, 40.0))
        Imgproc.rectangle(m, org.opencv.core.Point(0.0, 60.0), org.opencv.core.Point((w - 1).toDouble(), 79.0), Scalar(230.0, 230.0, 230.0), -1)
        Imgproc.rectangle(m, org.opencv.core.Point(0.0, 100.0), org.opencv.core.Point(149.0, 119.0), Scalar(230.0, 230.0, 230.0), -1)
        Imgproc.rectangle(m, org.opencv.core.Point(0.0, 170.0), org.opencv.core.Point((w - 1).toDouble(), 205.0), Scalar(236.0, 236.0, 236.0), -1)
        val x0 = 71
        val x1 = 267 // 跨那条只亮到 x=150 的带 ⇒ 剖面带横向结构，不是每行同一个值
        val need = (x1 - x0) * m.channels()
        try {
            var scratch = ByteArray(0)
            fun meanScratch(y: Int): Double {
                if (scratch.size < need) scratch = ByteArray(need)
                m.get(y, x0, scratch)
                var s = 0L
                for (i in 0 until need) s += scratch[i].toLong() and 0xFF
                return s.toDouble() / need
            }
            fun meanFresh(y: Int): Double {
                val buf = ByteArray(need)
                m.get(y, x0, buf)
                var s = 0L
                for (i in buf.indices) s += buf[i].toLong() and 0xFF
                return s.toDouble() / buf.size
            }
            val fresh = DoubleArray(h) { meanFresh(it) }
            val reused = DoubleArray(h) { meanScratch(it) }
            assertArrayEquals("grow-only 缓冲必须逐位等价（累加与除数都用本次 need，不能用 scratch.size）", fresh, reused, 0.0)

            val yFrom = 13
            val yTo = h - 19
            val minRun = 4
            val maxRun = 40
            val whole = GridAlign.brightBands(fresh, yFrom, yTo, minRun, maxRun)
            val windowed = DoubleArray(yTo - yFrom + 1) { meanScratch(yFrom + it) }
            assertArrayEquals("限定行窗后窗内剖面应与整帧剖面逐位相同", fresh.copyOfRange(yFrom, yTo + 1), windowed, 0.0)
            val restricted = GridAlign.brightBands(windowed, 0, windowed.size - 1, minRun, maxRun).map { it + yFrom }
            assertTrue("合成帧须真检出亮带，否则下面那条相等断言是空对空", whole.isNotEmpty())
            assertEquals("只量 yFrom..yTo 与整帧扫描给出的带中心必须一致", whole, restricted)
        } finally {
            m.release()
        }
    }

    /**
     * 复用缓冲唯一的真陷阱（实测语义，改的人必须知道）：`Mat.get(row, col, buf)` 是**线性**拷贝
     * `buf.size` 字节，不按行截断 ⇒ 缓冲比本行剩余长时会读到**下一行**的像素（末行则只拷得下的那些，
     * 余字节保持旧值且不抛异常）。
     */
    @Test
    fun `Mat get 用超长缓冲读到的是下一行`() {
        val m = Mat(4, 4, CvType.CV_8UC3, Scalar.all(0.0))
        for (r in 0 until 4) {
            val row = ByteArray(4 * 3) { ((r + 1) * 10).toByte() }
            m.put(r, 0, row)
        }
        try {
            val over = ByteArray(2 * 4 * 3)
            m.get(1, 0, over)
            assertArrayEquals("前 12 字节是本行、后 12 字节已经是下一行", over, (List(12) { 20.toByte() } + List(12) { 30.toByte() }).toByteArray())
            val atLast = ByteArray(2 * 4 * 3) { (-77).toByte() }
            val rc = m.get(3, 0, atLast)
            assertTrue("末行超长读只拷得下的字节（实测 rc=${rc}）且不抛异常", rc < atLast.size)
            assertTrue("拷不到的尾部保持调用方旧值", atLast[atLast.size - 1] == (-77).toByte())
        } finally {
            m.release()
        }
    }
}
