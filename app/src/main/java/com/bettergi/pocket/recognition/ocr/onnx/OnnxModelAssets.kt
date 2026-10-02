package com.bettergi.pocket.recognition.ocr.onnx

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * ONNX 模型资产装载：assets/onnx/ → filesDir/onnx/，SHA-256 校验（manifest.json），缺失或损坏重拷。
 *
 * 为什么要拷到 filesDir：OrtSession 只能从文件系统路径加载模型，不能直接吃 assets 的 fd/流。
 *
 * 移植自 irminsul `com.esc.irminsul.ocr.ModelAssets`。
 * 注意：本类依赖 Context（仅供主进程使用）；[OnnxOcrEngine] 只吃文件路径，JVM 单测可脱离 Context 直接构造。
 */
class OnnxModelAssets(context: Context) {

    private val appContext = context.applicationContext
    val modelDir: File = File(appContext.filesDir, ASSET_DIR)

    /** 返回是否就绪；任一项缺失即失败 */
    fun ensure(): Boolean {
        if (!modelDir.isDirectory && !modelDir.mkdirs()) return false
        val manifest = loadManifest()
        for (name in REQUIRED) {
            val target = File(modelDir, name)
            val expected = manifest[name]
            if (!target.exists() || (expected != null && sha256(target) != expected)) {
                copyFromAssets(name, target)
            }
            if (!target.exists()) return false
        }
        return true
    }

    fun detModel(): File = File(modelDir, "det.onnx")

    fun recModel(): File = File(modelDir, "rec.onnx")

    fun dictFile(): File = File(modelDir, DICT_NAME)

    /**
     * 加载识别字典：跳过空行，返回纯字符列表。
     *
     * 契约（与 CTC 解码 `dict[best-1]` 对齐）：**dict 不含 blank**——
     * 模型类 0 是 CTC blank，模型类 N(≥1) 对应 dict[N-1]。
     * 因此字典条目数（6904）≠ 模型类数（[OnnxPaddleOcrService.MODEL_CLASS_COUNT]=6906）。
     */
    fun loadDict(): List<String> = dictFile().readLines().filter { it.isNotEmpty() }

    private fun loadManifest(): Map<String, String> {
        return try {
            appContext.assets.open("$ASSET_DIR/manifest.json").bufferedReader().use { it.readText() }
                .let { text ->
                    val obj = org.json.JSONObject(text)
                    obj.keys().asSequence().associateWith { obj.getString(it) }
                }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun copyFromAssets(name: String, target: File) {
        try {
            appContext.assets.open("$ASSET_DIR/$name").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (_: Exception) {
            target.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val ASSET_DIR = "onnx"
        const val DICT_NAME = "ppocrv6_tiny_dict.txt"

        /**
         * 预期资产清单。
         * 20260827 教训（irminsul）：rec.onnx 是 PP-OCRv6 模型，字典必须用配套的
         * ppocrv6_tiny_dict.txt；误绑 ppocr_keys_v1.txt（v1 字符序）会导致汉字全部解码错乱
         * （ASCII/数字看起来正常，极易漏检）。
         */
        val REQUIRED = listOf("det.onnx", "rec.onnx", DICT_NAME, "manifest.json")
    }
}
