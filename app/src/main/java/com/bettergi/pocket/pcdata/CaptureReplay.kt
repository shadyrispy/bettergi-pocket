package com.bettergi.pocket.pcdata

import android.content.Context
import android.util.Log
import com.bettergi.pocket.scan.GoodRepository
import com.esc.irminsul.capture.CaptureResult
import com.esc.irminsul.capture.CaptureSource
import com.esc.irminsul.capture.DataStatus
import com.esc.irminsul.capture.DataStatusSink
import com.esc.irminsul.capture.IrminsulCapture
import java.io.File
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * 抓包数据源的**离线回放**通路（集成方案 C1）。
 *
 * 一个 pcap 走完真机链路里除 VPN 之外的全部环节：`CaptureSource.File` → 原生解码 →
 * `exportGood` → [GoodRepository.save]。回放不建隧道、不要授权、不要求装了游戏，
 * 所以它是"抓包接进 bp"的最小可判定切片：解出来的数据对不对，在这里就能看出来。
 *
 * 产物直接落在既有输入仓库上，`GoodPlan` 契约与 DSL 引擎都不必知道数据是 OCR 扫的
 * 还是抓包解的。
 *
 * ⚠️ 只在**主进程**调用：抓包的包队列经进程静态字段交给捕获服务（见库的
 * `CaptureError.WrongProcess`），`:a11y` 进程里调只会得到一个空会话。
 */
object CaptureReplay {

    private const val TAG = "BetterGI.Capture"

    /** 只防"回放线程不回来"；正常结束由库的 `replayFinished` 报。 */
    private const val MAX_WAIT_MS = 300_000L
    private const val POLL_MS = 100L

    /** @return 一行结果，进 logcat 也回给调用方（adb 调试链直接打印它）。 */
    suspend fun replay(context: Context, pcapPath: String): String {
        val ticket = CaptureGate.tryEnter()
        if (ticket == 0L) return fail("已有抓包在跑（在线会话或另一次回放），本次不启动")
        try {
            return run(context, pcapPath)
        } finally {
            CaptureGate.exit(ticket)
        }
    }

    private suspend fun run(context: Context, pcapPath: String): String {
        val file = File(pcapPath)
        if (!file.canRead()) {
            return fail("文件不可读：$pcapPath（投放到 ${context.getExternalFilesDir(null)?.absolutePath} 下）")
        }
        when (val support = IrminsulCapture.probeNativeSupport()) {
            is CaptureResult.Ok -> Unit
            is CaptureResult.Err -> return fail("本机跑不了抓包栈（ABI 没装到原生库？）：${support.error}")
        }

        // 每轮从干净的 sniffer 开始：原生收集是跨会话累加的，不清就把上一份 pcap
        // 的库存混进这一轮导出里。已知明文样本存在 filesDir，重建时会读回来。
        runCatching { IrminsulCapture.stop(context) }
        runCatching { IrminsulCapture.close() }
        when (val init = IrminsulCapture.initNative(context)) {
            is CaptureResult.Ok -> Unit
            is CaptureResult.Err -> return fail("原生栈初始化失败：${init.error}")
        }

        val startedAt = System.currentTimeMillis()
        when (val start = IrminsulCapture.start(
            context,
            CaptureSource.File(file.absolutePath),
            StatusSink,
            IrminsulCapture.Config(completionNotification = false),
        )) {
            is CaptureResult.Ok -> Unit
            is CaptureResult.Err -> return fail("回放没起来：${start.error}")
        }

        val (finished, outcome) = awaitReplay()
        if (!finished) {
            // 没跑到文件末尾 ⇒ 这一轮库存必然是半截的，而 `GoodRepository` 是**单文件覆盖写**：
            // 一存就把上一份完整输入顶掉，随后被 `artifact_lock` / `auto_equip` 当成全库依据。
            // 与在线会话「只有四段齐才落盘」是同一条定策。
            IrminsulCapture.stop(context)
            return fail("$outcome ⇒ 不入库（上一份输入保持不变）")
        }
        val json = when (val export = IrminsulCapture.exportGood(GOOD_EXPORT_SETTINGS)) {
            is CaptureResult.Err -> {
                IrminsulCapture.stop(context)
                return fail("导出失败：${export.error}（$outcome）")
            }
            is CaptureResult.Ok -> export.value
        }
        // 文件源没有隧道要拆，这里只停解码管线（stop 内部按源与 isCapturing 判断，
        // 不会为一次回放把 VPN 服务拉起来）。
        IrminsulCapture.stop(context)

        val (ok, message) = GoodRepository.save(context, "抓包回放 ${file.name}", json)
        if (!ok) return fail("解析完成但存不进输入仓库：$message（$outcome）")
        return line("抓包回放 ${System.currentTimeMillis() - startedAt}ms：$outcome ${countsOf(json)} → $message")
    }

    /**
     * 等回放跑到文件末尾，判据是库的 `replayFinished`。
     *
     * ⚠️ 不要用 `packets` 条数判结束：那是个有上限（2000）的环形缓冲，大 pcap 很早就平
     * 住了，"条数不再涨"与"回放结束"长得一模一样 —— 读早一步就是少一类数据。原先这里
     * 用"静默 3s"猜，正是踩在同一个坑上。
     */
    private suspend fun awaitReplay(): Pair<Boolean, String> {
        val deadline = System.currentTimeMillis() + MAX_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (IrminsulCapture.replayFinished.value) {
                // `replayFinished` 只说"没有在飞的回放"，不说"整个文件都喂进去了"。
                // 后者要看 replayError：读坏 / 句柄失效 / 打不开 / 被打断都会在那里留话，
                // 而每一种都意味着库存是半截的 —— 半截的不能覆盖上一份完整输入。
                val why = IrminsulCapture.replayError.value
                return (why == null) to (
                    why ?: "commands=${IrminsulCapture.packets.value.size}"
                    )
            }
            delay(POLL_MS)
        }
        return false to "到硬上限 ${MAX_WAIT_MS}ms 回放线程没回来"
    }

    private fun countsOf(json: String): String {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return "JSON 读不出"
        fun n(key: String) = root.optJSONArray(key)?.length() ?: 0
        return "角色=${n("characters")} 圣遗物=${n("artifacts")} 武器=${n("weapons")} " +
            "材料=${root.optJSONObject("materials")?.length() ?: 0}"
    }

    /** 回放只要解码继续跑就行，进度不进面板 ⇒ sink 无需留存状态。 */
    private object StatusSink : DataStatusSink {
        override fun publish(status: DataStatus) = Unit
    }

    private fun line(text: String): String {
        Log.i(TAG, text)
        return text
    }

    private fun fail(text: String): String {
        Log.w(TAG, text)
        return "抓包回放失败：$text"
    }
}
