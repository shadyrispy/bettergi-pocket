package com.bettergi.pocket.recognition.ocr.onnx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A24（optimization-plan-20260930 轨 2B）回归：
 *
 * - manifest 解析失败按**校验失败**处理（触发重拷），而不是返回 emptyMap 让 SHA256 校验
 *   恒跳过（损坏/旧版模型永不重拷）。JVM 侧可测的是纯解析函数 [OnnxModelAssets.parseManifest]
 *   与"解析失败 ≠ 有内容"的契约；「重拷链路」的 Context/assets 分支无法脱离 Android 构造，
 *   由真机/模拟器验证（链路：ensure() → 解析失败 → 全量重拷 → 重读 → 仍失败则 Log.w 降级沿用旧模型）。
 * - 字典条目数不符由 Log.w 升级为**硬失败**：init 抛 IllegalStateException →
 *   OcrFactory.createOnnx 的 runCatching 通路 → OCR 不可用（prepare false 语义）。
 */
class OnnxModelAssetsTest {

    @Test
    fun `A24 manifest 合法内容解析为 sha 映射`() {
        val parsed = OnnxModelAssets.parseManifest(
            """
            {"recOutputType":"auto","det.onnx":"aa","rec.onnx":"bb","ppocrv6_tiny_dict.txt":"cc"}
            """.trimIndent(),
        )
        assertEquals("aa", parsed?.get("det.onnx"))
        assertEquals("bb", parsed?.get("rec.onnx"))
        assertEquals("cc", parsed?.get(OnnxModelAssets.DICT_NAME))
        assertEquals("auto", parsed?.get(OnnxModelAssets.REC_OUTPUT_TYPE_KEY))
    }

    @Test
    fun `A24 manifest 损坏返回 null 而不是 emptyMap`() {
        // 恰好是"重拷判定"的关键分叉：null → ensure() 触发全量重拷；
        // 旧实现的 emptyMap → expected==null → SHA 校验恒跳过（被修复的 bug）。
        assertNull(OnnxModelAssets.parseManifest("not a json {{{"))
        assertNull(OnnxModelAssets.parseManifest(""))
        // 类型不符（数组而非对象）同样算损坏
        assertNull(OnnxModelAssets.parseManifest("[\"det.onnx\"]"))
        // 截断的 JSON（模拟写坏/旧版本残留）同样算损坏
        assertNull(OnnxModelAssets.parseManifest("{\"det.onnx\": \"aa\""))
    }

    @Test
    fun `A24 仓库 manifest 可解析且三个资产 sha 齐全`() {
        // 对账：manifest.json 新增可选键（recOutputType）不得挤掉三个资产的 sha256 条目，
        // 否则 SHA 校验对该文件退化为"存在即过"。
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(5) {
            val candidate = File(dir, "src/main/assets/onnx")
            if (candidate.isDirectory) {
                val parsed = OnnxModelAssets.parseManifest(File(candidate, "manifest.json").readText())
                assertTrue("manifest 解析失败", parsed != null)
                for (name in listOf("det.onnx", "rec.onnx", OnnxModelAssets.DICT_NAME)) {
                    assertTrue("$name 缺少 sha256 条目", parsed?.get(name)?.length == 64)
                }
                return
            }
            dir = dir.parentFile ?: return@repeat
        }
        org.junit.Assume.assumeTrue("src/main/assets/onnx not found，跳过对账", false)
    }

    @Test
    fun `A24 字典条目数不符硬失败`() {
        val engine = OnnxOcrEngine(
            File("/nonexistent/bettergi-pocket/det.onnx"),
            File("/nonexistent/bettergi-pocket/rec.onnx"),
        )
        try {
            // 字典错绑（irminsul 20260827 教训）= 全量乱码 ⇒ 宁可 OCR 不可用也不能静默错下去
            val thrown = runCatching { OnnxPaddleOcrService(engine, listOf("a", "b")) }
            assertTrue("字典条目数不符应在 init 抛出", thrown.isFailure)
            assertTrue(
                "应为 IllegalStateException：${thrown.exceptionOrNull()}",
                thrown.exceptionOrNull() is IllegalStateException,
            )
            // 条目数正确时构造通过（桩条目即可，构造不建会话）
            OnnxPaddleOcrService(engine, List(OnnxPaddleOcrService.EXPECTED_DICT_SIZE) { "桩" })
        } finally {
            engine.close()
        }
    }
}
