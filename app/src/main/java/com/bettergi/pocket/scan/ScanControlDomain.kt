package com.bettergi.pocket.scan

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Mat
import com.bettergi.pocket.scan.ScanEngine.Companion.CARD_SIG_BLOCKS
import com.bettergi.pocket.scan.ScanEngine.Companion.CHAIN_ANCHOR_PATHS
import com.bettergi.pocket.scan.ScanEngine.Companion.CLICK_RETRY_MAX_ON_NOCHANGE
import com.bettergi.pocket.scan.ScanEngine.Companion.CLICK_SETTLE_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.GRID_END_DIFF
import com.bettergi.pocket.scan.ScanEngine.Companion.GRID_SIMILAR_DIFF
import com.bettergi.pocket.scan.ScanEngine.Companion.MAX_FILTER_PAGES
import com.bettergi.pocket.scan.ScanEngine.Companion.FILTER_PAGE_CONSISTENCY_MIN
import com.bettergi.pocket.scan.ScanEngine.Companion.FILTER_PAGE_CONSISTENCY_RETRY
import com.bettergi.pocket.scan.ScanEngine.Companion.FILTER_PAGE_CONSISTENCY_WAIT_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.MAX_FILTER_PAGE_TURNS
import com.bettergi.pocket.scan.ScanEngine.Companion.PANEL_CHANGE_WAIT_MAX_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.PANEL_REFRESH_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.SETTLE_MAX_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.SETTLE_STABLE_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.SNAP_TOL
import com.bettergi.pocket.scan.ScanEngine.Companion.TAG
import com.bettergi.pocket.recognition.name.GoodNames
import com.bettergi.pocket.log.RecognitionLog
import com.bettergi.pocket.notice.NoticeCenter
import android.os.SystemClock
import com.bettergi.pocket.recognition.name.NameMatcher

/*
 * Stage 3.3 步④：ScanControlDomain（do 原语按族拆分，扩展函数，函数体一字未改）。
 */

internal fun ScanEngine.characterKeyOfDisplay(raw: String, dict: JSONObject?): String? =
    nameOverrides.ownerKeyOf(raw)
        ?: names?.match(raw, GoodNames.Kind.CHARACTER, dictFuzzyOf(dict))?.key

/**
 * #107：装配动作**发出之后**的独立复核 —— 读 `panels.artifact_manage.equipped`
 * （详情面板底部的「XX已装备」），把显示名归一成 GOOD key 后与目标角色比对。
 *
 * 为什么不用"左下按钮有没有翻成「卸下」"当判据：那颗钮的 rect 是 (2430,1291)-(2524,1345)，
 * 与角色页右下角的「替换」钮（实测 ≈2682,1315）几乎同高同带 ⇒ 页面一旦退回角色页，
 * 按按钮判就会把"读到的是另一个页面的替换"当成"没装上"。而「XX已装备」说的**就是**
 * "这件在谁身上"这件事本身，不经过代理。
 *
 * 判据来源（2026-09-28 实机逐格核过，不是推的）：在圣遗物管理界面选中珐露珊穿戴的那件时
 * 该 ROI 显示「珐露珊已装备」且左下是「卸下」；选到行秋的另一件时显示「行秋已装备」且左下
 * 变成「替换」⇒ 它随选中件走，且能区分人。
 *
 * @return `true` 确认已在目标身上；`false` 确认仍在**别人**身上；
 *         `null` 没读到 / 读不出人 ⇒ 调用方记 ClickedUnverified 而不是 Failed
 *         （我们**没看见**，不等于它**没发生**；把两者混成一个标签，久了就分不清
 *         "复核器坏了"和"装配真在失败"）。昵称表没填时就会落在这一档。
 */
internal fun ScanEngine.noteDupAndMaybeStop(identity: String?, seen: Collection<String>): Boolean {
    // ★ 跳行回补期间的重读**不计**连续重复：否则 21 格重读 = "整页零新增" ⇒ 连中两次回卷判据
    //   就会把整轮扫描提前收掉（本文件 2026-09-16 回卷判据说明里的那个坑）。
    if (suppressDupStreak) return false
    val limit = if (dupLimitEffective > 0) dupLimitEffective else charDupStreakLimit
    val (next, hit) = CharDupJudge.step(seen, identity, charDupStreak, limit)
    charDupStreak = next
    vars.charDupStreak = next
    if (hit) {
        // ⚠️ 仅 `pagedGrid` 内（`dupLimitEffective > 0`）才有「页」的概念 ⇒ 走**页级确认**；
        //    角色 rosterFind / snap 等非翻页路径沿用旧的「连续 N 个重复即停」（逐位一致，勿动）。
        //    2026-09-26 方案 C：翻页阈值已改成 `cols×(traverseRows+1)`（一页 + 一行）且**跨翻页累积**，
        //    所以 artifact/weapon 两条 flow 把 `dupPageConfirm` 设成 1 —— 一次命中就等于
        //    "点完一整页无新增、再翻一页、第一行仍无新增"。确认链本身保留，因为**它是真兜底**：
        //    单测实测把终止权收归件数之后，`ScanEngineDryRunTest` 7 个用例全部 180s 超时
        //    （mock 计数器 1026 而唯一件只有 21~42 ⇒ 件数永不达标、页数上限要 54 页）。
        if (dupLimitEffective <= 0 || dupRollbackConfirmed()) {
            vars.stopRequested = true
            vars.stopReason = "stopWhen"
            vars.gridExhausted = true
            Log.i(TAG, "stopWhen triggered (连续 $next 个重复件 ≥ $limit)：$identity")
            return true
        }
        // 命中但未确认 ⇒ 疑似滑空/半页重叠：清计数、继续翻页（不停止；本件仍按调用方的去重逻辑处置）
        charDupStreak = 0
        vars.charDupStreak = 0
        return false
    }
    return false
}

/** emit as=GoodCharacter：草稿入库 + 上报进度。 */
internal fun ScanEngine.emitCharacter(step: JSONObject) {
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
        // ascension：由 `parseCharacterPanel` 算好（优先面板等级上限 Lv.X/Y 的 Y，缺失按 level 分层）
        ascension = lastCharAscension,
        // ★ 2026-09-30 #155：面板显示的是**含命座加成**的值，GT/GOOD 要基础值 ⇒ 减法在 `readTalent`
        //   里做（`applyConstellationTalentBonus`，查字典 c3/c5 是哪一行 + 旅行者那条特例），
        //   这里拿到的已经是基础值。旧的"c≥3⇒skill、c≥5⇒burst"近似规则连同那张 GT 反推的手表一起删了。
        talents = charTalents.toList(),
    )
    // ── 连续重复角色判据（2026-09-11 用户定：连续 3 个重复 = 遍历完）──
    // 「重复」= 该名字**已在本轮入库过**。列表回卷（Genshin 头像条可循环滚）或滑动失效时，
    // 会连续读到已扫过的角色；`reachedEnd`（指纹到底）对回卷列表永不触发，故此为**主判据**。
    // ⚠️ 按**词典 key**判重（角色身份），不按名字：名字可能读错/两名相撞 → 按名字会误杀丢件。
    // key == null（未解析出身份）→ 判据不介入，且**照常入库**（宁可多一件也不丢）。
    //   ⚠️ 故意**不**退到"按显示名去重"：CharDupJudge 的 KDoc 记着实测风险 —— 两个不同角色
    //   的 OCR 名可能撞在一起，按名去重会**静默少一件**，比"多一件待办"更糟（#146 定的口径）。
    //   #156 剩下的那半条（多条无 key 记录在下游按 key 并成一条）改在**下游**修：
    //   `good_vs_gt.py::cha_key` 现在对未解析记录退回显示名当临时键，N 条不再并成 1 条，
    //   同显示名重复出现仍然报 🔁。这里只补一个**摘要计数**，让"本轮有 N 位没解析出身份"
    //   一眼可见，不用去翻逐条 Log.w。
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
    // ★ #156：数一下"没有 GOOD key 的入库件"，进 `scan finished` 摘要（逐条只有 Log.w，
    //   翻日志才看得见；对账侧表现为"多 N 条未解析 + 缺 N 个真角色"）。
    if (c.key == null) charUnresolved++
    // #122：给「格级:」日志一个**与本格入库同源**的标签（由 pagedGrid 在本格日志后清）
    lastCharCellLabel = "c:${c.key ?: c.name}/lv${c.level}"
    Log.i(TAG, "char emit #${resultsCharacters.size}: ${c.name} lv=${c.level} c${c.constellation} t=${c.talents}")
    listener.onProgress(
        "character",
        vars.snapshot() + ("name" to c.name) + ("idx" to resultsCharacters.size),
    )
    resetCharDraft()
}

/** 重置角色草稿（不清 `lastCharName`：它供止扫判据使用）。 */
internal fun ScanEngine.resetCharDraft() {
    charName = ""
    charKey = null
    charLevel = 0
    charElement = null
    charConstellation = 0
    for (i in charTalents.indices) charTalents[i] = 0
}

/** 计划中目标的**最低稀有度**（稀有度止扫判据）。计划为空或无 rarity 字段返回 null ⇒ 判据不生效。 */
internal fun ScanEngine.minTargetRarity(): Int? {
    val plan = vars.plan ?: return null
    var min: Int? = null
    for (t in plan) {
        val r = t.optInt("rarity", -1)
        if (r > 0 && (min == null || r < min!!)) min = r
    }
    return min
}

/** §14 A4：计划中目标的最高等级（pageSkip 判据）。计划为空或无 level 字段返回 null。 */
internal fun ScanEngine.maxTargetLevel(): Int? {
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
internal fun ScanEngine.reachedEnd(a: ByteArray?, b: ByteArray?, thr: Float = GRID_SIMILAR_DIFF): Boolean {
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
/** 帧 → 灰度（落地条带 fpband 测量的搜索区用）。失败返回 null；返回值归调用方 release。 */
internal fun ScanEngine.grayOf(frame: Mat): Mat? = runCatchingCancellable {
    val g = Mat()
    if (frame.channels() == 1) frame.copyTo(g)
    else org.opencv.imgproc.Imgproc.cvtColor(frame, g, org.opencv.imgproc.Imgproc.COLOR_BGR2GRAY)
    g
}.getOrNull()

/**
 * 2026-09-14 诊断：翻页滑动**派发可见性**。
 * 此前 advance 路径 actions.swipe(...) 的返回值被丢弃且不打日志 ⇒ 派发被拒/手势没送达完全不可见
 * （2560「翻页落地 0px」长期隐身的直接原因）。所有翻页滑动都走它。
 */
internal fun ScanEngine.swipeLogged(gridKey: String, x: Int, y0: Int, y1: Int, why: String): Boolean {
    val t0 = System.nanoTime()
    val ok = actions.swipe(x, y0, x, y1)
    val ms = (System.nanoTime() - t0) / 1_000_000
    Log.i(TAG, "advance[$gridKey]: swipe($why) ($x,$y0)→($x,$y1) accepted=$ok ${ms}ms")
    return ok
}

internal suspend fun ScanEngine.setFilter(step: JSONObject) {
    val ocrGateway = ocr
    val chain = step.getJSONArray("chain")
    // §16.4 链项解析统一走 clickChainEntry（命中 CHAIN_ANCHOR_PATHS → profile 机读坐标，
    // 跨分辨率正确；字面仅 fallback）。旧实现直接 scalePoint 字面 → 2560 错位。
    for (i in 0 until chain.length()) clickChainEntry(chain.getString(i))
    // ★ 2026-09-18（P0①）：chain 走完**必须先确认已进「套装子面板」**才允许往下点。
    //   ① 后面每一步（列表几何、清空条件、确认筛选）都来自 set_filter_popup，只有站在子面板上才成立；
    //   ② 旧实现不验证就一路点下去 —— 面板没开时点的是背包网格，可能误触别的按钮。
    //   未确认 ⇒ BACK 清一层 + 重试 chain 一次；仍不行 ⇒ **放弃筛选、继续全量扫描**（not_applied），
    //   照 filterReset 的「绝不盲点」：宁可带筛选扫全量，也不在错屏上乱点。
    if (!ensureSetPanel { for (i in 0 until chain.length()) clickChainEntry(chain.getString(i)) }) {
        Log.w(TAG, "setFilter: 未确认套装子面板已打开 ⇒ 放弃筛选（not_applied），继续全量扫描")
        // 非激进：此时面板**很可能压根没打开**（例如游戏内 5★ 视图把漏斗禁用），
        // 按 BACK 只会把背包关掉 ⇒ 让 flow 的 assertScreen 去兜。
        leaveFilterPanels("setFilter 放弃", aggressive = false, homeHint = homeHintOf(step))
        return
    }
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
        // ★ 2026-09-18（P0）：一个目标都没有 ⇒ **不能"确认筛选"**（那等于应用一个空筛选、白跑一趟），
        //   直接按 BACK 退出并记 not_applied，让上层按"无筛选"扫全量。
        //   旧实现在这里调 confirmFilter ⇒ 点的是**主面板**坐标 ⇒ 子面板开着不退
        //   ⇒ 之后 foreach/panelMatch 全程对着子面板跑（真机实测：每格 verify FAILED）。
        Log.w(TAG, "setFilter: 无筛选目标（未注入 setName/targets）⇒ 不确认、直接退出（not_applied）")
        leaveFilterPanels("无筛选目标", homeHint = homeHintOf(step))
        return
    }
    val grid = profile.rawObject("grids.$gridKey") ?: return
    val columns = grid.getJSONObject("columns")
    val rowYTop = grid.getJSONArray("rowYTop")
    val leftX = columns.getJSONObject("left").getInt("checkboxX")
    val rightX = columns.getJSONObject("right").getInt("checkboxX")
    val leftBox = columns.getJSONObject("left").getJSONArray("nameBox")
    val rightBox = columns.getJSONObject("right").getJSONArray("nameBox")
    if (ocrGateway == null) return
    val rowHeight = grid.optInt("rowHeight", 120)
    // 行距以 `rowYTop` 相邻差为准（缺 rowYTop 时回退 profile 的 rowPitch 键）。
    // 三档实测：2244=102、2560=136、3200=136（2560 那份 2026-10-02 傍晚前是 133，
    // 与逐行字心差 136 不符 ⇒ 每行欠 3px、第 8 行欠 21px，已按实测改格点，见该档 labelAnchorNote）。
    // ⚠️ 本值同时是**选取窗半宽**（±pitch/2）⇒ 改 pitch 会动"末行落点离重置钮还有多少余量"，
    //   见审计 §8-5。
    val rowPitch = if (rowYTop.length() > 1) rowYTop.getInt(1) - rowYTop.getInt(0) else grid.optInt("rowPitch", 0)
    // ★ #169：「名义行顶 → 文本行中心」的实测锚（与 GridAlign 的 labelAnchor 同一套路）。
    //   缺键 = 未标定 ⇒ textLineCrop 直接返回 null ⇒ 每行都走"量不到 ⇒ 不读不点" ⇒ 恒 selected=0 ⇒ not_applied。
    //   ⚠️ 这**不是**"退回改动前的行为"（2026-10-02 审计更正本注释）：旧写法会拿名义行带硬读，
    //   而名义带在落点偏时装的是**相邻卡被裁掉的下半截字**，能"认出"套名却点在本行中心 ⇒ 读 B 点 A。
    //   未标定的档**宁可不筛**，也不静默选错套装；故本分支用 Log.w 让它一眼可见，别让"没生效"再隐身。
    val labelAnchor = grid.optInt("labelAnchor", -1)
    // ★ #169 §8-5：列表视口（bounds 的 y 向）。落点越出去就**整格不做**，不钳到边界内继续点。
    val panelBounds = grid.optJSONArray("bounds")
    val panelTop = panelBounds?.optInt(1, 0) ?: 0
    val panelBottom = panelBounds?.optInt(3, Int.MAX_VALUE) ?: Int.MAX_VALUE
    if (panelBounds == null) {
        Log.w(TAG, "setFilter: $gridKey 缺 bounds ⇒ 落点越界无从判定，末行可能点到面板外的「重置」")
    }
    if (labelAnchor < 0) {
        Log.w(TAG, "setFilter: $gridKey 未标定 labelAnchor ⇒ 整页不读不点 ⇒ 本档筛选不会生效（not_applied）；请先标定该档 labelAnchor")
    } else {
        Log.i(TAG, "setFilter: 行文本锚 labelAnchor=$labelAnchor band=$rowHeight pitch=$rowPitch")
        // ★ #169 的相位只在本键按 1:1 标定时成立：textLineCrop 拿**原始 profile 整数**索引 Mat，
        //   而 clickAt/checkboxChecked 会 profile.scale ⇒ scale≠1 时"读的带"与"点的位"分叉。
        //   三档各有专属 profile 文件 ⇒ 今天恒 1.0；只有非整档设备回落 baseline 才可能不为 1，
        //   那种配置下本路径未测过 ⇒ 只告警、不改算法（盲改缩放会把所有裁剪框整体平移，风险更大）。
        if (profile.scaleX != 1.0 || profile.scaleY != 1.0) {
            Log.w(TAG, "setFilter: $gridKey scale=${profile.scaleX}x${profile.scaleY} ≠ 1 ⇒ 行位相位按 1:1 标定，本档未测，读数与落点可能分叉")
        }
    }
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
    // ★ 2026-09-29 #140 诊断（**只记日志，不中止**）：把"回顶后首行读到什么"打出来。
    //   ⚠️ 原版把它做成了"读不到锚点就重试/放弃筛选"，**已撤回**——锚点的前提是错的：
    //   19:12/19:18 两次读到「流浪大地的乐团」被我当成"顶页首行"，但 21:29 同一条流程回顶后
    //   首行读的是「华馆梦醒形骸记 / 角斗士的终幕礼」⇒ 那两次读到的**也不是顶页**
    //   （同样是"画面没变就判到顶"的产物）。基于坏测量的判据比没有判据更糟：它会把一个
    //   **本来能用**的筛选判死（实测：abort 后退化成无筛全量扫 ⇒ 目标 NotFound）。
    //   ⇒ 保留读数（下一次真机跑就能看出"顶页到底该是什么"），判决仍归 swipeGridToTop。
    val topRowKey = grid.optString("topRowKey", "")
    if (topRowKey.isNotEmpty()) {
        val yTopProbe = rowYTop.getInt(0)
        logTopRowRead(ocrGateway, lookup, leftBox, yTopProbe, rowHeight, rowPitch, labelAnchor, topRowKey)
        logTopRowRead(ocrGateway, lookup, rightBox, yTopProbe, rowHeight, rowPitch, labelAnchor, topRowKey)
    }
    var guard = 0
    var turns = 0
    var selected = 0
    // ★ A41（#147）：本页因"自洽度不足"重读的次数 + 上一次读数的逐行文本（翻页成功后都复位）
    var selfConsistRetry = 0
    var lastPageReads: List<String>? = null
    // ★ #169 §6-1：本页已用过的"半行轻推"次数（翻页成功后复位）
    var pageNudges = 0
    while (pending.isNotEmpty() && guard++ < MAX_FILTER_PAGES) {
        if (vars.stopRequested) break
        var rowsRead = 0
        var rowsBlank = 0
        var rowsResolved = 0
        val pageReads = ArrayList<String>()
        fun note(side: String, y: Int, r: RowRead) {
            rowsRead++
            if (r.blank) rowsBlank++
            if (r.resolved) rowsResolved++
            if (r.matched) selected++
            pageReads += "$side$y${r.text}"
        }
        for (i in 0 until rowYTop.length()) {
            if (pending.isEmpty()) break
            val y = rowYTop.getInt(i)
            val rowCenterY = y + rowHeight / 2
            note("L", y, matchFilterRow(ocrGateway, lookup, leftBox, y, rowHeight, rowPitch, labelAnchor, leftX, rowCenterY, panelTop, panelBottom, pending, "left"))
            if (pending.isEmpty()) break
            note("R", y, matchFilterRow(ocrGateway, lookup, rightBox, y, rowHeight, rowPitch, labelAnchor, rightX, rowCenterY, panelTop, panelBottom, pending, "right"))
        }
        if (pending.isEmpty() || vars.stopRequested) break
        // ── ★ A41（#147）：**页级自洽度 + 跨次读数对比**闸门 —— 判"这页读稳了没"改看内容 ──
        //   旧判据是 `hits == 0 ⇒ 重扫一次`（§15 P1-2）：一页本来就可能**没有待选目标**（56+ 套里
        //   只挑 1~2 个），于是它把正常页当劣化页白读一整页（实测本轮 6 页全部零命中 ⇒ 全白读一遍），
        //   又看不见"整页乱码"本身（乱码页与无目标页同形）。
        //
        //   两道信号（00:33 轮 2244 实测逼出来的，单看第一道会误伤）：
        //   ① **自洽度** = 认出套装名的行数 / 读到非空文本的行数。正常页 1.00/0.81/0.94，
        //      劣化页 0.29~0.77。
        //   ② **跨次读数是否逐字相同**：不同 ⇒ 列表还在漂，等一等再读；相同 ⇒ 再等也不会变。
        //   ⚠️ ②里当初写的成因（"灰化低对比度 + 形近字"，指 '经冰之人'/'终火之人' 那批尾部套）
        //   **是错的**，2026-10-02 #169 复查推翻：同一批行在邻页一字不差地读对过，而"逐字相同的
        //   乱码页"每次都被**整页**命中 —— 真因是 rec-only 没有 det、名义行带又远高于一行字，
        //   列表落点偏出行带余量就把整页切成半字（见 [GridAlign.textLineCrop] 的 KDoc：离线把真截图
        //   平移即可复现，δ=−30 时 16 行只剩 1 行）。灰化只是**同时**发生在尾部，不是原因。
        //   ⇒ 行裁剪修好后这条闸门仍留着：它现在兜的是"词典缺口 / 相位确实没量到"（ph=0 且乱码）。
        //   所以：不达标 ⇒ 先重读**一次**拿第二份读数；两份逐字相同 ⇒ 判"稳定但读不出"，立刻收手
        //   （只花 1 次重读，不再打满预算）；不同 ⇒ 仍在漂，继续等到预算打满，最后 loud 带页继续。
        val nonBlank = rowsRead - rowsBlank
        val consistency = filterPageConsistency(rowsRead, rowsBlank, rowsResolved)
        Log.i(
            TAG,
            "setFilter 页#${turns + 1}: 读 $rowsRead 行（空 $rowsBlank）⇒ 认出套装 $rowsResolved/$nonBlank " +
                "自洽度=${"%.2f".format(consistency)} 重读 $selfConsistRetry/$FILTER_PAGE_CONSISTENCY_RETRY",
        )
        if (consistency < FILTER_PAGE_CONSISTENCY_MIN) {
            val settled = filterPageStable(lastPageReads, pageReads)
            if (lastPageReads != null && settled) {
                Log.i(
                    TAG,
                    "setFilter: 第${turns + 1}页两次读数**逐字相同**（自洽度 ${"%.2f".format(consistency)}）" +
                        "⇒ 列表已静止，再读也不会变（读得出的行都认得 / 读不出的全是词典或行格漂移）⇒ 停止重读",
                )
            } else if (selfConsistRetry < FILTER_PAGE_CONSISTENCY_RETRY) {
                selfConsistRetry++
                Log.w(
                    TAG,
                    "setFilter: 第${turns + 1}页自洽度 ${"%.2f".format(consistency)} < " +
                        "$FILTER_PAGE_CONSISTENCY_MIN（乱码 ${nonBlank - rowsResolved} 行）" +
                        (if (lastPageReads == null) "⇒ 先重读一次拿对比基准"
                         else "⇒ 两次读数不同，判列表仍在慢漂") +
                        "，等 ${FILTER_PAGE_CONSISTENCY_WAIT_MS}ms 重读本页（第 $selfConsistRetry/" +
                        "$FILTER_PAGE_CONSISTENCY_RETRY 次）",
                )
                lastPageReads = pageReads
                delay(FILTER_PAGE_CONSISTENCY_WAIT_MS)
                continue
            } else {
                Log.w(
                    TAG,
                    "setFilter: 第${turns + 1}页重读满 $FILTER_PAGE_CONSISTENCY_RETRY 次，自洽度仍 " +
                        "${"%.2f".format(consistency)} 且读数仍在变 ⇒ 带着本页继续（未点完=$pending）",
                )
            }
        }
        selfConsistRetry = 0
        lastPageReads = null
        // ★ #169 §6-1：整页大面积"量不到" ⇒ 落点挤在 ±半行距 的**窗沿**，本行与邻行的字带离锚
        //   几乎等距，被歧义门成批拒判（2560 尾页实测 7/16；代价口径见审计 §10-附2 —— 漏格不是
        //   "慢一点"，是这一轮的目标达不成）。沿 advance 同一 x **往回**轻推半行，把 δ 从窗沿挪到
        //   窗中间，再读**同一页**。
        //   ⚠️ 必须是**往回**推：往前推会把本页总前进量抬到计划之外，那正是 #75
        //   「指纹没变就重发 ⇒ 一页滑两次 ⇒ 静默跳 2 页」的形状；往回推只多重叠半行（白花时间，不丢内容）。
        //   每页最多 1 次：推完还读不到，说明不是窗沿问题，继续推只会白烧。
        if (advance != null && pending.isNotEmpty() &&
            shouldNudgeFilterPage(rowsRead, rowsBlank, pageNudges, FILTER_PAGE_NUDGE_BUDGET)
        ) {
            pageNudges++
            val nx = profile.scale(advance.getJSONArray("from").getInt(0), profile.scaleX)
            val ny = profile.scale(advance.getJSONArray("to").getInt(1), profile.scaleY)
            Log.i(
                TAG,
                "setFilter: 第${turns + 1}页 $rowsBlank/$rowsRead 行量不到 ⇒ 往回轻推半行重读本页" +
                    "（第 $pageNudges/$FILTER_PAGE_NUDGE_BUDGET 次）",
            )
            actions.swipe(nx, ny, nx, ny + profile.scale(rowPitch / 2, profile.scaleY))
            // 半行位移喂给 24×16 缩略图的"确实变了"判据太弱 ⇒ 这里只等画面静止。
            awaitGridStable(profile, gridKey).release()
            continue
        }
        pageNudges = 0
        // §12.4-⑤ 本页未点完 → 翻页继续
        if (advance == null) break
        // ★ 2026-09-18（P0③）：改用**固定页数上限**（对齐 GOODScanner 的 max_scrolls=5），
        //   删掉原来的"指纹不变 ⇒ 到底"判据 —— 那条会误判：手势是**异步派发**的，
        //   若画面起步晚于判稳窗，`awaitGridStable` 会把**滑动前的静止帧**当稳定帧返回，
        //   于是 thumbAfter ≈ thumbBefore ⇒ 判"到底" ⇒ 真机实测 16/56 个套装只走一页就退出。
        if (turns >= MAX_FILTER_PAGE_TURNS) {
            Log.w(TAG, "setFilter: 已翻 $MAX_FILTER_PAGE_TURNS 页仍未点完 ⇒ 停止翻页（未点完=$pending）")
            break
        }
        turns++
        val advFrom = advance.getJSONArray("from")
        val advTo = advance.getJSONArray("to")
        actions.swipe(
            profile.scale(advFrom.getInt(0), profile.scaleX),
            profile.scale(advFrom.getInt(1), profile.scaleY),
            profile.scale(advTo.getInt(0), profile.scaleX),
            profile.scale(advTo.getInt(1), profile.scaleY),
        )
        // requireChange=true：必须**先看到列表变了**才允许判稳定；超窗未变会告警，
        // 那种情况才真的可能是"滑动未送达 / 确实到底"。
        val stable = awaitGridStable(profile, gridKey, requireChange = true)
        stable.release()
    }
    if (pending.isNotEmpty()) Log.w(TAG, "setFilter: 目标未全部点选，剩余=$pending")
    // ★ 2026-09-18（P0）：一个都没勾上 ⇒ 同样**不确认**，退出并记 not_applied。
    //   （部分勾上则照旧确认：有筛选总比没有好，未点到的由后面的逐格匹配兜。）
    if (selected == 0) {
        Log.w(TAG, "setFilter: 一个目标都没勾上（selected=0，翻页 $turns 次）⇒ 不确认、直接退出（not_applied）")
        leaveFilterPanels("selected=0", homeHint = homeHintOf(step))
        return
    }
    Log.i(TAG, "setFilter: 已勾选 $selected 个目标（翻页 $turns 次，未点完=$pending）⇒ 确认筛选")
    confirmFilter(step)
}

/** 取当前帧网格缩略图（失败返回 null），用于到底判据（差异比例阈值）。 */
internal suspend fun ScanEngine.foreach(step: JSONObject) {
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
    // ★ 逐目标循环必须**每项复位流程状态**（止扫计数/页间游标；GOOD 结果跨目标累加去重，
    //   见 resetScanAccumulation 的 KDoc，2026-10-01 裁决）
    val resetPerItem = step.optBoolean("resetScanPerItem", false)
    manageResults.clear()
    for ((idx, item) in items.withIndex()) {
        if (resetPerItem) resetScanAccumulation()
        vars.currentTask = item
        vars.matchHit = false
        vars.actTried = false
        vars.actOk = false
        vars.actVerified = null
        vars.equipClicked = false
        vars.lockWriteAttempted = false
        vars.gridExhausted = false
        val label = taskLabel(item)
        // #108：本项**自己**扫了多少格。`scan finished: cells=` 是整轮累计，分不出
        // "这一项根本没开始扫"和"扫了 800 格没找到"—— 而 auto_equip 的两种失败
        // （面板陈旧假重复早停 / 目标在更靠后的页）在结果上同形，只能靠这个数区分。
        val cellsAtItemStart = tmCells
        // ★ #136：本项是否被"界面未知"中止。中止项**不能**落进 manageStatusOf 的 else 支
        //   （那会记 NotFound = "扫完了、没这件"，而我们根本没扫）⇒ 单独记 Failed。
        var itemAborted = false
        val pagesAtItemStart = tmPages
        Log.i(TAG, "foreach iter $asName[$idx/${items.size}]: $label")
        for (i in 0 until subSteps.length()) {
            executeStep(subSteps.getJSONObject(i))
            // ★ #136：某一步断言"我还在不在该界面上"失败 ⇒ 本项余下步骤**一步都不再执行**
            //   （尤其不能再跑 pagedGrid：它只会按坐标点格子，不知道屏上是什么）。
            if (vars.abortItem) {
                Log.w(TAG, "foreach: 本项被 abortItem 中止 ⇒ 跳过余下动作步骤（防对着未知界面点格子）")
                RecognitionLog.log(
                    logTag,
                    RecognitionLog.Level.W,
                    "本项中止：断言未通过，界面未知 ⇒ 跳过余下动作（${taskLabel(item)}）",
                )
                vars.abortItem = false
                itemAborted = true
                break
            }
            if (!vars.stopRequested) continue
            // ★ 2026-09-18：区分「本项的网格止扫」与「整轮停」——
            //   前者（stopWhen/maxPages）只是"本项这一趟 pagedGrid 扫够了"，**本项余下步骤必须继续跑**。
            //   真机事故：auto_equip 的 pagedGrid 命中目标后置了 stopRequested，内层循环一见标志就 break
            //   ⇒ 后面的 「替换」 与 「换装确认弹窗」 两步**从未执行**（perf 里根本没有这两步），
            //   却照样报 AlreadyCorrect。装配流程因此"看着跑完了、实际没换"。
            val perItemStop = vars.stopReason == "stopWhen" || vars.stopReason == "maxPages"
            if (!perItemStop) break
            if (vars.gridExhausted && !vars.matchHit) {
                // ★ 网格到底、本项仍未命中 ⇒ **不能**再往下跑「替换」：那一步无态校验、无匹配判据，
                //   会把当时屏上选中的错件装到角色身上，而状态照记 NotFound（看着跑完、实际做错）。
                //   仍按「本项正常结束」处置：记 NotFound、继续下一个目标，不算整轮停止。
                Log.w(TAG, "foreach: 本项网格到底仍未命中 ⇒ 跳过余下动作步骤（防错装）")
                RecognitionLog.log(
                    logTag,
                    RecognitionLog.Level.W,
                    "本项未命中即止：网格到底，跳过「替换」等余下动作（${taskLabel(item)}）",
                )
                vars.stopRequested = false
                vars.stopReason = null
                break
            }
            Log.i(TAG, "foreach: 本项网格止扫（${vars.stopReason}）⇒ 复位后继续本项余下步骤")
            vars.stopRequested = false
            vars.stopReason = null
        }
        // ★ #107：装配动作真点过 ⇒ 回读装备者栏做**点击后**复核。
        //   必须放在内层步骤**之后**：跨角色时 `dialog(equipConfirm)` 才是 foreach 的最后一步，
        //   在 dualStateButton 里复核会读到确认框还盖在上面的画面。
        if (vars.equipClicked) {
            // ★ 2026-09-29：卸下项（char 空 + location 非空）要走**卸下侧**的判据
            //   （"那格空没空"），不能沿用装备侧（"是不是他穿"）——两者对"空"的解释相反。
            val verifyIntent = equipIntentOf(item.optString("char"), item.optString("location"))
            vars.actVerified = if (verifyIntent == EquipIntent.UNEQUIP) {
                verifyUnequipped()
            } else {
                item.optString("char").takeIf { it.isNotEmpty() }?.let { verifyEquippedBy(it, null) }
            }
        }
        // ⚠️ 状态映射**不能**看 `vars.stopRequested` 就判 Skipped：本目标的 pagedGrid 因
        //   **稀有度止扫**（目标全 5★ ⇒ 走到 4★ 即停）而结束时也会置该标志，那是**本条扫描的正常收尾**，
        //   应记 `NotFound`（走完了、没命中）。真机实测：4 目标里后 3 个被误记 Skipped。
        //   只有**整轮停止**（exit/watchdog）才是 Skipped。
        val flowStop = vars.stopRequested &&
            vars.stopReason != "stopWhen" && vars.stopReason != "maxPages"
        val status = if (itemAborted) "Failed" else
            manageStatusOf(vars.matchHit, vars.actTried, vars.actOk, vars.actVerified, flowStop)
        manageResults.add(Triple(idx, label, status))
        // ⚠️ 这里**不能**打 `vars.stopReason`：per-item 的止扫（stopWhen/maxPages）在上面的
        //   循环里已被复位，到这儿恒为 null，会被读成"根本没止扫"。改用两个仍有效的判据：
        //   `gridExhausted`（网格是**连续重复件**到底停的，不是命中目标停的）+ 本项扫过的格数。
        //   两者合起来才能分开 auto_equip 的两种失败：
        //     扫了 3 格就 exhausted ⇒ 面板陈旧/假重复早停；扫了 800 格 exhausted ⇒ 目标真不在筛出的集合里。
        Log.i(
            TAG,
            "结果[$idx] $label ⇒ $status（本项已扫 ${tmCells - cellsAtItemStart}格 " +
                "${tmPages - pagesAtItemStart}页 命中=${vars.matchHit} 网格到底=${vars.gridExhausted}）",
        )
        // ★ 本目标处理完（stopReason 为 stopWhen/maxPages 即"本目标的网格止扫"）⇒ 复位后继续下一个目标。
        if (flowStop) {
            for (k in idx + 1 until items.size) {
                manageResults.add(Triple(k, taskLabel(items[k]), "Skipped"))
            }
            break
        }
        vars.stopRequested = false
        vars.stopReason = null
    }
    emitManageSummary()
}

/**
 * 复位**本目标一轮**的流程状态（逐目标循环 `resetScanPerItem` 专用）。
 * ★ 语义裁决（2026-10-01，P3-8 定稿）：本函数 = "每个目标一轮**全新的页/格游标与流程状态**"；
 *   GOOD 结果（results/resultsWeapons/resultsCharacters）与去重键（seenArtifactKeys）
 *   **同进退、都不清** —— 跨目标按物理件全局去重、累加导出（不重复、不丢件）。
 *
 * 为什么必须有（历史，2026-09-18）：artifact_lock 是**逐目标**跑同一张网格（每个目标一次
 * 完整走查），而"走到底"的判据全是**累积式**的 —— ① 重复件计数（`charDupStreak` /
 * `dupPageStreak`）② 回卷止扫（连续整页零新增）。第 2 个目标开始，**每一件都是"已入库"** ⇒
 * 回卷止扫在第一整页就断言"到底"并截断，排在后面的真目标永远扫不到。
 * 真机实测：目标 1 走 120s，目标 2 只走 58s，真件 `ScarletProof/flower` 被判 `NotFound`。
 *
 * 为什么旧版"清 results 但留 seenArtifactKeys"被禁止（2026-10-01 裁决）：该组合下目标 2
 * 重扫同件全部撞 seen 键被去重，而 results 已被清空 ⇒ 最终导出 0 件（丢件），违反 GOOD
 * 导出"不重复、不丢件"原则。`artifact_lock` 多目标共用一套圣遗物时：锁定等动作逐目标执行；
 * 导出按物理件去重、跨目标累加。语义钉测试见 ScanEngineDryRunTest 的
 * `resetScanPerItem dedupes identical content across targets and keeps all physical pieces`。
 */
internal fun ScanEngine.resetScanAccumulation() {
    // ── 全局累积，**不清**（2026-10-01 裁决：results 与 seenArtifactKeys 同进退）──
    // results / resultsWeapons / resultsCharacters / seenArtifactKeys 均保留：
    // 目标 2 重扫同件 ⇒ 撞 seen 键去重不入库（不重复）；新件照常入库（不丢件）。
    // 跨目标锁写等动作仍逐目标执行（foreach 的动作步骤不受此处影响）。

    // ── 每目标全新：止扫累积计数器 ──
    // 回卷/重复判据是"本目标这一趟走查"的累积，跨目标残留会让目标 2 在第一整页就误判到底
    // （2026-09-18 真机实测，见 KDoc），必须清。
    charDupStreak = 0
    vars.charDupStreak = 0
    dupPageStreak = 0
    dupPageDecided = false
    dupPageStopConfirmed = false
    // scope=cell 止扫（3★/2★）的连续命中计数也是"本目标这一趟"的累积 ⇒ 同理清。
    stopWhenCellStreak = 0

    // ── 每目标全新：跨页游标/对照表 ──
    // 这些字段携带"上一次翻页"的对照信息，属于**本目标这一趟走查**的页间状态：
    // 目标 2 从第 0 页重新开始，若残留目标 1 末页的对照表，其第 1 页会与目标 1 的**末页**
    // 做跨页重叠/锚定比对（同背包、内容本来就相同）⇒ 假判重叠跳过点击 ⇒ 目标 2 的锁定动作
    // 根本执行不到那些格（违背"逐目标执行"）。pagedGrid 在 pageNo==0 对这些也有部分复位
    // （rowCheckPrevKeys/rowCheckGlobalStart/panelFpSnapshot），但 prevAllCell* 的页间
    // 复制发生在 pageNo>0 分支之前读取，必须在这里显式清。
    prevAllCellFps = null
    prevAllCellIds = null
    prevAllCellKeys = null
    rowCheckPrevKeys = emptyList()
    lastCellKey = null
    lastCellIdentity = null
    lastCellFp = null
    gridCellChanged = true
    curPageIds.clear()
    skipCopyFrom.clear()
    anchoredD = null
    curCellIdx = -1
    curCellRow = -1
    curCellCol = -1

    // ── 每目标全新：武器陈旧帧保护/格幂等表 ──
    // weaponCellEmitted 是「页:行:列:身份」的**格重解析幂等**表（#58②），页序跨目标会重号 ⇒ 清。
    // weaponIdentityRuns / lastEmittedWeaponIdentity / lastWeaponIdentity / weaponSameRun 是
    // "连续同一 identity"的走查内观测（陈旧帧保护），跨目标残留会把目标 1 尾件与目标 2 首件
    // 连成一串假"连续"⇒ 误触发 CAP 丢帧。均为走查域状态，非导出域 ⇒ 清（不碰 resultsWeapons）。
    weaponCellEmitted.clear()
    weaponIdentityRuns.clear()
    lastEmittedWeaponIdentity = null
    lastWeaponIdentity = null
    weaponSameRun = 0

    Log.i(TAG, "resetScanAccumulation: 已复位本目标流程状态（止扫计数/页间游标）；GOOD 结果与 seen 键跨目标累加去重（2026-10-01 裁决）")
}

/** 每条计划项的可读标签（角色/套装/部位，缺省 "?"）。 */
internal fun ScanEngine.taskLabel(t: JSONObject): String {
    val parts = listOf(
        t.optString("char"),
        t.optString("setKey"),
        t.optString("slotKey", t.optString("slot", "")),
    ).filter { it.isNotEmpty() }
    return parts.joinToString("/").ifEmpty { "?" }
}

/**
 * 结果汇总（对齐 GOODScanner 的 `ManageSummary`）。
 *
 * 为什么必须有：此前用户只能看到"start flow=… plan=N"和逐格日志，
 * **问不出"目标 6 个，到底成了几个"** —— 失败与没跑到在日志上不可区分。
 * 现在按项给出 InstructionStatus（Success/ClickedUnverified/AlreadyCorrect/NotFound/Failed/Skipped），
 * 走 `NoticeCenter`（唯一提醒通路）+ 日志，异常项点名。
 */
internal fun ScanEngine.emitManageSummary() {
    if (manageResults.isEmpty()) return
    val order = listOf("Success", "AlreadyCorrect", "ClickedUnverified", "NotFound", "Failed", "Skipped")
    val counts = mutableMapOf<String, Int>()
    for ((_, _, st) in manageResults.sortedBy { it.first }) counts[st] = (counts[st] ?: 0) + 1
    // ⚠️ 未知标签**也要出现在头行**：原先是 `order.filter { counts.containsKey(it) }`，
    //   新增一档忘了登记就会"共 6 项"对不上后面各项之和 —— 正是本条要消灭的那类"看着跑完了"。
    val shown = order.filter { counts.containsKey(it) } + counts.keys.filterNot { it in order }
    val head = "本轮结果：共 ${manageResults.size} 项 ｜ " +
        shown.joinToString(" ") { "$it=${counts[it]}" }
    val bad = manageResults.sortedBy { it.first }
        .filter { it.third == "NotFound" || it.third == "Failed" || it.third == "ClickedUnverified" }
    val detail = if (bad.isEmpty()) "" else " ｜ 异常：" +
        bad.take(8).joinToString("，") { "${it.second}(${it.third})" }
    Log.i(TAG, "ManageSummary: $head$detail")
    RecognitionLog.log(logTag, RecognitionLog.Level.I, "结果汇总 $head$detail")
    NoticeCenter.post(
        if (bad.isEmpty()) NoticeCenter.Level.INFO else NoticeCenter.Level.WARN,
        head + detail,
    )
}

/**
 * P3 navigate：左侧菜单切换（字符/角色界面）——leftMenu 每项名直接是 profiles.char_interface.leftMenu 的 key。
 * 每项点中心 + settle，顺序执行（如 ["命之座", "天赋"]）。
 */
internal fun ScanEngine.dictKeyOf(step: JSONObject, field: String): String? =
    step.optJSONObject("dict")?.optString(field)?.takeIf { it.isNotEmpty() }

/** `dict.fuzzy`（1/true → 允许模糊匹配；缺省 true，与 [NameMatcher] 默认一致）。 */
internal fun ScanEngine.dictFuzzyOf(dict: JSONObject?): Boolean {
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
internal fun ScanEngine.snapSubstats(
    list: List<StatParser.ParsedStat>,
    rarity: Int,
    dictKey: String,
): List<StatParser.ParsedStat> {
    if (dictKey != "rollTable") {
        Log.w(TAG, "subStats 词典 '$dictKey' 不支持档位吸附（仅支持 rollTable），保持原值")
        return list
    }
    // ★★ 2026-09-16 加**吸附容差**（真机 GT 对账定谳）★★
    //   原实现是"无条件吸到最近档位" ⇒ 把游戏里**真实存在、但不在我们档位表里**的值改掉 ✗：
    //   实测 `def_ 18.9`（GT 真值，Irminsul 直读游戏数据）被吸成 `19.0`
    //   （我们穷举表的 5★ def_ 三次和只有 18.2/19.0/19.7 ⇒ 18.9 不在表里）⇒ 导出与 GT 差 1 件 ✗。
    //   容差 0.06 = OCR 抖动级（1 位小数显示值 ±0.05）⇒ 只消化真正的读数抖动，
    //   **不再改动画面上写的值**（宁可保留原始值，也不臆造"更标准"的值）。
    return list.map { s ->
        val snapped = RollTable.snap(rarity, s.key, s.value)
        if (snapped != null && snapped != s.value) {
            val delta = Math.abs(snapped - s.value)
            if (delta <= SNAP_TOL) {
                Log.d(TAG, "rollTable 吸附: ${s.key} ${s.value} → $snapped（Δ=%.2f）".format(delta))
                s.copy(value = snapped)
            } else {
                Log.i(
                    TAG,
                    "rollTable **不吸附**（超容差 %.2f）：${s.key} ${s.value}（最近档 $snapped）" +
                        " ⇒ 保留游戏显示值（档位表可能缺该值）".format(delta),
                )
                s
            }
        } else {
            s
        }
    }
}

internal fun ScanEngine.lookupName(dictKey: String, text: String, allowFuzzy: Boolean = true): String? {
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
internal suspend fun ScanEngine.ocrWithRetry(step: JSONObject) {
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

/** P3 exit：via 返回按钮（文本 "return[2913,41]"）→ 点击后终止流程。链名优先走 CHAIN_ANCHOR_PATHS 机读，字面点只是回落。 */
internal suspend fun ScanEngine.runVisit(
    visit: JSONArray,
    gridKey: String,
    col: Int,
    row: Int,
    index: Int,
    prof: ScreenProfile,
    /** §14 A3：snap 遍历——点击由外部固定循环完成，visit 内 click 步只做 settle + 抓帧。 */
    snapMode: Boolean = false,
) {
    val ctx = ScanEngine.CellFrameContext()
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
internal suspend fun ScanEngine.executeVisitStep(
    step: JSONObject,
    gridKey: String,
    col: Int,
    row: Int,
    index: Int,
    ctx: ScanEngine.CellFrameContext,
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

/**
 * 点格后等「面板名变了且连续两次读数一致」（签名路径不可用时的**旧就绪判据**）。
 *
 * ⚠️ 2026-09-10 的教训保留：判据不能是"名字一变就 break"——面板淡入**早期**名字先变、
 * 等级/属性还没渲染，立刻抓帧会读空。
 *
 * @return 是否**见过**名字变化。全程未变 ⇒ 极可能点击被游戏吞了（面板还停在上一件），
 *   调用方据此同坐标重发；这里不能顺手把它当成"这格本来就是同名件"放行 ——
 *   那正是"漏件且无痕"的形态（读到重复件 ⇒ 被去重吞掉）。
 */
internal suspend fun ScanEngine.executeVisitStepInner(
    step: JSONObject,
    vop: String,
    gridKey: String,
    col: Int,
    row: Int,
    index: Int,
    ctx: ScanEngine.CellFrameContext,
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
                        runCatchingCancellable { Expr.eval(whenExpr, exprVars()) }.getOrElse { e ->
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
                // ★ 2026-09-27 #96 真因：`lockClickAdaptive()` **自己就是一次点击**（点完还 settle 收敛），
                //   而下面的通用分支会把返回的坐标**再点一次** ⇒ 锁钮被切两下 = 净效果归零，
                //   锁态回到原样。所以 `verify` 读到"未锁"是**读数正确**、不是判据假阴；
                //   09-18 真机那条「clickLocal accepted=true 但毫无反应」也是同一件事。
                //   ⇒ 解析阶段已点过的，这里必须跳过第二次点击。
                var clickedWhileResolving = false
                val (cx, cy) = runCatchingCancellable {
                    val fast = step.optJSONObject("fast")
                    when (val at = fast?.opt("at")) {
                        is String -> when {
                            at == "\$cell.center" || at.isEmpty() -> cellCenter.x to cellCenter.y
                            at == "\$zones.artifact.panel.lock" ->
                                lockClickAdaptive().also { clickedWhileResolving = true }
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
                    .firstNotNullOfOrNull { p -> runCatchingCancellable { profile.rect(p) }.getOrNull() }
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
                // ★ 2026-09-19：格点击模式由 [ClickModeOverrides.cellTap] 决定（**A/B 开关**）。
                //   背景：历史上 tap/微滑的取舍证据来自**华为真机 EMUI**（char_popup 9 格全停首格，
                //   adb tap 9/9 切换）——那是**真机特性**，不能直接套到 BlueStacks。
                //   ⇒ 在 BlueStacks 上用免重编开关做受控 A/B（`DEBUG_SET_CELL_CLICK tap|swipe`），
                //     多轮小样本（2~3 页）比差异，再决定长期取值（默认沿用微滑）。
                // ★ 2026-09-23：点击**前**先采一张「被点卡片自身」的签名 —— 点击后要靠它区分
                //   "点击被吞"与"相邻两格内容一模一样"（见 [cardFrameMoved]）。只能在点击前采。
                val cardBox = cardRoi(prof, gridKey, col, row)
                val cardSigOk = cardBox != null && frameSource.sampleSignature(
                    cardBox, sigCardBefore, CARD_SIG_BLOCKS, CARD_SIG_BLOCKS,
                )
                val clickOk = if (clickedWhileResolving) {
                    Log.i(TAG, "visit cell($col,$row) idx=$index 已在解析阶段点过 ⇒ 跳过重复点击")
                    true
                } else if (useTapForCell) actions.tap(cx, cy) else actions.click(cx, cy)
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
                    clickedWhileResolving -> {
                        // ★ #96 同源第二处：就绪门的兜底动作是「**同坐标重发**」（下方 5104/5142）。
                        //   锁钮点的是面板上的按钮、不是换卡片 ⇒ 面板名字与签名**本就不该变**，
                        //   走门必被判"点击被吞"⇒ 重发 = 又把锁切回去。
                        //   状态收敛已经在 `lockClickAdaptive → settleLockState` 里做完了，
                        //   这里既不等也不重发。
                        Log.i(TAG, "visit cell($col,$row) idx=$index 锁钮已在解析阶段点击并收敛 ⇒ 跳过就绪门与重发")
                    }
                    sigUsed -> {
                        // 计时口径：签名的判据是**墙钟**（含采样/确认），与旧路径的"delay 之和"
                        // 略有不同但更真实（旧口径漏掉每轮的抓帧+OCR 时间）。
                        val tPanel = SystemClock.elapsedRealtime()
                        // ★ 2026-09-16：确认回调提为**具名匿名函数** —— 兜底重发后需要**重复等待**（见下方 while）。
                        val onPanelStable: suspend () -> Boolean = stable@{
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
                            if (!TimingOverrides.panelSigConfirm) return@stable true
                            val f = ctx.frame ?: return@stable false
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
                        var clickRetry = 0
                        while (true) {
                            val sawChange = waitReadyBySignature(
                                before = sigBefore,
                                a = sigA,
                                b = sigB,
                                roi = sigRoi!!,
                                blocksX = sigBx,
                                blocksY = sigBy,
                                step = TimingOverrides.panelSigPollMs,
                                maxMs = PANEL_CHANGE_WAIT_MAX_MS,
                                minMs = 0L,
                                fallbackMs = TimingOverrides.panelStableFallbackMs,
                                what = if (clickRetry == 0) "panel" else "panel-retry$clickRetry",
                                stableSamples = TimingOverrides.panelSigSamples,
                                confirm = onPanelStable,
                            )
                            if (sawChange) break
                            // `cardSigOk` 的定义里已含 `cardBox != null`（采样要用它），这里不必再查一遍
                            val selMoved = cardSigOk && cardFrameMoved(cardBox, sigCardBefore)
                            if (clickRetry >= CLICK_RETRY_MAX_ON_NOCHANGE) {
                                // 放弃：本格将读到**陈旧面板** ⇒ 那件东西从头到尾没被选中过 = 丢件。
                                dumpStallShot(cx, cy, col, row, index, clickRetry, selMoved, clickOk, "giveup")
                                break
                            }
                            // ★ 2026-09-23：面板没变**不等于**点击被吞。相邻两格内容完全一样时
                            //   （武器尤其多：同名同等级同精炼）面板像素一模一样、指纹永不变化，
                            //   旧判据一律当吞击 ⇒ 1 页武器 7/21 格白烧 35 次重发（≈15s）。
                            //   选中框能分开两者：点中了高亮框必换格，没送达则一块不动。
                            if (selMoved) {
                                Log.i(
                                    TAG,
                                    "visit cell($col,$row) idx=$index 面板未变但**选中框已移动** ⇒ 点击已送达，不重发",
                                )
                                break
                            }
                            // 本轮首格：游戏打开背包时**已自动选中第一件** ⇒ 面板与选中框都不会变，
                            // 这是正常态而不是吞击（重发 5 次 ≈2s 纯浪费）。
                            if (index == 0 && results.isEmpty() && resultsWeapons.isEmpty() &&
                                resultsCharacters.isEmpty()
                            ) {
                                Log.i(TAG, "visit cell($col,$row) idx=$index 本轮首格且选中框未动 ⇒ 已是选中态，不重发")
                                break
                            }
                            // 全程未见面板变化 ⇒ 极可能**点击被游戏吞掉**（2026-09-16 run6 page3 实证：
                            //   21 格耗时零方差且最快 = 就绪轮询秒过 = 内容从未变化）⇒ **同坐标重发**。
                            //   安全性：坐标完全相同 ⇒ 最坏只是重读同一张卡（幂等），不会误加相邻件。
                            clickRetry++
                            swallowedClickRetries++
                            // 取证：本次等待超时、下一次点击**之前**的画面（开了落图开关才有开销）
                            dumpStallShot(cx, cy, col, row, index, clickRetry, selMoved, clickOk, "retry")
                            Log.w(
                                TAG,
                                "visit cell($col,$row) idx=$index 面板全程未见变化（clickOk=$clickOk）" +
                                    " ⇒ 疑似点击被吞 ⇒ 同坐标重发 #$clickRetry",
                            )
                            if (useTapForCell) actions.tap(cx, cy) else actions.click(cx, cy)
                        }
                        tmPanelMs += SystemClock.elapsedRealtime() - tPanel
                    }
                    nameRect != null && beforeName != null && ocr != null -> {
                        // ★ 2026-09-22：旧路径补上「吞击重发」。
                        // ★ 2026-09-28（#48）：**与签名路径同形了** —— 补上原先缺的两样：
                        //   ① 「选中框已移动」闸门 ② 「本轮首格」豁免。
                        //   原注释断言"本分支没有签名采样上下文"**是错的**：`cardBox`/`sigCardBefore`
                        //   在 5190-5193 是**无条件**采的（点击前、与 panelSig 无关），
                        //   `cardFrameMoved` 也只依赖 frameSource 的签名采样 ⇒ 这条分支一直用得上。
                        //   补之前的后果：走这条路径的设备上，每一个"同名邻居"格都白烧
                        //   `CLICK_RETRY_MAX_ON_NOCHANGE` 次重发 + 同数量取证图，最后仍记 giveup
                        //   （签名路径第 1 次就 break）⇒ **漏件率取决于这台设备走哪条路径**，
                        //   而这正是补重发想消除的分叉。
                        //   名字全程不变 = 面板还停在上一件 ⇒ 读到的就是重复件 ⇒ 被去重**静默吞掉**
                        //   （漏件无痕）。同坐标重发是幂等的（最坏重读同一张卡），
                        //   代价约 panelStableFallbackMs/次。
                        val cleanBefore = StatParser.clean(beforeName)
                        var clickRetry = 0
                        while (true) {
                            val sawChange = waitPanelNameReady(nameRect, ocr, cleanBefore)
                            if (sawChange) break
                            val selMoved = cardSigOk && cardFrameMoved(cardBox, sigCardBefore)
                            if (clickRetry >= CLICK_RETRY_MAX_ON_NOCHANGE) {
                                dumpStallShot(cx, cy, col, row, index, clickRetry, selMoved, clickOk, "giveup")
                                break
                            }
                            // 面板没变但高亮框换了格 ⇒ 点击确实送达，只是邻居内容一样（同名同练）
                            if (selMoved) {
                                Log.i(
                                    TAG,
                                    "visit cell($col,$row) idx=$index 名字未变但**选中框已移动** ⇒ 点击已送达，不重发",
                                )
                                break
                            }
                            // 本轮首格：游戏打开背包时已自动选中第一件 ⇒ 面板与选中框都不会变，
                            // 这是正常态而不是吞击（与签名路径同判据）。
                            if (index == 0 && results.isEmpty() && resultsWeapons.isEmpty() &&
                                resultsCharacters.isEmpty()
                            ) {
                                Log.i(TAG, "visit cell($col,$row) idx=$index 本轮首格且选中框未动 ⇒ 已是选中态，不重发")
                                break
                            }
                            clickRetry++
                            swallowedClickRetries++
                            // 取证图先落、日志后打：manifest 里的序号要与日志顺序同轴，
                            // 否则按日志时间轴复盘时会对不上（签名路径就是这个顺序）
                            dumpStallShot(cx, cy, col, row, index, clickRetry, selMoved, clickOk, "retry")
                            Log.w(
                                TAG,
                                "visit cell($col,$row) idx=$index 名字全程未变（clickOk=$clickOk）" +
                                    " ⇒ 疑似点击被吞 ⇒ 同坐标重发 #$clickRetry",
                            )
                            if (useTapForCell) actions.tap(cx, cy) else actions.click(cx, cy)
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
                            val regionRect = try { profile.rect(region) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                            if (regionRect != null) {
                                val ok = runCatchingCancellable {
                                    val frame = freshFrame()
                                    try {
                                        val text = ocrGateway.readLines(frame, listOf(regionRect)).joinToString(" ")
                                        StatParser.clean(text).isNotEmpty()
                                    } finally { frame.release() }
                                }.getOrDefault(true)
                                if (!ok && !clickedWhileResolving) {
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
            "notify" -> notifyStep(step)
            // ★ 2026-09-18 单趟扫描：visit 层也要能逐格绑定计划项
            //   （顶层分派有了 visit 层没加 ⇒ 真机全格 `unknown visit step 'planMatch', skipped`）
            "planMatch" -> planMatch(step)
            "planDone" -> planDone(step)
            else -> {
                Log.w(TAG, "unknown visit step '$vop', skipped")
                RecognitionLog.log(logTag, RecognitionLog.Level.W, "未知 visit 步 $vop 已跳过")
            }
        }
    }

// ---- #4 vote：像素投票判据 ----
