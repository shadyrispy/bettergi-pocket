package com.bettergi.pocket.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A23（optimization-plan-20260930 轨 2B）回归：
 * 精确分辨率目录（`${w}x${h}/`）命中的模板**不得**再吃 legacy asset scale（×captureW/1920
 * 二次缩小 ⇒ 模板与画面失配）；legacy scale 只对回退目录（基准 1920x1080 / 任务根）生效。
 *
 * 纯 JVM：AssetManager 是 Android 类型，JVM 无法构造 —— 路径解析抽成 [TemplateAssetLoader.resolveAssetPath]
 * 纯函数（exists 探测注入），这里直接对决策逻辑断言；imdecode/resize 分支由真机验证。
 */
class TemplateAssetLoaderTest {

    /** 按给定存活路径集合构造 exists 探测。 */
    private fun existsFn(vararg paths: String): (String) -> Boolean {
        val set = paths.toSet()
        return { it in set }
    }

    @Test
    fun `A23 命中精确分辨率目录时标记 exactHit`() {
        val resolution = TemplateAssetLoader.resolveAssetPath(
            taskName = "domain",
            fileName = "icon.png",
            captureWidth = 2560,
            captureHeight = 1600,
            exists = existsFn(
                "recognition/domain/2560x1600/icon.png",
                "recognition/domain/1920x1080/icon.png",
            ),
        )
        assertEquals("recognition/domain/2560x1600/icon.png", resolution.path)
        assertTrue(resolution.exactHit)
    }

    @Test
    fun `A23 未命中精确目录时回退基准目录并标记 non-exact`() {
        val resolution = TemplateAssetLoader.resolveAssetPath(
            taskName = "domain",
            fileName = "icon.png",
            captureWidth = 2560,
            captureHeight = 1600,
            exists = existsFn("recognition/domain/1920x1080/icon.png"),
        )
        assertEquals("recognition/domain/1920x1080/icon.png", resolution.path)
        assertFalse(resolution.exactHit)
    }

    @Test
    fun `A23 回退任务根目录同样标记 non-exact`() {
        val resolution = TemplateAssetLoader.resolveAssetPath(
            taskName = "domain",
            fileName = "icon.png",
            captureWidth = 1600,
            captureHeight = 900,
            exists = existsFn("recognition/domain/icon.png"),
        )
        assertEquals("recognition/domain/icon.png", resolution.path)
        assertFalse(resolution.exactHit)
    }

    @Test
    fun `A23 三级都未命中时抛明确异常`() {
        assertThrows(IllegalArgumentException::class.java) {
            TemplateAssetLoader.resolveAssetPath(
                "domain",
                "icon.png",
                1600,
                900,
                exists = { false },
            )
        }
    }
}
