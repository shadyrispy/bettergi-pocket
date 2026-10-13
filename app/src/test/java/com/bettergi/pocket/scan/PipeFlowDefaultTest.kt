package com.bettergi.pocket.scan

import com.bettergi.pocket.capture.CapturedBgrFrame
import com.bettergi.pocket.capture.FrameSource
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar

/**
 * ★ A③（#69 收尾，2026-10-13）：管道深度的**流程级默认**（"①流程属性"形态）接线。
 *
 * 钉三件事，都是"错了不会报错、只会静默变慢或变危险"的那种：
 * 1. 默认只给**读背包**的两条（artifact_scan / weapon_scan）。写类流程（artifact_lock /
 *    auto_equip）按构造不在表里 —— 它们要在**下一格开头**读回本格投票位来决定点不点写操作，
 *    而管道下投票位滞后 ≤pipeDepth 格；角色扫描同理（一格多帧 + 中途分支，#262）。
 * 2. 显式 `timing "pipe=N"` 必须**压过**流程默认。这是 #69 正反序 A/B 与"管道出问题退回同步"
 *    唯一的把手；把手丢了的话，`pipe=0` 会被默认悄悄升回 2，两臂跑的是同一档。
 * 3. 深度 >0 必须同时把 `crop` 打开（消费者只拿得到裁片），与 `TimingOverrides` 的 `pipe` 分支同口径。
 *
 * 收益侧的实测依据（不在这里重测，只登记默认值来源）：华为 −10.8%（反序对，两臂做功逐位相同）、
 * BS 2244 −5.7%，两序同向；等价性由 BS/华为各四臂导出全字节相同确认。
 * 见 dsl/docs/scan-pipeline-20261006.md §16.10–§16.13。
 */
class PipeFlowDefaultTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            nu.pattern.OpenCV.loadLocally()
            Mat(2, 2, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0)).release()
        }
    }

    private class NoopFrameSource : FrameSource {
        override fun markActionAt(timestampMs: Long) = Unit
        override suspend fun grabFresh(afterTimestampMs: Long, timeoutMs: Long): Mat =
            throw UnsupportedOperationException("流程默认判定不抓帧")

        override fun newFreshnessScope(): FrameSource = this
        override fun acquireLatestBgr(): CapturedBgrFrame? = null
        override fun discardLatestImages() = Unit
        override fun capturedSize(): Pair<Int, Int>? = null
        override fun isRunning(): Boolean = false
        override fun release() = Unit
    }

    private class NoopActions : ScanEngine.ActionGateway {
        override fun click(x: Int, y: Int, durationMs: Long) = true
        override fun tap(x: Int, y: Int) = true
        override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int) = true
        override fun back() = true
    }

    private class NoopListener : ScanListener {
        override fun onProgress(stage: String, data: Map<String, Any?>) = Unit
        override fun onNotice(level: String, text: String) = Unit
        override fun onFinished(summary: String) = Unit
    }

    private fun profile(): ScreenProfile {
        var dir = java.io.File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val f = java.io.File(dir, "src/main/assets/dsl/profiles_2244x1080.json")
            if (f.isFile) {
                val p = ScreenProfile(JSONObject(f.readText()))
                p.calibrate(2244, 1080)
                return p
            }
            dir = dir.parentFile ?: dir
        }
        error("profiles_2244x1080.json not found")
    }

    private fun engine(flowName: String) = ScanEngine(
        flowJson = JSONObject().put("flow", flowName),
        profile = profile(),
        frameSource = NoopFrameSource(),
        actions = NoopActions(),
        ocr = null,
        listener = NoopListener(),
        flowName = flowName,
    )

    /** TimingOverrides 是进程级全局；每条用例进出都归零，免得默认值串到别的用例。 */
    @After
    fun restore() = TimingOverrides.reset()

    @Test
    fun `表里只有读背包的两条，写类与角色按构造不在其中`() {
        assertEquals(
            "管道默认只能给「只读背包」的流程；写操作/角色一格多帧都不能开",
            setOf("artifact_scan", "weapon_scan"),
            ScanEngine.PIPE_DEFAULT_BY_FLOW.keys,
        )
    }

    @Test
    fun `五条流程各自拿到的默认深度`() {
        val expected = mapOf(
            "artifact_scan" to 2,
            "weapon_scan" to 2,
            "character_scan" to 0,
            "artifact_lock" to 0,
            "auto_equip" to 0,
        )
        for ((flow, want) in expected) {
            TimingOverrides.reset()
            engine(flow).applyFlowPipeDefault()
            assertEquals("flow=$flow 的管道深度", want, TimingOverrides.pipeDepth)
            // 深度 >0 必带 crop（消费者只拿得到裁片）；深度 0 不许顺带把裁片路径点开
            assertEquals("flow=$flow 的 crop 联动", want > 0, TimingOverrides.panelCrop)
        }
    }

    @Test
    fun `显式 pipe= 压过流程默认，同步臂仍可强制`() {
        // `pipe=0` = 把默认开了管道的 artifact_scan 拉回同步臂（A/B 与回退路）
        TimingOverrides.apply("pipe=0")
        assertTrue("显式给过 pipe ⇒ 必须标记为显式", TimingOverrides.pipeExplicit)
        engine("artifact_scan").applyFlowPipeDefault()
        assertEquals("显式 pipe=0 不得被流程默认升回 2", 0, TimingOverrides.pipeDepth)
        assertFalse("深度 0 时不该顺带开 crop", TimingOverrides.panelCrop)

        // `pipe=1` = 显式换一档，表里的 2 也不能覆盖它
        TimingOverrides.apply("pipe=1")
        engine("weapon_scan").applyFlowPipeDefault()
        assertEquals(1, TimingOverrides.pipeDepth)
        assertTrue(TimingOverrides.panelCrop)

        // 没给过 pipe = 走流程默认
        TimingOverrides.reset()
        engine("artifact_scan").applyFlowPipeDefault()
        assertEquals(2, TimingOverrides.pipeDepth)
    }

    /**
     * ★ 复核 #263 那批时补的一条：白名单必须是**硬闸**，不能只写在注释里。
     *
     * 写类流程（`artifact_lock`/`auto_equip`）要在下一格开头读回**本格**投票位来决定点不点写操作，
     * 角色扫描的判重账本同理 —— 管道下这些都滞后 ≤pipeDepth 格 ⇒ 拿旧状态点写按钮。
     * 有了 `pipeExplicit` 之后，"显式 `pipe=2`" 原本可以绕过整张表（`--es timing` 是生产可达的入口），
     * 所以 `applyFlowPipeDefault` 现在对不在表里的流程**无条件清零**，显式也不例外。
     */
    @Test
    fun `不在白名单里的流程，显式 pipe= 也不能开管道`() {
        for (flow in listOf("artifact_lock", "auto_equip", "character_scan")) {
            TimingOverrides.apply("pipe=2")
            engine(flow).applyFlowPipeDefault()
            assertEquals("flow=$flow 必须被强制回同步", 0, TimingOverrides.pipeDepth)
        }
    }
}
