package com.bettergi.pocket.scan

import android.util.Log
import com.bettergi.pocket.notice.NoticeCenter
import com.bettergi.pocket.recognition.name.GoodNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Mat
import com.bettergi.pocket.scan.ScanEngine.Companion.CHAIN_ANCHOR_PATHS
import com.bettergi.pocket.scan.ScanEngine.Companion.CHAR_BOX_CHECKED_MIN
import com.bettergi.pocket.scan.ScanEngine.Companion.CHAR_LEVEL_MIN_PLAUSIBLE
import com.bettergi.pocket.scan.ScanEngine.Companion.CHAR_NAME_MAX_TRIES
import com.bettergi.pocket.scan.ScanEngine.Companion.CHAR_NAME_REREAD_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.CHAR_PANEL_SCROLL_TRIES
import com.bettergi.pocket.scan.ScanEngine.Companion.CLICK_SETTLE_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.CONSTELLATION_PAGE_RETRY
import com.bettergi.pocket.scan.ScanEngine.Companion.CONSTELLATION_POLL_GAP_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.CONSTELLATION_POLL_MAX
import com.bettergi.pocket.scan.ScanEngine.Companion.CONSTELLATION_RENAV_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.CONSTELLATION_SAT_MIN
import com.bettergi.pocket.scan.ScanEngine.Companion.CONSTELLATION_STABLE_TOL
import com.bettergi.pocket.scan.ScanEngine.Companion.DEFAULT_CHAR_DICT
import com.bettergi.pocket.scan.ScanEngine.Companion.panelShotDir
import com.bettergi.pocket.scan.ScanEngine.Companion.talentLevelOf
import com.bettergi.pocket.scan.ScanEngine.Companion.talentRowPitch
import com.bettergi.pocket.scan.ScanEngine.Companion.talentSamplesTied
import com.bettergi.pocket.scan.ScanEngine.Companion.voteTalentLevel
import com.bettergi.pocket.scan.ScanEngine.Companion.FILTER_PANEL_BACK_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.MAX_ROSTER_PAGES
import com.bettergi.pocket.scan.ScanEngine.Companion.MAX_SNAP_ROUNDS
import com.bettergi.pocket.scan.ScanEngine.Companion.PANEL_RAW_DUMP
import com.bettergi.pocket.scan.ScanEngine.Companion.TAG
import com.bettergi.pocket.scan.ScanEngine.Companion.TALENT_LEVEL_MAX
import com.bettergi.pocket.scan.ScanEngine.Companion.TALENT_REREAD_DELAY_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.TALENT_RETRY_MAX

/*
 * Stage 3.3 步⑤：角色扫描域（§14）从 ScanEngine.kt 抽出（扩展函数，函数体一字未改）。
 */

internal suspend fun ScanEngine.readConstellation(step: JSONObject) {
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
            runCatchingCancellable { actions.back() }
            delay(FILTER_PANEL_BACK_MS)
        }
        "abort" -> throw ScanAbortedException("readConstellation: not on constellation page")
        else -> Unit
    }
}

internal suspend fun ScanEngine.rosterFind(step: JSONObject) {
    // flow 写 "char": "$task.char"（foreach as=task 注入 currentTask）→ 解 $ 前缀引用；
    // 字面名（如 "希诺宁"）直用。
    val raw = step.optString("char", "").takeIf { it.isNotEmpty() }
    // ★ 2026-09-29 卸下意图：GOOD 契约 `location:""` = 卸下，而 artifact 自己的 `location`
    //   是"这件现在穿在谁身上"（见 GoodPlan）⇒ 归一后 `char` 空、`location` 非空，
    //   要去的就是**那个持有者**。没这条兜底的话 rosterFind 会因"无目标角色"直接中止。
    val holder = vars.currentTask?.optString("location")?.takeIf { it.isNotEmpty() }
    val fromStep: String? = when {
        raw != null && raw.startsWith("$") -> {
            val ref = raw.removePrefix("$").substringAfter('.', "") // task.char
            val v = vars.currentTask
            if (v != null && ref.isNotEmpty()) v.optString(ref).takeIf { it.isNotEmpty() } else null
        }
        raw != null -> raw
        else -> null
    }
    val target = fromStep
        ?: vars.currentTask?.optString("char")?.takeIf { it.isNotEmpty() }
        ?: holder
        ?: run {
            Log.w(TAG, "rosterFind: 无目标角色（step.char / currentTask.char / currentTask.location 均空）")
            return
        }
    val gridKey = step.optString("grid", "char_popup")
    val rawGrid = profile.rawObject("grids.$gridKey") ?: return
    val cols = rawGrid.optInt("cols", 3)
    val traverseRows = rawGrid.optInt("traverseRows", 3)
    val advance = rawGrid.optJSONObject("advance")
    // ⚠️ 弹层内禁止 back：back 会关掉 char_popup → 后续点击全落详情页（equip9 实证死循环）。
    // 弹层内直接连点网格即切换详情（2026-09-10 逐格实测：12/12 格点到 12 个不同角色，8 次独立扫描一致）。
    // ★ 2026-09-10 用户定：**只有 auto_equip 走 char_popup**（12 卡/屏，找单个角色密度高）；
    //   角色遍历（character_scan）改走 grids.char_strip（左列头像条 1 列网格）。
    //   调用方负责开/收弹层（flow 里 clicks["tian"] 前后各一次），本函数只管遍历。
    //   （旧注释「点田会劫持网格点击」已被推翻：当时失败样本实为「点到当前角色自己的卡」+ 网格点落在
    //    无弹层态的左侧菜单上两个混杂变量，非弹层问题。）
    // ★ 2026-09-29 #125：先试「沙漏」预筛（元素×武器 AND）。基线实测单件 rosterFind=64.45s
    //   （72 格 + 7 翻页），预筛后实测 93 人 → 8 卡单页。筛不上不报错，只是退回全量遍历。
    var filtered = false
    if (step.optBoolean("filter", true)) {
        when (applyCharFilterFor(target)) {
            CharacterFilterOutcome.APPLIED -> filtered = true
            CharacterFilterOutcome.PANEL_STUCK -> {
                NoticeCenter.error("角色筛选面板关不掉，已中止本轮（避免隔着面板乱点）")
                vars.stopRequested = true
                vars.stopReason = "filterStuck"
                return
            }
            CharacterFilterOutcome.NOT_APPLIED -> Unit
        }
    }
    // 筛到**零命中**时网格是空的（实测 2026-09-29：风+长柄武器 该格 0 人 → 屏幕中央
    // 「暂无筛选结果」，而且**配队那 4 格也不置顶** —— "配队恒占前 4 格"只在有命中时成立）。
    // 这时逐格点 9 下只会读到 9 次空面板，直接走"清筛无筛重扫"。
    val gridEmpty = filtered && charGridEmpty()
    if (gridEmpty) {
        Log.i(TAG, "rosterFind: 筛选结果为零（网格空）⇒ 跳过筛选态遍历")
        dumpCharFilterShot("empty_grid")
    } else {
        dumpCharFilterShot("scan1_start", JSONObject().put("filtered", filtered).put("target", target))
    }
    var found = if (gridEmpty) false
    else traverseRoster(step, gridKey, target, cols, traverseRows, advance)
    // 筛选态没找到 ⇒ **不能直接废轮**：词典的元素/武器是静态值，而旅行者/奇偶的元素
    // 随旅行者当前元素实时变化（2026-09-29 实测页头「草元素 / 崽崽」，词典恒记 anemo）——
    // 静态值一旦错，目标就被筛**出去**了（假阴性）。所以先清筛、无筛重扫一次，仍无才中止。
    if (!found && filtered) {
        Log.w(TAG, "rosterFind: 筛选态未找到 '$target' ⇒ 清除筛选、无筛重扫一次（防词典静态元素漏筛）")
        if (clearCharFilter()) {
            dumpCharFilterShot("scan2_unfiltered")
            found = traverseRoster(step, gridKey, target, cols, traverseRows, advance)
        }
    }
    if (!found) {
        // ★ 2026-09-18 改为**中止整轮**（原来是只 return ⇒ 后续步骤照跑，带着**上一个角色**继续导航/换装，
        //   2026-09-18 真机事故：一路点漂到「确认退出游戏」）。角色选不对 ⇒ 后面做什么都是错的。
        Log.e(TAG, "rosterFind: 目标 '$target' 未在名册找到 ⇒ 中止整轮（绝不带错角色继续）")
        NoticeCenter.error("未在角色名册找到「$target」，已中止本轮（避免换装到错角色）")
        vars.stopRequested = true
        vars.stopReason = "rosterMiss"
        return
    }
}

/**
 * 预筛的三种收尾。
 *
 * ⚠️ `NOT_APPLIED` 有两种来路：压根没筛（词典缺项 / 档位未标定），以及
 * **筛上了、但为了关掉面板不得不把勾选清掉**（零命中时确认钮失效）。后者必须报
 * NOT_APPLIED 而不是 APPLIED —— 2026-09-29 就是报成 APPLIED，于是 rosterFind 以为在筛选态，
 * 先跑一趟**全量**（网格其实已经没筛了），再"清筛无筛重扫"又跑一趟，外加 11 次回顶，
 * 比不预筛还慢一倍。
 *
 * `PANEL_STUCK` 必须中止整轮——面板盖着整张弹层网格（x0..830），点下去全是乱的。
 */
internal enum class CharacterFilterOutcome { APPLIED, NOT_APPLIED, PANEL_STUCK }

/** [confirmCharPanel] 的三种收尾。 */
internal enum class CharacterCloseOutcome { CLOSED_FILTERED, CLOSED_WITHOUT_FILTER, STUCK }

/** rosterFind 的一趟遍历（可能被调用两次：筛选态一次、无筛兜底一次）。 */
internal suspend fun ScanEngine.traverseRoster(
    step: JSONObject,
    gridKey: String,
    target: String,
    cols: Int,
    traverseRows: Int,
    advance: JSONObject?,
): Boolean {
    var firstSeen: String? = null
    var found = false
    // 保留 Lv 特征检测仅作诊断日志。
    // 保留 Lv 特征检测仅作诊断日志。⚠️ 原来用 840×700 大块 ROI ⇒ 恒读不到 Lv、
    // 每轮都打「缺失(跳过点田，直接扫)」，连弹层明明开着时也一样（见 [gridLvText]）。
    runCatchingCancellable {
        val gw = ocr
        if (gw != null) {
            val f = freshFrame()
            val txt = try {
                gridLvText(gw, f)
            } finally { f.release() }
            Log.i(
                TAG,
                "rosterFind: 网格特征" + when {
                    txt == null -> "未标定(lvBands 缺失)"
                    txt.contains("Lv") -> "存在"
                    else -> "缺失"
                } + " text='${txt?.take(60) ?: ""}'"
            )
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
            val before = try { freshFrame() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
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
    return found
}

/**
 * #125 按目标角色的 元素×武器类型 应用弹层「沙漏」预筛。
 *
 * 返回 false = 没筛（词典缺项 / 旅行者系 / 面板未标定 / 复核不过）。这不是错误：预筛只是省时间，
 * 词典给不出可靠 (元素,武器) 时全量遍历才是正确路径。
 *
 * ⚠️ **返回 false 前面板一定已经关回"无勾选"态**。2026-09-29 探针第一版漏了这条：放弃分支不关面板，
 * 后续 rosterFind 全程点在面板上（日志表现为 `网格特征缺失` + 两页就 `reachedEnd`）。
 */
internal suspend fun ScanEngine.applyCharFilterFor(target: String): CharacterFilterOutcome {
    val (el, wt) = charFilterKeys(target) ?: return CharacterFilterOutcome.NOT_APPLIED
    val fp = profile.rawObject("screens.char_interface.filterPanel")
    if (fp == null) {
        Log.i(TAG, "charFilter: profile 未标定 filterPanel（该分辨率待实测）⇒ 跳过预筛")
        return CharacterFilterOutcome.NOT_APPLIED
    }
    val eRow = fp.optJSONArray("elements")?.let { findRow(it, el) }
    val wRow = fp.optJSONArray("weapons")?.let { findRow(it, wt) }
    if (eRow == null || wRow == null) {
        Log.w(TAG, "charFilter: 面板无 $el/$wt 条目 ⇒ 跳过预筛")
        return CharacterFilterOutcome.NOT_APPLIED
    }
    clickPanelCenter("screens.char_interface.filterPanel.funnel")
    delay(panelSettleMs)
    dumpCharFilterShot("opened", JSONObject().put("wantEl", el).put("wantWt", wt))
    // ★ 面板**重开时保留上次的滚动位置**（2026-09-29 探针根因：它开在底部态，露出的是
    //   武器/队伍强化/星标状态，而代码按"置顶态"坐标去点 风元素(120,620) ⇒ 点到别处，
    //   并且把「清除」也点歪，攒出 长柄武器+火+水 三条残留勾选）。所以任何按 y 点格之前，
    //   必须先用 OCR 锚点把滚动状态**读出来确认**，不许假设。
    if (!ensureCharPanelScrolled(fp, atBottom = false)) {
        dumpCharFilterShot("FAIL_not_top")
        Log.w(TAG, "charFilter: 面板滚不回置顶态 ⇒ 放弃预筛")
        return closeOrStuck()
    }
    if (!resetCharFilterSelections()) {
        dumpCharFilterShot("FAIL_not_cleared")
        Log.w(TAG, "charFilter: 残留勾选清不掉 ⇒ 放弃预筛（不带未知状态点网格）")
        return closeOrStuck()
    }
    dumpCharFilterShot("ready_top")
    // ★ 顺序硬约束（2026-09-29 实测）：**先武器、后元素**。勾上任意一项后浮出的 chip 行
    //   正好压住武器第 2 行 弓(120,1162)/长柄武器(486,1162)；反过来先点元素，第二件武器就点不到了。
    val scrolled = wRow.optBoolean("needScroll")
    if (scrolled && !ensureCharPanelScrolled(fp, atBottom = true)) {
        dumpCharFilterShot("FAIL_not_bottom")
        Log.w(TAG, "charFilter: 面板滚不到底（法器行露不出来）⇒ 放弃预筛")
        resetCharFilterSelections()
        return closeOrStuck()
    }
    var ok = toggleCharRow(wRow)
    if (scrolled && ok) ok = ensureCharPanelScrolled(fp, atBottom = false)
    if (ok) ok = toggleCharRow(eRow)
    if (!ok) {
        Log.w(TAG, "charFilter: 勾选复核未通过（$el/$wt）⇒ 放弃预筛")
        resetCharFilterSelections()
        return closeOrStuck()
    }
    // 面板必须**关掉**才能开始遍历：面板覆盖 x0..830，而弹层网格整块在它下面。
    when (confirmCharPanel()) {
        CharacterCloseOutcome.STUCK -> {
            Log.e(TAG, "charFilter: 确认后面板仍关不掉 ⇒ **中止整轮**（隔着面板点网格必然乱点）")
            return CharacterFilterOutcome.PANEL_STUCK
        }
        CharacterCloseOutcome.CLOSED_WITHOUT_FILTER -> {
            Log.i(TAG, "charFilter: 零命中 ⇒ 为关掉面板已清掉勾选，**本轮不预筛**（只走一趟全量）")
            return CharacterFilterOutcome.NOT_APPLIED
        }
        CharacterCloseOutcome.CLOSED_FILTERED -> Unit
    }
    dumpCharFilterShot("applied", JSONObject().put("el", el).put("wt", wt))
    Log.i(TAG, "charFilter: 已应用 ${eRow.getString("zh")} × ${wRow.getString("zh")}（$el/$wt）")
    return CharacterFilterOutcome.APPLIED
}

/** 放弃预筛时的收尾：能关就关干净，关不掉就上报 stuck（调用方必须中止整轮）。 */
internal suspend fun ScanEngine.closeOrStuck(): CharacterFilterOutcome =
    if (confirmCharPanel() == CharacterCloseOutcome.STUCK) CharacterFilterOutcome.PANEL_STUCK else CharacterFilterOutcome.NOT_APPLIED

/**
 * 把筛选面板滚到置顶态 / 夹底态，并用**段标题 OCR** 确认到位。
 *
 * 必须确认而不能只滑一次：①面板保留上次的滚动位置（起点未知）；②滚动带惯性
 * （实测 400px 手势让内容走了 732px），一次手势既可能不足也可能过冲。
 * 判据用段标题（置顶=『元素』、夹底=『星标』），与像素/坐标不同源。
 */
internal suspend fun ScanEngine.ensureCharPanelScrolled(fp: JSONObject, atBottom: Boolean): Boolean {
    val gw = ocr ?: return false
    val expect = fp.optString(if (atBottom) "bottomExpect" else "topExpect")
    if (expect.isEmpty()) return false
    val anchorPath = "screens.char_interface.filterPanel." + if (atBottom) "bottomAnchor" else "topAnchor"
    val gesture = if (atBottom) "scrollToBottom" else "scrollToTop"
    repeat(CHAR_PANEL_SCROLL_TRIES) {
        if (panelAnchorHits(gw, anchorPath, expect)) return true
        swipePanelTo(fp, gesture)
        delay(panelSettleMs)
    }
    val hit = panelAnchorHits(gw, anchorPath, expect)
    if (!hit) Log.w(TAG, "charFilter: 滑了 $CHAR_PANEL_SCROLL_TRIES 次仍未见锚点『$expect』@$anchorPath")
    return hit
}

internal suspend fun ScanEngine.panelAnchorHits(gw: OcrGateway, anchorPath: String, expect: String): Boolean {
    val rect = runCatchingCancellable { profile.rect(anchorPath) }.getOrNull() ?: return false
    val frame = try {
        freshFrame()
    } catch (e: CancellationException) { throw e } catch (_: Exception) {
        return false
    }
    return try {
        gw.readLines(frame, listOf(rect)).joinToString("").contains(expect)
    } finally {
        frame.release()
    }
}

/** 撤销预筛：开面板 → 清除 → 确认（回到全量名册）。返回 false = 没能复位到无勾选。 */
internal suspend fun ScanEngine.clearCharFilter(): Boolean {
    if (profile.rawObject("screens.char_interface.filterPanel") == null) return false
    clickPanelCenter("screens.char_interface.filterPanel.funnel")
    delay(panelSettleMs)
    val cleared = resetCharFilterSelections()
    // 面板没关掉就不能让调用方去点网格（它盖着整张弹层 x 0..830）。
    val closed = leaveCharFilterPanel()
    if (!closed) Log.e(TAG, "clearCharFilter: 面板关不掉 ⇒ 上报失败（调用方须中止，不得隔着面板点网格）")
    return cleared && closed
}

/** 点「确认筛选」收起面板。调用前勾选集必须已复位 ⇒ 等价于"应用一个空筛选"。 */
internal suspend fun ScanEngine.leaveCharFilterPanel(): Boolean = confirmCharPanel() != CharacterCloseOutcome.STUCK

/** 把面板复位到"无任何勾选"（清除幂等重试）。 */
internal suspend fun ScanEngine.resetCharFilterSelections(): Boolean {
    repeat(3) {
        if (!charChipRowPresent()) return true
        clickPanelCenter("screens.char_interface.filterPanel.clear")
        delay(panelSettleMs)
    }
    val left = charChipRowPresent()
    if (left) Log.w(TAG, "charFilter: 点了 3 次清除 chip 行仍在 ⇒ 面板状态未知")
    return !left
}

/** 点一行并**重读该行勾选框**复核；首点被过渡动画吞掉时补一次。已勾则不点（防误取消）。 */
internal suspend fun ScanEngine.toggleCharRow(row: JSONObject): Boolean {
    val x = row.getInt("x")
    val y = row.getInt("y")
    val zh = row.optString("zh", "$x,$y")
    val pre = charBoxLevel(x, y)
    if (pre > CHAR_BOX_CHECKED_MIN) {
        Log.i(TAG, "charFilter: $zh 已勾选(level=$pre)，跳过（防误取消）")
        return true
    }
    clickAt(x, y, panelSettleMs)
    var lv = charBoxLevel(x, y)
    if (lv <= CHAR_BOX_CHECKED_MIN) {
        clickAt(x, y, panelSettleMs)
        delay(panelSettleMs)
        lv = charBoxLevel(x, y)
    }
    dumpCharFilterShot(
        "row_$zh",
        JSONObject().put("x", x).put("y", y).put("levelBefore", pre).put("levelAfter", lv)
            .put("judged", lv > CHAR_BOX_CHECKED_MIN),
    )
    if (lv <= CHAR_BOX_CHECKED_MIN) Log.w(TAG, "charFilter: 点了两次 $zh 勾选框 level 仍=$lv (≤$CHAR_BOX_CHECKED_MIN)")
    return lv > CHAR_BOX_CHECKED_MIN
}

/**
 * chip 行是否浮出 = 面板里是否已有勾选。
 *
 * ⚠️ 判据是**「清除」按钮上那枚红垃圾桶的红色像素数**，不是 OCR 读 `chipRoi`：chip 行与
 *   弓/长柄武器 行**同一条 y 带**（chip 1174~1214 vs 武器行 2 中心 1162），没勾选时 OCR
 *   照样读出"长柄武器"。2026-09-29 探针就是被这条误判带跑的：面板刚开就被当成"带着残留勾选"，
 *   连点两次清除都"失败"，于是放弃预筛。实测红像素 **无勾选 0 个 / 有勾选 128 个**（2640 采样点）。
 */
/**
 * 筛选后网格是否**一张卡都没有**。
 *
 * 判据用"读不到 `Lv`"而不是去认「暂无筛选结果」那行字：OCR 对中文艺术字常糊
 * （实测把「确认筛选」读成「确认筛洗选」），而 `Lv` 是拉丁字母、稳定得多。
 * 这条判据顺带也挡住了另一种坏态——面板没关掉时该区域同样读不到 `Lv`，
 * 而此时**绝不该**隔着面板去点网格。
 */
internal suspend fun ScanEngine.charGridEmpty(): Boolean {
    val gw = ocr ?: return false
    val frame = try {
        freshFrame()
    } catch (e: CancellationException) { throw e } catch (_: Exception) {
        return false
    }
    return try {
        val txt = gridLvText(gw, frame)
        if (txt == null) {
            Log.w(TAG, "charGridEmpty: grids.char_popup.lvBands 未标定 ⇒ 判不了，按「没看见」返回非空")
            return false
        }
        Log.i(TAG, "charGridEmpty: 读不到 Lv = ${!txt.contains("Lv")} text='${txt.take(120)}'")
        !txt.contains("Lv")
    } finally {
        frame.release()
    }
}

/**
 * 弹层网格里有没有卡：**只读铭牌细条带**再拼文本。
 *
 * ⚠️ 不能把整块网格当一个 ROI 丢给 `readLines` —— 该接口按**单行文本**设计，喂 1740×900
 *   的大块只会读出垃圾（2026-09-29 实测只返回一个『会』字）。这条坑同时埋着一个
 *   **从没生效过的死判据**：`traverseRoster` 开头的「网格特征」诊断用的就是 840×700 大块，
 *   所以它每一轮都打「网格特征缺失」，包括弹层明明开着的那些轮。
 *
 * 带坐标取自各档 profile 的 `grids.char_popup.lvBands`，**不许写死在代码里**：铭牌贴在卡底、
 * 卡高按档位各自取整，两档不成固定比例（2560 实测标签 y 398/677，2244 实测 312/521 ——
 * 同一个 2560 的带放到 2244 正好落在行间隙，恒读空）。
 *
 * @return `null` = 该档没配 lvBands（未标定）。调用方按**"没看见"**处理，
 *         不能当成"网格是空的"——那会把一次标定缺失报成一个不存在的空名册。
 */
internal suspend fun ScanEngine.gridLvText(gw: OcrGateway, frame: Mat): String? {
    val arr = profile.rawAny("grids.char_popup.lvBands") as? JSONArray ?: return null
    if (arr.length() < 2) return null
    val rects = (0 until arr.length()).mapNotNull { i ->
        val b = arr.optJSONArray(i) ?: return@mapNotNull null
        if (b.length() < 4) return@mapNotNull null
        profile.scaleRect(b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3))
    }
    if (rects.isEmpty()) return null
    return runCatchingCancellable { gw.readLines(frame, rects).joinToString(" ") }.getOrDefault("")
}

internal suspend fun ScanEngine.charChipRowPresent(): Boolean {
    val arr = profile.rawAny("screens.char_interface.filterPanel.clear") as? JSONArray
    if (arr == null || arr.length() < 4) return false
    val frame = try {
        freshFrame()
    } catch (e: CancellationException) { throw e } catch (_: Exception) {
        return false
    }
    return try {
        val x0 = profile.scale(arr.getInt(0), profile.scaleX)
        val x1 = profile.scale(arr.getInt(2), profile.scaleX)
        val y0 = profile.scale(arr.getInt(1), profile.scaleY)
        val y1 = profile.scale(arr.getInt(3), profile.scaleY)
        var red = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                // ⚠️ 帧是 **BGR**（本文件既有约定：`[2]=R、[1]=G、[0]=B`）。
                //   第一版按 RGB 写、拿 px[0] 当红 ⇒ 红色图标 B 分量低，条件恒不成立
                //   ⇒ charChipRowPresent 永远返回"没有 chip"，清除从来没被点过。
                val px = runCatchingCancellable { frame.get(y, x) }.getOrNull()
                if (px != null && px[2] > 140 && px[2] - px[1] > 60 && px[2] - px[0] > 60) red++
                x += 3
            }
            y += 3
        }
        red >= 10
    } finally {
        frame.release()
    }
}

/**
 * 勾选框亮度读数（±8px 邻域最大值，-1 = 取帧失败）。
 *
 * 2560 实测：未选格心 50~52、圆环最亮 75；已选 125（绿勾）~227（米底）⇒ 判勾线 [CHAR_BOX_CHECKED_MIN]。
 * 邻域取 max 是因为绿勾不一定正落在中心点上。
 */
internal suspend fun ScanEngine.charBoxLevel(x: Int, y: Int): Int {
    val frame = try {
        freshFrame()
    } catch (e: CancellationException) { throw e } catch (_: Exception) {
        return -1
    }
    return try {
        var best = -1
        for (dy in -8..8 step 4) for (dx in -8..8 step 4) {
            val px = runCatchingCancellable {
                frame.get(
                    profile.scale(y + dy, profile.scaleY),
                    profile.scale(x + dx, profile.scaleX),
                )
            }.getOrNull() ?: continue
            val bright = ((px[0] + px[1] + px[2]) / 3.0).toInt()
            if (bright > best) best = bright
        }
        best
    } finally {
        frame.release()
    }
}

internal suspend fun ScanEngine.charBoxChecked(x: Int, y: Int): Boolean = charBoxLevel(x, y) > CHAR_BOX_CHECKED_MIN

/**
 * charFilter 的**步级取证**（#129）。
 *
 * 为什么每步都落图：2026-09-29 三轮探针里日志报 `charFilter: 已应用`，而紧随其后的遍历读到
 * `网格特征缺失` + 整页 `looped=true`（点 9 格名字不变）。只看日志会在"面板没关 / 弹层没开 /
 * 坐标整体错位"三种解释之间反复打转——一张现场图直接定死。
 * 沿用 [dumpStallShot] 的目录 / 闸门 / manifest 口径：取证开关关着时**连帧都不抓**。
 */
internal suspend fun ScanEngine.dumpCharFilterShot(phase: String, detail: JSONObject = JSONObject()) {
    val dir = panelShotDir ?: return
    if (!shotGateOpen(dir)) return
    runCatchingCancellable {
        val seq = ++panelShotSeq
        val name = String.format("cf%04d_%s", seq, phase)
        val f = freshFrame()
        try {
            val json = JSONObject()
                .put("kind", "charFilter")
                .put("phase", phase)
                .put("detail", detail)
            if (writeShot(dir, name, seq, json, f)) {
                Log.i(TAG, "cfShot[$phase] $detail -> $name.jpg")
            }
        } finally {
            f.release()
        }
    }.onFailure { Log.w(TAG, "charFilter 取证落盘失败", it) }
}

internal fun ScanEngine.findRow(arr: JSONArray, key: String): JSONObject? {
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        if (o.optString("key") == key) return o
    }
    return null
}

/** 筛选面板内滑动（惯性大，只当"滚到夹死"用，不承诺位移量）。 */
internal suspend fun ScanEngine.swipePanelTo(fp: JSONObject, key: String) {
    val g = fp.optJSONObject(key) ?: return
    val from = g.getJSONArray("from"); val to = g.getJSONArray("to")
    actions.swipe(
        profile.scale(from.getInt(0), profile.scaleX),
        profile.scale(from.getInt(1), profile.scaleY),
        profile.scale(to.getInt(0), profile.scaleX),
        profile.scale(to.getInt(1), profile.scaleY),
    )
}

/**
 * 点 profile 里某个 `[x0,y0,x1,y1]` 矩形的中心。
 *
 * ⚠️ **不能**用 `profile.rect()`：它返回的已是帧坐标，而 [clickAt] 还会再乘一次 scale
 * （三档专属 profile 时 scale=1 看不出来，未标定分辨率**回落基线档**时才会点到屏幕外）。
 * 所以取原始基准数组，缩放统一交给 clickAt。
 *
 * 点击样式**沿用共享的微滑 click**：2026-09-29 做过正反对照，非空状态下
 * `clickLocal`(2px+120ms) 与 adb 原生 `input tap` 都能一次关掉「确认筛选」；
 * 零命中状态下**两种都关不掉**。⇒ 样式无关，别为它单开一条路径。
 */
internal suspend fun ScanEngine.clickPanelCenter(path: String) {
    val arr = profile.rawAny(path) as? JSONArray
    if (arr == null || arr.length() < 4) {
        Log.w(TAG, "charFilter: profile 缺 $path ⇒ 该步跳过")
        return
    }
    clickAt((arr.getInt(0) + arr.getInt(2)) / 2, (arr.getInt(1) + arr.getInt(3)) / 2, panelSettleMs)
}

/**
 * 点「确认筛选」**并确认面板真的关了**。
 *
 * ⚠️ 绝不能"点了就算"，也不能无脑多按：面板关闭后同一个坐标就是弹层底栏的
 * **「等级顺序」排序下拉**（2026-09-29 撞过：多按一次把排序菜单点开，名册顺序被改掉
 * ⇒ rosterFind 的位置假设全废）。判据用"置顶锚点『元素』消失"——面板开着就一定读得到。
 *
 * ⚠️ **零命中时「确认筛选」是失效的**（实测：风+长柄武器该格 0 人，屏幕打「暂无筛选结果」，
 *   此时无论 `clickLocal` 还是 adb 原生 `input tap` 都关不掉面板）。
 *   所以第一次失败后先**清除勾选**——网格回到全量、按钮恢复响应——再确认一次。
 *   调用方拿到 false 时面板**可能仍开着**，绝不能继续点网格。
 */
internal suspend fun ScanEngine.confirmCharPanel(): CharacterCloseOutcome {
    val gw = ocr
    val expect = profile.rawObject("screens.char_interface.filterPanel")
        ?.optString("topExpect", "元素").orEmpty().ifEmpty { "元素" }
    clickPanelCenter("screens.char_interface.filterPanel.confirm")
    if (!charPanelStillOpen(gw, expect)) return CharacterCloseOutcome.CLOSED_FILTERED
    Log.w(TAG, "charFilter: 确认后面板未关（多半是**零命中**让确认钮失效）⇒ 清筛再关一次")
    dumpCharFilterShot("retry_close")
    resetCharFilterSelections()
    clickPanelCenter("screens.char_interface.filterPanel.confirm")
    if (charPanelStillOpen(gw, expect)) {
        Log.w(TAG, "charFilter: 清筛后仍关不掉面板 ⇒ 状态未知，调用方不得点网格")
        dumpCharFilterShot("FAIL_panel_open")
        return CharacterCloseOutcome.STUCK
    }
    return CharacterCloseOutcome.CLOSED_WITHOUT_FILTER
}

internal suspend fun ScanEngine.charPanelStillOpen(gw: OcrGateway?, expect: String): Boolean =
    gw != null && panelAnchorHits(gw, "screens.char_interface.filterPanel.topAnchor", expect)

/**
 * 目标角色 → 筛选键对（元素, 武器类型），任一缺项返回 null。
 *
 * ⚠️ 旅行者系/奇偶恒返回 null：他们的元素**随旅行者当前元素实时变**（词典静态值一定是错的），
 * 而 auto_equip 也不会给他们配装（用户 2026-09-29 定），所以直接不筛。
 */
internal fun ScanEngine.charFilterKeys(target: String): Pair<String, String>? {
    val table = names ?: return null
    val key = table.match(target, GoodNames.Kind.CHARACTER)?.key
        ?: target.takeIf { it.isNotBlank() }
    val attrs = key?.let { table.charAttrs[it] } ?: return null
    if (key.startsWith("Traveler", ignoreCase = true) || key.startsWith("Manekin", ignoreCase = true)) {
        Log.i(TAG, "charFilter: '$key' 元素随旅行者动态变 ⇒ 不预筛")
        return null
    }
    val el = attrs.element
    val wt = attrs.weapon
    if (el == null || wt == null) Log.i(TAG, "charFilter: '$key' 词典缺 element/weapon ⇒ 不预筛")
    return if (el != null && wt != null) el to wt else null
}

internal fun ScanEngine.nameMatches(got: String, target: String): Boolean {
    if (StatParser.clean(got) == StatParser.clean(target)) return true
    // ★ 2026-09-18 修：GoodNames 以**中文名**为键，而 flow 的 `$task.char` 来自 GOOD 计划的
    //   `location`，是**英文键**（`Shenhe`）⇒ 原写法 `names?.match(target)?.key ?: return false`
    //   会**直接返回 false**，于是目标角色**永远匹配不上**（真机实测：rosterFind 从未命中，
    //   却因只 `return` 而**带着错角色继续往下跑**，最终会把件装到别人身上）。
    //   修法：查不到就**把 target 自身当作 key 用**（下面 cleaned.equals(tk) 是大小写不敏感比较）。
    val tk = names?.match(target, GoodNames.Kind.CHARACTER)?.key
        ?: target.takeIf { it.isNotBlank() } ?: return false
    // got 常为「中文乱码 + 英文名 + 等级数字粘连」混合（app OCR 读中文艺术字弱，equip14/15 实证
    // '夥得大Sandrone' / 'Xilonen90790'）→ 剥尾数字 + 逐 token 词典反查，任一 token 命中即等
    val tokens = got.split(Regex("[\\s·・]+")) + got.trimEnd { it.isDigit() }
    for (tok in tokens) {
        val cleaned = StatParser.clean(tok).trimEnd { it.isDigit() }
        if (cleaned.isEmpty()) continue
        // GoodNames 表以中文名为键 → 面板读出的英文名（Xilonen）反查为 null；
        // 直接与 target 解出的 GOOD id 比（target '希诺宁' → tk 'Xilonen'）
        if (cleaned.equals(tk, ignoreCase = true)) return true
        val gk = names?.match(cleaned, GoodNames.Kind.CHARACTER)?.key
        if (gk != null && gk == tk) return true
    }
    return false
}

/**
 * §14 A3 **snap 遍历**（click-to-snap 零滑动，仅选择界面 `select_3col`）——依据
 * `dsl/docs/flow5-auto-equip.md` §遍历优化（真机实证：点部分遮挡底行卡 → 网格自动上弹，
 * 被点卡顶边对齐 y≈917）：
 * - **两段覆盖**：①先按 `rowY/colX` 逐格点过完全可见的顶部 `traverseRows` 行（点它们不上弹）；
 *   ②再进固定点击带循环，每轮 3 击 = `peekCols[0]`（遮挡行首列，点它触发上弹）→ `loopClicks`
 *   （上弹后同一行的另两列）。两段合起来才是"每卡恰点一次"。
 * - 终止：末页 peek 消失 → 上弹不发生（网格指纹不变）→ **补点遮挡槽残余卡**（peekCols 其余列）→ 停止。
 * - 点击由本函数代劳，故 visit 内 `click` 步降级为「settle + 抓帧」（[runVisit] snapMode）。
 * - ⚠️ 上弹动画 ~300ms 需 settle；此处沿用 CLICK_SETTLE_MS(600ms) 保守值（过 settle 只费时不损正确性，
 *   精确值见 grids.*.advance.animMs，待真机标定后再细化）。
 * - ⚠️ ①段的前提是**网格停在首行**（auto_equip 由 sortBtn→排序弹窗确认这条复位链保证）。
 *   复位链断掉时 ①段扫的是"当前可见的顶三行"，不是第 1..9 号卡 —— 会漏，但不会漏得比修前更狠。
 */
internal suspend fun ScanEngine.snapTraverse(
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
    // ★★ 2026-09-29 #132 真机实测的漏扫：上面那两条点击带（peekCols y=1215 / loopClicks y=1048）
    //   只覆盖**被底栏遮挡的那一行**，而网格停在首行时 `traverseRows` 行是完全可见的、
    //   根本不在带上 ⇒ 首 3 行 9 张卡从来没被点过。当天实测：目标件就在首行首列
    //   (164,336)，四轮点击全落在 y=1048/1215 的空处 ⇒ 面板身份四次不变 ⇒ duplicateStreak
    //   ⇒ 判「网格到底」⇒ NotFound，而它左侧第一格就是答案。
    //   此前没暴露，是因为历次真机跑的"命中"都是**进界面时自动选中的那件已穿件**
    //   （点空处不改选中 ⇒ 照样读到目标 ⇒ AlreadyCorrect），从未依赖过点击。
    //   修法：先按 rowY/colX 逐格扫过完全可见的顶部 `traverseRows` 行（网格已在首行，
    //   点这些格不会触发上弹），再进原循环处理遮挡行 —— 两段合起来才是一页的完整覆盖。
    val rawGrid = profile.rawObject("grids.$gridKey")
    val cols = rawGrid?.optInt("cols", -1) ?: -1
    val topRows = rawGrid?.optInt("traverseRows", -1) ?: -1
    if (cols >= 1 && topRows >= 1) {
        Log.i(TAG, "snap[$gridKey]: 先扫完全可见的顶部 ${topRows}×${cols}=${cols * topRows} 格")
        for (i in 0 until cols * topRows) {
            if (vars.stopRequested) break
            val c = profile.cellCenter(gridKey, i)
            actions.click(c.x, c.y)
            runVisit(visit, gridKey, 0, 0, 0, profile, snapMode = true)
            visited++
        }
    } else {
        Log.w(TAG, "snap[$gridKey]: grids.$gridKey 缺 cols/traverseRows ⇒ 跳过顶部带（只剩遮挡行点击，会漏前 9 格）")
    }
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
internal fun ScanEngine.hardMatch(task: JSONObject, tol: Double, why: StringBuilder? = null): Boolean {
    val a = results.lastOrNull() ?: run { why?.append("无已解析产物"); return false }
    return TaskMatch.hardMatch(task, a, tol, why)
}

/**
 * §14 P2 链路条目 → 点击中心。支持两型写法：
 * `sortBtn(828,1320)`（括号=点）与 `重置[311,1310,483,1363]`（方括号=矩形→取中心）。
 */
internal suspend fun ScanEngine.clickChainEntry(entry: String) {
    // §12.4 P3：链项名（括号前的 token）若命中 CHAIN_ANCHOR_PATHS → 走 profile 机读坐标
    // （设计意图：flow 字面 (x,y) 仅为 3200 占位，跨分辨率须由 profile 提供；字面被忽略）。
    val name = entry.substringBefore("(").substringBefore("[").trim()
    val rectPath = CHAIN_ANCHOR_PATHS[name]
    if (rectPath != null) {
        runCatchingCancellable {
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
internal fun ScanEngine.dupRollbackConfirmed(): Boolean {
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
internal fun ScanEngine.isPlausiblePlayerName(s: String): Boolean =
    s.length in 2..8 && s.all { it in '\u4e00'..'\u9fa5' }

/**
 * 「这个名字像不像『旅行者』」—— 元素规则的第二道门（#146）。
 *
 * ⚠️ 只有 [isPlausiblePlayerName] 不够：实测 3200 一轮里 `随机姓`/`随机人`/`崽崽`
 * **全是 2~3 个纯汉字**、全过第一道门，于是三个**别的角色**被并成 `TravelerDendro`，
 * 其中两个被键去重静默吞掉（GT 93 条 ⇒ 导出 90 条）。
 * 旅行者官方显示名固定是「旅行者」三字（OCR 会掉字/糊字），所以要求**同时含「旅」和「者」
 * 且不超过 4 字**；玩家给旅行者改过名 ⇒ 走昵称表（#105），不走这条猜测。
 */
internal fun ScanEngine.looksLikeTravelerName(s: String): Boolean =
    s.length <= 4 && s.contains('旅') && s.contains('者')

/** 角色概览面板：name/level/header(元素) —— panels.char_profile。 */
internal suspend fun ScanEngine.parseCharacterPanel(step: JSONObject, ctx: ScanEngine.CellFrameContext?) {
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
        var texts = gateway.readRois(frame, rects)
        // 2026-09-10 诊断日志：name/level 在 app 内读空（离线 paddleocr 同源 ROI 能读）→ 打印原始输出与实际 ROI
        fun logRaw(tt: List<String>) = Log.d(
            TAG,
            "char.raw: name='${tt.getOrElse(0) { "" }}' lv='${tt.getOrElse(1) { "" }}' " +
                "hdr='${tt.getOrElse(2) { "" }}' | rects=${rects.joinToString { "(${it.left},${it.top},${it.right},${it.bottom})" }}",
        )
        logRaw(texts)

        /**
         * 本帧的 name / level / ascension / element 解析（抽成本地函数，供「名称未命中 ⇒ 重读」复用）。
         * @return Triple(rawName, level, element)
         */
        fun parseFrame(tt: List<String>): Triple<String, Int, String?> {
            val rawN = StatParser.clean(tt.getOrElse(0) { "" })
            // ★ 2026-09-17 修：OCR 把「Lv.70/80」读成 "70180"（"/" 误读为 "1"）⇒ 直接 toInt 得脏值 70180 ✗
            //   ⇒ 取数字串**前 2~3 位中 ≤100 的最长者**为 level；**尾部**若为合法上限
            //   （20/40/50/60/70/80/90/100）⇒ 记 levelCap，供 ascension 精确推导 ✓
            val lvText = tt.getOrElse(1) { "" }
            val (lv, levelCap) = parseLevelText(lvText)
            lastCharAscension = ascensionOf(lv, levelCap).also { asc ->
                // cap 没读到 ⇒ 只能按 level 分层推，而 level 恰落在突破门槛上时 asc 5/6 是真的分不开
                // （#165 就是这么静默错掉 8 位的）。这种"只能猜"的场合必须出声。
                if (levelCap == null && lv in AMBIGUOUS_LEVELS) {
                    Log.w(TAG, "char: 等级原文='$lvText' 没读到上限，level=$lv 正卡在门槛上" +
                        "⇒ asc=$asc 是按 level 猜的，5/6 分不清；要么 ROI 又收窄了，要么 OCR 掉了后半段")
                }
            }
            if (PANEL_RAW_DUMP) {
                Log.i(TAG, "char.level raw='$lvText' ⇒ level=$lv cap=$levelCap asc=$lastCharAscension")
            }
            val header = StatParser.clean(tt.getOrElse(2) { "" })
            // header 形如「冰元素／桑多涅」→ 取「元素」前缀。
            // ⚠️ OCR 偶把装饰符号一起读进来（实测 '“火元素／…'、'.岩元素／…'）→ 元素值变成 '“火'/'.岩'
            //    ⇒ 先净化成「汉字 + 斜杠」再匹配。
            val headerCjk = Regex("[\\u4e00-\\u9fa5/／]+").findAll(header).map { it.value }.joinToString("")
            val el = Regex("^(.+?)元素").find(headerCjk)?.groupValues?.getOrNull(1)
            return Triple(rawN, lv, el)
        }

        // 词典/模糊开关取自 flow 的 dict.name / dict.fuzzy（未声明时回落默认，不再硬编码）
        val nameDict = dictKeyOf(step, "name") ?: DEFAULT_CHAR_DICT
        /**
         * 三级解析：① 用户昵称表（#105，奇偶/流浪者这类显示名由玩家自定义、词典结构性命中不了）
         *            ② 官方词典（mappings，中文名为键）
         *            ③ 元素规则（旅行者 —— 官方键还要 `<元素>` 后缀，只有概览面板读得到元素）
         * ⚠️ ①② 都可换序，③ 必须最后：元素规则会吃掉**任何**未命中且元素已知的名字 ⇒ 前两档
         *    必须先判，否则「随机姓/随机人」（同为冰元素）会被误判成 TravelerCryo ✗（2026-09-17 实测）。
         * ★ 2026-09-17 加固：元素规则加**两道门**（[isPlausiblePlayerName] 且 level>0）——
         *    实测 cver12 鹿野院平藏的 name ROI 首读为 `'.'`（level 也读成 `'.'`）⇒ 旧规则把它
         *    误判成 TravelerAnemo（凭空多一件、且真角色丢失）✗。
         */
        fun resolveKey(rn: String, lv: Int, el: String?): String? {
            val hit = nameOverrides.characterKeyOf(rn)?.also { Log.i(TAG, "char: 显示名 '$rn' 命中用户昵称表 ⇒ $it") }
                ?: lookupName(nameDict, rn, dictFuzzy(step))
            // 玩家给旅行者改过名 ⇒ 昵称表/词典只会给出裸键 `Traveler`，而 GOOD v3 的键要带**当前元素**
            // 后缀（2026-09-30 实测：本账号把旅行者改名成 `崽崽`，元素已从 GT 的 冰 换成 草）。
            // 元素只有概览面板读得到 ⇒ 在这里补后缀，玩家就不必每次换元素都改一次昵称表。
            if (hit == "Traveler") {
                val tk = el?.let { TRAVELER_BY_ELEMENT[it] }
                if (tk == null) {
                    Log.w(TAG, "char: '$rn' 判为旅行者但元素没读到（el=$el）⇒ 保留裸键 Traveler")
                    return hit
                }
                Log.i(TAG, "char: 显示名 '$rn' → 旅行者，按面板元素 $el 补后缀 ⇒ $tk")
                return tk
            }
            return hit
                ?: el?.takeIf { lv > 0 && isPlausiblePlayerName(rn) && looksLikeTravelerName(rn) }
                    ?.let { TRAVELER_BY_ELEMENT[it] }
                    ?.also { Log.i(TAG, "char: 显示名 '$rn' 未命中词典/昵称表（元素=$el lv=$lv）⇒ 判为旅行者 $it") }
        }

        var parsed = parseFrame(texts)
        var rawName = parsed.first
        var level = parsed.second
        var element = parsed.third
        var key = resolveKey(rawName, level, element)
        // ★ 2026-09-17：**名称/等级未取到 ⇒ 重读**（时序性读失败；重读不改变已成功的解析结果）
        var nTry = 1
        // ★ 2026-09-17：把「等级读数可疑」也纳入重读条件 —— 实测 Yaoyao 的 `等级70/90` 被读成 `7`
        //   ⇒ lv=7 却 >0，旧条件（key==null || level<=0）不会重读 ✗。
        //   等级 OCR 只会**欠读**（丢末位/首位）⇒ 重读时**逐次取 max** 是安全合并律；
        //   `< CHAR_LEVEL_MIN_PLAUSIBLE` 仅作触发条件（不参与取值）。
        while ((key == null || level <= 0 || level < CHAR_LEVEL_MIN_PLAUSIBLE) && nTry < CHAR_NAME_MAX_TRIES) {
            nTry++
            delay(CHAR_NAME_REREAD_MS)
            val f2 = runCatchingCancellable { freshFrame() }.getOrNull() ?: break
            // ★ A7/G：OCR 引擎不可用 ≠ 读失败。「重读失败 ⇒ break 放弃该格」只该作用于
            //   真读失败，引擎没就绪必须 loud（放行见下方 catch 链），否则静默出脏数据。
            val t2 = try {
                gateway.readRois(f2, rects)
            } catch (e: OcrUnavailableException) {
                throw e
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                null
            }
            if (t2 != null) { texts = t2; logRaw(t2) }
            f2.release()
            parsed = parseFrame(texts)
            rawName = parsed.first
            if (parsed.second > level) level = parsed.second   // 欠读方向确定 ⇒ 取 max
            element = parsed.third
            key = resolveKey(rawName, level, element)
            Log.i(TAG, "char: key/lv 可疑 ⇒ 重读 #$nTry name='$rawName' lv=$level el=$element ⇒ key=$key")
        }
        if (key == null) {
            // ★ 2026-09-30 #146：认不出来的名字**按原文入库**，不再并进旅行者。
            //   对账侧会把它报成「多出一条」——这是**故意的**：宁可显式多一条待办
            //   （去管理器页补昵称，#105），也不要静默少两件。
            Log.w(TAG, "char: 显示名 '$rawName' 三档解析全未命中 ⇒ 按原文入库（lv=$level el=$element），请补昵称表")
        }
        charName = key ?: rawName
        charKey = key
        lastCharKey = key
        charLevel = level
        val elGood = exportElement(key, element)
        charElement = elGood
        vars.level = level
        Log.i(TAG, "char: name=${key ?: rawName} lv=$level element=$elGood（页头 OCR='$element'）")
    } catch (e: OcrUnavailableException) {
        // ★ A7/G：函数级 catch 会把「OCR 引擎未就绪」吞成「跳过该格」（静默错数据）。
        //   引擎不可用是 loud 失败，必须放行（轨 E 已在 OcrGatewayImpl 侧显式抛出）。
        throw e
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        Log.w(TAG, "parseCharacterPanel failed: ${e.message}")
    } finally {
        if (!owned) frame.release()
    }
}

/**
 * 命之座：6 节点「白锁紧凑块」判据（profiles：RGB>195 且占比 >0.4 → 已点亮）。
 * 步长 3px 抽样，避免逐像素遍历大 ROI。
 */
internal suspend fun ScanEngine.readConstellation() {
    val obj = profile.rawObject("panels.char_constellation") ?: return
    val nodes = obj.optJSONArray("nodes") ?: return
    val roi = obj.optInt("roi", 55)
    // ★ 2026-09-30 #124：饱和度阈值是**档位量**，允许 profile 用 `satMin` 覆盖（未声明的档位沿用基准值）。
    //   判据形式（逐节点绝对阈值 + 稳定轮询取 min）不变，变的只是每档 own 的数值。
    //   非法值（缺失/非正）逐节点回落到 CONSTELLATION_SAT_MIN —— 阈值 0 会恒判点亮，比缺字段更危险。
    val satMinArr = obj.optJSONArray("satMin")
    val satMin = IntArray(nodes.length()) { i ->
        val v = if (satMinArr != null && i < satMinArr.length()) satMinArr.optInt(i) else 0
        if (v > 0) v else CONSTELLATION_SAT_MIN.getOrElse(i) { 21 }
    }
    // ── ★ 2026-09-17（三修）：**命之座页「页签级」判据 + 重导航** ──
    //   实测 cver14 Skirk：命之座页没切过去（节点 ROI 读到**属性页**内容 [66,79,107,78,88,69]
    //   ⇒ 误判 c6，且**饱和度判据无法区分**——奇偶（合法在该页）读数 [64,72,66,77,68,91] 与它几乎重叠 ✗。
    //   ⇒ 改用**文本**判据（页签级）：属性页底部独有「提升指南」、天赋页独有「天赋演示/战斗天赋」，
    //     而**命之座页底部无文本**（flow 注释已记录该性质但一直没落地为判据）。
    //     命中前两者 ⇒ 不在命之座页 ⇒ 重击左菜单「命之座」并等待，最多 [CONSTELLATION_PAGE_RETRY] 次。
    var pageTries = 0
    while (pageTries <= CONSTELLATION_PAGE_RETRY) {
        if (!charPanelLooksWrongPage()) break
        if (pageTries == CONSTELLATION_PAGE_RETRY) {
            Log.w(TAG, "char: 命之座页始终未到位（底部仍见属性/天赋页文本）⇒ 按现状解析")
            break
        }
        pageTries++
        val menuRect = profile.rect("screens.char_interface.leftMenu.命之座")
        actions.click(menuRect.centerX, menuRect.centerY)
        delay(CONSTELLATION_RENAV_MS)
        Log.i(TAG, "char: 未在命之座页 ⇒ 重击左菜单「命之座」#$pageTries")
    }
    val frame = try {
        freshFrame()
    } catch (e: CancellationException) { throw e } catch (_: Exception) {
        return
    }
    var lit = 0
    val satSamples = ArrayList<Int>()   // 诊断：每个节点中心区的平均饱和度（多次采样的**逐节点最小值**）
    try {
        val nNode = nodes.length()
        // ── ★ 2026-09-17（二修）：**稳定轮询 + 兜底取 min** ──
        //   根因：切到「命之座」页后有 ~2.5s 的**交叉淡入**，节点 ROI 会吃到上一页（属性/天赋）的彩色内容
        //   ⇒ 饱和度被**单向抬高**（实测切页 +1.0s 时 [67,67,101,72,75,80]，+2.6s 稳定 [38,47,45,26,42,34]；
        //   同一角色同一页两轮实测 11~29 vs 27~42 ⇒ 单次读数完全不稳）。
        //   做法：轮询到相邻两次稳定为止；打满则取首末帧逐节点 **min**（污染单向 ⇒ 安全方向）。
        fun sampleOnce(f: Mat): IntArray {
            val out = IntArray(nNode)
            for (i in 0 until nNode) {
                val a = nodes.getJSONArray(i)
                val cx = profile.scale(a.getInt(0), profile.scaleX)
                val cy = profile.scale(a.getInt(1), profile.scaleY)
                val half = Math.max(1, profile.scale(roi, profile.scaleY) / 2)
                var satSum = 0
                var total = 0
                var y = cy - half
                while (y <= cy + half && y < f.rows()) {
                    var x = cx - half
                    while (x <= cx + half && x < f.cols()) {
                        val px = f.get(y, x)   // BGR
                        val b = px[0].toInt() and 0xFF
                        val g = px[1].toInt() and 0xFF
                        val r = px[2].toInt() and 0xFF
                        // ★ 简易饱和度 = max−min（0~255）：彩色图标高、白/灰/暗底低 ✓
                        satSum += maxOf(r, g, b) - minOf(r, g, b)
                        total++
                        x += 3
                    }
                    y += 3
                }
                out[i] = if (total > 0) satSum / total else 0
            }
            return out
        }
        var firstS: IntArray? = null
        var prevS: IntArray? = null
        var stableS: IntArray? = null
        var polls = 0
        for (poll in 0 until CONSTELLATION_POLL_MAX) {
            if (poll > 0) delay(CONSTELLATION_POLL_GAP_MS)
            val f = if (poll == 0) frame else runCatchingCancellable { freshFrame() }.getOrNull() ?: break
            try {
                val s = sampleOnce(f)
                polls++
                val p = prevS
                if (p != null) {
                    var d = 0
                    for (i in 0 until nNode) d = maxOf(d, Math.abs(s[i] - p[i]))
                    if (d <= CONSTELLATION_STABLE_TOL) {
                        stableS = s
                        break
                    }
                }
                if (firstS == null) firstS = s
                prevS = s
            } finally {
                if (poll > 0) f.release()
            }
        }
        val useS = stableS ?: run {
            val a = firstS ?: IntArray(nNode) { 0 }
            val b = prevS ?: a
            IntArray(nNode) { minOf(a[it], b[it]) }
        }
        if (PANEL_RAW_DUMP || stableS == null) {
            Log.i(
                TAG,
                "char.constellation 采样 $polls 次 ⇒ ${if (stableS != null) "已稳定" else "未稳定(取首末 min)"}" +
                    " ${useS.joinToString()}",
            )
        }
        for (i in 0 until nNode) {
            val sat = useS[i]
            satSamples += sat
            // ★★ 2026-09-17 **判据换成"饱和度"**（白像素比例无法区分"彩色图标"与"暗底" ✗）★★
            //   点亮 = 中心是**彩色技能图标** ⇒ 饱和度高 ✓
            //   未点亮 ⇒ 饱和度低。⚠️ 2026-09-30 #124 订正这半句：**未点亮节点上的锁图标本身是金色的**，
            //   正中心的饱和度反而最高 ⇒ 本判据靠的是 nodes 里那套**偏在节点上方暗背景**的采样点，
            //   不是"节点中心"。别按"更准的节点坐标"去改它们（改过一次，见 profiles satMinNote/nodesNote ✗）。
            val th = satMin[i]
            if (sat >= th) lit++
            if (PANEL_RAW_DUMP) Log.i(TAG, "char.constellation node#$i sat=$sat th=$th lit=${sat >= th}")
        }
    } catch (e: CancellationException) { throw e } catch (_: Exception) {
    } finally {
        frame.release()
    }
    charConstellation = lit
    // ★ 2026-09-17：**奇偶（Manekin/Manekina）无命座系统** —— 其"命之座"页无真实节点，
    //   六格饱和度恒高（实测 [58,61,69,83,99,95]）⇒ 恒被判 6/6 点亮 ✗。
    //   独立证据：其天赋面板读数 = 10/10/10，GT 基础值也是 10/10/10 ⇒ **无任何命座加成** ⇒ c=0 ✓。
    if (lastCharKey != null && lastCharKey in NO_CONSTELLATION_KEYS) {
        Log.i(TAG, "char: $lastCharKey 属奇偶（无命座）⇒ 命座读数 $lit → 0")
        charConstellation = 0
    }
    // 阈值打**实际生效的那套**（档位覆盖后打印常量只会误导：曾经打出来是 `[I@8365670`）
    Log.i(TAG, "char: 命座点亮 $lit/${nodes.length()} ｜ 各节点饱和度=$satSamples（阈值 ${satMin.joinToString()}）")
}

/**
 * 角色界面**页签级**判据（用于命之座页到位检查）：
 * 三个页签左上标题完全相同（都是 `<元素>/<角色名>`），只能靠**页内独有文本**区分：
 *   · 属性页底部 = 「提升指南」  · 天赋页底部 = 「天赋演示 / 选中战斗天赋以升级」  · 命之座页底部 = **无文本**
 *
 * ⚠️ 判据区用**宽底栏** [panels.char_profile.bottomBar]（不要退回 `bottomHint`），且匹配词必须放宽
 *   （2026-09-29 修）：2560 档宽底栏左沿 =376，而按钮文字「提升指南」实测起点 **x≈366**
 *   ⇒ 左沿落在文字内 10px、「提」被切掉一角 ⇒ rec-only 路径只出 `是升指南`
 *   （三档余量：3200 = +41 / 2244 = +29 / **2560 = −10**，只有这一档会切）。
 *   旧写法用 `bottomHint`（2560 档由 2244 换算、x=394）切得更狠 ⇒ 本判据**整轮 0 次生效**，
 *   命之座页始终没到位那个分支等于没有守卫（同一条误读也打掉了 flow 里的 assertScreen）。
 *   匹配词：`升指南`（唯一来源就是 `提升指南`）/ `演示`（`天腻演示` 也含）/
 *   `中战`（`选中战斗天赋以升级` 实测会被读成 `洗中战大以升级`，取中段最稳）。
 *   ⚠️ 与 `flows/character_scan.json` 的两条 assertScreen 是**同一套词**，改一处要改两处。
 *
 * @return true = 命中属性/天赋页文本 ⇒ **不在命之座页**
 */
internal suspend fun ScanEngine.charPanelLooksWrongPage(): Boolean {
    val g = ocr ?: return false
    val f = runCatchingCancellable { freshFrame() }.getOrNull() ?: return false
    return try {
        val rects = listOf(
            profile.rect("panels.char_profile.bottomBar"),
            profile.rect("panels.char_talent.bottomBar"),
        )
        // ★ A7/G：读不到 ≠ 引擎没就绪。引擎不可用被这里吞成 getOrDefault("") ⇒ 判据恒
        //   「在命之座页」（假阴性放行）—— loud 放行。
        val t = try {
            g.readRois(f, rects).joinToString(" ")
        } catch (e: OcrUnavailableException) {
            throw e
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            ""
        }
        val wrong = t.contains("升指南") || t.contains("演示") || t.contains("中战") || t.contains("战斗天赋")
        if (wrong) Log.i(TAG, "char: 页签级判据命中非命之座页文本：'$t'")
        wrong
    } finally {
        f.release()
    }
}

/** 天赋：3 个战斗天赋等级 OCR（panels.char_talent.lvRois）。 */
internal suspend fun ScanEngine.readTalent() {
    val gateway = ocr ?: return
    val lv = profile.rawObject("panels.char_talent")?.optJSONArray("lvRois") ?: return
    val rects = ArrayList<FrameRect>()
    for (i in 0 until lv.length()) {
        val a = lv.getJSONArray(i)
        rects.add(FrameRect(a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3)))
    }
    val frame = try {
        freshFrame()
    } catch (e: CancellationException) { throw e } catch (_: Exception) {
        return
    }
    var texts = try {
        gateway.readRois(frame, rects)
    } finally {
        frame.release()
    }
    val samples = ArrayList<IntArray>()
    samples.add(IntArray(3) { i -> talentLevelOf(texts.getOrNull(i)) })
    for (i in 0 until Math.min(3, texts.size)) {
        val raw = samples.last()[i]
        if (raw > 0) {
            charTalents[i] = raw
        } else if (raw < 0) {
            Log.w(TAG, "char: 天赋第${i + 1}格读数越界 ⇒ 弃用（原文='${texts.getOrNull(i)}'）")
        }
    }
    // ★ 2026-09-17（三修）：**多次重读**，直到三项都 >0（或打满 [TALENT_RETRY_MAX]）。
    //   实测 cver13：「天赋」页切换与「命之座」页一样有 ~2.5s 交叉淡入 ⇒ 单次 350ms 重读**仍读不到**
    //   （原文第 3 ROI = `'散步'`/`'設行'` 之类汉字 ⇒ 读数 0；Ayaka/Mona 连续两轮都错在第 3 项）。
    //   ⇒ 停止条件改成「三项都 >0」—— 全部战斗天赋基础等级**恒 ≥1**（GT 最小值 1）✓
    //   ⚠️ 2026-09-30 #153：合并律**不再是 max**，改成逐格投票（见 [voteTalentLevel]）。
    //   原注释「OCR 失配恒为欠读、不会多读」**已被实测证伪**：2244 六轮里偏大错读全是 max 留下的
    //   （Diona 首读 `4Lv.1`→4、Layla `.L4`→4，GT 都是 1；Skirk 一度导出 410）。
    //   循环节奏/停止条件/延迟**一个都没动**，只换合并律。
    var attempts = 0
    // ★ 停止条件：**至少完成 1 次额外读**（防"非零误读"，实测 cver14 Chevreuse 首读 skill='Lv.1'
    //   而真值 11 ⇒ 只判 `>0` 会直接采信 ✗）**且**三项都 >0（全部战斗天赋基础等级恒 ≥1）
    //   **且**各格已**不再平票**（#166）。
    // ★ 2026-10-01 #166：只判「三项都 >0」时 2 个样本就够，而两样本的任何分歧**恰好是平票**
    //   ⇒ 取值完全由平票方向单方面决定，多数票退化成一帧定终身。
    //   取证（2244 三轮 19:30 / 19:45 / 20:58，逐访问把 `天赋重读` 序列还原成样本后回放）：
    //   · **已入库**的错值 1 条：19:30 轮 随机姓名(=Manekina) `auto` 读数 `[10,10,10]` 后跟
    //     `[1,10,10]`（第 2 帧原文 `'Lv.1o'`＝末位 `0` 被认成字母 `o`）⇒ 平票取更晚 = 1，
    //     导出件 `20261001_1941_2244_character_HEAD.json` 里 auto=1，真值 10。
    //   · 未入库但同形 2 次：Fischl burst `[8,13,12]` 后跟 `[8,13,2]`（`'•Lv.i2'`＝首位 `1`
    //     认成 `i`）⇒ 取 2，再按 c6 减 3 夹到下限 1（真值面板 12 / GT 9）。两轮都落在**第二遍
    //     访问**（`char 重复 ⇒ 不入库`）⇒ 只是没造成损失，首遍同形就会写进 GOOD。
    //   · 反例 1 次：Citlali 20:58 轮 `[4,10,10]` → `[6,10,10]`（第 1 帧原文没打进日志、读到 4，
    //     第 2 帧 `'WLv.6'`→6）⇒ 平票取更晚这次**恰好对**。所以平票不是恒错，是"没有多数票时只能赌"。
    //   ⇒ 两手一起做：**采到不平票才停**（本处）+ **平票方向改成取更大**（见 [voteTalentLevel]，
    //     锚定后 12 次分歧里每条错读都是**掉字**⇒偏小，取更大 9/9 对、取更晚只 6/9）。
    //   代价已按同三轮日志逐访问回放量过：**156/160 次访问仍是 2 个样本**（20:58 轮），
    //   只有真出现分歧的那 1~6 次会多读 1~4 次（每次 700ms），上限仍是 [TALENT_RETRY_MAX]。
    //   ⚠️ 旧的 max 合并在 Manekina/Fischl 这两例**都是对的** ⇒ 本改法不是回退 #153：
    //   多数票仍然保留（它挡"单帧偶发"，不依赖方向），只是不再让平票冒充多数票。
    //   循环节奏/延迟/上限一个都不动。
    while (attempts < TALENT_RETRY_MAX) {
        if (attempts > 0 && charTalents.none { it <= 0 } && !talentSamplesTied(samples)) break
        attempts++
        delay(TALENT_REREAD_DELAY_MS)
        val f2 = runCatchingCancellable { freshFrame() }.getOrNull() ?: break
        // ★ A7/G：重读循环的「读失败 ⇒ continue」只该作用于真读失败；引擎不可用必须 loud。
        val t2 = try {
            gateway.readRois(f2, rects)
        } catch (e: OcrUnavailableException) {
            throw e
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            null
        }
        f2.release()
        if (t2 == null) continue
        val before = charTalents.toList()
        texts = t2
        val v2 = IntArray(3) { i -> talentLevelOf(t2.getOrNull(i)) }
        samples.add(v2)
        for (i in 0 until Math.min(3, t2.size)) {
            if (v2[i] > 0) {
                if (v2[i] > charTalents[i]) charTalents[i] = v2[i]
            } else if (v2[i] < 0) {
                Log.w(TAG, "char: 天赋第${i + 1}格重读越界（非等级值）⇒ 弃用（原文='${t2.getOrNull(i)}'）")
            }
        }
        Log.i(
            TAG,
            "char: 天赋重读 #$attempts $before → ${charTalents.toList()} ｜ 原文=${t2.take(3).map { "'" + it + "'" }}",
        )
    }
    // ★ 2026-09-30 #153：**合并律从 max 改为「多数票」**。
    //   旧注释「OCR 失配恒为欠读 ⇒ 取 max 安全」当时**已被实测证伪**：错读也会**偏大**
    //   （2244 六轮实测：Diona 首读 `4Lv.1`→4 / Layla `.L4`→4，GT 真值都是 1；
    //    Kaeya 导出 (7,2,2) vs GT (3,2,2)）。max 让这类偏大错读**永久压住**后面读到的真值。
    //   多数票能同时挡住「早到的淡入期错读」和「晚到的瞬时错读」，max/first/last 各只挡一侧。
    //   ⚠️ 那三条**偏大**样本都是同一个解析缺陷（「取串里第一个数」把邻格碎字当等级），
    //   #153 在改合并律的**同一次提交**里把 [talentLevelOf] 改成锚定 `v` 之后就再没复现过；
    //   #166 复查留存日志时，锚定后的 12 次分歧读数里 0 次偏大（详见 [voteTalentLevel] KDoc）。
    //   ⇒ 保留多数票（它挡的是"单帧偶发"，不依赖方向），但**平票方向已改成取更大**（#166）。
    //   ⚠️ 只在**非零票**之间投票：0 = 「这次没读到」，不是「读到 0」，不能参与计数。
    //   平票 ⇒ 取**更大**的那个值（#166 改的方向；原判据与 9/9 取证见 [voteTalentLevel] KDoc）。
    val voted = IntArray(3) { i -> voteTalentLevel(samples, i) }
    if (voted.toList() != charTalents.toList()) {
        Log.i(
            TAG,
            "char: 天赋合并律生效 max=${charTalents.toList()} → 投票=${voted.toList()} " +
                "｜ ${samples.size} 次读数 ${samples.joinToString("") { "[${it.joinToString(",")}]" }}",
        )
        for (i in 0 until 3) if (voted[i] > 0) charTalents[i] = voted[i]
    }
    // ★ 2026-10-01 #166：打到 [TALENT_RETRY_MAX] 仍平票 ⇒ 这一格不是多数票判的，是平票方向判的
    //   （掉字型错读是持续性的，多采几次未必自己收敛）。必须出声——静默采信一帧正是 #166 的形态。
    if (talentSamplesTied(samples)) {
        Log.w(
            TAG,
            "char: 天赋 $attempts 次重读后仍有平票 ⇒ 该格由「平票取更大」决定（不是多数票）" +
                " ｜ 取值=${charTalents.toList()} ｜ 读数=${samples.joinToString("") { "[${it.joinToString(",")}]" }}",
        )
    }
    // ── ★ 2026-09-17（四修）：**「多一行」回退**（冲刺技占行的角色）──
    //   实测 cver15：神里绫华 / 莫娜 的「战斗天赋」组里**多插一行无 Lv 的冲刺技**
    //   （神里流·霰步 / 虚实流动）⇒ 第 3 行（元素爆发）的 Lv 位置读到的是那一行的**行名**，
    //   且 6/6 次重读完全一致（原文 `'散步'`＝霰步、`'实流动'`＝虚实流动）—— **不是时序问题** ✗。
    //   判据：该行读数为 0 **且** 原文含汉字（= 读到了名称）⇒ 真值在**下一行** ⇒
    //   用同一 x、y + **本档行距**的 ROI 补读一次（行距见 [talentRowPitch]：2560/3200≈152、2244=114）。
    //   ⚠️ 只在"读到汉字名称"时启用：若某行只是 OCR 失败（原文为空/乱码），下移一行会**串行** ✗
    val burstText = texts.getOrElse(2) { "" }
    //   判据再收紧：必须**含汉字**（读到名称）**且不含数字**（含数字说明只是被符号污染，
    //   真值就在本行 ⇒ 下移会串行 ✗）；且补读结果必须**落在合法等级区间 [1, TALENT_LEVEL_MAX]**。
    //   ⚠️ 实测 cver18 Shenhe：条件过宽 ⇒ 补读到 19（非等级值）✗ ⇒ 加区间校验兜住。
    val burstHasCjk = burstText.any { it in '\u4e00'..'\u9fa5' }
    val burstHasDigit = burstText.any { it.isDigit() }
    if (charTalents[2] <= 0 && burstHasCjk && !burstHasDigit) {
        val r3 = rects.getOrNull(2)
        if (r3 != null) {
            // ★ 2026-09-30 #152：行距**从本档 lvRois 自身推**（见 [talentRowPitch]）。
            //   写死 153 只在 2560/3200 成立；2244 的 lvRois 行距是 113/114 ⇒ 补读落到空处、
            //   OCR 空手而归 ⇒ 静默保持 burst=0（旧代码该分支不打日志，三档里只有 2244 中招）。
            val pitch = talentRowPitch(rects)
            val shifted = FrameRect(
                r3.left,
                r3.top + pitch,
                r3.right,
                r3.bottom + pitch,
            )
            val f3 = runCatchingCancellable { freshFrame() }.getOrNull()
            if (f3 != null) {
                val t3 = runCatchingCancellable { gateway.readRois(f3, listOf(shifted)) }.getOrNull()?.firstOrNull()
                f3.release()
                val v = talentLevelOf(t3)
                if (v > 0) {
                    charTalents[2] = v
                    Log.i(TAG, "char: 第3天赋行读到行名（'$burstText'）⇒ 下移一行补读 burst=$v（原文='$t3' 行距=$pitch）")
                } else if (v < 0) {
                    Log.w(TAG, "char: 下移一行补读得越界值（非等级值）⇒ 弃用（原文='$t3'）")
                } else {
                    Log.w(TAG, "char: 第3天赋行读到行名（'$burstText'）⇒ 下移一行补读未得等级（原文='$t3' 行距=$pitch）")
                }
            }
        }
    }
    // ── ★ 2026-10-01 #155：命座天赋加成改**数据驱动**（GOODScanner `adjust_talents` 同法）──
    //   旧实现是一张 GT 监督反推的手表（51 角色 ×「本账号当时那一档命座」），换个命座档就查不到 ⇒
    //   落进 `c≥3⇒E / c≥5⇒Q` 的近似规则。而词典里 `c3`/`c5` 一直就有（120/123 角色齐全，此前被
    //   中间那层 re-encode 丢掉），且**绫华 / 莫娜 / 琴…方向正好相反**（c3=Q、c5=E）⇒ 近似规则两头都减错，
    //   表现就是 #155 的「成片少减 3」。手表 51 条里 50 条与本规则逐位一致，唯一差异是 Tartaglia
    //   的普攻 +1（与命座无关，旧表把它塞进 c=0 那一格 ⇒ 达达利亚一旦有命座就丢掉这个 +1）。
    val c = charConstellation
    // 旅行者的 GOOD 键带元素后缀（TravelerDendro…），而词典只有一个 `Traveler` 条目 ⇒ 归一到基名再查。
    val dictKey = lastCharKey?.let { k -> if (TRAVELER_BY_ELEMENT.containsValue(k)) "Traveler" else k }
    val attrs = dictKey?.let { names?.charAttrs?.get(it) }
    if (dictKey != null && attrs == null) {
        Log.w(TAG, "char: 词典里没有角色 $dictKey ⇒ 命座加成无从可查，天赋按面板原值入库")
    }
    val (bonus, suspicious) = applyConstellationTalentBonus(charTalents, dictKey, attrs, c)
    if (suspicious) {
        Log.w(
            TAG,
            "char: $dictKey c=$c 该减加成的天赋行读数 <4（面板原值=${texts.take(3).map { "'" + it + "'" }}）" +
                "⇒ 疑似命座误读，导出可能偏 3",
        )
    }
    // 诊断：实测我方天赋 = GT + 3（恒差，非命座加成 ✗）⇒ 打印 OCR 原文与 ROI 定位
    Log.i(
        TAG,
        "char: 天赋等级 ${charTalents.toList()} ｜ 原文=${texts.take(3).map { "'" + it + "'" }} " +
            "｜ bonus=${bonus.toList()} key=$dictKey c=$c attrs(c3,c5)=${attrs?.c3}/${attrs?.c5} " +
            "｜ ROI=${rects.take(3).joinToString { "(${it.left},${it.top},${it.right},${it.bottom})" }}",
    )
}

/**
 * 命座对天赋行的加成扣减（#155，GOODScanner `character::scanner::adjust_talents` 同法）。
 *
 * 天赋面板显示的是**含加成值**，而 GOOD/GT 要的是**基础值** ⇒ 必须把命座抬上去的 +3 减回来。
 * 减哪一行由词典决定：[GoodNames.CharAttrs.c3]/[GoodNames.CharAttrs.c5] 给出第 3 / 第 5 层
 * 抬的是 `A` 普攻 / `E` 元素战技 / `Q` 元素爆发。**不要按「c≥3⇒E、c≥5⇒Q」近似** ——
 * 绫华 / 莫娜 / 琴 等人是 `c3=Q、c5=E`，方向正好相反，近似规则会两头都减错。
 *
 * 就地修改 [talents]，返回「实际减掉的量」与 [suspicious]。三条口径：
 * - `talents[i] == 0` 表示**这一行没读到**（不是读到 0）⇒ 跳过不减，否则凭空造出等级；
 * - 减完下限是 **1**（游戏里天赋最低 1 级），不是 0；
 * - [suspicious]：该减加成的行读数 <4 ⇒ 十有八九是命座读错或本行 OCR 错。上游用它触发角色重扫，
 *   本仓先只报日志（重扫是行为改动，要真机验）。
 *
 * 两个特例：达达利亚的普攻恒 +1（与命座无关）；旅行者命座按元素各有一套、词典里不给 c3/c5，
 * 沿用上游推定 —— c≥5 时 E/Q 各 +3，否则**只有超过基础上限 10 的行**才可能含命座加成。
 */
internal fun applyConstellationTalentBonus(
    talents: MutableList<Int>,
    dictKey: String?,
    attrs: GoodNames.CharAttrs?,
    constellation: Int,
): Pair<IntArray, Boolean> {
    val bonus = IntArray(3)
    var suspicious = false
    fun rowOf(t: String?) = when (t) { "A" -> 0; "E" -> 1; "Q" -> 2; else -> null }
    fun sub3(i: Int) {
        val v = talents[i]
        if (v <= 0) return
        if (v < 4) suspicious = true
        talents[i] = (v - 3).coerceAtLeast(1)
        bonus[i] += 3
    }
    if (dictKey == "Tartaglia" && talents[0] > 0) {
        talents[0] = (talents[0] - 1).coerceAtLeast(1)
        bonus[0] += 1
    }
    when {
        attrs != null && (attrs.c3 != null || attrs.c5 != null) -> {
            if (constellation >= 3) rowOf(attrs.c3)?.let(::sub3)
            if (constellation >= 5) rowOf(attrs.c5)?.let(::sub3)
        }
        dictKey == "Traveler" -> {
            if (constellation >= 5) {
                sub3(1)
                sub3(2)
            } else {
                if (talents[1] > 10) sub3(1)
                if (talents[2] > 10) sub3(2)
            }
        }
        // 有词条但确实无加成（奇偶无命座系统），或词典里没有这个角色 ⇒ 什么都不减。
        else -> {}
    }
    return bonus to suspicious
}

/**
 * 导出用的元素名（GOOD 写法 'Hydro' 这类），★ 2026-10-01 #167：**不再从页头 OCR 取**。
 *
 * 触发样本（22:01 轮 2244）：Columbina 首遍访问页头读成 `'永元素/哥伦比处'` ⇒ 导出
 * `element='永'`（GT 是 Hydro），而第二遍访问读到正确的 `'水'` —— 但那一遍被判成
 * `char 重复 ⇒ 不入库`，正确值直接丢了。element 和 level 一样是**单样本字段**，
 * 没有天赋那套重读+投票，一帧错读就是终值。
 *
 * 正解不是"多采几帧"，而是**这个字段根本不需要采**：角色 key 一定，元素就是定值，
 * 字典里查得到。GOODScanner 同一条划分（`character/scanner.rs::ELEMENT_CHARACTERS`）：
 * 只有**旅行者 / 偶数 / 奇偶**三个可改名角色的元素会随游戏内实时变（字典恒为 anemo），
 * 他们才用页头 OCR，其余角色连 element 都不写。
 *
 * 与 GOODScanner 的**一处差异**（故意的）：我们照常给所有角色写 element，
 * 因为对账侧 `good_vs_gt.py` 拿 GT（抓包，人人都有 element）逐位比，缺字段就等于少一道闸。
 */
internal fun ScanEngine.exportElement(dictKey: String?, ocrZh: String?): String? {
    val n = names
    val dynamic = dictKey != null &&
        (dictKey.startsWith("Traveler", ignoreCase = true) ||
            dictKey.startsWith("Manekin", ignoreCase = true))
    if (dictKey != null && !dynamic) {
        n?.elementOf(dictKey)?.let { return it }
        Log.w(TAG, "char: 字典里没有角色 $dictKey 的元素 ⇒ 回退页头 OCR '$ocrZh'")
    }
    val zh = ocrZh?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val good = n?.elementByZh?.get(zh)
    if (good == null) {
        Log.w(TAG, "char: 页头元素 '$zh' 不在七元素闭集里 ⇒ 原样入库（OCR 误读，对账会报出来）")
    }
    return good ?: zh
}

/**
 * 等级控件文本 → `(level, cap)`。
 *
 * 游戏里这一格是「等级80 / 90」，OCR 常把 `/` 读成 `1` ⇒ 文本可能是 `等级80/90`、`8090`、`80190`、
 * `等级80.1.90`、`80`（ROI 截断）几种形态。规则：第 1 个数字串里取**前 2~3 位中 ≤100 的最长者**为 level；
 * 上限取第 2 个数字串**起**第一个合法值（先滤掉被读成独立 token 的 `1`），都取不到则回看第 1 个串
 * **尾部**是不是合法上限（正是 `80190` 这种粘连）。
 *
 * ⚠️ 上限合法值含 **95**：5.x 起可再突破到 95/100，而超过 90 的等级只有 95、100 两档
 * （不会出现 91/96 之类），所以 level=95/100 本身就是"已突破到该上限"的证据。
 */
internal fun parseLevelText(text: String): Pair<Int, Int?> {
    val nums = Regex("\\d+").findAll(text).map { it.value }.toList()
    val level = nums.firstOrNull()?.let { d ->
        d.take(3).toIntOrNull()?.takeIf { it <= 100 } ?: d.take(2).toIntOrNull()
    } ?: 0
    // 斜杠被读成 `1` 时它是**独立的一个 token**（`等级80 / 90` → `等级80.1.90` → ["80","1","90"]），
    // 上限排在第 3 个数字上。`1` 不可能是合法上限（最小上限是 20），直接滤掉再找。
    val tail = nums.drop(1).filterNot { it == "1" }
    val cap = tail.mapNotNull { it.toIntOrNull() }.firstOrNull { it in VALID_LEVEL_CAPS }
        ?: nums.firstOrNull()?.let { d ->
            VALID_LEVEL_CAPS.firstOrNull { d.length > it.toString().length && d.endsWith(it.toString()) }
        }
    return level to cap
}

/**
 * `(level, cap)` → ascension。**cap 是唯一可靠来源**：level 恰好等于某个突破门槛时，
 * "卡在本阶段上限(asc=i)"与"已突破到下一阶段(asc=i+1)"的等级数字完全一样，只看 level 分不开。
 *
 * 2026-10-01 #165 的教训：2244 档的 level ROI 曾在 09-11 被收窄到只框住白色等级数字
 * （因为那时还没有上面那套粘连解析），于是 cap 恒 null、一律走 level 分层 ⇒
 * **每一位 level 80 的角色都被报成 asc 5**，与 GT 差 8 位（另 5 位真 asc 5 只是蒙对）。
 */
internal fun ascensionOf(level: Int, cap: Int?): Int {
    val c = cap ?: 0
    if (c > 0) return when {
        c >= 90 -> 6; c >= 80 -> 5; c >= 70 -> 4
        c >= 60 -> 3; c >= 50 -> 2; c >= 40 -> 1; else -> 0
    }
    return when { // 回退：只按 level 分层（门槛处会猜，调用方负责打日志）
        level >= 81 -> 6; level >= 70 -> 5
        level >= 61 -> 4; level >= 50 -> 3
        level >= 41 -> 2; level >= 21 -> 1; else -> 0
    }
}

/** 游戏里合法的等级上限（5.x 含 95/100）。长的排前面，让 `80100` 这类粘连优先配到 `100`。 */
private val VALID_LEVEL_CAPS = listOf(100, 95, 90, 80, 70, 60, 50, 40, 20)

/** cap 读不到时，这些 level 值真的无法判定 ascension（= 各阶段的突破门槛）。 */
private val AMBIGUOUS_LEVELS = setOf(20, 40, 50, 60, 70, 80, 90)

/** navigate 后按 `read` 数组判读页面（char_constellation / char_talent）。 */