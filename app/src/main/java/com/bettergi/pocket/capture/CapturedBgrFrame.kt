package com.bettergi.pocket.capture

import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * 已转成 BGR 的一帧，供识别使用。调用方负责随 [com.bettergi.pocket.recognition.CaptureContent] 释放 Mat。
 *
 * [timestampNs] 为 ImageReader 原始帧时间戳（纳秒，平台相关时间基准），仅用于同源流的
 * 帧新旧相对比较（FrameSource 判"动作后新帧"）；不可与 SystemClock 时刻直接换算。
 */
class CapturedBgrFrame(
    val width: Int,
    val height: Int,
    val bgr: Mat,
    val timestampNs: Long = 0L,
)

/**
 * GC P0（工单 A）：池化 BGR 帧的 release 钩子。
 *
 * 背景：`acquireLatestBgr()` 交付的 BGR Mat 会穿过本包边界，被**不可修改的调用方**持有并在
 * 解析结束时经 `GameCaptureRegion.close() → ImageRegion.releaseOwnedMats() → srcMat.release()`
 * 归还（ownsMat=true，全仓恰好一次 release，见 ScreenCaptureController 的持有者审计注释）。
 * 这些调用点不经过 [CapturedBgrFrame]，也没有任何可接线的 close 回调 ⇒ 池无法从外部得知
 * 「帧已被用完」。本类的最小方案：**继承 Mat 覆写 [release]**——OpenCV Java 绑定（4.10.0，
 * javap 验证）中 `Mat.release()` 为 public 非 final、`nativeObj` 为 public final，JNI 全部走
 * `nativeObj`，子类对 `Imgproc.cvtColor/copyTo/put/...` 完全透明；release 时先释放原生数据
 * （与普通 Mat 逐位一致），再回调池做簿记（引用清点归零后回收/丢弃）。
 *
 * 生命周期契约（ScreenCaptureController.bgrPool 维护）：
 * - 构造即分配 `w×h CV_8UC3`；同一实例可跨多次交付复用（每次交付 outstanding+1）。
 * - [release] 语义 = **「我不再持有这个引用」**，而不是「立刻释放原生数据」：是否真释放由
 *   池在簿记回调里裁决（[onReleased] 返回 true 才 [super.release]）。**发布位与池内槽的原生
 *   数据必须保持可读**——发布帧在屏幕静止时会被下一个 `acquireLatestBgr` 再交付，先释放后
 *   簿记会把共享的发布帧清空 ⇒ 交付空 Mat（2026-10-01 真机实录：weapon 翻页后静止窗口内
 *   `countMatches` 拿到 cols()=0 的 Mat，`coerceIn(0,-1)` 崩）。
 * - 可重复调用（与普通 Mat 的幂等 release 对齐）：簿记按 outstanding 计数兜底，
 *   `inPool` 标志防同一槽重复入池。
 * - [disposeNow] 供池在收缩/换尺寸/停机时主动释放原生数据（绕开簿记回调）。
 */
internal class PooledBgrMat(
    width: Int,
    height: Int,
    private val onReleased: (PooledBgrMat) -> Boolean,
) : Mat(height, width, CvType.CV_8UC3) {

    /** 已被 [disposeNow] 释放过（池收缩/停机）。此后 hook 一律允许真释放。 */
    @Volatile
    internal var disposed: Boolean = false

    override fun release() {
        if (disposed || onReleased(this)) {
            super.release()
        }
    }

    /** 池主动释放（停机/缩池/尺寸失配），不走归还簿记。 */
    internal fun disposeNow() {
        disposed = true
        super.release()
    }
}
