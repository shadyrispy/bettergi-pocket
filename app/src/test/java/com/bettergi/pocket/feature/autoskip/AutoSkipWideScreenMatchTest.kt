package com.bettergi.pocket.feature.autoskip

import com.bettergi.pocket.recognition.CaptureContent
import com.bettergi.pocket.recognition.CaptureScale
import com.bettergi.pocket.recognition.IntRect
import com.bettergi.pocket.recognition.RecognitionObject
import com.bettergi.pocket.recognition.RecognitionObjectJsonLoadContext
import com.bettergi.pocket.recognition.RecognitionObjectJsonLoader
import com.bettergi.pocket.recognition.area.ReferenceSearch
import com.bettergi.pocket.recognition.opencv.OpenCvRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

class AutoSkipWideScreenMatchTest {
    @Test
    fun `talk history expandPercent covers the 2400x1080 hit`() {
        val scale = CaptureScale.fromCaptureSize(NATIVE_WIDTH, NATIVE_HEIGHT)
        val talk = loadObject("TalkHistory", scale.recognitionWidth, scale.recognitionHeight)
        val search = requireSearch(talk, scale)
        assertTrue(
            "TalkHistory ROI ${search.effectiveRoi} should contain $TALK_HIT",
            search.effectiveRoi.contains(TALK_HIT),
        )
    }

    @Test
    fun `chat icon left expandPercent covers the 2400x1080 hits`() {
        val scale = CaptureScale.fromCaptureSize(NATIVE_WIDTH, NATIVE_HEIGHT)
        val chat = loadObject("ChatIcon", scale.recognitionWidth, scale.recognitionHeight)
        val search = requireSearch(chat, scale)
        CHAT_HITS.forEach { hit ->
            assertTrue(
                "ChatIcon ROI ${search.effectiveRoi} should contain $hit",
                search.effectiveRoi.contains(hit),
            )
        }
    }

    @Test
    fun `wide screenshot matches talk history through CaptureContent`() {
        CaptureContent.fromBgr(loadShotBgr(), NATIVE_WIDTH, NATIVE_HEIGHT).use { content ->
            val talk = loadObject(
                "TalkHistory",
                content.captureRectArea.width,
                content.captureRectArea.height,
            )
            val hit = content.find(talk)
            assertTrue("TalkHistory should exist, score=${hit.matchScore}", hit.isExist())
            assertTrue(
                "TalkHistory score ${hit.matchScore} should pass threshold ${talk.threshold}",
                (hit.matchScore ?: 0.0) >= talk.threshold,
            )
            assertTrue(
                "TalkHistory $hit should sit on the list icon $TALK_HIT",
                TALK_HIT.contains(IntRect(hit.x, hit.y, 1, 1)) ||
                    overlapEnough(IntRect(hit.x, hit.y, hit.width, hit.height), TALK_HIT),
            )
        }
    }

    @Test
    fun `wide screenshot matches three chat icons through CaptureContent`() {
        CaptureContent.fromBgr(loadShotBgr(), NATIVE_WIDTH, NATIVE_HEIGHT).use { content ->
            val chat = loadObject(
                "ChatIcon",
                content.captureRectArea.width,
                content.captureRectArea.height,
            ).apply { maxMatchCount = 8 }
            val hits = content.findMulti(chat)
            assertEquals("should find three dialogue options", 3, hits.size)
            hits.forEach { hit ->
                assertTrue(
                    "ChatIcon score ${hit.matchScore} should pass ${chat.threshold}",
                    (hit.matchScore ?: 0.0) >= chat.threshold,
                )
            }
            val top = AutoSkipFeature.selectTopChatIcon(hits)!!
            assertEquals(CHAT_HITS.minOf { it.y }, top.y)
        }
    }

    private fun requireSearch(
        ro: RecognitionObject,
        scale: CaptureScale,
    ) = ReferenceSearch.tryGetRegion(
        srcWidth = scale.recognitionWidth,
        srcHeight = scale.recognitionHeight,
        roi = ro.regionOfInterest,
        referenceImageSize = ro.referenceImageSize,
        referenceBoundingBox = ro.referenceBoundingBox,
        searchOptions = ro.searchOptions,
        canUseReferenceSearch = true,
        recognitionType = ro.recognitionType,
    ) ?: error("reference search returned null for ${ro.name}")

    companion object {
        private const val NATIVE_WIDTH = 2400
        private const val NATIVE_HEIGHT = 1080
        private val TALK_HIT = IntRect(334, 37, 25, 21)
        private val CHAT_HITS = listOf(
            IntRect(1198, 417, 24, 14),
            IntRect(1198, 492, 24, 14),
            IntRect(1198, 566, 24, 14),
        )

        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            assertTrue("OpenCV failed to load for JVM unit tests", OpenCvRuntime.ensureLoaded())
        }

        private fun loadObject(objectName: String, captureWidth: Int, captureHeight: Int): RecognitionObject {
            val dir = autoSkipDir()
            return RecognitionObjectJsonLoader.load(
                json = File(dir, "Recognition.json").readText(Charsets.UTF_8),
                objectName = objectName,
                context = RecognitionObjectJsonLoadContext(
                    captureWidth = captureWidth,
                    captureHeight = captureHeight,
                    templateLoader = { fileName, _ ->
                        decodeAsset(File(dir, fileName))
                    },
                ),
            )
        }

        private fun loadShotBgr(): Mat = decodeAsset(shotFile())

        private fun decodeAsset(file: File): Mat {
            val bytes = file.readBytes()
            val encoded = Mat(1, bytes.size, CvType.CV_8UC1)
            encoded.put(0, 0, bytes)
            val decoded = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR)
            encoded.release()
            assertTrue("failed to decode ${file.name}", !decoded.empty())
            return decoded
        }

        private fun autoSkipDir(): File {
            return listOf(
                File("src/main/assets/recognition/AutoSkip"),
                File("app/src/main/assets/recognition/AutoSkip"),
            ).first { it.isDirectory }
        }

        private fun shotFile(): File {
            val resource = AutoSkipWideScreenMatchTest::class.java.getResource("/autoskip_2400x1080.jpeg")
            if (resource != null && resource.protocol == "file") {
                return File(resource.toURI())
            }
            return listOf(
                File("src/test/resources/autoskip_2400x1080.jpeg"),
                File("app/src/test/resources/autoskip_2400x1080.jpeg"),
            ).first { it.isFile }
        }

        private fun IntRect.contains(other: IntRect): Boolean {
            return x <= other.x &&
                y <= other.y &&
                x + width >= other.x + other.width &&
                y + height >= other.y + other.height
        }

        private fun overlapEnough(a: IntRect, b: IntRect): Boolean {
            val left = maxOf(a.x, b.x)
            val top = maxOf(a.y, b.y)
            val right = minOf(a.x + a.width, b.x + b.width)
            val bottom = minOf(a.y + a.height, b.y + b.height)
            if (right <= left || bottom <= top) return false
            val inter = (right - left) * (bottom - top)
            val minArea = minOf(a.width * a.height, b.width * b.height).coerceAtLeast(1)
            return inter * 2 >= minArea
        }
    }
}