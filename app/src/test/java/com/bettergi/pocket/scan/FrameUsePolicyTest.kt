package com.bettergi.pocket.scan

import com.bettergi.pocket.scan.ScanEngine.Companion.STOP_MARKER_RARITY
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar

/**
 * #196 第二版的**取帧处置表**测试（2026-10-04）。
 *
 * 为什么只能这样测（这条决定了可信度，别跳过）：
 *   · BlueStacks 2026-10-04 全天 **46 条摘要里 `帧新鲜性`/`判稳无新帧` 出现 0 次**
 *     ⇒ 健康轮根本走不到"拿不到动作后新帧"这一支，拿它做设备 A/B 是恒等、是空跑。
 *   · 唯一走过的是华为退化轮：`超时=16次 旧帧顶替=16次 无帧可退=0次`
 *     （`ui_scan_20261003_231618_..._weapon_scan.logcat.log`，`scan finished: watchdog cells=13`）
 *     ⇒ 16/16 每一次超时都静默退了旧帧。而"投影卡住"不可按需复现。
 *   ⇒ 所以这版的行为**只能钉在决策表上**，设备侧留一个可事后核对的信号：
 *     真发生退化轮时 `不回退旧帧`（frameRefusedTotal）应当从 0 变成 >0，
 *     且失败是响的（格/页处置层会重访或放弃），不是静默错读。
 */
class FrameUsePolicyTest {

    companion object {
        // JVM 桌面 OpenCV（与 PooledBgrMatTest / VoteJudgesRowReadParityTest 同源做法）：
        // 不加载的话 `Mat(...)` 直接 UnsatisfiedLinkError，而那条失败长得像"策略返回了 null"。
        init { nu.pattern.OpenCV.loadLocally() }
    }

    private fun mat() = Mat(2, 2, CvType.CV_8UC3, Scalar(1.0, 2.0, 3.0))

    /** 第一版（以及更早全部历史）的策略：两种用途都回退到缓存帧。 */
    private fun oldPolicy(use: FrameUse, cached: Mat?): Mat? = cached

    @Test
    fun `recognition refuses a stale frame - the whole point of v2`() {
        val cached = mat()
        try {
            assertNull(
                "识别用帧拿不到动作后的新帧时**不许**退回缓存帧：那等于拿动作之前的画面做 OCR，" +
                    "与『画面真的没变』在下游完全同形 ⇒ 静默错读（#188 那轮就是这么绕的）",
                frameOnNoFresh(FrameUse.RECOGNITION, cached),
            )
            // 负对照：旧策略在同一输入下**会**把这一帧交出去。没有这条，
            // 上面那句 assertNull 可能只是因为 cached 本来就该被丢。
            assertSame(
                "同一份输入下旧策略确实返回了旧帧 ⇒ 上面那条红/绿之差来自本版改动，不是来自夹具",
                cached, oldPolicy(FrameUse.RECOGNITION, cached),
            )
        } finally {
            cached.release()
        }
    }

    @Test
    fun `settle keeps using the cached frame - no new frame IS the answer there`() {
        val cached = mat()
        try {
            assertSame(
                "判稳/重读侧问的是『还在不在动』：BlueStacks 虚拟屏静止就不推帧，" +
                    "这里退回缓存帧是设计意图（删了它会让 settle 循环把正常静止页当故障）",
                cached, frameOnNoFresh(FrameUse.SETTLE, cached),
            )
        } finally {
            cached.release()
        }
    }

    @Test
    fun `both uses return null when there is no cached frame at all`() {
        assertNull(frameOnNoFresh(FrameUse.RECOGNITION, null))
        assertNull(frameOnNoFresh(FrameUse.SETTLE, null))
    }

    @Test
    fun `the two uses are distinct values so the table cannot collapse back`() {
        // 防"顺手合并成一个函数"：一旦有人把两个入口并回一个，上面两条测试会同时失去意义，
        // 而这条会在重构时先给出信号。
        assertNotSame(FrameUse.RECOGNITION, FrameUse.SETTLE)
        assertTrue(FrameUse.values().size == 2)
    }

    @Test
    fun `stop marker rarity stays the artifact floor the table assumes`() {
        // 与 #216 的交叉钉：识别侧不回退后，素材标识是尾区唯一的"响亮"出口之一，
        // 而圣遗物侧的 1★/2★ 止扫仍按这条常量走（snap 的 peek 收尾只覆盖 auto_equip）。
        assertTrue(STOP_MARKER_RARITY == 4)
    }
}
