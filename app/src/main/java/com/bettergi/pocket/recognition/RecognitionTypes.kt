package com.bettergi.pocket.recognition

enum class RecognitionTypes {
    None,
    TemplateMatch,
    ColorMatch,
    OcrMatch,
    Ocr,
    ColorRangeAndOcr,
    Detect,
}

enum class TemplateMatchMode {
    SqDiff,
    SqDiffNormed,
    CCorr,
    CCorrNormed,
    CCoeff,
    CCoeffNormed,
}

enum class ColorConversion {
    None,
    BgrToRgb,
    BgrToHsv,
    BgrToGray,
}

enum class SearchAnchorMode {
    Auto,
    TopLeft,
    TopRight,
    BottomLeft,
    BottomRight,
    Center,
}

data class SearchOptions(
    val anchorMode: SearchAnchorMode = SearchAnchorMode.Auto,
    val referenceSearchBox: IntRect? = null,
    val expandSize: IntSize? = null,
    val expandPercent: SearchExpandRatio? = null,
)

/**
 * 搜索区域四边的扩展比例，顺序与 XAML Thickness 一致：Left、Top、Right、Bottom。
 * 例如 0.05 表示按当前截图对应边长扩展 5%。
 */
data class SearchExpandRatio(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    val isValid: Boolean
        get() = left.isFinite() && left >= 0.0 &&
            top.isFinite() && top >= 0.0 &&
            right.isFinite() && right >= 0.0 &&
            bottom.isFinite() && bottom >= 0.0

    companion object {
        fun fromThickness(values: List<Double>): SearchExpandRatio {
            val ratio = when (values.size) {
                1 -> SearchExpandRatio(values[0], values[0], values[0], values[0])
                2 -> SearchExpandRatio(values[0], values[1], values[0], values[1])
                4 -> SearchExpandRatio(values[0], values[1], values[2], values[3])
                else -> throw IllegalArgumentException("search.expandPercent 必须包含 1、2 或 4 个数字")
            }
            if (!ratio.isValid) {
                throw IllegalArgumentException("search.expandPercent 必须全部为有限且非负的小数比例")
            }
            return ratio
        }
    }
}
