package com.bettergi.pocket.scan

import android.os.SystemClock
import android.util.Log
import com.bettergi.pocket.capture.FrameSource
import com.bettergi.pocket.capture.FrameTimeoutException
import com.bettergi.pocket.log.RecognitionLog
import com.bettergi.pocket.recognition.IntRect
import com.bettergi.pocket.recognition.name.GoodNames
import com.bettergi.pocket.recognition.name.NameMatcher
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Mat

/** 扫描主动终止（anchor 断言失败等不可继续场景）——ScriptRunner 捕获后上报 error。 */
class ScanAbortedException(message: String) : Exception(message)

/** OCR 网关：ML Kit（P1-b）/ ONNX PaddleOCR（后续替换）双实现同接口。 */
interface OcrGateway {
    /** 定点数字 OCR（readCount 总数 N/M 等）。 */
    suspend fun readNumber(frame: Mat, rect: FrameRect): Int?

    /** rec-only 字段槽读取（parsePanel 字段行）。 */
    suspend fun readLines(frame: Mat, rects: List<FrameRect>): List<String>

    /**
     * 一次批量读全部槽位（parsePanel 的 name/slot/main/level/subStats 合一次调用）。
     * 返回与 rects 一一对应的文本（无内容为空串）。
     */
    suspend fun readRois(frame: Mat, rects: List<FrameRect>): List<String> =
        rects.map { rect -> readLines(frame, listOf(rect)).firstOrNull().orEmpty() }
}

/** 进度上报（悬浮窗/通知，P1-c 接视图）。 */
interface ScanListener {
    fun onProgress(stage: String, vars: Map<String, Any?>)
    fun onFinished(reason: String)
}

/** 扫描会话变量（flow vars + visit 产物）。 */
class ScanVars {
    var total: Int? = null
    var gridLocked: Boolean? = null
    var crafted: Boolean = false
    var rarity: Int = -1
    var locked: Boolean? = null
    var favorited: Boolean? = null
    /** ocrWithRetry 命中写入（fuzzy 词典匹配 key）。 */
    var ocrMatch: String? = null
    /** vote as=curLock 快照（artifact_lock ifMatch 的 verify toggle 依据）。 */
    var curLock: Boolean? = null
    /** foreach：外部注入任务计划（P4 规则层注入；flow 内按 task 使用）。 */
    var plan: List<JSONObject>? = null
    var currentTask: JSONObject? = null
    var level: Int = 0
    var stopRequested: Boolean = false
    /** 连续重复角色计数（止扫判据观测用；0 = 未启用或上一个不是重复）。 */
    var charDupStreak: Int = 0
    /**
     * 终止原因（可观测性）。`stopRequested` 被三个不同的地方置位：`stopWhen` 命中、flow 的
     * `exit` 步、`pagedGrid` 的 maxPages 早停 —— 原先 `onFinished` 一律报 "stopWhen"，
     * 导致「止扫判据是否真的触发」在日志上不可辨（负向用例也会显示 stopWhen）。
     */
    var stopReason: String? = null

    fun snapshot(): Map<String, Any?> = mapOf(
        "total" to total,
        "gridLocked" to gridLocked,
        "crafted" to crafted,
        "rarity" to rarity,
        "locked" to locked,
        "favorited" to favorited,
        "level" to level,
        "stopRequested" to stopRequested,
        "charDupStreak" to charDupStreak,
    )

    fun exprVars(): Map<String, Any?> = mapOf(
        "rarity" to rarity,
        "level" to level,
        "total" to (total ?: 0),
    )
}

/**
 * 最小 JSON 解释器（P1）：加载 dsl/json/flows/artifact_scan.json，
 * 实现圣遗物扫描所需 8 业务原语（enterScreen/readCount/pagedGrid/vote/parsePanel/
 * dualStateButton/emit/stopWhen + click），坐标全部来自 profiles（ScreenProfile 缩放）。
 *
 * OCR 依赖步骤（readCount/parsePanel）在 OcrGateway 未接入时记录跳过，流程不中断。
 */
class ScanEngine(
    private val flowJson: JSONObject,
    val profile: ScreenProfile,
    private val frameSource: FrameSource,
    private val actions: ActionGateway,
    private val ocr: OcrGateway?,
    /**
     * 单一名称词典（角色/武器/套装/圣遗物单件/词条/部位）。
     * 所有名称反查统一走 [NameMatcher]，本引擎不再持有各写一份模糊逻辑的词典类。
     */
    private val names: GoodNames? = null,
    private val listener: ScanListener,
    private val dedupe: Boolean = true,
    /** 翻 N 页早停（调试翻页准确性用；默认不限制）。 */
    private val maxPages: Int = Int.MAX_VALUE,
    /** 单测注入真实时钟用：JVM 里 SystemClock 被 returnDefaultValues 恒返回 0（会挂死轮询）。 */
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    /**
     * 点击后固定等待（yas clickDelay 同构，irminsul=100ms）。**不做面板稳定轮询**——
     * stale 读由内容指纹去重兜底（irminsul producerLoop 同构：click → 短睡 → capture）。
     * 默认 250ms = 模拟器实测面板内容稳定点（+150ms 指纹已变但仍未稳，+270ms 稳定）；
     * 100ms 会截到动画中帧，OCR 乱码名会污染导出。单测注入短值。
     *
     * §真机标定（EML-AL00）：accessibility gesture 派发存在额外调度延迟，250ms 下
     * 第 2 格即 stale → 提升面板切换置信度。500ms 保守值，真机 settle 后再收紧。
     */
    private val clickDelayMs: Long = 500L,
    /** §12.1 起点规范化开关：true=用几何推导落点，false=用 profiles 写死坐标（实机 A/B 用）。 */
    private val useGeometryAdvance: Boolean = true,
    /**
     * §12.2 距离自适应开关：true=每页按相位误差校正翻页距离。
     *
     * **默认 false**：模拟器 4 页 A/B 实测——开启后 err 序列（+44/−7/+80）呈噪声、
     * 扫描结果与关闭时完全一致（emit 77 / 去重 7 两组的相同）→ 该设备上既无收益也无害。
     * 按方案 §12.3 待办 #2 的口径：先采真机 err 序列确认漂移 >10px 再启用（adb 可开）。
     */
    /** §12.2 旧距离自适应开关（2026-09-05 起被 §12.5 相位偏移遍历取代，参数保留作 API 兼容、不再生效）。 */
    private val useAdaptiveDistance: Boolean = false,
    /**
     * 外部注入任务计划（P4 规则层）。`foreach over=$plan` 消费并逐项写入 [ScanVars.currentTask]，
     * `ifMatch` 以 currentTask 非空为闸 → artifact_lock / auto_equip 依赖此注入，未注入则 ifMatch 段整段跳过。
     */
    private val plan: List<JSONObject>? = null,
    /** 流程名（ScriptRunner 传入），仅用于 §13 识别日志的 [RecognitionLog.Tag] 归属。 */
    private val flowName: String = "artifact_scan",
) {
    val vars = ScanVars().apply { this.plan = this@ScanEngine.plan }

    /** §13：流程 → 日志来源标签。 */
    private val logTag: RecognitionLog.Tag
        get() = when {
            flowName.contains("lock") -> RecognitionLog.Tag.LOCK
            flowName.contains("equip") -> RecognitionLog.Tag.EQUIP
            flowName.contains("char") -> RecognitionLog.Tag.CHAR
            else -> RecognitionLog.Tag.SCAN
        }

    /** 扫描产物（parsePanel emit 收集，endConditions 后由 ScriptRunner 导出）。 */
    val results = ArrayList<GoodArtifact>()

    /** 入库内容指纹（去重键，见 parseArtifactPanel）。 */
    private val seenArtifactKeys = HashSet<String>()

    val resultsWeapons = ArrayList<GoodWeapon>()

    /**
     * 动作网关：实现侧在 dispatch 受理后调用 frameSource.markActionAt（P0 联动点），
     * 保证 freshFrame 只取动作后新帧。
     */
    interface ActionGateway {
        /**
 * 强制把悬浮窗恢复为**不可触摸**（穿透）。
 * ⚠️ 进入界面链的点击失败时最可能的元凶是「悬浮窗此时仍可触摸 ⇒ 点击被自家窗吞掉」，
 *    而逐点 passthrough 的恢复是**延时调度**的（`durationMs + SLACK`），链式连点时可能尚未恢复。
 *    故在 enterScreen 开链前显式复位一次。默认空实现 ⇒ 不破坏既有实现/单测 mock。
 */
fun resetPassthrough() {}

fun click(x: Int, y: Int, durationMs: Long = 50L): Boolean
        /** 纯 tap（零位移 stroke）——char_popup 弹窗卡片对 2px 微滑识别为拖拽不切换（equip18/19 实证）。 */
        fun tap(x: Int, y: Int): Boolean
        fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean
        /** 系统返回键（GLOBAL_ACTION_BACK）——enterScreen 失败重进前清游戏每日弹窗（签到/物品过期）。 */
        fun back(): Boolean
    }

    suspend fun run() {
        tmStartMs = clock()
        tmCells = 0; tmPages = 0; tmNavMs = 0; tmPanelMs = 0; tmSettleMs = 0
        PerfProbe.reset() // 只读探针：每轮扫描独立统计
        Log.i(TAG, "timing: ${TimingOverrides.summary()}")
        // §15 流程前置归位：GOODScanner `GenshinGameController::return_to_main_ui` 同思想
        // （循环点返回直至主界面），各流程启动前统一回到 game_home，避免残留弹窗/界面错位。
        // 默认 false：仅 ScriptRunner 真机启动路径显式开启（干跑单测不注入 → 不产生归位点击/取帧）
        if (flowJson.optBoolean("returnHome", false)) {
            val tHome = System.nanoTime()
            try {
                returnToHome()
            } catch (e: Exception) {
                // 归位是尽力而为的前置，失败不应中断流程（首步 enterScreen 仍会校验）
                Log.w(TAG, "returnToHome 异常，继续流程：${e.message}")
            } finally {
                PerfProbe.addStep("returnToHome", System.nanoTime() - tHome)
            }
        }
        val steps = flowJson.getJSONArray("steps")
        for (i in 0 until steps.length()) {
            if (vars.stopRequested) break
            executeStep(steps.getJSONObject(i))
        }
        // endConditions：total 核对（止扫中断时允许不符——止扫即异常策略）
        val total = vars.total
        if (total != null && !vars.stopRequested && total != results.size) {
            Log.w(TAG, "total mismatch: readCount=$total scanned=${results.size}")
            RecognitionLog.log(
                logTag,
                RecognitionLog.Level.W,
                "总数不符 读到=$total 实扫=${results.size}",
            )
            listener.onProgress("total_mismatch", mapOf("total" to total, "scanned" to results.size))
        }
        val reason = vars.stopReason ?: if (vars.stopRequested) "stopWhen" else "completed"
        val elapsed = (clock() - tmStartMs).coerceAtLeast(0L)
        val perCell = if (tmCells > 0) elapsed / tmCells else 0L
        val perPage = if (tmPages > 0) elapsed / tmPages else 0L
        Log.i(
            TAG,
            "scan finished: $reason elapsed=${elapsed}ms cells=$tmCells pages=$tmPages " +
                "perCell=${perCell}ms perPage=${perPage}ms | waits nav=${tmNavMs} panel=${tmPanelMs} settle=${tmSettleMs}",
        )
        // 只读性能探针：click/swipe 真实注入耗时 + OCR 网关耗时（分量实测，供
        // dsl/verify/_audit/IMAGE-PATH-COST.md §8 决策）。**独立一行**，避免打乱既有日志正则。
        Log.i(TAG, "probe ${PerfProbe.summary()} | sigSamples=$tmSigSamples")
        listener.onFinished(reason)
    }

    private suspend fun executeStep(step: JSONObject) {
        // 只读探针：顶层步骤按类型累计（与 executeVisitStep 同法）。真分发在 executeStepInner。
        val op = step.getString("do")
        val t0 = System.nanoTime()
        try {
            executeStepInner(step, op)
        } finally {
            PerfProbe.addStep("top:$op", System.nanoTime() - t0)
        }
    }

    private suspend fun executeStepInner(step: JSONObject, op: String) {
        // §13：埋点打在原语分发层——加锁/装备/角色流程接 runner 后自动继承，无需各流程另写
        RecognitionLog.log(logTag, RecognitionLog.Level.I, "步骤 $op")
        when (op) {
            "enterScreen" -> enterScreen(step)
            "clicks" -> step.optJSONArray("chain")?.let { arr ->
                for (i in 0 until arr.length()) clickChainEntry(arr.getString(i))
            } ?: Log.w(TAG, "clicks: 缺 chain")
            "dualStateButton" -> dualStateButton(step)
            "filterReset" -> filterReset(step)
            "assertScreen" -> assertScreen(step)
            "readConstellation" -> readConstellation(step)
            "readCount" -> readCount(step)
            "pagedGrid" -> pagedGrid(step)
            "dialog" -> dialog(step)
            "ocrWithRetry" -> ocrWithRetry(step)
            "navigate" -> navigate(step)
            "foreach" -> foreach(step)
            "rosterFind" -> rosterFind(step)
            "setFilter" -> setFilter(step)
            "exit" -> exitStep(step)
            "verify" -> verify(step)
            "emit" -> listener.onProgress("emit", vars.snapshot())
            else -> {
                Log.w(TAG, "unknown step '$op', skipped")
                RecognitionLog.log(logTag, RecognitionLog.Level.W, "未知原语 $op 已跳过")
            }
        }
    }

    // ---- #1 enterScreen：入口链跳转 + anchor OCR 断言（重试 3 次）----
    private var enterScreenStep: JSONObject? = null

    /**
     * §15 归位主界面：循环点右上角「返回/关闭」钮直至主界面（背包钮 + 角色钮同时可见）。
     * 对应 GOODScanner `return_to_main_ui(max_attempts)`：先判在 home → 直接返回；
     * 否则点返回 → 等 900ms → 再判，最多 [RETURN_HOME_ATTEMPTS] 次。
     * 返回钮模板未命中（部分界面样式不同）时退回右上锚点坐标（真机右锚 (x−3200)×0.75+2244）。
     */
    private suspend fun returnToHome(maxAttempts: Int = RETURN_HOME_ATTEMPTS) {
        // 模板未注册（干跑单测/未下发模板）→ 无法判据，直接放弃（且不消耗帧，避免打乱帧序列）
        if (!TemplateMatcher.hasTemplate("home.bagpack")) {
            Log.w(TAG, "returnToHome: home.bagpack 模板未注册，跳过归位")
            return
        }
        var attempt = 0
        while (attempt++ < maxAttempts) {
            val frame = freshFrame()
            val (atHome, bx, by) = try {
                // 任一命中即视为主界面（背包钮/角色钮均为 home 独有）
                val rb = TemplateMatcher.match(frame, "home.bagpack", profile)
                // 模板未注册/配置未加载时 match 返回 score=-1 → 无法判据，直接放弃归位
                // （干跑单测与未下发模板的环境不应产生无意义点击）
                if (rb.score < 0) {
                    Log.w(TAG, "returnToHome: home.bagpack 模板不可用，跳过归位")
                    return
                }
                val home = rb.matched ||
                    TemplateMatcher.match(frame, "home.character", profile).matched
                val r = TemplateMatcher.match(frame, "ui.return", profile)
                // 模板与 ROI 换算同 TemplateMatcher：按高比缩放，x 右锚
                val s = frame.rows().toDouble() / 1440.0
                // fallback 优先 profile 机读 returnBtn（2560 返回钮右缘边距与 3200 不同，
                // cols-248 旧推算在 2560 偏左 120px → 永点不中 → 归位失败）
                val retObj = runCatching { profile.zone("ui.return.btn") }.getOrNull()
                val retRect = retObj?.getJSONArray("rect")
                val px = if (r.matched) r.x + (39 * s).toInt()
                else if (retRect != null && retRect.length() >= 4)
                    profile.scale((retRect.getInt(0) + retRect.getInt(2)) / 2, profile.scaleX)
                else ((2952 - 3200) * s + frame.cols()).toInt()
                val py = if (r.matched) r.y + (39 * s).toInt()
                else if (retRect != null && retRect.length() >= 4)
                    profile.scale((retRect.getInt(1) + retRect.getInt(3)) / 2, profile.scaleY)
                else (80 * s).toInt()
                Triple(home, px, py)
            } finally {
                frame.release()
            }
            if (atHome) {
                Log.i(TAG, "returnToHome: 已在主界面（第 $attempt 次判定）")
                return
            }
            Log.i(TAG, "returnToHome: 第 $attempt 次点返回 ($bx,$by)")
            clickAt(bx, by)
            delay(RETURN_HOME_SETTLE_MS)
        }
        Log.w(TAG, "returnToHome: $maxAttempts 次未达主界面，继续流程（首步 enterScreen 会再校验）")
    }

    /**
     * **带校验的筛选复位**（2026-09-12，用户定稿 A 之后的第一件守卫）。
     *
     * 语义：`点漏斗 → OCR 确认「圣遗物筛选」面板**已打开** → 才点 重置 → 确认`。
     *
     * ⚠️ **为什么必须校验**：圣遗物页底部同排按钮**几乎重叠** ——
     * `锁定辅助`=[544,981,666,1017] **包含 `filterPanel.ok` 的中心 (638,1002)**，
     * `采用推荐设置` 热点 ≈(315,1003) 紧贴漏斗/排序 (295,983)/(377,983)。
     * 若面板没打开就照点 reset/ok ⇒ 会打到页面按钮上**弹出全屏模态面板**（实测「锁定辅助」）
     * ⇒ 之后所有点击/滑动**全部失效**（`panel` 等待≈兜底值、`pages=1`、`reached end`），整轮报废。
     * ⇒ **未确认面板打开时宁可放弃复位**（带着筛选扫），**绝不盲点**。
     *
     * 参数：`open` / `title` / `reset` / `ok`（均为 profile 路径）+ `titleExpect`（默认 `"圣遗物|筛选"`，
     * 用 `|` 分隔多个备选，命中任一即算面板已打开）+ `retries`（默认 2）。
     * 两次点漏斗都没等到标题 ⇒ 再按一次 BACK（清掉可能已卡住的面板）后重试一次；仍失败则记警告跳过。
     */
    private suspend fun filterReset(step: JSONObject) {
        val gateway = ocr
        if (gateway == null) {
            Log.w(TAG, "filterReset: OCR 未就绪，跳过筛选复位")
            return
        }
        fun rectOf(key: String): FrameRect? =
            step.optString(key).takeIf { it.isNotEmpty() }?.let { p ->
                runCatching { profile.rect(p.removePrefix("$")) }.getOrNull()
            }

        val openRect = rectOf("open") ?: run {
            Log.w(TAG, "filterReset: 缺 open，跳过")
            return
        }
        val titleRect = rectOf("title") ?: run {
            Log.w(TAG, "filterReset: 缺 title，跳过")
            return
        }
        val resetRect = rectOf("reset")
        val okRect = rectOf("ok")
        // ⚠️ 判据允许多个备选（`"A|B"`）：面板标题实测约 `[179,50,419,104]`@2560，
        //    而 `profile.anchorTitle` 只覆盖它的**左半** ⇒ OCR 可能只读到「圣遗物」而丢掉「筛选」。
        //    用单个词做判据会把"面板已开"误判成"未开"⇒ 白等 1.2s 后走 BACK 重试（不会误点，但拖慢并可能关掉面板）。
        val expects = step.optString("titleExpect", "圣遗物|筛选")
            .split("|").map { it.trim() }.filter { it.isNotEmpty() }
        val tries = step.optInt("retries", 2).coerceIn(1, 4)

        /** 面板标题是否已出现（面板确实打开了）。 */
        suspend fun panelOpen(): Boolean {
            val f = freshFrame()
            val text = try {
                gateway.readLines(f, listOf(titleRect)).joinToString(" ")
            } finally {
                f.release()
            }
            val hit = expects.any { text.contains(it) }
            Log.i(TAG, "filterReset: 面板标题='$text' 期望含$expects ⇒ ${if (hit) "已打开" else "未打开"}")
            return hit
        }

        suspend fun attempt(): Boolean {
            Log.i(TAG, "filterReset: 点漏斗 (${openRect.centerX},${openRect.centerY})")
            clickAt(openRect.centerX, openRect.centerY)
            // 面板有淡入：最多轮询 ~1.2s
            for (i in 1..6) {
                delay(FILTER_PANEL_POLL_MS)
                if (panelOpen()) return true
            }
            return false
        }

        var opened = attempt()
        if (!opened) {
            // 常见原因：屏幕上有别的模态面板盖着（点击无效）。按一次 BACK 清掉后再试一次。
            Log.w(TAG, "filterReset: 漏斗后未见面板 ⇒ 按 BACK 清可能卡住的面板后重试")
            runCatching { actions.back() }
            delay(FILTER_PANEL_BACK_MS)
            opened = attempt()
        }
        repeat(tries - 1) {
            if (!opened) opened = attempt()
        }
        if (!opened) {
            Log.w(TAG, "filterReset: 未能确认面板打开 ⇒ **放弃复位**（继续扫描，宁可带筛选）")
            return
        }
        // 面板已确认打开 ⇒ reset/ok 的坐标此刻是安全的
        if (resetRect != null) {
            Log.i(TAG, "filterReset: 重置 (${resetRect.centerX},${resetRect.centerY})")
            clickAt(resetRect.centerX, resetRect.centerY)
            delay(FILTER_PANEL_STEP_MS)
        }
        if (okRect != null) {
            Log.i(TAG, "filterReset: 确认 (${okRect.centerX},${okRect.centerY})")
            clickAt(okRect.centerX, okRect.centerY)
            delay(FILTER_PANEL_STEP_MS)
        }
        // 收尾校验：面板应已关闭（标题不再是"筛选"）
        if (panelOpen()) Log.w(TAG, "filterReset: 确认后标题仍在 ⇒ 面板可能未关闭（后续会由锚点兜）")
    }

/**
     * **界面确认**（2026-09-13 新增；用户需求："每次切页/开弹窗之后加一个界面确认，避免误触让流程失败"）。
     *
     * 依据实测（`dsl/docs/screen-confirm.md`）：**左上角标题条**会显示当前界面名，位置一致 ——
     * 51 张 2244 截图的标题文本块包络 = `x[120,643] y[19,97]` ⇒ 统一 ROI `screens._common.titleBar`。
     * 例：`背包/圣遗物`、`背包／武器`、`圣遗物筛选`、`圣遗物套装筛选`、`排序方式`、`快速装备`、
     * `<元素>/<角色名>`（如 `冰元素/桑多涅`）、`属性/<角色名>`。
     *
     * 语义：OCR 标题条（+ 可选 `alts` 备选区）⇒ 任一 `expect`（**正则子串**）命中即通过；
     * `retries` 次内都不中则按 [onFail] 处置（`back` 清掉误开的界面 / `abort` 终止 / `continue` 仅告警）。
     *
     * \u26a0\ufe0f 为什么必须"多备选 + 重试"（实测）：同一张 `char_interface` 截图 OCR **一次读到
     *    `冰元素/桑多涅`、另一次读空** ⇒ 单次判定不可靠。另：**圣遗物管理界面左上只显示角色名**（实测 `豆豆`）
     *    而非界面名 ⇒ 它必须走 `alts`（`screens._common.topRightHint` 里的 `圣遗物推荐`）。
     * \u26a0\ufe0f 大世界**没有任何标题文本**（只有噪声 `N`）⇒ 该界面仍只能用模板判据（`home.bagpack`）。
     *
     * 参数：`expect`（正则子串，必需）/ `title`（缺省 `$screens._common.titleBar`）/ `alts`（`[{rect,expect}]`）
     *      / `retries`（默认 3）/ `onFail`（`back`|`abort`|`continue`，默认 `continue`）。
     */
    /**
     * **命座页确认 + 读数**（2026-09-13 新增；GOODScanner 移植，见 `topics/09-reference-goodscanner.md`）。
     *
     * 为什么需要：角色界面三个页签**左上标题完全相同**（`<元素>/<角色名>`）⇒ 界面级判据区分不了页签；
     * 而属性/天赋页有底部按钮文本可用、**命座页底部为空**（无正判据）。本步用**节点环亮度**补上这个缺口。
     *
     * 判据：6 个节点各取环带亮度 `ring` 与中心区 `center`，**锁定态 ring−center 大、激活态小**；
     * `lockedCount ≥ screens.char_constellation.pageMinLocked` ⇒ **在命座页**
     * （实测：命座页 5 角色命中 4~6 个；属性页/天赋页同坐标 **0 个**）。
     *
     * 参数：`verifyPage`（默认 true，未通过按 `onFail` 处置）/ `onFail`（`back`|`abort`|`continue`，默认 continue）。
     * ⚠️ **目前只做页确认 + 读数写日志**：等级（激活数）**仍差 1**（阿罗夏 C4 读成 C1-C3，节点中心未逐节点精修）
     * ⇒ **先不要把它接进 emit/GOOD**（会导出错误命座数），等逐节点标定完成（见 profile note）。
     */
    private suspend fun readConstellation(step: JSONObject) {
        val frame = freshFrame()
        val read = try {
            VoteJudges.constellationNodes(frame, profile)
        } finally {
            frame.release()
        }
        if (read == null) {
            Log.w(TAG, "readConstellation: profile 缺 screens.char_constellation，跳过")
            return
        }
        Log.i(
            TAG,
            "命座读数：${read.brief()} | 锁定=${read.lockedCount} 激活=${read.activeCount} " +
                "后缀形=${read.suffixPattern} 在命座页=${read.isConstellationPage}",
        )
        if (!step.optBoolean("verifyPage", true) || read.isConstellationPage) return
        Log.w(TAG, "readConstellation: 未认定在命座页（锁定 ${read.lockedCount} < 阈值）⇒ onFail=${step.optString("onFail", "continue")}")
        when (step.optString("onFail", "continue")) {
            "back" -> {
                runCatching { actions.back() }
                delay(FILTER_PANEL_BACK_MS)
            }
            "abort" -> throw ScanAbortedException("readConstellation: not on constellation page")
            else -> Unit
        }
    }

    private suspend fun assertScreen(step: JSONObject) {
        val gateway = ocr ?: return
        val checks = ArrayList<Pair<FrameRect, Regex>>()
        val expect = step.optString("expect")
        // 主判据区：`rect`（首选，语义就是"要 OCR 的矩形"，可指向任意位置，如**底部按钮**）/
        // `title`（别名，兼容早期写法）/ 缺省 = 通用标题条。
        val titlePath = step.optString("rect")
            .ifEmpty { step.optString("title") }
            .ifEmpty { "\$screens._common.titleBar" }
            .removePrefix("\$")
        if (expect.isNotEmpty()) {
            runCatching { profile.rect(titlePath) }.getOrNull()?.let { checks += it to Regex(expect) }
        }
        step.optJSONArray("alts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val e = o.optString("expect")
                val r = runCatching { profile.rect(o.optString("rect").removePrefix("\$")) }.getOrNull()
                if (r != null && e.isNotEmpty()) checks += r to Regex(e)
            }
        }
        if (checks.isEmpty()) {
            Log.w(TAG, "assertScreen: 无可判定区域（expect 为空或路径解析失败），跳过")
            return
        }
        val retries = step.optInt("retries", 3).coerceIn(1, 6)
        for (attempt in 1..retries) {
            val frame = freshFrame()
            val texts = try {
                checks.map { (rect, re) ->
                    re to StatParser.clean(gateway.readLines(frame, listOf(rect)).joinToString(" "))
                }
            } finally {
                frame.release()
            }
            val hit = texts.firstOrNull { it.first.containsMatchIn(it.second) }
            if (hit != null) {
                Log.i(TAG, "assertScreen OK（第 $attempt 次）：'${hit.second}' 命中 /${hit.first.pattern}/")
                return
            }
            Log.w(TAG, "assertScreen 第 $attempt/$retries 次未命中：${texts.joinToString { "'${it.second}'" }}")
            if (attempt < retries) delay(CLICK_SETTLE_MS)
        }
        when (step.optString("onFail", "continue")) {
            "back" -> {
                Log.w(TAG, "assertScreen 失败 ⇒ 按一次 BACK 清掉可能误开的界面后继续")
                runCatching { actions.back() }
                delay(FILTER_PANEL_BACK_MS)
            }
            "abort" -> throw ScanAbortedException("assertScreen not reached: $expect")
            else -> Log.w(TAG, "assertScreen 失败 ⇒ 继续（onFail=continue）")
        }
    }

    private suspend fun enterScreen(step: JSONObject, isRetry: Boolean = false) {

        if (enterScreenStep == null) enterScreenStep = step
        // §14 P2 preReset：到达前参数复位（auto_equip D7 定稿「排序→重置→确认」）。
        // 链可写于 step.preReset.chain，缺省取 profiles.screens.artifact_manage.resetChain。
        if (!isRetry) {
            val preReset = step.optJSONObject("preReset")
            if (preReset != null) {
                @Suppress("UNCHECKED_CAST")
                val prChain = preReset.optJSONArray("chain")
                    ?: (profile.rawAny("screens.artifact_manage.resetChain") as? JSONArray)
                if (prChain != null) {
                    Log.i(TAG, "preReset: 执行复位链（${prChain.length()} 步）")
                    for (i in 0 until prChain.length()) clickChainEntry(prChain.getString(i))
                } else {
                    Log.w(TAG, "preReset: 无 chain 且 profiles 无 resetChain，跳过复位")
                }
            }
        }
        val chain = step.getJSONArray("chain")
        // ⚠️ 开链前强制复位穿透：链式连点时逐点 passthrough 的**延时恢复**可能还没跑，
        //    悬浮窗若仍可触摸会把链点击吞掉（真机实测：链点击全部无效、锚点读到世界内容而中止）。
        actions.resetPassthrough()
        // 预解析每个链入口的落点：**最后一个可解析入口**的 settle 交给"锚点就绪轮询"（见下），
        // 其余入口的 settle 保持 ENTER_SETTLE_MS（点早了会点空 ⇒ 链失败，这一侧不能省）。
        fun chainRectOf(i: Int): FrameRect? {
            val anchorName = parseChainAnchor(chain.getString(i))
            val rectPath = CHAIN_ANCHOR_PATHS[anchorName] ?: return null
            return runCatching { profile.rect(rectPath) }.getOrNull()
        }
        val lastIdx = (chain.length() - 1 downTo 0).firstOrNull { chainRectOf(it) != null } ?: -1
        for (i in 0 until chain.length()) {
            val anchorName = parseChainAnchor(chain.getString(i))
            val rectPath = CHAIN_ANCHOR_PATHS[anchorName]
            if (rectPath == null) {
                Log.w(TAG, "enterScreen anchor '$anchorName' has no profile mapping, skipped")
                continue
            }
            val rect = runCatching { profile.rect(rectPath) }.getOrElse {
                Log.w(TAG, "enterScreen anchor '$anchorName' 路径 '$rectPath' 解析失败，跳过：${it.message}")
                continue
            }
            // 每步记录受理结果（原来静默 ⇒ 点击失败只能靠锚点事后兜，排障极难）
            val ok = actions.click(rect.centerX, rect.centerY)
            Log.i(TAG, "chainEntry '$anchorName' click=(${rect.centerX},${rect.centerY}) ok=$ok")
            if (i != lastIdx) delay(ENTER_SETTLE_MS)
        }
        // ★ 2026-09-12 固定 settle 优化：`SCREEN_SETTLE_MS(1500)` 盲等 → **轮询锚点就绪**（上限仍是 1500ms）。
        //   锚点本来就是这条链的到达校验（紧随其后的 `assertAnchor`），把它**提前**当就绪信号用：
        //   页面提前渲染完就提前走；未命中则照旧 `delay(budget)` 后交给 `assertAnchor`
        //   走既有的「3 次重试 → BACK → 重跑入口链」逻辑 ⇒ **绝不更慢、也不改变失败路径**。
        //   预算 = 被跳过的最后一个入口 settle + SCREEN_SETTLE_MS（合并上限 ⇒ 上限语义不变）。
        //   ⚠️ 中间入口的 `ENTER_SETTLE_MS` **不动**：它是给"下一个点击目标"出现的保障
        //      （点早了会点空 ⇒ 链失败），不是可以拿锚点替代的"末端就绪"。
        val anchorObj = step.optJSONObject("anchor")
        val budgetMs = (if (lastIdx >= 0) ENTER_SETTLE_MS else 0L) + SCREEN_SETTLE_MS
        if (!pollAnchorReady(anchorObj, budgetMs)) delay(budgetMs)
        assertAnchor(anchorObj, step, isRetry)    }

    /**
     * 轮询「锚点就绪」：命中即**立即**返回 true；打满 [budgetMs] 未命中返回 false。
     *
     * 用途：把 `enterScreen` 末尾的 `SCREEN_SETTLE_MS` 盲等换成"页面就绪即走"。
     * ⚠️ 未命中时**不在这里**触发重试/重跑入口链 —— 那仍归 [assertAnchor]（保持失败路径不变）。
     * ⚠️ 与 `assertAnchor` 的 OCR 判据**刻意保持同款**（expect 正则 + 数字/斜杠 fallback + prefixStrict）；
     *    两处重复是刻意的：`assertAnchor` 的日志要区分"直接命中/fallback 命中"，合并会丢可诊断性。
     */
    private suspend fun pollAnchorReady(anchor: JSONObject?, budgetMs: Long): Boolean {
        val gateway = ocr ?: return false
        if (anchor == null || anchor.optString("kind") != "ocr") return false
        val expect = anchor.optString("expect")
        if (expect.isEmpty()) return false
        val rect = runCatching { profile.rect(anchor.getString("rect").removePrefix("$")) }.getOrNull() ?: return false
        val regex = Regex(expect)
        val fallback = Regex("\\d+\\s*/\\s*\\d+")
        val prefixStrict = anchor.optBoolean("prefixStrict", false)
        val zhWords = Regex("[\\u4e00-\\u9fa5]{2,}").findAll(expect).map { it.value }.toList()
        var waited = 0L
        while (waited < budgetMs) {
            delay(ANCHOR_POLL_MS)
            waited += ANCHOR_POLL_MS
            val f = try { freshFrame(SETTLE_FRAME_TIMEOUT_MS) } catch (_: Exception) { null } ?: continue
            val text = try { gateway.readLines(f, listOf(rect)).joinToString(" ") } finally { f.release() }
            val cleaned = StatParser.clean(text)
            var hit = regex.containsMatchIn(cleaned)
            if (!hit && fallback.containsMatchIn(cleaned)) {
                hit = if (prefixStrict) zhWords.isEmpty() || zhWords.any { cleaned.contains(it) } else true
            }
            if (hit) {
                Log.i(TAG, "enterScreen: 锚点轮询命中 ${waited}ms（省 ${budgetMs - waited}ms）'$text'")
                return true
            }
        }
        Log.i(TAG, "enterScreen: 锚点轮询 ${budgetMs}ms 未命中，交 assertAnchor 处理")
        return false
    }

    /**
     * anchor.kind=ocr：rect 内文本匹配 expect 正则（另有数字宽容 fallback——OCR 对
     * "圣遗物"等前缀字易错漏，数字/斜杠才是稳定特征）。
     * 一轮 3 次重试失败 → 重跑入口链（退出重进，onZero 同款模式）→ 再失败才终止。
     */
    private suspend fun assertAnchor(anchor: JSONObject?, step: JSONObject, isRetry: Boolean) {
        if (anchor == null) return
        val kind = anchor.optString("kind")
        when (kind) {
            "template" -> {
                // 模板锚点：OpenCV matchTemplate + NMS 取最高响应（配置/阈值全读 dsl/templates.json）
                val ref = anchor.optString("ref")
                if (ref.isEmpty()) {
                    Log.w(TAG, "assertAnchor: template 锚点缺 ref")
                    return
                }
                val retries = anchor.optInt("retries", ANCHOR_RETRIES)
                for (attempt in 1..retries) {
                    val frame = freshFrame()
                    val r = try {
                        TemplateMatcher.matchAnchor(frame, ref, profile)
                    } finally {
                        frame.release()
                    }
                    if (r.matched) {
                        Log.i(
                            TAG,
                            "anchor matched (template '$ref', attempt $attempt): score=${"%.3f".format(r.score)}",
                        )
                        return
                    }
                    if (attempt < retries) delay(CLICK_SETTLE_MS)
                }
                Log.w(TAG, "assertAnchor: template '$ref' 未命中（$retries 次）——入口可能未到达")
                return
            }
            "ocr" -> {
                if (!anchor.has("expect")) {
                    Log.w(TAG, "assertAnchor: ocr 锚点无 expect，跳过文本断言（到达校验）")
                    return
                }
            }
            else -> {
                Log.w(TAG, "assertAnchor: kind='$kind' 未实现（支持 ocr/template），本次跳过断言")
                return
            }
        }
        if (ocr == null) return
        val rect = profile.rect(anchor.getString("rect").removePrefix("$"))
        val expect = anchor.getString("expect")
        val regex = Regex(expect)
        val fallback = Regex("\\d+\\s*/\\s*\\d+")
        for (attempt in 1..ANCHOR_RETRIES) {
            val frame = freshFrame()
            val text = try {
                ocr.readLines(frame, listOf(rect)).joinToString(" ")
            } finally {
                frame.release()
            }
            val cleaned = StatParser.clean(text)
            if (regex.containsMatchIn(cleaned)) {
                Log.i(TAG, "anchor matched (attempt $attempt): '$text'")
                return
            }
            if (fallback.containsMatchIn(cleaned)) {
                // 数字兜底防跨 tab 误配：prefixStrict=true（如 weapon_scan 声明）时 expect 的中文前缀词
                // 必须出现在 OCR 文本（武器/圣遗物背包 count 均为 "x/y" 格式）。
                // 默认 false 宽松：模拟器/低质量 OCR 常丢"圣遗物"前缀小字，严格会拒掉唯一可用路径。
                val zhWords = Regex("[\\u4e00-\\u9fa5]{2,}").findAll(expect).map { it.value }.toList()
                val prefixOk = if (anchor.optBoolean("prefixStrict", false)) {
                    zhWords.isEmpty() || zhWords.any { cleaned.contains(it) }
                } else {
                    true
                }
                if (prefixOk) {
                    Log.i(TAG, "anchor matched via fallback (attempt $attempt): '$text'")
                    return
                }
                Log.w(TAG, "fallback digits matched but prefix $zhWords missing: '$text'")
            }
            Log.w(TAG, "anchor attempt $attempt/$ANCHOR_RETRIES mismatch: '$text' vs /$expect/")
            delay(ANCHOR_RETRY_DELAY_MS)
        }
        if (!isRetry) {
            Log.w(TAG, "anchor failed, pressing BACK to clear popups then reopening screen")
            // 清游戏每日弹窗（签到/物品过期等）——返回键只关界面不退游戏
            runCatching { actions.back() }
            delay(1200)
            enterScreen(step, isRetry = true)
            return // 重跑内 assertAnchor 已再验证；仍失败则抛
        }
        // 断言失败 = 界面未到达：继续扫描只会全错，终止比带病跑偏好
        throw ScanAbortedException("enterScreen anchor not reached: /$expect/")
    }

    private fun parseChainAnchor(entry: String): String {
        // "game_home→bagpack(2786,36)" → "bagpack"；括号内坐标为人工核对提示，机读走 profile
        return entry.substringBefore('(').substringAfterLast('→').trim()
    }

    // ---- #6 dualStateButton：判态（pill 底色）+ ensure ----
    /**
     * 管理面板「已装备」标识：`panels.artifact_manage.equipped` ROI 有字 → 该件正被穿戴
     * （决定 leftBtn 当前是「卸下」还是「替换」）。OCR 不可用/读空按未装备处理。
     */
    private suspend fun isCurrentlyEquipped(): Boolean {
        val gateway = ocr ?: return false
        val arr = profile.rawObject("panels.artifact_manage")?.optJSONArray("equipped") ?: return false
        if (arr.length() < 4) return false
        val r = profile.scaleRect(arr.getInt(0), arr.getInt(1), arr.getInt(2), arr.getInt(3))
        val frame = try {
            freshFrame()
        } catch (_: Exception) {
            return false
        }
        return try {
            StatParser.clean(gateway.readLines(frame, listOf(r)).joinToString(" ")).isNotEmpty()
        } finally {
            frame.release()
        }
    }

    private suspend fun dualStateButton(step: JSONObject) {
        // §14 P2：ref 形态（"$screens.artifact_manage.leftBtn"）——非药丸双态，而是按 rect
        // 点击的动作按钮。states 描述语义：
        //   替换 → 点击后由后续 dialog(equipConfirm) 步骤确认（仅跨角色替换才弹窗）；
        //   卸下 → 无确认弹窗，且卸下后网格不回第一行 → 按 profiles resetChain 复位。
        val ref = step.optString("ref", "")
        if (ref.startsWith("$")) {
            val path0 = ref.removePrefix("$")
            // readonly（如 rightBtn 强化/重塑）：只判态、**绝不点击**——误点会进强化/重塑界面
            if (step.optBoolean("readonly", false)) {
                Log.i(TAG, "dualStateButton ref=$path0: readonly，仅判态不点击")
                return
            }
            val obj0 = profile.rawObject(path0)
            val rectArr = obj0?.optJSONArray("rect")
            if (rectArr != null && rectArr.length() >= 4) {
                val r = profile.scaleRect(
                    rectArr.getInt(0), rectArr.getInt(1), rectArr.getInt(2), rectArr.getInt(3),
                )
                Log.i(TAG, "dualStateButton ref=$path0 → 点击 (${r.centerX},${r.centerY})")
                actions.click(r.centerX, r.centerY)
                delay(CLICK_SETTLE_MS)
                val hasUnequip = step.optJSONObject("states")?.keys()?.asSequence()
                    ?.any { it.contains("卸下") } == true
                if (hasUnequip && isCurrentlyEquipped()) {
                    @Suppress("UNCHECKED_CAST")
                    val chain = profile.rawAny("screens.artifact_manage.resetChain") as? JSONArray
                    if (chain != null) {
                        Log.i(TAG, "dualStateButton: 卸下后网格复位（${chain.length()} 步）")
                        for (i in 0 until chain.length()) clickChainEntry(chain.getString(i))
                    }
                }
                return
            }
            // 无 rect：带 pill（如 fiveStarToggle 五星筛选）→ 落到下方 pill 判态 ensure；
            //   两者皆无才跳过（原实现一律跳过，5★ 筛选 ensure 长期静默失效）
            if (obj0 == null) {
                Log.w(TAG, "dualStateButton ref '$path0' missing in profile")
                return
            }
            if (obj0.opt("pill") == null) {
                Log.w(TAG, "dualStateButton ref='$path0' 无 rect 且无 pill，跳过")
                return
            }
        }
        val path = ref.removePrefix("$")
        val obj = profile.rawObject(path) ?: run {
            Log.w(TAG, "dualStateButton ref '$path' missing in profile")
            return
        }
        // pill 两种形态兼容：纯 rect 数组 或 {rect:[...]} 对象
        val pillArr: JSONArray = when (val pill = obj.opt("pill")) {
            is JSONArray -> pill
            is JSONObject -> pill.optJSONArray("rect") ?: run {
                Log.w(TAG, "dualStateButton '$path'.pill has no rect")
                return
            }
            else -> {
                Log.w(TAG, "dualStateButton '$path' has no pill")
                return
            }
        }
        val ensure = step.optString("ensure", "off")
        var attempts = 0
        while (attempts < 3) {
            val frame = freshFrame()
            val state = try {
                pillState(frame, pillArr)
            } finally {
                frame.release()
            }
            if (state == ensure) {
                Log.i(TAG, "dualStateButton '$path' is $ensure")
                return
            }
            val rect = profile.scaleRect(
                pillArr.getInt(0), pillArr.getInt(1), pillArr.getInt(2), pillArr.getInt(3),
            )
            actions.click(rect.centerX, rect.centerY)
            delay(CLICK_SETTLE_MS)
            attempts++
        }
        Log.w(TAG, "dualStateButton '$path' could not reach '$ensure' after retries")
    }

    /**
     * 药丸底色判态：off=深藏青底（金像素≈0），on=金底（金像素占比过半）。
     * profiles: off="深藏青底+金圈×在左", on="金底+深✓在右"。
     */
    private fun pillState(frameBgr: Mat, pillArr: JSONArray): String {
        val rect = profile.scaleRect(
            pillArr.getInt(0), pillArr.getInt(1), pillArr.getInt(2), pillArr.getInt(3),
        )
        val sample = FrameRect(
            rect.centerX - rect.width / 4,
            rect.centerY - rect.height / 4,
            rect.centerX + rect.width / 4,
            rect.centerY + rect.height / 4,
        )
        val gold = VoteJudges.countMatches(frameBgr, sample, VoteJudges.GOLD)
        val area = sample.width.coerceAtLeast(1) * sample.height.coerceAtLeast(1)
        return if (gold > area / 2) "on" else "off"
    }

    // ---- #2 readCount：右上总数 OCR（"圣遗物 1026/2400" → 1026；0 件时重进界面重试）----
    private suspend fun readCount(step: JSONObject) {
        if (ocr == null) {
            Log.w(TAG, "readCount skipped: OcrGateway not available")
            return
        }
        var n = readCountOnce(step, ocr)
        if (n == 0 && step.optString("onZero") == "reopenAndRetry") {
            // 读到 0：大概率停在了弹窗/半透明层——重跑入口链（退出重进）后再读一次。
            // ⚠️ 只重跑 `enterScreen`（点背包 → 锚点校验），**刻意不重跑 `filterReset`**：
            //    筛选是**游戏侧持久状态**，进入本页时已复位过；重进再开合一次筛选面板纯属多余，
            //    且会平白多一次「点到页面按钮 ⇒ 弹出全屏模态面板」的风险敞口。
            Log.w(TAG, "readCount got 0, reopening screen and retrying")
            enterScreenStep?.let { reentry -> enterScreen(reentry) }
            n = readCountOnce(step, ocr)
        }
        vars.total = n
    }

    private suspend fun readCountOnce(step: JSONObject, gateway: OcrGateway): Int? {
        val frame = freshFrame()
        return try {
            val rect = profile.rect(step.getString("rect").removePrefix("$"))
            gateway.readNumber(frame, rect)
        } finally {
            frame.release()
        }
    }

    // ---- #3 pagedGrid：网格遍历编排 ----
    private suspend fun pagedGrid(step: JSONObject) {
        val gridKey = step.getString("grid")
        val visit = step.getJSONArray("visit")
        val rawGrid = profile.rawObject("grids.$gridKey")
        if (rawGrid == null) {
            Log.w(TAG, "pagedGrid: grids.$gridKey missing, skipped")
            return
        }
        val advance = rawGrid.getJSONObject("advance")
        // §14 A1/A3：两种推进模式——snap（click-to-snap 零滑动，仅选择界面）/ swipe
        if (advance.optString("type") == "snap") {
            snapTraverse(gridKey, visit, advance, step)
            return
        }
        val cols = rawGrid.optInt("cols", -1)
        val traverseRows = rawGrid.optInt("traverseRows", -1)
        if (cols < 1 || traverseRows < 1) {
            Log.w(TAG, "pagedGrid[$gridKey]: cols/traverseRows missing (cols=$cols rows=$traverseRows), skipped")
            return
        }
        // §14 A4 pageSkip：按页快筛（末格等级已低于目标最高等级 → 整页跳过，仍继续翻页）。
        // flow 表达式含 max(targets.level) 函数调用，Expr 仅支持变量/比较 → 引擎预计算后文本替换。
        val pageSkipExpr = step.optJSONObject("pageSkip")?.optString("expr")
            ?.replace("max(targets.level)", "targetMaxLevel")
        val advFrom = advance.getJSONArray("from")
        val advTo = advance.getJSONArray("to")
        // §15 回顶（暂缓）：pagedGrid 起始回顶在真机出现 hang（settle 后无后续日志），
        // 已回滚。列表位置跨会话残留 → 待办：weapon_scan 前手动复位列表或实现安全回顶
        // （疑 actions.swipe 连续派发被 EMUI 无障碍节流挂起）。
        // §12.1 起点规范化：优先用几何推导的落点（末尾两卡间隙中点 + 锚行上沿+5），
        // 几何不足或显式关闭时回退到 profiles.advance.from 的写死坐标（A/B 实测用）。
        val geoStart = if (useGeometryAdvance) profile.advanceStart(gridKey) else null
        val geoDist = if (useGeometryAdvance) profile.advanceDistance(gridKey) else null
        if (geoStart != null && geoDist != null) {
            Log.i(
                TAG,
                "advance[$gridKey]: 几何起点 base=(${geoStart.x},${geoStart.y}) dist=$geoDist" +
                    "（回退坐标 base=(${advFrom.getInt(0)},${advFrom.getInt(1)})→(${advTo.getInt(0)},${advTo.getInt(1)})）",
            )
        } else {
            Log.i(
                TAG,
                "advance[$gridKey]: 写死坐标 base=(${advFrom.getInt(0)},${advFrom.getInt(1)})→" +
                    "(${advTo.getInt(0)},${advTo.getInt(1)})",
            )
        }
        // 翻页距离：几何名义值（§12.5 相位偏移承担精度；自适应距离移除——距离积分只能消均值
        // 偏差、消不掉单页抖动，相位偏移 + 相位校正已覆盖其职责且保证本页对齐）
        var dist = geoDist
        var pagesAdvanced = 0
        // §12.5 相位偏移遍历：本页网格视图随累计相位 φ 平移（首页顶对齐 φ=0）。
        // 测量（GridAlign 相位参考）永远用原始 profile；offset 视图只进点击/投票坐标。
        var pageProfile = profile
        // p0 基准：清上轮残留（GridAlign 单例跨扫描持久），首帧顶对齐建立（见下方 beforeFrame）
        // §16.2 安全回顶：进背包连续上滑到顶（避 EMUI 节流：每轮单次 swipe + awaitGridStable 吸收）
        if (step.optBoolean("scrollToTop", false)) {
            // 背包列表跨会话残留 → 先滚到顶。此处要求「连续 2 次未动」才认定到顶（双保险）。
            val tTop = System.nanoTime()
            try {
                swipeGridToTop(gridKey, "scrollGridToTop", stableRounds = GRID_TOP_STABLE_ROUNDS)
            } finally {
                PerfProbe.addStep("scrollToTop", System.nanoTime() - tTop)
            }
        }
        GridAlign.resetBaseline(gridKey)
        var capturedBaseline = false
        // §14 A4 pageSkip 状态：页序号 + 上一页最低等级（背包按等级降序 → 末格即本页最低）
        var pageNo = 0
        var pageMinLevel: Int? = null
        // 回卷止扫阈值 = **本网格一页的卡片数**（各流程/网格自动不同；见 dupLimitEffective 的 KDoc）
        dupLimitEffective = cols * traverseRows
        dupPageStreak = 0
        dupPageDecided = false
        Log.i(
            TAG,
            "pagedGrid[$gridKey]: 回卷止扫阈值 = 一页卡片数 $cols×$traverseRows = $dupLimitEffective" +
                "，且需连续 $dupPageConfirm 个整页零新增才断言回卷",
        )

        while (true) {
            if (vars.stopRequested) break
            // §14 A4 pageSkip 命中则整页跳过（仍继续翻页，避免提前终止漏掉后续页）
            val skip = pageSkipExpr != null && pageMinLevel != null && runCatching {
                Expr.eval(
                    pageSkipExpr,
                    mapOf(
                        "pageMinLevel" to pageMinLevel!!,
                        "targetMaxLevel" to (maxTargetLevel() ?: Int.MAX_VALUE),
                    ),
                )
            }.getOrDefault(false)
            // §13：产物入库按页聚合（逐件会 21 行/页刷屏；逐件明细归 D 级，verbose 时才有）
            val beforeCount = results.size + resultsWeapons.size + resultsCharacters.size
            if (skip) {
                Log.i(TAG, "pagedGrid[$gridKey]: pageSkip 命中（pageMinLevel=$pageMinLevel）第 $pageNo 页整页跳过")
            } else {
                // 本页：traverseRows 行 × cols 列（visibleRows 第 4 行是滑动锚，不遍历）。
                // scope=cell 止扫（3★/2★ 标识）：置 flag 后当页仍完整遍历（PC 策略「本页后停」），页尾 break。
                // ⚠️ **例外：回卷止扫（duplicateStreak 命中）必须立即停** —— 回卷意味着后面全是**已入库**的件，
                //   走完本页纯属浪费（实测：21 格重复件 → +14.6s，perCell 293→526ms）。
                //   「本页后停」是为 scope=cell 的 3★/2★ 止扫设计的（避免漏掉同页后面的高稀有度件）；
                //   回卷没有"本页后"的意义 ⇒ 单独走立即停。
                // ⚠️ **每页开头清零连续重复计数**（2026-09-12 全量实测根因）：
                //    实测翻页一页只前进 ~1.9 行（应 3 行）⇒ 上一页尾部的重复会与本页头部的重复**跨页累计**
                //    （实测 page6 尾 8 + page7 头 13 = 21 = 一页卡片数）⇒ 在第 8 页就误判回卷停住。
                //    清零后「连续 N 个重复」只能落在**同一页内** ⇒ 语义正好回到用户定谳的
                //    「一页卡片数」= **该页 0 件新增**。
                if (vars.charDupStreak > 0) {
                    Log.i(
                        TAG,
                        "pagedGrid[$gridKey]: page=$pageNo 开页清零连续重复计数（原 ${vars.charDupStreak}，防跨页累计误判回卷）",
                    )
                }
                vars.charDupStreak = 0
                dupPageDecided = false
                var dupWrapped = false
                for (row in 0 until traverseRows) {
                    if (dupWrapped) break
                    for (col in 0 until cols) {
                        // ⚠️ 这里**刻意不看 `vars.stopRequested`**：`scope=cell` 的 3★/2★ 止扫是 PC 策略
                        //   「本页后停」（当页仍完整遍历，避免漏掉同页后面的高稀有度件）。
                        //   一旦在此加 stopRequested 检查，就会把它变成「立即停」——
                        //   实测三星期用例由 21 格/页塌成 1 格/页（expected 46 but was 26）。勿加。
                        val dupLim = if (dupLimitEffective > 0) dupLimitEffective else charDupStreakLimit
                        if (dupLim > 0 && vars.charDupStreak >= dupLim) {
                            // 页内已攒满「一页卡片数」个连续重复 ⇒ 本页疑似零新增。
                            // ⚠️ 不再无条件停：先过**页级确认**（连续 dupPageConfirm 个整页零新增）。
                            val confirmed = dupRollbackConfirmed()
                            if (!confirmed) vars.charDupStreak = 0
                            dupWrapped = true
                            Log.i(
                                TAG,
                                "pagedGrid[$gridKey]: " +
                                    if (confirmed) {
                                        "回卷止扫（确认连续 $dupPageStreak 个整页零新增 ≥ $dupPageConfirm）"
                                    } else {
                                        "整页重复第 $dupPageStreak 次（< $dupPageConfirm）⇒ 疑似滑空/半页重叠，清计数继续翻页"
                                    } +
                                    " ⇒ 立即结束本页（已入库 ${results.size + resultsWeapons.size + resultsCharacters.size} 件）",
                            )
                            break
                        }
                        val idx = row * cols + col
                        val beforeCell = results.size + resultsWeapons.size + resultsCharacters.size
                        if (col == 0 && row == 0) tmPages++
                        tmCells++
                        Log.i(TAG, "pagedGrid[$gridKey] page=$pageNo start cell($col,$row) idx=$idx")
                        runVisit(visit, gridKey, col, row, idx, pageProfile)
                        val addedCell = results.size + resultsWeapons.size + resultsCharacters.size - beforeCell
                        Log.i(TAG, "pagedGrid[$gridKey] page=$pageNo done cell($col,$row) idx=$idx added=$addedCell")
                    }
                }
                // 背包按等级降序 → 本页末格等级即本页最低等级（作下页 pageSkip 判据）
                if (vars.level > 0) pageMinLevel = vars.level
            }
            val added = results.size + resultsWeapons.size + resultsCharacters.size - beforeCount
            RecognitionLog.log(logTag, RecognitionLog.Level.I, "第 $pageNo 页 入库 $added 件")
            pageNo++
            if (vars.stopRequested) break

            // 翻页：路标链滑动 → 自适应 settle（轮询指纹至稳定）→ 相位测量（超限补滑）→ 指纹比对判到底
            val beforeFrame = freshFrame()
            // §12.5 p0 基准：首帧（进背包顶对齐、尚未翻页）建立，phaseOffset 自此以它为参考
            if (!capturedBaseline) {
                GridAlign.captureBaseline(beforeFrame, profile, gridKey)
                capturedBaseline = true
            }
            val beforeThumb: ByteArray?
            val beforeProf: DoubleArray?
            var prevGray: Mat? = null   // ★ 2D 位移测量的"上一帧"灰度图（2026-09-13 周期歧义修复）
            try {
                beforeThumb = VoteJudges.gridThumb(beforeFrame, profile, gridKey)
                beforeProf = VoteJudges.gridRowProfile(beforeFrame, profile, gridKey)
                runCatching {
                    val g = Mat()
                    if (beforeFrame.channels() == 1) beforeFrame.copyTo(g)
                    else org.opencv.imgproc.Imgproc.cvtColor(beforeFrame, g, org.opencv.imgproc.Imgproc.COLOR_BGR2GRAY)
                    prevGray = g
                }
            } finally {
                beforeFrame.release()
            }
            // 翻页距离（帧坐标）：默认 = 几何推导 3 行；`advdist` 可绝对覆盖、`advextra` 可加偏置
            // —— 用于标定"游戏实际滚动量 < 手指行程"造成的页面重叠（2026-09-12 实测每页只覆盖 ~17/21 件）。
            val advDist = when {
                TimingOverrides.advanceDistPx > 0 -> TimingOverrides.advanceDistPx
                dist != null -> dist + TimingOverrides.advanceExtraPx
                else -> null
            }
            // 期望推进量（帧 px，统一 Int）：几何路径 = advDist；profile 字面路径 = |to.y − from.y|
            val advTarget: Int = (advDist ?: Math.abs(
                profile.scale(advTo.getInt(1), profile.scaleY) -
                    profile.scale(advFrom.getInt(1), profile.scaleY),
            ).toLong()).toInt()
            if (advDist != null && geoStart != null && advDist != dist) {
                Log.i(TAG, "advance[$gridKey]: 翻页距离覆盖 dist=${dist} → $advDist")
            }
            // ── ★ 落地位移**闭环**（2026-09-12）────────────────────────────────────────────
            // 开环的根本问题：单次手势的**落地距离 ≠ 指令距离**（且每页都在变），而自适应点击位置只能
            // 吸收 ±半卡高（≈126px）。这里改为：每次滑动后用 `VoteJudges.profileShift`（行亮度剖面纵向
            // 互相关，**只读**）实测落地像素 ⇒ 不足则按**实测增益**补滑，直到累计落地贴近目标。
            //   · 首滑命令 = 目标 / 增益记忆（跨页 EMA）⇒ 第 2 页起通常一次就够；
            //   · 容差默认 = 行距 × 0.4（≈82px，仍 < 126px）；`advloop=0` 回到单次滑动（原行为）；
            //   · 命令值夹在 [minCmd, maxCmd]：maxCmd 由起手 y 与屏幕底边余量推出（手势不能出屏）。
            val rowPitch = profile.gridGeometryFor(gridKey)
                ?.let { Math.round(it.rowPitch * profile.scaleY).toInt() }
                ?.takeIf { it > 40 } ?: 204
            val advTol = if (TimingOverrides.advanceTolPx > 0) {
                TimingOverrides.advanceTolPx
            } else {
                Math.round(rowPitch * 0.4f)
            }
            val minCmd = Math.round(rowPitch * 0.6f)
            val maxCmdRaw = if (geoStart != null) {
                (geoStart.y - ADV_MIN_END_Y).coerceAtLeast(minCmd + 40)
            } else {
                advTarget
            }
            // `advcmdmax`：把单次命令压到游戏 fling 阈值以下 ⇒ 落地 ≈ 命令（实测命令 60 落地 51、
            // 命令 612 落地 720 不可控）。配合 `advmax` 多做几步即可线性逼近目标。
            val maxCmd = if (TimingOverrides.advanceCmdMaxPx > 0) {
                Math.min(maxCmdRaw, Math.max(TimingOverrides.advanceCmdMaxPx, minCmd))
            } else {
                maxCmdRaw
            }
            val loopOn = TimingOverrides.advanceLoop > 0 && geoStart != null && beforeProf != null
            var latest: Mat
            if (loopOn) {
                var prevProf = beforeProf!!
                var gain = advGainEma.coerceIn(0.25, 1.0)
                var totalDy = 0
                var steps = 0
                var outFrame: Mat? = null
                while (outFrame == null) {
                    val need = advTarget - totalDy
                    // ⚠️ **保守**命令：`cmd = min(剩余, 剩余/增益)` —— 只在增益 >1 时压小，**从不放大**。
                    //   为什么不做"按增益放大"的预补偿：**过冲会整行跳过 ⇒ 漏件**（少滚只是本页重叠，安全）。
                    //   代价是本页可能 2~3 次滑动；收益是绝不丢件。增益记忆仅用于"增益 >1 时收敛"。
                    val cmd: Int = Math.min(
                        need,
                        Math.round(need.toDouble() / gain).toInt(),
                    ).coerceIn(60, maxCmd)
                    actions.swipe(geoStart!!.x, geoStart!!.y, geoStart!!.x, geoStart!!.y - cmd)
                    val f = awaitGridStable(profile, gridKey)
                    steps++
                    val p = VoteJudges.gridRowProfile(f, profile, gridKey)
                    var curGray: Mat? = null
                    runCatching {
                        val g = Mat()
                        if (f.channels() == 1) f.copyTo(g)
                        else org.opencv.imgproc.Imgproc.cvtColor(f, g, org.opencv.imgproc.Imgproc.COLOR_BGR2GRAY)
                        curGray = g
                    }
                    // ★ 位移测量：**2D 模板优先**（卡片图案唯一 ⇒ 无周期歧义）；不可信时回退 1D+期望先验
                    val m2 = if (prevGray != null && curGray != null)
                        VoteJudges.gridShift2D(prevGray!!, curGray!!, profile, gridKey) else null
                    // 期望位移 = 本次命令（周期歧义消解的先验，见 profileShift 注释）
                    val m1 = if (p != null) VoteJudges.profileShift(prevProf, p, cmd) else null
                    val m = m2 ?: m1
                    prevGray?.release()
                    prevGray = curGray
                    val raw = if (m != null && m.second >= VoteJudges.PROFILE_SHIFT_MIN_SCORE) m.first else -1
                    // ⚠️ **可信性守卫**（2026-09-12 实测）：互相关偶发"锁到相邻周期"⇒ 命令 60px 却测出 714px。
                    //    这类野值若计入累计，会把增益 EMA 冲到 6.4 并把页面推飞（实测 累计=1537）。故：
                    //    落地 > 命令×2 + 80 ⇒ 判为不可信，**不计入、不更新增益**，并按"测不到"处置。
                    val plausible = raw >= 0 && raw <= cmd * 2 + 80
                    val dy = if (plausible) raw else -1
                    if (dy >= 0) {
                        totalDy += dy
                        val g = (dy.toDouble() / cmd).coerceIn(0.03, 1.6)
                        gain = (0.5 * gain + 0.5 * g).coerceIn(0.03, 1.6)
                        prevProf = p!!
                    }
                    // ⚠️ **必须在此处（已计入本次落地后）重算** remaining：若沿用发送前的值，
                    //    本步已到位/已过冲也会被判成"还没够" ⇒ 再朝前补一次 ⇒ 把过冲越推越大。
                    val remaining = advTarget - totalDy
                    // ⚠️ 停止条件用 `remaining <= advTol`（**只判"够了/超过了"**），**绝不朝前追过冲**：
                    //    过冲（remaining<0）交给已有的「相位校正」反向拉回（|φ|>半卡高才补，代价小）；
                    //    若在这里继续发正向滑动，只会越推越远（实测 -102 → -252 → -925）。
                    val done = dy < 0 || remaining <= advTol || steps >= TimingOverrides.advanceMaxSteps
                    Log.i(
                        TAG,
                        "advance[$gridKey]: 闭环#$steps 命令=$cmd 落地=${if (raw >= 0) "${raw}px" else "测不到"}" +
                            (if (raw >= 0 && !plausible) "(不可信>$cmd×2)" else "") +
                            " score=${m?.second?.let { "%.2f".format(it) } ?: "null"}" +
                            " 累计=$totalDy 目标=$advTarget 剩余=$remaining 增益=${"%.2f".format(gain)}" +
                            if (done) " ⇒ 结束" else " ⇒ 补滑",
                    )
                    if (done) {
                        outFrame = f
                    } else {
                        f.release()
                    }
                }
                latest = outFrame!!
                advGainEma = gain
            } else {
                // 原行为：单次滑动（`advloop=0` 或非几何路径）
                if (geoStart != null) {
                    // 用 advTarget（= advDist ?: |to.y−from.y|）而非 advDist!!：非几何路径下 advDist 可能为 null
                    actions.swipe(geoStart!!.x, geoStart!!.y, geoStart!!.x, geoStart!!.y - advTarget)
                } else {
                    actions.swipe(
                        profile.scale(advFrom.getInt(0), profile.scaleX),
                        profile.scale(advFrom.getInt(1), profile.scaleY),
                        profile.scale(advTo.getInt(0), profile.scaleX),
                        profile.scale(advTo.getInt(1), profile.scaleY),
                    )
                }
                latest = awaitGridStable(profile, gridKey)
            }
            // ⚠️ 翻页**未生效**的重试守卫（2026-09-12 实测新增）：实测约 1/17 页的翻页滑动**完全没落地**
            //   （整页 21 格全重复），且失败**连续出现** ⇒ 回卷判据「连续 2 个整页零新增」会把整轮扫描
            //   提前收掉（实测 210/933、110/933）。`reachedEnd`（翻页前后指纹不变）无法区分
            //   「列表真到底（钳制）」与「这次滑动没落地」⇒ **先重发滑动**，连续 [GRID_END_RETRIES] 次
            //   都纹丝不动才认定到底。代价不对称：真到底多滑 3 次 ≈ 3s；误判则整轮报废。
            var latestThumb: ByteArray? = null
            var endTries = 0
            var atEnd = false
            while (true) {
            // §12.5 相位偏移遍历（2026-09-05，替代逐页对齐补滑）：遍历目标是「视口内 3 行卡片」
            // 而非「回到初始相位」。累计相位 φ = centeredMod(det − p0) 即本页网格相对 profiles
            // 几何的真实偏移（±整行错位被 mod 吸收）→ 直接平移下一页点击/投票坐标，不滑动。
            // 仅当 |φ| 超过卡片半高（逼近 ±146 混叠边界，再多滚会整行跳过 7 卡）时补滑拉回；
            // 补滑把新相位驱动到 ~0（小距增益 ~0.8，循环重测收敛），少滚方向天然只重叠不漏。
            // 测量用 base profile（固定参考）；指纹判到底取最终帧。
            var corrections = 0
            val cardHalf = (profile.gridGeometryFor(gridKey)?.let { profile.scale(it.cardH, profile.scaleY) } ?: 253) / 2
            var measured = GridAlign.phaseOffset(latest, profile, gridKey)
            while (measured != null && Math.abs(measured) > cardHalf && corrections < MAX_FINISH_SWIPES) {
                val sx = geoStart?.x ?: profile.scale(advFrom.getInt(0), profile.scaleX)
                val sy = geoStart?.y ?: profile.scale(advFrom.getInt(1), profile.scaleY)
                Log.i(TAG, "advance[$gridKey]: 相位校正#${corrections + 1} φ=$measured (阈值 $cardHalf)")
                actions.swipe(sx, sy, sx, sy - measured)
                val next = awaitGridStable(profile, gridKey)
                latest.release()
                latest = next
                corrections++
                measured = GridAlign.phaseOffset(latest, profile, gridKey)
            }
            if (measured != null) {
                pageProfile = profile.withGridRowOffset(measured)
                Log.i(TAG, "advance[$gridKey]: 页面相位 φ=$measured corrections=$corrections")
            } // φ 测量失败（守卫不通过）：沿用上一页偏移（相位近似延续）

            latestThumb = try {
                VoteJudges.gridThumb(latest, profile, gridKey)
            } finally {
                latest.release()
            }
            if (!reachedEnd(beforeThumb, latestThumb)) break
            if (endTries >= GRID_END_RETRIES) {
                atEnd = true
                Log.i(TAG, "pagedGrid reached end (fingerprint unchanged ×${endTries + 1})")
                break
            }
            endTries++
            Log.w(
                TAG,
                "pagedGrid[$gridKey]: 翻页指纹未变（疑似滑动未生效；已到底也会如此）⇒ 重发翻页滑动 #$endTries",
            )
            if (geoStart != null && advDist != null) {
                actions.swipe(geoStart.x, geoStart.y, geoStart.x, geoStart.y - advDist)
            } else {
                actions.swipe(
                    profile.scale(advFrom.getInt(0), profile.scaleX),
                    profile.scale(advFrom.getInt(1), profile.scaleY),
                    profile.scale(advTo.getInt(0), profile.scaleX),
                    profile.scale(advTo.getInt(1), profile.scaleY),
                )
            }
            latest = awaitGridStable(profile, gridKey)
            }
            if (atEnd) break
            pagesAdvanced++
            Log.i(TAG, "pagedGrid advanced to page ${pagesAdvanced + 1} (maxPages=$maxPages)")
            if (pagesAdvanced >= maxPages) {
                Log.i(TAG, "pagedGrid early stop: reached maxPages=$maxPages (debug 早停，用于翻页准确性验证)")
                vars.stopRequested = true
                vars.stopReason = "maxPages"
                break
            }
        }
    }

    // §16.2 安全回顶（rosterFind 名册 / pagedGrid 背包 / setFilter 套装面板 三处共用一个实现）。
    // 反向 advance（to→from 上滑）连续多次，每轮 awaitGridStable + 缩略图差异判「是否真滚动」。
    // EMUI 节流规避：每轮单次 swipe 派发（不连续派发），靠 settle 轮询吸收节流。
    //
    // ⚠️ 两个易错点（三处重复实现时都踩过）：
    // 1. 判据必须用**松阈值** GRID_END_DIFF：静止态下 4-bit 量化边界抖动会让 diff 在 0.065~0.22
    //    乱跳、横跨紧阈值 GRID_SIMILAR_DIFF(0.10) → 时而判顶时而不判（曾白滑满上限）。真实翻页是
    //    整块位移（diff 0.62~1.00），与噪声带无重叠。
    // 2. awaitGridStable 返回的 Mat **归调用方释放**：原 rosterFind/setFilter 两处直接丢弃返回值
    //    → 每轮泄一个 native Mat（OpenCV 堆外内存）。
    //
    // @param stableRounds 需「连续未动」几次才认定到顶（≥1）
    // @return true = 判定到顶；false = 打满 maxRounds / stopRequested 中断 / 该网格无 advance
    private suspend fun swipeGridToTop(
        gridKey: String,
        logTag: String,
        stableRounds: Int = 1,
        maxRounds: Int = MAX_SCROLL_TOP_ROUNDS,
    ): Boolean {
        val grid = profile.rawObject("grids.$gridKey") ?: return false
        val adv = grid.optJSONObject("advance") ?: return false
        if (adv.optString("type") != "swipe") return false
        if (!adv.optBoolean("returnTop", true)) return false
        val from = adv.getJSONArray("from")
        val to = adv.getJSONArray("to")
        // ⚠️ swipe 的 x 必须取 advance.from 的 x（char_popup=297 中缝 / char_strip=80 左缘）：
        // 压在卡片上会被判成「拖卡片」而非滚页（equip12 实证回顶失效）。
        val fx = profile.scale(to.getInt(0), profile.scaleX)
        val fy = profile.scale(to.getInt(1), profile.scaleY)
        val tx = profile.scale(from.getInt(0), profile.scaleX)
        val ty = profile.scale(from.getInt(1), profile.scaleY)
        var stable = 0
        for (i in 1..maxRounds) {
            if (vars.stopRequested) return false
            val tb = gridThumbOf(gridKey)
            actions.swipe(fx, fy, tx, ty)
            // ⛔ 2026-09-12 试过「不等稳定、直接与滑动前缩略图比是否移动」来省掉每轮的 settle 等待，
            //   **实测不可行，已回退**。原因：`actions.swipe` 是**异步派发**（不等 780ms 手势走完），
            //   而列表在顶部时上滑会先被**拖起再回弹** ⇒ 手势期间 d 就 >GRID_END_DIFF（实测 12/12 轮
            //   `d=0.44~0.75 waited=120~240ms`）⇒ 每轮误判"已滚动"，12 个手势以 ~150ms 节奏**重叠派发**
            //   （1835ms）⇒ **到顶判定彻底失效**（起始页不是首页 ⇒ 漏件）。
            //   ⇒ 每轮**必须等手势结束 + 画面稳定**：wait 这一侧没有安全的提前量。
            //   （同一轮次里真正可省的是"手势时长 780ms 与稳定等待重叠"，但那不改变总时长。）
            val settled = awaitGridStable(profile, gridKey)
            val ta = try { VoteJudges.gridThumb(settled, profile, gridKey) } finally { settled.release() }
            val diff = VoteJudges.thumbChangedFraction(tb, ta)
            val moved = !(tb != null && ta != null && reachedEnd(tb, ta, GRID_END_DIFF))
            Log.i(
                TAG,
                "$logTag: 回顶#$i ${if (moved) "(已滚动)" else "(已到顶)"} " +
                    "diff=${diff?.let { "%.3f".format(it) } ?: "null"} thr=$GRID_END_DIFF",
            )
            // D 级（RecognitionLog.verbose）探针：把「变了哪些缩略图像素」打成 ASCII 掩码，
            // 用于区分「整块位移」与「零散量化抖动」。缩略图尺寸见 VoteJudges.gridThumb。
            // ⚠️ 掩码刻意用**严格不等**（不带容差）：带容差会把噪声像素抹掉，就看不出噪声密度了。
            if (RecognitionLog.verbose && tb != null && ta != null && tb.size == THUMB_COLS * THUMB_ROWS * 3) {
                Log.i(TAG, "$logTag: 回顶#$i ${thumbMask(tb, ta)}")
            }
            if (moved) stable = 0 else if (++stable >= stableRounds) return true
        }
        Log.w(TAG, "$logTag: 回顶达上限 $maxRounds 次仍未判定到顶")
        return false
    }

    /** 24×16 缩略图差异掩码（'.'=同 '#'=变，row0=网格上沿）；仅供 D 级诊断，故刻意不带容差。 */
    private fun thumbMask(a: ByteArray, b: ByteArray): String {
        val sb = StringBuilder("diffmask(24x16 . =同 # =变):\n")
        for (y in 0 until THUMB_ROWS) {
            for (x in 0 until THUMB_COLS) {
                val k = (y * THUMB_COLS + x) * 3
                val changed = a[k] != b[k] || a[k + 1] != b[k + 1] || a[k + 2] != b[k + 2]
                sb.append(if (changed) '#' else '.')
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    // §16.2 #3 rosterFind：角色名册找目标并点进（复用 char_popup 遍历 + parseCharacterPanel 取名）
    private suspend fun rosterFind(step: JSONObject) {
        // flow 写 "char": "$task.char"（foreach as=task 注入 currentTask）→ 解 $ 前缀引用；
        // 字面名（如 "希诺宁"）直用。
        val raw = step.optString("char", "").takeIf { it.isNotEmpty() }
        val target = when {
            raw != null && raw.startsWith("$") -> {
                val expr = raw.removePrefix("$") // task.char
                val ref = expr.substringAfter('.', "") // char
                val v = vars.currentTask
                val resolved = when {
                    v != null && ref.isNotEmpty() -> v.optString(ref).takeIf { it.isNotEmpty() }
                    else -> null
                }
                resolved ?: run { Log.w(TAG, "rosterFind: '$raw' 解析失败（currentTask.$ref 空）"); return }
            }
            raw != null -> raw
            else -> vars.currentTask?.optString("char")?.takeIf { it.isNotEmpty() }
        } ?: run { Log.w(TAG, "rosterFind: 无目标角色（step.char / currentTask.char 均空）"); return }
        val gridKey = step.optString("grid", "char_popup")
        val rawGrid = profile.rawObject("grids.$gridKey") ?: return
        val cols = rawGrid.optInt("cols", 3)
        val traverseRows = rawGrid.optInt("traverseRows", 3)
        val advance = rawGrid.optJSONObject("advance")
        var firstSeen: String? = null
        var found = false
        // ⚠️ 弹层内禁止 back：back 会关掉 char_popup → 后续点击全落详情页（equip9 实证死循环）。
        // 弹层内直接连点网格即切换详情（2026-09-10 逐格实测：12/12 格点到 12 个不同角色，8 次独立扫描一致）。
        // ★ 2026-09-10 用户定：**只有 auto_equip 走 char_popup**（12 卡/屏，找单个角色密度高）；
        //   角色遍历（character_scan）改走 grids.char_strip（左列头像条 1 列网格）。
        //   调用方负责开/收弹层（flow 里 clicks["tian"] 前后各一次），本函数只管遍历。
        //   （旧注释「点田会劫持网格点击」已被推翻：当时失败样本实为「点到当前角色自己的卡」+ 网格点落在
        //    无弹层态的左侧菜单上两个混杂变量，非弹层问题。）
        // 保留 Lv 特征检测仅作诊断日志。
        runCatching {
            val gw = ocr
            if (gw != null) {
                val f = freshFrame()
                val txt = try {
                    gw.readLines(f, listOf(FrameRect(60, 350, 900, 1050))).joinToString(" ")
                } finally { f.release() }
                Log.i(TAG, "rosterFind: 网格特征${if (txt.contains("Lv")) "存在" else "缺失(跳过点田，直接扫)"}")
            }
        }
        // ⚠️ 先回顶：弹层滚动位置跨 run 保留，排序靠前的角色在首页——不回顶则从尾页开始永远找不到（equip11 实证）。
        swipeGridToTop(gridKey, "rosterFind")
        for (page in 0 until MAX_ROSTER_PAGES) {
            if (vars.stopRequested || found) break
            for (row in 0 until traverseRows) for (col in 0 until cols) {
                val idx = row * cols + col
                val c = profile.cellCenter(gridKey, idx)
                // 与 character_scan 一致用微滑 click（该路径实测有效；零位移 tap 反而未验证有效）
                actions.click(c.x, c.y)
                delay(CLICK_SETTLE_MS)
                parseCharacterPanel(step, null)
                var name = charName
                if (name.isEmpty()) {
                    // 详情切换动画未完 → 补读一次（probe 实证 1.1s 稳定）
                    delay(600)
                    parseCharacterPanel(step, null)
                    name = charName
                }
                if (firstSeen == null && name.isNotEmpty()) firstSeen = name
                if (name.isNotEmpty() && nameMatches(name, target)) { found = true; break }
            }
            if (found) break
            if (advance != null && advance.optString("type") == "swipe") {
                val from = advance.getJSONArray("from"); val to = advance.getJSONArray("to")
                val fx = profile.scale(from.getInt(0), profile.scaleX); val fy = profile.scale(from.getInt(1), profile.scaleY)
                val tx = profile.scale(to.getInt(0), profile.scaleX); val ty = profile.scale(to.getInt(1), profile.scaleY)
                val before = try { freshFrame() } catch (_: Exception) { null }
                val beforeThumb = before?.let { try { VoteJudges.gridThumb(it, profile, gridKey) } finally { it.release() } }
                actions.swipe(fx, fy, tx, ty)
                val after = awaitGridStable(profile, gridKey)
                val afterThumb = try { VoteJudges.gridThumb(after, profile, gridKey) } finally { after.release() }
                val looped = firstSeen != null && charName == firstSeen
                Log.i(
                    TAG,
                    "rosterFind: 翻页 page=$page diff=" +
                        (VoteJudges.thumbChangedFraction(beforeThumb, afterThumb)?.let { "%.3f".format(it) } ?: "null") +
                        " looped=$looped",
                )
                if (reachedEnd(beforeThumb, afterThumb) || looped) break
            } else break
        }
        if (!found) {
            Log.w(TAG, "rosterFind: 目标 '$target' 未在名册找到 → 跳过本 task 后续步骤（防止 clicks 落在错误界面连锁误点，equip8 实证）")
            return
        }
    }

    private fun nameMatches(got: String, target: String): Boolean {
        if (StatParser.clean(got) == StatParser.clean(target)) return true
        val tk = names?.match(target, GoodNames.Kind.CHARACTER, true)?.key ?: return false
        // got 常为「中文乱码 + 英文名 + 等级数字粘连」混合（app OCR 读中文艺术字弱，equip14/15 实证
        // '夥得大Sandrone' / 'Xilonen90790'）→ 剥尾数字 + 逐 token 词典反查，任一 token 命中即等
        val tokens = got.split(Regex("[\\s·・]+")) + got.trimEnd { it.isDigit() }
        for (tok in tokens) {
            val cleaned = StatParser.clean(tok).trimEnd { it.isDigit() }
            if (cleaned.isEmpty()) continue
            // GoodNames 表以中文名为键 → 面板读出的英文名（Xilonen）反查为 null；
            // 直接与 target 解出的 GOOD id 比（target '希诺宁' → tk 'Xilonen'）
            if (cleaned.equals(tk, ignoreCase = true)) return true
            val gk = names?.match(cleaned, GoodNames.Kind.CHARACTER, true)?.key
            if (gk != null && gk == tk) return true
        }
        return false
    }

    /**
     * §14 A3 **snap 遍历**（click-to-snap 零滑动，仅选择界面 `select_3col`）——依据
     * `dsl/docs/flow5-auto-equip.md` §遍历优化（真机实证：点部分遮挡底行卡 → 网格自动上弹，
     * 被点卡顶边对齐 y≈917）：
     * - 固定点击点循环，**每卡恰点一次，3 击/行**：`peek(284,1215)` → 上弹 → `(520,1048)` → `(756,1048)` → 下一轮 peek。
     * - 终止：末页 peek 消失 → 上弹不发生（网格指纹不变）→ **补点遮挡槽残余卡**（peekCols 其余列）→ 停止。
     * - 点击由本函数代劳，故 visit 内 `click` 步降级为「settle + 抓帧」（[runVisit] snapMode）。
     * - ⚠️ 上弹动画 ~300ms 需 settle；此处沿用 CLICK_SETTLE_MS(600ms) 保守值（过 settle 只费时不损正确性，
     *   精确值见 grids.*.advance.animMs，待真机标定后再细化）。
     */
    private suspend fun snapTraverse(
        gridKey: String,
        visit: JSONArray,
        advance: JSONObject,
        step: JSONObject,
    ) {
        val peekCols = advance.optJSONArray("peekCols")
        val loopClicks = advance.optJSONArray("loopClicks")
        if (peekCols == null || peekCols.length() == 0) {
            Log.w(TAG, "snap[$gridKey]: peekCols missing, skipped")
            return
        }
        fun point(arr: JSONArray, i: Int): IntArray {
            val a = arr.getJSONArray(i)
            return intArrayOf(a.getInt(0), a.getInt(1))
        }
        val peek = point(peekCols, 0)
        val loops = ArrayList<IntArray>()
        if (loopClicks != null) {
            for (i in 0 until loopClicks.length()) loops.add(point(loopClicks, i))
        }
        val clickAt = { p: IntArray ->
            actions.click(
                profile.scale(p[0], profile.scaleX),
                profile.scale(p[1], profile.scaleY),
            )
        }
        var lastThumb: ByteArray? = null
        var round = 0
        var visited = 0
        while (round < MAX_SNAP_ROUNDS) {
            if (vars.stopRequested) break
            // 每轮 = peek 1 击 + loop 两击（3 击/行，每卡恰点一次）
            clickAt(peek)
            runVisit(visit, gridKey, 0, 0, 0, profile, snapMode = true)
            visited++
            for (p in loops) {
                if (vars.stopRequested) break
                clickAt(p)
                runVisit(visit, gridKey, 0, 0, 0, profile, snapMode = true)
                visited++
            }
            if (vars.stopRequested) break
            // 终止判据：网格指纹变化 ≤ 阈值 → peek 消失（不再上弹）
            val frame = freshFrame()
            val thumb = try {
                VoteJudges.gridThumb(frame, profile, gridKey)
            } finally {
                frame.release()
            }
            if (reachedEnd(thumb, lastThumb)) {
                Log.i(TAG, "snap[$gridKey]: peek 消失（指纹不变）→ 补点遮挡槽残余卡 → 停止（已遍历 $visited）")
                // 补点：遮挡槽残余卡（peekCols 第 2 列起）
                for (i in 1 until peekCols.length()) {
                    if (vars.stopRequested) break
                    clickAt(point(peekCols, i))
                    runVisit(visit, gridKey, 0, 0, 0, profile, snapMode = true)
                    visited++
                }
                break
            }
            lastThumb = thumb
            round++
        }
        Log.i(TAG, "snap[$gridKey]: 遍历结束 rounds=$round visited=$visited")
    }

    /**
     * §14 P1 hardMatch 的引擎侧入口：从"最近一次解析产物"取候选，其余纯逻辑在 [TaskMatch.hardMatch]。
     * 逻辑抽离到 TaskMatch 以便离线单测（本类构造依赖 FrameSource/OcrGateway，不宜为测而构造）。
     */
    private fun hardMatch(task: JSONObject, tol: Double, why: StringBuilder? = null): Boolean {
        val a = results.lastOrNull() ?: run { why?.append("无已解析产物"); return false }
        return TaskMatch.hardMatch(task, a, tol, why)
    }

    /**
     * §14 P2 链路条目 → 点击中心。支持两型写法：
     * `sortBtn(828,1320)`（括号=点）与 `重置[311,1310,483,1363]`（方括号=矩形→取中心）。
     */
    private suspend fun clickChainEntry(entry: String) {
        // §12.4 P3：链项名（括号前的 token）若命中 CHAIN_ANCHOR_PATHS → 走 profile 机读坐标
        // （设计意图：flow 字面 (x,y) 仅为 3200 占位，跨分辨率须由 profile 提供；字面被忽略）。
        val name = entry.substringBefore("(").substringBefore("[").trim()
        val rectPath = CHAIN_ANCHOR_PATHS[name]
        if (rectPath != null) {
            runCatching {
                val r = profile.rect(rectPath)
                clickAt(r.centerX, r.centerY)
                Log.i(TAG, "clickChainEntry: $name → profile $rectPath center=(${r.centerX},${r.centerY})")
                return
            }.onFailure { Log.w(TAG, "clickChainEntry: profile $rectPath 解析失败，回退字面: ${it.message}") }
        }
        val paren = Regex("\\((\\d+),(\\d+)\\)").find(entry)
        if (paren != null) {
            clickAt(paren.groupValues[1].toInt(), paren.groupValues[2].toInt())
            return
        }
        val bracket = Regex("\\[(\\d+),(\\d+),(\\d+),(\\d+)\\]").find(entry)
        if (bracket != null) {
            val v = bracket.groupValues.drop(1).map { it.toInt() }
            clickAt((v[0] + v[2]) / 2, (v[1] + v[3]) / 2)
            return
        }
        Log.w(TAG, "chain entry 无坐标，跳过：$entry")
    }

    // ================= §14 P3 角色扫描（流程三） =================
    /** 角色扫描产物（emit as=GoodCharacter 收集，ScriptRunner 导出）。 */
    val resultsCharacters = ArrayList<GoodCharacter>()

    /** 单角色草稿：parsePanel(char_profile) → navigate(命座/天赋) → emit。 */
    // ── 耗时归因（调试标定用；见 TimingOverrides）──
    private var tmStartMs = 0L
    private var tmCells = 0
    private var tmPages = 0
    private var tmNavMs = 0L          // navigate 页切换等待累计
    private var tmPanelMs = 0L        // 点格后面板名轮询累计
    private var tmSettleMs = 0L       // 翻页 settle 轮询累计
    /** 签名轮询实际跨帧样本数（用于 `scan finished` 归因；0 = 走了旧路径）。 */
    private var tmSigSamples = 0
    /** 就绪内容带只打印一次（便于现场确认用的是哪块 ROI）。 */
    private var sigBandLogged = false

    /** "名字 ROI 是否可读"的流程级门：本流程首格求值一次后缓存（`siggate=0` 则逐格求值）。 */
    private var sigGateCached: Boolean? = null

    // ── 就绪信号直采（2026-09-12）：实例级 scratch，避免每格分配 ──
    // 按**最大块网格**分配（`sigblocks` 可覆盖到 24×12），实际用多少由 TimingOverrides 决定。
    private val sigBefore = ByteArray(SIG_LEN_MAX)
    private val sigA = ByteArray(SIG_LEN_MAX)
    private val sigB = ByteArray(SIG_LEN_MAX)

    private var charName = ""
    /**
     * 最近一次**成功入库**的角色名。⚠️ `charName` 在 `emitCharacter` 末尾会被清空（重置草稿），
     * 而 flow 顺序是 `emit` → `stopWhen` ⇒ 止扫判据若读 `charName` 会**恒为空、永不触发**
     * （2026-09-11 实测：`name == roster[0]` 从未生效，10 角色那次实为 `reachedEnd` 收尾）。
     * 本字段不清空，专门供止扫判据求值。
     */
    private var lastCharName = ""
    /** 连续「已入库过的角色」个数（回卷/遍历完判据）；由 stopWhen mode=duplicateStreak 启用。 */
    private var charDupStreak = 0
    private var charDupStreakLimit = 0

    /**
     * **回卷止扫的有效阈值**（`pagedGrid` 内 = 该网格**一页的卡片数** `cols × traverseRows`）。
     *
     * 为什么不用 flow 里写死的 streak（用户定稿 2026-09-12）：一页卡片数**随流程/网格不同**
     * （圣遗物 7×3=21、武器/角色各自不同），而"回卷"的本质是**整页格子全是已入库的件**。
     * 阈值写死 3 太敏感：翻页相位抖动（≤cardHalf≈0.43 行≈3 格）会让页首与上一页重叠 ⇒ 凑出 3 个
     * 连续重复件 ⇒ 误判回卷（实测只扫 100/932 件）；写死 8 也只到 205 件。
     * `0` = 未设置 ⇒ 回退到 flow 的 `charDupStreakLimit`（snap / rosterFind 等非翻页路径仍用它）。
     */
    private var dupLimitEffective = 0

    /**
     * 翻页滑动**落地增益**的跨页 EMA（实测落地 px ÷ 指令 px）。
     *
     * 用途：闭环首滑按 `目标 / 增益` 预补偿 —— 实测单次手势的落地只有指令的 ~0.7 倍（且每页波动），
     * 开环时每页都要靠补滑纠偏；记住增益后第 2 页起通常**一次滑到位**。
     * 初值 0.7 是 2244/BS 上的实测典型值；首轮会自我修正。
     */
    private var advGainEma = 0.7

    /**
     * 连续「整页零新增」的页数；达到 [dupPageConfirm] 才断言列表回卷。
     *
     * ⚠️ 与 `charDupStreak`（连续重复件计数，**每页开头清零**）配套：那个是**页内**判据，这个是**页级确认**。
     * 为什么需要页级确认（2026-09-12 实测）：实测翻页一页只前进 ~1.9 行（应 3 行），于是上一页尾部的重复
     * 会与本页头部的重复**跨页累计**（实测 page6 尾 8 + page7 头 13 = 21 = 一页卡片数）⇒ 全量扫描在第 8 页
     * 就误判回卷停住（导出 111 件 / 应 933）。开页清零解决跨页累计；本确认再挡「单次滑空」。
     */
    private var dupPageStreak = 0

    /** 断言回卷所需的连续整页零新增页数（flow `stopWhen.dupPageConfirm`，默认 2；=1 恢复旧的立即停行为）。 */
    private var dupPageConfirm = 2

    /** 本页是否已做过回卷判定（同一页 item 级 + 格级两处调用只记账一次）。每页开头复位。 */
    private var dupPageDecided = false

    /** 本页判定的结果（配合 [dupPageDecided] 在同一页内复用，避免重复计数）。 */
    private var dupPageStopConfirmed = false

    /**
     * 连续重复达到「一页卡片数」时的**统一处置**（item 级 `noteDupAndMaybeStop` 与格级检查共用同一判据）。
     *
     * 首次命中多为**滑空 / 半页重叠** ⇒ 不算回卷：清计数继续翻页；
     * 只有**连续 [dupPageConfirm] 个整页**都零新增，才断言列表回卷并停止。
     * （真回卷里「后续每一页全是已入库件」⇒ 必然连续满足；误判代价不对称：错停=整轮报废，多扫一页=~6s。）
     *
     * @return `true` = 确认回卷，调用方应停止；`false` = 疑似滑空，调用方应清计数并继续。
     */
    private fun dupRollbackConfirmed(): Boolean {
        // 同一页内会被调用两次（item 级 noteDupAndMaybeStop + 格级检查）⇒ 只对第一记账。
        if (dupPageDecided) return dupPageStopConfirmed
        dupPageDecided = true
        dupPageStreak++
        dupPageStopConfirmed = dupPageStreak >= dupPageConfirm
        if (dupPageStopConfirmed) {
            Log.i(TAG, "回卷判据：连续 $dupPageStreak 个整页零新增 ≥ $dupPageConfirm ⇒ 断言列表回卷，停止扫描")
        } else {
            Log.i(
                TAG,
                "回卷判据：整页重复第 $dupPageStreak 次（< $dupPageConfirm）⇒ 疑似滑空/半页重叠，清计数继续翻页",
            )
        }
        return dupPageStopConfirmed
    }
    private var charKey: String? = null
    private var charLevel = 0
    private var charElement: String? = null
    private var charConstellation = 0
    private val charTalents = MutableList(3) { 0 }

    /** 角色概览面板：name/level/header(元素) —— panels.char_profile。 */
    private suspend fun parseCharacterPanel(step: JSONObject, ctx: CellFrameContext?) {
        val gateway = ocr
        if (gateway == null) {
            Log.w(TAG, "parseCharacterPanel: OCR 未接入，跳过")
            return
        }
        val frame = ctx?.acquire { freshFrame() } ?: freshFrame()
        val owned = ctx?.frame != null
        try {
            val rects = listOf(
                profile.rect("panels.char_profile.name"),
                profile.rect("panels.char_profile.level"),
                profile.rect("panels.char_profile.header"),
            )
            val texts = gateway.readRois(frame, rects)
            // 2026-09-10 诊断日志：name/level 在 app 内读空（离线 paddleocr 同源 ROI 能读）→ 打印原始输出与实际 ROI
            Log.d(
                TAG,
                "char.raw: name='${texts.getOrElse(0) { "" }}' lv='${texts.getOrElse(1) { "" }}' " +
                    "hdr='${texts.getOrElse(2) { "" }}' | rects=${rects.joinToString { "(${it.left},${it.top},${it.right},${it.bottom})" }}",
            )
            val rawName = StatParser.clean(texts.getOrElse(0) { "" })
            val level = Regex("(\\d+)").find(texts.getOrElse(1) { "" })
                ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val header = StatParser.clean(texts.getOrElse(2) { "" })
            // header 形如「冰元素／桑多涅」→ 取「元素」前缀。
            // ⚠️ OCR 偶把装饰符号一起读进来（实测 '“火元素／…'、'.岩元素／…'）→ 元素值变成 '“火'/'.岩'
            //    ⇒ 先净化成「汉字 + 斜杠」再匹配。
            val headerCjk = Regex("[\\u4e00-\\u9fa5/／]+").findAll(header).map { it.value }.joinToString("")
            val element = Regex("^(.+?)元素").find(headerCjk)?.groupValues?.getOrNull(1)
            // 词典/模糊开关取自 flow 的 dict.name / dict.fuzzy（未声明时回落默认，不再硬编码）
            val nameDict = dictKeyOf(step, "name") ?: DEFAULT_CHAR_DICT
            val key = lookupName(nameDict, rawName, dictFuzzy(step))
            charName = key ?: rawName
            charKey = key
            charLevel = level
            charElement = element
            vars.level = level
            Log.i(TAG, "char: name=${key ?: rawName} lv=$level element=$element")
        } catch (e: Exception) {
            Log.w(TAG, "parseCharacterPanel failed: ${e.message}")
        } finally {
            if (!owned) frame.release()
        }
    }

    /**
     * 命之座：6 节点「白锁紧凑块」判据（profiles：RGB>195 且占比 >0.4 → 已点亮）。
     * 步长 3px 抽样，避免逐像素遍历大 ROI。
     */
    private suspend fun readConstellation() {
        val obj = profile.rawObject("panels.char_constellation") ?: return
        val nodes = obj.optJSONArray("nodes") ?: return
        val roi = obj.optInt("roi", 55)
        val frame = try {
            freshFrame()
        } catch (_: Exception) {
            return
        }
        var lit = 0
        try {
            for (i in 0 until nodes.length()) {
                val a = nodes.getJSONArray(i)
                val cx = profile.scale(a.getInt(0), profile.scaleX)
                val cy = profile.scale(a.getInt(1), profile.scaleY)
                val half = Math.max(1, profile.scale(roi, profile.scaleY) / 2)
                var bright = 0
                var total = 0
                var y = cy - half
                while (y <= cy + half) {
                    var x = cx - half
                    while (x <= cx + half) {
                        val px = frame.get(y, x)
                        if (px[0] > 195 && px[1] > 195 && px[2] > 195) bright++
                        total++
                        x += 3
                    }
                    y += 3
                }
                if (total > 0 && bright.toDouble() / total > 0.4) lit++
            }
        } catch (_: Exception) {
        } finally {
            frame.release()
        }
        charConstellation = lit
        Log.i(TAG, "char: 命座点亮 $lit/${nodes.length()}")
    }

    /** 天赋：3 个战斗天赋等级 OCR（panels.char_talent.lvRois）。 */
    private suspend fun readTalent() {
        val gateway = ocr ?: return
        val lv = profile.rawObject("panels.char_talent")?.optJSONArray("lvRois") ?: return
        val rects = ArrayList<FrameRect>()
        for (i in 0 until lv.length()) {
            val a = lv.getJSONArray(i)
            rects.add(FrameRect(a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3)))
        }
        val frame = try {
            freshFrame()
        } catch (_: Exception) {
            return
        }
        val texts = try {
            gateway.readRois(frame, rects)
        } finally {
            frame.release()
        }
        for (i in 0 until Math.min(3, texts.size)) {
            charTalents[i] = Regex("(\\d+)").find(StatParser.clean(texts[i]))
                ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        }
        Log.i(TAG, "char: 天赋等级 ${charTalents.toList()}")
    }

    /** §15 P1-1：面板名区亮度指纹（步长 6 采样，仅用于检测面板内容变化）。 */
    private fun panelAreaFp(frame: Mat, rect: FrameRect): Long {
        var fp = 0L
        var y = rect.top
        while (y < rect.bottom && y < frame.rows()) {
            var x = rect.left
            while (x < rect.right && x < frame.cols()) {
                val p = frame.get(y, x)
                fp = fp * 31 + (((p[0] + p[1] + p[2]) / 3).toInt() / 8)
                x += 6
            }
            y += 6
        }
        return fp
    }

    /** navigate 后按 `read` 数组判读页面（char_constellation / char_talent）。 */
    private suspend fun readAfterNavigate(step: JSONObject) {
        val read = step.optJSONArray("read") ?: return
        for (i in 0 until read.length()) {
            val page = read.optJSONObject(i)?.optString("page", "") ?: continue
            when {
                page.contains("constellation") -> readConstellation()
                page.contains("talent") -> readTalent()
            }
        }
    }

    /**
     * 连续重复**件**判据（通用：角色/武器/圣遗物；2026-09-12 由角色专用推广）。
     *
     * ⚠️ 身份键必须与各自 dedupe 的键**完全一致**，否则会误判：
     * - 角色：词典 key（角色唯一）
     * - 武器：`key|L{level}|R{refine}`（与武器 dedupe 同键；同件名不同等级是两件）
     * - 圣遗物：`contentKey`（件名+等级+词条；**同件名的不同圣遗物是不同件**，只用件名会秒判重复）
     *
     * 「重复」= 该身份键**已在本轮入库过**（列表回卷/滑动失效时的特征）。
     * `identity == null` ⇒ 不介入（既不计也不重置）。返回 true = 应止扫。
     */
    private fun noteDupAndMaybeStop(identity: String?, seen: Collection<String>): Boolean {
        val limit = if (dupLimitEffective > 0) dupLimitEffective else charDupStreakLimit
        val (next, hit) = CharDupJudge.step(seen, identity, charDupStreak, limit)
        charDupStreak = next
        vars.charDupStreak = next
        if (hit) {
            // ⚠️ 仅 `pagedGrid` 内（`dupLimitEffective > 0`）才有「页」的概念 ⇒ 走**页级确认**；
            //    角色 rosterFind / snap 等非翻页路径沿用旧的「连续 N 个重复即停」（逐位一致，勿动）。
            if (dupLimitEffective <= 0 || dupRollbackConfirmed()) {
                vars.stopRequested = true
                vars.stopReason = "stopWhen"
                Log.i(TAG, "stopWhen triggered (连续 $next 个重复件 ≥ $limit)：$identity")
                return true
            }
            // 首次整页重复 ⇒ 疑似滑空/半页重叠：清计数、继续翻页（不停止；本件仍按调用方的去重逻辑处置）
            charDupStreak = 0
            vars.charDupStreak = 0
            return false
        }
        return false
    }

    /** emit as=GoodCharacter：草稿入库 + 上报进度。 */
    private fun emitCharacter(step: JSONObject) {
        if (charName.isEmpty()) {
            Log.w(TAG, "emit GoodCharacter: 草稿为空（char_profile 未解析），跳过")
            return
        }
        val c = GoodCharacter(
            key = charKey,
            name = charName,
            level = charLevel,
            element = charElement,
            constellation = charConstellation,
            talents = charTalents.toList(),
        )
        // ── 连续重复角色判据（2026-09-11 用户定：连续 3 个重复 = 遍历完）──
        // 「重复」= 该名字**已在本轮入库过**。列表回卷（Genshin 头像条可循环滚）或滑动失效时，
        // 会连续读到已扫过的角色；`reachedEnd`（指纹到底）对回卷列表永不触发，故此为**主判据**。
        // ⚠️ 按**词典 key**判重（角色身份），不按名字：名字可能读错/两名相撞 → 按名字会误杀丢件。
        // key == null（未解析出身份）→ 判据不介入，且**照常入库**（宁可多一件也不丢）。
        val seenKeys = resultsCharacters.mapNotNull { it.key }
        val dup = c.key != null && seenKeys.contains(c.key)
        lastCharName = c.name
        noteDupAndMaybeStop(c.key, seenKeys)
        if (dup) {
            // ⚠️ 不入库：角色侧无 dedupe（`dedupe` 只作用于圣遗物），重复件入库会污染导出。
            Log.i(TAG, "char 重复（连续 $charDupStreak/${charDupStreakLimit}）：${c.name} key=${c.key} → 不入库")
            resetCharDraft()
            return
        }
        resultsCharacters.add(c)
        Log.i(TAG, "char emit #${resultsCharacters.size}: ${c.name} lv=${c.level} c${c.constellation} t=${c.talents}")
        listener.onProgress(
            "character",
            vars.snapshot() + ("name" to c.name) + ("idx" to resultsCharacters.size),
        )
        resetCharDraft()
    }

    /** 重置角色草稿（不清 `lastCharName`：它供止扫判据使用）。 */
    private fun resetCharDraft() {
        charName = ""
        charKey = null
        charLevel = 0
        charElement = null
        charConstellation = 0
        for (i in charTalents.indices) charTalents[i] = 0
    }

    /** §14 A4：计划中目标的最高等级（pageSkip 判据）。计划为空或无 level 字段返回 null。 */
    private fun maxTargetLevel(): Int? {
        val plan = vars.plan ?: return null
        var max: Int? = null
        for (t in plan) {
            val lv = t.optInt("level", -1)
            if (lv >= 0 && (max == null || lv > max!!)) max = lv
        }
        return max
    }

    /**
     * 两帧是否"实质相同"（差异比例 ≤ [thr]），用于翻页到底/回顶判据。
     * [thr] 默认 [GRID_SIMILAR_DIFF]（settle 用的紧阈值）；**回顶用 [GRID_END_DIFF] 松阈值**——
     * 误判"已到顶"只多滑一次（便宜），漏判则白滑满 8 次（贵），代价不对称故放松。
     */
    private fun reachedEnd(a: ByteArray?, b: ByteArray?, thr: Float = GRID_SIMILAR_DIFF): Boolean {
        val d = VoteJudges.thumbChangedFraction(a, b) ?: return false
        return d <= thr
    }

    /**
     * 翻页后自适应 settle：轮询网格缩略图，连续 [SETTLE_STABLE_MS] 帧间差异 ≤ [GRID_SIMILAR_DIFF]
     * 即视为动画结束。替代固定延时——不同设备动画时长不同，固定值要么浪费要么不足。
     *
     * 原实现用「指纹精确相等」判稳：真机 MediaProjection 逐帧带抖动/微动画，24×16 缩略图
     * 几乎不可能连续 300ms 逐像素一致 → 永不收敛、每次打满 [SETTLE_MAX_MS]（真机归位 48s+）。
     * 现改「帧间差异比例阈值」：容忍抖动，仍可靠检测翻页大变化。
     * @return 稳定后的最新帧（调用方负责 release）
     */
    private suspend fun awaitGridStable(profile: ScreenProfile, gridKey: String): Mat {
        val start = clock()
        var prevThumb: ByteArray? = null
        var stableSince = -1L
        var latest: Mat? = null
        try {
            val sPoll = TimingOverrides.settlePollMs
            val sStable = TimingOverrides.settleStableMs
            while (clock() - start < SETTLE_MAX_MS) {
                delay(sPoll); tmSettleMs += sPoll
                latest?.release()
                latest = freshFrame(SETTLE_FRAME_TIMEOUT_MS)
                val thumb = VoteJudges.gridThumb(latest, profile, gridKey)
                if (thumb != null) {
                    val diff = VoteJudges.thumbChangedFraction(prevThumb, thumb)
                    if (diff != null && diff <= GRID_SIMILAR_DIFF) {
                        if (stableSince < 0) stableSince = clock()
                        if (clock() - stableSince >= sStable) {
                            Log.i(TAG, "page settle: ${clock() - start}ms (stable diff=${"%.3f".format(diff)})")
                            return latest
                        }
                    } else {
                        stableSince = -1L
                    }
                    prevThumb = thumb
                }
            }
        } catch (e: Exception) {
            latest?.release()
            throw e
        }
        Log.w(TAG, "settle not stabilized within ${SETTLE_MAX_MS}ms, using latest frame")
        return latest ?: freshFrame(SETTLE_FRAME_TIMEOUT_MS)
    }

    private suspend fun dialog(step: JSONObject) {
        if (ocr == null) return
        val ref = step.getString("ref").removePrefix("$")
        val obj = profile.rawObject(ref) ?: return
        // 1. 检测弹窗：**优先用 `expect` 正判据**（弹窗独有措辞）。
        // ⚠️ 2026-09-13 修：原来「ROI 非空即视为弹窗」**会误判** —— 实测 51 张**无弹窗**截图里有 6 张该 ROI 非空
        //    （武器背包读到 `Lv.90 Lv.90 Lv.20`、套装筛选弹窗读到 `纺月的夜歌 70`、野外读到 `LV`）
        //    ⇒ 会在**没有弹窗**时**盲点确认钮**（换装确认的 confirmClick）⇒ 误触。
        //    真弹窗文案实测：`…已被<角色>装备，是否更换？`（登记于 profile `dialogs.equipConfirm.expect`）。
        //    未配 `expect`（step 与 profile 都缺）时退回旧的「非空」判据并告警（兼容既有流程）。
        val ocrGateway = ocr
        val textRect = profile.rect("$ref.text")
        val expect = step.optString("expect").takeIf { it.isNotEmpty() }
            ?: obj.optString("expect").takeIf { it.isNotEmpty() }
        val frame = freshFrame()
        val text = try {
            StatParser.clean(ocrGateway.readLines(frame, listOf(textRect)).joinToString(" "))
        } finally {
            frame.release()
        }
        val shown = if (expect != null) {
            Regex(expect).containsMatchIn(text)
        } else {
            text.isNotBlank().also {
                if (it) Log.w(TAG, "dialog $ref: 未配 expect（step 与 profile 都缺）⇒ 按『非空即弹窗』判定，有误判风险")
            }
        }
        if (!shown) {
            Log.i(TAG, "dialog $ref 未出现（OCR='$text'）⇒ 不点击、直接继续（无人装备时走此分支）")
            return
        }
        Log.i(TAG, "dialog $ref 出现（OCR='$text' 命中 /${expect ?: "非空"}/）⇒ 点确认")
        // 2. 弹窗在 → 按 confirmClick 坐标点确认（profiles.dialogs.equipConfirm.confirmClick = [x,y]）
        obj.optJSONArray("confirmClick")?.let { c ->
            val pt = profile.scalePoint(c.getInt(0), c.getInt(1))
            actions.click(pt.x, pt.y)
            Log.i(TAG, "dialog $ref confirmed at (${pt.x},${pt.y})")
        }
    }

    /**
     * P3 setFilter：entry=BACKPACK/PILL 套装筛选——chain 各项文本含 (x,y) 正则点；selectByOcr 遍历 grid 行 OCR 套装名匹配目标（vars.currentTask.setName）→ 点 checkboxX（BACKPACK 点左列、PILL 两列都查）。
     * 弹窗结构：profiles.grids.set_filter_popup → rowYTop (8 行) + cols {left, right} {nameBox + checkboxX}。
     */
    /**
     * §12.4 筛选面板中心点（profiles.screens.dialogs.filterPanel.<key> = [x0,y0,x1,y1]）。
     * 用于「清空条件」(reset) 与「确认筛选」(ok) —— 二者 §12.4 原记为缺口，实已标定于 profiles。
     */
    private fun filterPanelCenter(key: String): IntArray? {
        val fp = profile.rawObject("screens.dialogs.filterPanel") ?: return null
        val r = fp.optJSONArray(key) ?: return null
        if (r.length() < 4) return null
        return intArrayOf((r.getInt(0) + r.getInt(2)) / 2, (r.getInt(1) + r.getInt(3)) / 2)
    }

    /** 点击 profile 基坐标点（缩放后）。 */
    private suspend fun clickAt(x: Int, y: Int, settleMs: Long = CLICK_SETTLE_MS) {
        val pt = profile.scalePoint(x, y)
        actions.click(pt.x, pt.y)
        delay(settleMs)
    }

    /**
     * 面板锁钮自适应点击（fast.at=$zones.artifact.panel.lock 专用）。
     * 2560 实测：同一单件名存在普通（锁徽 center 2309,744）与祝圣（+zhushengShiftPx ≈808）两种面板，
     * 固定坐标必漏一种。策略：先双区判已锁（已锁则跳过点击）；未锁则先点普通位，回读双区 gold，
     * 未生效再点祝圣位。返回实际点击坐标（日志用）。
     */
    private suspend fun lockClickAdaptive(): Pair<Int, Int> {
        val obj = profile.zone("artifact.panel.lock")
            ?: throw IllegalStateException("zone artifact.panel.lock missing")
        val r = obj.getJSONArray("rect")
        val rect = profile.scaleRect(r.getInt(0), r.getInt(1), r.getInt(2), r.getInt(3))
        val sh = profile.zhushengShiftPx
        val lockedAny: (Mat) -> Boolean = { f ->
            VoteJudges.panelLock(f, profile, 0).matched || VoteJudges.panelLock(f, profile, sh).matched
        }
        var f = freshFrame()
        val pre = try { lockedAny(f) } finally { f.release() }
        if (pre) {
            Log.i(TAG, "lockClickAdaptive: 双区判定已锁，跳过点击")
            return rect.centerX to rect.centerY
        }
        actions.click(rect.centerX, rect.centerY)
        delay(CLICK_SETTLE_MS)
        f = freshFrame()
        val now = try { lockedAny(f) } finally { f.release() }
        if (now) return rect.centerX to rect.centerY
        actions.click(rect.centerX, rect.centerY + sh)
        delay(CLICK_SETTLE_MS)
        Log.i(TAG, "lockClickAdaptive: 普通位未生效 → 祝圣位 +$sh")
        return rect.centerX to (rect.centerY + sh)
    }

    /**
     * checkbox 是否已勾选（"未选中黑 / 选中白"）。取该点亮度均值，>150 视为已选（防误取消）。
     * 读帧失败按「未选」处理（宁可多点一次也不会漏点）。
     */
    private suspend fun checkboxChecked(x: Int, y: Int): Boolean {
        val sx = profile.scale(x, profile.scaleX)
        val sy = profile.scale(y, profile.scaleY)
        val frame = try {
            freshFrame()
        } catch (e: Exception) {
            return false
        }
        return try {
            val px = frame.get(sy, sx)
            val bright = (px[0] + px[1] + px[2]) / 3.0
            bright > 150.0
        } catch (_: Exception) {
            false
        } finally {
            frame.release()
        }
    }

    /**
     * §14 P1：筛选目标集合。取并集逻辑在 [TaskMatch.targets]（纯逻辑，可离线单测）。
     */
    private fun filterTargets(): Set<String> = TaskMatch.targets(vars.currentTask, vars.plan)

    private suspend fun setFilter(step: JSONObject) {
        val ocrGateway = ocr
        val chain = step.getJSONArray("chain")
        // §16.4 链项解析统一走 clickChainEntry（命中 CHAIN_ANCHOR_PATHS → profile 机读坐标，
        // 跨分辨率正确；字面仅 fallback）。旧实现直接 scalePoint 字面 → 2560 错位。
        for (i in 0 until chain.length()) clickChainEntry(chain.getString(i))
        // §12.4-① 开始筛选前先清空已选条件（filterPanel.reset）。
        // 2560 实测：setPlus 打开子面板有动画，紧接的 reset 点击落在过渡态被吞 → 游戏残留勾选
        // （天之美赐）清不掉 → 筛的是错套装。补 delay 待动画完成；reset 幂等，双击保险。
        val resetPt = filterPanelCenter("reset")
        if (resetPt != null) {
            delay(800)
            Log.i(TAG, "setFilter: 清空已选条件 reset=(${resetPt[0]},${resetPt[1]})")
            clickAt(resetPt[0], resetPt[1])
            clickAt(resetPt[0], resetPt[1])
        } else {
            Log.w(TAG, "setFilter: filterPanel.reset 未标定，跳过清空（可能残留上次条件）")
        }
        val sel = step.optJSONObject("selectByOcr")
        if (sel == null) {
            // 无 OCR 选择段（如 PILL 直达）→ 直接确认关闭
            confirmFilter(step)
            return
        }
        val gridKey = sel.optString("grid", "set_filter_popup")
        val dictKey = sel.optString("dict", "mappings.artifactSets")
        if (GoodNames.kindOf(dictKey) == null || names == null) {
            Log.w(TAG, "setFilter: dictionary '$dictKey' not loaded")
            return
        }
        val lookup: (String) -> String? = { text -> lookupName(dictKey, text) }
        val match = sel.optString("match", "exact")
        // §12.4-② 多选：目标集（currentTask.setName / targets[] / plan[].setName）
        val targets = filterTargets()
        if (targets.isEmpty()) {
            Log.w(TAG, "setFilter: 无筛选目标（P4 未注入 setName/targets），仅关闭面板")
            confirmFilter(step)
            return
        }
        val grid = profile.rawObject("grids.$gridKey") ?: return
        val cols = grid.getJSONObject("cols")
        val rowYTop = grid.getJSONArray("rowYTop")
        val leftX = cols.getJSONObject("left").getInt("checkboxX")
        val rightX = cols.getJSONObject("right").getInt("checkboxX")
        val leftBox = cols.getJSONObject("left").getJSONArray("nameBox")
        val rightBox = cols.getJSONObject("right").getJSONArray("nameBox")
        if (ocrGateway == null) return
        val rowHeight = grid.optInt("rowHeight", 120)
        // §12.4-② 目标名空间归一：plan 注入的是中文显示名，matchFilterRow 比对的是词典 key
        // （如 绝缘之旗印→EmblemOfSeveredFate），必须先经词典 lookup 归一，否则恒 miss。
        val pending = LinkedHashSet<String>()
        targets.forEach { t -> pending.add(lookup(t) ?: t) }
        // 契约可观测性：把「plan 报的原始名 → 词典归一 key」打出来，便于确认 P4 注入是否生效
        // （归一失败会回落到原文 → 与 OCR 侧 key 空间不一致 → 恒 miss，此日志一眼可辨）。
        Log.i(
            TAG,
            "setFilter: 目标 $targets → 归一 ${pending.toList()}（dict=$dictKey match=$match 面板=$gridKey）",
        )
        val advance = grid.optJSONObject("advance")
        // §12.4-⓪ 打开弹窗先回顶：弹窗滚动位置跨会话保留，上次停在末页则首页即末页。
        swipeGridToTop(gridKey, "setFilter")
        var lastThumb: ByteArray? = null
        var guard = 0
        var rescanUsed = false
        while (pending.isNotEmpty() && guard++ < MAX_FILTER_PAGES) {
            if (vars.stopRequested) break
            var hits = 0
            for (i in 0 until rowYTop.length()) {
                if (pending.isEmpty()) break
                val y = rowYTop.getInt(i)
                val rowCenterY = y + rowHeight / 2
                if (matchFilterRow(ocrGateway, lookup, leftBox, y, rowHeight, leftX, rowCenterY, pending, "left")) hits++
                if (pending.isEmpty()) break
                if (matchFilterRow(ocrGateway, lookup, rightBox, y, rowHeight, rightX, rowCenterY, pending, "right")) hits++
            }
            if (pending.isEmpty() || vars.stopRequested) break
            // §15 P1-2：本页零命中多为翻页残差致行 OCR 劣化 → 重扫本页一次再翻页
            if (hits == 0 && !rescanUsed) {
                rescanUsed = true
                Log.i(TAG, "setFilter: 本页零命中，重扫一次")
                continue
            }
            rescanUsed = false
            // §12.4-⑤ 本页未点完 → 翻页继续
            if (advance == null) break
            val advFrom = advance.getJSONArray("from")
            val advTo = advance.getJSONArray("to")
            val thumbBefore = gridThumbOf(gridKey)
            actions.swipe(
                profile.scale(advFrom.getInt(0), profile.scaleX),
                profile.scale(advFrom.getInt(1), profile.scaleY),
                profile.scale(advTo.getInt(0), profile.scaleX),
                profile.scale(advTo.getInt(1), profile.scaleY),
            )
            val stable = awaitGridStable(profile, gridKey)
            val thumbAfter = try {
                VoteJudges.gridThumb(stable, profile, gridKey)
            } finally {
                stable.release()
            }
            if (reachedEnd(thumbAfter, lastThumb) || reachedEnd(thumbAfter, thumbBefore)) {
                Log.i(TAG, "setFilter: 筛选列表到底（指纹不变），停止翻页；未点完=$pending")
                break
            }
            lastThumb = thumbAfter
        }
        if (pending.isNotEmpty()) Log.w(TAG, "setFilter: 目标未全部点选，剩余=$pending")
        confirmFilter(step)
    }

    /** 取当前帧网格缩略图（失败返回 null），用于到底判据（差异比例阈值）。 */
    private suspend fun gridThumbOf(gridKey: String): ByteArray? {
        val frame = try {
            freshFrame()
        } catch (_: Exception) {
            return null
        }
        return try {
            VoteJudges.gridThumb(frame, profile, gridKey)
        } finally {
            frame.release()
        }
    }

    /**
     * 筛选列表单行匹配：OCR 名称 → 词典反查 → 命中且 checkbox 未勾选则点选（§12.4-③ 防误取消）。
     */
    private suspend fun matchFilterRow(
        gateway: OcrGateway,
        lookup: (String) -> String?,
        box: JSONArray,
        y: Int,
        rowHeight: Int,
        checkboxX: Int,
        rowCenterY: Int,
        pending: MutableSet<String>,
        side: String,
    ): Boolean {
        // nameBox 两种写法：[x0,x1]（canonical 2 元 x 区间）或 [x0,y0,x1,y1]（4 元 rect，y 取 index 2）
        val rect = if (box.length() >= 4) FrameRect(box.getInt(0), y, box.getInt(2), y + rowHeight)
        else FrameRect(box.getInt(0), y, box.getInt(1), y + rowHeight)
        // 行 OCR 失败重试一次：翻页残差使文字在 ROI 内错位时首读常烂，缓 250ms 后重取帧显著提升命中
        var key: String? = null
        var text: String
        for (attempt in 0 until 2) {
            val frame = freshFrame()
            text = try {
                gateway.readLines(frame, listOf(rect)).joinToString(" ")
            } finally {
                frame.release()
            }
            key = lookup(StatParser.clean(text))
            Log.d(TAG, "filterRow[$side] y=$y a$attempt text='$text' key=$key")
            if (key != null || text.isBlank()) break
            delay(250)
        }
        if (key == null || key !in pending) return false
        if (checkboxChecked(checkboxX, rowCenterY)) {
            Log.i(TAG, "setFilter: $key ($side) 已勾选，跳过（防误取消）")
            pending.remove(key)
            return true
        }
        clickAt(checkboxX, rowCenterY)
        pending.remove(key)
        Log.i(TAG, "setFilter matched: $key ($side)")
        return true
    }

    /**
     * §12.4-⑥ 确认筛选并关闭。两层结构（OCR 实测 2560）：
     *   ① 先点 filterPanel.ok（套装子面板「确认筛选」）→ 仅收起子面板，回到主面板「圣遗物筛选」，主面板仍开；
     *   ② 再点 filterPanel.confirm（主面板「确认」）→ 应用筛选并关闭主面板，回到背包网格。
     * 若 profile 未定义 confirm（旧分辨率/配置），回退 tailGuard 旧逻辑：锚点仍在则再点一次 ok。
     */
    private suspend fun confirmFilter(step: JSONObject) {
        val okPt = filterPanelCenter("ok")
        if (okPt == null) {
            Log.w(TAG, "setFilter: filterPanel.ok 未标定，无法确认")
            return
        }
        Log.i(TAG, "confirmFilter: ok=(${okPt[0]},${okPt[1]}) 点击（收起套装子面板）")
        clickAt(okPt[0], okPt[1])
        val confirmPt = filterPanelCenter("confirm")
        if (confirmPt != null) {
            delay(1200) // 等子面板收起动画完成，否则「确认」点击落在过渡态被吞
            Log.i(TAG, "confirmFilter: confirm=(${confirmPt[0]},${confirmPt[1]}) 点击（关闭主面板）")
            clickAt(confirmPt[0], confirmPt[1])
            return
        }
        // 兼容旧配置（无 confirm 键）：tailGuard 锚点仍在 → 再点一次 ok（双层关闭）
        if (step.optString("tailGuard").contains("ok")) {
            val anchorPt = filterPanelCenter("anchorTitle")
            if (anchorPt != null) {
                val gone = runCatching { actions.click(anchorPt[0], anchorPt[1]) }.getOrDefault(false)
                if (!gone) delay(CLICK_SETTLE_MS)
            }
            clickAt(okPt[0], okPt[1])
        }
    }

    /**
     * P3 foreach：遍历外部注入 plan（vars.plan），每项作为 vars.currentTask（"as" 字段，默认 "task"），执行内部 steps 数组（每步走 executeStep 顶层）。
     * 供 character_scan 的 "$plan" 遍历（auto_equip 的 "$plan" 同款，setFilter 用 vars.currentTask["setName"] 选套装）。
     */
    private suspend fun foreach(step: JSONObject) {
        val over = step.getString("over").removePrefix("$")
        val items: List<JSONObject>? = when (over) {
            "plan" -> vars.plan
            else -> null
        }
        if (items.isNullOrEmpty()) {
            Log.w(TAG, "foreach: '$over' not available or empty, skipped")
            return
        }
        val asName = step.optString("as", "task")
        val subSteps = step.getJSONArray("steps")
        for (item in items) {
            vars.currentTask = item
            Log.i(TAG, "foreach iter $asName: ${item.optString("char", item.optString("name", "?"))}")
            for (i in 0 until subSteps.length()) {
                executeStep(subSteps.getJSONObject(i))
                if (vars.stopRequested) return
            }
        }
    }

    /**
     * P3 navigate：左侧菜单切换（字符/角色界面）——leftMenu 每项名直接是 profiles.char_interface.leftMenu 的 key。
     * 每项点中心 + settle，顺序执行（如 ["命之座", "天赋"]）。
     */
    private suspend fun navigate(step: JSONObject) {
        val menu = step.getJSONArray("leftMenu")
        for (i in 0 until menu.length()) {
            val name = menu.getString(i)
            val rect = try {
                profile.rect("screens.char_interface.leftMenu.$name")
            } catch (e: Exception) {
                Log.w(TAG, "navigate: menu '$name' missing in profiles.char_interface.leftMenu (${e.message})")
                continue
            }
            actions.click(rect.centerX, rect.centerY)
            val navWait = TimingOverrides.navSettleMs
            // 页面切换动画较点击 settle 更长 → 先等页面稳定再读，否则读到上一页
            // ⚠️ 时延可被 TimingOverrides 覆盖（默认 = 原常量，逐位一致）
            //
            // ⛔ **自适应导航就绪：三轮实测后否决（2026-09-12）** —— 不要重做，除非先解决下面的矛盾。
            //   动机：`navigate` 占角色流程 55%（20 次 × 700ms = 14s）。
            //   做法（已实现又移除）：签名停稳 + 锚 OCR 非空，上限仍 700ms（保证"绝不更慢"）。
            //   实测（`dsl/scripts/roi_stability.py` 按 tab 分别测 + A/B）：
            //   · **静止态**确实有稳定锚：`char_profile.favor` 三 tab 全 100%；属性页 `level` 98%；
            //     天赋页 `lvRois` 并集 98%。且**选中的那个左菜单项永远最不稳**（属性页 18% /
            //     命之座页 10% / 天赋页 **0%**，自身流光动画）⇒ "用页签高亮做锚"也不成立。
            //   · **切换动画期间**这些锚持续运动：严格判据（0 块）与放宽到 2 / 4 / 8 块**全部打满 700ms**
            //     （`navigate` 21231 → 21536 / 21407 / 21462ms，**零收益**）。
            //   · 放宽到 **12 块**才开始判稳（navigate −6%），但**丢件**（导出 10 → **8** 个角色）。
            //   ⇒ **"动画期间必须严格才不丢件"与"严格就永不判稳"不可兼得**。
            //     注：`PERF-timing.md` 的"页面就绪 265~530ms"用**缩略图 2% 容差**，比逐块判据宽松得多；
            //     两者不矛盾 —— 动画在 ~500ms 后仍有亚 2% 的像素运动。
            //   ⇒ 保留固定等待。要重做必须先换更结构化的信号（如目标页做一次 OCR 判定，
            //     而不是像素判稳）。
            tmNavMs += navWait
            delay(navWait)
            // §15 P0-1：点一个菜单即读对应页（旧实现点完全部菜单再统一读 → 读命座时已停在天赋页，恒 0）
            when {
                name.contains("命之座") -> readConstellation()
                name.contains("天赋") -> readTalent()
            }
        }
    }

    /**
     * 名称反查统一入口：flow 的 `dict` 名 → 表 → [NameMatcher] 匹配（取代三套各自实现的模糊逻辑）。
     * @param allowFuzzy false 时只走归一化+精确（flow 的 `fuzzy: 0` 语义）
     */
    /**
     * §14 **flow dict 声明读取**：取 `step.dict.<field>`。
     * 未声明返回 null（各调用方回落自身默认），**不再在代码里写死 flow 已声明的词典名**。
     */
    private fun dictKeyOf(step: JSONObject, field: String): String? =
        step.optJSONObject("dict")?.optString(field)?.takeIf { it.isNotEmpty() }

    /** `dict.fuzzy`（1/true → 允许模糊匹配；缺省 true，与 [NameMatcher] 默认一致）。 */
    private fun dictFuzzy(step: JSONObject): Boolean = dictFuzzyOf(step.optJSONObject("dict"))

    /** [dictFuzzy] 的 dict 对象版（子解析函数只拿到 dict 时用）。 */
    private fun dictFuzzyOf(dict: JSONObject?): Boolean {
        val f = dict?.opt("fuzzy")
        return when (f) {
            is Int -> f != 0
            is Boolean -> f
            is String -> f != "0" && !f.equals("false", ignoreCase = true)
            else -> true
        }
    }

    /**
     * §14 `dict.subStats`：把 OCR 副词条数值吸附到标准档位表（rollTable）。
     * 非 "rollTable" 的词典名显式告警——静默忽略会让人误以为已生效。
     */
    private fun snapSubstats(
        list: List<StatParser.ParsedStat>,
        rarity: Int,
        dictKey: String,
    ): List<StatParser.ParsedStat> {
        if (dictKey != "rollTable") {
            Log.w(TAG, "subStats 词典 '$dictKey' 不支持档位吸附（仅支持 rollTable），保持原值")
            return list
        }
        return list.map { s ->
            val snapped = RollTable.snap(rarity, s.key, s.value)
            if (snapped != null && snapped != s.value) {
                Log.d(TAG, "rollTable 吸附: ${s.key} ${s.value} → $snapped")
                s.copy(value = snapped)
            } else {
                s
            }
        }
    }

    private fun lookupName(dictKey: String, text: String, allowFuzzy: Boolean = true): String? {
        val kind = GoodNames.kindOf(dictKey)
        if (kind == null) {
            Log.w(TAG, "lookupName: unknown dict '$dictKey'")
            return null
        }
        val table = names?.table(kind) ?: return null
        val result = NameMatcher.match(text, table, allowFuzzy) ?: return null
        Log.d(TAG, "name matched: '$text' → ${result.name} (${result.tier.label}) = ${result.key}")
        return result.key
    }

    /**
     * P3 ocrWithRetry：OCR 区域 → 词典模糊匹配 → vars.ocrMatch = key。
     * 重试 N 次（每次重新取帧），重试间 200ms（动画缓冲）。
     * fuzzy：0=仅精确；>0=走完整分级（子串/编辑距离/LCS/Dice）。
     */
    private suspend fun ocrWithRetry(step: JSONObject) {
        val ocrGateway = ocr ?: return
        val rect = profile.rect(step.getString("rect").removePrefix("$"))
        val fuzzy = step.optInt("fuzzy", 0)
        val maxRetries = step.optInt("retries", 3)
        val dictKey = step.optString("dict", "mappings.characters")
        for (attempt in 1..maxRetries) {
            val frame = freshFrame()
            val text = try {
                ocrGateway.readLines(frame, listOf(rect)).joinToString(" ")
            } finally { frame.release() }
            val cleaned = StatParser.clean(text)
            if (cleaned.isNotEmpty()) {
                val key = lookupName(dictKey, cleaned, allowFuzzy = fuzzy > 0)
                if (key != null) {
                    vars.ocrMatch = key
                    Log.i(TAG, "ocrWithRetry matched: $cleaned → $key (attempt $attempt)")
                    return
                }
            }
            if (attempt < maxRetries) delay(200)
        }
        Log.w(TAG, "ocrWithRetry failed after $maxRetries attempts: $rect")
    }

    /** P3 exit：via 返回按钮（文本 "return[2913,41]"，坐标机读走正则）→ 点击后终止流程。 */
    private suspend fun exitStep(step: JSONObject) {
        val via = step.optString("via", "")
        val m = Regex("\\[(\\d+),(\\d+)\\]").find(via)
        if (m != null) {
            val pt = profile.scalePoint(m.groupValues[1].toInt(), m.groupValues[2].toInt())
            actions.click(pt.x, pt.y)
            delay(CLICK_SETTLE_MS)
        }
        Log.i(TAG, "exit step reached")
        vars.stopRequested = true // 终止扫描（run() 与 pagedGrid 均检查）
        vars.stopReason = "exit" // flow 正常走完（≠ stopWhen 命中）
    }

    /**
     * P3 verify：zone 状态断言（顶层步骤，不在 visit 内——无 cell 上下文；支持 artifact.panel.* 系列）。
     * 判定不等于 expect 则 warn（D5 交给外层 fallback/retry 处理，verify 本身不阻断）。
     */
    private suspend fun verify(step: JSONObject) {
        val zone = step.getString("zone")
        val expectRaw = step.optString("expect", "true")
        // toggle($curLock)：期望与 vote as=curLock 快照取反（锁定翻转断言）
        val expect = if (expectRaw.startsWith("toggle(")) {
            val varName = expectRaw.removePrefix("toggle(").removeSuffix(")").removePrefix("$").trim()
            when (varName) {
                "curLock" -> !(vars.curLock ?: false)
                else -> {
                    Log.w(TAG, "verify toggle: unknown var '$varName'")
                    return
                }
            }
        } else expectRaw.toBoolean()
        val frame = freshFrame()
        val actual = try {
            when (zone) {
                "artifact.panel.astral" -> VoteJudges.panelAstral(frame, profile, 0).matched
                "artifact.panel.lock" -> VoteJudges.panelLock(frame, profile, 0).matched ||
                    VoteJudges.panelLock(frame, profile, profile.zhushengShiftPx).matched
                else -> {
                    Log.w(TAG, "verify: zone '$zone' not supported in top-level context")
                    return
                }
            }
        } finally { frame.release() }
        if (actual != expect) {
            Log.w(TAG, "verify FAILED: zone=$zone expect=$expect actual=$actual")
            RecognitionLog.log(
                logTag,
                RecognitionLog.Level.W,
                "verify 失败 $zone 期望=$expect 实际=$actual",
            )
        }
    }

    private suspend fun runVisit(
        visit: JSONArray,
        gridKey: String,
        col: Int,
        row: Int,
        index: Int,
        prof: ScreenProfile,
        /** §14 A3：snap 遍历——点击由外部固定循环完成，visit 内 click 步只做 settle + 抓帧。 */
        snapMode: Boolean = false,
    ) {
        val ctx = CellFrameContext()
        try {
            // ⚠️ 不在格子级响应 stopRequested：止扫语义是「本页后停」（pagedGrid 在页完成后检查）。
            for (i in 0 until visit.length()) {
                executeVisitStep(visit.getJSONObject(i), gridKey, col, row, index, ctx, prof, snapMode)
            }
        } finally {
            ctx.release()
        }
    }

    /**
     * 单格共享帧上下文：click 的面板切换轮询收敛帧 → 后续 panel vote/parsePanel 复用。
     * 每件从 ~12 次抓帧降到 2~4 次（轮询 1~3 + card vote 1）。
     */
    private class CellFrameContext {
        var frame: Mat? = null

        /** 已有共享帧则复用；否则抓新帧并持有（visit 结束统一 release）。 */
        suspend fun acquire(fresh: suspend () -> Mat): Mat = frame ?: fresh().also { frame = it }

        fun release() {
            frame?.release()
            frame = null
        }
    }

    /** §15 P2：ifMatch when 表达式变量表（Boolean→0/1，与 [Expr] 求值约定一致；null 视为 false/0）。 */
    private fun exprVars(): Map<String, Any?> = mapOf(
        "curLock" to (vars.curLock ?: false),
        "gridLocked" to (vars.gridLocked ?: false),
        "locked" to (vars.locked ?: false),
        "favorited" to (vars.favorited ?: false),
        "crafted" to vars.crafted,
        "rarity" to vars.rarity,
        "level" to vars.level,
    )

    private suspend fun executeVisitStep(
        step: JSONObject,
        gridKey: String,
        col: Int,
        row: Int,
        index: Int,
        ctx: CellFrameContext,
        prof: ScreenProfile,
        snapMode: Boolean = false,
    ) {
        // 只读探针：按步骤类型累计耗时（一次跑完即得完整分解，见 PerfProbe）。
        // 注意：函数名不带 Inner 的那个是"计时外壳"，真正的分发在 executeVisitStepInner。
        val vop = step.getString("do")
        val tStep = System.nanoTime()
        try {
            executeVisitStepInner(step, vop, gridKey, col, row, index, ctx, prof, snapMode)
        } finally {
            PerfProbe.addStep(vop, System.nanoTime() - tStep)
        }
    }

    private suspend fun executeVisitStepInner(
        step: JSONObject,
        vop: String,
        gridKey: String,
        col: Int,
        row: Int,
        index: Int,
        ctx: CellFrameContext,
        prof: ScreenProfile,
        snapMode: Boolean,
    ) {
        // §13：D 级逐格埋点（默认关闭——一页 21 格会刷屏，由 RecognitionLog.verbose 开启）
        RecognitionLog.log(logTag, RecognitionLog.Level.D, "格($col,$row) $vop")
        when (vop) {
                "ifMatch" -> {
                    // 计划匹配闸（artifact_lock）：P4 规则层注入 vars.currentTask；无计划则整段跳过
                    if (vars.currentTask == null) {
                        Log.i(TAG, "ifMatch: no plan injected (vars.currentTask null), then skipped")
                    } else {
                        // §15 P2：可选 when 布尔表达式（[Expr] 引擎，标识符查 exprVars）→ 不满足则跳过 then。
                        // 例：when:"curLock == false" 仅对已解锁件执行加锁，避免盲 toggle 误解锁已锁件（数据腐蚀）。
                        val whenExpr = step.optString("when", "")
                        val pass = if (whenExpr.isNotEmpty()) {
                            runCatching { Expr.eval(whenExpr, exprVars()) }.getOrElse { e ->
                                Log.w(TAG, "ifMatch when eval failed: '$whenExpr' ${e.message}")
                                false
                            }.also { if (!it) Log.d(TAG, "ifMatch when='$whenExpr' false, then skipped") }
                        } else true
                        if (pass) {
                            val thenSteps = step.getJSONArray("then")
                            for (i in 0 until thenSteps.length()) {
                                executeVisitStep(thenSteps.getJSONObject(i), gridKey, col, row, index, ctx, prof, snapMode)
                                if (vars.stopRequested) break
                            }
                        }
                    }
                }
                "vote" -> vote(step, gridKey, col, row, ctx, prof)
                "click" -> {
                    if (snapMode) {
                        // §14 A3：snap 遍历点击由 snapTraverse 的固定循环代劳（$cell.center 不适用，
                        // 上弹后网格几何已变），此处只等上弹动画 settle 并抓面板帧供 parsePanel 复用。
                        delay(CLICK_SETTLE_MS)
                        ctx.release()
                        ctx.frame = freshFrame()
                    } else {
                    // fast.at=$cell.center（缺省）/ 字面 [x,y] / profile 引用 $zones.*.*
                    // prof = 页面网格偏移视图（§12.5）：点击坐标随相位平移，panel 类坐标不受影响
                    val cellCenter = prof.cellCenter(gridKey, index)
                    val (cx, cy) = runCatching {
                        val fast = step.optJSONObject("fast")
                        when (val at = fast?.opt("at")) {
                            is String -> when {
                                at == "\$cell.center" || at.isEmpty() -> cellCenter.x to cellCenter.y
                                at == "\$zones.artifact.panel.lock" -> lockClickAdaptive()
                                at.startsWith("$") -> {
                                    val r = profile.rect(at.removePrefix("$"))
                                    r.centerX to r.centerY
                                }
                                else -> cellCenter.x to cellCenter.y
                            }
                            is JSONArray -> {
                                val p = profile.scalePoint(at.getInt(0), at.getInt(1))
                                p.x to p.y
                            }
                            else -> cellCenter.x to cellCenter.y
                        }
                    }.getOrElse { e ->
                        Log.w(TAG, "click fast.at 解析失败: ${e.message}，回退 cell center")
                        cellCenter.x to cellCenter.y
                    }
                    Log.i(TAG, "click cell($col,$row) fast.at -> ($cx,$cy)")
                    // §15 P1-1：点击前记录面板名区指纹，点击后轮询至变化（固定延时治不了
                    // 武器面板切换时长波动，恒滞后一格 → 内容指纹去重误杀）
                    // ⚠️ 2026-09-10 修：角色遍历网格 key 是 `char_strip`，但角色面板字段挂在该 key 下
                    // 并不存在（`panels.char_strip.*` 无），旧代码 → nameRect=null → 落到固定 `delay(PANEL_REFRESH_MS)`
                    // 兜底 → 抓帧时右面板「名字/等级」淡入未完成 → parsePanel 读到空（header 在左上瞬时变故正常）。
                    // 回退候选：角色线一律用 `panels.char_profile.name`。
                    val nameRect = listOf("panels.$gridKey.name", "panels.char_profile.name")
                        .firstNotNullOfOrNull { p -> runCatching { profile.rect(p) }.getOrNull() }
                    // ★ 2026-09-12 就绪信号直采：优先走"分块签名"轮询（零 Mat / 零 OCR）。
                    //   实测旧路径的 428ms/格 = delay(120×3) 360 + 轮询 OCR(20×3) 60 + 抓帧(3×3) 9
                    //   + 真点击 1ms ⇒ **99.8% 是"等就绪"**（见 IMAGE-PATH-COST.md §11.4）。
                    // §15 P1-1：点击前记录面板名文本（**同时充当"名字 ROI 是否可靠"的探针**）。
                    val gateCached = TimingOverrides.panelSigGateCached && sigGateCached == true
                    val beforeName = if (gateCached) {
                        null // 门已缓存 ⇒ 签名路径下这格不需要"点击前名字"
                    } else {
                        nameRect?.let { r -> ctx.frame?.let { f -> ocr?.readLines(f, listOf(r))?.firstOrNull() } }
                    }
                    // ⚠️ 只用「旧路径的轮询本就可用的流程」（beforeName 可读）启用签名：
                    //   `beforeName == null` ⇒ 该流程的 click 是 visit 首步（ctx.frame 空，如 character_scan），
                    //   旧代码此时走的是**固定 `delay(PANEL_REFRESH_MS)` 兜底**、名字 ROI 在弹窗态常读不到；
                    //   签名路径的"确认 OCR"会频繁读空并重试 ⇒ **比固定等待还慢**（实测角色 556→689ms/格）。
                    //   ⇒ 让这类流程原样走旧分支。
                    // ★ 就绪签名 ROI：默认取**靠后渲染的内容带**（等级/副属性/…），不是名字区。
                    //   `--es timing "sigband=0"` 可回退为名字区（A/B 与回滚用）。
                    val sigRoi = if (TimingOverrides.panelSigBand) {
                        panelReadyBand(gridKey) ?: nameRect
                    } else {
                        nameRect
                    }
                    // 块网格：未显式覆盖（`sigblocks`）时按面板取默认 —— 见 [sigBlocksFor] 的实测理由。
                    val sigBx = if (TimingOverrides.sigBlocksX > 0) TimingOverrides.sigBlocksX else sigBlocksFor(gridKey).first
                    val sigBy = if (TimingOverrides.sigBlocksY > 0) TimingOverrides.sigBlocksY else sigBlocksFor(gridKey).second
                    if (!sigBandLogged && sigRoi != null) {
                        sigBandLogged = true
                        Log.i(
                            TAG,
                            "就绪签名 ROI: band=${TimingOverrides.panelSigBand} " +
                                "rect=[${sigRoi.left},${sigRoi.top},${sigRoi.right},${sigRoi.bottom}] " +
                                "(${sigRoi.width}x${sigRoi.height}) samples=${TimingOverrides.panelSigSamples} " +
                                "confirm=${TimingOverrides.panelSigConfirm}",
                        )
                    }
                    // ★ `siggate=1`（默认）：把"名字 ROI 是否可读"这个**流程级**属性只求值一次并缓存，
                    //   签名路径下**不再逐格做这次点击前 OCR**（省 ~10~20ms/格）。
                    //   ⚠️ 不走签名路径时（或门为 false 时）仍逐格读，行为与改动前逐位一致。
                    if (TimingOverrides.panelSigGateCached && sigGateCached == null && nameRect != null) {
                        sigGateCached = ctx.frame?.let { f ->
                            ocr?.readLines(f, listOf(nameRect))?.firstOrNull()
                        } != null
                    }
                    val sigGateOk = TimingOverrides.panelSigGateCached && sigGateCached == true
                    val sigUsable = sigRoi != null && TimingOverrides.panelSigEnabled &&
                        (sigGateOk || beforeName != null)
                    val clickOk = actions.click(cx, cy)
                    Log.i(TAG, "visit cell($col,$row) idx=$index click=($cx,$cy) ok=$clickOk")
                    // ⚠️ 收敛帧必须在**所有**退出路径上都是"点击后"的新帧。
                    //   2026-09-12 踩过：把抓帧挪进 confirm 回调 ⇒ helper 走「未变兜底」退出时
                    //   不经过回调 ⇒ ctx.frame 仍是**点击前**的旧帧 ⇒ parsePanel 读到上一件
                    //   ⇒ contentKey 重复 ⇒ 被去重**误杀**（artifact_scan 实测 20 → 18 件，
                    //   丢的正是两件已锁 5★ 未强化件）。
                    //   故用 `panelFrameReady` 标记"确认成功时已抓过帧"，仅在**没抓过**时补抓。
                    var panelFrameReady = false
                    val sigUsed = sigUsable &&
                        frameSource.sampleSignature(sigRoi!!.toIntRect(), sigBefore, sigBx, sigBy)
                    when {
                        sigUsed -> {
                            // 计时口径：签名的判据是**墙钟**（含采样/确认），与旧路径的"delay 之和"
                            // 略有不同但更真实（旧口径漏掉每轮的抓帧+OCR 时间）。
                            val tPanel = SystemClock.elapsedRealtime()
                            waitReadyBySignature(
                                before = sigBefore,
                                a = sigA,
                                b = sigB,
                                roi = sigRoi!!,
                                step = TimingOverrides.panelSigPollMs,
                                maxMs = PANEL_CHANGE_WAIT_MAX_MS,
                                minMs = 0L,
                                fallbackMs = TimingOverrides.panelStableFallbackMs,
                                what = "panel",
                                stableSamples = TimingOverrides.panelSigSamples,
                            ) {
                                // ★ 收尾 OCR 确认（整个就绪判定里唯一的一次 OCR）。
                                //
                                // ⚠️ 为什么确认必须能"否决"（2026-09-12 实测教训）：
                                //   面板切件是「上一件 → **空白平台期** → 新件」，空白期像素**静止**，
                                //   签名会误判"已稳定"（character_scan 实测前 4 格全中，读到空天赋 → 写 0）。
                                //   旧路径天然免疫：旧判据比**文本**，空读为 `null` ⇒ 不满足"已变"⇒ 继续等。
                                //   ⇒ 等价做法：把"名字非空"也作为退出条件 —— 返回 false 则由 helper
                                //   以当前签名为新基准再等一轮。正常路径**零额外成本**。
                                ctx.release()
                                ctx.frame = freshFrame()
                                panelFrameReady = true
                                // 确认 OCR 可关（内容带已覆盖"最后渲染的字段" ⇒ 冗余度下降）
                                if (!TimingOverrides.panelSigConfirm) return@waitReadyBySignature true
                                val f = ctx.frame ?: return@waitReadyBySignature false
                                val nm = ocr?.readLines(f, listOf(nameRect!!))?.firstOrNull()
                                // 名字非空 **且** 面板星级已画出（后者是"面板真正渲染完"的强证据）
                                val stars = countStars(f, gridKey)
                                // ⚠️ 诊断（`sigdebug=1`）：把**退出时刻实际读到的东西**打出来 ——
                                //   只测"等待时长"看不出读到的是新件还是交叉淡入中的旧件（2026-09-12 踩过）。
                                if (TimingOverrides.sigDebug) {
                                    Log.i(TAG, "sigExit cell($col,$row) name='${nm ?: ""}' stars=$stars band=$gridKey")
                                }
                                !nm.isNullOrBlank() && (stars > 0 || !panelHasStarBand(gridKey))
                            }
                            tmPanelMs += SystemClock.elapsedRealtime() - tPanel
                        }
                        nameRect != null && beforeName != null && ocr != null -> {
                            var waited = 0L
                            // ⚠️ 2026-09-10 修：原判据「名字一变就 break」→ 面板淡入**早期**名字文本就已变化，
                            // 立刻抓帧时「等级/属性」尚未渲染 → parsePanel 读到空（首格无切换故正常，后续格全空）。
                            // 改为「已变 AND 连续两次读数一致」= 面板已稳定。
                            var prev: String? = null
                            var stableSame = 0
                            val pStep = TimingOverrides.panelPollMs
                            while (waited < PANEL_CHANGE_WAIT_MAX_MS) {
                                delay(pStep); waited += pStep; tmPanelMs += pStep
                                val f = freshFrame()
                                val now = try { ocr.readLines(f, listOf(nameRect)).firstOrNull() } finally { f.release() }
                                val c = now?.let { StatParser.clean(it) }?.takeIf { it.isNotEmpty() }
                                if (c != null && c != StatParser.clean(beforeName) && c == prev) break
                                // ★ 2026-09-11 效率修：相邻两件**同名**时上面的「已变」条件永不成立 →
                                //   每格白等满 PANEL_CHANGE_WAIT_MAX_MS(6s)。实测 weapon_scan 63 格里有 9 格打满
                                //   （P90=6.50s，占全流程 ~54s/100s）。加兜底：名字**未变但已连续稳定**累计
                                //   PANEL_STABLE_FALLBACK_MS → 判定「面板本来就是这个件」（相邻同名/同件），提前退出。
                                //   稳妥性：淡入过程中读数不稳定（不会连续相等），故稳定即视为已渲染完。
                                stableSame = if (c != null && c == prev) stableSame + 1 else 0
                                val needSame = (TimingOverrides.panelStableFallbackMs / pStep).coerceAtLeast(1L)
                                if (stableSame.toLong() >= needSame) break
                                prev = c
                            }
                        }
                        else -> {
                            // ⚠️ 这条分支是「拿不到面板名 ROI」时的**固定**等待，与轮询步长无关
                            //    （曾误用 coerceAtMost(panelPollMs) → 550ms 被拖到 120ms）。
                            tmPanelMs += PANEL_REFRESH_MS
                            delay(PANEL_REFRESH_MS)
                        }
                    }
                    // 收敛帧兜底：签名路径若从未确认成功（未变兜底/超时），此处补抓一次，
                    // 保证 ctx.frame 一定是"点击后"的新帧（否则 parsePanel 读旧件 → 去重误杀）。
                    if (!panelFrameReady) {
                        ctx.release() // 面板切换，上一格共享帧作废
                        ctx.frame = freshFrame()
                    }
                    if (gridKey == "artifact_backpack") {
                        Log.i(TAG, "visit cell($col,$row) idx=$index freshFrame acquired")
                    } else {
                        val ocrGateway = ocr
                        val fallback = step.optJSONObject("fallback")
                        if (fallback != null && ocrGateway != null) {
                            val region = fallback.optString("region", "").removePrefix("$")
                            if (region.isNotEmpty()) {
                                val regionRect = try { profile.rect(region) } catch (_: Exception) { null }
                                if (regionRect != null) {
                                    val ok = runCatching {
                                        val frame = freshFrame()
                                        try {
                                            val text = ocrGateway.readLines(frame, listOf(regionRect)).joinToString(" ")
                                            StatParser.clean(text).isNotEmpty()
                                        } finally { frame.release() }
                                    }.getOrDefault(true)
                                    if (!ok) {
                                        Log.w(TAG, "fallback: panel not detected at $region, retry click")
                                        actions.click(cx, cy)
                                        delay(CLICK_SETTLE_MS)
                                    }
                                }
                            }
                        }
                    }
                    }
                }
                "parsePanel" -> {
                    // §14 P3：角色概览面板走独立解析分支（字段与圣遗物面板不同构）
                    if (step.optString("panel") == "char_profile") {
                        // ⚠️ 2026-09-10 修：角色 visit 在 click 与 parsePanel 之间有 navigate(属性) 复位步骤，
                        // 而 ctx 缓存的是 **navigate 之前**的帧 → 复用会读到旧页（天赋页 → name ROI 读到天赋名）。
                        // 故角色面板一律取新帧（每次多 1 次抓帧，代价可忽略）。
                        parseCharacterPanel(step, null)
                    } else {
                        parsePanel(step, ctx)
                    }
                }
                "navigate" -> {
                    navigate(step)
                    // §15 P0-1：有 leftMenu 时已在 navigate 内逐页读；无 leftMenu 才走 read 数组兜底
                    val menuLen = step.optJSONArray("leftMenu")?.length() ?: 0
                    if (menuLen == 0) readAfterNavigate(step)
                }
                "dialog" -> dialog(step)
                "verify" -> verify(step)
                "assertScreen" -> assertScreen(step)
            "readConstellation" -> readConstellation(step)
                "emit" -> {
                    // §14 P3：emit as=GoodCharacter → 角色入库；否则沿用既有 vars 快照上报
                    if (step.optString("as") == "GoodCharacter") {
                        emitCharacter(step)
                    } else {
                        listener.onProgress("emit", vars.snapshot())
                    }
                }
                "stopWhen" -> stopWhen(step)
                else -> {
                    Log.w(TAG, "unknown visit step '$vop', skipped")
                    RecognitionLog.log(logTag, RecognitionLog.Level.W, "未知 visit 步 $vop 已跳过")
                }
            }
        }

    // ---- #4 vote：像素投票判据 ----
    private suspend fun vote(
        step: JSONObject,
        gridKey: String? = null,
        col: Int = 0,
        row: Int = 0,
        ctx: CellFrameContext? = null,
        prof: ScreenProfile = profile,
    ) {
        val tVote = System.nanoTime() // 只读探针（见 PerfProbe）
        val zoneKey = step.getString("zone")
        // 单格共享帧：panel vote 复用 click 轮询的收敛帧（card vote 在 click 前且 ctx 帧为空，仍自抓）
        val frame = ctx?.acquire { freshFrame() } ?: freshFrame()
        try {
            val yShift = if (vars.crafted) profile.zhushengShiftPx else 0
            when (zoneKey) {
                // 网格类判据：坐标随页面相位偏移（prof = 偏移视图）
                "artifact.card.lockBadge" -> {
                    val r = VoteJudges.cardLockBadge(frame, prof, requireNotNull(gridKey), col, row)
                    vars.gridLocked = r.matched
                }
                "weapon.card.starStrip" -> {
                    val r = VoteJudges.weaponStarStrip(frame, prof, requireNotNull(gridKey), col, row)
                    vars.rarity = r.count
                    Log.d(TAG, "vote starStrip c$r.count col=$col row=$row (D 诊断)")
                }
                "weapon.card.lockBadge" -> {
                    val r = VoteJudges.cardLockBadgeByZone(frame, prof, requireNotNull(gridKey), "weapon.card.lockBadge", col, row)
                    vars.locked = r.matched
                }
                // panel 类判据：详情面板固定位置，用原始 profile（不随网格相位偏移）
                "artifact.panel.zhusheng" -> {
                    vars.crafted = VoteJudges.panelZhusheng(frame, profile).matched
                }
                "artifact.panel.lock" -> {
                    // 双区判定：同一单件名可普通(锁徽 y)/祝圣(锁徽 y+shift)两种面板（2560 实测 744 vs 808），
                    // 任一区 gold 达阈即已锁；artifact_lock flow 不投 zhusheng，故弃 vars.crafted 改自动。
                    vars.locked = VoteJudges.panelLock(frame, profile, 0).matched ||
                        VoteJudges.panelLock(frame, profile, profile.zhushengShiftPx).matched
                    if (step.optString("as") == "curLock") vars.curLock = vars.locked
                }
                "artifact.panel.astral" -> {
                    vars.favorited = VoteJudges.panelAstral(frame, profile, yShift).matched
                }
                "artifact.rarity" -> {
                    vars.rarity = VoteJudges.rarityFromBanner(frame, profile)
                }
                // ★ 2026-09-11：武器星级改由**详情面板星行**逐格判定（对齐圣遗物 starBand），
                //   取代卡片 starStrip —— 后者在 3★/4★ 边界抖动（同件两次读出 3★/4★，36 件中 10 件）。
                //   面板星行是固定坐标、大而清晰；5★ 大星带辉光会连成一片，故必须逐格采样（countStars 已是逐格）。
                "weapon.panel.starBand" -> {
                    val panelStars = countStars(frame, "weapon_backpack")
                    if (panelStars > 0) {
                        vars.rarity = panelStars
                        Log.d(TAG, "weapon panel stars = $panelStars")
                    } else {
                        // ⚠️ 回退：profiles 未标定 `panels.weapon_backpack.starBand` 的分辨率档
                        //    （3200/2560 目前没有）⇒ 走回旧的卡片星带判据，避免 rarity=0 让
                        //    parseWeaponPanel 直接 return（**整档导出 0 件**）。
                        val r = VoteJudges.weaponStarStrip(frame, prof, requireNotNull(gridKey), col, row)
                        vars.rarity = r.count
                        Log.d(TAG, "weapon panel stars unavailable → fallback card starStrip = ${r.count}")
                    }
                }
                else -> Log.w(TAG, "vote zone '$zoneKey' not implemented, skipped")
            }
        } finally {
            if (ctx?.frame !== frame) frame.release()
            PerfProbe.addVote(System.nanoTime() - tVote)
        }
    }

    // ---- #5 parsePanel：字段槽 OCR → GoodArtifact（含 set_name 词典反推）----
    private suspend fun parsePanel(step: JSONObject, ctx: CellFrameContext? = null) {
        if (ocr == null) {
            Log.d(TAG, "parsePanel skipped: OcrGateway not available")
            return
        }
        val panelKey = step.optString("panel", "artifact_backpack")
        // §14 P2-C3：放行 artifact_manage（圣遗物管理界面面板，与背包面板不同构）
        if (panelKey != "artifact_backpack" && panelKey != "weapon_backpack" && panelKey != "artifact_manage") {
            Log.w(TAG, "parsePanel panel '$panelKey' not supported yet, skipped")
            return
        }
        // 单格共享帧：复用 click 轮询收敛帧（visit 结束由 ctx 统一释放）
        val owned = ctx == null
        val frame = ctx?.acquire { freshFrame() } ?: freshFrame()
        try {
            if (panelKey == "weapon_backpack") {
                parseWeaponPanel(frame, ocr, step.optJSONObject("dict"))
            } else {
                parseArtifactPanel(frame, ocr, panelKey, step.optJSONObject("dict"))
            }
        } finally {
            if (owned) frame.release()
        }
    }

    private suspend fun parseWeaponPanel(frame: Mat, ocr: OcrGateway, dict: JSONObject? = null) {
        val yShift = 0
        // ★ 2026-09-12：**5 次单槽 `readLines` → 1 次批量 `readRois`**（多 ROI 同帧、一次推理）。
        //   ⚠️ 语义差异（踩过）：`readLines` 是 `mapNotNull`（丢空串、返回长度会变）；
        //      `readRois` 是 `map`（**与 rects 一一对应**，空为 ""）⇒ 必须**按下标取**，
        //      沿用 `firstOrNull()` 会字段串位（静默错数据）。
        //   顺带删掉两个**读了从不使用**的槽（mainLabel/mainValueText：武器主词条/白字无 GOOD 映射）。
        val base = "panels.weapon_backpack"
        val rects = listOf(
            profile.rect("$base.name"),
            profile.rect("$base.level"),
            profile.rect("$base.refine"),
        )
        val texts = ocr.readRois(frame, rects)
        fun at(i: Int): String? = texts.getOrNull(i)?.takeIf { it.isNotBlank() }
        val pieceName = at(0)?.let { StatParser.clean(it) }
        val levelText = at(1)
        val refineText = at(2)
        // 词典/模糊开关取自 flow 的 dict.name / dict.fuzzy（weapon_scan 已声明 mappings.weapons + fuzzy=1）
        val nameDict = dict?.optString("name")?.takeIf { it.isNotEmpty() }
        val key = pieceName?.let {
            if (nameDict != null) {
                lookupName(nameDict, it, dictFuzzyOf(dict))
            } else {
                names?.match(it, GoodNames.Kind.WEAPON, dictFuzzyOf(dict))?.key
            }
        }
        if (pieceName != null && key == null) {
            Log.w(TAG, "weapon name '$pieceName' not found in mappings.weapons")
        }
        val level = levelText?.let { StatParser.extractValue(it)?.toInt() } ?: 0
        val refine = refineText?.let { StatParser.extractValue(it)?.toInt() }
        val rarity = vars.rarity  // vote zone weapon.card.starStrip 已设
        if (rarity < 1 || rarity > 5) return
        if (key == null) return
        // ★ 连续重复件判据（在 dedupe 早退**之前**求值，否则永远数不到）：身份键与下面的 dedupe 完全同构
        if (noteDupAndMaybeStop("$key|L$level|R$refine",
                resultsWeapons.map { "${it.key}|L${it.level}|R${it.refine}" })) return
        // 去重：key + level + refine 唯一
        if (dedupe && resultsWeapons.any { it.key == key && it.level == level && it.refine == refine }) {
            Log.d(TAG, "duplicate weapon skipped: $key L$level R$refine")
            return
        }
        val weapon = GoodWeapon(
            key = key,
            level = level,
            rarity = rarity,
            refine = refine,
            lock = vars.locked == true,
        )
        resultsWeapons.add(weapon)
        listener.onProgress("weapon", vars.snapshot() + ("piece" to pieceName) + ("idx" to resultsWeapons.size))
    }

    /**
     * @param panelKey 面板键（"artifact_backpack" | "artifact_manage"）。
     * ⚠️ 两面板**不同构**（坐标/字段/星带/set_name 可读性皆异），故路径必须由此参数化——
     * 旧码硬编码 panels.artifact_backpack.*，对管理面板会静默取错 ROI。
     */
    private suspend fun parseArtifactPanel(
        frame: Mat,
        ocr: OcrGateway,
        panelKey: String = "artifact_backpack",
        /** flow 的 `dict` 声明（字段→词典 key / fuzzy / subStats 档位表），全 JSON 驱动。 */
        dict: JSONObject? = null,
    ) {
        val base = "panels.$panelKey"
        val yShift = if (vars.crafted) profile.zhushengShiftPx else 0

        // 一次批量读全部文本槽（name/slot/mainName/mainValue/level/subStats×4）——单帧单调用，
        // 返回与 rects 一一对应（blank 保留占位，按索引取回）
        val nameRect = profile.rect("$base.name")
        val slotRect = profile.rect("$base.slot")
        val mainNameRect = profile.rect("$base.mainName")
        val mainValueRect = profile.rect("$base.mainValue")
        val levelRect = profile.rect("$base.level").shiftedBy(yShift)
        val subStatsArr = profile.rawObject(base)!!.getJSONArray("subStats")
        val subRects = (0 until subStatsArr.length()).map { i ->
            val r = subStatsArr.getJSONArray(i)
            profile.scaleRect(r.getInt(0), r.getInt(1), r.getInt(2), r.getInt(3)).shiftedBy(yShift)
        }
        // ★ 2026-09-12：把 artifact_manage 的 set_name 也**并入同一次批量**（原为独立第 2 次调用）。
        val setNameRect = if (panelKey == "artifact_manage") {
            profile.rawObject(base)?.optJSONArray("setName")?.takeIf { it.length() >= 4 }?.let {
                profile.scaleRect(it.getInt(0), it.getInt(1), it.getInt(2), it.getInt(3))
            }
        } else null
        val setNameIdx = if (setNameRect != null) 5 + subRects.size else -1
        val lines = ocr.readRois(
            frame,
            listOf(nameRect, slotRect, mainNameRect, mainValueRect, levelRect) + subRects +
                (if (setNameRect != null) listOf(setNameRect) else emptyList()),
        )

        // 件名 + 部位 + 主词条（blank → null，保持原单槽 readLines 语义）
        val pieceName = lines.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { StatParser.clean(it) }
        val slotText = lines.getOrNull(1)?.takeIf { it.isNotBlank() }
        val slotKey = slotText?.let { StatParser.slotKeyOf(it, names) }
        val mainName = lines.getOrNull(2)?.takeIf { it.isNotBlank() }
        val mainValueText = lines.getOrNull(3)?.takeIf { it.isNotBlank() }
        val mainStat = mainName?.let { StatParser.parse(it + (mainValueText ?: ""), names) }

        // 等级（祝圣面板 yShift）
        val levelText = lines.getOrNull(4)?.takeIf { it.isNotBlank() }
        vars.level = levelText?.let { StatParser.extractValue(it)?.toInt() } ?: 0

        // 星带逐格（⚠️整带列投影 5★ 会合并，必须逐格；祝圣 yShift 不作用于星带——profiles zhusheng.fields 仅 level/subStats/lock/astral）
        val starCount = countStars(frame, panelKey)
        // 交叉校验：banner 色分类 vs 星数——以星数为准
        val rarity = if (starCount > 0) starCount else vars.rarity
        vars.rarity = rarity

        // 副词条（祝圣 yShift；4 行）——blank 槽 parse 为 null 被 mapNotNull 过滤
        var substats = lines.drop(5).mapNotNull { StatParser.parse(it, names) }

        // 稀有度-词条数规则（用户定稿 2026-09-02）：5★恒4词条 / 4★初始2最高3 / 3★2★止扫不解析
        when {
            rarity < STOP_MARKER_RARITY -> {
                Log.d(TAG, "parsePanel: rarity=$rarity stop-marker, not parsed")
                return
            }
            rarity == 5 && substats.size > MAX_SUBS_5STAR -> substats = substats.take(MAX_SUBS_5STAR)
            rarity == 4 && substats.size > MAX_SUBS_4STAR -> {
                Log.w(TAG, "parsePanel: 4★ has ${substats.size} substats (>3), parse anomaly")
                substats = substats.take(MAX_SUBS_4STAR)
            }
        }
        // §14 dict.subStats：OCR 数值吸附到标准档位（rollTable），使导出值与游戏内一致
        dict?.optString("subStats")?.takeIf { it.isNotEmpty() }?.let { subDict ->
            substats = snapSubstats(substats, rarity, subDict)
        }

        // set_name 反推（背包面板 set_name 被遮挡，用户定稿 2026-09-01）
        // set_name 反推（背包面板 set_name 被遮挡，用户定稿 2026-09-01）：单件名 → 套装 id
        // 件名→套装反推的词典取自 flow 的 dict.name（artifact_scan 已声明 mappings.artifactPieces）
        val pieceDict = dict?.optString("name")?.takeIf { it.isNotEmpty() }
        var setKey = pieceName?.let {
            if (pieceDict != null) {
                lookupName(pieceDict, it)
            } else {
                names?.match(it, GoodNames.Kind.PIECE)?.key
            }
        }
        // §14 P2-C3：管理界面面板 set_name **可读直采**（背包面板被遮挡，只能单件名反推）。
        // 直采优先；失败仍回落反推。同帧顺带判读 lockChip/starChip 图标。
        if (panelKey == "artifact_manage") {
            if (setNameIdx >= 0) {
                val t = StatParser.clean(lines.getOrNull(setNameIdx).orEmpty())
                // 词典取自 flow 的 dict.setName（未声明时回落默认，不再硬编码）
                val setNameDict = dict?.optString("setName")?.takeIf { it.isNotEmpty() }
                    ?: DEFAULT_SET_DICT
                val k = lookupName(setNameDict, t)
                if (k != null) {
                    setKey = k
                    Log.i(TAG, "manage set_name 直采: $k（OCR=$t）")
                }
            }
            readManageIcons(frame)
        }
        if (pieceName != null && setKey == null) {
            Log.w(TAG, "setKey not found for piece '$pieceName' (dict miss)")
        }

        val artifact = GoodArtifact(
            setKey = setKey,
            slotKey = slotKey,
            level = vars.level,
            rarity = rarity,
            mainStatKey = mainStat?.key,
            mainStatValue = mainStat?.value ?: 0.0,
            substats = substats.map { GoodSubStat(it.key, it.value) },
            lock = vars.locked == true,
            favorited = vars.favorited,
            pieceName = pieceName ?: "",
        )
        // 指纹去重入库（Q2 决策；键升级为内容指纹）：翻页 settle 半格重叠会重复出现同一件，
        // 快速点击的 stale 读内容也与上一件完全一致——两者都靠内容键拦截。
        // ⚠️ 键必须含 level/词条：同件名的不同实例（不同强化/词条）真实存在，
        // 旧 pieceName 单键会把它们当重复丢掉（1406 件 / 全游戏 ~280 种件名，同名必然大量）。
        // irminsul 同为内容键（slotKey|setKey|level|词条）。
        val contentKey = artifact.run {
            listOfNotNull(
                setKey,
                slotKey,
                pieceName?.takeIf { it.isNotEmpty() },
                level.toString(),
                mainStatKey,
                mainStatValue.toString(),
                "L$lock",
                "F$favorited",
            ).joinToString("|") + "|" + substats.joinToString("|") { "${it.key}:${it.value}" }
        }
        // ★ 连续重复件判据：身份键用**与 dedupe 相同的 contentKey**（件名+等级+词条），
        //   不能只用件名 —— 同件名的不同圣遗物会被秒判重复。
        noteDupAndMaybeStop(contentKey, seenArtifactKeys.toList())
        if (dedupe && !seenArtifactKeys.add(contentKey)) {
            Log.d(TAG, "duplicate artifact skipped: $pieceName")
            return
        }
        results.add(artifact)
        listener.onProgress("artifact", vars.snapshot() + ("piece" to pieceName) + ("idx" to results.size))
    }

    /**
     * §14 P2-C3 管理界面图标判读（panels.artifact_manage，采样步长 2px）：
     * - lockChip  roi[2862,288,2912,338] 红掩码(r>140, r-g>60, r-b>60) >150px → 已锁
     * - starChip  roi[2943,288,2992,338] 金掩码(R>170, G 120~220, B<150) ≥100px → 已收藏（灰星基线 0-7px）
     * 帧为 BGR（OpenCV Mat.get）→ 索引 [2]=R、[1]=G、[0]=B。
     */
    private fun readManageIcons(frame: Mat) {
        val pm = profile.rawObject("panels.artifact_manage") ?: return
        val lock = pm.optJSONObject("lockChip")?.optJSONArray("roi")
        val star = pm.optJSONObject("starChip")?.optJSONArray("roi")
        if (lock != null && lock.length() >= 4) {
            var red = 0
            var y = profile.scale(lock.getInt(1), profile.scaleY)
            val y1 = profile.scale(lock.getInt(3), profile.scaleY)
            val x0 = profile.scale(lock.getInt(0), profile.scaleX)
            val x1 = profile.scale(lock.getInt(2), profile.scaleX)
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    val p = frame.get(y, x)
                    if (p[2] > 140 && p[2] - p[1] > 60 && p[2] - p[0] > 60) red++
                    x += 2
                }
                y += 2
            }
            vars.locked = red > 150
            Log.i(TAG, "manage lockChip: red=$red → locked=${vars.locked}")
        }
        if (star != null && star.length() >= 4) {
            var gold = 0
            var y = profile.scale(star.getInt(1), profile.scaleY)
            val y1 = profile.scale(star.getInt(3), profile.scaleY)
            val x0 = profile.scale(star.getInt(0), profile.scaleX)
            val x1 = profile.scale(star.getInt(2), profile.scaleX)
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    val p = frame.get(y, x)
                    if (p[2] > 170 && p[1] >= 120.0 && p[1] <= 220.0 && p[0] < 150) gold++
                    x += 2
                }
                y += 2
            }
            vars.favorited = gold >= 100
            Log.i(TAG, "manage starChip: gold=$gold → favorited=${vars.favorited}")
        }
    }

    /** 星带逐格采样：格内金像素>100 → 该星点亮（profiles starBand.judge 固化阈值）。星带不随祝圣 yShift 移动。 */
    private fun countStars(frame: Mat, panelKey: String = "artifact_backpack"): Int {
        val band = profile.rawObject("panels.$panelKey")?.optJSONObject("starBand")
            ?: return 0
        val y0 = band.getJSONArray("y").getInt(0)
        val y1 = band.getJSONArray("y").getInt(1)
        val x0 = band.getInt("x0")
        val pitch = band.getInt("pitch")
        val cell = band.optInt("cell", 46)
        val top = profile.scale(y0, profile.scaleY)
        val bottom = profile.scale(y1, profile.scaleY)
        var count = 0
        for (i in 0 until 5) {
            val baseX = x0 + i * pitch
            val rect = FrameRect(
                left = profile.scale(baseX, profile.scaleX),
                top = top,
                right = profile.scale(baseX + cell, profile.scaleX),
                bottom = bottom,
            )
            if (VoteJudges.countMatches(frame, rect, VoteJudges.GOLD) > STAR_GOLD_THRESHOLD) count++
        }
        return count
    }

    // ---- #10 stopWhen：表达式谓词（D3）----
    private fun stopWhen(step: JSONObject) {
        // 显式模式优先（不再靠 expr 正则猜分支）：mode=duplicateStreak 的判据在 emitCharacter 求值
        // （那里才拿得到刚解析出的角色名与已入库集合；flow 顺序 emit→stopWhen 时 charName 已被清空）。
        if (step.optString("mode") == "duplicateStreak") {
            charDupStreakLimit = step.optInt("streak", 3)
            // 页级确认页数（默认 2）：`1` = 恢复旧的「首次整页重复即停」行为
            dupPageConfirm = step.optInt("dupPageConfirm", 2).coerceIn(1, 5)
            Log.i(
                TAG,
                "stopWhen 登记 mode=duplicateStreak streak=$charDupStreakLimit dupPageConfirm=$dupPageConfirm" +
                    "（判据在 emitCharacter 求值）",
            )
            return
        }
        val expr = step.optString("expr")
        if (expr.isEmpty()) {
            Log.w(TAG, "stopWhen: 既无 mode 也无 expr，忽略")
            return
        }
        // §14 P1：panelMatch(target, tol=0.1) —— Expr 不支持函数调用，此处直接求值 [hardMatch]
        if (expr.contains("panelMatch(")) {
            val task = vars.currentTask
            if (task == null) {
                Log.d(TAG, "panelMatch skipped: 无 currentTask（P4 未注入计划）")
                return
            }
            val tol = Regex("tol\\s*=\\s*([0-9.]+)").find(expr)?.groupValues?.getOrNull(1)
                ?.toDoubleOrNull() ?: 0.1
            val why = StringBuilder()
            if (hardMatch(task, tol, why)) {
                vars.stopRequested = true
                vars.stopReason = "stopWhen"
                Log.i(TAG, "stopWhen triggered (panelMatch tol=$tol): $expr | $why")
            } else {
                // 未命中也要留痕：否则「判据不生效」与「确实不匹配」在日志上不可区分。
                Log.i(TAG, "panelMatch 未命中 (tol=$tol): $why")
            }
            return
        }
        // 哨兵保护：rarity=-1（vote 未判定，如 3★ 蓝卡无金/紫 banner）不得触发止扫表达式
        // （rarity<4 对 -1 恒真 → 会把"整页未判定"误判为"全部低星"而立即终止）。
        if (expr.contains("rarity") && vars.rarity < 0) {
            Log.d(TAG, "stopWhen skipped: rarity not judged yet (-1 sentinel)")
            return
        }
        // §14 复核：character_scan 的 `name == roster[0]`（首名重现=遍历完）。
        // Expr 仅支持数值、不支持下标/字符串比较，且 tokenize 遇 '[' 会抛 EvalException → 特判。
        // 语义：当前角色草稿名 == 首个已入库角色名（且已入库 ≥1 条）→ 绕回起点，停止。
        if (expr.contains("roster[0]")) {
            val first = resultsCharacters.firstOrNull()?.name
            // ⚠️ 取 charName.ifEmpty{lastCharName}：`emitCharacter` 末尾清空草稿，
            //    而本步在 emit 之后 ⇒ 只看 charName 会恒为空、判据静默失效（2026-09-11 修）。
            val cur = charName.ifEmpty { lastCharName }
            // ⚠️⚠️ 必须要求**已入库 ≥2**：本步在 emit 之后求值时，`lastCharName` 就是刚入库那个，
            //    只有 1 条时「首名 == 末名」恒成立 ⇒ 会在第 1 件就误判「绕回起点」而**立即停扫**
            //    （2026-09-12 真机实测：`stopWhen (首名重现) 当前=YaeMiko 首名=YaeMiko 已入库=1`）。
            //    character_scan 现已改用 `mode=duplicateStreak`，本分支仅为兼容旧 flow 而保留。
            if (resultsCharacters.size >= 2 && !first.isNullOrEmpty() && cur == first) {
                vars.stopRequested = true
                vars.stopReason = "stopWhen"
                Log.i(TAG, "stopWhen triggered (首名重现): 当前=$cur 首名=$first 已入库=${resultsCharacters.size}")
            }
            return
        }
        val scope = step.optString("scope", "page")
        // 兜底：坏表达式（未定义变量/语法越界）不得炸掉整个流程——记 warn 后按未命中处理
        val hit = runCatching { Expr.eval(expr, vars.exprVars()) }
            .onFailure { Log.w(TAG, "stopWhen 表达式求值失败，按未命中处理：$expr (${it.message})") }
            .getOrDefault(false)
        if (hit) {
            // scope=cell（本页后停）：置 flag，页遍历结束后停止；scope=page 立即停
            vars.stopRequested = true
            vars.stopReason = "stopWhen"
            Log.i(TAG, "stopWhen triggered ($scope): $expr")
        }
    }

    /**
     * ★ 通用「就绪轮询」（2026-09-12）：签名停稳 +（可选）锚内容非空。
     *
     * 两个调用方共用（面板就绪 / 导航页就绪），语义一致：
     * 1. **已变**：签名与动作前不同；
     * 2. **连续 [SIG_STABLE_SAMPLES] 次「跨帧」一致** ⇒ 渲染完成（3 次防交叉淡入的平台期）；
     * 3. **必须跨帧**：步长 < 帧间隔时同一帧自比会假稳定 ⇒ 用 `frameGeneration()` 作门；
     * 4. **未变兜底**：一直与动作前相同且过了 `fallbackMs` ⇒ 认定"本来就是这个状态"
     *    （⚠️ 此支**不要求跨帧**：静态屏不产新帧）；
     * 5. **下限 `minMs`**：防"动作尚未生效、页面还静止"时立刻判稳（导航场景必需）；
     * 6. **锚确认 `confirm`**：判稳后跑一次确认；返回 false ⇒ **以当前签名为新基准再等一轮**，
     *    最多 [SIG_CONFIRM_RETRIES] 次。这一步替代旧判据的"文本非空"隐含防护（空白平台期）。
     *
     * @param what 日志前缀（如 `panel` / `nav:天赋`）
     */
    private suspend fun waitReadyBySignature(
        before: ByteArray,
        a: ByteArray,
        b: ByteArray,
        roi: FrameRect,
        step: Long,
        maxMs: Long,
        minMs: Long,
        fallbackMs: Long,
        what: String,
        /** 认定"已稳定"所需的**跨帧**连同样本数（默认 [SIG_STABLE_SAMPLES]）。 */
        stableSamples: Int = SIG_STABLE_SAMPLES,
        confirm: (suspend () -> Boolean)? = null,
    ) {
        val roiI = roi.toIntRect()
        var waited = 0L
        var first = true
        var lastGen = Long.MIN_VALUE
        var changedEver = false
        var sameStreak = 0
        var retries = 0
        var prev: ByteArray? = null
        var cur = a
        // ── 分段计时（只读诊断，`sigdebug=1` 才打）──
        var firstChangeAt = -1L          // 从"开始轮询"到**首次读到已变**的耗时 = 页面渲染时长
        var lastCountedAt = 0L
        var frameGapSum = 0L
        var frameGaps = 0
        var samples = 0
        fun logWait(reason: String) {
            if (!TimingOverrides.sigDebug || what != "panel") return
            Log.i(
                TAG,
                "sigWait[$what] $reason total=${waited}ms firstChange=${if (firstChangeAt < 0) "none" else "${firstChangeAt}ms"} " +
                    "afterChange=${if (firstChangeAt < 0) "n/a" else "${waited - firstChangeAt}ms"} " +
                    "samples=$samples frameGaps=$frameGaps/${frameGapSum}ms step=$step",
            )
        }
        while (waited < maxMs) {
            delay(step)
            waited += step
            if (!frameSource.sampleSignature(roiI, cur, SIG_BLOCKS_X, SIG_BLOCKS_Y)) {
                Log.w(TAG, "$what signature unavailable mid-poll, abort")
                return
            }
            val gen = frameSource.frameGeneration()
            val genChanged = first || gen != lastGen
            lastGen = gen
            if (genChanged) {
                samples++
                if (lastCountedAt > 0) {
                    frameGapSum += waited - lastCountedAt
                    frameGaps++
                }
                lastCountedAt = waited
            }
            val changed = changedFraction(before, cur) > 0f
            if (changed && firstChangeAt < 0) {
                firstChangeAt = waited
                // 诊断（`sigdebug=1`）：**首次检出变化**时打出块网格掩码 ——
                // 用来回答"band 里到底是**哪一块**先变"（若只是边框/背景先动，说明该把 ROI 收窄）。
                if (TimingOverrides.sigDebug && what == "panel") {
                    Log.i(TAG, "sigFirstChange[${roiI.left},${roiI.top},${roiI.right},${roiI.bottom}] " +
                        "waited=${waited}ms\n${blockMask(before, cur)}")
                }
            }
            val p = prev
            var stable = false
            when {
                changed -> {
                    changedEver = true
                    // 未动判据**严格**：任何一块不同都不算"同一状态"（见 SIG_LEN 旁的事故记录）
                    val sameAsPrev = p != null && changedFraction(p, cur) == 0f
                    sameStreak = if (sameAsPrev && genChanged) sameStreak + 1 else 1
                    if (genChanged) tmSigSamples++
                    stable = sameStreak >= stableSamples && waited >= minMs
                }
                !changedEver -> {
                    if (genChanged) tmSigSamples++
                    // 未变兜底：用"距动作的墙钟"（静态屏也能兜底）
                    if (waited >= maxOf(fallbackMs, minMs)) {
                        logWait("unchanged-fallback")
                        return
                    }
                }
                else -> sameStreak = 0
            }
            if (stable) {
                val ok = confirm?.invoke() ?: true
                if (ok) {
                    logWait("stable")
                    return
                }
                if (retries >= SIG_CONFIRM_RETRIES) {
                    Log.w(TAG, "$what confirm still empty after $retries retries, proceed")
                    return
                }
                retries++
                Log.i(TAG, "$what confirm empty → 以当前签名为新基准再等一轮 retry=$retries")
                System.arraycopy(cur, 0, before, 0, before.size) // 新基准 = 当前（可能是空白平台）
                changedEver = false
                sameStreak = 0
                prev = null
                cur = a
                first = true
                continue
            }
            // 轮换缓冲：prev = 本轮样本，cur = 下一轮写入目标
            val other = if (cur === a) b else a
            prev = cur
            cur = other
            first = false
        }
        logWait("timeout")
        Log.w(TAG, "$what signature not settled within ${maxMs}ms")
    }

    /**
     * 面板**就绪内容带**：把面板里"靠后渲染的内容区"取并集，作为就绪签名的采样区。
     *
     * ⚠️ **不要用名字区做就绪信号**（2026-09-12 定论）：名字是**最先**渲染完的 ⇒
     *   "名字稳定" ≠ "面板渲染完" ⇒ 早退出 ⇒ `parsePanel` 读到半渲染的等级/副属性
     *   —— 这正是 artifact_scan 曾把 **20 件导成 18 件**的根因（靠"确认 OCR 读名字"兜不住，
     *   因为名字本来就早好了）。参考 GOODScanner `PANEL_POOL_RECT=(1330,478,370,187)`@1920：
     *   他们同样选**中下方面板窗口**（等级+前两条副属性），而不是名字。
     *
     * 取值为 [PANEL_BAND_KEYS] 里**存在**的项的并集（按 panel 自动适配圣遗物/武器/角色）；
     * 坐标统一走 [ScreenProfile.scaleRect] ⇒ 三档分辨率自动适配。
     *
     * @return null = 该面板没有可用内容带（调用方回退名字区）
     */
    private fun panelReadyBand(panelKey: String): FrameRect? {
        val d = profile.rawObject("panels.$panelKey") ?: return null
        var l = Int.MAX_VALUE
        var t = Int.MAX_VALUE
        var r = Int.MIN_VALUE
        var b = Int.MIN_VALUE
        var any = false
        fun takeRect(arr: org.json.JSONArray) {
            if (arr.length() < 4) return
            val x0 = arr.optInt(0, Int.MIN_VALUE)
            val y0 = arr.optInt(1, Int.MIN_VALUE)
            val x1 = arr.optInt(2, Int.MIN_VALUE)
            val y1 = arr.optInt(3, Int.MIN_VALUE)
            if (x0 == Int.MIN_VALUE || y0 == Int.MIN_VALUE || x1 == Int.MIN_VALUE || y1 == Int.MIN_VALUE) return
            val sc = profile.scaleRect(x0, y0, x1, y1)
            if (sc.left < l) l = sc.left
            if (sc.top < t) t = sc.top
            if (sc.right > r) r = sc.right
            if (sc.bottom > b) b = sc.bottom
            any = true
        }
        // ⚠️ 取**面板内所有矩形条目**的并集（不按 key 过滤）：内容带必须**覆盖我们实际要读的每一个字段**，
        //    否则"带内已稳定"≠"整面板已就绪"。2026-09-12 实测踩过：按 key 只收左列属性行 ⇒ 武器面板的
        //    **锁图标（x2017）落在带外** ⇒ 2 样本 + 免确认时锁还没渲染就读 ⇒ 一件武器的 `lock` 由 true 读成 false。
        for (k in d.keys()) {
            val v = d.opt(k)
            if (v is org.json.JSONArray) {
                // 单矩形（4 个数字）或矩形数组（subStats 之类）；starBand 这类对象会被自然跳过
                if (v.length() > 0 && v.opt(0) is org.json.JSONArray) {
                    for (i in 0 until v.length()) (v.opt(i) as? org.json.JSONArray)?.let { takeRect(it) }
                } else {
                    takeRect(v)
                }
            }
        }
        if (!any || r <= l || b <= t) return null
        return FrameRect(l, t, r, b)
    }

    /**
     * 面板**星级行是否已画出**（就绪确认用）。
     *
     * ⚠️ 为什么必须有这一条（2026-09-12 实测）：把块网格细化到 16×8 后，"已变"能在 40ms 检出、
     *   80ms 即判稳；但武器面板存在**空白平台期**（像素静止、内容未画）⇒ 名字已可读而**星级行还没画**
     *   ⇒ `countStars` 读 0 ⇒ 误触发 `stopWhen(rarity<=2)`（实测导出 36 → 1~8 件）。
     *   ⇒ 就绪确认必须覆盖**实际出错的字段**，不能只看名字。
     * 无 `starBand` 的面板（如角色）⇒ 返回 true（不做此判据，由其余判据兜）。
     */
    /**
     * 面板 → 就绪签名**块网格**（未显式 `sigblocks` 覆盖时生效）。
     *
     * ⚠️ 实测理由（2026-09-12，见 `PIPELINE-FEASIBILITY.md` §14.7）：
     * - **artifact_backpack → 16×8（细）**：圣遗物面板的内容在**约 1 帧内换完**（实测 `firstChange=40ms`
     *   且 80ms 已稳），细网格能立刻检出 ⇒ 等待 240→80ms，**perCell −20%**（4 次 GOOD 逐字段一致）。
     * - **weapon_backpack → 8×4（粗）**：武器面板切换有一个 **~200ms 的过渡态**：整面板像素先全变、
     *   **文本约 200ms 后才换**。细网格会把过渡态误判成"已换完"（实测掩码 16×8 全 `#`、80ms 退出）
     *   ⇒ 读到**滞后 2 格的旧面板** ⇒ 内容指纹重复 ⇒ 被去重误杀（导出 36 → 1~8 件）。
     *   粗网格的块均值在过渡态几乎不动 ⇒ 要等内容真正出现才检出 ⇒ 天然躲开过渡态。
     * ⇒ 与 GOODScanner 一致（它对**武器用 FixedDelay**、其余用 Fingerprint：相同武器面板完全相同，
     *   指纹永不变化）。
     */
    private fun sigBlocksFor(panelKey: String): Pair<Int, Int> =
        if (panelKey == "artifact_backpack") {
            SIG_BLOCKS_FINE_X to SIG_BLOCKS_FINE_Y
        } else {
            SIG_BLOCKS_X to SIG_BLOCKS_Y
        }

    private fun panelHasStarBand(panelKey: String): Boolean {
        val band = profile.rawObject("panels.$panelKey")?.optJSONObject("starBand") ?: return false
        return band.has("y") && band.has("x0")
    }

    private fun panelStarsDrawn(frame: Mat, panelKey: String): Boolean {
        if (!panelHasStarBand(panelKey)) return true
        return countStars(frame, panelKey) > 0
    }

    /**
     * 两签名差异占比（复用既有容差语义；长度不一致/越界按"变了"处理）。
     */
    private fun changedFraction(x: ByteArray, y: ByteArray): Float =
        VoteJudges.thumbChangedFraction(x, y, VoteJudges.THUMB_DIFF_TOL) ?: 1f

    /** 块网格差异掩码（'.'=同 '#'=变，第一行=ROI 上沿）；仅供 `sigdebug` 诊断。 */
    private fun blockMask(a: ByteArray, b: ByteArray): String {
        val bx = TimingOverrides.sigBlocksX
        val by = TimingOverrides.sigBlocksY
        val sb = StringBuilder("  blockMask(${bx}x$by) . =同 # =变\n")
        for (y in 0 until by) {
            sb.append("  ")
            for (x in 0 until bx) {
                val i = (y * bx + x) * 3
                if (i + 2 >= a.size || i + 2 >= b.size) {
                    sb.append('?')
                    continue
                }
                val d = maxOf(
                    kotlin.math.abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)),
                    kotlin.math.abs((a[i + 1].toInt() and 0xFF) - (b[i + 1].toInt() and 0xFF)),
                    kotlin.math.abs((a[i + 2].toInt() and 0xFF) - (b[i + 2].toInt() and 0xFF)),
                )
                sb.append(if (d > VoteJudges.THUMB_DIFF_TOL) '#' else '.')
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun FrameRect.toIntRect(): IntRect = IntRect(left, top, width, height)

    /**
     * 动作后取新帧：帧阈值由 ActionGateway 实现侧 markActionAt 管理（P0 机制），
     * afterTimestampMs 传 0 仅作占位；超时回退最新帧（R1：单应用共享静止停帧兜底）。
     */
    private suspend fun freshFrame(timeoutMs: Long = 2500L): Mat {
        // 只读探针：freshFrame **包含「等一个新帧」的等待**（投影 ~30fps ⇒ 平均 ~33ms），
        // 这部分不计入 nav/panel/settle ⇒ 是「其它」里最易被忽略的大块（见 IMAGE-PATH-COST.md §8）。
        val t0 = System.nanoTime()
        try {
            return try {
                frameSource.grabFresh(0L, timeoutMs)
            } catch (e: FrameTimeoutException) {
                Log.w(TAG, "grabFresh timeout, falling back to latest frame")
                frameSource.acquireLatestBgr()?.bgr ?: throw e
            }
        } finally {
            PerfProbe.addFrame(System.nanoTime() - t0)
        }
    }

    internal companion object {
        const val TAG = "BetterGI.Scan"
        const val ENTER_SETTLE_MS = 1200L
        const val SCREEN_SETTLE_MS = 1500L
        const val CLICK_SETTLE_MS = 600L
        const val STAR_GOLD_THRESHOLD = 100 // starBand.judge: 格内金像素>100 → 星亮
        const val ANCHOR_RETRIES = 3
        const val ANCHOR_RETRY_DELAY_MS = 1500L
        // §15 归位主界面（GOODScanner return_to_main_ui 同参数）
        const val RETURN_HOME_ATTEMPTS = 8
        const val RETURN_HOME_SETTLE_MS = 900L
        // §15 P0-1：左侧菜单切页后的稳定等待（长于普通点击，动画约 600~800ms）
        /**
         * 页签切换后的等待。**2026-09-12 由 900 → 700**（档位实测：屏幕侧内容就绪 397~529ms；
         * 700 留 32% 余量，全量 90 角色实测 −23%，质量与 900 等价）。
         * ⚠️ 再往下（600/450/300）实测读取错误率翻倍并开始丢件 ⇒ 不要调。
         */
        const val NAVIGATE_PAGE_SETTLE_MS = 700L
        // §15 P1-1：点格后详情面板刷新等待（武器面板实测切换 >300ms 恒滞后一格 → 取 550 留余量）
        const val PANEL_REFRESH_MS = 550L
        /** 面板名「未变但已稳定」的兜底等待：相邻同名时不至于打满 6s 上限（实测省 ~54s/100s）。 */
        const val PANEL_STABLE_FALLBACK_MS = 400L
        /**
         * 点格后面板名轮询**步长**。**2026-09-12 由 200 → 120**（全量武器实测 −29%）。
         * ⚠️ 不要再调到 80：面板真刷新前就会走完「未变但稳定」兜底 → 读到上一格的名字
         * → 会被 character_scan 的「连续重复」止扫判据误判成遍历完（实测只出 1 个角色）。
         */
        const val PANEL_POLL_MS = 120L        // §15 P1-1：点格后面板变化轮询上限（武器详情 3D 加载慢，实测切换可 >2s）
        const val PANEL_CHANGE_WAIT_MAX_MS = 6000L

        // ── ★ 就绪信号直采（2026-09-12，取代上面的「抓帧 + OCR」轮询）──
        /**
         * 签名轮询步长。**实测面板内容 133ms（中位）就绪**（PERF-timing.md，样本 67/133/298），
         * 旧路径却花 428ms/格（= delay(120×3) + 每轮抓帧 + 每轮 OCR）。步长取 40ms 即可
         * 与帧率（~33ms）解耦；可由 `--es timing "sigpoll=…"` 覆盖。
         */
        const val PANEL_SIG_POLL_MS = 40L
        /** 签名分块数（列×行）；格式与 [VoteJudges.thumbChangedFraction] 对齐（每块 3 字节）。 */
        const val SIG_BLOCKS_X = 8
        const val SIG_BLOCKS_Y = 4
        const val SIG_LEN = SIG_BLOCKS_X * SIG_BLOCKS_Y * 3
        /** 块网格上限（scratch 缓冲按它分配；`sigblocks` 只能在此范围内调）。 */
        /** 圣遗物面板用的**细**网格（内容 1 帧内换完；见 [sigBlocksFor]）。 */
        const val SIG_BLOCKS_FINE_X = 16
        const val SIG_BLOCKS_FINE_Y = 8
        const val SIG_BLOCKS_X_MAX = 24
        const val SIG_BLOCKS_Y_MAX = 12
        const val SIG_LEN_MAX = SIG_BLOCKS_X_MAX * SIG_BLOCKS_Y_MAX * 3
        // ⚠️ 「已变 / 未动」两个判据都必须**严格**（见 waitReadyBySignature）：
        //   已变 = changedFraction > 0f（任一块变化即算变了）；未动 = changedFraction == 0f。
        //
        //   曾经为救"导航页永不判稳"而放宽「未动」到 0.05（允许 1/32 块差异）⇒ 面板名 ROI 里
        //   "等级数字"恰好只占 1~2 个块 ⇒ **面板还在淡入就被判稳** ⇒ 读到未渲染的等级 ⇒
        //   误触发 stopWhen(rarity<4&&level==0) ⇒ artifact_scan 导出 **20 → 18 件**（2026-09-12 实测）。
        //   ⇒ 两个阈值**不要为迁就某个用不上的场景而放宽**；严格语义是已验证的基线。
        /**
         * 认定"已稳定"所需的**跨帧**连同样本数。
         * ⚠️ 用 3 而不是 2：40ms 步长下交叉淡入可能有短暂平台，2 次会在平台处提前退出 → 读到空白面板。
         */
        const val SIG_STABLE_SAMPLES = 3
        /**
         * 收尾 OCR 确认读到空时的**最大重试轮数**。
         * 空读 = 撞上"面板空白平台期"被误判稳定 ⇒ 以当前签名为新基准再等一次"变化 + 稳定"。
         * 上限 1 轮：正常路径 0 轮（实测圣遗物/武器流程 0 次读空），异常格最多多花 ~300ms。
         */
        const val SIG_CONFIRM_RETRIES = 1
        /**
         * 面板"就绪内容带"的典型组成项（**仅文档性**；实现取面板内**所有**矩形条目的并集，
         * 刻意不按 key 过滤 —— 带必须覆盖我们实际要读的每个字段，锁图标/星行/横幅都算）。
         */
        val PANEL_BAND_TYPICAL_KEYS = arrayOf(
            "name", "level", "slot", "mainName", "mainValue", "baseLabel", "baseValue",
            "refine", "subStats", "lock", "stars", "starColor", "banner", "equipped",
        )
        // 自适应 settle（替代固定 PAGE_SETTLE）：轮询指纹至连续稳定，自动标定各设备翻页动画时长
        const val SETTLE_POLL_MS = 120L
        // ★ 2026-09-13：300→700。实测（7 轮 A/B，每轮前列表回顶、timing 生效已验证）：
        //   300ms 时被吞率 53~67%、落地中位 13~19px；700ms 时被吞率 33~40%、中位 128~139px。
        //   机制：滑动后存在 ~0.4s 低速惯性尾（帧间变化恰在 0.10 阈值附近）⇒ 300ms 会在尾巴里判"稳定"
        //   ⇒ 下一次滑动打在滚动动画中 ⇒ 被整段吞掉。700ms 让 settle 跨过尾巴。
        const val SETTLE_STABLE_MS = 700L
        const val SETTLE_MAX_MS = 6000L
        const val SETTLE_FRAME_TIMEOUT_MS = 800L
        /** `pollAnchorReady` 的轮询步长（enterScreen 末端就绪）。一次采样 = 抓帧 + 1 次 rec ≈ 10~25ms。 */
        const val ANCHOR_POLL_MS = 150L
        /** `filterReset` 轮询步长 / 非文本等待 / 面板内两次点击之间的等待。 */
        const val FILTER_PANEL_POLL_MS = 200L
        const val FILTER_PANEL_BACK_MS = 700L
        const val FILTER_PANEL_STEP_MS = 400L
        // 帧间差异比例阈值：相邻帧/翻页前后帧 RGB 四比特差异占比 ≤ 此值即视为"同帧/已到顶"。
        // 真机逐帧抖动/微动画使精确哈希相等几乎不成立 → 改用比例阈值（GRID_SIMILAR_DIFF）。
        const val GRID_SIMILAR_DIFF = 0.10f
        /**
         * 「回顶到顶」判据阈值（松）。误判到顶只多滑一次（便宜），漏判则白滑满
         * [MAX_SCROLL_TOP_ROUNDS] 次（贵）→ 代价不对称故放松。实测噪声带 ≤0.22、真实位移 ≥0.72，
         * 0.35 落在两者之间的安全带内（含 4-bit 量化容差 [VoteJudges.THUMB_DIFF_TOL] 后噪声更低）。
         */
        const val GRID_END_DIFF = 0.35f
        /** 诊断用：VoteJudges.gridThumb 的缩略图尺寸（24×16），仅用于掩码日志与长度校验。 */
        const val THUMB_COLS = 24
        const val THUMB_ROWS = 16
        // §12.5 相位校正（2026-09-05）：|φ| 超过卡片半高（pagedGrid 内按 geometry 计算）才补滑，最多 2 次
        const val MAX_FINISH_SWIPES = 2
        /** §14 A3：snap 遍历最大轮数兜底（防指纹判据失效时死循环；正常由 peek 消失终止）。 */
        const val MAX_SNAP_ROUNDS = 400
        const val MAX_SCROLL_TOP_ROUNDS = 12
        const val GRID_TOP_STABLE_ROUNDS = 2

        /**
         * 翻页**未生效**时的重发次数（2026-09-12 实测新增）。
         *
         * `reachedEnd` 判据（翻页前后指纹不变）**无法区分**「列表真的到底（钳制不动）」与
         * 「这次滑动没落地」—— 而实测约 **1/17 页**的翻页滑动完全没落地（整页 21 格全重复），
         * 且失败会**连续出现** ⇒ 回卷判据「连续 2 个整页零新增」会把整轮扫描提前收掉（实测 210/933）。
         * ⇒ 先重发滑动，只有连续 [GRID_END_RETRIES] 次都纹丝不动才认定到底。
         * 代价不对称：真到底时多滑 3 次（≈3s），误判则整轮报废。
         */
        const val GRID_END_RETRIES = 3

        /**
         * 翻页滑动的**起手 y 到底边至少留出的余量**（帧 px）。
         *
         * 闭环需要按增益放大命令距离（例如目标 612 而增益 0.7 ⇒ 命令 875），但手势起点是固定的
         * 几何起点 `geoStart.y`，命令距离过大会让终点算出屏幕外 ⇒ 手势被系统钳制、实际更短。
         * 故命令距离上限 = `geoStart.y − 本余量`。
         */
        const val ADV_MIN_END_Y = 60
        const val MAX_ROSTER_PAGES = 50
        /** §14 P1：筛选面板翻页上限（防指纹判据失效时死循环）。 */
        const val MAX_FILTER_PAGES = 60
        /**
         * §14 flow 未声明 `dict` 时的回落词典（**仅作默认**，flow 已声明一律以声明为准）。
         * 与 `GoodNames.kindOf` 的键名一致。
         */
        const val DEFAULT_SET_DICT = "mappings.artifactSets"
        const val DEFAULT_CHAR_DICT = "mappings.characters"
        const val MAX_SUBS_5STAR = 4 // 稀有度-词条数规则（用户定稿 2026-09-02）：5★ 恒 4 词条
        const val MAX_SUBS_4STAR = 3 // 4★ 初始 2 最高 3（>3 = 解析异常截断）
        const val STOP_MARKER_RARITY = 4 // 3★/2★ = 止扫标识不解析（flow 语义 rarity < 4，即低于 4★ 止扫）

        /** chain 锚名 → profile rect 路径（P1-a 固定映射；P3 收敛为 chain 机读格式）。 */
        val CHAIN_ANCHOR_PATHS = mapOf(
            "bagpack" to "screens.game_home.anchors.bagpack",
            "character" to "screens.game_home.anchors.character",
            // auto_equip enterScreen 链（flow 字面 (x,y) 为 3200 占位，name 命中即走 profile 机读）
            "game_home→character" to "screens.game_home.anchors.character",
            "圣遗物菜单→tihuan" to "screens.char_interface.artifactTab.tihuan",
            "artifact_tab" to "screens.artifact_backpack.tab",
            "weapon_tab" to "screens.weapon_backpack.tab",
            "tian" to "screens.char_interface.tianBtn",
            // 角色选择弹层收起钮：⚠️ 田在弹层态被弹层底栏覆盖，不能当开关用 → 必须点这个
            "弹层收起" to "zones.char_popup.collapse.rect",
            "filterRoundBtn" to "screens.artifact_backpack.filterRoundBtn",
            "filterSetPlus" to "screens.dialogs.filterPanel.setPlus.rect",
            // 筛选面板的「重置 / 确认」（2026-09-12）：背包会残留筛选状态（artifact_lock 会设筛选）
            // ⇒ artifact_scan 的 enterScreen 链尾用它复位，否则只扫到筛选后的子集（曾停在 21 格/1 页）。
            "filterPanel.reset" to "screens.dialogs.filterPanel.reset",
            "filterPanel.ok" to "screens.dialogs.filterPanel.ok",
            "filterPanel.setPlus" to "screens.dialogs.filterPanel.setPlus.rect",
            "fiveStarPill" to "screens.artifact_backpack.fiveStarToggle.pill",
            // auto_equip setFilter entry=PILL：套装筛选 pill 直达（整条）
            "pill 直达：整条" to "screens.artifact_manage.pill.whole",
            // preReset 方案B 排序复位：排序圆钮 + 排序弹窗确认（2560 confirm center 692,1320）
            "sortBtn" to "screens.artifact_manage.sortBtn",
            "sortPopupConfirm" to "screens.dialogs.sortPopup.confirm",
            // 详情页「替换」快捷钮（char_interface→artifact_manage 的入口）；非管理界面内的 leftBtn
            "tihuan" to "screens.char_interface.artifactTab.tihuan",
            "圣遗物菜单" to "screens.char_interface.leftMenu.圣遗物",
        )
    }
}
