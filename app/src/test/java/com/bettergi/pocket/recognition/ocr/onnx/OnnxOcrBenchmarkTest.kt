package com.bettergi.pocket.recognition.ocr.onnx

import com.bettergi.pocket.recognition.IntRect
import nu.pattern.OpenCV
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * ONNX PaddleOCR **真实推理**基准（JVM 侧跑 onnxruntime + desktop OpenCV，不依赖模拟器）。
 *
 * 目的：把「识别速度」与「识别精度」变成可回归的数字，而不是真机上凭感觉。
 *
 * 说明：
 * - 样本用 Java2D 渲染合成（真字体，非 OpenCV 描边字体）——描边字体与真实文本差异过大，
 *   会让精度数字失真，无法代表真机表现。
 * - 中文样本依赖系统 CJK 字体，缺失时该段自动跳过（Assume），不是失败。
 * - 阈值取宽松值：基准用于**发现数量级退化**（如误改预处理/归一化），不是卡 CI 的硬门。
 */
class OnnxOcrBenchmarkTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCvNative() {
            // 必须走 desktop 加载：OpenCvRuntime 的 Android 分支在 JVM 上会「假成功」
            nu.pattern.OpenCV.loadLocally()
            Mat(2, 2, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0)).release()
        }
    }

    // ---- 资产定位（与 ScanEngineDryRunTest 同一套向上查找约定）----
    private fun onnxAssets(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(5) {
            val candidate = File(dir, "src/main/assets/onnx")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        throw IllegalStateException("src/main/assets/onnx not found (cwd=${System.getProperty("user.dir")})")
    }

    private fun buildService(): OnnxPaddleOcrService {
        val dir = onnxAssets()
        val engine = OnnxOcrEngine(File(dir, "det.onnx"), File(dir, "rec.onnx"))
        val dict = File(dir, "ppocrv6_tiny_dict.txt").readLines().filter { it.isNotEmpty() }
        val service = OnnxPaddleOcrService(engine, dict)
        check(service.prepare()) { "ONNX 引擎初始化失败（模型缺失或全部 EP 档不可用）" }
        return service
    }

    // ---- 合成样本 ----
    private fun font(size: Int, cjk: Boolean): Font? {
        val candidates = if (cjk) {
            listOf("PingFang SC", "Heiti SC", "Songti SC", "Noto Sans CJK SC", "Microsoft YaHei", "SansSerif")
        } else {
            listOf("SansSerif", "Helvetica", "Arial")
        }
        for (name in candidates) {
            val f = Font(name, Font.PLAIN, size)
            if (!cjk || f.canDisplay('遗')) return f
        }
        return null
    }

    /** 白底黑字渲染成 BGR Mat（与采集链产出的 BGR Mat 同构） */
    private fun renderText(text: String, size: Int = 56, cjk: Boolean = false): Mat {
        val f = font(size, cjk) ?: throw IllegalStateException("无可用字体 cjk=$cjk")
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_3BYTE_BGR).createGraphics()
        probe.font = f
        val fm = probe.fontMetrics
        val pad = size / 3
        val w = fm.stringWidth(text) + pad * 2
        val h = fm.height + pad * 2
        probe.dispose()

        val img = BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        g.color = Color.BLACK
        g.font = f
        g.drawString(text, pad, fm.ascent + pad)
        g.dispose()

        val bytes = (img.raster.dataBuffer as DataBufferByte).data
        val mat = Mat(img.height, img.width, CvType.CV_8UC3)
        mat.put(0, 0, bytes)
        return mat
    }

    private fun normalize(s: String): String = s.replace(Regex("\\s"), "")

    /** 字符级准确率 = 1 - 编辑距离/最长长度（对 OCR 这种「少字/多字/错字」混合误差更公允） */
    private fun charAccuracy(expected: String, actual: String): Double {
        if (expected.isEmpty()) return if (actual.isEmpty()) 1.0 else 0.0
        val dist = levenshtein(expected, actual)
        return 1.0 - dist.toDouble() / max(expected.length, actual.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = min(
                    min(dp[j] + 1, dp[j - 1] + 1),
                    prev + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private fun median(xs: LongArray): Long = xs.sorted()[xs.size / 2]

    // ================= 速度 =================

    @Test
    fun `det 与 rec 推理速度基准`() {
        val service = buildService()
        println("\n=== ONNX OCR 速度基准（JVM / tier=${service.tierLabel}）===")

        val sample = renderText("Lv.90 1026/2400", size = 56)
        try {
            // 预热（首次含 ORT 内部惰性分配）
            service.recognize(sample)

            // rec-only（稳态扫描主路径：固定 ROI 槽位，跳过 det）
            val roi = IntRect(0, 0, sample.cols(), sample.rows())
            val recTimes = LongArray(7) {
                val t0 = System.nanoTime()
                service.recognizeRois(sample, listOf(roi))
                (System.nanoTime() - t0) / 1_000_000
            }
            // 全管线（det + 每行 rec）
            val fullTimes = LongArray(5) {
                val t0 = System.nanoTime()
                service.recognize(sample)
                (System.nanoTime() - t0) / 1_000_000
            }

            val recMedian = median(recTimes)
            val fullMedian = median(fullTimes)
            println("rec-only（单槽）  中位 ${recMedian}ms  样本 ${recTimes.toList()}")
            println("全管线 det+rec   中位 ${fullMedian}ms  样本 ${fullTimes.toList()}")

            // 宽松上界：只拦数量级退化（真机 ARM 通常远快于 JVM x86 的桌面 ORT 构建）
            assertTrue("rec-only 过慢：${recMedian}ms", recMedian < 5_000)
            assertTrue("全管线过慢：${fullMedian}ms", fullMedian < 15_000)
        } finally {
            sample.release()
        }
    }

    @Test
    fun `多槽批量扫描吞吐`() {
        val service = buildService()
        val slot = renderText("ATK+31 4.7%", size = 48)
        try {
            val rects = List(10) { IntRect(0, 0, slot.cols(), slot.rows()) }
            service.recognizeRois(slot, rects) // 预热
            val t0 = System.nanoTime()
            val results = service.recognizeRois(slot, rects)
            val total = (System.nanoTime() - t0) / 1_000_000
            val hits = results.count { it.text.isNotBlank() }
            println("\n=== 10 槽批量 rec-only ===")
            println("总耗时 ${total}ms（每槽 %.1fms），非空命中 $hits/10".format(total / 10.0))
            assertEquals(10, results.size)
            assertTrue("10 槽批量过慢：${total}ms", total < 10_000)
        } finally {
            slot.release()
        }
    }

    // ================= 精度 =================

    @Test
    fun `数字与拉丁文本识别精度`() {
        val service = buildService()
        val samples = listOf(
            "Lv.90",
            "1026",
            "2400",
            "4.7%",
            "7.8%",
            "311",
            "23.4%",
        )

        var exact = 0
        var accSum = 0.0
        val rows = ArrayList<String>()
        for (text in samples) {
            val mat = renderText(text)
            val got = try {
                service.recognizeRois(mat, listOf(IntRect(0, 0, mat.cols(), mat.rows()))).firstOrNull()?.text ?: ""
            } finally {
                mat.release()
            }
            val exp = normalize(text)
            val act = normalize(got)
            val acc = charAccuracy(exp, act)
            if (exp.equals(act, ignoreCase = true)) exact++
            accSum += acc
            rows += "%-8s 期望=%-8s 实际=%-8s 字符准确率=%.2f".format(text, exp, act, acc)
        }
        val charAcc = accSum / samples.size
        println("\n=== ONNX OCR 精度（数字/拉丁，n=${samples.size}）===")
        rows.forEach { println(it) }
        println("完全匹配 ${exact}/${samples.size}，字符级准确率 %.3f".format(charAcc))

        // 宽松门槛：发现「预处理/归一化/类数」这类会整体崩掉的错误即可
        assertTrue("字符级准确率过低：%.3f".format(charAcc), charAcc >= 0.70)
    }

    @Test
    fun `中文文本识别精度`() {
        val cjkFont = font(56, cjk = true)
        org.junit.Assume.assumeTrue("JVM 无 CJK 字体，跳过中文精度用例", cjkFont != null)

        val service = buildService()
        val samples = listOf("圣遗物", "攻击力", "暴击率", "元素精通", "生命值")

        var exact = 0
        var accSum = 0.0
        val rows = ArrayList<String>()
        for (text in samples) {
            val mat = renderText(text, cjk = true)
            val got = try {
                service.recognizeRois(mat, listOf(IntRect(0, 0, mat.cols(), mat.rows()))).firstOrNull()?.text ?: ""
            } finally {
                mat.release()
            }
            val exp = normalize(text)
            val act = normalize(got)
            val acc = charAccuracy(exp, act)
            if (exp == act) exact++
            accSum += acc
            rows += "%-6s 期望=%-6s 实际=%-6s 字符准确率=%.2f".format(text, exp, act, acc)
        }
        val charAcc = accSum / samples.size
        println("\n=== ONNX OCR 精度（中文，n=${samples.size}）===")
        rows.forEach { println(it) }
        println("完全匹配 ${exact}/${samples.size}，字符级准确率 %.3f".format(charAcc))

        // 中文字形复杂，门槛低于拉丁；合成文本（非游戏内描边艺术字）应当远好于 0.5
        assertTrue("中文字符级准确率过低：%.3f".format(charAcc), charAcc >= 0.50)
    }

    @Test
    fun `整帧 recognize 能检出文本行且坐标在界内`() {
        val service = buildService()
        val mat = renderText("Lv.90\n1026/2400", size = 56)
        try {
            val result = service.recognize(mat)
            println("\n=== 整帧 recognize ===\nregions=${result.regions.size} text='${result.text}'")
            result.regions.forEach { println("  rect=${it.rect} text='${it.text}' score=%.3f".format(it.score)) }

            assertTrue("整帧未检出任何文本行", result.regions.isNotEmpty())
            for (region in result.regions) {
                assertTrue("rect 越界：${region.rect}", region.rect.x >= 0 && region.rect.y >= 0)
                assertTrue(
                    "rect 超出图像：${region.rect} vs ${mat.cols()}x${mat.rows()}",
                    region.rect.right <= mat.cols() && region.rect.bottom <= mat.rows(),
                )
                assertTrue("置信度应在 0..1：${region.score}", region.score in 0f..1f)
            }
        } finally {
            mat.release()
        }
    }

    // ================= 契约（防回归）=================

    @Test
    fun `字典与类数契约不被漂移`() {
        val dir = onnxAssets()
        val dict = File(dir, "ppocrv6_tiny_dict.txt").readLines().filter { it.isNotEmpty() }
        assertEquals("字典条目数漂移 —— 换模型时必须同步 MODEL_CLASS_COUNT", 6904, dict.size)
        assertEquals("MODEL_CLASS_COUNT 漂移", 6906, OnnxPaddleOcrService.MODEL_CLASS_COUNT)
        // dict 不含 blank：模型类 0 是 CTC blank，类 N 对应 dict[N-1]
        assertTrue("字典不应含空串（blank 不属于字典）", dict.none { it.isEmpty() })
    }

    @Test
    fun `rec 动态宽计算与 PP-OCR resize_to_h48 对齐`() {
        assertEquals(960, OnnxPaddleOcrService.scaledWidthFor(640, 32))
        assertEquals(48, OnnxPaddleOcrService.scaledWidthFor(48, 48))
        assertEquals(1, OnnxPaddleOcrService.scaledWidthFor(0, 10))
    }

    private fun caps(
        vendor: String,
        big: Int = 4,
        arm64: Boolean = true,
        qnn: Boolean = false,
    ) = DeviceCapabilities(
        socVendor = vendor,
        socModel = "test-$vendor",
        isArm64 = arm64,
        cpuCount = big * 2,
        bigCoreCount = big,
        bigCoreMaxFreqKHz = 2_360_000,
        qnnLibsLoadable = qnn,
    )

    @Test
    fun `默认候选序只有 CPU —— 不把启动赌在抓不住的失败上`() {
        // NNAPI：BlueStacks 与华为 P20 上都是 libonnxruntime.so 内原生 SIGSEGV（Java 抓不到，启动即崩）。
        // XNNPACK：Kirin 970 上 createSession 挂起（同样抓不到，且会拖住后续建会话）。
        // ⇒ 两者都要 allowRisky 才进候选；默认序必须与"直接选 CPU"的旧行为逐位一致。
        listOf("qualcomm", "hisilicon", "mediatek", "samsung", "unknown").forEach { v ->
            assertEquals(
                "vendor=$v 默认不该尝试任何风险档",
                listOf(EpTierPicker.Tier.CPU),
                EpTierPicker.candidatesFor(caps(v)),
            )
        }
    }

    @Test
    fun `QNN 只在高通且运行库可加载时进候选，并排在 CPU 前`() {
        assertEquals(
            listOf(EpTierPicker.Tier.QNN, EpTierPicker.Tier.CPU),
            EpTierPicker.candidatesFor(caps("qualcomm", qnn = true)),
        )
        // 高通但没带库 ⇒ 不进（AAR 不含 libQnnHtp.so）
        assertEquals(
            listOf(EpTierPicker.Tier.CPU),
            EpTierPicker.candidatesFor(caps("qualcomm", qnn = false)),
        )
        // 非高通即使误报"库可加载"也不给 QNN（纯函数自身要守住，不依赖采集层的闸门）
        assertEquals(
            listOf(EpTierPicker.Tier.CPU),
            EpTierPicker.candidatesFor(caps("hisilicon", qnn = true)),
        )
    }

    @Test
    fun `allowRisky 才放出 XNNPACK 与 NNAPI，且海思永远不给 NNAPI`() {
        // 高通 + 非模拟器：NNAPI 与 XNNPACK 都放出，按优先级排在 CPU 前
        assertEquals(
            listOf(EpTierPicker.Tier.NNAPI, EpTierPicker.Tier.XNNPACK, EpTierPicker.Tier.CPU),
            EpTierPicker.candidatesFor(caps("qualcomm", qnn = false), allowRisky = true),
        )
        // 模拟器上即使 allowRisky 也不给 NNAPI（BlueStacks 实测 SIGSEGV）
        assertEquals(
            listOf(EpTierPicker.Tier.XNNPACK, EpTierPicker.Tier.CPU),
            EpTierPicker.candidatesFor(caps("unknown", arm64 = false), allowRisky = true),
        )
        // 海思：NNAPI 与 XNNPACK 都被排除（实测挂起），只剩 CPU
        assertEquals(
            "海思开了 allowRisky 也不该冒出风险档",
            listOf(EpTierPicker.Tier.CPU),
            EpTierPicker.candidatesFor(caps("hisilicon"), allowRisky = true),
        )
    }

    @Test
    fun `大核计数按逐核频率分组，不能按 cpufreq policy 目录数`() {
        // 华为 P20 实测：4×1844000 + 4×2362000，但 /sys/.../cpufreq/ 下只有 policy0、policy1 两个目录。
        // 按 policy 数会得出 big=1 ⇒ intra 候选退化成 {1} ⇒ 自动择优静默失效（真实踩过的坑）。
        val p20 = List(4) { 1_844_000L } + List(4) { 2_362_000L }
        assertEquals(4, DeviceCapabilities.topCount(p20, caps0 = 8))
        // 同构核（只有一个值）⇒ 全部算"大核"，把候选放宽，后面有实测择优兜着
        assertEquals(8, DeviceCapabilities.topCount(List(8) { 2_400_000L }, caps0 = 8))
        // 真读不到 ⇒ 0，调用方据此退回保守候选，不猜
        assertEquals(0, DeviceCapabilities.topCount(emptyList(), caps0 = 8))
        // 2 小 + 6 大
        assertEquals(6, DeviceCapabilities.topCount(List(2) { 1_800_000L } + List(6) { 2_800_000L }, caps0 = 8))
    }

    @Test
    fun `intra-op 候选按大核簇收窄，读不到核数时退回单个 2`() {
        // 华为 Kirin 970：4 大核 ⇒ 候选 {2,4}（6 被簇宽截断后与 4 重）
        assertEquals(listOf(2, 4), EpTierPicker.intraOpCandidates(caps("hisilicon", big = 4)))
        // 8 大核 ⇒ {2,4,6}
        assertEquals(listOf(2, 4, 6), EpTierPicker.intraOpCandidates(caps("qualcomm", big = 8)))
        // 双核小机器 ⇒ 只有 2
        assertEquals(listOf(2), EpTierPicker.intraOpCandidates(caps("unknown", big = 2)))
        // 读不到拓扑（big=0）⇒ 不猜，退回单候选 2 ⇒ prepare 会跳过基准
        assertEquals(listOf(2), EpTierPicker.intraOpCandidates(caps("unknown", big = 0)))
    }

    /**
     * #74：intra-op **换档**必须带余量（"取最小中位数"会把生产永久调到慢档）。
     * 数值全部来自 2026-09-26 华为 P20 同机构建的五轮实测（见 `EpTierPicker.intraOpCandidates` 的表）。
     */
    @Test
    fun `intra-op 只在挑战者快出余量时才换档`() {
        // 四里的慢档：4 比 2 慢 2.2~2.3× ⇒ 留 2
        assertEquals("慢档不许换", 2, EpTierPicker.pickIntraOp(mapOf(2 to 643L, 4 to 1420L), incumbent = 2))
        assertEquals("慢档不许换(第二轮)", 2, EpTierPicker.pickIntraOp(mapOf(2 to 406L, 4 to 940L), incumbent = 2))
        // 优势不足 20%（340 比 406 只快 16.3%）⇒ 不换
        assertEquals("优势 16% < 余量 20% ⇒ 不换", 2, EpTierPicker.pickIntraOp(mapOf(2 to 406L, 4 to 340L), incumbent = 2))
        // 恰好差 20%（320 = 400×0.8）⇒ 边界算"优势不足"，不换
        assertEquals("恰好 20% 属边界，按不换", 2, EpTierPicker.pickIntraOp(mapOf(2 to 400L, 4 to 320L), incumbent = 2))
        // 15:57 那次孤例（254 vs 384 = 快 34%）⇒ **余量拦不住**，这条如实记下：
        //   对它的防御是 runs 3→15 的中位数，而不是余量。别把本用例读成"换错已被杜绝"。
        assertEquals("快 34% 会换档（余量拦不住孤例读数，防御靠 runs=15 中位）", 4,
            EpTierPicker.pickIntraOp(mapOf(2 to 384L, 4 to 254L), incumbent = 2))
        // 保守退化：表空 / 打平 / incumbent 没测到 / incumbent 建会话失败
        assertEquals("无基准 ⇒ 不动", 2, EpTierPicker.pickIntraOp(emptyMap(), incumbent = 2))
        assertEquals("打平 ⇒ 留住 incumbent", 2, EpTierPicker.pickIntraOp(mapOf(2 to 500L, 4 to 500L), incumbent = 2))
        assertEquals("incumbent 没测到 ⇒ 取唯一实测者", 4, EpTierPicker.pickIntraOp(mapOf(4 to 500L), incumbent = 2))
        assertEquals("incumbent 建会话失败 ⇒ 必须换", 4,
            EpTierPicker.pickIntraOp(mapOf(2 to Long.MAX_VALUE, 4 to 500L), incumbent = 2))
    }

    @Test
    fun `EP 降档沿调用方给的候选序，到兜底不再降`() {
        val order = listOf(EpTierPicker.Tier.QNN, EpTierPicker.Tier.CPU)
        assertEquals(EpTierPicker.Tier.CPU, EpTierPicker.degrade(EpTierPicker.Tier.QNN, order))
        org.junit.Assert.assertNull("CPU 为兜底，不应再降", EpTierPicker.degrade(EpTierPicker.Tier.CPU, order))
        org.junit.Assert.assertNull(
            "不在序里的档不参与降档",
            EpTierPicker.degrade(EpTierPicker.Tier.NNAPI, order),
        )
    }

    @Test
    fun `EP 基准择优取中位最小者，无数据落 CPU`() {
        assertEquals(
            EpTierPicker.Tier.XNNPACK,
            EpTierPicker.pickByBenchmark(
                mapOf(EpTierPicker.Tier.XNNPACK to 40L, EpTierPicker.Tier.CPU to 60L),
                setOf(EpTierPicker.Tier.XNNPACK, EpTierPicker.Tier.CPU),
            ),
        )
        assertEquals(
            "没有任何基准数据时必须落 CPU",
            EpTierPicker.Tier.CPU,
            EpTierPicker.pickByBenchmark(emptyMap(), setOf(EpTierPicker.Tier.XNNPACK)),
        )
    }

    @Test
    fun `CTC 解码：blank 与相邻去重语义`() {
        // 词表：[blank, A, B]；时间步：A A blank B B → 期望 "AB"
        val dict = listOf("A", "B")
        val c = 3
        val logits = floatArrayOf(
            0f, 1f, 0f, // A
            0f, 1f, 0f, // A（重复，去重）
            1f, 0f, 0f, // blank
            0f, 0f, 1f, // B
            0f, 0f, 1f, // B（重复，去重）
        )
        val res = CtcDecoder.decode(logits, 5, c, dict)
        assertEquals("AB", res.text)
        assertTrue("置信度应在 0..1：${res.confidence}", res.confidence in 0f..1f)
    }
}
