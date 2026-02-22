package com.bettergi.pocket.recognition

import com.bettergi.pocket.recognition.area.ReferenceSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

class ReferenceSearchTest {
    @Test
    fun `2560x1600 top-left maps 1080p bbox to transformed box`() {
        val result = ReferenceSearch.tryGetRegion(
            srcWidth = 2560,
            srcHeight = 1600,
            roi = IntRect.EMPTY,
            referenceImageSize = IntSize(1920, 1080),
            referenceBoundingBox = IntRect(200, 150, 32, 32),
            searchOptions = SearchOptions(anchorMode = SearchAnchorMode.TopLeft, expandSize = IntSize(0, 0)),
            canUseReferenceSearch = true,
        )
        assertNotNull(result)
        assertTrue(result!!.usedReferenceSearch)
        assertEquals(2560 / 1920.0, result.scale, 1e-9)
        assertEquals(IntRect(267, 200, 42, 43), result.effectiveRoi)
        assertEquals(IntSize(43, 43), result.effectiveTemplateSize)
        assertEquals(IntSize(43, 43), ReferenceSearch.scaledTemplateSize(IntRect(200, 150, 32, 32), result.scale))
    }

    @Test
    fun `default expand grows predicted box then clamps`() {
        val result = ReferenceSearch.tryGetRegion(
            srcWidth = 2560,
            srcHeight = 1600,
            roi = IntRect.EMPTY,
            referenceImageSize = IntSize(1920, 1080),
            referenceBoundingBox = IntRect(200, 150, 32, 32),
            searchOptions = SearchOptions(anchorMode = SearchAnchorMode.TopLeft),
            canUseReferenceSearch = true,
        )
        assertEquals(IntRect(257, 190, 62, 63), result!!.effectiveRoi)
    }

    @Test
    fun `independent search box uses same anchor as bbox`() {
        val result = ReferenceSearch.tryGetRegion(
            srcWidth = 1920,
            srcHeight = 1080,
            roi = IntRect.EMPTY,
            referenceImageSize = IntSize(1920, 1080),
            referenceBoundingBox = IntRect(200, 150, 32, 32),
            searchOptions = SearchOptions(
                anchorMode = SearchAnchorMode.TopLeft,
                referenceSearchBox = IntRect(600, 250, 160, 120),
                expandPercent = SearchExpandRatio(0.0, 0.0, 0.0, 0.0),
            ),
            canUseReferenceSearch = true,
        )
        assertEquals(IntRect(600, 250, 160, 120), result!!.effectiveRoi)
        assertEquals(IntSize(32, 32), result.effectiveTemplateSize)
    }

    @Test
    fun `percent expand uses current screenshot width and height`() {
        val result = ReferenceSearch.tryGetRegion(
            srcWidth = 300,
            srcHeight = 200,
            roi = IntRect.EMPTY,
            referenceImageSize = IntSize(100, 100),
            referenceBoundingBox = IntRect(20, 20, 10, 10),
            searchOptions = SearchOptions(
                anchorMode = SearchAnchorMode.TopLeft,
                referenceSearchBox = IntRect(20, 20, 20, 20),
                expandPercent = SearchExpandRatio(0.1, 0.1, 0.2, 0.05),
            ),
            canUseReferenceSearch = true,
        )
        assertEquals(IntRect(10, 20, 130, 70), result!!.effectiveRoi)
    }

    @Test
    fun `zero percent expand ignores pixel expand`() {
        val result = ReferenceSearch.tryGetRegion(
            srcWidth = 1920,
            srcHeight = 1080,
            roi = IntRect.EMPTY,
            referenceImageSize = IntSize(1920, 1080),
            referenceBoundingBox = IntRect(200, 150, 32, 32),
            searchOptions = SearchOptions(
                anchorMode = SearchAnchorMode.TopLeft,
                expandSize = IntSize(100, 100),
                expandPercent = SearchExpandRatio(0.0, 0.0, 0.0, 0.0),
            ),
            canUseReferenceSearch = true,
        )
        assertEquals(IntRect(200, 150, 32, 32), result!!.effectiveRoi)
    }

    @Test
    fun `out of bounds search box is clamped`() {
        val result = ReferenceSearch.tryGetRegion(
            srcWidth = 200,
            srcHeight = 100,
            roi = IntRect.EMPTY,
            referenceImageSize = IntSize(200, 100),
            referenceBoundingBox = IntRect(10, 10, 10, 10),
            searchOptions = SearchOptions(
                anchorMode = SearchAnchorMode.TopLeft,
                referenceSearchBox = IntRect(-50, -20, 80, 50),
                expandPercent = SearchExpandRatio(0.0, 0.0, 0.0, 0.0),
            ),
            canUseReferenceSearch = true,
        )
        assertEquals(IntRect(0, 0, 30, 30), result!!.effectiveRoi)
    }

    @Test
    fun `scaled search box too small for template is rejected`() {
        val result = ReferenceSearch.tryGetRegion(
            srcWidth = 200,
            srcHeight = 200,
            roi = IntRect.EMPTY,
            referenceImageSize = IntSize(100, 100),
            referenceBoundingBox = IntRect(20, 20, 30, 30),
            searchOptions = SearchOptions(
                anchorMode = SearchAnchorMode.TopLeft,
                referenceSearchBox = IntRect(20, 20, 10, 10),
                expandPercent = SearchExpandRatio(0.0, 0.0, 0.0, 0.0),
            ),
            canUseReferenceSearch = true,
            recognitionType = RecognitionTypes.TemplateMatch,
        )
        assertNull(result)
    }

    @Test
    fun `explicit roi wins over reference search`() {
        val roi = IntRect(10, 20, 30, 40)
        val result = ReferenceSearch.tryGetRegion(
            srcWidth = 1920,
            srcHeight = 1080,
            roi = roi,
            referenceImageSize = IntSize(1920, 1080),
            referenceBoundingBox = IntRect(200, 150, 32, 32),
            searchOptions = null,
            canUseReferenceSearch = true,
        )
        assertEquals(roi, result!!.effectiveRoi)
        assertFalse(result.usedReferenceSearch)
        assertEquals(1.0, result.scale, 0.0)
    }

    @Test
    fun `partial reference config is rejected`() {
        val result = ReferenceSearch.tryGetRegion(
            srcWidth = 1920,
            srcHeight = 1080,
            roi = IntRect.EMPTY,
            referenceImageSize = IntSize(1920, 1080),
            referenceBoundingBox = null,
            searchOptions = SearchOptions(),
            canUseReferenceSearch = true,
        )
        assertNull(result)
    }

    @Test
    fun `reference search on cropped region is rejected`() {
        val result = ReferenceSearch.tryGetRegion(
            srcWidth = 400,
            srcHeight = 400,
            roi = IntRect.EMPTY,
            referenceImageSize = IntSize(1920, 1080),
            referenceBoundingBox = IntRect(200, 150, 32, 32),
            searchOptions = null,
            canUseReferenceSearch = false,
        )
        assertNull(result)
    }

    @Test
    fun `auto anchor uses right-bottom for far-corner bbox`() {
        val (h, v) = ReferenceSearch.resolveAnchor(
            SearchAnchorMode.Auto,
            IntRect(1800, 1000, 40, 40),
            IntSize(1920, 1080),
        )
        assertEquals(ReferenceSearch.HorizontalAnchor.Right, h)
        assertEquals(ReferenceSearch.VerticalAnchor.Bottom, v)
    }

    @Test
    fun `clone copies search options`() {
        val original = RecognitionObject().apply {
            searchOptions = SearchOptions(
                anchorMode = SearchAnchorMode.Center,
                referenceSearchBox = IntRect(10, 20, 30, 40),
                expandSize = IntSize(5, 6),
                expandPercent = SearchExpandRatio(0.1, 0.2, 0.3, 0.4),
            )
        }
        val cloned = original.clone()
        assertNotSame(original.searchOptions, cloned.searchOptions)
        assertEquals(original.searchOptions, cloned.searchOptions)
    }
}

@RunWith(Parameterized::class)
class ReferenceSearchExplicitAnchorTest(
    private val anchorMode: SearchAnchorMode,
    private val imageWidth: Int,
    private val imageHeight: Int,
    private val expected: IntRect,
) {
    @Test
    fun `explicit anchor transforms independent search box`() {
        val result = ReferenceSearch.tryGetRegion(
            srcWidth = imageWidth,
            srcHeight = imageHeight,
            roi = IntRect.EMPTY,
            referenceImageSize = IntSize(100, 100),
            referenceBoundingBox = IntRect(10, 20, 10, 10),
            searchOptions = SearchOptions(
                anchorMode = anchorMode,
                referenceSearchBox = IntRect(10, 20, 20, 20),
                expandPercent = SearchExpandRatio(0.0, 0.0, 0.0, 0.0),
            ),
            canUseReferenceSearch = true,
            recognitionType = RecognitionTypes.Ocr,
        )
        assertEquals(expected, result!!.effectiveRoi)
        assertEquals(IntSize(20, 20), result.effectiveTemplateSize)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1}x{2}")
        fun data(): List<Array<Any>> = listOf(
            arrayOf(SearchAnchorMode.TopLeft, 300, 200, IntRect(20, 40, 40, 40)),
            arrayOf(SearchAnchorMode.TopRight, 300, 200, IntRect(120, 40, 40, 40)),
            arrayOf(SearchAnchorMode.BottomLeft, 200, 300, IntRect(20, 140, 40, 40)),
            arrayOf(SearchAnchorMode.BottomRight, 200, 300, IntRect(20, 140, 40, 40)),
            arrayOf(SearchAnchorMode.BottomRight, 300, 200, IntRect(120, 40, 40, 40)),
            arrayOf(SearchAnchorMode.Center, 300, 200, IntRect(70, 40, 40, 40)),
            arrayOf(SearchAnchorMode.Center, 200, 300, IntRect(20, 90, 40, 40)),
        )
    }
}

@RunWith(Parameterized::class)
class ReferenceSearchAutoAnchorTest(
    private val bbox: IntRect,
    private val imageWidth: Int,
    private val imageHeight: Int,
    private val expected: IntRect,
) {
    @Test
    fun `auto preserves responsive layout`() {
        val result = ReferenceSearch.tryGetRegion(
            srcWidth = imageWidth,
            srcHeight = imageHeight,
            roi = IntRect.EMPTY,
            referenceImageSize = IntSize(1000, 1000),
            referenceBoundingBox = bbox,
            searchOptions = SearchOptions(
                anchorMode = SearchAnchorMode.Auto,
                expandPercent = SearchExpandRatio(0.0, 0.0, 0.0, 0.0),
            ),
            canUseReferenceSearch = true,
            recognitionType = RecognitionTypes.Ocr,
        )
        assertEquals(expected, result!!.effectiveRoi)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} -> {1}x{2}")
        fun data(): List<Array<Any>> = listOf(
            arrayOf(IntRect(100, 100, 20, 20), 1400, 1000, IntRect(100, 100, 20, 20)),
            arrayOf(IntRect(490, 100, 20, 20), 1400, 1000, IntRect(690, 100, 20, 20)),
            arrayOf(IntRect(880, 100, 20, 20), 1400, 1000, IntRect(1280, 100, 20, 20)),
            arrayOf(IntRect(100, 490, 20, 20), 1000, 1400, IntRect(100, 690, 20, 20)),
            arrayOf(IntRect(100, 880, 20, 20), 1000, 1400, IntRect(100, 1280, 20, 20)),
        )
    }
}
