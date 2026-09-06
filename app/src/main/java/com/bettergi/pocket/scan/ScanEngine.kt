package com.bettergi.pocket.scan

import android.os.SystemClock
import android.util.Log
import com.bettergi.pocket.capture.FrameSource
import com.bettergi.pocket.capture.FrameTimeoutException
import com.bettergi.pocket.log.RecognitionLog
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

    fun snapshot(): Map<String, Any?> = mapOf(
        "total" to total,
        "gridLocked" to gridLocked,
        "crafted" to crafted,
        "rarity" to rarity,
        "locked" to locked,
        "favorited" to favorited,
        "level" to level,
        "stopRequested" to stopRequested,
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
     */
    private val clickDelayMs: Long = 250L,
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
    val vars = ScanVars().apply { this.plan = plan }

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
        fun click(x: Int, y: Int, durationMs: Long = 50L): Boolean
        fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean
        /** 系统返回键（GLOBAL_ACTION_BACK）——enterScreen 失败重进前清游戏每日弹窗（签到/物品过期）。 */
        fun back(): Boolean
    }

    suspend fun run() {
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
        listener.onFinished(if (vars.stopRequested) "stopWhen" else "completed")
    }

    private suspend fun executeStep(step: JSONObject) {
        val op = step.getString("do")
        // §13：埋点打在原语分发层——加锁/装备/角色流程接 runner 后自动继承，无需各流程另写
        RecognitionLog.log(logTag, RecognitionLog.Level.I, "步骤 $op")
        when (op) {
            "enterScreen" -> enterScreen(step)
            "dualStateButton" -> dualStateButton(step)
            "readCount" -> readCount(step)
            "pagedGrid" -> pagedGrid(step)
            "dialog" -> dialog(step)
            "ocrWithRetry" -> ocrWithRetry(step)
            "navigate" -> navigate(step)
            "foreach" -> foreach(step)
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

    private suspend fun enterScreen(step: JSONObject, isRetry: Boolean = false) {
        if (enterScreenStep == null) enterScreenStep = step
        // §14 P2 preReset：到达前参数复位（auto_equip D7 定稿「排序→重置→确认」）。
        // 链可写于 step.preReset.chain，缺省取 profiles.screens.artifact_manage.resetChain。
        if (!isRetry) {
            val preReset = step.optJSONObject("preReset")
            if (preReset != null) {
                @Suppress("UNCHECKED_CAST")
                val prChain = preReset.optJSONArray("chain")
                    ?: (profile.rawObject("screens.artifact_manage.resetChain") as? JSONArray)
                if (prChain != null) {
                    Log.i(TAG, "preReset: 执行复位链（${prChain.length()} 步）")
                    for (i in 0 until prChain.length()) clickChainEntry(prChain.getString(i))
                } else {
                    Log.w(TAG, "preReset: 无 chain 且 profiles 无 resetChain，跳过复位")
                }
            }
        }
        val chain = step.getJSONArray("chain")
        for (i in 0 until chain.length()) {
            val anchorName = parseChainAnchor(chain.getString(i))
            val rectPath = CHAIN_ANCHOR_PATHS[anchorName]
            if (rectPath == null) {
                Log.w(TAG, "enterScreen anchor '$anchorName' has no profile mapping, skipped")
                continue
            }
            val rect = profile.rect(rectPath)
            actions.click(rect.centerX, rect.centerY)
            delay(ENTER_SETTLE_MS)
        }
        delay(SCREEN_SETTLE_MS)
        assertAnchor(step.optJSONObject("anchor"), step, isRetry)
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
            "ocr" -> Unit
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
            val path = ref.removePrefix("$")
            // readonly（如 rightBtn 强化/重塑）：只判态、**绝不点击**——误点会进强化/重塑界面
            if (step.optBoolean("readonly", false)) {
                Log.i(TAG, "dualStateButton ref=$path: readonly，仅判态不点击")
                return
            }
            val rectArr = profile.rawObject(path)?.optJSONArray("rect")
            if (rectArr != null && rectArr.length() >= 4) {
                val r = profile.scaleRect(
                    rectArr.getInt(0), rectArr.getInt(1), rectArr.getInt(2), rectArr.getInt(3),
                )
                Log.i(TAG, "dualStateButton ref=$path → 点击 (${r.centerX},${r.centerY})")
                actions.click(r.centerX, r.centerY)
                delay(CLICK_SETTLE_MS)
                val hasUnequip = step.optJSONObject("states")?.keys()?.asSequence()
                    ?.any { it.contains("卸下") } == true
                if (hasUnequip && isCurrentlyEquipped()) {
                    @Suppress("UNCHECKED_CAST")
                    val chain = profile.rawObject("screens.artifact_manage.resetChain") as? JSONArray
                    if (chain != null) {
                        Log.i(TAG, "dualStateButton: 卸下后网格复位（${chain.length()} 步）")
                        for (i in 0 until chain.length()) clickChainEntry(chain.getString(i))
                    }
                }
            } else {
                Log.w(TAG, "dualStateButton ref='$path' 无 rect，跳过")
            }
            return
        }
        val path = step.getString("ref").removePrefix("$")
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
            // 读到 0：大概率停在了弹窗/半透明层——重跑入口链（退出重进）后再读一次
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
        GridAlign.resetBaseline(gridKey)
        var capturedBaseline = false
        // §14 A4 pageSkip 状态：页序号 + 上一页最低等级（背包按等级降序 → 末格即本页最低）
        var pageNo = 0
        var pageMinLevel: Int? = null

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
                for (row in 0 until traverseRows) {
                    for (col in 0 until cols) {
                        runVisit(visit, gridKey, col, row, row * cols + col, pageProfile)
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
            val before = try {
                VoteJudges.gridFingerprint(beforeFrame, profile, gridKey)
            } finally {
                beforeFrame.release()
            }
            if (geoStart != null && dist != null) {
                // advanceStart / advanceDistance 已返回帧坐标，直接用（起手 y 在 |φ|≤半卡高内恒落列表区）
                actions.swipe(geoStart.x, geoStart.y, geoStart.x, geoStart.y - dist!!)
            } else {
                actions.swipe(
                    profile.scale(advFrom.getInt(0), profile.scaleX),
                    profile.scale(advFrom.getInt(1), profile.scaleY),
                    profile.scale(advTo.getInt(0), profile.scaleX),
                    profile.scale(advTo.getInt(1), profile.scaleY),
                )
            }
            var latest = awaitGridStable(profile, gridKey)

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

            val after = try {
                VoteJudges.gridFingerprint(latest, profile, gridKey)
            } finally {
                latest.release()
            }
            if (before == after) {
                Log.i(TAG, "pagedGrid reached end (fingerprint unchanged)")
                break
            }
            pagesAdvanced++
            Log.i(TAG, "pagedGrid advanced to page ${pagesAdvanced + 1} (maxPages=$maxPages)")
            if (pagesAdvanced >= maxPages) {
                Log.i(TAG, "pagedGrid early stop: reached maxPages=$maxPages (debug 早停，用于翻页准确性验证)")
                vars.stopRequested = true
                break
            }
        }
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
        var lastFp = -1L
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
            // 终止判据：网格指纹不变 → peek 消失（不再上弹）
            val frame = freshFrame()
            val fp = try {
                VoteJudges.gridFingerprint(frame, profile, gridKey)
            } finally {
                frame.release()
            }
            if (fp == lastFp) {
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
            lastFp = fp
            round++
        }
        Log.i(TAG, "snap[$gridKey]: 遍历结束 rounds=$round visited=$visited")
    }

    /**
     * §14 P1 **hardMatch**：面板产物与计划任务的硬匹配（artifact_lock / auto_equip 的命中判据）。
     * 比对顺序同 flow 声明 `matchOrder: level→main→sub1-4→set_name`；
     * 副词条按相对容差 [tol]（artifact_lock 定稿 0.100001，auto_equip 0.1）。
     * 缺字段的任务侧不参与比对（宽松匹配，避免未标定字段误杀）。
     */
    private fun hardMatch(task: JSONObject, tol: Double): Boolean {
        val a = results.lastOrNull() ?: return false
        val wantLevel = task.optInt("level", -1)
        if (wantLevel >= 0 && a.level != wantLevel) return false
        val wantSlot = task.optString("slotKey", task.optString("slot", ""))
        if (wantSlot.isNotEmpty() && a.slotKey != wantSlot) return false
        val wantSet = task.optString("setKey", task.optString("setName", ""))
        if (wantSet.isNotEmpty() && a.setKey != wantSet) return false
        val wantMain = task.optString("mainStatKey", "")
        if (wantMain.isNotEmpty() && a.mainStatKey != wantMain) return false
        val wantSubs = task.optJSONArray("substats") ?: return true
        for (i in 0 until wantSubs.length()) {
            val w = wantSubs.optJSONObject(i) ?: continue
            val key = w.optString("key", "")
            if (key.isEmpty()) continue
            val got = a.substats.firstOrNull { it.key == key } ?: return false
            val value = w.optDouble("value", Double.NaN)
            if (!value.isNaN() && Math.abs(got.value - value) > tol * Math.abs(value)) return false
        }
        return true
    }

    /**
     * §14 P2 链路条目 → 点击中心。支持两型写法：
     * `sortBtn(828,1320)`（括号=点）与 `重置[311,1310,483,1363]`（方括号=矩形→取中心）。
     */
    private suspend fun clickChainEntry(entry: String) {
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
    private var charName = ""
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
            val rawName = StatParser.clean(texts.getOrElse(0) { "" })
            val level = Regex("(\\d+)").find(texts.getOrElse(1) { "" })
                ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val header = StatParser.clean(texts.getOrElse(2) { "" })
            // header 形如「冰元素／桑多涅」→ 取「元素」前缀
            val element = Regex("^(.+?)元素").find(header)?.groupValues?.getOrNull(1)
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
        resultsCharacters.add(c)
        Log.i(TAG, "char emit #${resultsCharacters.size}: ${c.name} lv=${c.level} c${c.constellation} t=${c.talents}")
        listener.onProgress(
            "character",
            vars.snapshot() + ("name" to c.name) + ("idx" to resultsCharacters.size),
        )
        // 重置草稿，准备下一个角色
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
     * 翻页后自适应 settle：轮询网格指纹，连续 [SETTLE_STABLE_MS] 不变即视为动画结束。
     * 自动测出各设备真实 settle 耗时（logcat 直读，回填 profiles.advance.settle），
     * 替代固定延时——不同设备动画时长不同，固定值要么浪费要么不足。
     * @return 稳定后的最新帧（调用方负责 release）
     */
    private suspend fun awaitGridStable(profile: ScreenProfile, gridKey: String): Mat {
        val start = clock()
        var lastFp = -1L
        var stableSince = -1L
        var latest: Mat? = null
        try {
            while (clock() - start < SETTLE_MAX_MS) {
                delay(SETTLE_POLL_MS)
                latest?.release()
                latest = freshFrame(SETTLE_FRAME_TIMEOUT_MS)
                val fp = VoteJudges.gridFingerprint(latest, profile, gridKey)
                if (fp == lastFp) {
                    if (stableSince < 0) stableSince = clock()
                    if (clock() - stableSince >= SETTLE_STABLE_MS) {
                        Log.i(TAG, "page settle: ${clock() - start}ms (stable)")
                        return latest
                    }
                } else {
                    stableSince = -1L
                    lastFp = fp
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
        // 1. 检测弹窗（OCR ref.text 区域有非空文本 → 弹窗存在）
        val ocrGateway = ocr
        val textRect = profile.rect("$ref.text")
        val frame = freshFrame()
        val shown = try {
            ocrGateway.readLines(frame, listOf(textRect)).joinToString(" ").isNotBlank()
        } finally { frame.release() }
        if (!shown) {
            Log.d(TAG, "dialog $ref not visible")
            return
        }
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
     * §14 P1：筛选目标集合。优先 `vars.currentTask.setName`；其次 `currentTask.targets[]`（加锁多目标）；
     * 再退 `vars.plan[].setName`。空则记 warn（P4 未注入计划）。
     */
    private fun filterTargets(): Set<String> {
        val out = LinkedHashSet<String>()
        val task = vars.currentTask
        if (task != null) {
            task.optString("setName").takeIf { it.isNotEmpty() }?.let(out::add)
            val arr = task.optJSONArray("targets")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, "")
                    if (s.isNotEmpty()) out.add(s)
                    else arr.optJSONObject(i)?.optString("setName")?.takeIf { it.isNotEmpty() }?.let(out::add)
                }
            }
        }
        vars.plan?.forEach { t ->
            t.optString("setName").takeIf { it.isNotEmpty() }?.let(out::add)
        }
        return out
    }

    private suspend fun setFilter(step: JSONObject) {
        val ocrGateway = ocr
        val chain = step.getJSONArray("chain")
        for (i in 0 until chain.length()) {
            val m = Regex("\\((\\d+),(\\d+)\\)").find(chain.getString(i)) ?: continue
            val pt = profile.scalePoint(m.groupValues[1].toInt(), m.groupValues[2].toInt())
            actions.click(pt.x, pt.y)
            delay(CLICK_SETTLE_MS)
        }
        // §12.4-① 开始筛选前先清空已选条件（filterPanel.reset）
        val resetPt = filterPanelCenter("reset")
        if (resetPt != null) {
            Log.i(TAG, "setFilter: 清空已选条件 reset=(${resetPt[0]},${resetPt[1]})")
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
        val pending = LinkedHashSet(targets)
        val advance = grid.optJSONObject("advance")
        var lastFp = -1L
        var guard = 0
        while (pending.isNotEmpty() && guard++ < MAX_FILTER_PAGES) {
            if (vars.stopRequested) break
            for (i in 0 until rowYTop.length()) {
                if (pending.isEmpty()) break
                val y = rowYTop.getInt(i)
                val rowCenterY = y + rowHeight / 2
                matchFilterRow(ocrGateway, lookup, leftBox, y, rowHeight, leftX, rowCenterY, pending, "left")
                if (pending.isEmpty()) break
                matchFilterRow(ocrGateway, lookup, rightBox, y, rowHeight, rightX, rowCenterY, pending, "right")
            }
            if (pending.isEmpty() || vars.stopRequested) break
            // §12.4-⑤ 本页未点完 → 翻页继续
            if (advance == null) break
            val advFrom = advance.getJSONArray("from")
            val advTo = advance.getJSONArray("to")
            val fpBefore = fingerprint(gridKey)
            actions.swipe(
                profile.scale(advFrom.getInt(0), profile.scaleX),
                profile.scale(advFrom.getInt(1), profile.scaleY),
                profile.scale(advTo.getInt(0), profile.scaleX),
                profile.scale(advTo.getInt(1), profile.scaleY),
            )
            val stable = awaitGridStable(profile, gridKey)
            val fpAfter = try {
                VoteJudges.gridFingerprint(stable, profile, gridKey)
            } finally {
                stable.release()
            }
            if (fpAfter == lastFp || fpAfter == fpBefore) {
                Log.i(TAG, "setFilter: 筛选列表到底（指纹不变），停止翻页；未点完=$pending")
                break
            }
            lastFp = fpAfter
        }
        if (pending.isNotEmpty()) Log.w(TAG, "setFilter: 目标未全部点选，剩余=$pending")
        confirmFilter(step)
    }

    /** 取当前帧网格指纹（失败返回 -1）。 */
    private suspend fun fingerprint(gridKey: String): Long {
        val frame = try {
            freshFrame()
        } catch (_: Exception) {
            return -1L
        }
        return try {
            VoteJudges.gridFingerprint(frame, profile, gridKey)
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
    ) {
        val rect = FrameRect(box.getInt(0), y, box.getInt(2), y + rowHeight)
        val frame = freshFrame()
        val text = try {
            gateway.readLines(frame, listOf(rect)).joinToString(" ")
        } finally {
            frame.release()
        }
        val key = lookup(StatParser.clean(text)) ?: return
        if (key !in pending) return
        if (checkboxChecked(checkboxX, rowCenterY)) {
            Log.i(TAG, "setFilter: $key ($side) 已勾选，跳过（防误取消）")
            pending.remove(key)
            return
        }
        clickAt(checkboxX, rowCenterY)
        pending.remove(key)
        Log.i(TAG, "setFilter matched: $key ($side)")
    }

    /** §12.4-⑥ 确认筛选并关闭；tailGuard：锚点仍在则再点一次 ok。 */
    private suspend fun confirmFilter(step: JSONObject) {
        val okPt = filterPanelCenter("ok")
        if (okPt == null) {
            Log.w(TAG, "setFilter: filterPanel.ok 未标定，无法确认")
            return
        }
        clickAt(okPt[0], okPt[1])
        // tailGuard：filterPanel 锚点仍显示 → 再点一次 ok（artifact_lock 双层关闭）
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
                profile.rect("char_interface.leftMenu.$name")
            } catch (e: Exception) {
                Log.w(TAG, "navigate: menu '$name' missing in profiles.char_interface.leftMenu (${e.message})")
                continue
            }
            actions.click(rect.centerX, rect.centerY)
            delay(CLICK_SETTLE_MS)
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
                "artifact.panel.lock" -> VoteJudges.panelLock(frame, profile, 0).matched
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
        val vop = step.getString("do")
        // §13：D 级逐格埋点（默认关闭——一页 21 格会刷屏，由 RecognitionLog.verbose 开启）
        RecognitionLog.log(logTag, RecognitionLog.Level.D, "格($col,$row) $vop")
        when (vop) {
                "ifMatch" -> {
                    // 计划匹配闸（artifact_lock）：P4 规则层注入 vars.currentTask；无计划则整段跳过
                    if (vars.currentTask == null) {
                        Log.i(TAG, "ifMatch: no plan injected (vars.currentTask null), then skipped")
                    } else {
                        val thenSteps = step.getJSONArray("then")
                        for (i in 0 until thenSteps.length()) {
                            executeVisitStep(thenSteps.getJSONObject(i), gridKey, col, row, index, ctx, prof, snapMode)
                            if (vars.stopRequested) break
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
                    // fast.at=$cell.center + 两级（fallback ocrAnchor 验证详情面板）
                    // prof = 页面网格偏移视图（§12.5）：点击坐标随相位平移，panel 类坐标不受影响
                    val center = prof.cellCenter(gridKey, index)
                    actions.click(center.x, center.y)
                    if (gridKey == "artifact_backpack") {
                        // yas clickDelay 同构：固定短睡 → 抓一帧管到底（vote/parsePanel 复用），
                        // **不做切换确认**——stale 读由内容指纹去重兜底（浪费周期 ~clickDelay 而非 1.5s）。
                        // 实测面板内容稳定点 ~250ms（+150ms 指纹已变但未稳，+270ms 稳定）
                        delay(clickDelayMs)
                        ctx.release() // 面板切换，上一格共享帧作废
                        ctx.frame = freshFrame()
                    } else {
                        // weapon 等其余 grid：保留固定 settle + fallback 面板打开校验（旧路径）
                        delay(CLICK_SETTLE_MS)
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
                                        actions.click(center.x, center.y)
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
                        parseCharacterPanel(step, ctx)
                    } else {
                        parsePanel(step, ctx)
                    }
                }
                "navigate" -> {
                    navigate(step)
                    // §14 P3：navigate 后判读命座/天赋（character_scan.read 数组）
                    readAfterNavigate(step)
                }
                "dialog" -> dialog(step)
                "verify" -> verify(step)
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
                    vars.locked = VoteJudges.panelLock(frame, profile, yShift).matched
                    if (step.optString("as") == "curLock") vars.curLock = vars.locked
                }
                "artifact.panel.astral" -> {
                    vars.favorited = VoteJudges.panelAstral(frame, profile, yShift).matched
                }
                "artifact.rarity" -> {
                    vars.rarity = VoteJudges.rarityFromBanner(frame, profile)
                }
                else -> Log.w(TAG, "vote zone '$zoneKey' not implemented, skipped")
            }
        } finally {
            if (ctx?.frame !== frame) frame.release()
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
        val nameRect = profile.rect("panels.weapon_backpack.name")
        val pieceName = ocr.readLines(frame, listOf(nameRect)).firstOrNull()?.let { StatParser.clean(it) }
        // 武器 slot 文本（角色武器类型）无 GOod 映射——读但不导出
        val mainLabel = ocr.readLines(frame, listOf(profile.rect("panels.weapon_backpack.mainLabel"))).firstOrNull()
        val mainValueText = ocr.readLines(frame, listOf(profile.rect("panels.weapon_backpack.mainValue"))).firstOrNull()
        val levelText = ocr.readLines(frame, listOf(profile.rect("panels.weapon_backpack.level"))).firstOrNull()
        val refineText = ocr.readLines(frame, listOf(profile.rect("panels.weapon_backpack.refine"))).firstOrNull()
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
        val lines = ocr.readRois(frame, listOf(nameRect, slotRect, mainNameRect, mainValueRect, levelRect) + subRects)

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
            val raw = profile.rawObject(base)?.optJSONArray("setName")
            if (raw != null && raw.length() >= 4) {
                val r = profile.scaleRect(raw.getInt(0), raw.getInt(1), raw.getInt(2), raw.getInt(3))
                val t = StatParser.clean(ocr.readRois(frame, listOf(r)).getOrNull(0) ?: "")
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
        val expr = step.getString("expr")
        // §14 P1：panelMatch(target, tol=0.1) —— Expr 不支持函数调用，此处直接求值 [hardMatch]
        if (expr.contains("panelMatch(")) {
            val task = vars.currentTask
            if (task == null) {
                Log.d(TAG, "panelMatch skipped: 无 currentTask（P4 未注入计划）")
                return
            }
            val tol = Regex("tol\\s*=\\s*([0-9.]+)").find(expr)?.groupValues?.getOrNull(1)
                ?.toDoubleOrNull() ?: 0.1
            if (hardMatch(task, tol)) {
                vars.stopRequested = true
                Log.i(TAG, "stopWhen triggered (panelMatch tol=$tol): $expr")
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
        if (expr.contains("roster[0]") || (expr.contains("name") && expr.contains("=="))) {
            val first = resultsCharacters.firstOrNull()?.name
            if (!first.isNullOrEmpty() && charName == first) {
                vars.stopRequested = true
                Log.i(TAG, "stopWhen triggered (首名重现): 当前=$charName 首名=$first 已入库=${resultsCharacters.size}")
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
            Log.i(TAG, "stopWhen triggered ($scope): $expr")
        }
    }

    /**
     * 动作后取新帧：帧阈值由 ActionGateway 实现侧 markActionAt 管理（P0 机制），
     * afterTimestampMs 传 0 仅作占位；超时回退最新帧（R1：单应用共享静止停帧兜底）。
     */
    private suspend fun freshFrame(timeoutMs: Long = 2500L): Mat =
        try {
            frameSource.grabFresh(0L, timeoutMs)
        } catch (e: FrameTimeoutException) {
            Log.w(TAG, "grabFresh timeout, falling back to latest frame")
            frameSource.acquireLatestBgr()?.bgr ?: throw e
        }

    internal companion object {
        const val TAG = "BetterGI.Scan"
        const val ENTER_SETTLE_MS = 1200L
        const val SCREEN_SETTLE_MS = 1500L
        const val CLICK_SETTLE_MS = 600L
        const val STAR_GOLD_THRESHOLD = 100 // starBand.judge: 格内金像素>100 → 星亮
        const val ANCHOR_RETRIES = 3
        const val ANCHOR_RETRY_DELAY_MS = 1500L
        // 自适应 settle（替代固定 PAGE_SETTLE）：轮询指纹至连续稳定，自动标定各设备翻页动画时长
        const val SETTLE_POLL_MS = 120L
        const val SETTLE_STABLE_MS = 300L
        const val SETTLE_MAX_MS = 3000L
        const val SETTLE_FRAME_TIMEOUT_MS = 800L
        // §12.5 相位校正（2026-09-05）：|φ| 超过卡片半高（pagedGrid 内按 geometry 计算）才补滑，最多 2 次
        const val MAX_FINISH_SWIPES = 2
        /** §14 A3：snap 遍历最大轮数兜底（防指纹判据失效时死循环；正常由 peek 消失终止）。 */
        const val MAX_SNAP_ROUNDS = 400
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
            "artifact_tab" to "screens.artifact_backpack.tab",
            "weapon_tab" to "screens.weapon_backpack.tab",
        )
    }
}
