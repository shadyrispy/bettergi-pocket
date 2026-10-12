package com.bettergi.pocket.recognition.ocr.onnx

import android.util.Log
import com.bettergi.pocket.core.IntRect
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * **推理级取证**（对齐 GOODScanner `observe_ocr`/`debug_dump` 设计）：在 rec 边界把
 * **实际喂给模型的那一行像素**（NCHW float 反归一化回灰度图）逐行落 PNG + 一行 manifest，
 * 空读、推理失败与合批失败后的逐行回退重试都各自记账。
 *
 * 与逐格全屏帧取证（`ScanEngine.dumpPanelShot`）的分工：那个回答「帧是不是旧的」，
 * 这个回答「模型看到了什么、读出了什么」——离线复盘误读行时不再需要拿全屏帧重跑 OCR。
 *
 * ⚠️ **开着不免费**（华为 2244 实测，同会话同钳频 `4-7:1364/1018MHz batt=41C`，1 页 21 格）：
 * 关 58299ms → 开 63262ms / 67601ms ⇒ **+0.24~0.44s/格（+9~16%）**，落盘 180 张行图 ≈1.0MB
 * + manifest 63KB = 1.4MB/页。两次 ON 之间相差 4.3s ⇒ 与效应同量级，只当量级用、别当精确数。
 *
 * 默认关：[dir] 为 null 时热路径只有一次 volatile null 读。开关由
 * `TriggerForegroundService.ACTION_DEBUG_SET_REC_DUMPS` 拨动，一轮一个带时间戳的目录，
 * 历史运行目录**永不删除、不压缩**（原始扫描证据规约）。
 *
 * 字段/槽位标签：域内调用点用 [withFields] 包装 OCR 调用把
 * panel/field 带进来（ThreadLocal，绑定发生在推理边界，与上游「标签只在名字或像素匹配时
 * 绑定」同理——不匹配就没有标签，绝不猜）。当前 artifact 域文件由并行工单持有、未接线，
 * 未接线时 manifest 的 panel/field 为空串，行图名退化为 ROI 坐标，仍可经 call/seq/线程对齐。
 */
object RecInferenceDump {

    /** 取证开关是否打开：调用方据此决定要不要构造标签表（关着时零分配）。 */
    val isOpen: Boolean get() = dir != null

    @Volatile
    var dir: File? = null
        private set

    private const val TAG = "BetterGI.Ocr.RecDump"

    /** 张数上限：单张行图（48×≤4096 PNG）实测几十 KB，3 万行仍可控在百 MB 级。 */
    private const val MAX_ROWS_PER_RUN = 30_000
    private const val MIN_FREE_BYTES = 32L shl 20
    private const val SPACE_CHECK_EVERY = 256

    private val lock = Any()
    private val rowSeq = AtomicInteger()
    private val callSeq = AtomicInteger()
    private var dropped = 0
    private var spaceCheckedAt = 0
    private var spaceOk = true

    // 复用缓冲（锁内独占）：行图 native Mat + 字节中转。仅当行宽（=组宽）变化时重建一次，
    // 上限 48×4096×1B ≈ 192KB/份，组内逐行零分配。
    private val rowMat = Mat()
    private var rowBytes = ByteArray(0)
    private var rowCols = -1

    // [0] = panel 名，[1] = 本次 readRois 调用的**逐槽标签表**（下标 = 该次调用里的行号）
    private val fieldCtx = ThreadLocal<Array<Any>?>()

    // 推理时长由调用方在推理回调线程上登记（回调点已有精确 t2），同一次调用的各行共用该值。
    private val inferNsTl = ThreadLocal.withInitial { LongArray(1) }
    // ThreadLocal.get() 返回平台类型 ⇒ 直接下标会报「只允许安全调用」。withInitial 保证非空，
    // 这里显式落实，不把「会不会 NPE」留给下一个人判断。
    private fun inferBuf(): LongArray = inferNsTl.get() ?: LongArray(1).also { inferNsTl.set(it) }

    fun start(dir: File) {
        synchronized(lock) {
            rowSeq.set(0)
            callSeq.set(0)
            dropped = 0
            spaceCheckedAt = 0
            spaceOk = true
            this.dir = dir
            guarded {
                File(dir, "manifest.jsonl").appendText(
                    JSONObject()
                        .put("kind", "run_start")
                        .put("wallMs", System.currentTimeMillis())
                        .toString() + "\n",
                )
            }
        }
        Log.i(TAG, "rec dump: ON -> ${dir.absolutePath}")
    }

    fun stop() {
        val d = dir ?: return
        synchronized(lock) {
            dir = null
            guarded {
                File(d, "manifest.jsonl").appendText(
                    JSONObject()
                        .put("kind", "run_end")
                        .put("rows", rowSeq.get())
                        .put("dropped", dropped)
                        .put("wallMs", System.currentTimeMillis())
                        .toString() + "\n",
                )
            }
        }
        Log.i(TAG, "rec dump: OFF（已落 ${rowSeq.get()} 行，丢弃 $dropped，dir=${d.absolutePath}）")
    }

    /** 一次真实推理调用（单行或合批一组）的编号；关时返回 -1，调用方据此跳过 recordRow。 */
    fun newCall(): Int = if (dir == null) -1 else callSeq.incrementAndGet()

    /** 推理回调里登记本次 run 时长（纳秒）；关时仅一次 volatile null 读。 */
    fun noteInferNs(ns: Long) {
        if (dir == null) return
        inferBuf()[0] = ns
    }

    /**
     * 域内调用点包装：把面板名 + **本次 readRois 的逐槽标签**带到推理边界。
     * 标签按行号解析：合批路径的 rowIdx 就是
     * rects 下标，逐行回退路径由调用方把原始下标作为 `slot` 传进 recognizeLine。
     */
    suspend fun <T> withFields(panel: String, labels: List<String>, block: suspend () -> T): T {
        if (dir == null) return block()
        val prev = fieldCtx.get()
        fieldCtx.set(arrayOf(panel, labels))
        return try {
            block()
        } finally {
            fieldCtx.set(prev)
        }
    }

    /** 本次 readRois 调用的逐槽标签（供 recognizeRois **按原始 rect 下标**取用后逐行传下去）。 */
    @Suppress("UNCHECKED_CAST")
    fun labels(): List<String>? = (fieldCtx.get()?.getOrNull(1)) as? List<String>

    /** 本次调用的面板名；未接线回空串。 */
    fun panelName(): String = fieldCtx.get()?.getOrNull(0) as? String ?: "" 

    /**
     * 落一行：[src] 从 [base] 起是 R 通道平面（行主序、行距 [planeStride]、[REC_H] 行），
     * 即喂给模型的归一化张量（`v/127.5 − 1`），反归一化回 0..255 灰度。
     * pad 列张量值为 [OnnxPaddleOcrService.REC_PAD_FILL]（-1）⇒ 还原后是纯黑，肉眼可辨。
     * 关时第一行返回。图与 manifest 成败一致（写图失败不记清单，杜绝幽灵取证图）。
     */
    fun recordRow(
        call: Int,
        rowIdx: Int,
        field: String,
        rect: IntRect,
        scaledW: Int,
        groupW: Int,
        batch: Int,
        text: String?,
        conf: Float,
        err: String?,
        src: FloatArray,
        base: Int,
        planeStride: Int,
    ) {
        val d = dir ?: return
        synchronized(lock) {
            if (!gateOpen(d)) return
            val seq = rowSeq.incrementAndGet()
            val panel = panelName()
            val label = if (panel.isNotEmpty() || field.isNotEmpty()) {
                sanitize(if (panel.isEmpty()) field else "$panel.$field")
            } else {
                "x${rect.x}_y${rect.y}_w${rect.width}_h${rect.height}"
            }
            val name = String.format("r%05d_c%04d_%02d_%s", seq, call, rowIdx, label)
            val inf = inferBuf()
            val png = "$name.png"
            val ok = guarded {
                if (rowCols != planeStride) {
                    rowMat.create(OnnxPaddleOcrService.REC_H, planeStride, CvType.CV_8UC1)
                    rowBytes = ByteArray(OnnxPaddleOcrService.REC_H * planeStride)
                    rowCols = planeStride
                }
                val bytes = rowBytes
                val n = bytes.size
                var p = base
                for (i in 0 until n) {
                    val v = ((src[p + i] + 1f) * 127.5f).roundToInt()
                    bytes[i] = (if (v < 0) 0 else if (v > 255) 255 else v).toByte()
                }
                rowMat.put(0, 0, bytes)
                Imgcodecs.imwrite(File(d, png).absolutePath, rowMat)
            }.getOrDefault(false)
            if (!ok) {
                if (dropped == 0) Log.w(TAG, "rec dump: 写图失败 $png（不记 manifest）")
                dropped++
                return
            }
            val json = JSONObject()
                .put("seq", seq)
                .put("call", call)
                .put("row", rowIdx)
                .put("thread", Thread.currentThread().name)
                .put("panel", panel)
                .put("field", field)
                .put("roi", JSONObject().put("x", rect.x).put("y", rect.y).put("w", rect.width).put("h", rect.height))
                .put("scaledW", scaledW)
                .put("groupW", groupW)
                .put("pad", groupW - scaledW)
                .put("batch", batch)
                .put("empty", text.isNullOrEmpty())
                .put("text", text ?: "")
                .put("conf", conf.toDouble())
                .put("inferNs", inf[0])
                .put("img", png)
                .put("wallMs", System.currentTimeMillis())
            err?.let { json.put("err", it) }
            guarded { File(d, "manifest.jsonl").appendText(json.toString() + "\n") }
            if (seq % 500 == 0) Log.i(TAG, "rec dump: 已落 $seq 行（dir=${d.absolutePath}）")
        }
    }

    /** 闸门（锁内调用）：张数上限 + 剩余空间（每 [SPACE_CHECK_EVERY] 行查一次，statfs 不逐行做）。 */
    private fun gateOpen(d: File): Boolean {
        if (rowSeq.get() >= MAX_ROWS_PER_RUN) {
            if (dropped == 0) Log.w(TAG, "rec dump: 到本轮上限 $MAX_ROWS_PER_RUN 行 ⇒ 后续只计数不落盘")
            dropped++
            return false
        }
        if (spaceCheckedAt == 0 || rowSeq.get() - spaceCheckedAt >= SPACE_CHECK_EVERY) {
            spaceCheckedAt = rowSeq.get()
            spaceOk = (runCatching { d.usableSpace }.getOrDefault(Long.MAX_VALUE)) >= MIN_FREE_BYTES
            if (!spaceOk) Log.w(TAG, "rec dump: 剩余空间不足 ${MIN_FREE_BYTES / (1 shl 20)}MB，暂停落盘")
        }
        if (!spaceOk) dropped++
        return spaceOk
    }

    private inline fun <T> guarded(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (c: CancellationException) {
            throw c
        } catch (e: Throwable) {
            Log.w(TAG, "rec dump 落盘异常", e)
            Result.failure(e)
        }

    /** Windows/adb 友好：仅留 ASCII 字母数字与 . _ -。 */
    private fun sanitize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '_' || c == '-') c else '_')
        }
        return sb.toString()
    }
}
