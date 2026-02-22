package com.bettergi.pocket.recognition

import com.bettergi.pocket.recognition.opencv.MatOps
import org.opencv.core.Mat
import kotlin.math.round

class RecognitionObject {
    var recognitionType: RecognitionTypes = RecognitionTypes.None
    var regionOfInterest: IntRect = IntRect.EMPTY
    var name: String? = null

    var referenceImageSize: IntSize? = null
    var referenceBoundingBox: IntRect? = null
    var searchOptions: SearchOptions? = null

    var templateImageMat: Mat? = null
    var templateImageGreyMat: Mat? = null
    var threshold: Double = 0.8
    var use3Channels: Boolean = false
    var templateMatchMode: TemplateMatchMode = TemplateMatchMode.CCoeffNormed
    var useMask: Boolean = false
    var maskColor: ColorBgr = ColorBgr.MASK_GREEN
    var maskMat: Mat? = null
    var maxMatchCount: Int = -1
    var useBinaryMatch: Boolean = false
    var binaryThreshold: Int = 128

    var colorConversion: ColorConversion = ColorConversion.BgrToRgb
    var lowerColor: ColorBgr = ColorBgr(0.0, 0.0, 0.0)
    var upperColor: ColorBgr = ColorBgr(255.0, 255.0, 255.0)

    var replaceDictionary: Map<String, List<String>> = emptyMap()
    var allContainMatchText: List<String> = emptyList()
    var oneContainMatchText: List<String> = emptyList()
    var regexMatchText: List<String> = emptyList()
    var text: String = ""

    fun initTemplate(): RecognitionObject {
        val color = templateImageMat
        if (color != null && templateImageGreyMat == null) {
            templateImageGreyMat = MatOps.bgrToGray(color)
        }
        if (useMask && color != null && maskMat == null) {
            maskMat = MatOps.createMask(color, maskColor)
        }
        return this
    }

    fun clone(): RecognitionObject {
        val cloned = RecognitionObject()
        cloned.recognitionType = recognitionType
        cloned.regionOfInterest = regionOfInterest
        cloned.name = name
        cloned.referenceImageSize = referenceImageSize
        cloned.referenceBoundingBox = referenceBoundingBox
        cloned.searchOptions = searchOptions?.copy()
        cloned.templateImageMat = templateImageMat
        cloned.templateImageGreyMat = templateImageGreyMat
        cloned.threshold = threshold
        cloned.use3Channels = use3Channels
        cloned.templateMatchMode = templateMatchMode
        cloned.useMask = useMask
        cloned.maskColor = maskColor
        cloned.maskMat = maskMat
        cloned.maxMatchCount = maxMatchCount
        cloned.useBinaryMatch = useBinaryMatch
        cloned.binaryThreshold = binaryThreshold
        cloned.colorConversion = colorConversion
        cloned.lowerColor = lowerColor
        cloned.upperColor = upperColor
        cloned.replaceDictionary = replaceDictionary
        cloned.allContainMatchText = allContainMatchText
        cloned.oneContainMatchText = oneContainMatchText
        cloned.regexMatchText = regexMatchText
        cloned.text = text
        return cloned
    }

    companion object {
        fun templateMatch(mat: Mat): RecognitionObject {
            return RecognitionObject().apply {
                recognitionType = RecognitionTypes.TemplateMatch
                templateImageMat = mat
                useMask = false
            }.initTemplate()
        }

        fun templateMatch(mat: Mat, useMask: Boolean, maskColor: ColorBgr = ColorBgr.MASK_GREEN): RecognitionObject {
            return RecognitionObject().apply {
                recognitionType = RecognitionTypes.TemplateMatch
                templateImageMat = mat
                this.useMask = useMask
                this.maskColor = maskColor
            }.initTemplate()
        }

        fun templateMatch(mat: Mat, x: Double, y: Double, w: Double, h: Double): RecognitionObject {
            return RecognitionObject().apply {
                recognitionType = RecognitionTypes.TemplateMatch
                templateImageMat = mat
                regionOfInterest = IntRect(round(x).toInt(), round(y).toInt(), round(w).toInt(), round(h).toInt())
            }.initTemplate()
        }

        fun ocr(x: Double, y: Double, w: Double, h: Double): RecognitionObject {
            return RecognitionObject().apply {
                recognitionType = RecognitionTypes.Ocr
                regionOfInterest = IntRect(round(x).toInt(), round(y).toInt(), round(w).toInt(), round(h).toInt())
            }
        }

        fun ocr(rect: IntRect): RecognitionObject {
            return RecognitionObject().apply {
                recognitionType = RecognitionTypes.Ocr
                regionOfInterest = rect
            }
        }

        fun ocrMatch(x: Double, y: Double, w: Double, h: Double, vararg matchTexts: String): RecognitionObject {
            return RecognitionObject().apply {
                recognitionType = RecognitionTypes.OcrMatch
                regionOfInterest = IntRect(round(x).toInt(), round(y).toInt(), round(w).toInt(), round(h).toInt())
                oneContainMatchText = matchTexts.toList()
            }
        }

        fun ocrThis(): RecognitionObject {
            return RecognitionObject().apply {
                recognitionType = RecognitionTypes.Ocr
            }
        }
    }
}
