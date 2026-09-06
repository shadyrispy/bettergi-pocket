package com.bettergi.pocket.recognition.ocr.onnx

import com.bettergi.pocket.recognition.IntRect

/**
 * DB（Differentiable Binarization）后处理——行聚合简化版。
 *
 * 概率图按行聚合 → 二值化 → 相邻行合并为文本行框。对游戏详情卡这种横向文本行、
 * 行间留白的固定版式足够用；真·连通域 + unclip 版留待精度不足时再上。
 *
 * 纯逻辑（无 Android 依赖），JVM 可单测。
 * 移植自 irminsul `com.esc.irminsul.ocr.SimpleDbPostProcessor`，
 * 差异：返回 [IntRect] 而非 android.graphics.RectF（对齐本仓 recognition 层的几何类型，且便于 JVM 单测）。
 */
object DbPostProcessor {

    /** 行框（x1/y1 为开区间） */
    data class RowBox(val y0: Int, val y1: Int, val x0: Int, val x1: Int)

    /** det 预处理几何（等比缩放 + 黑边 pad 的左上锚定映射） */
    data class DetGeometry(val scaleX: Float, val scaleY: Float)

    /**
     * @param probMap 概率图 float[w*h]，取值 0..1
     * @param threshold 二值化阈值
     * @param minRowPixels 一行至少命中多少像素才算文本行
     * @param gap 相邻行合并允许的最大间隙（行）
     */
    fun toTextBoxes(
        probMap: FloatArray,
        w: Int,
        h: Int,
        threshold: Float = 0.3f,
        minRowPixels: Int = 4,
        gap: Int = 4,
    ): List<RowBox> {
        if (probMap.size < w * h || w <= 0 || h <= 0) return emptyList()
        val rowActive = BooleanArray(h)
        for (y in 0 until h) {
            var hit = 0
            for (x in 0 until w) if (probMap[y * w + x] >= threshold) hit++
            rowActive[y] = hit >= minRowPixels
        }
        val boxes = mutableListOf<RowBox>()
        var y0 = -1
        var last = -1
        for (y in 0..h) {
            val active = y < h && rowActive[y]
            if (active) {
                if (y0 < 0) {
                    y0 = y; last = y
                } else if (y - last > gap) {
                    boxes += buildBox(probMap, w, y0, last, threshold)
                    y0 = y
                }
                last = y
            } else if (y0 >= 0 && y - last > gap) {
                boxes += buildBox(probMap, w, y0, last, threshold)
                y0 = -1
            }
        }
        if (y0 >= 0) boxes += buildBox(probMap, w, y0, last, threshold)
        return boxes
    }

    private fun buildBox(probMap: FloatArray, w: Int, y0: Int, y1: Int, threshold: Float): RowBox {
        var x0 = w
        var x1 = 0
        for (y in y0..y1) {
            for (x in 0 until w) {
                if (probMap[y * w + x] >= threshold) {
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                }
            }
        }
        return RowBox(y0, y1 + 1, x0, x1 + 1)
    }

    /**
     * 行框 → 原图坐标：按 [geo] 的等比缩放比反向映射，越界裁剪。
     * 注意是等比（含 pad 区域，pad 在右/下），不是拉伸。
     */
    fun toRect(box: RowBox, geo: DetGeometry, srcW: Int, srcH: Int): IntRect {
        val l = (box.x0 / geo.scaleX).coerceIn(0f, srcW.toFloat()).toInt()
        val t = (box.y0 / geo.scaleY).coerceIn(0f, srcH.toFloat()).toInt()
        val r = (box.x1 / geo.scaleX).coerceIn(0f, srcW.toFloat()).toInt()
        val b = (box.y1 / geo.scaleY).coerceIn(0f, srcH.toFloat()).toInt()
        return IntRect(
            x = l,
            y = t,
            width = (r - l).coerceAtLeast(0),
            height = (b - t).coerceAtLeast(0),
        )
    }
}
