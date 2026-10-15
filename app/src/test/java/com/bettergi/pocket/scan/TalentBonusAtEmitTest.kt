package com.bettergi.pocket.scan

import com.bettergi.pocket.capture.FrameSource
import com.bettergi.pocket.capture.FrameTimeoutException
import com.bettergi.pocket.recognition.name.GoodNames
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * #281：命座天赋加成的**扣减时点**。
 *
 * 缺陷形态是"同一件事在两条访问顺序里结果不同"，所以断言必须**把两条顺序都摆出来比**：
 * 反序格是「天赋页开局 ⇒ 先读天赋 ⇒ 才读命座」，旧实现在 `readTalent` 末尾读
 * `charConstellation`（那时恒 0）⇒ 一分不减 ⇒ 导出带上面板的 +3。
 * 只测"给定 c=6 能减对"是**空跑**（那半一直是对的），必须钉"c 晚到也对"。
 */
class TalentBonusAtEmitTest {

    companion object {
        // 构造 ScanEngine 会分配 `panelCropBuf = Mat()` ⇒ 单类运行（--tests 过滤）时
        // 必须先加载 native；全量跑里由先行的 ScreenStateJudgeTest 等类代劳，这里自足。
        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            nu.pattern.OpenCV.loadLocally()
            org.opencv.core.Mat(2, 2, org.opencv.core.CvType.CV_8UC3, org.opencv.core.Scalar.all(0.0)).release()
        }
    }

    private val dict: GoodNames by lazy {
        val f = File("src/main/assets/dsl/tools/mappings.json")
        assertTrue("找不到随包词典 ${f.absolutePath}", f.isFile)
        GoodNames.fromJson(JSONObject(f.readText()))
    }

    private class NoOpActions : ScanEngine.ActionGateway {
        override fun click(x: Int, y: Int, durationMs: Long) = true
        override fun tap(x: Int, y: Int) = true
        override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int) = true
        override fun back() = true
    }

    private class NoOpListener : ScanListener {
        override fun onProgress(stage: String, vars: Map<String, Any?>) = Unit
        override fun onFinished(reason: String) = Unit
    }

    /** 本用例一步取帧都不做（扣减是纯算术），FrameSource 只是构造签名要求。 */
    private class UnusedFrameSource : FrameSource {
        override suspend fun grabFresh(afterTimestampMs: Long, timeoutMs: Long): org.opencv.core.Mat =
            throw FrameTimeoutException(0, 0)

        override fun markActionAt(timestampMs: Long) = Unit
        override fun acquireLatestBgr() = throw FrameTimeoutException(0, 0)
        override fun discardLatestImages() = Unit
        override fun capturedSize(): Pair<Int, Int>? = null
        override fun isRunning(): Boolean = false
        override fun release() = Unit
    }

    private fun engine(panel: List<Int>, constellation: Int, key: String?): ScanEngine {
        val p = ScreenProfile(JSONObject(File("src/main/assets/dsl/profiles.json").readText()))
        p.calibrate(3200, 1440)
        val e = ScanEngine(
            flowJson = JSONObject().put("steps", JSONArray()),
            profile = p,
            frameSource = UnusedFrameSource(),
            actions = NoOpActions(),
            ocr = null,
            names = dict,
            listener = NoOpListener(),
            maxPages = 1,
            clock = { System.nanoTime() / 1_000_000 },
        )
        for (i in 0 until 3) e.charTalents[i] = panel[i]
        e.charConstellation = constellation
        e.lastCharKey = key
        e.talentBonusApplied = false
        return e
    }

    /** Barbara 词典里 c3=E、c5=Q（近似规则会两头判对，这里要的是**词典驱动**那条）。 */
    private fun bonusOf(key: String, c: Int) = dict.charAttrs.getValue(key).let { "${it.c3}/${it.c5}" }

    @Test
    fun `c 已先读到 减对`() {
        val e = engine(listOf(1, 4, 4), 6, "Barbara")
        e.applyTalentBonusForEmit()
        assertEquals("c=6 时 E/Q 各减 3（词典 c3=${bonusOf("Barbara", 6)}）", listOf(1, 1, 1), e.charTalents.toList())
    }

    @Test
    fun `c 晚到 也一样减 —— 反序相位不再漏扣`() {
        // 反序格的真实时序：readTalent 时 c 还是 0，随后才读到 c=6，最后 emit。
        val e = engine(listOf(1, 4, 4), 0, "Barbara")
        // （旧实现就是在 readTalent 末尾就地扣 ⇒ 这里会是 [1,4,4]）
        e.charConstellation = 6 // 命座页随后读到
        e.applyTalentBonusForEmit()
        assertEquals(
            "扣减点在入库侧 ⇒ 两条顺序必须给出同一个结果（#281 的全部意义）",
            listOf(1, 1, 1),
            e.charTalents.toList(),
        )
    }

    @Test
    fun `同一格只扣一次`() {
        val e = engine(listOf(10, 10, 10), 6, "Barbara")
        e.applyTalentBonusForEmit()
        val once = e.charTalents.toList()
        e.applyTalentBonusForEmit() // 重访 / 重复 emit 都必须无操作
        assertEquals("重复扣会静默把等级压低 3（导出仍是合法数字）⇒ 幂等位必须守住", once, e.charTalents.toList())
        assertEquals(listOf(10, 7, 7), once)
    }

    @Test
    fun `判重跳过格没读过天赋 保持全零`() {
        val e = engine(listOf(0, 0, 0), 6, "Barbara")
        e.applyTalentBonusForEmit()
        assertEquals("0 行是「没读到」不是「0 级」⇒ 不得凭空造数也不得减", listOf(0, 0, 0), e.charTalents.toList())
    }

    @Test
    fun `词典没有这个角色 按面板原值入库`() {
        val e = engine(listOf(10, 10, 10), 6, "NoSuchCharacter")
        e.applyTalentBonusForEmit()
        assertEquals(listOf(10, 10, 10), e.charTalents.toList())
    }

    // ── ★ 2026-10-15 #306：奇偶（Manekin/Manekina）无命座 ⇒ emit 时补归零 ──

    /**
     * 华为 10-15 实测（gt-recapture-20261015）：headerOnly 预读把名字 OCR 成「随机姓」
     * ⇒ resolveKey=null ⇒ 命座页守卫查不到 NO_CONSTELLATION_KEYS ⇒ c=6 原样入库。
     * emit 时 lastCharKey 已被属性页 parse 修正为权威值 ⇒ 在此归零。
     */
    @Test
    fun `奇偶 c 误读 6 在 emit 时归零`() {
        val e = engine(listOf(10, 10, 10), 6, "Manekin")
        e.applyTalentBonusForEmit()
        assertEquals("Manekin 无命座系统，c 必须是 0（GT 恒 0）", 0, e.charConstellation)
        assertEquals("奇偶无命座加成 ⇒ 天赋保持面板原值 10/10/10", listOf(10, 10, 10), e.charTalents.toList())
    }

    @Test
    fun `奇偶 Manekina 同样归零`() {
        val e = engine(listOf(10, 10, 10), 6, "Manekina")
        e.applyTalentBonusForEmit()
        assertEquals(0, e.charConstellation)
    }

    @Test
    fun `奇偶 c 已是 0 时无操作`() {
        val e = engine(listOf(10, 10, 10), 0, "Manekin")
        e.applyTalentBonusForEmit()
        assertEquals(0, e.charConstellation)
        assertEquals(listOf(10, 10, 10), e.charTalents.toList())
    }

    @Test
    fun `非奇偶角色不受新守卫影响`() {
        // 真有 c6 的角色不得被误归零（守卫只认 NO_CONSTELLATION_KEYS）
        val e = engine(listOf(1, 4, 4), 6, "Barbara")
        e.applyTalentBonusForEmit()
        assertEquals("Barbara 的 c6 是真值，必须保留", 6, e.charConstellation)
        assertEquals(listOf(1, 1, 1), e.charTalents.toList())
    }
}
