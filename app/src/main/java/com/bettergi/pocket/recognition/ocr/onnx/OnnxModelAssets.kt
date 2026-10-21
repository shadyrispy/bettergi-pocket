package com.bettergi.pocket.recognition.ocr.onnx

import android.content.Context
import android.util.Log
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

    /** ensure() 后缓存的 manifest 内容；解析失败为 null（降级路径）。 */
    private var manifest: Map<String, String>? = null

    /** 返回是否就绪；任一项缺失即失败 */
    fun ensure(): Boolean {
        if (!modelDir.isDirectory && !modelDir.mkdirs()) return false
        var loaded = loadManifest()
        if (loaded == null) {
            // A24：manifest 解析失败 ≠ "没有校验信息"，而是**校验失败** —— 旧逻辑返回 emptyMap
            // 会让 SHA256 校验恒跳过，损坏/旧版模型永不重拷。这里按校验失败处理：
            // 全量重拷（含 manifest 本身），拷完重读；仍失败才降级告警并沿用旧模型。
            Log.w(TAG, "manifest.json 解析失败，按校验失败处理：全量重拷模型资产")
            for (name in REQUIRED) {
                copyFromAssets(name, File(modelDir, name))
            }
            loaded = loadManifest()
            if (loaded == null) {
                Log.w(TAG, "重拷后 manifest.json 仍不可解析，降级沿用旧模型（SHA 校验跳过）")
            }
        }
        manifest = loaded
        for (name in REQUIRED) {
            val target = File(modelDir, name)
            val expected = loaded?.get(name)
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

    /**
     * rec 输出类型声明（P3）：manifest 可选键 `recOutputType`。
     * - 缺省 / "auto"：维持旧口径（单值落在 0..1 视作概率，否则现场 softmax）——
     *   这是"靠数值范围猜"，仅作缺省兼容；
     * - "softmax"：导出已含 softmax，直接当概率用；
     * - "logits"：恒为原始 logits，解码时必做 softmax。
     * manifest 不可解析或未声明时返回 "auto"（行为与升级前一致）。
     */
    fun recOutputType(): String = manifest?.get(REC_OUTPUT_TYPE_KEY) ?: OUTPUT_AUTO

    private fun loadManifest(): Map<String, String>? {
        return try {
            appContext.assets.open("$ASSET_DIR/manifest.json").bufferedReader().use { it.readText() }
                .let { text -> parseManifest(text) }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "BetterGI.OnnxAssets"

        const val ASSET_DIR = "onnx"
        const val DICT_NAME = "ppocrv6_tiny_dict.txt"

        /** manifest 可选键：rec 输出类型（见 [recOutputType]）。 */
        const val REC_OUTPUT_TYPE_KEY = "recOutputType"
        const val OUTPUT_AUTO = "auto"

        /**
         * 预期资产清单。
         * 20260827 教训（irminsul）：rec.onnx 是 PP-OCRv6 模型，字典必须用配套的
         * ppocrv6_tiny_dict.txt；误绑 ppocr_keys_v1.txt（v1 字符序）会导致汉字全部解码错乱
         * （ASCII/数字看起来正常，极易漏检）。
         */
        val REQUIRED = listOf("det.onnx", "rec.onnx", DICT_NAME, "manifest.json")

        /**
         * manifest 解析（纯函数，JVM 可单测）。
         * A24：解析失败返回 **null**（= 校验失败，触发重拷），而不是 emptyMap（= 跳过校验）。
         */
        internal fun parseManifest(text: String): Map<String, String>? {
            return try {
                val obj = org.json.JSONObject(text)
                obj.keys().asSequence().associateWith { obj.getString(it) }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun copyFromAssets(name: String, target: File) {
        // A24：先写临时文件再落位；拷贝失败**保留旧文件**（重拷失败 = 降级告警沿用旧模型，
        // 而不是把唯一可用的旧副本删掉）。半写文件只存在于 .tmp，不会污染 target。
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            appContext.assets.open("$ASSET_DIR/$name").use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "拷贝 $name 失败，保留旧文件（降级沿用）", e)
            tmp.delete()
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
}
