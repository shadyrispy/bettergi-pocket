package com.bettergi.pocket.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrTextTest {
    @Test
    fun `remove spaces and tabs only`() {
        assertEquals("拾取调查\n继续", OcrText.removeAllSpace("拾取 调查\n继\t续"))
    }

    @Test
    fun `replacements map wrong spellings to canonical`() {
        val text = OcrText.applyReplacements(
            "调査",
            mapOf("调查" to listOf("调査", "週查")),
        )
        assertEquals("调查", text)
    }

    @Test
    fun `ocr match requires all contain and any one contain and all regex`() {
        val text = "关闭剧情自动播放"
        assertTrue(
            OcrText.matches(
                text,
                allContain = listOf("剧情"),
                oneContain = listOf("自动", "跳过"),
                regex = listOf("关闭.+播放"),
            ),
        )
        assertFalse(
            OcrText.matches(
                text,
                allContain = listOf("剧情", "不存在"),
                oneContain = emptyList(),
                regex = emptyList(),
            ),
        )
        assertFalse(
            OcrText.matches(
                text,
                allContain = emptyList(),
                oneContain = listOf("跳过"),
                regex = emptyList(),
            ),
        )
    }
}
