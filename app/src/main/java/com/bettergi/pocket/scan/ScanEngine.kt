package com.bettergi.pocket.scan

import android.os.SystemClock
import android.util.Log
import com.bettergi.pocket.capture.FrameSource
import com.bettergi.pocket.capture.FrameTimeoutException
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
    /** 详情面板当前件名（点击后检测详情是否切换，防读到上一件被去重）。 */
    var lastPieceName: String = ""
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
    private val setDictionary: ArtifactSetDictionary?,
    private val weaponDictionary: WeaponDictionary? = null,
    private val characterDictionary: CharacterDictionary? = null,
    private val listener: ScanListener,
    private val dedupe: Boolean = true,
    /** 翻 N 页早停（调试翻页准确性用；默认不限制）。 */
    private val maxPages: Int = Int.MAX_VALUE,
    /** 单测注入真实时钟用：JVM 里 SystemClock 被 returnDefaultValues 恒返回 0（会挂死轮询）。 */
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    val vars = ScanVars()

    /** 扫描产物（parsePanel emit 收集，endConditions 后由 ScriptRunner 导出）。 */
    val results = ArrayList<GoodArtifact>()
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
            listener.onProgress("total_mismatch", mapOf("total" to total, "scanned" to results.size))
        }
        listener.onFinished(if (vars.stopRequested) "stopWhen" else "completed")
    }

    private suspend fun executeStep(step: JSONObject) {
        when (step.getString("do")) {
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
            else -> Log.w(TAG, "unknown step '${step.getString("do")}', skipped")
        }
    }

    // ---- #1 enterScreen：入口链跳转 + anchor OCR 断言（重试 3 次）----
    private var enterScreenStep: JSONObject? = null

    private suspend fun enterScreen(step: JSONObject, isRetry: Boolean = false) {
        if (enterScreenStep == null) enterScreenStep = step
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
        if (anchor == null || ocr == null) return
        if (anchor.optString("kind") != "ocr") return
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
    private suspend fun dualStateButton(step: JSONObject) {
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
        val cols = profile.gridInt(gridKey, "cols")
        val traverseRows = profile.gridInt(gridKey, "traverseRows")
        val advance = profile.rawObject("grids.$gridKey")!!.getJSONObject("advance")
        val advFrom = advance.getJSONArray("from")
        val advTo = advance.getJSONArray("to")
        var pagesAdvanced = 0

        while (true) {
            if (vars.stopRequested) break
            // 本页：traverseRows 行 × cols 列（visibleRows 第 4 行是滑动锚，不遍历）。
            // scope=cell 止扫（3★/2★ 标识）：置 flag 后当页仍完整遍历（PC 策略「本页后停」），页尾 break。
            for (row in 0 until traverseRows) {
                for (col in 0 until cols) {
                    runVisit(visit, gridKey, col, row, row * cols + col)
                }
            }
            if (vars.stopRequested) break

            // 翻页：三段无惯性滑动 → 自适应 settle（轮询指纹至稳定）→ 指纹比对判到底
            val beforeFrame = freshFrame()
            val before = try {
                VoteJudges.gridFingerprint(beforeFrame, profile, gridKey)
            } finally {
                beforeFrame.release()
            }
            actions.swipe(
                profile.scale(advFrom.getInt(0), profile.scaleX),
                profile.scale(advFrom.getInt(1), profile.scaleY),
                profile.scale(advTo.getInt(0), profile.scaleX),
                profile.scale(advTo.getInt(1), profile.scaleY),
            )
            val afterFrame = awaitGridStable(profile, gridKey)
            val after = try {
                VoteJudges.gridFingerprint(afterFrame, profile, gridKey)
            } finally {
                afterFrame.release()
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
    private suspend fun setFilter(step: JSONObject) {
        val ocrGateway = ocr
        val chain = step.getJSONArray("chain")
        for (i in 0 until chain.length()) {
            val m = Regex("\\((\\d+),(\\d+)\\)").find(chain.getString(i)) ?: continue
            val pt = profile.scalePoint(m.groupValues[1].toInt(), m.groupValues[2].toInt())
            actions.click(pt.x, pt.y)
            delay(CLICK_SETTLE_MS)
        }
        val sel = step.optJSONObject("selectByOcr") ?: return
        val gridKey = sel.optString("grid", "set_filter_popup")
        // 词典按 dict 分类型调用（各自 API 不同）
        val dictKey = sel.optString("dict", "mappings.artifactSets")
        val dictAvailable = when (dictKey) {
            "mappings.artifactSets" -> setDictionary != null
            "mappings.weapons" -> weaponDictionary != null
            else -> false
        }
        if (!dictAvailable) {
            Log.w(TAG, "setFilter: dictionary '$dictKey' not loaded")
            return
        }
        val lookup: (String) -> String? = { text ->
            when (dictKey) {
                "mappings.artifactSets" -> setDictionary?.setKeyByPiece(text)
                else -> weaponDictionary?.keyByName(text)
            }
        }
        val match = sel.optString("match", "exact")
        val target = vars.currentTask?.optString("setName")
            ?: run { Log.w(TAG, "setFilter: vars.currentTask.setName not set (P4 rule injection missing)"); return }
        val grid = profile.rawObject("grids.$gridKey") ?: return
        val cols = grid.getJSONObject("cols")
        val rowYTop = grid.getJSONArray("rowYTop")
        val leftX = cols.getJSONObject("left").getInt("checkboxX")
        val rightX = cols.getJSONObject("right").getInt("checkboxX")
        val leftBox = cols.getJSONObject("left").getJSONArray("nameBox")
        val rightBox = cols.getJSONObject("right").getJSONArray("nameBox")
        if (ocrGateway == null) return
        for (i in 0 until rowYTop.length()) {
            val y = rowYTop.getInt(i)
            val leftRect = FrameRect(leftBox.getInt(0), y, leftBox.getInt(2), y + 120)
            val rightRect = FrameRect(rightBox.getInt(0), y, rightBox.getInt(2), y + 120)
            val leftFrame = freshFrame()
            val leftText = try {
                ocrGateway.readLines(leftFrame, listOf(leftRect)).joinToString(" ")
            } finally { leftFrame.release() }
            val cleanedLeft = StatParser.clean(leftText)
            val leftKey = lookup(cleanedLeft)
            if (leftKey == target) {
                val pt = profile.scalePoint(leftX, y)
                actions.click(pt.x, pt.y)
                delay(CLICK_SETTLE_MS)
                Log.i(TAG, "setFilter matched: $target (BACKPACK left)")
                return
            }
            val rightFrame = freshFrame()
            val rightText = try {
                ocrGateway.readLines(rightFrame, listOf(rightRect)).joinToString(" ")
            } finally { rightFrame.release() }
            val cleanedRight = StatParser.clean(rightText)
            val rightKey = lookup(cleanedRight)
            if (rightKey == target) {
                val pt = profile.scalePoint(rightX, y)
                actions.click(pt.x, pt.y)
                delay(CLICK_SETTLE_MS)
                Log.i(TAG, "setFilter matched: $target (right)")
                return
            }
        }
        Log.w(TAG, "setFilter: target '$target' not found in grid")
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
     * P3 ocrWithRetry：OCR 区域 → 词典模糊匹配 → vars.ocrMatch = key。
     * 重试 N 次（每次重新取帧），重试间 200ms（动画缓冲）。
     * fuzzy：允许 0=精确 / 1=首尾子串包含 / 2=更宽松（取前 fuzzy 字头匹配）。
     */
    private suspend fun ocrWithRetry(step: JSONObject) {
        val ocrGateway = ocr ?: return
        val dict = characterDictionary
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
                val key = when (dictKey) {
                    "mappings.characters" -> dict?.keyByName(cleaned, fuzzy)
                    "mappings.weapons" -> weaponDictionary?.keyByName(cleaned)
                    "mappings.artifactSets" -> setDictionary?.setKeyByPiece(cleaned)
                    else -> null
                }
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
        }
    }

    private suspend fun runVisit(visit: JSONArray, gridKey: String, col: Int, row: Int, index: Int) {
        for (i in 0 until visit.length()) {
            executeVisitStep(visit.getJSONObject(i), gridKey, col, row, index)
        }
    }

    private suspend fun executeVisitStep(step: JSONObject, gridKey: String, col: Int, row: Int, index: Int) {
        when (step.getString("do")) {
                "ifMatch" -> {
                    // 计划匹配闸（artifact_lock）：P4 规则层注入 vars.currentTask；无计划则整段跳过
                    if (vars.currentTask == null) {
                        Log.i(TAG, "ifMatch: no plan injected (vars.currentTask null), then skipped")
                    } else {
                        val thenSteps = step.getJSONArray("then")
                        for (i in 0 until thenSteps.length()) {
                            executeVisitStep(thenSteps.getJSONObject(i), gridKey, col, row, index)
                            if (vars.stopRequested) break
                        }
                    }
                }
                "vote" -> vote(step, gridKey, col, row)
                "click" -> {
                    // fast.at=$cell.center + 两级（fallback ocrAnchor 验证详情面板）
                    val center = profile.cellCenter(gridKey, index)
                    actions.click(center.x, center.y)
                    delay(CLICK_SETTLE_MS)
                    val ocrGateway = ocr // 锁定非空，跨 lambda 保持 smart cast
                    // 详情切换等待：点下一格后详情可能仍显示上一件（模拟器实测 63 格仅 27 件 emit 的主因）。
                    // OCR 面板件名 == 上次件名 → 重点一次 + 600ms；仅圣遗物背包（weapon 面板 rect 不同）。
                    if (ocrGateway != null && gridKey == "artifact_backpack") {
                        val nameRect = runCatching { profile.rect("panels.artifact_backpack.name") }.getOrNull()
                        if (nameRect != null) {
                            val pieceNow = runCatching {
                                val f = freshFrame()
                                try {
                                    StatParser.clean(ocrGateway.readLines(f, listOf(nameRect)).joinToString(" "))
                                } finally { f.release() }
                            }.getOrDefault("")
                            if (pieceNow.isNotEmpty() && pieceNow == vars.lastPieceName) {
                                Log.d(TAG, "detail unchanged ('$pieceNow'), re-click")
                                actions.click(center.x, center.y)
                                delay(600)
                            }
                            if (pieceNow.isNotEmpty()) vars.lastPieceName = pieceNow
                        }
                    }
                    val fallback = step.optJSONObject("fallback")
                    if (fallback != null && ocrGateway != null) {
                        // fallback.kind=ocrAnchor + region="$panels.xxx.name"
                        // 校验详情面板是否打开（OCR region 有非空文本）
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
                "parsePanel" -> parsePanel(step)
                "dialog" -> dialog(step)
                "verify" -> verify(step)
                "emit" -> listener.onProgress("emit", vars.snapshot())
                "stopWhen" -> stopWhen(step)
                else -> Log.w(TAG, "unknown visit step '${step.optString("do")}', skipped")
            }
        }

    // ---- #4 vote：像素投票判据 ----
    private suspend fun vote(step: JSONObject, gridKey: String? = null, col: Int = 0, row: Int = 0) {
        val zoneKey = step.getString("zone")
        val frame = freshFrame()
        try {
            val yShift = if (vars.crafted) Math.round(ZHUSHENG_YSHIFT * profile.scaleY).toInt() else 0
            when (zoneKey) {
                "artifact.card.lockBadge" -> {
                    val r = VoteJudges.cardLockBadge(frame, profile, requireNotNull(gridKey), col, row)
                    vars.gridLocked = r.matched
                }
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
                "weapon.card.starStrip" -> {
                    val r = VoteJudges.weaponStarStrip(frame, profile, requireNotNull(gridKey), col, row)
                    vars.rarity = r.count
                }
                "weapon.card.lockBadge" -> {
                    val r = VoteJudges.cardLockBadgeByZone(frame, profile, requireNotNull(gridKey), "weapon.card.lockBadge", col, row)
                    vars.locked = r.matched
                }
                else -> Log.w(TAG, "vote zone '$zoneKey' not implemented, skipped")
            }
        } finally {
            frame.release()
        }
    }

    // ---- #5 parsePanel：字段槽 OCR → GoodArtifact（含 set_name 词典反推）----
    private suspend fun parsePanel(step: JSONObject) {
        if (ocr == null) {
            Log.d(TAG, "parsePanel skipped: OcrGateway not available")
            return
        }
        val panelKey = step.optString("panel", "artifact_backpack")
        if (panelKey != "artifact_backpack" && panelKey != "weapon_backpack") {
            Log.w(TAG, "parsePanel panel '$panelKey' not supported yet, skipped")
            return
        }
        val frame = freshFrame()
        try {
            if (panelKey == "weapon_backpack") {
                parseWeaponPanel(frame, ocr)
            } else {
                parseArtifactPanel(frame, ocr)
            }
        } finally {
            frame.release()
        }
    }

    private suspend fun parseWeaponPanel(frame: Mat, ocr: OcrGateway) {
        val yShift = 0
        val nameRect = profile.rect("panels.weapon_backpack.name")
        val pieceName = ocr.readLines(frame, listOf(nameRect)).firstOrNull()?.let { StatParser.clean(it) }
        // 武器 slot 文本（角色武器类型）无 GOod 映射——读但不导出
        val mainLabel = ocr.readLines(frame, listOf(profile.rect("panels.weapon_backpack.mainLabel"))).firstOrNull()
        val mainValueText = ocr.readLines(frame, listOf(profile.rect("panels.weapon_backpack.mainValue"))).firstOrNull()
        val levelText = ocr.readLines(frame, listOf(profile.rect("panels.weapon_backpack.level"))).firstOrNull()
        val refineText = ocr.readLines(frame, listOf(profile.rect("panels.weapon_backpack.refine"))).firstOrNull()
        val key = pieceName?.let { weaponDictionary?.keyByName(it) }
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

    private suspend fun parseArtifactPanel(frame: Mat, ocr: OcrGateway) {
        val yShift = if (vars.crafted) Math.round(ZHUSHENG_YSHIFT * profile.scaleY).toInt() else 0

        // 件名 + 部位 + 主词条
        val nameRect = profile.rect("panels.artifact_backpack.name")
        val pieceName = ocr.readLines(frame, listOf(nameRect)).firstOrNull()?.let { StatParser.clean(it) }
        val slotText = ocr.readLines(frame, listOf(profile.rect("panels.artifact_backpack.slot"))).firstOrNull()
        val slotKey = slotText?.let { StatParser.slotKeyOf(it) }
        val mainName = ocr.readLines(frame, listOf(profile.rect("panels.artifact_backpack.mainName"))).firstOrNull()
        val mainValueText = ocr.readLines(frame, listOf(profile.rect("panels.artifact_backpack.mainValue"))).firstOrNull()
        val mainStat = mainName?.let { StatParser.parse(it + (mainValueText ?: "")) }

        // 等级（祝圣面板 yShift）
        val levelText = ocr.readLines(frame, listOf(profile.rect("panels.artifact_backpack.level").shiftedBy(yShift)))
            .firstOrNull()
        vars.level = levelText?.let { StatParser.extractValue(it)?.toInt() } ?: 0

        // 星带逐格（⚠️整带列投影 5★ 会合并，必须逐格；祝圣 yShift 不作用于星带——profiles zhusheng.fields 仅 level/subStats/lock/astral）
        val starCount = countStars(frame)
        // 交叉校验：banner 色分类 vs 星数——以星数为准
        val rarity = if (starCount > 0) starCount else vars.rarity
        vars.rarity = rarity

        // 副词条（祝圣 yShift；4 行）
        val subRects = mutableListOf<FrameRect>()
        val subStatsArr = profile.rawObject("panels.artifact_backpack")!!.getJSONArray("subStats")
        for (i in 0 until subStatsArr.length()) {
            val r = subStatsArr.getJSONArray(i)
            subRects.add(profile.scaleRect(r.getInt(0), r.getInt(1), r.getInt(2), r.getInt(3)).shiftedBy(yShift))
        }
        val subLines = ocr.readLines(frame, subRects)
        var substats = subLines.mapNotNull { StatParser.parse(it) }

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

        // set_name 反推（背包面板 set_name 被遮挡，用户定稿 2026-09-01）
        val setKey = pieceName?.let { setDictionary?.setKeyByPiece(it) }
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
        // 指纹去重入库（Q2 决策）：翻页 settle 半格重叠时同一件可能重复出现。
        // 去重键 = pieceName（词典 276 件全局唯一）；OCR 失败（空名）不判重，兜底入库。
        if (dedupe && !pieceName.isNullOrEmpty() && results.any { it.pieceName == pieceName }) {
            Log.d(TAG, "duplicate artifact skipped: $pieceName")
            return
        }
        results.add(artifact)
        listener.onProgress("artifact", vars.snapshot() + ("piece" to pieceName) + ("idx" to results.size))
    }

    /** 星带逐格采样：格内金像素>100 → 该星点亮（profiles starBand.judge 固化阈值）。星带不随祝圣 yShift 移动。 */
    private fun countStars(frame: Mat): Int {
        val band = profile.rawObject("panels.artifact_backpack")?.optJSONObject("starBand")
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
        // 哨兵保护：rarity=-1（vote 未判定，如 3★ 蓝卡无金/紫 banner）不得触发止扫表达式
        // （rarity<4 对 -1 恒真 → 会把"整页未判定"误判为"全部低星"而立即终止）。
        if (expr.contains("rarity") && vars.rarity < 0) {
            Log.d(TAG, "stopWhen skipped: rarity not judged yet (-1 sentinel)")
            return
        }
        val scope = step.optString("scope", "page")
        if (Expr.eval(expr, vars.exprVars())) {
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
        const val ZHUSHENG_YSHIFT = 63 // profiles: 祝圣 yShift=+63 作用于 level/lock/astral/sub1-4
        const val STAR_GOLD_THRESHOLD = 100 // starBand.judge: 格内金像素>100 → 星亮
        const val ANCHOR_RETRIES = 3
        const val ANCHOR_RETRY_DELAY_MS = 1500L
        // 自适应 settle（替代固定 PAGE_SETTLE）：轮询指纹至连续稳定，自动标定各设备翻页动画时长
        const val SETTLE_POLL_MS = 120L
        const val SETTLE_STABLE_MS = 300L
        const val SETTLE_MAX_MS = 3000L
        const val SETTLE_FRAME_TIMEOUT_MS = 800L
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
