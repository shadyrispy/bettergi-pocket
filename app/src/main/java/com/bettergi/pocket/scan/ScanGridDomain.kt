package com.bettergi.pocket.scan

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Mat
import com.bettergi.pocket.scan.ScanEngine.Companion.ADV_MIN_END_Y
import com.bettergi.pocket.scan.ScanEngine.Companion.ANCHOR_SETTLE_MAX_DY_PX
import com.bettergi.pocket.scan.ScanEngine.Companion.ANCHOR_SETTLE_MAX_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.ANCHOR_SETTLE_POLL_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.ANCHOR_SETTLE_STABLE_PAIRS
import com.bettergi.pocket.scan.ScanEngine.Companion.ANCHOR_SETTLE_WINDOW_PX
import com.bettergi.pocket.scan.ScanEngine.Companion.CROSS_PAGE_SETTLE_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.GRID_END_DIFF
import com.bettergi.pocket.scan.ScanEngine.Companion.GRID_SIMILAR_DIFF
import com.bettergi.pocket.scan.ScanEngine.Companion.GRID_TOP_STABLE_ROUNDS
import com.bettergi.pocket.scan.ScanEngine.Companion.MAX_SCROLL_TOP_ROUNDS
import com.bettergi.pocket.scan.ScanEngine.Companion.OVERLAP_ANCHOR_COUNT
import com.bettergi.pocket.scan.ScanEngine.Companion.PAGE_CAP_MARGIN
import com.bettergi.pocket.scan.ScanEngine.Companion.PAGE_WATCHDOG_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.PANEL_FP_GATE_ENABLED
import com.bettergi.pocket.scan.ScanEngine.Companion.SETTLE_CHANGE_WINDOW_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.SETTLE_FRAME_TIMEOUT_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.SETTLE_MAX_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.SUBST_STALE_MIN
import com.bettergi.pocket.scan.ScanEngine.Companion.TAG
import com.bettergi.pocket.scan.ScanEngine.Companion.THUMB_COLS
import com.bettergi.pocket.scan.ScanEngine.Companion.THUMB_ROWS
import com.bettergi.pocket.scan.ScanEngine.Companion.WINDOW_SETTLE_MS
import com.bettergi.pocket.log.RecognitionLog
import android.os.SystemClock
import com.bettergi.pocket.scan.ScanEngine.Companion.anchorHit
import com.bettergi.pocket.scan.ScanEngine.Companion.isSubstatBlock
import com.bettergi.pocket.capture.FrameTimeoutException

/*
 * Stage 3.3 步④：ScanGridDomain（do 原语按族拆分，扩展函数，函数体一字未改）。
 */

internal suspend fun ScanEngine.pagedGrid(step: JSONObject) {
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
        // ⚠️ 2026-09-18：`targets` 是旧拼写、`plan` 是新拼写，**两种都认**（跨版本 flow 兼容）。
        ?.replace("max(targets.level)", "targetMaxLevel")
        ?.replace("max(plan.level)", "targetMaxLevel")
        // 稀有度下界（稀有度止扫用）：背包按稀有度降序 ⇒ 低于目标最低稀有度即可停
        ?.replace("min(plan.rarity)", "targetMinRarity")
    val advFrom = advance.getJSONArray("from")
    val advTo = advance.getJSONArray("to")
    // §15 回顶（暂缓）：pagedGrid 起始回顶在真机出现 hang（settle 后无后续日志），
    // 已回滚。列表位置跨会话残留 → 待办：weapon_scan 前手动复位列表或实现安全回顶
    // （疑 actions.swipe 连续派发被 EMUI 无障碍节流挂起）。
    // §12.1 起点规范化：优先用几何推导的起点（**最左**卡间缝隙 + 锚行上沿+5），
    // 几何不足（1 列网格等）或显式关闭时回退到 profiles.advance.from 的写死坐标。
    val geoStart = if (useGeometryAdvance) profile.advanceStart(gridKey) else null
    // ⚠️ 距离必须与起点**同源**：`advanceStart` 因几何不足返回 null 时（如 char_strip 是 1 列网格，
    //   `colXs.size < 2`），`advanceDistance` 仍能给值（= traverseRows × 行距，与 from/to 无关）
    //   ⇒ 若只回退起点不回退距离，就会"字面起点 + 几何距离"混出一条谁都没标定的滑动
    //   （char_strip：起点 1000、几何距离 960 被钳到 940 ⇒ 1000→60，而标定值是 1000→360 ✗）。
    val geoDist = if (geoStart != null) profile.advanceDistance(gridKey) else null
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
    // ★ A9：**连续整页跳过**计数。skip 页不点格 ⇒ 无该页最低等级读数来源，页级下界不可推进
    //   （旧方案「命中页也推进页级下界」因此被否决）⇒ 若列表尾部全是低价值页，翻页永不终止：
    //   artifact_lock 无 readCount（total=null ⇒ pagesByCount=Int.MAX_VALUE）、生产 maxPages 无上限。
    //   收尾上限见下方 skip 分支（TimingOverrides.pageSkipLimit，默认 3）。
    var consecutiveSkipPages = 0
    // ★ 2026-09-16（审计 P0-2 修）：**跨页**主滑记账 —— 必须声明在页循环**外**，否则每页重建，
    //   写入在迭代结束即丢（旧版即踩此坑：日志写"记账 X 进下一次主滑"而实际从未生效）。
    //   单一规则（实现在 GridAlign.planMainSwipe / driftAfterPage）：
    //     · 发滑前 cmd = clamp(round(target/gain) + pageDrift, minCmd, maxCmd)；
    //     · 钳制余量（cmdRaw−cmd）先落进 pageDrift 作**兜底值**；
    //     · 相位有可信读数时**以相位为准覆盖**——落地测量已反映真实结果，再叠余额会重复补偿；
    //     · fpband 不可测 / 未送达 ⇒ 保留兜底值，下一页按它补偿。
    var pageDrift = 0
    // 回卷阈值 = **一页卡片数 + 一行**（用户定稿 2026-09-26：不写死 21，随网格尺寸推）。
    //   语义 = "点完一整页无新增、再翻一页、第一行仍无新增" ⇒ 判到尾；配套要求计数**跨翻页累积**
    //   （见下方"不再开页清零"）与 flow 的 `dupPageConfirm`（artifact/weapon 已置 1 = 一次命中即停）。
    //   为什么从 `cols×traverseRows` 抬到 + 一行：单次滑动没落地只会造出一整页（=cols×rows 个）
    //   重复件，正好卡在旧阈值上 ⇒ 一次滑空就能误停整轮；抬一行之后至少要**连续两次**滑空才凑得满。
    dupLimitEffective = cols * (traverseRows + 1)
    // 按件数推算的翻页数上限（**后备**；主判据是"已入库 ≥ 计数器"，见翻页块开头）
    val cellsPerPage = cols * traverseRows
    val pagesByCount = vars.total?.takeIf { it > 0 }?.let {
        Math.ceil(it.toDouble() / cellsPerPage).toInt() + PAGE_CAP_MARGIN
    } ?: Int.MAX_VALUE
    dupPageStreak = 0
    dupPageDecided = false
    Log.i(
        TAG,
        "pagedGrid[$gridKey]: 回卷阈值 = 一页+一行 $cols×($traverseRows+1) = $dupLimitEffective" +
            "，页级确认（flow `stopWhen.dupPageConfirm` 在本页之后才登记，此处打印恒为默认 2）；" +
            "收尾主判据 = 已入库 ≥ 计数器，后备 = 翻页数 > ${if (pagesByCount == Int.MAX_VALUE) "无(计数器未读到)" else pagesByCount}",
    )

    while (true) {
        if (vars.stopRequested) break
        // §14 A4 pageSkip 命中则整页跳过（仍继续翻页，避免提前终止漏掉后续页）
        val skip = pageSkipExpr != null && pageMinLevel != null && runCatchingCancellable {
            Expr.eval(
                pageSkipExpr,
                mapOf(
                    "pageMinLevel" to pageMinLevel!!,
                    "targetMinRarity" to (minTargetRarity() ?: 0),
                    "targetMaxLevel" to (maxTargetLevel() ?: Int.MAX_VALUE),
                ),
            )
        }.getOrDefault(false)
        // ★ A9：连续 skip 页达到上限 ⇒ 干净收尾（语义对齐回卷止扫：已入库件照常导出、
        //   正常 onFinished，不是报错）。依据：背包按等级降序，连续 K 页全跳 ⇒ 余下所有页
        //   都低于目标阈值（与 GOODScanner fast-mode page-skip 的止扫同源）；K 默认 3 是为
        //   容忍滑动欠滚/排序抖动造成的 1~2 页偶发误跳（pageMinLevel 反复读到同一低值）。
        //   ⚠️ 判据**独立于** vars.total（pagesByCount）与 maxPages：artifact_lock 无 readCount
        //   （total=null ⇒ pagesByCount=Int.MAX_VALUE）、生产 maxPages=Int.MAX_VALUE；
        //   且必须在页首**抢帧之前**终止 —— skip 页每页的 freshFrame 打点会喂会话看门狗
        //   （ScriptRunner 侧），引擎层必须自己终止。
        if (skip) {
            consecutiveSkipPages++
            if (consecutiveSkipPages >= TimingOverrides.pageSkipLimit) {
                Log.w(
                    TAG,
                    "pagedGrid[$gridKey]: 连续 $consecutiveSkipPages 页整页跳过 ≥ 上限 " +
                        "${TimingOverrides.pageSkipLimit} ⇒ 判定余下页均低于目标，收尾（已入库 " +
                        "${results.size + resultsWeapons.size + resultsCharacters.size} 件照常导出）",
                )
                vars.stopRequested = true
                vars.stopReason = "stopWhen"
                vars.gridExhausted = true
                break
            }
        } else {
            consecutiveSkipPages = 0
        }
        // §13：产物入库按页聚合（逐件会 21 行/页刷屏；逐件明细归 D 级，verbose 时才有）
        val beforeCount = results.size + resultsWeapons.size + resultsCharacters.size
        // ★★ 2026-09-16（用户定稿）：**卡格指纹每页只抓一帧** ★★
        //   同一页里 21 张卡**都在同一帧上** ⇒ 逐格 `freshFrame()`（1300+ 次 × ~60ms ≈ 80s/轮）纯属浪费；
        //   页首抓一帧，之后逐格只是从这一帧里切 72×72 小片（纯内存 copy，微秒级）。
        //   ⚠️ Mat 必须释放（页末 finally）—— 否则每页泄漏一张 3200×1440 BGR（约 13.8MB）⇒ OOM。
        // ★ 2026-09-17：**跨页指纹前先让列表停稳** —— 此前页首"立即抓帧"，
        //   若滑动惯性未停 ⇒ 帧里是"运动中"的卡面 ⇒ 与上页同列指纹**永不相等**（实测 `判定跳过 0/21` ✗）
        //   ⇒ 抓帧前固定等待（只影响帧内容，不影响点击时序；76 页 × 250ms ≈ +19s）
        //   ⚠️ 2026-09-24 曾把它放宽成"所有网格 + 2s"试治输入停滞：实测 0/4 页救回、纯多花 155s/轮 ⇒ 已回退。
        //   ★ A40（#147）：固定 250ms 换成「相邻帧底栏锚位移」判稳（见 [awaitCrossPageSettle]）——
        //     定时看不见列表残余慢漂（settle 缩略图 24×16 量化 + 固定延时都量不到几 px/帧的爬动），
        //     页帧在慢漂中抓取 ⇒ 卡格指纹/点击坐标整体偏移 ⇒ 整页 OCR 出"自信乱码"。
        if (pageNo > 0 && gridKey == "weapon_backpack") awaitCrossPageSettle(gridKey)
        // ★ #83：页首**查一次前台闸门**。非原神 ⇒ 整轮干净收尾（已入库的保留、照常导出）。
        //   不这么做的话：注入侧拦下动作后引擎毫不知情，会按同一页继续重试/回读直到看门狗
        //   或重复判据收尾 —— 白烧几十秒，而且日志里看不出"用户把游戏切走了"。
        foregroundOk?.invoke()?.let { ok ->
            if (!ok) {
                vars.stopRequested = true
                vars.stopReason = "前台不是原神（用户切走/游戏被切后台）"
                Log.w(TAG, "pagedGrid[$gridKey]: page=$pageNo 页首前台闸门判定失败 ⇒ 停止本轮（已入库照常导出）")
                break
            }
        }
        val pageCellFrame = runCatchingCancellable { freshFrame() }.getOrNull()
        // ★ 页级看门狗起点（挂死时中止并保留已入库结果）
        val pageWall0 = clock()
        // ★ 翻页后**清空面板指纹快照**（GOODScanner `reset_panel_fingerprint`）★
        //   保证**新页第一格必被当作"新的"** ⇒ 必然等待面板变化 ⇒ 不会解析到上一页遗留的面板 ✓
        //   （此前只在 pageNo==0 清 ⇒ 页首格容易读到上一页遗留 ✗，实测页首格重复现象支持这一点）
        curPageNo = pageNo
        curCols = cols
        curTraverseRows = traverseRows
        curPageIds.clear()
        skipCopyFrom.clear()
        anchoredD = null
        panelFpSnapshot = null
        if (pageNo == 0) {
            rowCheckGlobalStart = 0
            panelFpSnapshot = null // 新一轮扫描：清指纹（避免与上一轮残留面板误判同块）
        }
        // ★ 行级闭环（方案 C）：本页键序列必须在**页级作用域**（skip 页也要留序列占位）
        val pageKeys = ArrayList<String>(cols * traverseRows)
        // ★ 跨页重叠跳过（**武器专用**）：新页各格指纹 vs 上页**同列任意行**指纹 ⇒ 相同即同一件。
        //   命中写进 [skipCopyFrom]（本页 idx → 上页 idx），访问处**照此复制身份/内容键**。
        //   ⚠️ 圣遗物**不能**用像素判重来跳过点击，见 [lastCellFp] 的定谳（18 张同套同强化羽毛
        //      卡面像素几乎全同 ⇒ 会并成 1 张）。圣遗物那侧的重叠由**内容**判据 [anchoredD] 负责。
        //   ⚠️ 必须"跳过的同时复制身份/键"而非留空：留空 ⇒ pageKeys 该格为空 ⇒ 页末「空读格回读」
        //      会把这些格重新点一遍，跳过等于白跳（同一坑此前只在锚定路径修过，见 [prevAllCellKeys]）。
        if (gridKey == "weapon_backpack" && pageNo > 0 && prevAllCellFps != null && pageCellFrame != null) {
            val prev = prevAllCellFps!!
            for (r in 0 until traverseRows) {
                for (c in 0 until cols) {
                    val rect = cellFingerprintRect(gridKey, pageProfile, c, r) ?: continue
                    val fp = PanelFingerprint.capture(pageCellFrame, listOf(rect))
                    for (pr in 0 until traverseRows) {
                        val pi = pr * cols + c
                        if (!PanelFingerprint.same(fp, prev.getOrNull(pi))) continue
                        // 安全阀：只在"上页那格确实读成了件"（身份非空，见 parseWeaponPanel 末）时才跳过
                        //   —— 上页那格本就空 ⇒ 跳过 = 永久丢件（宁可慢，不可漏）。
                        val prevId = prevAllCellIds?.getOrNull(pi).orEmpty()
                        if (prevId.isNotEmpty()) skipCopyFrom.putIfAbsent(r * cols + c, pi)
                        break
                    }
                }
            }
            // 诊断：新页 row0 各列 vs 上页全页的指纹 hash ⇒ 一眼看出"值不等"还是"取值失败"
            val newH = (0 until cols).joinToString(",") { c ->
                val rr = cellFingerprintRect(gridKey, pageProfile, c, 0)
                val fp = if (rr != null) PanelFingerprint.capture(pageCellFrame, listOf(rr)) else null
                fp?.let { "%04x".format(java.util.Arrays.hashCode(it) and 0xFFFF) } ?: "----"
            }
            val oldH = (0 until cols * traverseRows).joinToString(",") { i ->
                prev.getOrNull(i)?.let { "%04x".format(java.util.Arrays.hashCode(it) and 0xFFFF) } ?: "----"
            }
            Log.i(TAG, "跨页指纹: page=$pageNo 新row0=[$newH] 上页=[$oldH]")
            Log.i(
                TAG,
                "跨页重叠: page=$pageNo 判定跳过 ${skipCopyFrom.size}/${cols * traverseRows} 格" +
                    " idx=${skipCopyFrom.keys.sorted()}",
            )
        }
        // ⚠️ 2026-09-17 修：本序列**必须与 pageKeys 同为页内局部**！
        //   此前误声明为类字段（跨页累积、与 pageKeys 长度不齐）⇒ 格级日志取错件
        //   ⇒ 凭空造出"同页同一件被读两次"112 次 ✗（已由手动点击实测推翻：r0c0/r1c0 是不同件）
        val pageIdentities = ArrayList<String>(cols * traverseRows)
        /** 每格：点击后面板是否**出现过**（指纹变化过）⇒ 区分"点击没生效"与"读了但错"。 */
        val pageAppeared = ArrayList<Boolean?>(cols * traverseRows)
        if (pageNo == 0) rowCheckPrevKeys = emptyList()
        if (skip) {
            Log.i(TAG, "pagedGrid[$gridKey]: pageSkip 命中（pageMinLevel=$pageMinLevel）第 $pageNo 页整页跳过")
        } else {
            // 本页：traverseRows 行 × cols 列（visibleRows 第 4 行是滑动锚，不遍历）。
            // scope=cell 止扫（3★/2★ 标识）：置 flag 后当页仍完整遍历（PC 策略「本页后停」），页尾 break。
            // ⚠️ **例外：回卷止扫（duplicateStreak 命中）必须立即停** —— 回卷意味着后面全是**已入库**的件，
            //   走完本页纯属浪费（实测：21 格重复件 → +14.6s，perCell 293→526ms）。
            //   「本页后停」是为 scope=cell 的 3★/2★ 止扫设计的（避免漏掉同页后面的高稀有度件）；
            //   回卷没有"本页后"的意义 ⇒ 单独走立即停。
            // ★ 2026-09-26 方案 C：**不再开页清零** —— 阈值已改成「一页 + 一行」`cols×(traverseRows+1)`，
            //   语义就是"点完一整页无新增、再翻一页、第一行仍无新增"，必须**跨翻页累积**才凑得出来。
            //   09-12 加清零是为了修「跨页 8+13=21 误判回卷」（实测欠滚：一页只前进 ~1.9 行），
            //   09-16 又补修了「内部状态没跟着清 ⇒ page6 尾 20 + page7 头 1 = 21 命中」（run8/run10
            //   只跑 8/12 页、101 件 vs 213 件）。新阈值下这两个场景分别是 21 和 27，**都 < 28** ⇒
            //   当年修掉的两个误判不会回来。
            //   而这条链**仍然是真兜底**：`noteDupAndMaybeStop` 命中确认后会置 `stopRequested` +
            //   `stopReason="stopWhen"`（并另记 `gridExhausted` 供 foreach 区分"到底"与"命中"）。
            //   件数主判据在计数器读不到（null/0）时**不启用**，收尾全靠这条链 ——
            //   实测把它去掉，`ScanEngineDryRunTest` 7 个用例全部 180s 超时。
            dupPageDecided = false
            var dupWrapped = false
            // #83：本页探针已在**逐格**里命中过（前台不是原神）。与 `dupWrapped` 同款——**本页局部**，
            //   不跨页/不跨 foreach 目标留存（用户若回到游戏，下一轮页首探针会正常放行）。
            var pageForegroundLost = false
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
                        // ★ 2026-09-30 #154：这条 break 会**连带丢掉本页剩余所有格**（每格一条日志都没有，
                        //   因为遍历根本没走到它们）。2244 那轮 Lisa/Yaoyao「连读数行都没有」最像这里，
                        //   所以本页必须把"放弃了哪几格"写进日志，否则事后只能靠 emit 数反推。
                        val abandoned = ArrayList<String>()
                        for (r in row until traverseRows) {
                            for (c in (if (r == row) col else 0) until cols) abandoned.add("r$r c$c")
                        }
                        Log.i(
                            TAG,
                            "pagedGrid[$gridKey]: " +
                                if (confirmed) {
                                    "回卷止扫（确认连续 $dupPageStreak 个整页零新增 ≥ $dupPageConfirm）"
                                } else {
                                    "整页重复第 $dupPageStreak 次（< $dupPageConfirm）⇒ 疑似滑空/半页重叠，清计数继续翻页"
                                } +
                                " ⇒ 立即结束本页（已入库 ${results.size + resultsWeapons.size + resultsCharacters.size} 件）" +
                                " ｜ 本页未点 ${abandoned.size} 格=${abandoned.joinToString(" ")}",
                        )
                        break
                    }
                    // ★ #83（A 方案）：**逐格**查前台闸门。窗口切换事件是异步到达 `:a11y` 进程的，
                    //   页首那一次取样常常还在"上一个包=原神"的旧值上（实测 page3 页首与 HOME 同秒 ⇒ 放行）。
                    //   逐格查的取样密度足够等到事件到达；查中即按「本页提前收尾」跳出本页
                    //   （复用 `dupWrapped` 同款开关，语义与上面的看门狗一致）。
                    //   ⚠️ 这里**仍然不碰 `vars.stopRequested` 作判据**（只看它、由本分支自己置位）：
                    //   直接把它当跳出条件会把 scope=cell 的「本页后停」塌成「立即停」，见上方实测注释。
                    if (!pageForegroundLost) {
                        foregroundOk?.invoke()?.let { ok ->
                            if (!ok) {
                                pageForegroundLost = true
                                vars.stopRequested = true
                                vars.stopReason = "前台不是原神（用户切走/游戏被切后台）"
                                Log.w(
                                    TAG,
                                    "pagedGrid[$gridKey]: page=$pageNo r$row c$col 前台闸门判定失败" +
                                        " ⇒ 本轮收尾（已入库 ${results.size + resultsWeapons.size + resultsCharacters.size} 件照常导出）",
                                )
                                dupWrapped = true
                            }
                        }
                    }
                    if (dupWrapped) break
                    // ★ 卡格指纹（点击前）：抓卡片中心一小块像素，判断"这一格是不是又点了同一张卡"
                    //   —— 仅用于决定面板闸门要不要等（见字段 KDoc；**不跳过点击**）。
                    if (PANEL_FP_GATE_ENABLED && pageCellFrame != null) {
                        val cr = cellFingerprintRect(gridKey, pageProfile, col, row)
                        if (cr != null) {
                            val cf = PanelFingerprint.capture(pageCellFrame, listOf(cr))
                            gridCellChanged = !PanelFingerprint.same(cf, lastCellFp)
                            lastCellFp = cf
                        }
                    }
                    // ★ 页级看门狗：单页耗时超阈值 ⇒ 判挂死，中止扫描（已入库仍导出）
                    if (clock() - pageWall0 > PAGE_WATCHDOG_MS) {
                        Log.w(
                            TAG,
                            "pagedGrid[$gridKey]: page=$pageNo 单页已耗时 ${clock() - pageWall0}ms " +
                                "> ${PAGE_WATCHDOG_MS}ms ⇒ 判挂死，中止扫描（已入库 " +
                                "${results.size + resultsWeapons.size + resultsCharacters.size} 件仍正常导出）",
                        )
                        vars.stopRequested = true
                        vars.stopReason = "watchdog"
                        dupWrapped = true // 复用一个"跳出本页"的开关（语义：本页提前收尾）
                        break
                    }
                    val idx = row * cols + col
                    val beforeCell = results.size + resultsWeapons.size + resultsCharacters.size
                    if (col == 0 && row == 0) tmPages++
                    tmCells++
                    Log.i(TAG, "pagedGrid[$gridKey] page=$pageNo start cell($col,$row) idx=$idx")
                    val copiedFrom = skipCopyFrom[idx]
                    if (copiedFrom != null) {
                        // 两条判据（卡格指纹 / 身份锚定）命中都是同一件事：**这格 ≡ 上页第 copiedFrom 格**。
                        //   不点击、不 OCR，但**身份与内容键照抄** —— 只跳不抄的话 pageKeys 留空，
                        //   页末「空读格回读」会把它重新点一遍（跳过白跳过）。
                        lastCellKey = prevAllCellKeys?.getOrNull(copiedFrom).orEmpty()
                        lastCellIdentity = prevAllCellIds?.getOrNull(copiedFrom).orEmpty()
                        lastPanelAppeared = true
                        // 复制格也要进**位置表**：`prevAllCellIds` 由 curPageIds 落盘，不写就留空洞
                        //   ⇒ 下页的安全阀/锚定在那格看到"空"⇒ 拒绝跳过 ⇒ 同一条重叠带每页都被重新点。
                        curPageIds[idx] = lastCellIdentity ?: ""
                        // ★★ 复制格**必须照常计入「连续重复」**（2026-09-20，单测当场抓住）★★
                        //   回卷止扫判据 = 「**一页卡片数（21）个连续重复件**」；被跳过的格不再走
                        //   `noteDupAndMaybeStop` ⇒ 计数最多凑到 17 ⇒ **列表真正到底时不再停**（会一直翻页）。
                        //   复制格按定义就是"上页已入库的同一件" ⇒ 用**自身**作 seen 集合 = "必已入库"，
                        //   与"逐格读到的键做成员判定"**等价**（控制组里这些格本就会被读到并计为重复）；
                        //   这样写还**不依赖各网格的键格式**（武器键另有一套 `key|L..|R..`）。
                        if (lastCellKey?.isNotEmpty() == true) {
                            noteDupAndMaybeStop(lastCellKey, listOf(lastCellKey!!))
                        }
                        Log.i(
                            TAG,
                            "跨页重叠跳过: page=$pageNo idx=$idx (r${row} c$col) ← 上页 idx=$copiedFrom" +
                                " 身份=${lastCellIdentity}",
                        )
                    } else {
                        curCellRow = row
                        curCellCol = col
                        curCellIdx = idx
                        runVisit(visit, gridKey, col, row, idx, pageProfile)
                    }
                    pageKeys += (lastCellKey ?: "")
                    pageIdentities += (lastCellIdentity ?: "")
                    pageAppeared += lastPanelAppeared
                    // ★★ 2026-09-20 C'：**身份锚定对齐**（读完 idx1、idx2 即可定对齐）★★
                    //   依据（真机身份串结构，两轮一致）：新页 idx1、idx2 ≡ 上页 idx15、idx16（逐位相同）
                    //   ⇒ 对齐偏移 d = j-1（本页 idx m ≡ 上页 idx d+m）⇒ 重叠格（d+m ≤ 20）可**复制上页身份**跳过。
                    //   ⚠️⚠️ 2026-09-20 真机第 2 轮定案：**锚点必须取 idx1/idx2（= 本页已访问的第 2、3 格）**。
                    //     第一版误取 pageIdentities[0]/[1]（= idx0/idx1），而 idx0 是**陈旧读**（读到上一页末格内容）
                    //     ⇒ 这对"相邻"锚点在上一页里必然不相邻 ⇒ **恒报未命中、C' 全程 0 命中**（实测 2 页 2 次未命中）。
                    //     故触发点从 `size == 2` 改为 `size == 3`，取值下标 1、2。
                    //   ★★ 2026-09-26（#66 定案）：锚定只接受**逐位（含词条数值）相邻命中**。★★
                    //     原先逐位不中时有一条「`#` 前缀且候选唯一」回退路，实测**6 轮 / 10 次命中全部判错**：
                    //     5★ 圣遗物长段是"同套同部位同等级同主词条"的连排（本轮 937/968 件为 5★），
                    //     两格前缀对的熵≈0 ⇒ "唯一"只是巧合。命中会**不点击、不 OCR 地抄走整格身份**，
                    //     于是那几件从未来得及进入任何一次读取 ⇒ **静默丢件**（且抄的是已入库身份 ⇒ 不产生 extra，
                    //     对账时只看得到"少件"，所以查了两晚）。
                    //     量出来的账（同一份日志即可复核）：**每轮「非整行的跳格数」== 该轮丢件数** ——
                    //     2560 page10 跳 2 ⇒ 缺 2（ScrollOfTheHeroOfCinderCity/flower ×2，GT 10→8）；
                    //     2244 pre64fix page17 跳 10 + page25 跳 1 ⇒ 缺 11。
                    //     而全部 ~100 次逐位命中**无一例外**满足 d % cols == 0，10 次前缀命中**无一例外**不满足
                    //     ⇒ 前缀路一删，`anchorHit` 的整行性质自动成立，下面的取模只是回归哨兵。
                    if (TimingOverrides.overlapSkip && pageNo > 0 && prevAllCellIds != null &&
                        anchoredD == null && pageIdentities.size == OVERLAP_ANCHOR_COUNT + 1
                    ) {
                        val prev = prevAllCellIds!!
                        val i1 = pageIdentities.getOrNull(1).orEmpty()
                        val i2 = pageIdentities.getOrNull(2).orEmpty()
                        if (i1.isEmpty() || i2.isEmpty()) {
                            Log.i(TAG, "身份锚定跳过：本页 idx1/idx2 身份为空 ⇒ 全部照常访问（i1='$i1' i2='$i2'）")
                        } else if (i1 == i2) {
                            // ★★ 2026-09-24：锚点两格身份**相同**时一律不锚定 ★★
                            //   锚定的全部信息量来自"这是**一对相邻且不同**的件"⇒ 相同 ⇒ 无序可对齐。
                            //   真机实证（页 53）：详情面板停滞 17 格 ⇒ idx1/idx2 读到同一陈旧身份，
                            //   而上页末两格恰是**同套同部位同等级同主词条**的两件（前缀相同）⇒
                            //   当时那条「前缀(唯一)」回退路被平凡满足 ⇒ 误判 d=15「本页与上页重叠」⇒
                            //   **把面板冻结当成跨页重叠、跳格并抄错身份**（冻结因此从"慢"升级成"丢"）。
                            //   （那条回退路已于 2026-09-26 随 #66 删除；本判据仍留着挡"零信息量锚点"。）
                            Log.w(
                                TAG,
                                "身份锚定跳过：本页 idx1/idx2 身份相同（'$i1'）⇒ 疑似详情面板停滞而非重叠，" +
                                    "全部照常访问（不做 d 推断）",
                            )
                        } else {
                            val hit = anchorHit(prev, i1, i2)
                            // 诊断：上页身份表**只进调试日志**（`sigdebug=1` 才打）——
                            //   它是"锚定为什么没命中"的唯一取证（2026-09-20 正是靠它一眼看出"上页表全空"），
                            //   但 21 格 × 24 字符会刷屏，故常态不打。
                            if (TimingOverrides.sigDebug) {
                                val dump = prev.mapIndexed { i, v ->
                                    "$i:" + v.substringBefore('#').takeLast(24)
                                }.joinToString(" ")
                                Log.i(TAG, "身份锚定诊断: idx1='${i1.take(44)}' idx2='${i2.take(44)}' j=$hit | 上页表=[$dump]")
                            }
                            val d = hit - 1
                            if (hit < 1) {
                                Log.i(
                                    TAG,
                                    "pagedGrid[$gridKey] 身份锚定未命中（本页 idx1,2 在上页无逐位相邻匹配）⇒ 全部照常访问" +
                                        "（idx1='${i1.take(30)}' idx2='${i2.take(30)}'）",
                                )
                            } else if (d % cols != 0) {
                                // 逐位命中却非整行 ⇒ 上页有**两对完全相同的相邻身份**，`anchorHit`
                                // 取到了第一对而真身在别处。宁可不锚定（这页照常逐格点、靠内容键去重），
                                // 也不能按错的 d 跳格 —— 跳掉的格根本不点击，那就是丢件。
                                Log.w(
                                    TAG,
                                    "pagedGrid[$gridKey] 身份锚定命中但 d=$d 非整行（cols=$cols）⇒ 疑似重复身份对，拒绝跳过",
                                )
                            } else {
                                anchoredD = d
                                val last = cols * traverseRows - 1
                                // 只复制"上页**确实读到了**（身份 + 内容键都非空）"的重叠格；
                                // 上页那格本就失败 ⇒ 本页照常访问（宁可慢，不可漏）。
                                var added = 0
                                for (m in (OVERLAP_ANCHOR_COUNT + 1)..(last - d)) {
                                    val o = d + m
                                    if (o !in prev.indices || prev[o].isEmpty()) continue
                                    if (prevAllCellKeys?.getOrNull(o)?.isNotEmpty() != true) continue
                                    // 卡格指纹已先一步判定同一格 ⇒ 不覆盖（指纹是直接像素证据，更强）
                                    if (skipCopyFrom.putIfAbsent(m, o) == null) added++
                                }
                                Log.w(
                                    TAG,
                                    "pagedGrid[$gridKey] **身份锚定命中**：本页 idx1,2 ≡ 上页 idx$hit,${hit + 1}" +
                                        " ⇒ d=$d ⇒ 新增跳过 $added 格（累计 ${skipCopyFrom.size} 格，" +
                                        "复制上页身份，不点击不 OCR）",
                                )
                            }
                        }
                    }
                    lastCellKey = null
                    lastCellIdentity = null
                    lastPanelAppeared = null
                    val addedCell = results.size + resultsWeapons.size + resultsCharacters.size - beforeCell
                    Log.i(TAG, "pagedGrid[$gridKey] page=$pageNo done cell($col,$row) idx=$idx added=$addedCell")
                    // ★★ 格级日志（2026-09-16，用户要求"查出漏的 8 件在哪"）★★
                    //   每格记：页/格序(idx)/**全局列表序号估计 G**/本格内容键是否为空/键哈希。
                    //   G 的推导：本页首格全局序号 rowCheckGlobalStart + idx（页内行主序）——行级闭环
                    //   已把每页实际前进量测出来并累加过 ⇒ G 是**实测累计**，不是理想值。
                    //   用途：跑完把日志里连续 G 序列一看 ⇒ **哪一段 G 缺号 = 哪几格没被点到/没读到**，
                    //   与 GT 无关即可定位（GT 没有列表顺序，无法反查位置）。
                    run {
                        val g = rowCheckGlobalStart + idx
                        // ⚠️ 顺序坑（2026-09-16 自查）：`lastCellKey` 在本格**收集后已被置空**
                        //   （`pageKeys += (lastCellKey ?: ""); lastCellKey = null` 就在上面）⇒
                        //   必须读 `pageKeys.lastOrNull()`，否则恒打出"空(未入库)"（实测 1365/1365 全空 ✗）。
                        val k = pageKeys.lastOrNull()
                        // ★ 2026-09-29 修（#122）：**角色格不能用 `key` 这一栏下判决**。
                        //   `lastCellKey` 只在圣遗物/武器路径赋值（见 [lastCellKey] 的 KDoc 与
                        //   emitCharacter），角色路径**从不写它** ⇒ 158/158 格全打 `**空(未入库)**`，
                        //   而同一秒紧挨着的 `char emit #N` 说明它们**明明入库了** —— 正是 #55/#84
                        //   那类"日志字段与实际相反"的坑（拿这份日志判丢件会得出全盘错误结论）。
                        //   角色侧改报 [lastCharCellLabel]（emit 时写的 `key/lv`），没入库才说"空"。
                        val charLabel = if (gridKey == "char_strip") lastCharCellLabel else null
                        val keyCol = when {
                            !k.isNullOrEmpty() -> "h${k.hashCode() and 0xFFFF}"
                            charLabel != null -> charLabel
                            else -> "**空(未入库)**"
                        }
                        Log.i(
                            TAG,
                            "格级: page=$pageNo row=${idx / cols} col=${idx % cols} idx=$idx global=$g key=" +
                                keyCol +
                                " item=" + (pageIdentities.lastOrNull()?.takeIf { it.isNotEmpty() } ?: "-") +
                                " appeared=" + (if (pageAppeared.isEmpty()) "-" else pageAppeared.last()?.toString() ?: "无闸门") +
                                " gridChanged=$gridCellChanged",
                        )
                    }
                    // #122：标签**必须在本格的「格级:」日志之后**才清 —— 它由 emitCharacter 写、
                    //   归本格所有（下一格重新写）。放在上面那段 lastCellKey=null 里会先清后读，
                    //   又变回"恒空"（这正是本条要修的那个坑的翻版）。
                    lastCharCellLabel = null
                }
            }
            // 背包按等级降序 → 本页末格等级即本页最低等级（作下页 pageSkip 判据）
            if (vars.level > 0) pageMinLevel = vars.level
        }
        val added = results.size + resultsWeapons.size + resultsCharacters.size - beforeCount
        RecognitionLog.log(logTag, RecognitionLog.Level.I, "第 $pageNo 页 入库 $added 件")
        // ★ 2026-09-16：页级回卷连击必须**真正连续**（KDoc 写的是"连续 N 个整页零新增"）——
        //   中间出现任何"有新增"的正常页即清零；否则两次相隔很远的坏页也会凑成 2 连击 ⇒ 误判回卷。
        if (added > 0) {
            dupPageStreak = 0
            dupPageStopConfirmed = false
        }
        // ★ 页末落盘**逐格位置表**（身份 / 内容键 / ——仅武器——卡格像素指纹），供下一页跨页比对。
        //   必须在释放页帧之前。
        if ((gridKey == "weapon_backpack" || gridKey == "artifact_backpack") && pageCellFrame != null) {
            if (gridKey == "weapon_backpack") {
                // 保存**全页**指纹（索引 r*cols+c），供下一页跨页重叠比对
                val all = ArrayList<ByteArray?>(cols * traverseRows)
                for (r in 0 until traverseRows) {
                    for (c in 0 until cols) {
                        val rect = cellFingerprintRect(gridKey, pageProfile, c, r)
                        all.add(if (rect != null) PanelFingerprint.capture(pageCellFrame, listOf(rect)) else null)
                    }
                }
                prevAllCellFps = all
            }
            // ★ 2026-09-20：同时存**逐格身份**，供下一页"跳过前先确认上页那件已入库"的安全阀用
            prevAllCellIds = List(cols * traverseRows) { i -> curPageIds[i] ?: "" }
            // ★★ 2026-09-20 修 ★★ `prevAllCellKeys` 此前**只声明、从未赋值** ⇒ C' 复制过来的格
            //   内容键恒为空 ⇒ 下游按"空读格"回读，跳过等于白跳过。此处与身份表同步落盘（同为位置表）。
            prevAllCellKeys = List(cols * traverseRows) { i -> pageKeys.getOrNull(i) ?: "" }
            // 跨页 identity 判据直接用 `prevAllCellIds`（**绝对下标** `r*cols+c` 的全行身份表）。
            // ★2026-09-25 #60：原先此处另建一张**紧凑表**（只存 row1/row2 ⇒ 槽位 0/1），
            //   而判据按**绝对下标** `(traverseRows-2)*cols+c` / `(traverseRows-1)*cols+c` 读
            //   ⇒ 前者实际取到 row2、后者**越界恒 null** ⇒ 这条判据事实上**只比上页 row2**，
            //   "前进 1 行"（新 row0 ← 上页 row1）完全没人管 ⇒ 尾区假重复。
            //   实测（2560 同码对照轮）：判据命中的 18 条**全是** `新行 ← 上页 row2`；
            //   漏掉的 TwinNephrite/EmeraldOrb/OtherworldlyStory/BlackTassel/ThrillingTales×2
            //   **全是** `page9 row0 ← page8 row1`。⇒ 换成绝对表，判据本身不动。
        }
        runCatchingCancellable { pageCellFrame?.release() } // 页级卡格帧用完即释放（防 Mat 泄漏）
        pageNo++
        // ⚠️ 下面几段（回读 / 行级闭环 / 冻结判定 / 锚定）描述的是**刚扫完那一页**，而 pageNo 已自增
        //   ⇒ 直接打当前页号会把页 53 的故障标成"页 54"（2026-09-24 排查冻结时踩到）。
        val scannedPage = pageNo - 1
        if (vars.stopRequested) break

        // 翻页：单次主滑 → settle → fpband 落地测量（相位平移 / 超限记账）→ 指纹比对判到底（未生效则重发）
        val beforeFrame = freshFrame()
        // §12.5 p0 基准：首帧（进背包顶对齐、尚未翻页）建立，phaseOffset 自此以它为参考
        if (!capturedBaseline) {
            GridAlign.captureBaseline(beforeFrame, profile, gridKey)
            capturedBaseline = true
        }
        var landingBand: Mat? = null // ★ 翻页落地模板：翻页前第4行可见条（design-docs/swipe-landing-measure.md）
        val landingGeom = profile.landingBandFor(gridKey)
        try {
            runCatchingCancellable {
                // 翻页前帧提取落地条带模板（帧释放前拷贝；profile 未登记则该 grid 跳过本测量）
                if (landingGeom != null) landingBand = VoteJudges.landingBandMat(beforeFrame, landingGeom)
            }
        } finally {
            beforeFrame.release()
        }
        // 翻页距离（帧坐标）：默认 = 几何推导 3 行；`advdist` 可绝对覆盖、`advextra` 可加偏置
        // —— 用于标定"游戏实际滚动量 < 手指行程"造成的页面重叠（2026-09-12 实测每页只覆盖 ~17/21 件）。
        // ★ 行级闭环（方案 C，2026-09-16）：`advance.extra` = profile 里的**常量负偏置**，
        //   把目标前进量压到「一页卡片数」以下 ⇒ 相邻页**必重叠**（内容键可测前进量）
        //   且覆盖带（2×行距+卡片高 = 837px）> 目标+最大过冲 ⇒ **结构性不可能跳行**。
        val advExtraProfile = advance.optInt("extra", 0)
        val advDist = when {
            TimingOverrides.advanceDistPx > 0 -> TimingOverrides.advanceDistPx
            dist != null -> dist + TimingOverrides.advanceExtraPx + advExtraProfile
            else -> null
        }
        // 期望推进量（帧 px，统一 Int）：有几何距离时 = advDist（含上面的偏置）；否则 = |to.y − from.y|
        val advTarget: Int = (advDist ?: Math.abs(
            profile.scale(advTo.getInt(1), profile.scaleY) -
                profile.scale(advFrom.getInt(1), profile.scaleY),
        ).toLong()).toInt()
        if (advDist != null && geoStart != null && advDist != dist) {
            Log.i(TAG, "advance[$gridKey]: 翻页距离覆盖 dist=${dist} → $advDist")
        }
        if (advDist == null && (advExtraProfile != 0 || TimingOverrides.advanceExtraPx != 0)) {
            // 常量偏置只挂在**几何**距离上（`dist` 来自 advanceDistance）⇒ 起点回退到字面 from/to 时
            // 它无处可加。不静默吞掉：重叠是"结构性防跳行"的主手段，配了却没生效必须看得见。
            // （要让它生效需 `geoAdvance=true`，而该开关在 BlueStacks 上另有已知问题 —— 见
            //   TriggerForegroundService 的默认值注释 ⇒ 别默认打开，按需评估。）
            Log.w(
                TAG,
                "advance[$gridKey]: 偏置被忽略 extra(profile)=$advExtraProfile " +
                    "extra(覆盖)=${TimingOverrides.advanceExtraPx} ⇒ 字面起点下目标 = |to−from| = $advTarget",
            )
        }
        // ── ★ 翻页（2026-09-14 整体改造）：每页**恰一次主滑动**，落地位移唯一可信源 = fpband ──
        //   命令 = 目标/增益（跨页 EMA 吸收系统偏差）+ pageDrift（上一页超限残差记账）。
        //   「没滚够」不再同页补滑（用户定稿）：旧闭环补滑 while 与相位校正反向滑全部废除；
        //   残差消化只走两条路——fpband φ 平移进本页点击坐标 / 超限记账进下一次滑动距离。
        //   「完全没落地」由外层指纹判定 → 到底重发兜底（手势失效 ≠ 距离偏差）。
        //   旧的 gridShift2D/profileShift 闭环测量随补滑一起退场（混叠/孪生失效源，见
        //   swipe-landing-measure.md「现有测量器为何全部不可靠」）。
        val rowPitch = profile.gridGeometryFor(gridKey)
            ?.let { Math.round(it.rowPitch * profile.scaleY).toInt() }
            ?.takeIf { it > 40 } ?: 204
        // ── ★ 空读格回读（2026-09-16，方案 ③）：本页「解析不出件」的格 settle 后**重访一次** ──
        //   实测缺口 33/1281 格 = 2.6%，性质是**单格读失败**（stale 帧 / 瞬时 OCR 失败），
        //   不是跳行（前进仅 14 件/21，结构性跳不了）。重访代价 ~250ms/格，只在失败时付。
        //   失败格也解释了行级闭环的 SKIP 误报（锚点那格恰好空读）⇒ **顺序必须是先回读、再判定**。
        //   守卫：整页产出过少（< 5 件）时不重试（可能是 pageSkip / 列表尾的空格）。
        if (pageKeys.count { it.isEmpty() } > 0 && pageKeys.count { it.isNotEmpty() } >= 5) {
            val emptyIdx = pageKeys.indices.filter { pageKeys[it].isEmpty() }
            var recovered = 0
            Log.i(TAG, "pagedGrid[$gridKey] 空读格回读: page=$scannedPage ${emptyIdx.size} 格 idx=$emptyIdx")
            // 重读**不喂**连续重复判据（与 [revisitFailedCells] 同规则，见其 suppressDupStreak 注释）
            suppressDupStreak = true
            try {
                for (i in emptyIdx) {
                    val col = i % cols
                    val row = i / cols
                    if (row >= traverseRows) continue
                    // ⚠️ 必须逐格改写页参数再访问：emit 处按 [curCellIdx] 落 `curPageIds`，
                    //    不写就会把补回那件的身份记到**上一个访问格**的槽位上 ⇒ 下页跨页表错位。
                    curCellRow = row
                    curCellCol = col
                    curCellIdx = i
                    runVisit(visit, gridKey, col, row, i, pageProfile)
                    curCellIdx = -1
                    val k = lastCellKey
                    val id = lastCellIdentity
                    lastCellKey = null
                    lastCellIdentity = null
                    if (!k.isNullOrEmpty()) {
                        pageKeys[i] = k
                        pageIdentities[i] = id.orEmpty()
                        recovered++
                    }
                }
            } finally {
                suppressDupStreak = false
            }
            if (recovered > 0) {
                Log.i(TAG, "pagedGrid[$gridKey] 空读格回读: page=$scannedPage 补回 $recovered/${emptyIdx.size} 件")
            }
        }
        // ── ★ 行级闭环（方案 C）：判定本页相对上一页实际前进了多少件 ────────────────
        //   前进 ≥ 一页卡片数 ⇒ 上一页末格之后、本页首格之前的件**从未被点过** = 跳行漏件
        //   （几何：点击容差只有点击安全窗那么宽 —— 2560 圣遗物实测 70px ⇒ 跨页空隙超过一个卡片有效区即丢件）。
        //   回补必须"往后退"：被跳的件在当前视图**上方**（第 0 行以上不可见）⇒ 只能把内容拉回来点。
        if (rowCheckPrevKeys.isNotEmpty() && pageKeys.isNotEmpty()) {
            val pageSize = cols * traverseRows
            // ⚠️ 2026-09-16：C1（滑空回补）经评估**不接入主流程**（整页重复也可能是"列表到底/半页重叠"，
            //   回补会平白多滑动）。STALL 判定与用例保留在 GridRowCheck 里备用，此处不传 stallBelow
            //   ⇒ 默认 0 ⇒ 永不触发（代码保留、不注释、不接入）。
            val rc = GridRowCheck.check(rowCheckPrevKeys, pageKeys, pageSize)
            Log.i(
                TAG,
                "pagedGrid[$gridKey] 行级闭环: page=$scannedPage 前进=${rc.advance?.toString() ?: "≥1页"}件 / " +
                    "$pageSize ⇒ ${rc.verdict}" + (rc.skipped?.takeIf { it > 0 }?.let { "（漏 $it 件）" } ?: ""),
            )
            rowCheckGlobalStart += (rc.advance ?: (cols * traverseRows))
            Log.i(TAG, "pagedGrid[$gridKey] 覆盖率: page=$scannedPage 本页首格全局序号≈$rowCheckGlobalStart")
            if (rc.verdict == GridRowCheck.Verdict.STALL) rowCheckStalls++
            if (rc.verdict == GridRowCheck.Verdict.SKIP) rowCheckSkips++
            // ⚠️ STALL **只计数不回补**（2026-09-16）：整页重复既可能是"真滑空"也可能是"列表到底"
            //   或"半页重叠"（既有单测 `duplicate artifacts across pages are deduped` 正是后者的真实场景：
            //   三页同内容 ⇒ 若这里回补会平白多 2 次滑动、并把"到底"语义搅乱 ✗）。
            //   而"滑空本身不直接丢件"（内容未变 ⇒ 覆盖已含）⇒ 回补收益未证实 ⇒ 按"不影响既有功能"降级为
            //   检测+计数，供日志/统计定位（真正的漏件根因仍待证，见 design-docs/inconsistency-audit）。
            // ★ 2026-09-19 修：回补触发源从「行级闭环 SKIP」（37 次全是前进量公式的系统性误报）
            //   换成**格级相邻重复指纹** —— 真机定位：5 件真漏与 5 次"相邻两格身份相同"一一对应
            //   （机理：某格点击被吞 ⇒ 本格重读上一格；下一格补跳 2 位 ⇒ 中间 1 件永不显示）。
            //   pageIdentities 是每页局部数组 ⇒ 页尾 zipWithNext 即得，零新增状态。
            // ★ 2026-09-19 用户定稿：**定点重访取代「退 1 行 + 整页重扫」**。
            //   点击不移动列表 ⇒ 失败格内容整页不变 ⇒ 原坐标重访该格即可读到；
            //   位置信息（页内行主序 idx → row/col）本就在手，此前只用 zipWithNext 比内容、丢了行列。
            val failedIdxs = ArrayList<Int>()
            for (i in 1 until pageIdentities.size) {
                val prevId = pageIdentities[i - 1]
                val curId = pageIdentities[i]
                if (curId.isNotEmpty() && curId == prevId) failedIdxs.add(i)
            }
            // ★★ 2026-09-19 基线定因（96/212 漏件 = 45%）：面板**半新半旧** ——
            //   名字/主词条区已更新（⇒ dup 指纹**检不出**，因为身份前缀不同），
            //   但**副词条区停滞** ⇒ 整块抄了邻件的副词条（格级日志实证：多件不同真件
            //   在同前缀下读到同一块 `#3.5,5.1,5.4,23.0`）。
            //   检测法（与 dup 指纹互补）：连续 ≥[SUBST_STALE_MIN] 格**副词条块相同**（即使前缀不同）。
            //   真·相邻同副词条的两件极少 ≥3 连 ⇒ 阈值安全；命中的整段加入重访（去重吸收正确格）。
            var runStart = -1
            var runSuffix = ""
            fun flushSubRun(end: Int) {
                if (runStart >= 0 && end - runStart + 1 >= SUBST_STALE_MIN) {
                    var staleHits = 0
                    for (j in runStart..end) {
                        if (failedIdxs.add(j)) staleHits++
                    }
                    if (staleHits > 0) {
                        Log.w(
                            TAG,
                            "pagedGrid[$gridKey] 副词条块停滞指纹：idx=${runStart}..$end 连续 ${end - runStart + 1} 格" +
                                " 副词条块相同（'$runSuffix'）⇒ 判为半新半旧面板，纳入重访",
                        )
                    }
                }
            }
            for (i in pageIdentities.indices) {
                val suf = pageIdentities[i].substringAfter('#', "")
                // 不是副词条数值块（武器 = `R1@位置`）⇒ 这条判据不适用，见 [isSubstatBlock]
                if (!isSubstatBlock(suf)) {
                    flushSubRun(i - 1)
                    runStart = -1
                    continue
                }
                if (runStart < 0) {
                    runStart = i
                    runSuffix = suf
                } else if (suf != runSuffix) {
                    flushSubRun(i - 1)
                    runStart = i
                    runSuffix = suf
                }
            }
            flushSubRun(pageIdentities.size - 1)
            // ★ 2026-09-24 修：`failedIdxs` 被两个检测源重复写入（相邻重复 + 副词条停滞段各 add 一遍），
            //   重复项会把 revisitFailedCells 里的"连续段"分组切碎 —— 实测 page7 一个 17 格整页冻结
            //   被拆成 13 个假窗口 `窗口(2格) idx=11..12 / 12..13 / …`，每段白等 WINDOW_SETTLE_MS。
            val failed = failedIdxs.distinct().sorted()
            if (failed.isNotEmpty()) {
                // ★ 2026-09-24：**页级冻结**就地快速放弃。全量实测（945 件 / 58 页 / 1026s）：
                //   定点重访 **0 / 502 救回**，对整页面板冻结完全无效 —— 该补救是 2026-09-19 针对
                //   "~12s 吞 ~6 击"的**短**窗口设计的（当时一轮 32 格救回 8 件），而本轮的冻结是
                //   **页级、持续整页处理时长**（page 7/29/36 各 175s，page 3 63s；正常页中位 8.2s）。
                //   代价实测：3 个冻结页烧掉 ~525s ≈ 全程一半，换 0 件。
                //   另：PAGE_WATCHDOG_MS 只在格循环内检查，覆盖不到格循环**之后**的重访阶段
                //   ⇒ 175s 的页面对 90s 看门狗完全隐形（全程 0 次触发）。
                //   既然救不回，就不再烧时间：跳过重访、显式计数，让损失可见而不是静默。
                val pageSize = (cols * traverseRows).coerceAtLeast(1)
                val longest = longestRunLen(failed)
                if (longest * 2 >= pageSize) {
                    // 该分支整页都不再重访 ⇒ 放弃的是**全部** failed 格，不只是最长那一段。
                    // 原先只加 longest，同页零散失败格被一并弃掉却不计数（摘要低报）。
                    pageFreezeAbandoned += failed.size
                    Log.e(
                        TAG,
                        "pagedGrid[$gridKey]: page=$scannedPage **页级冻结** 最长连续 $longest/$pageSize 格" +
                            " 详情面板未刷新（gridChanged=true ⇒ 网格在正常渲染、每格卡片各不相同，" +
                            "只有右侧详情面板停在上页末格）⇒ 定点重访对此实测 0/502 救回，跳过重访，" +
                            "放弃本格全部 ${failed.size} 个失败格（最长连续 $longest；累计放弃 " +
                            "$pageFreezeAbandoned 格）",
                    )
                } else {
                    dupRevisits += failed.size
                    Log.w(
                        TAG,
                        "pagedGrid[$gridKey] 相邻重复指纹 ${failed.size} 处 idx=$failed" +
                            " ⇒ 定点重访（不退行、不整页重扫）",
                    )
                    val gained = revisitFailedCells(visit, gridKey, pageProfile, cols, failed, pageKeys, pageIdentities)
                    dupRevisitRecovered += gained
                    Log.w(TAG, "  → 定点重访完成：救回 $gained 件（累计 $dupRevisitRecovered/$dupRevisits）")
                }
            }
        }
        rowCheckPrevKeys = ArrayList(pageKeys)
        // ★★ 2026-09-26 方案 C：**收尾主判据 = 件数**，且必须放在"下一页滑动之前"。
        //   `vars.total` 是 OCR 读到的背包计数器（`readCountOnce`），与上游 GOODScanner 的 `total`
        //   同一个来源 —— 它的主循环就是 `let remain = total - scanned_count; if remain == 0 { break }`，
        //   全程**不拿画面判"到底没到底"**，所以也没有"补一滑"这种动作。
        //   计数器读不到（null/0）⇒ 本判据不启用，退回下面的回卷链收尾。
        val collected = results.size + resultsWeapons.size + resultsCharacters.size
        val totalForStop = vars.total
        if (totalForStop != null && totalForStop > 0 && collected >= totalForStop) {
            // 终止权不挂在**单次** OCR 读数上：达标时再读一次，取较大者。
            // 首次读数是非零的偏小值时（96→968 这类丢位），原先会静默提前收尾，
            // 而 run() 里的「总数不符」告警在 stopRequested 分支被跳过 ⇒ 少一屏也报完成。
            val again = recountCounter()
            val confirmed = maxOf(totalForStop, again ?: 0)
            if (collected >= confirmed) {
                Log.i(
                    TAG,
                    "pagedGrid[$gridKey]: 件数达标（复读确认 计数器=$confirmed）已入库=$collected ⇒ 收尾（不再翻页）",
                )
                break
            }
            vars.total = confirmed
            Log.w(
                TAG,
                "pagedGrid[$gridKey]: 首次计数器=$totalForStop 复读=$again ⇒ 抬到 $confirmed" +
                    "（已入库=$collected 未达标 ⇒ 不收尾，继续翻页）",
            )
        }
        val minCmd = Math.round(rowPitch * 0.6f)
        // 滑动**起点**二选一：几何推导（§12.1，落在最左卡缝）或 profile 字面 `advance.from`。
        //   ⚠️ `useGeometryAdvance` 从此**只切换起点**；距离规划 / 记账 / 触摸增益补偿两条路共用同一套。
        //   此前"选起点"与"要不要走控制律"被同一个开关绑死，而产线默认 false ⇒ `pageDrift`、
        //   `advGainEma`、`touchScale` 全落在不执行的分支里（旧注释"坐标写死无法调距"不成立：
        //   起点固定、终点由命令算出即可调距；且三档 profile 的 from/to 同 x，纵向滑动等价）。
        val swipeFrom = geoStart ?: FramePoint(
            profile.scale(advFrom.getInt(0), profile.scaleX),
            profile.scale(advFrom.getInt(1), profile.scaleY),
        )
        val maxCmd = (swipeFrom.y - ADV_MIN_END_Y).coerceAtLeast(minCmd + 40)
        // ⚠️ 2026-09-16 用户定稿：**命令不加增益**（恒 1.0）—— 增益补偿会放大命令 ⇒ 过滚 ⇒
        //   **静默跳行漏件**（宁愿欠滚重复点）。`advGainEma` 仅保留为**诊断量**（日志 `增益=`），
        //   不再参与命令；命令上界由 planMainSwipe 封顶在 target。
        val driftPrev = pageDrift
        val plan = GridAlign.planMainSwipe(advTarget, 1.0, driftPrev, minCmd, maxCmd)
        // 本页实际发出的主滑命令（帧 px）—— 相位块的增益 EMA 用它当分母（不是 target）
        val pageCmd = plan.first
        pageDrift = plan.second
        // 本页发滑后的记账基线（相位块的钳制余量在它之上**覆盖**写，见下方 driftAfterPlan 用法）
        val driftAfterPlan = plan.second
        Log.i(
            TAG,
            // `增益=` 仅诊断（不参与命令，见上）；`起点` 标明几何/字面，便于核对实际走的那条路
            "advance[$gridKey]: 主滑规划 目标=$advTarget 起点=${if (geoStart != null) "几何" else "字面"}" +
                "(${swipeFrom.x},${swipeFrom.y}) 增益=${"%.2f".format(advGainEma)}(诊断)" +
                " 记账(上一页)=$driftPrev 余量=${plan.second} ⇒ 命令=$pageCmd",
        )
        // ★ 2026-09-24（#30）：把**注入像素**按实测触摸增益放大，`pageCmd` 本身仍是"有效滚动"语义
        //   （advTarget / 残差 / 封顶 / 记账 全部不变 ⇒ "命令 ≤ target ⇒ 永不超滚、不会静默跳行"
        //   的保证原样保留）。实测 BlueStacks 注入 834px 只滚 757px（比值 0.905~0.911，跨
        //   500/834/920 三点、跨分辨率配置、跨拖动速度均不变）。
        //   ⚠️ 机制接通但**默认不启用**：三档 profile 的 `advance.touchScale` 都是 1.0
        //   —— 放大后全量扫实测跳件，增益究竟在哪一层未定（见 #30/#33）。
        //   终点仍受 `ADV_MIN_END_Y` 约束（放大会把落点顶出屏外 ⇒ 必须钳）。
        val touchScale = profile.touchScaleFor(gridKey)
        val injected = if (touchScale >= 1.0) pageCmd else Math.round(pageCmd / touchScale).toInt()
            .coerceAtMost(swipeFrom.y - ADV_MIN_END_Y)
        if (injected != pageCmd) {
            Log.i(
                TAG,
                "advance[$gridKey]: 注入缩放 touchScale=$touchScale ⇒ 像素 $pageCmd → $injected" +
                    "（有效目标仍 $pageCmd）",
            )
        }
        swipeLogged(gridKey, swipeFrom.x, swipeFrom.y, swipeFrom.y - injected, "主滑")
        val latest = awaitGridStable(profile, gridKey, requireChange = true)
        // ── ★ 相位消化（2026-09-14 整体改造）：唯一主判据 = fpband 落地条带 ──────────────
        //   res = L − advTarget（raw）；φ_mod = centeredMod(**−res**, rowPitch)（补偿量=target−L，
        //   与 withGridRowOffset 的 y+=φ 语义一致；符号写反过一次，见 GridAlign.phiFromLanding）。
        //   |φ_mod| ≤ 卡片半高 ⇒ 仅平移本页点击坐标（pageDrift 记账清零）；超限 ⇒ 本页不平移、
        //   pageDrift = −res（并入下一次主滑动距离，§12.2 控制律语义）。同页一律**不补滑**（用户定稿）。
        //   fpband 不可测（Reject）⇒ 回退特征锁 phiDetect 仅平移；L < 半行距 ⇒ 判手势未送达
        //   ⇒ 不消化不记账不更 EMA，交外层指纹判定 → 到底重发兜底。
        //   ⚠️ 依赖 advTarget ≡ 行距整数倍（本项目 876 = 3×292 ✓）；若用 advdist/advextra 引入
        //      > 卡片半高的常量偏置，会表现为恒定残差 ⇒ 应先把该偏置从 advTarget 扣掉再比。
        // ★ 2026-09-24 修复（安全窗）：这里**不再**用 `cardH/2`，改用 profile 标定的
        //   `clickBand` 半高（见 ScreenProfile.clickBandHalfFor）。2560 档实测可点带是
        //   行顶起 [0,210]（卡画 290..500），而 `cardSize[1]=253` 推的窗是 126 ⇒ 多放行 42px，
        //   正好把带系统偏差的 φ 放进卡外的行间隙（= 页级冻结的直接成因）。
        // 两个量分开：`bandAcceptHalf` 管"这条读数还救不救得回来"，`shiftCap` 管"点击最多挪多远"
        val bandAcceptHalf = profile.clickBandHalfFor(gridKey)
        val shiftCap = profile.clickShiftCapFor(gridKey)
        var pageOffset: Int? = null
        var phiSrc = "—"
        // ★ 2026-09-16（审计 P1-1）：fpband 给出**可信读数**（Ok 且落地 ≥ 半行距）即置真 ⇒
        //   禁止再回退特征锁平移。超限档 |φ|∈(126,146] 正是特征锁最易锁到邻行的区间，
        //   且回退平移与定稿「超限 ⇒ 本页不平移」相反。Reject/未送达/未登记 ⇒ 保持 false ⇒ 允许回退。
        var fpbandAccepted = false
        if (landingBand != null && landingGeom != null) {
            val gCur = grayOf(latest)
            if (gCur != null) {
                try {
                    val sx = Math.max(0, landingGeom.sy0).coerceAtMost(Math.max(0, gCur.rows() - 1))
                    val ex = Math.min(gCur.rows(), landingGeom.sy1)
                    if (ex > sx + 8) {
                        val search = gCur.submat(org.opencv.core.Range(sx, ex),
                            org.opencv.core.Range(landingGeom.x0, landingGeom.x1))
                        // ★ 期望落点先验（2026-09-16）：把峰搜索限制在 advTarget ± 0.85 行 ⇒
                        //   排除 ±1 行孪生峰（同套密集卡页 peakGap 常掉到 0.02~0.11 被拒、真机 6 页拒 2）。
                        //   离线语料实测生产对 gap 0.20→0.44；门限一个都不放宽。
                        val priorHalf = Math.round(rowPitch * VoteJudges.LANDING_PRIOR_HALF_RATIO).toInt()
                        when (val lr = VoteJudges.landingShift(
                            landingBand!!, search, landingGeom.y0 - landingGeom.sy0, advTarget, priorHalf,
                        )) {
                            is VoteJudges.LandingResult.Ok -> {
                                val L = lr.shift.dy
                                // #70 几何先验：逐页取整（绝不累加），只与内容判决并排打印。
                                val rows = GridAlign.rowsAdvanced(L, rowPitch)
                                if (rows != null && rows != traverseRows) {
                                    Log.w(
                                        TAG,
                                        "advance[$gridKey]: 几何先验与整页不符 L=${L}px pitch=$rowPitch" +
                                            " ⇒ 本滑推进 $rows 行 ≠ $traverseRows 行（跳格仍由内容锚定决定）",
                                    )
                                }
                                if (L < rowPitch / 2) {
                                    // 内容上移不足半行 = 手势未送达：残差无意义 ⇒ 不消化、不记账、不更 EMA
                                    Log.i(
                                        TAG,
                                        "advance[$gridKey]: 落地条带 L=${L}px score=${"%.2f".format(lr.shift.score)}" +
                                            " 行数=$rows ⇒ 不足半行距 ⇒ 判手势未送达，交指纹判定",
                                    )
                                } else {
                                    val dec = VoteJudges.landingDecision(lr.shift, advTarget, pageCmd, advGainEma)
                                    // ⚠️ 符号：补偿量 = target − L = −residual（见 GridAlign.phiFromLanding 的 KDoc）
                                    val phiMod = GridAlign.phiFromLanding(dec.residual, rowPitch)
                                    advGainEma = dec.gain
                                    fpbandAccepted = true
                                    if (Math.abs(phiMod) <= bandAcceptHalf) {
                                        pageOffset = phiMod
                                        phiSrc = "fpband平移"
                                        pageDrift = GridAlign.driftAfterPage(phiMod, dec.residual, bandAcceptHalf)
                                        Log.i(
                                            TAG,
                                            "advance[$gridKey]: 落地条带 L=${L}px score=${"%.2f".format(lr.shift.score)}" +
                                                " 行数=$rows 残差=${dec.residual} φ=$phiMod ⇒ 平移点击坐标（记账清零）",
                                        )
                                    } else {
                                        pageDrift = GridAlign.driftAfterPage(phiMod, dec.residual, bandAcceptHalf)
                                        phiSrc = "fpband超限记账"
                                        Log.i(
                                            TAG,
                                            "advance[$gridKey]: 落地条带 L=${L}px score=${"%.2f".format(lr.shift.score)}" +
                                                " 行数=$rows 残差=${dec.residual} φ=$phiMod ⇒ 超可点带半高($bandAcceptHalf) ⇒ 本页不平移，" +
                                                "记账 ${pageDrift}px 进下一次主滑",
                                        )
                                    }
                                }
                            }
                            is VoteJudges.LandingResult.Reject -> {
                                phiSrc = "fpband拒"
                                Log.w(TAG, "advance[$gridKey]: 落地条带不可测(${lr.reason}) ⇒ 回退特征锁")
                            }
                        }
                    } else {
                        Log.w(TAG, "advance[$gridKey]: 落地条带搜索窗越界(sx=$sx,ex=$ex) ⇒ 回退特征锁")
                    }
                } finally {
                    gCur.release()
                }
            }
            // ★ 2026-09-16（审计 P2-6）：模板**不在此处释放** —— 否则走「到底重发」回边时条带已失效，
            //   只能回退特征锁（±70px）。条带取自**本页首次滑动前**的帧，重发后仍是它的平移且搜索窗
            //   含原位 ⇒ 依旧可测。释放挪到本页翻页真正结束处（下方 while(true) 出口）。
        }
        // ★ 2026-09-24 试过新增一个"绝对行顶检测"（detectRow − 名义行顶）来取代条带，已回滚删除，
        //   run7 实测**否决**：28 页读数在 −137..+119 乱跳，而同一时刻 20px 标尺量到卡片
        //   正压在名义格 290/568/846 上（真值 0）。原因在 `buildColumnProfiles`：列亮度按
        //   **整卡宽 196px 取均值** ⇒ 台阶被卡面美术与白色底栏主导，`detectStep` 锁到的常是
        //   底栏/星条而非卡顶上沿 ⇒ 逐页翻脸。band 那条虽有系统偏差（见 §12.4 记录），
        //   但至少稳定同向 ⇒ 暂仍作 φ 来源；两条都不许直接当"绝对相位"用。
        // ★★ 2026-09-25 判据换源（run9 冻结根因，见 GridAlign 底部「底栏锚」段与任务 #38）：
        //   点击平移量改用**绝对行相位** δ（实测行顶 − 名义行顶），不再用条带的相邻帧残差 φ。
        //   理由：φ 是"这一滑比目标少/多滚了多少"，而 withGridRowOffset 需要的是"行现在在哪"；
        //   两者只在上页正好落在名义位时等价。run9 条带每页报 φ=+44..+119（向下），像素实测
        //   三张冻结帧内容比名义**偏上** 71..101 ⇒ 点击落在卡下沿 1..12px 的死区 ⇒ 整页冻结。
        //   条带照常测量并打日志（对照 + 继续喂 EMA/手势未送达判据），只是不再决定点击坐标。
        var rulerUsed = false
        val bandPhi = pageOffset
        val bandSrc = phiSrc
        val phase = GridAlign.rowPhase(latest, profile, gridKey)
        if (phase != null) {
            val half = rowPitch / 2
            if (Math.abs(phase.offset) <= half) {
                pageOffset = phase.offset
                phiSrc = "底栏绝对"
                rulerUsed = true
                // ⚠️ **记账必须为 0**（run10 实测：把折叠后的 δ 记进下一次主滑 ⇒ 整轮提前收在 20 页）。
                //   δ 是 mod pitch 折叠过的相位（+134 与 −144 是同一个画面），因此它**只够回答
                //   "卡现在在哪个相位"**，不够回答"上一滑少滚/多滚了多少行"。用它做累积性补偿
                //   （缩短命令）会在"其实是欠滚但折成负"的页上越补越少 ⇒ 页间重叠单调增大 ⇒
                //   duplicateStreak 判到底。欠/过滚的记账继续归条带那条（它本来就是增量语义）。
                pageDrift = 0
                Log.i(
                    TAG,
                    "advance[$gridKey]: 底栏绝对行相位 δ=${phase.offset}px" +
                        "（列 ${phase.columns} 票 ${phase.votes} 一致带 ${phase.spread}px 带数 ${phase.bars}）" +
                        " ⇒ 点击按实测行顶；对照：条带 φ=$bandPhi($bandSrc)；本路**不记账**（pageDrift 恒 0，见上）",
                )
            } else {
                Log.w(
                    TAG,
                    "advance[$gridKey]: 底栏绝对行相位 δ=${phase.offset} 超半行距($half)" +
                        " ⇒ 行序映射不可信，沿用 $bandSrc（φ=$bandPhi）",
                )
            }
        } else {
            Log.w(TAG, "advance[$gridKey]: 底栏绝对行相位不可测 ⇒ 沿用 $bandSrc（φ=$bandPhi，仅对照）")
        }
        if (pageOffset == null && !fpbandAccepted) {
            // 回退：特征锁（fpband 未跑/未登记/拒/未送达——未送达页画面没动，测出即上一页相位）
            pageOffset = GridAlign.phaseOffset(latest, profile, gridKey)
            if (pageOffset != null) phiSrc = "特征锁"
        } else if (pageOffset == null) {
            // ★ 2026-09-16（审计 P1-1）：fpband 有可信读数但超半卡高 ⇒ **本页不平移**（定稿），
            //   只把残差记账进下一次主滑；此处禁止用特征锁兜（超限档必锁邻行）。
            Log.i(TAG, "advance[$gridKey]: 本页不平移（$phiSrc）⇒ 沿用上一页相位、残差已记账")
        }
        if (pageOffset != null) {
            // ★★ 2026-09-17 修（真机证据定案）★★ **统一钳制 φ 到 ±点击安全窗**
            //   窗 = [ScreenProfile.clickShiftCapFor]：标定过 clickBand 的网格用实测可点带 ×2/3
            //   （2560 圣遗物 = 70）；**未标定的网格 = 0 ⇒ 一律不平移**（#51）。
            //   截至本轮，六个背包网格已全部标定（#53/#56），"不平移"这条实际只剩非背包网格；
            //   别照抄旧版本在这里列的"武器 / 3200 / 2244"清单 —— 那份枚举标定前写的，已过期。
            //   本变量有两条来源：① fpband 路径（`phiMod`，已自带 |φ|≤bandAcceptHalf 判断 ✓）
            //   ② **特征锁回退路径**（`GridAlign.phaseOffset`，**此前无任何钳制** ✗✗）
            //   实测日志：fpband 的 φ 都在 34~74 ✓，但出现 φ = 139 / 134 / −134 / −131 / 135
            //   —— 全部来自特征锁回退 ⇒ **|φ| 超出安全窗 ⇒ 点击落到卡片之外（卡缝/邻卡）**
            //   ⇒ 该格读到**邻卡内容**（所以 appeared=true、去重把它当重复吞掉、**全程无痕**）
            //   ⇒ 目标件从未入库 = 就是那些漏件 ✓（18 次超限 ≈ 每 4 页 1 次 ≈ 去重后漏 10 件，量级吻合）
            // ★★ 2026-09-25（run11 真机定因）：底栏绝对读数**不钳安全窗**，但**必须钳网格上沿**。
            //   run11 第 8 页 δ=−134 ⇒ 行 0 点击落到 y=261，那里不是卡片而是
            //   「按获得时间顺序展示5星圣遗物」那一行（label [1006,211,1464,245] + 开关
            //   [1466,196,1560,250]）⇒ **扫描自己把 5星筛选打开了**，整轮列表塌成 930 件 5★，
            //   4★/3★ 共 159 件根本没进视图（导出 rarity 全 5 是现场证据）。
            // 行 0 点击不得落到真实卡顶之上：
            //   - 未标定带 / 带上沿为 0 的网格 ⇒ −clickDy = 名义行顶
            //   - 2244 圣遗物带 = [53,242]，cardOrigin.y 比真实卡顶高 53px ⇒ −(clickDy − bandTop) = 真实卡顶
            //   ⚠️ 这版地板不再等于"名义行顶"，但仍落在带内 ⇒ 安全（#64）。
            // ★ #64 随本值落地而关闭：旧版 −clickDy 在 2244 会把下界放行到 162 = 真实卡顶之上 53px。
            val gPhase = profile.gridGeometryFor(gridKey)
            val clickFloor = if (gPhase == null) -shiftCap
            else -(profile.scale(gPhase.clickDy, profile.scaleY) -
                profile.scale(gPhase.bandTop, profile.scaleY))
            val phiClamped = when {
                // 未标定 ⇒ 窗为 0：不平移，但要说清"为什么这条页的相位闭环没生效"，
                //   否则日志看上去像"偏移恰好为 0、一切正常"（本项目栽过三次同类假象）。
                !rulerUsed && shiftCap == 0 -> {
                    Log.i(
                        TAG,
                        "advance[$gridKey]: φ=$pageOffset（$phiSrc）**未采用 ⇒ 本页不平移**：" +
                            "grids.$gridKey 没有实测 clickBand，没有可点带依据可平移。" +
                            "要恢复先量该档 clickBand/labelAnchor（见 ScreenProfile.clickShiftCapFor）",
                    )
                    0
                }
                !rulerUsed -> pageOffset.coerceIn(-shiftCap, shiftCap)
                pageOffset < clickFloor -> {
                    Log.w(
                        TAG,
                        "advance[$gridKey]: δ=$pageOffset 会把行 0 点击顶到网格上方（限到 $clickFloor" +
                            "）—— 上方是筛选行/5星开关，点上去会**改掉筛选条件**",
                    )
                    clickFloor
                }
                else -> pageOffset
            }
            if (!rulerUsed && shiftCap > 0 && phiClamped != pageOffset) {
                // 超出部分**记账到下一次主滑**（与 fpband 超限分支同语义：本页只消化半卡内）。
                //   ⚠️ 必须从"本页快照"覆盖写，不能 `pageDrift +=`：本块在「到底重发」的 while 回边里
                //   会再跑一次，累加语义会把同一页的钳制余量记 2~4 遍（一次滑动被当成多次记账）。
                pageDrift = driftAfterPlan + (pageOffset - phiClamped)
                Log.w(
                    TAG,
                    "advance[$gridKey]: **相位 φ=$pageOffset 超点击上限($shiftCap) ⇒ 钳制为 $phiClamped**" +
                        "（来源 $phiSrc；超限会导致点击落到邻卡 ⇒ 漏件）",
                )
            }
            // ★ 2026-09-19 真机 A/B 开关：`phi=0` ⇒ 本页不做相位平移（点击坐标用 profile 原值）。
            //   动机：华为真机上 fpband 给出 **φ=-69**，把行 0 点击从 y=250 顶到 y=181
            //   （行 0 卡顶 221 之上）⇒ 点到空白 ⇒ 整页 dup/落空。需要一个免重编开关在真机上验证。
            if (TimingOverrides.phiApply) {
                pageProfile = profile.withGridRowOffset(phiClamped)
                Log.i(TAG, "advance[$gridKey]: 页面相位 φ=$pageOffset ($phiSrc)")
            } else {
                Log.w(TAG, "advance[$gridKey]: **φ=$pageOffset 已按开关忽略（phi=0）⇒ 点击坐标用原值**（$phiSrc）")
            }
        } // 测量全失败：沿用上一页偏移（相位近似延续）

        latest.release()
        // ★★ 2026-09-26 方案 C：**翻页有且只滑一次** —— 整条「指纹未变 ⇒ 重发」回路已删
        //   （回路出自 `2432d26`/2026-09-13，别照着它的理由加回来）。它拿 `reachedEnd(beforeThumb,
        //   latestThumb)` 判"这一滑没落地"，可**同一个函数**自己量到的落地条带才是位移证据：
        //   小米 15（3200×1440）16 次翻页里 6 次在 `L=871px`（目标 876、残差 −5 ⇒ 确实滚了 871）
        //   时被误判"未变"，补第二滑 ⇒ 一页前进 ~1731px ≈ **2 页**，每次静默跳过约 21 件，
        //   事后任何对账都追不回来（`翻页#N` 照常 +1、每页照常 21 格）。模拟器/华为上这条判据
        //   只在列表真到底时命中一次（紧接 `reached end`+`completed`）⇒ 跑着全对，所以两周没暴露。
        //   滑空怎么办：阈值抬到「一页 + 一行」后，**单次**滑空造出的那一页重复件凑不满阈值 ⇒ 只是
        //   白读一页，下一轮照常只滑一次；要误停得连续两次滑空。终止链见 `noteDupAndMaybeStop`。
        // ★ 2026-09-16（审计 P2-6）：条带模板到「本页翻页真正结束」才释放
        landingBand?.release()
        landingBand = null
        pagesAdvanced++
        Log.i(TAG, "pagedGrid 翻页#$pagesAdvanced（已扫 $pageNo 页, maxPages=$maxPages）")
        // ★ 2026-09-26 方案 C 的**后备**上限：按件数推算"最多该翻几页"，超出即收尾。
        //   为什么还要它：`整页重复第…` 与 `回卷止扫（确认…` 在 3200/2244/2560/华为四轮全量里
        //   计数**都是 0** —— 每次都是刚被删掉的那条指纹回路先收的尾 ⇒ 回卷链等于从没被验证过。
        //   ⇒ 三条链分工：**件数 = 主判据**（GOODScanner 同构），**回卷 = 正常兜底**，
        //     **本上限 = 回卷也失灵时的最后一道**（计数器偏大 / 列表卡住一直翻）。
        if (pagesAdvanced > pagesByCount) {
            Log.w(
                TAG,
                "pagedGrid[$gridKey]: 翻页数 $pagesAdvanced > 按件数推算的上限 $pagesByCount" +
                    "(计数器=${vars.total ?: "?"} ÷ 每页 $cellsPerPage + $PAGE_CAP_MARGIN) ⇒ 收尾",
            )
            vars.stopRequested = true
            vars.stopReason = "pageCap"
            break
        }
        // ★ A9 双保险：**总滑动次数上限**（独立于计数器）。计数器读不到时 pagesByCount=
        //   Int.MAX_VALUE、生产 maxPages 无上限 ⇒ 这是「列表卡住一直翻」的最后一道闸。
        //   默认 240 ≈ 2× 满背包（2400 件 ÷ 21 件/页 ≈ 115 页）翻页量，正常扫描远够用。
        if (pagesAdvanced >= TimingOverrides.maxTotalSwipes) {
            Log.w(
                TAG,
                "pagedGrid[$gridKey]: 总滑动次数 $pagesAdvanced ≥ 上限 ${TimingOverrides.maxTotalSwipes} " +
                    "⇒ 收尾（已入库 ${results.size + resultsWeapons.size + resultsCharacters.size} 件照常导出）",
            )
            vars.stopRequested = true
            vars.stopReason = "swipeCap"
            break
        }
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
internal suspend fun ScanEngine.swipeGridToTop(
    gridKey: String,
    logTag: String,
    // ★ 2026-09-30 #144：默认从 1 提到 2（与 pagedGrid 那条一直用的 GRID_TOP_STABLE_ROUNDS 对齐）。
    //   2244 实测：setFilter 的 `回顶#1` 连续两轮报 diff=0.010 / 0.000 就判「已到顶」，
    //   而**真顶**首行是 HeartOfTheFurnace（炉火融炼之心，手工连滑 8 次后逐字节复验到位），
    //   两轮"顶页"读数分别是 乐园遗落之花 / 流浪大地的乐团 ⇒ 一次没生效的手势（或被吞、或帧源没更新）
    //   就足以把整册判成"已在顶部"，后面的翻页是从**未知行号**起步的。
    //   判"到顶"从此要求**连续两次**无变化；代价 = 每次回顶多一轮（~0.8s 手势 + 判稳窗）。
    stableRounds: Int = GRID_TOP_STABLE_ROUNDS,
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
internal fun ScanEngine.thumbMask(a: ByteArray, b: ByteArray): String {
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
internal suspend fun ScanEngine.awaitGridStable(
    profile: ScreenProfile,
    gridKey: String,
    requireChange: Boolean = false,
): Mat {
    val start = clock()
    var prevThumb: ByteArray? = null
    var stableSince = -1L
    var sawChange = false
    var warnedNoChange = false
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
                    // ★ 2026-09-14（`requireChange`，滑动后专用）：本循环只判「连续 700ms 帧间差 ≤ 阈值」，
                    //   从不要求"先看到变化" ⇒ 而手势是**异步派发**（见 swipeGridToTop 注释：780ms 手势不等走完）
                    //   ⇒ 若画面起步晚于 ~700ms，会把**滑动前的静止帧**当稳定帧返回（日志照样 diff=0.000）
                    //   ⇒ 位移/相位测的是滑动前状态，且**静默**。故：在 [SETTLE_CHANGE_WINDOW_MS] 内
                    //   **必须先观察到一次变化**才允许判稳定；超窗仍未变 ⇒ 视为"滑动未送达/列表到底"。
                    //   ★ 2026-09-14（补滑空转可见性）：旧逻辑只在 6000ms 全程超时才告警，
                    //   「1500ms 先变化窗已过 → 仍按连续稳定判返回」的区间是**静默**的
                    //   （实测 5 连 0 补滑的 settle 全部无告警）。超窗仍未看到变化 ⇒ 打一次警告（每次调用仅一行）。
                    if (requireChange && !sawChange && !warnedNoChange && clock() - start >= SETTLE_CHANGE_WINDOW_MS) {
                        warnedNoChange = true
                        Log.w(
                            TAG,
                            "settle: ${clock() - start}ms 未观察到变化（超 ${SETTLE_CHANGE_WINDOW_MS}ms 窗）" +
                                " ⇒ 本次滑动疑似未送达 / 列表已到底",
                        )
                    }
                    if (requireChange && !sawChange && clock() - start < SETTLE_CHANGE_WINDOW_MS) {
                        prevThumb = thumb
                        continue
                    }
                    if (stableSince < 0) stableSince = clock()
                    if (clock() - stableSince >= sStable) {
                        Log.i(TAG, "page settle: ${clock() - start}ms (stable diff=${"%.3f".format(diff)})")
                        return latest
                    }
                } else {
                    sawChange = true
                    stableSince = -1L
                }
                prevThumb = thumb
            }
        }
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        latest?.release()
        throw e
    }
    Log.w(
        TAG,
        "settle not stabilized within ${SETTLE_MAX_MS}ms, using latest frame" +
            if (requireChange && !sawChange) "（⚠️ 全程未观察到变化 ⇒ 本次滑动疑似未送达 / 列表已到底）" else "",
    )
    return latest ?: freshFrame(SETTLE_FRAME_TIMEOUT_MS)
}

/**
 * ★ A40（#147）：翻页后的**跨页判稳** —— 相邻帧底栏锚位移信号，替代 `CROSS_PAGE_SETTLE` 定时 250ms。
 *
 * 为什么换信号而不是再调 settle（实测调 settle 无效）：翻页主滑后 [awaitGridStable] 用
 * 24×16 缩略图差异判稳，阈值 GRID_SIMILAR_DIFF(0.10) 看不见**几 px/帧的残余慢漂**
 * （一帧挪 2px 对缩略图几乎零差异）；固定 250ms 只是猜一个"应该停了"的时长，慢漂不止时
 * 页首抓帧仍带着位移 ⇒ 卡格指纹整体偏移、点击落点错位 ⇒ 整页 OCR 出"自信乱码"。
 *
 * 新信号：以**网格第 4 可见行条带**（`grids.<key>.landingBand`，翻页落地测量已登记的"底栏锚"）
 * 为模板，相邻两帧做 2D 匹配量位移（复用 [VoteJudges.landingShift]，不新造模板依赖）：
 * 连续 [ANCHOR_SETTLE_STABLE_PAIRS] 对帧的锚位移都 ≤ [ANCHOR_SETTLE_MAX_DY_PX] px 才算稳。
 * 静止列表的模板匹配读数是 0~1px（整数像素量化 + 采集抖动），阈值 3 ⇒ 残余慢漂 ≥30px/s
 * 即被拒；阈值取紧不取松，代价只是多等几对帧（预算 [ANCHOR_SETTLE_MAX_MS] 封顶）。
 *
 * 降级路径（行为与旧版逐位一致）：该档没有 landingBand 几何 ⇒ 回退 `delay(CROSS_PAGE_SETTLE_MS)`；
 * 条带不可测（Reject）/几何不足 ⇒ 该对帧不计入、继续轮询；预算打满仍未凑满 ⇒ 按已稳继续
 * （对齐旧定时"到点就过"的语义，不白烧）。
 *
 * ⚠️ `internal` 供 CrossPageSettleTest 直接注入漂移帧序列单测（不跑整条 flow）。
 */
internal suspend fun ScanEngine.awaitCrossPageSettle(gridKey: String) {
    val geom = profile.landingBandFor(gridKey)
    if (geom == null) {
        delay(CROSS_PAGE_SETTLE_MS)
        return
    }
    val start = clock()
    var stablePairs = 0
    var prevBand: Mat? = null
    try {
        while (clock() - start < ANCHOR_SETTLE_MAX_MS && stablePairs < ANCHOR_SETTLE_STABLE_PAIRS) {
            delay(ANCHOR_SETTLE_POLL_MS); tmSettleMs += ANCHOR_SETTLE_POLL_MS
            val f = runCatchingCancellable { freshFrame(SETTLE_FRAME_TIMEOUT_MS) }.getOrNull() ?: break
            val g = grayOf(f)
            f.release()
            if (g == null) continue
            var moved: Int? = null
            val band = runCatchingCancellable { VoteJudges.landingBandMat(g, geom) }.getOrNull()
            val pb = prevBand
            if (band != null && pb != null) {
                // 搜索窗 = 本帧条带位置 ± 窗（相邻帧位移有限，窗不必覆盖整屏；窗宽不足时弃测）
                val sy0 = (geom.y0 - ANCHOR_SETTLE_WINDOW_PX).coerceAtLeast(0)
                val sy1 = (geom.y1 + ANCHOR_SETTLE_WINDOW_PX).coerceAtMost(g.rows())
                val x0 = geom.x0.coerceAtLeast(0)
                val x1 = geom.x1.coerceAtMost(g.cols())
                if (sy1 - sy0 >= band.rows() + 2 && x1 - x0 == band.cols()) {
                    val search = g.submat(org.opencv.core.Range(sy0, sy1), org.opencv.core.Range(x0, x1))
                    moved = when (val lr = VoteJudges.landingShift(pb, search, geom.y0 - sy0)) {
                        is VoteJudges.LandingResult.Ok -> lr.shift.dy
                        is VoteJudges.LandingResult.Reject -> null
                    }
                }
            }
            prevBand?.release()
            prevBand = band
            g.release()
            if (moved != null && Math.abs(moved) <= ANCHOR_SETTLE_MAX_DY_PX) {
                stablePairs++
            } else {
                if (stablePairs > 0 || moved != null) {
                    Log.i(
                        TAG,
                        "跨页判稳[$gridKey]: 锚位移 ${moved ?: "不可测"}px ⇒ 重计（$stablePairs/" +
                            "$ANCHOR_SETTLE_STABLE_PAIRS 对稳定）",
                    )
                }
                stablePairs = 0
            }
        }
        prevBand?.release()
        prevBand = null
    } finally {
        prevBand?.release()
    }
    if (stablePairs >= ANCHOR_SETTLE_STABLE_PAIRS) {
        Log.i(
            TAG,
            "跨页判稳[$gridKey]: ${clock() - start}ms 内锚点连续 $stablePairs 对帧位移 " +
                "≤ ${ANCHOR_SETTLE_MAX_DY_PX}px ⇒ 判稳",
        )
    } else {
        Log.w(
            TAG,
            "跨页判稳[$gridKey]: ${clock() - start}ms 未凑满 $ANCHOR_SETTLE_STABLE_PAIRS 对稳定帧 " +
                "（锚不可测/预算打满）⇒ 按原定时语义继续",
        )
    }
}

internal suspend fun ScanEngine.freshFrame(timeoutMs: Long = 2500L): Mat {
    // 只读探针：freshFrame **包含「等一个新帧」的等待**（投影 ~30fps ⇒ 平均 ~33ms），
    // 这部分不计入 nav/panel/settle ⇒ 是「其它」里最易被忽略的大块（见 IMAGE-PATH-COST.md §8）。
    lastProgressAtMs = android.os.SystemClock.elapsedRealtime()
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
