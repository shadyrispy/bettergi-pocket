package com.bettergi.pocket.scan

import com.bettergi.pocket.capture.FrameSource
import com.bettergi.pocket.capture.FrameTimeoutException
import com.bettergi.pocket.capture.PooledBgrMat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import java.util.IdentityHashMap

/**
 * #225 第③条：`awaitGridStable` 在**取消**路径上必须把借来的帧归还。
 *
 * 为什么这算泄漏而不是"GC 会收"：生产侧 `ProjectionFrameSource.grabFresh` 交付的就是
 * `controller.acquireLatestBgr()` 的 `frame.bgr` —— 池里的 [PooledBgrMat]，交付时
 * `outstanding++`。`release()` 的语义是「我不再持有」，漏一次就是**永久钉住一个池槽**；
 * `BGR_POOL_MAX=4` 用尽后 poller 退化成"逐帧新建-即弃"（变慢），而且没有任何日志会报出来。
 *
 * 取消点选在 `delay` 而不是 `grabFresh`：循环体是「delay → release(上一帧) → 取新帧」，
 * 若在**取到帧之后**、下一轮 `delay` 处被取消，手里那帧还没到释放点 ⇒ 只有 catch 分支会放。
 * （从 `grabFresh` 里抛取消的话上一帧已在循环顶放过 ⇒ 旧代码也能过，是空跑。）
 */
class SettleFrameCancelTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCvNative() {
            nu.pattern.OpenCV.loadLocally()
            Mat(2, 2, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0)).release()
        }
    }

    /**
     * 每轮交付一个带归还簿记的池化帧；第 [cancelOnGrab] 次交付后把宿主协程取消，
     * 于是取消落在下一轮循环顶的 `delay` 上（生产里最常见的形态）。
     */
    private class CancellingFrameSource(private val cancelOnGrab: Int) : FrameSource {
        val delivered = ArrayList<PooledBgrMat>()
        val releaseCalls = IdentityHashMap<PooledBgrMat, Int>()
        var job: Job? = null

        override suspend fun grabFresh(afterTimestampMs: Long, timeoutMs: Long): Mat {
            val mat = PooledBgrMat(640, 360) { m ->
                releaseCalls[m] = (releaseCalls[m] ?: 0) + 1
                true
            }
            delivered += mat
            if (delivered.size == cancelOnGrab) job?.cancel()
            return mat
        }

        override fun markActionAt(timestampMs: Long) = Unit
        override fun acquireLatestBgr() = throw FrameTimeoutException(0, 0)
        override fun discardLatestImages() = Unit
        override fun capturedSize(): Pair<Int, Int>? = 640 to 360
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

    private fun profile(): ScreenProfile {
        var dir = java.io.File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val f = java.io.File(dir, "src/main/assets/dsl/profiles.json")
            if (f.isFile) {
                val p = ScreenProfile(JSONObject(f.readText()))
                p.calibrate(640, 360)
                return p
            }
            dir = dir.parentFile ?: dir
        }
        error("profiles.json not found")
    }

    @Test
    fun `cancelling the settle loop returns every borrowed frame`() = runBlocking {
        val src = CancellingFrameSource(cancelOnGrab = 2)
        val engine = ScanEngine(
            flowJson = JSONObject().put("steps", org.json.JSONArray()),
            profile = profile(),
            frameSource = src,
            actions = StubActions(),
            ocr = null,
            listener = StubListener(),
            clock = { System.nanoTime() / 1_000_000 },
        )
        val job = launch(start = CoroutineStart.LAZY) {
            runCatching { engine.awaitGridStable(profile(), "weapon_backpack") }
        }
        src.job = job
        job.start()
        // 取消是源自己在第 2 次交付时发起的；join 等到它真正收尾（不给固定 sleep 猜时间）
        job.join()

        assertTrue("循环至少转了两轮才取消 delivered=${src.delivered.size}", src.delivered.size >= 2)
        val leaked = src.delivered.filter { (src.releaseCalls[it] ?: 0) == 0 }
        assertEquals(
            "取消路径漏归还的池帧数=${leaked.size}（借出 ${src.delivered.size} 帧）⇒ " +
                "每漏一帧永久钉死一个池槽，BGR_POOL_MAX=4 耗尽后 poller 逐帧新建-即弃",
            0,
            leaked.size,
        )
    }
}
