package com.bettergi.pocket.scan

import com.bettergi.pocket.core.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar

/**
 * #169 行文本自标定（[GridAlign.textLineCrop]）守门。
 *
 * 本组用例锁 [GridAlign.textLineCrop]（与 [GridAlign.rowPhase] 同属"绝对行相位"家族，见该文件常量段）。
 *
 * 病灶（真机 2026-10-02 三轮 2244 探针 + 把真截图整体平移的离线复算，两侧同因）：
 * `set_filter_popup` 走 **rec-only**（没有 det），而名义行带远高于一行字 —— 2244 实测行带
 * 90px 里只有 [23,49] 那 27px 是字，且**贴带顶**。列表落点 δ 一出这个非对称窗（约 [−23,+40]），
 * 整页每一行都被切成"半截字 + 大片空白"，压到 rec 的 48px 工作高后成**确定性乱码**（同页
 * 两次读数一字不差；当初误判成"灰化低对比度 / 词典缺口"）。离线复算 `_dev/probe_169_anchor.py`：
 * δ=−30 时 16 行只对 1 行、δ=±40~50 全页归零；换本裁剪同条件 12~14 行。
 *
 * 用例锁两条，第二条更要紧：
 * 1. **跟得上**：字带完整落在搜索窗内时，相位＝真实落点、裁剪＝实测字带±裕量（相位同时加到
 *    checkbox 读数与点击上，见 `matchFilterRow`）；
 * 2. **不错行**：δ 取遍 ±半行距，裁剪**永远不许**吃进邻行的字。"读到 B 套、点在 A 行"＝静默
 *    选错套装，比整页乱码坏得多。免锚写法（取离行带中心最近的带）在 |δ|>45 时会把整页统一
 *    错一行 —— 这就是 [ANCHOR] 必须取自 profile 实测算值的原因。
 */
class GridAlignTextLineTest {

    companion object {
        private const val BAND = 90 // 名义行带高（2244 profile rowHeight）
        private const val PITCH = 102 // 行距（2244 profile rowPitch）
        private const val ANCHOR = 36 // 名义行顶 → 字带中心（2244 profile labelAnchor，实测 35.5..36.5）
        private const val INK_TOP = 23 // 字带在行带内的真实上下沿（2244 逐行实测 [22,49]）
        private const val INK_BOT = 50 // 不含
        private const val ROWS = 8
        private const val X0 = 283
        private const val XW = 216
        private const val FIRST = 157
        private const val PAD = BAND / 16 // 与 GridAlign.textLineCrop 同式
        private const val HALF = PITCH / 2 // 选取窗（±半行距），与 GridAlign.textLineCrop 同式

        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            nu.pattern.OpenCV.loadLocally()
            Mat(2, 2, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0)).release()
        }
    }

    /**
     * 合成面板：深底 48，每行 5 个 26px 字块（**最长上墨段 0.12×框宽**，与真字同形 —— 画成一条
     * 连续横带会被 [GridAlign.isBarShape] 判成横条），整页停在 δ 处；亮度可降到灰化档。
     */
    private fun panel(delta: Int, bright: Int = 220, rows: Int = ROWS): Mat {
        val m = Mat(1080, 2244, CvType.CV_8UC3, Scalar(48.0, 48.0, 48.0))
        try {
            for (r in 0 until rows) {
                val y0 = maxOf(0, FIRST + r * PITCH + INK_TOP + delta)
                val y1 = minOf(m.rows(), FIRST + r * PITCH + INK_BOT + delta)
                if (y1 - y0 <= 0) continue
                val sub = Mat(m, Rect(X0, y0, 26, y1 - y0))
                try {
                    sub.setTo(Scalar(bright.toDouble(), bright.toDouble(), bright.toDouble()))
                } finally {
                    sub.release()
                }
                for (g in 1 until 5) {
                    val b = Mat(m, Rect(X0 + g * 30, y0, 26, y1 - y0))
                    try {
                        b.setTo(Scalar(bright.toDouble(), bright.toDouble(), bright.toDouble()))
                    } finally {
                        b.release()
                    }
                }
            }
            return m
        } catch (e: Throwable) {
            m.release()
            throw e
        }
    }

    /** 本行字带在帧坐标的上下沿（不含上沿→含下沿）。 */
    private fun ink(row: Int, delta: Int): IntRange =
        (FIRST + row * PITCH + INK_TOP + delta) until (FIRST + row * PITCH + INK_BOT + delta)

    private fun crop(frame: Mat, row: Int, anchor: Int = ANCHOR, pitch: Int = PITCH) =
        GridAlign.textLineCrop(frame, X0, XW, FIRST + row * PITCH, BAND, pitch, anchor)

    // ---- 1) 安全性：δ 取遍半个行距，裁到的永远是**某一张卡的完整字带** ----
    //
    // 不变式不是"必须裁本行"，而是"**读数与点击指向同一张卡**"：相位同时加到 readRect 与 clickY，
    // 所以裁到相邻那张卡本身不错（它就该点它）；错的是裁半张（跨两张、或截掉上下沿）——
    // 那才会"读出来是拼的字、点下去落在两张之间"。δ=−53 的尾页实测就是靠这条将相邻卡读正的。

    @Test
    fun `落点取遍半个行距时裁剪永远是某一张卡的完整字带`() {
        for (delta in -HALF..HALF) {
            val frame = panel(delta)
            try {
                for (row in 1 until ROWS - 1) {
                    val c = crop(frame, row) ?: continue
                    // 找"裁剪归属哪一张卡"：某行 r 的字带**完整**等于裁剪±PAD 才算数
                    val owner = (0 until ROWS).firstOrNull { r ->
                        val b = ink(r, delta)
                        c.crop.y == b.first - PAD && c.crop.bottom == b.last + 1 + PAD
                    }
                    assertTrue(
                        "δ=$delta 行$row 裁到半张/跨张：crop=[${c.crop.y},${c.crop.bottom}) ph=${c.phase}",
                        owner != null,
                    )
                    assertTrue(
                        "δ=$delta 行$row 相位与所裁卡不一致：ph=${c.phase} 应在 ±$HALF 内",
                        Math.abs(c.phase) <= HALF,
                    )
                }
            } finally {
                frame.release()
            }
        }
    }

    @Test
    fun `两条候选离锚一样近时判没量准而不是硬选一张`() {
        // δ=±半行距：本行与邻行的字心**对称**落在 ±HALF 里，"离锚最近"只差 1~2px ⇒ 宁可不读
        for (delta in listOf(-HALF, HALF)) {
            val frame = panel(delta)
            try {
                assertNull("δ=$delta 属歧义区，不许硬裁", crop(frame, 1))
            } finally {
                frame.release()
            }
        }
    }

    // ---- 2) 准确性：字带完整落在窗内时，相位与裁剪都量得准 ----

    @Test
    fun `窗内相位等于真实落点`() {
        // 字带 [23,50) 完整进窗（剖面窗 = 锚 ±(HALF + BAND/4)）⇒ δ ∈ [−60, +60] 都量得全
        for (delta in -30..30 step 10) {
            val frame = panel(delta)
            try {
                val c = crop(frame, 2) ?: error("δ=$delta 应量到字带")
                assertEquals("δ=$delta 相位", delta.toLong(), c.phase.toLong())
                assertEquals("δ=$delta 裁剪上下沿", (ink(2, delta).first - PAD).toLong(), c.crop.y.toLong())
                assertEquals("δ=$delta 裁剪高", (INK_BOT - INK_TOP + 2 * PAD).toLong(), c.crop.height.toLong())
            } finally {
                frame.release()
            }
        }
    }

    @Test
    fun `零落点时裁剪就是实测字带加裕量`() {
        val frame = panel(0)
        try {
            val c = crop(frame, 3)!!
            assertEquals(IntRect(X0, FIRST + 3 * PITCH + INK_TOP - PAD, XW, INK_BOT - INK_TOP + 2 * PAD), c.crop)
            assertEquals(0L, c.phase.toLong())
        } finally {
            frame.release()
        }
    }

    /**
     * 真机 2244 尾页复刻（tt2/1.png，δ=−53：列表滑到底停不住，整页停在格点之外）。
     * 装机版的 ±45 窗在 8 帧 × 16 格里**一个都没量到**，而名义带"读出"的 16 个全是
     * 上一张卡的下半截字（读 B 点 A）。半行距窗应当裁到**下面那张卡**的完整字带（ph≈+49）。
     */
    @Test
    fun `落点超出半格时改裁相邻那张卡而不是回退名义带`() {
        val frame = panel(-53)
        try {
            val c = crop(frame, 3) ?: error("δ=−53 应裁到下面那张卡（ph≈+49）")
            assertTrue("ph=${c.phase}", c.phase in 46..50)
            val below = ink(4, -53) // 下面那张卡的字带
            assertEquals("裁上沿", (below.first - PAD).toLong(), c.crop.y.toLong())
            assertEquals("裁下沿", (below.last + 1 + PAD).toLong(), c.crop.bottom.toLong())
        } finally {
            frame.release()
        }
    }

    // ---- 3) 保守回退：缺标定 / 空行 / 字带在窗 ----

    @Test
    fun `未标定锚与非正几何一律不裁`() {
        val frame = panel(0)
        try {
            assertNull("锚缺键（-1）⇒ 不裁剪，调用方按'量不到 ⇒ 不读不点'处理（不许回退名义带）", crop(frame, 1, anchor = -1))
            assertNull(crop(frame, 1, pitch = 0))
            assertNull(GridAlign.textLineCrop(Mat(), X0, XW, FIRST, BAND, PITCH, ANCHOR))
        } finally {
            frame.release()
        }
    }

    @Test
    fun `空行带不造假字带`() {
        val frame = panel(0, rows = 2) // 第 3 行起没有字
        try {
            assertNull(crop(frame, 4))
        } finally {
            frame.release()
        }
    }

    @Test
    fun `字带整条越出搜索窗时不硬裁`() {
        val m = Mat(1080, 2244, CvType.CV_8UC3, Scalar(48.0, 48.0, 48.0))
        val sub = Mat(m, Rect(X0, FIRST + 88, 140, 27)) // 名义行带下沿之外：+88..+114
        sub.setTo(Scalar(220.0, 220.0, 220.0))
        sub.release()
        try {
            assertNull("离锚超过半行距 ⇒ 判没量到", crop(m, 0))
        } finally {
            m.release()
        }
    }

    @Test
    fun `灰化低对比度行仍能量到`() {
        // 尾部套是灰的：字带 96 对底 48（峰−中位 48 ≫ 门 12），落点照旧量得出
        val frame = panel(-20, bright = 96)
        try {
            val c = crop(frame, 3) ?: error("灰化行也该量到字带")
            assertEquals((-20).toLong(), c.phase.toLong())
        } finally {
            frame.release()
        }
    }

    @Test
    fun `分位剖面扛得住稀疏短名`() {
        // 2 字短名只占行宽 1/6：均值会被背景抹平，p95 只要 >5% 覆盖就能拉起对比度
        val m = Mat(120, XW, CvType.CV_8UC3, Scalar(48.0, 48.0, 48.0))
        val sub = Mat(m, Rect(0, 40, 36, 27)) // 字带 abs 40..66，中心 53
        sub.setTo(Scalar(220.0, 220.0, 220.0))
        sub.release()
        try {
            // yTop=10、锚 36 ⇒ 锚位 46；字心 53 ⇒ 相位 +7
            val c = GridAlign.textLineCrop(m, 0, XW, 10, BAND, PITCH, ANCHOR) ?: error("短名行也该量到字带")
            assertEquals(7L, c.phase.toLong())
            assertTrue("裁剪没盖住字带：${c.crop}", c.crop.y <= 40 && c.crop.bottom >= 67)
        } finally {
            m.release()
        }
    }

    /** 一行"字"：`count` 个 26px 方块、块间留空 —— 最长上墨段＝一个字宽（26/216≈0.12）。 */
    private fun glyphs(m: Mat, x0: Int, yTop: Int, count: Int, bright: Double) {
        for (i in 0 until count) {
            val sub = Mat(m, Rect(x0 + i * 30, yTop, 26, 26))
            sub.setTo(Scalar(bright, bright, bright))
            sub.release()
        }
    }

    /**
     * 真机 f58（静止帧、δ=+31）暴露的缺陷：行卡片的**上边框线**也是一条墨迹带，
     * 而且它比文字更靠近锚（边框 ph=−19、文字 ph=+31）⇒ 单看"离锚最近"会裁到一条线上，
     * rec 读空 —— 这就是第一轮探针里"整页空 13 行"的来源。
     * 本用例把那个形状复刻出来：一条满幅连续的横条压在锚上方，真字带在 +31 处。
     */
    @Test
    fun `行分隔横条不许冒充文字行`() {
        val m = Mat(1080, 2244, CvType.CV_8UC3, Scalar(48.0, 48.0, 48.0))
        val y = FIRST + 3 * PITCH
        // 横条：整窗连续上墨（最长段/框宽 = 1.00），离锚 −19
        var sub = Mat(m, Rect(X0, y + ANCHOR - 19 - 6, XW, 12))
        sub.setTo(Scalar(150.0, 150.0, 150.0))
        sub.release()
        // 文字：四个字块，最长段只到一个字宽（0.12），离锚 +31
        glyphs(m, X0, y + ANCHOR + 31 - 13, 4, 220.0)
        try {
            val c = GridAlign.textLineCrop(m, X0, XW, y, BAND, PITCH, ANCHOR) ?: error("横条被当成唯一候选，裁不出")
            assertTrue("该裁到 +31 的字带，实际 ph=${c.phase}", c.phase in 28..34)
        } finally {
            m.release()
        }
    }

    @Test
    fun `横条判据看横向连续性不看覆盖率`() {
        // 实测（2244 真机 38 帧 608 次）：文字带含 2 字短名都 ≤0.13×框宽；
        // 而同一根线被复选框打断时覆盖率只剩 0.45~0.81 —— 覆盖率那条判据会放它过去。
        assertTrue("一个字宽不许判横条", !GridAlign.isBarShape(0.13))
        assertTrue(!GridAlign.isBarShape(0.29))
        assertTrue("连续半幅才是横条", GridAlign.isBarShape(0.30))
        assertTrue(GridAlign.isBarShape(0.44))
        assertTrue(GridAlign.isBarShape(1.00))
    }

    /**
     * 真机 f60 尾页四行（`谐律异想断章 / 沙上楼阁史话 / 回声之林夜话 / 来歆余响`）的成因：
     * 这些行的字**比横条暗**（字 p95≈135~141、线 211），而起带阈是从整窗剖面的 峰−中位 推的 ——
     * 横条把 峰 抬到 211 ⇒ 阈 = 93+0.4×118 = 140 > 135 ⇒ 字**一根都进不了带**，于是"只剩横条"、
     * 整行读空。**认出横条不够，还得把它从阈值统计里摘掉再算第二遍**（摘掉后阈 = 110 < 135）。
     */
    @Test
    fun `横条不许把起带阈抬到把暗字挤出带外`() {
        val bg = 93.0
        val m = Mat(1080, 2244, CvType.CV_8UC3, Scalar(bg, bg, bg))
        val y = FIRST + 6 * PITCH
        var sub = Mat(m, Rect(X0, y + ANCHOR + 30 - 5, XW, 12))
        sub.setTo(Scalar(211.0, 211.0, 211.0))
        sub.release()
        glyphs(m, X0, y + ANCHOR - 22 - 13, 4, 135.0)
        try {
            val c = GridAlign.textLineCrop(m, X0, XW, y, BAND, PITCH, ANCHOR)
                ?: error("暗字被横条抬起来的阈值挤掉了（ph 应≈−22）")
            assertTrue("该裁到 −22 的字带，实际 ph=${c.phase}", c.phase in -25..-19)
        } finally {
            m.release()
        }
    }

    // ---- 4) 剖面纯函数：对比度门与宽度门 ----

    @Test
    fun `一条字带对应一条墨迹带`() {
        val prof = DoubleArray(90) { if (it in INK_TOP until INK_BOT) 220.0 else 48.0 }
        assertEquals(listOf(INK_TOP..(INK_BOT - 1)), GridAlign.inkRuns(prof, 5, 45, GridAlign.inkThreshold(prof)!!))
    }

    @Test
    fun `对比度不过门的空剖面不产出墨迹带`() {
        val prof = DoubleArray(90) { 48.0 + (it % 5) } // 峰−中位 = 4 < CONTRAST_MIN
        assertNull("阈值都该给不出", GridAlign.inkThreshold(prof))
    }

    @Test
    fun `宽度出格的碎片与整片高亮都被丢弃`() {
        val thin = DoubleArray(90) { if (it == 40) 220.0 else 48.0 }
        assertEquals("1px 噪声碎片 < minRun", 0, GridAlign.inkRuns(thin, 5, 45, 150.0).size)
        val fat = DoubleArray(90) { if (it in 10..80) 220.0 else 48.0 }
        assertEquals("71px 连片＝整行高亮而非一行字 > maxRun", 0, GridAlign.inkRuns(fat, 5, 45, 150.0).size)
    }
}
