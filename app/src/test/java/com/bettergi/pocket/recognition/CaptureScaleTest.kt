package com.bettergi.pocket.recognition

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureScaleTest {
    @Test
    fun `1080p keeps asset scale 1 and recognition size unchanged`() {
        val scale = CaptureScale.fromCaptureSize(1920, 1080)
        assertEquals(1.0, scale.assetScale, 0.0)
        assertEquals(1.0, scale.scaleTo1080PRatio, 0.0)
        assertEquals(1920, scale.recognitionWidth)
        assertEquals(1080, scale.recognitionHeight)
        assertEquals(1090, scale.scale1080P(1090))
    }

    @Test
    fun `1440p downscales recognition canvas to 1920 wide`() {
        val scale = CaptureScale.fromCaptureSize(2560, 1440)
        assertEquals(1.0, scale.assetScale, 0.0)
        assertEquals(2560 / 1920.0, scale.scaleTo1080PRatio, 1e-9)
        assertEquals(1920, scale.recognitionWidth)
        assertEquals(1080, scale.recognitionHeight)
    }

    @Test
    fun `720p shrinks 1080p templates and rois`() {
        val scale = CaptureScale.fromCaptureSize(1280, 720)
        assertEquals(1280 / 1920.0, scale.assetScale, 1e-9)
        assertEquals(1280, scale.recognitionWidth)
        assertEquals(720, scale.recognitionHeight)
        val roi = scale.rect1080P(1090, 330, 60, 420)
        assertEquals(727, roi.x)
        assertEquals(220, roi.y)
        assertEquals(40, roi.width)
        assertEquals(280, roi.height)
    }
}
