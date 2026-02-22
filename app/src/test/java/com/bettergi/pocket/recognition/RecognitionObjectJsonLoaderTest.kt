package com.bettergi.pocket.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

class RecognitionObjectJsonLoaderTest {
    @Test
    fun `load talk history and chat icon search options`() {
        val json = """
            {
              "version": 1,
              "objects": {
                "TalkHistory": {
                  "type": "Ocr",
                  "reference": {
                    "size": [3040, 1904],
                    "bbox": "rect(375, 72, 55, 47)"
                  }
                },
                "ChatIcon": {
                  "type": "Ocr",
                  "reference": {
                    "size": [3040, 1904],
                    "bbox": "rect(1930, 1342, 52, 30)"
                  },
                  "search": {
                    "box": "rect(1787, 126, 1098, 1470)"
                  }
                }
              }
            }
        """.trimIndent()

        val talk = RecognitionObjectJsonLoader.load(json, "TalkHistory", unusedContext())
        assertEquals(RecognitionTypes.Ocr, talk.recognitionType)
        assertEquals(IntSize(3040, 1904), talk.referenceImageSize)
        assertEquals(IntRect(375, 72, 55, 47), talk.referenceBoundingBox)
        assertNull(talk.searchOptions)

        val chat = RecognitionObjectJsonLoader.load(json, "ChatIcon", unusedContext())
        assertNotNull(chat.searchOptions)
        assertEquals(SearchAnchorMode.Auto, chat.searchOptions!!.anchorMode)
        assertEquals(IntRect(1787, 126, 1098, 1470), chat.searchOptions!!.referenceSearchBox)
    }

    @Test
    fun `invalid expand percent throws`() {
        val json = """
            {
              "objects": {
                "Target": {
                  "type": "Ocr",
                  "search": { "expandPercent": [-0.1] }
                }
              }
            }
        """.trimIndent()
        try {
            RecognitionObjectJsonLoader.load(json, "Target", unusedContext())
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun unusedContext(): RecognitionObjectJsonLoadContext {
        return RecognitionObjectJsonLoadContext(
            captureWidth = 1920,
            captureHeight = 1080,
            templateLoader = { _, _ -> throw IllegalStateException("OCR 配置不应加载模板图片") },
        )
    }
}

@RunWith(Parameterized::class)
class RecognitionObjectJsonExpandPercentTest(
    private val jsonArray: String,
    private val expected: SearchExpandRatio,
) {
    @Test
    fun `expandPercent uses xaml thickness order`() {
        val json = """
            {
              "objects": {
                "Target": {
                  "type": "Ocr",
                  "reference": {
                    "size": [1920, 1080],
                    "bbox": "rect(200, 150, 32, 32)"
                  },
                  "search": {
                    "anchor": "TopRight",
                    "box": "rect(100, 80, 300, 200)",
                    "expand": [99, 88],
                    "expandPercent": $jsonArray
                  }
                }
              }
            }
        """.trimIndent()
        val ro = RecognitionObjectJsonLoader.load(
            json,
            "Target",
            RecognitionObjectJsonLoadContext(1920, 1080) { _, _ ->
                throw IllegalStateException("OCR 配置不应加载模板图片")
            },
        )
        assertEquals(SearchAnchorMode.TopRight, ro.searchOptions!!.anchorMode)
        assertEquals(IntRect(100, 80, 300, 200), ro.searchOptions!!.referenceSearchBox)
        assertEquals(IntSize(99, 88), ro.searchOptions!!.expandSize)
        assertEquals(expected, ro.searchOptions!!.expandPercent)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): List<Array<Any>> = listOf(
            arrayOf("[0]", SearchExpandRatio(0.0, 0.0, 0.0, 0.0)),
            arrayOf("[0.05]", SearchExpandRatio(0.05, 0.05, 0.05, 0.05)),
            arrayOf("[0.1, 0.2]", SearchExpandRatio(0.1, 0.2, 0.1, 0.2)),
            arrayOf("[0.1, 0.2, 0.3, 0.4]", SearchExpandRatio(0.1, 0.2, 0.3, 0.4)),
        )
    }
}
