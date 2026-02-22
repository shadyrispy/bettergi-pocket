package com.bettergi.pocket.recognition.opencv

import android.util.Log
import com.bettergi.pocket.recognition.TemplateMatchMode
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.floor

data class TemplateMatchHit(
    val x: Int,
    val y: Int,
    val score: Double,
)

object MatchTemplateHelper {
    private const val TAG = "BetterGI.Match"
    private const val NMS_IOU = 0.5

    fun findBestMatch(
        src: Mat,
        template: Mat,
        mode: TemplateMatchMode,
        mask: Mat?,
        threshold: Double,
    ): TemplateMatchHit? {
        return findMatches(src, template, mode, mask, threshold, 1).firstOrNull()
    }

    fun findMatches(
        src: Mat,
        template: Mat,
        mode: TemplateMatchMode,
        mask: Mat?,
        threshold: Double,
        maxCount: Int,
    ): List<TemplateMatchHit> {
        val matches = ArrayList<TemplateMatchHit>()
        if (src.empty() || template.empty() || src.cols() < template.cols() || src.rows() < template.rows()) {
            return matches
        }

        var limit = maxCount
        if (limit < 0) {
            limit = src.cols() * src.rows() / template.cols() / template.rows()
        }
        if (limit <= 0) {
            return matches
        }

        val result = Mat()
        val candidateMask = Mat()
        try {
            val cvMode = mode.toCvMode()
            if (mask == null || mask.empty()) {
                Imgproc.matchTemplate(src, template, result, cvMode)
            } else {
                Imgproc.matchTemplate(src, template, result, cvMode, mask)
            }

            if (mode == TemplateMatchMode.SqDiff ||
                mode == TemplateMatchMode.CCoeff ||
                mode == TemplateMatchMode.CCorr
            ) {
                Core.normalize(result, result, 0.0, 1.0, Core.NORM_MINMAX)
            }

            val lowerBetter = mode == TemplateMatchMode.SqDiff || mode == TemplateMatchMode.SqDiffNormed
            val scoreThreshold = if (lowerBetter) 1.0 - threshold else threshold

            // 搜索区与模板同尺寸时结果只有 1 个像素。部分 OpenCV 对 1x1 带 mask 的 minMaxLoc 会原生崩溃。
            if (result.rows() == 1 && result.cols() == 1) {
                val buf = FloatArray(1)
                result.get(0, 0, buf)
                val rawScore = buf[0].toDouble()
                val passed = !rawScore.isNaN() &&
                    if (lowerBetter) rawScore <= scoreThreshold else rawScore >= scoreThreshold
                if (passed) {
                    matches.add(
                        TemplateMatchHit(0, 0, if (lowerBetter) 1.0 - rawScore else rawScore),
                    )
                }
                return matches
            }

            Core.compare(
                result,
                Scalar(scoreThreshold),
                candidateMask,
                if (lowerBetter) Core.CMP_LE else Core.CMP_GE,
            )

            while (matches.size < limit) {
                val mm = Core.minMaxLoc(result, candidateMask)
                val location = if (lowerBetter) mm.minLoc else mm.maxLoc
                if (location.x < 0 || location.x >= candidateMask.cols() ||
                    location.y < 0 || location.y >= candidateMask.rows() ||
                    MatOps.u8(candidateMask, location.y.toInt(), location.x.toInt()) == 0
                ) {
                    break
                }

                val rawScore = if (lowerBetter) mm.minVal else mm.maxVal
                if (rawScore.isNaN()) {
                    MatOps.setU8(candidateMask, location.y.toInt(), location.x.toInt(), 0)
                    continue
                }

                val score = if (lowerBetter) 1.0 - rawScore else rawScore
                matches.add(TemplateMatchHit(location.x.toInt(), location.y.toInt(), score))
                suppressOverlappingCandidates(
                    candidateMask,
                    location,
                    template.cols(),
                    template.rows(),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "template match failed", e)
        } finally {
            result.release()
            candidateMask.release()
        }
        return matches
    }

    private fun suppressOverlappingCandidates(
        candidateMask: Mat,
        selected: Point,
        templateWidth: Int,
        templateHeight: Int,
    ) {
        val maxDeltaX = floor(templateWidth * (1 - NMS_IOU) / (1 + NMS_IOU)).toInt() + 1
        val maxDeltaY = floor(templateHeight * (1 - NMS_IOU) / (1 + NMS_IOU)).toInt() + 1
        val selectedX = selected.x.toInt()
        val selectedY = selected.y.toInt()
        val minX = (selectedX - maxDeltaX).coerceAtLeast(0)
        val maxX = (selectedX + maxDeltaX).coerceAtMost(candidateMask.cols() - 1)
        val minY = (selectedY - maxDeltaY).coerceAtLeast(0)
        val maxY = (selectedY + maxDeltaY).coerceAtMost(candidateMask.rows() - 1)

        for (y in minY..maxY) {
            for (x in minX..maxX) {
                if (hasSuppressingOverlap(selectedX, selectedY, x, y, templateWidth, templateHeight) &&
                    MatOps.u8(candidateMask, y, x) != 0
                ) {
                    MatOps.setU8(candidateMask, y, x, 0)
                }
            }
        }
    }

    private fun hasSuppressingOverlap(
        firstX: Int,
        firstY: Int,
        secondX: Int,
        secondY: Int,
        templateWidth: Int,
        templateHeight: Int,
    ): Boolean {
        val templateArea = templateWidth.toDouble() * templateHeight
        val overlapWidth = templateWidth - abs(firstX - secondX)
        val overlapHeight = templateHeight - abs(firstY - secondY)
        if (overlapWidth <= 0 || overlapHeight <= 0) {
            return false
        }
        val intersection = overlapWidth.toDouble() * overlapHeight
        val union = templateArea * 2 - intersection
        return intersection / union >= NMS_IOU
    }
}

fun TemplateMatchMode.toCvMode(): Int {
    return when (this) {
        TemplateMatchMode.SqDiff -> Imgproc.TM_SQDIFF
        TemplateMatchMode.SqDiffNormed -> Imgproc.TM_SQDIFF_NORMED
        TemplateMatchMode.CCorr -> Imgproc.TM_CCORR
        TemplateMatchMode.CCorrNormed -> Imgproc.TM_CCORR_NORMED
        TemplateMatchMode.CCoeff -> Imgproc.TM_CCOEFF
        TemplateMatchMode.CCoeffNormed -> Imgproc.TM_CCOEFF_NORMED
    }
}
