package com.bettergi.pocket.scan

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import com.bettergi.pocket.capture.CapturedBgrFrame
import com.bettergi.pocket.capture.FrameSource

/**
 * **S4a：运行中遇到「同包名 MiHoYo SDK 登录/切号页」时必须干净停手**（2026-10-15）。
 *
 * 为什么必须停手而不是继续：该页与游戏**同包名**（`com.miHoYo.Yuanshen/com.miHoYo.sdk.platform.SdkActivity`）
 * ⇒ 前台闸按包名判恒为"是原神"、放行 ⇒ 引擎会在登录页上继续点入口、跑锚点，直到
 * `anchor not reached` 崩轮（历史事故：2026-10-14 06:10 那条 `/元素/`）。
 * 项目红线：一旦冒出登录表单**立刻交回用户**，绝不自动填。
 *
 * 为什么要单测：探针值来自 Activity 事件缓存（`lastAppClass`），**上机只能撞 1.4s 的瞬态**
 * （实测 `hw283_b2b3_gate_after_fix.txt`：SdkActivity 态约 1.4s 后被 RelativeLayout 替换）
 * ⇒ 设备侧只能验"起跑闸拒绝"，验不到"运行中查探针"这条路径。本类用可注入探针把
 * **三条消费点的行为**钉住（returnToHome / enterScreen / pagedGrid 页首）。
 */
class SdkLoginPageTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            nu.pattern.OpenCV.loadLocally()
            Mat(2, 2, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0)).release()
        }
    }

    /** 恒返"是登录页"的探针。 */
    private class LoginProbe(private val value: Boolean?) {
        fun invoke(): Boolean? = value
    }

    private class NoopFrameSource : FrameSource {
        var grabs = 0
        override fun markActionAt(timestampMs: Long) = Unit
        override suspend fun grabFresh(afterTimestampMs: Long, timeoutMs: Long): Mat {
            grabs++
            return Mat(1080, 2244, CvType.CV_8UC3, Scalar(40.0, 45.0, 60.0))
        }
        override fun newFreshnessScope(): FrameSource = this
        override fun acquireLatestBgr(): CapturedBgrFrame? = null
        override fun discardLatestImages() = Unit
        override fun capturedSize(): Pair<Int, Int> = 2244 to 1080
        override fun isRunning(): Boolean = true
        override fun release() = Unit
    }

    private class RecordingActions : ScanEngine.ActionGateway {
        val taps = ArrayList<Pair<Int, Int>>()
        val clicks = ArrayList<Pair<Int, Int>>()
        val swipes = ArrayList<List<Int>>()
        var backs = 0
        override fun click(x: Int, y: Int, durationMs: Long): Boolean { clicks += x to y; return true }
        override fun tap(x: Int, y: Int): Boolean { taps += x to y; return true }
        override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean {
            swipes += listOf(fromX, fromY, toX, toY); return true
        }
        override fun back(): Boolean { backs++; return true }
    }

    private class StubListener : ScanListener {
        override fun onProgress(stage: String, vars: Map<String, Any?>) = Unit
        override fun onFinished(reason: String) = Unit
    }

    private fun profile(): ScreenProfile {
        var dir = java.io.File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val c = java.io.File(dir, "src/main/assets/dsl")
            if (c.isDirectory) {
                val p = ScreenProfile(JSONObject(java.io.File(c, "profiles_2244x1080.json").readText()))
                p.calibrate(2244, 1080)
                return p
            }
            dir = dir.parentFile ?: return@repeat
        }
        error("assets/dsl not found")
    }

    private fun engine(
        src: FrameSource,
        actions: RecordingActions,
        login: Boolean?,
    ): ScanEngine = ScanEngine(
        flowJson = JSONObject().put("steps", JSONArray()),
        profile = profile(),
        frameSource = src,
        actions = actions,
        ocr = null,
        listener = StubListener(),
        sdkLoginOnTop = { login },
        clock = { System.nanoTime() / 1_000_000 },
    )

    /**
     * **`returnToHome` 第一件事就是查探针**：查到 ⇒ 一次都不点（零 tap/click/back）+ 停手原因正确。
     */
    @Test
    fun `returnToHome 见登录页即停手且零点击`() {
        val src = NoopFrameSource()
        val acts = RecordingActions()
        val engine = engine(src, acts, login = true)
        runBlocking {
            withTimeout(20_000) { engine.returnToHome() }
        }
        val vars = engine.vars
        assertTrue("判出登录页后不应有任何点击（tap=${acts.taps} click=${acts.clicks} back=${acts.backs}）",
            acts.taps.isEmpty() && acts.clicks.isEmpty() && acts.backs == 0)
        assertEquals("sdkLoginRequired", vars.stopReason)
        assertTrue("应置 stopRequested", vars.stopRequested)
        assertEquals("不应抓帧（探针在抓帧之前）", 0, src.grabs)
    }

    /**
     * 探针返回 `null`（无障碍未连/读不到类名）⇒ **放行**，与全仓 fail-open 口径一致
     * （硬关会把正常游戏误拒 —— 游戏的窗口也会短暂报非 Activity 类名）。
     */
    @Test
    fun `探针读不到时不拦（fail-open）`() {
        val src = NoopFrameSource()
        val acts = RecordingActions()
        val engine = engine(src, acts, login = null)
        runBlocking {
            // atHomeScreen 需要模板（未注册时返回 null ⇒ 归位直接返回），这条只验"没被登录页拦"：
            // 因此断言"探针没置 sdkLoginRequired"而不是"跑了多久"
            withTimeout(20_000) { runCatching { engine.returnToHome() } }
        }
        assertFalse("null 不应触发 sdkLoginRequired", engine.vars.stopReason == "sdkLoginRequired")
    }

    /** 探针返回 `false`（不是登录页）⇒ 同样放行。 */
    @Test
    fun `非登录页不拦`() {
        val src = NoopFrameSource()
        val acts = RecordingActions()
        val engine = engine(src, acts, login = false)
        runBlocking {
            withTimeout(20_000) { runCatching { engine.returnToHome() } }
        }
        assertFalse(engine.vars.stopReason == "sdkLoginRequired")
    }

    /**
     * **`pagedGrid` 页首**：查探针 ⇒ 停在 `sdkLoginRequired`、且**不抓格**（不白烧一页）。
     */
    @Test
    fun `pagedGrid 页首见登录页即停且不跑格子`() {
        val src = NoopFrameSource()
        val acts = RecordingActions()
        val engine = engine(src, acts, login = true)
        runBlocking {
            withTimeout(30_000) {
                runCatching {
                    engine.pagedGridNative(
                        gridKey = "weapon_backpack",
                        scrollToTop = true,
                        visitBody = { _, _, _, _ -> },
                    )
                }
            }
        }
        assertEquals("sdkLoginRequired", engine.vars.stopReason)
        // 断言放在"不跑格"上：`scrollToTop` 的回顶抓帧发生在页循环之前（属准备动作，无害），
        // 真正要防的是**在登录页上点格子/翻页** —— 那才是会崩轮的路径。
        // 断言口径（与实现顺序对齐）：`scrollToTop` 的**回顶滑动**发生在页循环之前，
        // 是无害手势（滑动不会崩轮，且能确认列表可滚）；真正要防的是**进入页循环后的格点击/翻页**。
        // ⇒ 断言"零格点击"，不断言"零滑动"。
        assertEquals("页首就停手 ⇒ 不应有任何格点击", 0, acts.clicks.size)
        assertTrue("页首就停手 ⇒ 不应有格级 tap", acts.taps.isEmpty())
    }

    /** 判据层：空/异常不抛（与其它判据同口径）。 */
    @Test
    fun `ScreenStateJudge 断线框判据对异常帧不抛`() {
        assertNull(ScreenStateJudge.disconnectBox(Mat()))
    }
}
