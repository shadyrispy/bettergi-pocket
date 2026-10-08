package com.bettergi.pocket.scan

import com.bettergi.pocket.capture.CapturedBgrFrame
import com.bettergi.pocket.capture.FrameSource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar

/**
 * P0-A/P0-C 缺席证明门禁（2026-10-08，GS 对齐 lock_manager.rs 的 missed/skipped 分流）：
 *
 * `planSummary` 对**从未 planDone** 的计划项按处境分流：
 * - 正常走查完整（两项标志未置位）⇒ NotFound（"背包里确实没有这件"，可重造方案）；
 * - `lockPlanAborted`（流程异常中止：enterScreen/assertScreen 未达等）⇒ Skipped；
 * - `lockFilterPartial`（筛选部分命中，剩余目标在遮蔽区）⇒ Skipped（需清筛重跑）。
 *
 * 直接驱动 `ScanEngine.planSummary`（internal），读 `manageResults`。
 * 最小桩：planSummary 只碰 vars.plan / manageResults / emitManageSummary——
 * frameSource/actions 永不被调。
 */
class PlanSummaryAbsenceGateTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            nu.pattern.OpenCV.loadLocally()
            Mat(2, 2, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0)).release()
        }
    }

    private class NoopFrameSource : FrameSource {
        override fun markActionAt(timestampMs: Long) {}
        override suspend fun grabFresh(afterTimestampMs: Long, timeoutMs: Long): Mat =
            throw UnsupportedOperationException("planSummary 不抓帧")
        override fun newFreshnessScope(): FrameSource = this
        override fun acquireLatestBgr(): CapturedBgrFrame? = null
        override fun discardLatestImages() {}
        override fun capturedSize(): Pair<Int, Int>? = null
        override fun isRunning(): Boolean = false
        override fun release() {}
    }

    private class NoopActions : ScanEngine.ActionGateway {
        override fun click(x: Int, y: Int, durationMs: Long): Boolean = false
        override fun tap(x: Int, y: Int): Boolean = false
        override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean = false
        override fun back(): Boolean = false
    }

    private class NoopListener : ScanListener {
        override fun onProgress(stage: String, data: Map<String, Any?>) {}
        override fun onNotice(level: String, text: String) {}
        override fun onFinished(summary: String) {}
    }

    private fun engineWithPlan(planKeys: List<String>): ScanEngine {
        val plan = planKeys.map { k ->
            JSONObject().put("set_key", k).put("slot", "flower").put("want_lock", true)
        }
        return ScanEngine(
            flowJson = JSONObject().put("flow", "artifact_lock"),
            profile = ScreenProfile(JSONObject()),
            frameSource = NoopFrameSource(),
            actions = NoopActions(),
            ocr = null,
            listener = NoopListener(),
            plan = plan,
        )
    }

    @Test
    fun `正常走查完整 未绑定项记 NotFound`() {
        val e = engineWithPlan(listOf("Set_A", "Set_B"))
        try {
            e.planSummary()
            assertEquals(listOf("NotFound", "NotFound"), e.manageResults.map { it.third })
        } finally {
            e.vars.lockPlanAborted = false
            e.vars.lockFilterPartial = false
        }
    }

    @Test
    fun `流程异常中止 未绑定项记 Skipped`() {
        val e = engineWithPlan(listOf("Set_A", "Set_B"))
        try {
            e.vars.lockPlanAborted = true
            e.planSummary()
            assertEquals(listOf("Skipped", "Skipped"), e.manageResults.map { it.third })
        } finally {
            e.vars.lockPlanAborted = false
        }
    }

    @Test
    fun `筛选部分命中 未绑定项记 Skipped`() {
        val e = engineWithPlan(listOf("Set_A", "Set_B", "Set_C"))
        try {
            e.vars.lockFilterPartial = true
            e.planSummary()
            assertEquals(listOf("Skipped", "Skipped", "Skipped"), e.manageResults.map { it.third })
        } finally {
            e.vars.lockFilterPartial = false
        }
    }

    @Test
    fun `已 planDone 的项不受门禁影响`() {
        val e = engineWithPlan(listOf("Set_A", "Set_B"))
        try {
            e.vars.consumedPlanIndexes.add(0) // 模拟 planMatch+planDone 走完第 0 项
            e.planSummary()
            assertEquals(1, e.manageResults.size)
            assertEquals("NotFound", e.manageResults[0].third)
        } finally {
            e.vars.consumedPlanIndexes.clear()
        }
    }

    @Test
    fun `无计划时 planSummary 直接返回`() {
        val e = engineWithPlan(emptyList())
        e.planSummary()
        assertEquals(0, e.manageResults.size)
    }
}
