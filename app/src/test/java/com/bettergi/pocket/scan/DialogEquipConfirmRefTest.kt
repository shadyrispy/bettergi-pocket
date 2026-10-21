package com.bettergi.pocket.scan

import com.bettergi.pocket.capture.FrameSource
import com.bettergi.pocket.capture.FrameTimeoutException
import com.bettergi.pocket.scan.flows.EquipFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar

/**
 * #222：`dialog` 原语的 ref 断链 —— flow 词汇写 `$dialogs.equipConfirm`，profiles 却把它挂在
 * `screens.dialogs.equipConfirm` 下。旧实现不做归一（`ScreenProfile.normalizeFlowRef` 有定义、
 * 零调用点），`rawObject` 恒 null ⇒ `?: return` **静默跳过**：确认钮从来没被点过，而弹窗还盖在
 * foreach 末尾的复核读上（见 ScanControlDomain「必须放在内层步骤之后」那段）。
 *
 * 钉的是**活路径**：引擎 `auto_equip` → `runEquipSteps()` 原生步序 →
 * `dialog(EquipFlow.ITEM_DIALOG_EQUIP_CONFIRM)`（DSL 运行时已下线，`flows/auto_equip.json` 无人执行）。
 */
class DialogEquipConfirmRefTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCvNative() {
            nu.pattern.OpenCV.loadLocally()
            Mat(2, 2, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0)).release()
        }
    }

    private val clicks = mutableListOf<Pair<Int, Int>>()

    /** 把同一份文案回给 dialog 的那一次 readLines，并记下它究竟读了哪个 rect。 */
    private class DialogGateway(private val text: String) : OcrGateway {
        var lastRects: List<FrameRect> = emptyList()

        override suspend fun readLines(frame: Mat, rects: List<FrameRect>): List<String> {
            lastRects = rects
            return listOf(text)
        }

        override suspend fun readNumber(frame: Mat, rect: FrameRect): Int? = null
    }

    private inner class RecordingActions : ScanEngine.ActionGateway {
        override fun click(x: Int, y: Int, durationMs: Long): Boolean {
            clicks += x to y
            return true
        }

        override fun tap(x: Int, y: Int): Boolean = true
        override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean = true
        override fun back(): Boolean = true
    }

    private class StubListener : ScanListener {
        override fun onProgress(stage: String, vars: Map<String, Any?>) = Unit
        override fun onFinished(reason: String) = Unit
    }

    private class TinyFrameSource : FrameSource {
        override suspend fun grabFresh(afterTimestampMs: Long, timeoutMs: Long): Mat =
            Mat(64, 64, CvType.CV_8UC3, Scalar(255.0, 255.0, 255.0))

        override fun markActionAt(timestampMs: Long) = Unit
        override fun acquireLatestBgr() = throw FrameTimeoutException(0, 0)
        override fun discardLatestImages() = Unit
        override fun capturedSize(): Pair<Int, Int>? = 64 to 64
        override fun isRunning(): Boolean = true
        override fun release() = Unit
    }

    /** assets 里那份 3200x1440 profile（与 flow 常量同档 ⇒ scale=1，坐标可直接对账）。 */
    private fun loadProfile(): ScreenProfile {
        var dir = java.io.File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val f = java.io.File(dir, "src/main/assets/dsl/profiles.json")
            if (f.isFile) {
                val p = ScreenProfile(JSONObject(f.readText()))
                p.calibrate(3200, 1440)
                return p
            }
            dir = dir.parentFile ?: dir
        }
        error("profiles.json not found")
    }

    /** @return 抛出的异常（null = 没抛）+ dialog 实际读过的 ROI 列表。 */
    private fun runDialog(
        profile: ScreenProfile,
        step: JSONObject,
        ocrText: String,
    ): Pair<Throwable?, List<FrameRect>> {
        val gateway = DialogGateway(ocrText)
        val engine = ScanEngine(
            flowJson = JSONObject(),
            profile = profile,
            frameSource = TinyFrameSource(),
            actions = RecordingActions(),
            ocr = gateway,
            listener = StubListener(),
            maxPages = 1,
            clock = { System.nanoTime() / 1_000_000 },
        )
        val err = runCatching {
            runBlocking { withTimeout(30_000) { engine.dialog(step) } }
        }.exceptionOrNull()
        return err to gateway.lastRects
    }

    @Test
    fun `flow 词汇 dialogs ref 必须归一到 screens 挂载点并真点确认钮`() {
        val profile = loadProfile()
        val node = profile.rawObject("screens.dialogs.equipConfirm")
            ?: error("夹具失效：profile 无 screens.dialogs.equipConfirm")
        val cc = node.getJSONArray("confirmClick")
        val want = profile.scalePoint(cc.getInt(0), cc.getInt(1))
        val wantRect = profile.rect("screens.dialogs.equipConfirm.text")

        val (err, readRects) = runDialog(
            profile,
            EquipFlow.ITEM_DIALOG_EQUIP_CONFIRM,
            "班尼特已被琴装备，是否更换？",
        )

        assertTrue("弹窗在且 ref 解析得到 ⇒ 不该抛，实际=$err", err == null)
        // 旧实现在 rawObject 那行就 null-return 了：既没读 ROI、也没点 ⇒ 下面两条断言在修复前都红
        assertEquals(
            "dialog 必须读 profile 的 screens.dialogs.equipConfirm.text（旧版一次都没读）",
            listOf(wantRect),
            readRects,
        )
        assertEquals(
            "确认钮必须按 profile 的 confirmClick 真点下去（旧版一次都没点 ⇒ #222）",
            listOf(want.x to want.y),
            clicks,
        )
    }

    @Test
    fun `无弹窗文案时不点（归一修复不得放宽误判闸门）`() {
        val profile = loadProfile()
        val (err, readRects) = runDialog(
            profile,
            EquipFlow.ITEM_DIALOG_EQUIP_CONFIRM,
            "Lv.90 Lv.90 Lv.20",
        )
        assertTrue("无弹窗是正常分支（目标无人装备），不该抛；实际=$err", err == null)
        assertEquals("读了 ROI 但没命中 expect ⇒ 绝不盲点确认钮", emptyList<Pair<Int, Int>>(), clicks)
        assertTrue("本用例的判据要真读过弹窗 ROI 才成立", readRects.isNotEmpty())
    }

    @Test
    fun `解析不到的 ref 必须响而不是静默跳过`() {
        val profile = loadProfile()
        val step = JSONObject()
            .put("do", "dialog")
            .put("ref", "\$dialogs.no_such_dialog")
            .put("expect", "是否更换")

        val (err, _) = runDialog(profile, step, "班尼特已被琴装备，是否更换？")

        assertTrue(
            "取不到节点必须 loud（旧版 `?: return` 静默跳过，正是 #222 的失效形态）；实际=$err",
            err is ScanAbortedException,
        )
        val msg = err!!.message.orEmpty()
        assertTrue("报错要带上 flow 写的原样 ref：$msg", msg.contains("\$dialogs.no_such_dialog"))
        assertTrue("报错要带上归一后真正查的 profile 键：$msg", msg.contains("screens.dialogs.no_such_dialog"))
        assertTrue("报错要带上 profile 里实际注册了什么（便于对键）：$msg", msg.contains("equipConfirm"))
        assertEquals("抛出去之前不该已经点过任何东西", emptyList<Pair<Int, Int>>(), clicks)
    }
}
