package com.bettergi.pocket.scan

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Mat
import com.bettergi.pocket.scan.ScanEngine.Companion.ANCHOR_POLL_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.ANCHOR_RETRIES
import com.bettergi.pocket.scan.ScanEngine.Companion.ANCHOR_RETRY_DELAY_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.CHAIN_ANCHOR_PATHS
import com.bettergi.pocket.scan.ScanEngine.Companion.CLICK_SETTLE_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.ENTER_SETTLE_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.ENTRY_IDEMPOTENT_POLL_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.FILTER_PANEL_BACK_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.FILTER_PANEL_POLL_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.FILTER_PANEL_STEP_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.PANEL_SETTLE_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.RETURN_HOME_ATTEMPTS
import com.bettergi.pocket.scan.ScanEngine.Companion.RETURN_HOME_BLIND_MAX
import com.bettergi.pocket.scan.ScanEngine.Companion.RETURN_HOME_SETTLE_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.SCREEN_SETTLE_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.SETTLE_FRAME_TIMEOUT_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.TAG
import com.bettergi.pocket.log.RecognitionLog
import com.bettergi.pocket.notice.NoticeCenter
import com.bettergi.pocket.recognition.name.NameMatcher

/*
 * Stage 3.3 步④：ScanNavigationDomain（do 原语按族拆分，扩展函数，函数体一字未改）。
 */

internal fun ScanEngine.atHomeScreen(frame: Mat): Boolean? {
    val rb = TemplateMatcher.match(frame, "home.bagpack", profile)
    if (rb.score < 0) return null
    return rb.matched || TemplateMatcher.match(frame, "home.character", profile).matched
}

/** 返回钮落点 + 是否来自 `ui.return` 模板命中（false = 走兜底坐标，属**盲点**）。 */
internal fun ScanEngine.returnButtonPoint(frame: Mat): Pair<FramePoint, Boolean> {
    val r = TemplateMatcher.match(frame, "ui.return", profile)
    // 模板与 ROI 换算同 TemplateMatcher：按高比缩放，x 右锚
    val s = frame.rows().toDouble() / 1440.0
    if (r.matched) return FramePoint(r.x + (39 * s).toInt(), r.y + (39 * s).toInt()) to true
    // fallback 优先 profile 机读 returnBtn（2560 返回钮右缘边距与 3200 不同，
    // cols-248 旧推算在 2560 偏左 120px → 永点不中 → 归位失败）
    val retRect = runCatchingCancellable { profile.zone("ui.return.btn") }.getOrNull()?.getJSONArray("rect")
    val px = if (retRect != null && retRect.length() >= 4)
        profile.scale((retRect.getInt(0) + retRect.getInt(2)) / 2, profile.scaleX)
    else ((2952 - 3200) * s + frame.cols()).toInt()
    val py = if (retRect != null && retRect.length() >= 4)
        profile.scale((retRect.getInt(1) + retRect.getInt(3)) / 2, profile.scaleY)
    else (80 * s).toInt()
    return FramePoint(px, py) to false
}

/**
 * §15 归位主界面：循环点右上角「返回/关闭」钮直至主界面（背包钮 + 角色钮同时可见）。
 * 对应 GOODScanner `return_to_main_ui(max_attempts)`：先判在 home → 直接返回；
 * 否则点返回 → 等 900ms → 再判，最多 [RETURN_HOME_ATTEMPTS] 次。
 *
 * ★★ 2026-09-29 #139 加两道刹车（事故驱动）★★
 * 事故：探针从「角色详情页」起跑，本函数连点 8 次右上角都没认定"已回主界面"，
 * 随后 `enterScreen` 又在同一角落盲点一次（大世界里那个位置是**派蒙菜单**钮），
 * 整轮 abort 后画面已漂到菜单层，游戏最终掉回 BlueStacks 桌面。
 * 该轮帧是新鲜的（无 `grabFresh` 回退）⇒ **不是陈旧帧问题，就是盲点本身**：
 *  - 刹车①**无进展即停**：点完前后整帧缩略图几乎不变（[reachedEnd]）⇒ 再点同一个位置没有意义，
 *    立刻停手（原实现会把这个动作重复点满 [RETURN_HOME_ATTEMPTS] 次）。
 *  - 刹车②**盲点封顶**：走**兜底坐标**（`ui.return` 模板没命中）时最多点
 *    [RETURN_HOME_BLIND_MAX] 次。模板命中=「这确实是个返回钮」，兜底坐标=「右上角那一带」，
 *    两者可信度不是一回事，不该共用同一个次数上限。
 *  - 放弃时不再只 warn：ERROR + NoticeCenter + `stopReason="returnHomeStuck"` 终止本轮 ——
 *    归位失败意味着后面每一步都在未知界面上点，**继续跑比停下来危险**。
 */
internal suspend fun ScanEngine.returnToHome(maxAttempts: Int = RETURN_HOME_ATTEMPTS) {
    // 模板未注册（干跑单测/未下发模板）→ 无法判据，直接放弃（且不消耗帧，避免打乱帧序列）
    if (!TemplateMatcher.hasTemplate("home.bagpack")) {
        Log.w(TAG, "returnToHome: home.bagpack 模板未注册，跳过归位")
        return
    }
    var attempt = 0
    var blindClicks = 0
    while (attempt++ < maxAttempts) {
        val frame = freshFrame()
        val atHome: Boolean?
        val pt: FramePoint
        val fromTemplate: Boolean
        val beforeThumb: ByteArray?
        try {
            atHome = atHomeScreen(frame)
            // 模板未注册/配置未加载时 match 返回 score=-1 → 无法判据，直接放弃归位
            // （干跑单测与未下发模板的环境不应产生无意义点击）
            if (atHome == null) {
                Log.w(TAG, "returnToHome: home 模板不可用，跳过归位")
                return
            }
            beforeThumb = VoteJudges.screenThumb(frame)
            val (p, tmpl) = returnButtonPoint(frame)
            pt = p
            fromTemplate = tmpl
        } finally {
            frame.release()
        }
        if (atHome) {
            Log.i(TAG, "returnToHome: 已在主界面（第 $attempt 次判定）")
            return
        }
        if (!fromTemplate) {
            blindClicks++
            if (blindClicks > RETURN_HOME_BLIND_MAX) {
                val why = "归位已盲点 $RETURN_HOME_BLIND_MAX 次（返回钮模板始终未命中）仍不在主界面"
                Log.e(TAG, "returnToHome: $why ⇒ 停手，不再点击")
                NoticeCenter.error("$why，已中止本轮以防误点")
                vars.stopRequested = true
                vars.stopReason = "returnHomeStuck"
                return
            }
        }
        Log.i(
            TAG,
            "returnToHome: 第 $attempt 次点返回 (${pt.x},${pt.y})" +
                if (fromTemplate) "" else " [盲点 $blindClicks/$RETURN_HOME_BLIND_MAX]",
        )
        clickAt(pt.x, pt.y)
        delay(RETURN_HOME_SETTLE_MS)
        val afterThumb = try {
            val f = freshFrame()
            try {
                VoteJudges.screenThumb(f)
            } finally {
                f.release()
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            null
        }
        if (reachedEnd(beforeThumb, afterThumb)) {
            Log.e(TAG, "returnToHome: 点击后画面无变化 ⇒ 判定无进展，停手（点的位置不是返回钮）")
            NoticeCenter.error("自动归位点击后画面无变化，已停手（避免继续盲点漂移）")
            vars.stopRequested = true
            vars.stopReason = "returnHomeStuck"
            return
        }
    }
    Log.e(TAG, "returnToHome: $maxAttempts 次未达主界面 ⇒ 停手（首步 enterScreen 会再校验，但不会再盲点入口）")
    NoticeCenter.error("自动归位 $maxAttempts 次未达主界面，已停止点击")
    vars.stopRequested = true
    vars.stopReason = "returnHomeStuck"
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
internal suspend fun ScanEngine.filterReset(step: JSONObject) {
    val gateway = ocr
    if (gateway == null) {
        Log.w(TAG, "filterReset: OCR 未就绪，跳过筛选复位")
        return
    }
    fun rectOf(key: String): FrameRect? =
        step.optString(key).takeIf { it.isNotEmpty() }?.let { p ->
            runCatchingCancellable { profile.rect(p.removePrefix("$")) }.getOrNull()
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
        runCatchingCancellable { actions.back() }
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
internal suspend fun ScanEngine.assertScreen(step: JSONObject) {
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
        runCatchingCancellable { profile.rect(titlePath) }.getOrNull()?.let { checks += it to Regex(expect) }
    }
    step.optJSONArray("alts")?.let { arr ->
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val e = o.optString("expect")
            val r = runCatchingCancellable { profile.rect(o.optString("rect").removePrefix("\$")) }.getOrNull()
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
            runCatchingCancellable { actions.back() }
            delay(FILTER_PANEL_BACK_MS)
        }
        // ★ #136：**不按 BACK**，直接放弃本项。"back" 这一支在"我们可能已经不在目标界面上了"
        //   的时候是有害的 —— 多按的那一下正是把流程带出角色页的动作。
        "abortItem" -> {
            Log.w(TAG, "assertScreen 失败 ⇒ 放弃本项（不再点任何格子），屏上不是 '$expect' 所在界面")
            vars.abortItem = true
        }
        "abort" -> throw ScanAbortedException("assertScreen not reached: $expect")
        else -> Log.w(TAG, "assertScreen 失败 ⇒ 继续（onFail=continue）")
    }
}

/**
 * 角色筛选面板每步等待的**生效值**：`timing=panel=NNNN` 优先，未给则用 [PANEL_SETTLE_MS]（与改动前逐位一致）。
 */
internal suspend fun ScanEngine.enterScreen(step: JSONObject, isRetry: Boolean = false) {

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
    // ★★ 2026-09-19 真机修：**入口幂等化**（本轮 3/4 次入口失败的对症修法）★★
    //   真机事故：背包**已开着**时，本函数仍会去点 bagpack 按钮 —— 那个按钮是**切换**，
    //   于是把背包**关掉** ⇒ 紧随其后的锚点读到世界画面（`广甲`）⇒ assertAnchor 三次失败 ⇒ 整轮 abort。
    //   同类：弹窗遮挡时点击落空。修法：开链前先**短轮询锚点**，已命中就直接返回
    //   （不点击、不再断言）⇒ "已经在目标界面/刚打开"都不会被切错。
    val anchorObjPre = step.optJSONObject("anchor")
    if (TimingOverrides.entryIdempotent && anchorObjPre != null &&
        pollAnchorReady(anchorObjPre, ENTRY_IDEMPOTENT_POLL_MS)
    ) {
        Log.i(TAG, "enterScreen: **已在目标界面（入口幂等）⇒ 跳过入口链**，不做任何点击")
        return
    }
    // ⚠️ 开链前强制复位穿透：链式连点时逐点 passthrough 的**延时恢复**可能还没跑，
    //    悬浮窗若仍可触摸会把链点击吞掉（真机实测：链点击全部无效、锚点读到世界内容而中止）。
    actions.resetPassthrough()
    // ★ 2026-09-29 #139：**入口点击的前置判据** —— 这条链的语义是 `game_home→character`，
    //   即"从主界面出发"。归位没成功时，下面原本照样按坐标盲点入口：真机实测那一轮
    //   点在 (2447,80)（大世界里那个位置是**派蒙菜单**钮）⇒ 画面漂进菜单层，整轮 abort。
    //   判得出且**不在主界面** ⇒ 一次都不点，放弃本项；判不出来（模板不可用/干跑）⇒ 照旧点。
    //   ⚠️ 只对**以主界面起步**的链生效：`artifact_tab` / `weapon_tab` 这类链的语义是
    //   "已经在背包里、切个页签"，它们本来就不在主界面（artifact_scan/weapon_scan 各有一条），
    //   一视同仁地拦会把这两条流程整条打死。
    val startsAtHome = parseChainAnchor(chain.getString(0)).startsWith("game_home")
    runCatchingCancellable { if (startsAtHome) freshFrame() else null }.getOrNull()?.let { f ->
        try {
            if (atHomeScreen(f) == false) {
                Log.e(TAG, "enterScreen: 当前不在主界面 ⇒ 不点入口链（防在未知界面上盲点）")
                RecognitionLog.log(
                    logTag,
                    RecognitionLog.Level.W,
                    "入口前置不满足：不在主界面 ⇒ 不点入口，放弃本项",
                )
                vars.abortItem = true
            }
        } finally {
            f.release()
        }
    }
    if (vars.abortItem) return

    // 预解析每个链入口的落点：**最后一个可解析入口**的 settle 交给"锚点就绪轮询"（见下），
    // 其余入口的 settle 保持 ENTER_SETTLE_MS（点早了会点空 ⇒ 链失败，这一侧不能省）。
    fun chainRectOf(i: Int): FrameRect? {
        val anchorName = parseChainAnchor(chain.getString(i))
        val rectPath = CHAIN_ANCHOR_PATHS[anchorName] ?: return null
        return runCatchingCancellable { profile.rect(rectPath) }.getOrNull()
    }
    val lastIdx = (chain.length() - 1 downTo 0).firstOrNull { chainRectOf(it) != null } ?: -1
    for (i in 0 until chain.length()) {
        val anchorName = parseChainAnchor(chain.getString(i))
        val rectPath = CHAIN_ANCHOR_PATHS[anchorName]
        if (rectPath == null) {
            Log.w(TAG, "enterScreen anchor '$anchorName' has no profile mapping, skipped")
            continue
        }
        val rect = runCatchingCancellable { profile.rect(rectPath) }.getOrElse {
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
    // ★ 2026-09-19 真机标定：入口预算可被 `timing=anchor=NNNN` 覆盖
    //   （真机开背包 2~4s > 默认 2700ms ⇒ anchor 未命中整轮 abort）
    val budgetMs = if (TimingOverrides.anchorBudgetMs > 0) {
        TimingOverrides.anchorBudgetMs
    } else {
        (if (lastIdx >= 0) ENTER_SETTLE_MS else 0L) + SCREEN_SETTLE_MS
    }
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
internal suspend fun ScanEngine.pollAnchorReady(anchor: JSONObject?, budgetMs: Long): Boolean {
    val gateway = ocr ?: return false
    if (anchor == null || anchor.optString("kind") != "ocr") return false
    val expect = anchor.optString("expect")
    if (expect.isEmpty()) return false
    val rect = runCatchingCancellable { profile.rect(anchor.getString("rect").removePrefix("$")) }.getOrNull() ?: return false
    val regex = Regex(expect)
    val fallback = Regex("\\d+\\s*/\\s*\\d+")
    val prefixStrict = anchor.optBoolean("prefixStrict", false)
    val zhWords = Regex("[\\u4e00-\\u9fa5]{2,}").findAll(expect).map { it.value }.toList()
    var waited = 0L
    while (waited < budgetMs) {
        delay(ANCHOR_POLL_MS)
        waited += ANCHOR_POLL_MS
        val f = try { freshFrame(SETTLE_FRAME_TIMEOUT_MS) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: continue
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
internal suspend fun ScanEngine.assertAnchor(anchor: JSONObject?, step: JSONObject, isRetry: Boolean) {
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
        runCatchingCancellable { actions.back() }
        delay(1200)
        enterScreen(step, isRetry = true)
        return // 重跑内 assertAnchor 已再验证；仍失败则抛
    }
    // 断言失败 = 界面未到达：继续扫描只会全错，终止比带病跑偏好
    throw ScanAbortedException("enterScreen anchor not reached: /$expect/")
}

internal fun ScanEngine.parseChainAnchor(entry: String): String {
    // "game_home→bagpack(2786,36)" → "bagpack"；括号内坐标为人工核对提示，机读走 profile
    return entry.substringBefore('(').substringAfterLast('→').trim()
}

// ---- #6 dualStateButton：判态（pill 底色）+ ensure ----
/**
 * 管理面板「已装备」标识：`panels.artifact_manage.equipped` ROI 有字 → 该件正被穿戴
 * （决定 leftBtn 当前是「卸下」还是「替换」）。OCR 不可用/读空按未装备处理。
 */
internal suspend fun ScanEngine.readAfterNavigate(step: JSONObject) {
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
internal suspend fun ScanEngine.dialog(step: JSONObject) {
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
internal suspend fun ScanEngine.safetyGuard(): Boolean {
    val gateway = ocr ?: return true
    val rect = runCatchingCancellable { profile.rect("screens.dialogs.quitConfirm.title") }.getOrNull() ?: return true
    val expect = runCatchingCancellable {
        profile.rawObject("screens.dialogs.quitConfirm")?.optString("expect", "退出")
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "退出"
    val frame = try {
        freshFrame()
    } catch (e: CancellationException) { throw e } catch (_: Exception) {
        return true
    }
    val text = try {
        gateway.readLines(frame, listOf(rect)).joinToString(" ")
    } catch (e: CancellationException) { throw e } catch (_: Exception) {
        ""
    } finally {
        frame.release()
    }
    if (!text.contains(expect)) return true
    Log.e(TAG, "SAFETY: 检测到危险界面（'$text'）⇒ 立即中止流程，绝不再点击")
    // ⚠️ RecognitionLog.Level 只有 I/D/W（无 E）⇒ 这里用 W；严重性由下一行 NoticeCenter.error 承担
    RecognitionLog.log(logTag, RecognitionLog.Level.W, "安全守卫触发：当前界面是「$text」⇒ 已中止（不点击）")
    NoticeCenter.error("安全守卫：检测到「$text」，已中止自动流程以防误点")
    vars.stopRequested = true
    vars.stopReason = "safety"
    return false
}

internal suspend fun ScanEngine.clickAt(x: Int, y: Int, settleMs: Long = CLICK_SETTLE_MS) {
    // 每次点击前过安全守卫（中止则不再点击）
    if (!safetyGuard()) return
    val pt = profile.scalePoint(x, y)
    actions.click(pt.x, pt.y)
    delay(settleMs)
}

/**
 * 处置「加锁确认」提示框（★ 2026-09-18）。
 *
 * 真机实测：点详情面板锁图标**加锁**会弹「提示 / 已锁定此装备。/ 锁定的装备无法被用作强化、精炼素材消耗。」
 * +「确认」按钮；**解锁不弹**。该框是**全屏模态**：
 * ① 盖住锁图标 ⇒ 之后的回读 gold=0，被误判成"点击未生效" ⇒ 补点第二次；
 * ② `verify` 也恒 FAILED；
 * ③ 框一直挂着，把之后所有点击（含翻页/下一个目标）全部吃掉。
 * ⇒ 这正是"**能解锁、不能上锁**"的真因（**不是坐标问题** —— 坐标已用真实面板离线复核过）。
 * @return true = 命中并点了确认
 */
internal suspend fun ScanEngine.dismissLockConfirm(timeoutMs: Long = 12000L): Boolean {
    // ⚠️ 判据用**像素白底占比**，不用中文 OCR —— 实测该弹框的正文 ROI 设备端 OCR 读空（''），
    //   而同一帧该区域白底像素 ≈357560（占 54%）、无框时是背包网格（暗）。
    //   本类已被"设备端 OCR 对多行/长文本 ROI 不可靠"坑过两次（面板标题、这里），故改像素。
    val obj = profile.rawObject("screens.dialogs.lockConfirm") ?: run {
        Log.w(TAG, "dismissLockConfirm: profile 缺 screens.dialogs.lockConfirm ⇒ 跳过")
        return false
    }
    val probe = runCatchingCancellable { profile.rect("screens.dialogs.lockConfirm.probe") }.getOrNull() ?: run {
        Log.w(TAG, "dismissLockConfirm: 缺 probe ⇒ 跳过")
        return false
    }
    val confirm = runCatchingCancellable { profile.rect("screens.dialogs.lockConfirm.confirm") }.getOrNull() ?: run {
        Log.w(TAG, "dismissLockConfirm: 缺 confirm ⇒ 跳过")
        return false
    }
    val need = obj.optDouble("whiteRatio", 0.25)
    val deadline = clock() + timeoutMs
    while (true) {
        val frame = try {
            freshFrame()
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            return false
        }
        val ratio = try {
            lockOverlayWhiteRatio(frame, probe)
        } finally {
            frame.release()
        }
        if (ratio >= need) {
            Log.i(TAG, "dismissLockConfirm: 白底占比=%.3f ≥ $need ⇒ 命中加锁提示框，点确认 (${confirm.centerX},${confirm.centerY})".format(ratio))
            // 纯 tap。⚠️ 原来这里的理由是"同锁图标必须用 tap"，那条前提已被 #96 推翻
            // （锁图标从来不是 tap 无效，是被点了两次）⇒ 取 tap 只是**未验证**，不是结论。
            actions.tap(confirm.centerX, confirm.centerY)
            delay(CLICK_SETTLE_MS)
            return true
        }
        if (clock() >= deadline) {
            Log.d(TAG, "dismissLockConfirm: ${timeoutMs}ms 内未出现提示框（白底占比=%.3f）".format(ratio))
            return false
        }
        delay(250)
    }
}

/**
 * 加锁提示框判据的**像素内核**：`probe` 区白底占比（步长 12px，纯读、不动作）。
 *
 * 抽出来是为了让 [dumpStallShot] 能在停滞现场**只判不动**地把遮罩状态记进 manifest
 * （纯扫描流程里 [dismissLockConfirm] 被 `lockWriteAttempted` 闸住不跑 ⇒ 停滞时有没有遮罩，
 * 日志里本来看不到）。
 */
internal suspend fun ScanEngine.onHintScreen(hint: String): Boolean {
    val gateway = ocr ?: return false
    val r = runCatchingCancellable { profile.rect("screens._common.topRightHint") }.getOrNull() ?: return false
    val frame = try {
        freshFrame()
    } catch (e: CancellationException) { throw e } catch (_: Exception) {
        return false
    }
    return try {
        gateway.readLines(frame, listOf(r)).joinToString(" ").contains(hint)
    } finally {
        frame.release()
    }
}

/**
 * 退掉筛选面板回到"家"：用**系统 BACK**（分辨率无关、免标定；GOODScanner 同样用 Escape 关这个面板），
 * 每次按前先判态 —— **回到家就不再按**，避免多按一次把家本身关掉。
 *
 * ⚠️ 2026-09-29 #136：`homeHint` 是给**圣遗物管理界面**用的。这个界面的左上角显示的是**角色名**
 *   （flow 自己记着这条实测），所以 `filterPanelState()` 在这儿**永远不会**返回 BACKPACK
 *   ⇒ "回到家就停"这个判据不可达 ⇒ 每次都把 3 次 BACK 按满，从管理页一路退出角色页。
 *   真机后果：第 3 轮 `selected=0` 放弃筛选后，后面的 `pagedGrid` 对着大世界点了 15 格，
 *   最后误开「确认退出游戏」。给了 `homeHint` 之后：① 见到它就停；② 判态 UNKNOWN **且**它也不在
 *   ⇒ 说明早就不在这个界面上了，**立刻停手**（再按 BACK 只会往更深层菜单里钻）。
 */
internal suspend fun ScanEngine.leaveFilterPanels(
    reason: String,
    aggressive: Boolean = true,
    homeHint: String? = null,
): Boolean {
    // ⚠️ 2026-09-18 返工（真机暴露）：原来 `UNKNOWN -> return false`（怕误按），
    //   结果是"判态读不到 ⇒ 一次 BACK 都不按 ⇒ 面板永远开着" ⇒ 后续整段流程站在面板上跑。
    //   正确语义是：**只有确认回到背包才停**，其余（含 UNKNOWN）都该退一层；
    //   次数封顶 3，且一旦确认背包立刻停（不会把背包本身关掉）。
    //   实测：子面板 → 主面板 → 背包，两层各一次 BACK。
    repeat(3) {
        val st = filterPanelState()
        if (st == FilterPanelState.BACKPACK) return true
        if (homeHint != null) {
            if (onHintScreen(homeHint)) {
                Log.i(TAG, "  leaveFilterPanels($reason): 读到『$homeHint』⇒ 已回到家，停手")
                return true
            }
            if (st == FilterPanelState.UNKNOWN) {
                Log.w(TAG, "  leaveFilterPanels($reason): 判态 UNKNOWN 且『$homeHint』也不在 ⇒ **已不在目标界面上**，" +
                    "不再按 BACK（再按只会往更深层钻）")
                return false
            }
        }
        // ★ aggressive=false（"面板可能压根没打开"的路径专用）：只按**正向证据**退 ——
        //   读不清（UNKNOWN）时不按 BACK，否则会把**背包本身**关掉（真机实测：把这轮整条流程
        //   带到了世界界面，之后每格都是"无已解析产物"）。收尾交给 flow 的 assertScreen 兜。
        if (!aggressive && st == FilterPanelState.UNKNOWN) {
            Log.w(TAG, "  leaveFilterPanels($reason): 判态 UNKNOWN 且非激进模式 ⇒ 不按 BACK（交给 assertScreen 兜）")
            return false
        }
        Log.i(TAG, "  leaveFilterPanels($reason): 当前=$st ⇒ 按 BACK 退一层")
        runCatchingCancellable { actions.back() }
        delay(FILTER_PANEL_BACK_MS)
    }
    return filterPanelState() == FilterPanelState.BACKPACK ||
        (homeHint != null && onHintScreen(homeHint))
}

/** flow 在 setFilter 步上声明的"家"锚词（圣遗物管理界面填「圣遗物推荐」；背包不填＝沿用 BACKPACK 判据）。 */
internal fun ScanEngine.homeHintOf(step: JSONObject): String? =
    step.optString("homeHint").takeIf { it.isNotEmpty() }

internal suspend fun ScanEngine.navigate(step: JSONObject) {
    val menu = step.getJSONArray("leftMenu")
    for (i in 0 until menu.length()) {
        val name = menu.getString(i)
        val rect = try {
            profile.rect("screens.char_interface.leftMenu.$name")
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
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
internal suspend fun ScanEngine.exitStep(step: JSONObject) {
    val via = step.optString("via", "")
    // 与 clicks 链同一套机读优先：链名命中 CHAIN_ANCHOR_PATHS 且该档 profile 有这条 rect
    // ⇒ 用 rect 中心；否则回落 flow 字面点（字面是 3200 占位，其它档位会点空）。
    val rectPath = CHAIN_ANCHOR_PATHS[via.substringBefore("[").trim()]
    val pt = rectPath?.let { p ->
        runCatchingCancellable { profile.rect(p) }.getOrNull()?.let { r ->
            Log.i(TAG, "exit: '$via' → profile $p center=(${r.centerX},${r.centerY})")
            FramePoint(r.centerX, r.centerY)
        }
    } ?: Regex("\\[(\\d+),(\\d+)\\]").find(via)?.let { m ->
        profile.scalePoint(m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }
    if (pt != null) {
        actions.click(pt.x, pt.y)
        delay(CLICK_SETTLE_MS)
    } else {
        Log.w(TAG, "exit: via='$via' 既无机读 rect 也无字面点 ⇒ 什么都没点")
    }
    // ★ 2026-09-29 #135 补：一次返回只退**一层**（圣遗物管理页 → 角色详情页），
    //   于是每轮跑完都把游戏停在角色页，下一轮的 returnToHome 得多花判定。
    //   ⚠️ 不能"再点一下同一个坐标"——大世界里那个位置是**派蒙菜单**钮（#139 的事故形状）。
    //   交给带判据的 returnToHome：它先看是不是主界面，再决定点不点。
    returnToHome()
    Log.i(TAG, "exit step reached")
    vars.stopRequested = true // 终止扫描（run() 与 pagedGrid 均检查）
    vars.stopReason = "exit" // flow 正常走完（≠ stopWhen 命中）
}

/**
 * P3 verify：zone 状态断言（顶层步骤，不在 visit 内——无 cell 上下文；支持 artifact.panel.* 系列）。
 * 判定不等于 expect 则 warn（D5 交给外层 fallback/retry 处理，verify 本身不阻断）。
 */
