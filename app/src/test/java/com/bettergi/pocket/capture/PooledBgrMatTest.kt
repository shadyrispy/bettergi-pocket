package com.bettergi.pocket.capture

import nu.pattern.OpenCV
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import java.util.concurrent.atomic.AtomicInteger

/**
 * GC P0（工单 A）：池化 BGR Mat 的 release 钩子行为（JVM 桌面 OpenCV，与 AAR 绑定同源 API）。
 *
 * 覆盖 ScreenCaptureController 池设计的前提与 2026-10-01 深测回归修复：
 * 1. `PooledBgrMat` 对 OpenCV Java API 透明：cvtColor/put 等按普通 Mat 工作（JNI 走 nativeObj）；
 * 2. **release = 「我不再持有引用」，不是「立刻释放原生数据」**——真释放由池裁决
 *    （hook 返回 true 才释放）。回归依据：原实现先释放后簿记，发布位在屏幕静止时被
 *    清空 ⇒ 下一个 acquireLatestBgr 交付空 Mat ⇒ 判据层 `coerceIn(0,-1)` 崩（15:31 实录）。
 * 3. [PooledBgrMat.disposeNow] 不触发回调（池主动丢弃路径），此后 release 直接过。
 */
class PooledBgrMatTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCvNative() {
            nu.pattern.OpenCV.loadLocally()
            Mat(2, 2, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0)).release()
        }
    }

    @Test
    fun `release 后池裁决保留数据则 Mat 仍可读（发布位语义）`() {
        val calls = AtomicInteger(0)
        val mat = PooledBgrMat(8, 6) { calls.incrementAndGet(); false } // 池判定：保留
        val data = ByteArray(8 * 6 * 3) { it.toByte() }
        assertEquals(8 * 6 * 3, mat.put(0, 0, data))
        mat.release()
        assertEquals("release 触发一次簿记回调", 1, calls.get())
        assertFalse("池保留数据 ⇒ release 后 Mat 不为空（发布位必须保持可读）", mat.empty())
        val readBack = ByteArray(8 * 6 * 3)
        mat.get(0, 0, readBack)
        assertTrue("内容逐位保留", data.contentEquals(readBack))
    }

    @Test
    fun `release 后池裁决丢弃则原生数据释放`() {
        val mat = PooledBgrMat(8, 6) { true } // 池判定：收缩丢弃
        mat.put(0, 0, ByteArray(8 * 6 * 3))
        mat.release()
        assertTrue("丢弃裁决 ⇒ 真释放（空 Mat）", mat.empty())
    }

    @Test
    fun `回归钉 20261001 静止窗口两次 acquire 之间 release 不清空发布帧`() {
        // 复现原崩溃序列：S0 发布 → 调用方 A release（池判定保留）→ 屏幕静止无新帧 →
        // 调用方 B acquire 同一槽 ⇒ 必须拿到完整内容（原实现此处拿到 cols()=0 空 Mat）。
        val mat = PooledBgrMat(16, 8) { false }
        val data = ByteArray(16 * 8 * 3) { (it % 251).toByte() }
        mat.put(0, 0, data)
        // A 用完释放
        mat.release()
        // B（静止窗口内的下一个 acquire）读取
        val readBack = ByteArray(16 * 8 * 3)
        mat.get(0, 0, readBack)
        assertTrue("发布帧在他人 release 后必须保持逐位可读", data.contentEquals(readBack))
    }

    @Test
    fun `disposeNow 释放原生数据但不触发归还回调`() {
        val calls = AtomicInteger(0)
        val mat = PooledBgrMat(4, 4) { calls.incrementAndGet(); false }
        mat.put(0, 0, ByteArray(4 * 4 * 3))
        mat.disposeNow()
        assertEquals("disposeNow 不走簿记", 0, calls.get())
        assertTrue(mat.empty())
        // dispose 后再 release：直接过（不再回调、保持空）
        mat.release()
        assertEquals(0, calls.get())
        assertTrue(mat.empty())
    }
}
