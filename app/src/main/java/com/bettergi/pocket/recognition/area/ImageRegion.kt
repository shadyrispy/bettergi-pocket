package com.bettergi.pocket.recognition.area

import com.bettergi.pocket.core.ColorConversion
import com.bettergi.pocket.core.IntRect
import com.bettergi.pocket.recognition.OcrText
import com.bettergi.pocket.recognition.RecognitionObject
import com.bettergi.pocket.recognition.RecognitionTypes
import com.bettergi.pocket.recognition.ocr.IOcrService
import com.bettergi.pocket.recognition.ocr.OcrFactory
import com.bettergi.pocket.recognition.opencv.MatchTemplateHelper
import com.bettergi.pocket.core.image.MatOps
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.round

open class ImageRegion(
    srcMat: Mat,
    x: Int,
    y: Int,
    prev: Region? = null,
    prevConverter: NodeConverter? = null,
    private val ownsMat: Boolean = true,
    protected val ocrService: IOcrService = OcrFactory.default,
) : Region(x, y, srcMat.cols(), srcMat.rows(), prev, prevConverter) {
    var srcMat: Mat = srcMat
        private set

    private var cacheGreyMat: Mat? = null

    /**
     * ★ P3（2026-10-01）二值化缓存：`useBinaryMatch` 分支的 `MatOps.binary(...)` 原先每次识别
     * 都新建一个 ROI 级二值 Mat。现按 `(阈值)` 缓存在 **Region 上**——依据：
     * 二值化源恒为本 Region 的 [cacheGreyMatSafe]（惰性创建后内容不变，帧快照只读），
     * 与 RecognitionObject 无关（ro.templateImageMat 虽共享只读，但被二值化的是**帧侧**灰度图），
     * 故缓存放 Region 而非 object 上；同一 Region 上多个 binary 识别对象/多次识别直接复用。
     * 失效时机：[releaseOwnedMats]（Region 关闭/换帧）——帧与帧之间是新 Region，天然不跨帧。
     */
    private val cacheBinaryMats = HashMap<Int, Mat>()
    private var released: Boolean = false

    val cacheGreyMatSafe: Mat
        get() {
            val cached = cacheGreyMat
            if (cached != null) return cached
            val gray = MatOps.bgrToGray(srcMat)
            cacheGreyMat = gray
            return gray
        }

    fun deriveCrop(x: Int, y: Int, w: Int, h: Int): ImageRegion {
        val rect = IntRect(x, y, w, h).clampTo(srcMat.cols(), srcMat.rows())
        if (rect.width <= 0 || rect.height <= 0) {
            throw IllegalArgumentException(
                "DeriveCrop 裁剪区域无效: ($x,$y,$w,$h)，图像大小: ${srcMat.cols()}x${srcMat.rows()}",
            )
        }
        return ImageRegion(
            srcMat = MatOps.roiView(srcMat, rect),
            x = rect.x,
            y = rect.y,
            prev = this,
            prevConverter = TranslationConverter(rect.x, rect.y),
            ownsMat = true,
            ocrService = ocrService,
        )
    }

    fun deriveCrop(rect: IntRect): ImageRegion = deriveCrop(rect.x, rect.y, rect.width, rect.height)

    fun find(ro: RecognitionObject): Region {
        return when (ro.recognitionType) {
            RecognitionTypes.TemplateMatch -> findTemplate(ro)
            RecognitionTypes.OcrMatch -> findOcrMatch(ro)
            RecognitionTypes.Ocr, RecognitionTypes.ColorRangeAndOcr -> findOcr(ro)
            else -> throw IllegalArgumentException("ImageRegion不支持的识别类型${ro.recognitionType}")
        }
    }

    fun findMulti(ro: RecognitionObject): List<Region> {
        return when (ro.recognitionType) {
            RecognitionTypes.TemplateMatch -> findTemplateMulti(ro)
            RecognitionTypes.Ocr -> findOcrMulti(ro)
            else -> throw IllegalArgumentException("RectArea多目标识别不支持的识别类型${ro.recognitionType}")
        }
    }

    fun exists(ro: RecognitionObject): Boolean = find(ro).isExist()

    private fun findTemplate(ro: RecognitionObject): Region {
        val template = requiredTemplate(ro)

        val search = resolveSearch(ro) ?: return Region()
        var ownedSource: Mat? = null
        var ownedRoiView: Mat? = null
        var ownedTemplate: Mat? = null
        var ownedMask: Mat? = null
        try {
            val source = templateMatchSource(ro).also {
                // ★ 缓存后的二值 Mat 是 Region 持有的共享只读缓存（非本次新建），不得标记 owned 被 release
                val isCachedBinary = synchronized(cacheBinaryMats) { cacheBinaryMats.containsValue(it) }
                if (it !== cacheGreyMatSafe && it !== srcMat && !isCachedBinary) ownedSource = it
            }
            var roi = source
            var roiRect: IntRect? = null
            if (!search.effectiveRoi.isDefault()) {
                // A21：越界先 clamp，clamp 后为空则抛明确异常（不再"打日志后撞 CvException"）
                roiRect = resolveRoi(source, ro, search)
                ownedRoiView = MatOps.roiView(source, roiRect)
                roi = ownedRoiView
            }

            val effectiveTemplate = effectiveTemplate(ro, template, search).also {
                if (it !== template) ownedTemplate = it
            }
            val effectiveMask = effectiveMask(ro.maskMat, effectiveTemplate).also {
                if (it != null && it !== ro.maskMat) ownedMask = it
            }

            if (roi.cols() < effectiveTemplate.cols() || roi.rows() < effectiveTemplate.rows()) {
                return Region()
            }

            val match = MatchTemplateHelper.findBestMatch(
                roi,
                effectiveTemplate,
                ro.templateMatchMode,
                effectiveMask,
                ro.threshold,
            ) ?: return Region()

            val hit = derive(
                match.x + (roiRect?.x ?: 0),
                match.y + (roiRect?.y ?: 0),
                effectiveTemplate.cols(),
                effectiveTemplate.rows(),
            )
            hit.matchScore = match.score
            return hit
        } finally {
            ownedRoiView?.release()
            ownedSource?.release()
            ownedTemplate?.release()
            ownedMask?.release()
        }
    }

    private fun findTemplateMulti(ro: RecognitionObject): List<Region> {
        val template = requiredTemplate(ro)

        val search = resolveSearch(ro) ?: return emptyList()
        var ownedSource: Mat? = null
        var ownedRoiView: Mat? = null
        var ownedTemplate: Mat? = null
        var ownedMask: Mat? = null
        try {
            val source = templateMatchSource(ro).also {
                // ★ 缓存后的二值 Mat 是 Region 持有的共享只读缓存（非本次新建），不得标记 owned 被 release
                val isCachedBinary = synchronized(cacheBinaryMats) { cacheBinaryMats.containsValue(it) }
                if (it !== cacheGreyMatSafe && it !== srcMat && !isCachedBinary) ownedSource = it
            }
            var roi = source
            var roiRect: IntRect? = null
            if (!search.effectiveRoi.isDefault()) {
                // A21：与 findTemplate 同口径 —— 先 clamp，空则抛明确异常
                roiRect = resolveRoi(source, ro, search)
                ownedRoiView = MatOps.roiView(source, roiRect)
                roi = ownedRoiView
            }

            val effectiveTemplate = effectiveTemplate(ro, template, search).also {
                if (it !== template) ownedTemplate = it
            }
            val effectiveMask = effectiveMask(ro.maskMat, effectiveTemplate).also {
                if (it != null && it !== ro.maskMat) ownedMask = it
            }

            if (roi.cols() < effectiveTemplate.cols() || roi.rows() < effectiveTemplate.rows()) {
                return emptyList()
            }

            return MatchTemplateHelper.findMatches(
                roi,
                effectiveTemplate,
                ro.templateMatchMode,
                effectiveMask,
                ro.threshold,
                ro.maxMatchCount,
            ).map { match ->
                derive(
                    match.x + (roiRect?.x ?: 0),
                    match.y + (roiRect?.y ?: 0),
                    effectiveTemplate.cols(),
                    effectiveTemplate.rows(),
                ).also { it.matchScore = match.score }
            }
        } finally {
            ownedRoiView?.release()
            ownedSource?.release()
            ownedTemplate?.release()
            ownedMask?.release()
        }
    }

    private fun findOcrMatch(ro: RecognitionObject): Region {
        if (ro.allContainMatchText.isEmpty() && ro.oneContainMatchText.isEmpty() && ro.regexMatchText.isEmpty()) {
            throw IllegalArgumentException("[OCR]识别对象${ro.name}的匹配文本不能全为空")
        }
        val search = resolveSearch(ro) ?: return Region()
        // A21：ROI 先 clamp（空则抛明确异常），命中区域按 clamped rect 回报
        val roiRect = if (!search.effectiveRoi.isDefault()) resolveRoi(srcMat, ro, search) else null
        val ownedRoi = roiRect?.let { MatOps.roiView(srcMat, it) }
        try {
            val roi = ownedRoi ?: srcMat
            val result = ocrService.recognize(roi)
            val text = OcrText.normalize(result.text, ro.replaceDictionary)
            return if (OcrText.matches(text, ro.allContainMatchText, ro.oneContainMatchText, ro.regexMatchText)) {
                derive(roiRect ?: IntRect(0, 0, width, height))
            } else {
                Region()
            }
        } finally {
            ownedRoi?.release()
        }
    }

    private fun findOcr(ro: RecognitionObject): Region {
        val search = resolveSearch(ro) ?: return Region()
        // A21：ROI 先 clamp（空则抛明确异常）
        val roiRect = if (!search.effectiveRoi.isDefault()) resolveRoi(srcMat, ro, search) else null
        val ownedRoi = roiRect?.let { MatOps.roiView(srcMat, it) }
        var colorConverted: Mat? = null
        var colorMasked: Mat? = null
        try {
            var roi = ownedRoi ?: srcMat
            if (ro.recognitionType == RecognitionTypes.ColorRangeAndOcr) {
                val converted = if (ro.colorConversion == ColorConversion.None) {
                    roi
                } else {
                    MatOps.convertColor(roi, ro.colorConversion).also { colorConverted = it }
                }
                colorMasked = MatOps.inRange(converted, ro.lowerColor, ro.upperColor)
                roi = colorMasked
            }
            val result = ocrService.recognize(roi)
            val text = OcrText.normalize(result.text, ro.replaceDictionary)
            if (text.isEmpty()) {
                return Region()
            }
            val hit = derive(roiRect ?: IntRect(0, 0, width, height))
            hit.text = text
            return hit
        } finally {
            ownedRoi?.release()
            colorConverted?.release()
            colorMasked?.release()
        }
    }

    private fun findOcrMulti(ro: RecognitionObject): List<Region> {
        val search = resolveSearch(ro) ?: return emptyList()
        // A21：ROI 先 clamp（空则抛明确异常），偏移取 clamped 原点
        val roiRect = if (!search.effectiveRoi.isDefault()) resolveRoi(srcMat, ro, search) else null
        val ownedRoi = roiRect?.let { MatOps.roiView(srcMat, it) }
        try {
            val roi = ownedRoi ?: srcMat
            val result = ocrService.recognize(roi)
            val offsetX = roiRect?.x ?: 0
            val offsetY = roiRect?.y ?: 0
            return result.regions.mapNotNull { ocrRegion ->
                val clamped = ocrRegion.rect.clampTo(roi.cols(), roi.rows())
                if (clamped.isEmpty()) {
                    null
                } else {
                    derive(clamped.offset(offsetX, offsetY)).also {
                        it.text = OcrText.applyReplacements(ocrRegion.text, ro.replaceDictionary)
                    }
                }
            }
        } finally {
            ownedRoi?.release()
        }
    }

    private fun resolveSearch(ro: RecognitionObject): ReferenceSearchResult? {
        return ReferenceSearch.tryGetRegion(
            srcWidth = srcMat.cols(),
            srcHeight = srcMat.rows(),
            roi = ro.regionOfInterest,
            referenceImageSize = ro.referenceImageSize,
            referenceBoundingBox = ro.referenceBoundingBox,
            searchOptions = ro.searchOptions,
            canUseReferenceSearch = canUseReferenceSearch(),
            recognitionType = ro.recognitionType,
        )
    }

    private fun canUseReferenceSearch(): Boolean {
        return this is GameCaptureRegion ||
            (prev is GameCaptureRegion && prevConverter is ScaleConverter)
    }

    private fun requiredTemplate(ro: RecognitionObject): Mat {
        val template = if (ro.use3Channels) ro.templateImageMat else ro.templateImageGreyMat
        return template
            ?: throw IllegalArgumentException("[TemplateMatch]识别对象${ro.name}的模板图片不能为null")
    }

    private fun templateMatchSource(ro: RecognitionObject): Mat {
        if (ro.use3Channels) {
            return srcMat
        }
        if (ro.useBinaryMatch) {
            val gray = cacheGreyMatSafe
            synchronized(cacheBinaryMats) {
                cacheBinaryMats[ro.binaryThreshold]?.let { return it }
                val bin = MatOps.binary(gray, ro.binaryThreshold)
                cacheBinaryMats[ro.binaryThreshold] = bin
                return bin
            }
        }
        return cacheGreyMatSafe
    }

    private fun effectiveTemplate(
        ro: RecognitionObject,
        template: Mat,
        search: ReferenceSearchResult,
    ): Mat {
        if (!search.usedReferenceSearch) {
            return template
        }
        val target = search.effectiveTemplateSize
            ?: ro.referenceBoundingBox?.let { ReferenceSearch.scaledTemplateSize(it, search.scale) }
            ?: return template
        return MatOps.resize(template, target)
    }

    private fun effectiveMask(mask: Mat?, effectiveTemplate: Mat): Mat? {
        if (mask == null || (mask.cols() == effectiveTemplate.cols() && mask.rows() == effectiveTemplate.rows())) {
            return mask
        }
        return MatOps.resize(
            mask,
            com.bettergi.pocket.core.IntSize(effectiveTemplate.cols(), effectiveTemplate.rows()),
            Imgproc.INTER_NEAREST,
        )
    }

    /**
     * ROI 统一入口（A21）：先按源图尺寸 clamp（对齐 [deriveCrop] 先例），clamp 后空/负尺寸
     * 则 fail-loud —— 抛带 region 名与原始 rect 的明确异常。
     *
     * 背景：OpenCV `Mat(Mat, Rect)` 对越界 rect 是 CV_Assert 硬抛（CvException），
     * 旧代码在 [findTemplate] 里"先打日志再照常 roiView"，等于先报警再崩溃；
     * 16:10 屏跑 16:9 坐标时 JSON rect 溢出画面就是这条崩溃路径。
     * 部分越界（rect 与画面有交集）clamp 后继续识别，命中坐标按 clamped 原点回映。
     */
    private fun resolveRoi(source: Mat, ro: RecognitionObject, search: ReferenceSearchResult): IntRect {
        val roi = search.effectiveRoi
        val clamped = roi.clampTo(source.cols(), source.rows())
        if (clamped.isEmpty()) {
            throw IllegalArgumentException(
                "[ROI]识别对象${ro.name}的ROI越界且clamp后为空: rect=(${roi.x},${roi.y},${roi.width}x${roi.height})，" +
                    "图像 ${source.cols()}x${source.rows()}",
            )
        }
        return clamped
    }

    internal fun releaseOwnedMats() {
        if (released) return
        released = true
        cacheGreyMat?.release()
        cacheGreyMat = null
        // 二值化缓存与灰度缓存同生命周期（见 cacheBinaryMats 注释）
        synchronized(cacheBinaryMats) {
            cacheBinaryMats.values.forEach { it.release() }
            cacheBinaryMats.clear()
        }
        if (ownsMat) {
            srcMat.release()
        }
    }

    override fun close() {
        releaseOwnedMats()
        super.close()
    }

    private companion object
}

class GameCaptureRegion(
    srcMat: Mat,
    x: Int,
    y: Int,
    prev: Region? = null,
    prevConverter: NodeConverter? = null,
    ocrService: IOcrService = OcrFactory.default,
) : ImageRegion(srcMat, x, y, prev, prevConverter, ownsMat = true, ocrService = ocrService) {

    /**
     * 捕获宽大于 1920 时缩到 1080P 宽，坐标通过 [ScaleConverter] 回到原生分辨率。
     */
    fun deriveTo1080P(): ImageRegion {
        if (width <= 1920) {
            return this
        }
        val scale = width / 1920.0
        val resized = Mat()
        Imgproc.resize(srcMat, resized, Size(1920.0, height / scale))
        releaseOwnedMats()
        return ImageRegion(
            srcMat = resized,
            x = 0,
            y = 0,
            prev = this,
            prevConverter = ScaleConverter(scale),
            ownsMat = true,
            ocrService = ocrService,
        )
    }

    fun to1080PPos(nativeX: Double, nativeY: Double): Pair<Int, Int> {
        val scale = if (width > 1920) width / 1920.0 else 1.0
        return round(nativeX / scale).toInt() to round(nativeY / scale).toInt()
    }
}
