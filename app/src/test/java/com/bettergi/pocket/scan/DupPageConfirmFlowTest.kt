package com.bettergi.pocket.scan

import com.bettergi.pocket.capture.CapturedBgrFrame
import com.bettergi.pocket.capture.FrameSource
import com.bettergi.pocket.capture.FrameTimeoutException
import com.bettergi.pocket.scan.flows.ArtifactFlow
import com.bettergi.pocket.scan.flows.CharacterFlow
import com.bettergi.pocket.scan.flows.EquipFlow
import com.bettergi.pocket.scan.flows.LockFlow
import com.bettergi.pocket.scan.flows.WeaponFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import java.io.File

/**
 * #219：flow 的 `stopWhen.dupPageConfirm` 必须在**第一次回卷求值之前**就是 flow 声明的值。
 *
 * 为什么钉这条：回卷阈值已抬到「一页 + 一行」且重复计数跨翻页累积，所以 artifact/weapon 两条 flow
 * 声明 `dupPageConfirm=1`（一次命中就等于「整页 + 一行零新增」）。这个声明值读不到时回卷要两次命中
 * 才成立 ⇒ 每轮白扫一整页（真机日志里 `回卷判据：整页重复第 1 次（< 2）` 就是那个形状）。
 *
 * 页级确认只有一个求值函数 [dupRollbackConfirmed]（格级门与 `noteDupAndMaybeStop` 都走它），
 * 登记只有一个入口 `stopWhenRegisterDuplicateStreak`。本用例把 flow 的 stopWhen 步喂给 visit 入口
 * ([runVisitBody]) 后**立刻**求值一次 ⇒ 登记若晚于求值，第一条就红。
 */
class DupPageConfirmFlowTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCvNative() {
            // ScanEngine 构造里有 `panelCropBuf = Mat()` ⇒ 不装 native 就是 UnsatisfiedLinkError。
            nu.pattern.OpenCV.loadLocally()
            Mat(2, 2, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0)).release()
        }
    }

    /** 本用例只走 visit 入口的登记段：不点格、不取帧、不读坐标 ⇒ 帧源全 stub。 */
    private class StubFrameSource : FrameSource {
        override suspend fun grabFresh(afterTimestampMs: Long, timeoutMs: Long): Mat =
            throw FrameTimeoutException(afterTimestampMs, timeoutMs)

        override fun markActionAt(timestampMs: Long) = Unit
        override fun acquireLatestBgr(): CapturedBgrFrame? = null
        override fun discardLatestImages() = Unit
        override fun capturedSize(): Pair<Int, Int>? = 3200 to 1440
        override fun isRunning(): Boolean = true
        override fun release() = Unit
    }

    private class StubActions : ScanEngine.ActionGateway {
        override fun click(x: Int, y: Int, durationMs: Long) = true
        override fun tap(x: Int, y: Int) = true
        override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int) = true
        override fun back() = true
    }

    private class StubListener : ScanListener {
        override fun onProgress(stage: String, vars: Map<String, Any?>) = Unit
        override fun onFinished(reason: String) = Unit
    }

    private fun engine(): ScanEngine = ScanEngine(
        flowJson = JSONObject().put("steps", JSONArray()),
        profile = ScreenProfile(JSONObject()),
        frameSource = StubFrameSource(),
        actions = StubActions(),
        ocr = null,
        listener = StubListener(),
        clock = { 0L },
    )

    /** 走一次「整页零新增」的页级确认（每页开头 pagedGrid 会把 dupPageDecided 复位，这里照做）。 */
    private fun ScanEngine.confirmWholePage(dupLimit: Int = 7 * (3 + 1)): Boolean {
        dupLimitEffective = dupLimit
        dupPageDecided = false
        return dupRollbackConfirmed()
    }

    /** flow 声明 `dupPageConfirm=1` ⇒ 第一次「整页零新增」就断言回卷（登记必须早于求值）。 */
    @Test
    fun `flow declared dupPageConfirm is in effect before the first rollback evaluation`() {
        for ((grid, stopStep) in listOf(
            ArtifactFlow.GRID to ArtifactFlow.STOP_DUP,
            WeaponFlow.GRID to WeaponFlow.STOP_DUP,
        )) {
            val engine = engine()
            assertEquals("${grid}：登记之前读默认 2", 2, engine.dupPageConfirm)
            runBlocking {
                engine.runVisitBody(grid, 0, 0, 0, engine.profile, pipeEmit = null, pipeStop = stopStep) { _ -> }
            }
            assertEquals(
                "${grid}：visit 入口应按 flow 的 stopWhen 步登记",
                stopStep.optInt("dupPageConfirm"),
                engine.dupPageConfirm,
            )
            assertEquals("${grid}：streak 同源登记", stopStep.optInt("streak"), engine.charDupStreakLimit)
            assertTrue(
                "${grid}：dupPageConfirm=1 ⇒ 第一次整页零新增即断言回卷（读到默认 2 时要两次，每轮白扫一页）",
                engine.confirmWholePage(),
            )
        }
    }

    /** 负对照：flow **没声明** `dupPageConfirm` ⇒ 默认 2 不动，第一次命中只清计数继续翻页。 */
    @Test
    fun `flow without dupPageConfirm keeps the default of two whole pages`() {
        val undeclared = JSONObject()
            .put("do", "stopWhen")
            .put("mode", "duplicateStreak")
            .put("streak", 3)
        val engine = engine()
        runBlocking {
            engine.runVisitBody(
                ArtifactFlow.GRID, 0, 0, 0, engine.profile,
                pipeEmit = null, pipeStop = undeclared,
            ) { _ -> }
        }
        assertEquals("未声明 ⇒ 不得改默认值", 2, engine.dupPageConfirm)
        assertFalse("第一次整页零新增按默认判为滑空", engine.confirmWholePage())
        assertTrue("连续第二次整页零新增才断言回卷", engine.confirmWholePage())
    }

    /** visit 没有 stopWhen 步（标定流）⇒ 完全不介入，默认 2 保持。 */
    @Test
    fun `visit without a stopWhen step leaves the default untouched`() {
        val engine = engine()
        runBlocking { engine.runVisitBody("char_popup", 0, 0, 0, engine.profile) { _ -> } }
        assertEquals(2, engine.dupPageConfirm)
    }

    /**
     * canonical flow JSON（`dsl/json/flows`）声明的 `streak` / `dupPageConfirm` 必须与运行时那份
     * `STOP_DUP` 步逐值相同。
     *
     * 为什么要单独钉：flow JSON 已随 DSL 运行时下线（`ScriptRunner` 只造 `{"returnHome":…}`，
     * `DslAssetsIntegrityTest` 也明确不再比 flows 目录）⇒ JSON 里这两个数**没有代码读者**，
     * 生效的是 `scan/flows/` 目录里的 Kotlin 常量；两边谁改了都不通知对方，而 #219 的整条设计
     * （阈值抬一行 + 确认降到 1）就靠这两个数对齐。
     * 单独构建 app 模块（仓库里没有 dsl/）时本用例记为 **skipped**（不是 passed）。
     */
    @Test
    fun `canonical flow json and runtime stopWhen step declare the same duplicateStreak values`() {
        val found: File? = findFlowsDir()
        Assume.assumeTrue("仓库内未找到 dsl/json/flows ⇒ 本用例不适用（跳过，非通过）", found != null)
        val flowsDir = found!!
        val pairs = listOf(
            "artifact_scan.json" to ArtifactFlow.STOP_DUP,
            "weapon_scan.json" to WeaponFlow.STOP_DUP,
            "character_scan.json" to CharacterFlow.STOP_DUP,
            "artifact_lock.json" to LockFlow.STOP_DUP,
            "auto_equip.json" to EquipFlow.STOP_DUP,
        )
        for ((name, runtimeStep) in pairs) {
            val json = JSONObject(File(flowsDir, name).readText())
            val declared = firstDuplicateStreakStep(json)
                ?: error("${name}：找不到 mode=duplicateStreak 的 stopWhen 步（引擎按它登记 dupPageConfirm）")
            assertEquals(
                "${name}：streak 与运行时 STOP_DUP 不一致",
                declared.optInt("streak"),
                runtimeStep.optInt("streak"),
            )
            assertEquals(
                "${name}：dupPageConfirm 与运行时 STOP_DUP 不一致",
                declared.optInt("dupPageConfirm"),
                runtimeStep.optInt("dupPageConfirm"),
            )
        }
    }

    private fun findFlowsDir(): File? {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(5) {
            val candidate = File(dir, "dsl/json/flows")
            if (File(candidate, "artifact_scan.json").isFile) return candidate
            dir = dir.parentFile ?: return null
        }
        return null
    }

    /** 递归找第一条 `do=stopWhen && mode=duplicateStreak` 的步（auto_equip 把它埋在 foreach 里）。 */
    private fun firstDuplicateStreakStep(node: Any?): JSONObject? = when (node) {
        is JSONObject ->
            if (node.optString("do") == "stopWhen" && node.optString("mode") == "duplicateStreak") node
            else node.keys().asSequence().mapNotNull { firstDuplicateStreakStep(node.opt(it)) }.firstOrNull()

        is JSONArray ->
            (0 until node.length()).mapNotNull { firstDuplicateStreakStep(node.opt(it)) }.firstOrNull()

        else -> null
    }
}
