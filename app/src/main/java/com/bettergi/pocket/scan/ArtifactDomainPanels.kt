package com.bettergi.pocket.scan

import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Mat
import com.bettergi.pocket.scan.ScanEngine.Companion.TAG
import com.bettergi.pocket.scan.ScanEngine.Companion.PANEL_SHOT_JPEG_QUALITY
import com.bettergi.pocket.scan.ScanEngine.Companion.PANEL_SHOT_MAX_PER_RUN
import com.bettergi.pocket.scan.ScanEngine.Companion.PANEL_SHOT_MIN_FREE_BYTES
import com.bettergi.pocket.scan.ScanEngine.Companion.PANEL_SHOT_SPACE_CHECK_EVERY
import com.bettergi.pocket.scan.ScanEngine.Companion.PANEL_FP_GATE_ENABLED
import com.bettergi.pocket.scan.ScanEngine.Companion.PANEL_FP_WAIT_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.PANEL_FP_DUP_WAIT_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.PANEL_FP_POLL_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.PANEL_RAW_DUMP
import com.bettergi.pocket.scan.ScanEngine.Companion.STAR_GOLD_THRESHOLD
import com.bettergi.pocket.scan.ScanEngine.Companion.STOP_MARKER_RARITY
import com.bettergi.pocket.scan.ScanEngine.Companion.WEAPON_PANEL_DELAY_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.WEAPON_SAME_IDENTITY_CAP
import com.bettergi.pocket.scan.ScanEngine.Companion.DEFAULT_SET_DICT
import com.bettergi.pocket.scan.ScanEngine.Companion.crossPageOverlap
import com.bettergi.pocket.scan.ScanEngine.Companion.equippedOwnerOf
import com.bettergi.pocket.scan.ScanEngine.Companion.panelShotDir
import com.bettergi.pocket.recognition.name.GoodNames
import kotlinx.coroutines.delay
import org.opencv.imgcodecs.Imgcodecs

/*
 * Stage 3.3 步②：parsePanel 簇从 ScanEngine.kt 抽出（扩展函数，函数体一字未改）。
 * 抽出的成员：writeShot / shotGateOpen / dumpPanelShot / dumpStallShot / parsePanel /
 * evaluatePanelMatch / parseWeaponPanel / parseArtifactPanel / readManageIcons /
 * cellFingerprintRect / countStars。它们通过扩展接收者访问 ScanEngine 的成员
 * （`this` = ScanEngine 实例），故调用点与语义完全不变。
 */


// ---- #5 parsePanel：字段槽 OCR → GoodArtifact（含 set_name 词典反推）----
/**
 * 取证落盘的**唯一出口**：写图 + 追加一行 manifest，二者成败一致。
 *
 * ★ #46 的三条护栏（原先两条 dump* 各自裸写，谁都没管）：
 * 1. **`imwrite` 的布尔返回值必须看**。原先忽略 ⇒ 磁盘满/编码失败时，紧接着仍往
 *    `manifest.jsonl` 追加一行 ⇒ 清单里出现**根本不存在的"幽灵取证图"**（下游按清单
 *    去 pull 会缺文件，却看不出是写失败）。
 * 2. **张数上限**：一轮 1256 格全落 = 数百 MB（实测 JPEG q80 约 351KB/张）。超限后
 *    只计数不落盘 —— 取证只需**样本**，不需要全量。
 * 3. **剩余空间闸**：可用空间低于 [PANEL_SHOT_MIN_FREE_BYTES] 时停落，避免把设备写满。
 *
 * @param json manifest 行（调用方填好，除 `shot`/`seq` 由本函数补）
 * @return true = 确实落了图且记了 manifest
 */
internal fun ScanEngine.writeShot(dir: java.io.File, name: String, seq: Int, json: JSONObject, frame: Mat): Boolean {
    val t0 = SystemClock.elapsedRealtime()
    // ★ #46 ④：`MatOfInt` 是**原生内存**，靠 GC/finalize 回收不及时 ⇒ 显式 release。
    //   原实现每次 imwrite 都 new 一个且从不释放（一轮 1256 张 = 1256 个泄漏）。
    val params = org.opencv.core.MatOfInt(
        Imgcodecs.IMWRITE_JPEG_QUALITY, PANEL_SHOT_JPEG_QUALITY,
    )
    val ok = try {
        runCatchingCancellable {
            Imgcodecs.imwrite(java.io.File(dir, "$name.jpg").absolutePath, frame, params)
        }.getOrDefault(false)
    } finally {
        params.release()
    }
    if (!ok) {
        panelShotWriteFailures++
        // 只在首次与每 50 次打一行：写失败通常是持续性的（磁盘满），逐张刷屏没有信息量
        if (panelShotWriteFailures == 1 || panelShotWriteFailures % 50 == 0) {
            Log.w(TAG, "取证图写失败 #$panelShotWriteFailures（不记 manifest）：${dir.absolutePath}/$name.jpg")
        }
        return false
    }
    java.io.File(dir, "manifest.jsonl").appendText(
        json.put("seq", seq).put("shot", "$name.jpg")
            .put("writeMs", SystemClock.elapsedRealtime() - t0)
            .put("wallMs", System.currentTimeMillis())
            .toString() + "\n",
    )
    return true
}

/**
 * 取证开关的**闸门**：张数上限 + 剩余空间。关着开关时调用方已在更外层返回。
 *
 * 空间检查**不是每张都做**（`getUsableSpace` 要 statfs）：每 [PANEL_SHOT_SPACE_CHECK_EVERY]
 * 张查一次，结果缓存在 [panelShotSpaceOk]，够用且不拖慢热路径。
 *
 * @return true = 允许落盘；false = 已到闸门（调用方只计数）
 */
internal fun ScanEngine.shotGateOpen(dir: java.io.File): Boolean {
    if (panelShotSeq + panelShotDropped >= PANEL_SHOT_MAX_PER_RUN) {
        panelShotDropped++
        if (panelShotDropped == 1) {
            Log.w(TAG, "取证已达本轮上限 ${PANEL_SHOT_MAX_PER_RUN} 张 ⇒ 后续只计数不落盘")
        }
        return false
    }
    if (panelShotSpaceCheckedAt == 0 || panelShotSeq - panelShotSpaceCheckedAt >= PANEL_SHOT_SPACE_CHECK_EVERY) {
        panelShotSpaceCheckedAt = panelShotSeq
        panelShotSpaceOk = runCatchingCancellable { dir.usableSpace }.getOrDefault(Long.MAX_VALUE) >=
            PANEL_SHOT_MIN_FREE_BYTES
        if (!panelShotSpaceOk) {
            Log.w(TAG, "取证落盘暂停：剩余空间不足 ${PANEL_SHOT_MIN_FREE_BYTES / (1 shl 20)}MB" +
                "（dir=${dir.absolutePath}）")
        }
    }
    if (!panelShotSpaceOk) {
        panelShotDropped++
        return false
    }
    return true
}

/**
 * 调试落盘：把**识别实际使用的那一帧**存成图片 + 一行 JSONL 清单。
 *
 * 为什么需要：整页面板冻结时，日志只能给出间接信号（`appeared=false`、指纹不变、选中框判据），
 * 无法区分「游戏侧详情面板真的没换」与「我方误识别/读的是旧缓存帧」。逐格留帧后可直接
 * 与**上一格的同位置画面**比对 —— 卡片外白边在不在、在不在被点的那一格上，肉眼即可判定。
 *
 * 只在 [panelShotDir] 非空时动作（默认关，扫描热路径零开销：一次 null 判断）。
 * 帧是 BGR（`MatOps` 约定），恰为 OpenCV `imwrite` 的默认通道序，直接写即可。
 */
internal fun ScanEngine.dumpPanelShot(frame: Mat, panelKey: String, fpWaitMs: Long) {
    val dir = panelShotDir ?: return
    if (!shotGateOpen(dir)) return
    runCatchingCancellable {
        val seq = ++panelShotSeq
        val name = String.format("p%03d_c%02d_s%04d", curPageNo, curCellCol, seq)
        val json = JSONObject()
            .put("page", curPageNo)
            .put("cellIdx", curCellIdx)
            .put("col", curCellCol)
            .put("panel", panelKey)
            // 本格定案值：面板指纹确实换过（新面板渲染出来了）
            .put("appeared", lastPanelAppeared?.toString() ?: "无闸门")
            // 点击前卡片中心像素与上一格是否不同（true ⇒ 网格本身在正常刷新）
            .put("gridChanged", gridCellChanged)
            // 指纹闸门预算：重复格走 100ms 快档、新格走全档
            .put("fpWaitMs", fpWaitMs)
            .put("prevIdentity", lastCellIdentity ?: "")
        if (writeShot(dir, name, seq, json, frame) && seq % 20 == 0) {
            Log.i(TAG, "panelShot: 已落 $seq 张（dir=${dir.absolutePath}）")
        }
    }.onFailure { Log.w(TAG, "panelShot #$panelShotSeq 落盘失败", it) }
}

/**
 * **停滞取证**：点击后面板始终不变（重发也救不回来）时，落一张**全屏**图 + 一行 manifest。
 *
 * 要回答的问题：日志只能给出间接信号（`accepted=true` 但选中框不动、面板指纹不变），
 * 分不清这三种：① 有遮罩/弹层（如迟到的确认框）盖住界面吃掉点击；② 点击落到卡外的空隙/
 * 非交互区；③ 游戏侧输入彻底卡死。逐次重发各留一张 ⇒ 直接看按下态、遮罩、选中白边在哪。
 *
 * 与 [dumpPanelShot] 共用目录与 manifest（`kind="stall"` 区分），同样只在
 * [panelShotDir] 非空时动作 —— 关闭时**连帧都不抓**。
 *
 * @param phase `retry` = 第 attempt 次重发前；`giveup` = 打满上限、本格将读到陈旧面板
 * @param selMoved 选中框是否已移动（null = 该路径无选中框判据）
 */
internal suspend fun ScanEngine.dumpStallShot(
    cx: Int,
    cy: Int,
    col: Int,
    row: Int,
    index: Int,
    attempt: Int,
    selMoved: Boolean?,
    clickOk: Boolean,
    phase: String,
) {
    // 计数必须落在取证开关**之前**：关着开关跑全量是常态，而"本格被放弃、将读到陈旧面板"
    // 这件事不能因为不落图就从摘要里消失。
    // ★ 2026-09-30 #154：日志同理**无条件**打。原先放弃一格只有摘要里的一个聚合计数
    //   （`点击放弃=N格`），事后无法回答"是哪一格"—— 2244 那轮 Lisa/Yaoyao 连读数行都没有，
    //   当时就没法定位到本分支。取证图受开关限制，日志不该受。
    if (phase == "giveup") {
        clickGiveups++
        Log.w(
            TAG,
            "格放弃: page=$curPageNo cell($col,$row) idx=$index（重发 $attempt 次面板仍无变化" +
                " selMoved=$selMoved clickOk=$clickOk）⇒ 本格将读到陈旧面板",
        )
    }
    val dir = panelShotDir ?: return
    // 闸门放在 freshFrame **之前**：到顶/没空间时连帧都不抓（与"关着开关不抓帧"同一口径）。
    if (!shotGateOpen(dir)) return
    runCatchingCancellable {
        val seq = ++panelShotSeq
        val name = String.format("stall_p%03d_i%02d_%s%d_s%04d", curPageNo, index, phase, attempt, seq)
        val f = freshFrame()
        // 同一帧顺带判遮罩：白底占比 + 是否达到 [dismissLockConfirm] 的命中线（**只判不点**）
        val overlay = runCatchingCancellable {
            val obj = profile.rawObject("screens.dialogs.lockConfirm")
            val probe = runCatchingCancellable { profile.rect("screens.dialogs.lockConfirm.probe") }.getOrNull()
            if (obj == null || probe == null) null
            else {
                val r = lockOverlayWhiteRatio(f, probe)
                JSONObject().put("whiteRatio", r).put("wouldHit", r >= obj.optDouble("whiteRatio", 0.25))
            }
        }.getOrNull()
        val json = JSONObject()
            .put("kind", "stall")
            .put("phase", phase)
            .put("page", curPageNo)
            .put("cellIdx", index)
            .put("col", col)
            .put("row", row)
            .put("clickX", cx)
            .put("clickY", cy)
            .put("clickOk", clickOk)
            .put("attempt", attempt)
            .put("selMoved", selMoved ?: JSONObject.NULL)
            // 加锁提示框遮罩（只判不点）：null = profile 未登记该弹框
            .put("lockOverlay", overlay ?: JSONObject.NULL)
            .put("gridChanged", gridCellChanged)
            .put("prevIdentity", lastCellIdentity ?: "")
        try {
            if (writeShot(dir, name, seq, json, f)) {
                Log.i(TAG, "stallShot: $phase #$attempt cell($col,$row) idx=$index -> $name.jpg")
            }
        } finally {
            f.release()
        }
    }.onFailure { Log.w(TAG, "stall shot 落盘失败", it) }
}

internal suspend fun ScanEngine.parsePanel(step: JSONObject, ctx: ScanEngine.CellFrameContext? = null) {
    // 每格先置 false：任何提前 return 都不会留下上一格的陈旧命中值（宁可不动作）
    vars.panelMatched = false
    // ★ 全局弹框清场：加锁确认框可能**迟到 >60s**（服务端回包慢），会盖住整个界面把后续点击全吃掉。
    //   在这里每格查一次（像素判据，廉价），迟到的框最多存活一格。
    //   ⚠️ 必须加闸：该框**只可能在锁定写入之后出现**；无条件查会让"从没锁过东西"的流程
    //   （如纯扫描）在浅色画面上误判、平白点一下确认（干跑测试实测：每格多 1 击 = 21 击）。
    //   ★ #93：闸门从 `actTried` 换成 `lockWriteAttempted` —— 装配链现在也会置 `actTried`，
    //     若继续共用，装配流程就会开始探测加锁框（同一条误判路径）。
    if (vars.lockWriteAttempted) dismissLockConfirm(0L)
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
    // ★ A11：`var` —— 指纹闸门等到新面板后要**换帧**（旧帧只含上一格内容，见下方闸门分支）。
    var frame = ctx?.acquire { freshFrame() } ?: freshFrame()
    // ★★ 面板指纹 = **面板加载闸门**（GOODScanner `wait_until_panel_loaded` / `panel_snapshot`）★★
    //   语义澄清（2026-09-16 真机回归后定谳）：`panel_snapshot` 相同 **≠** "重复件"，
    //   而是"**新面板还没渲染出来**"（点了另一张卡、但抓到的还是上一张的面板）⇒ **必须等它换**，
    //   **绝不可跳过解析** —— 曾把"未换"当"重复"，实测导出 937 → **817**（−120 真件被丢）✗✗。
    //   （GOODScanner 的**去重**是另一套：`detect_grid_duplicates` 比**网格卡格**像素，点击前判重；
    //     面板 snapshot 只负责"等加载完 + 保证后续 OCR 读的是新面板"。）
    //   ⇒ 本闸门收益 = **修陈旧帧误读**（GT 里 B 类 1 件）；重复格仍由**内容键**去重（保留不变）。
    // ★★ 2026-09-17 对齐 GOODScanner（deepwiki 查证）★★
    //   依据（deepwiki 查证 GOODScanner，2026-09-17）：`PanelWaitMode::Fingerprint`（**圣遗物默认模式**）
    //   是**无条件**等待 —— 必须等到「面板 ≠ panel_snapshot」且稳定才跑 OCR；
    //   重复格（卡格指纹未变）**不跳过 OCR**，只把超时缩到 `PANEL_LOAD_FAST_TIMEOUT_MS = 100ms`；
    //   翻页后 `reset_panel_fingerprint()` 清空快照 ⇒ 新页首格必等。
    //   ⚠️ 触发本次对齐的"实测 118 个重复读事件"**是我的日志工具 bug 造成的假象**（已推翻 ✗，
    //      见 pageIdentities 处的注释）—— 但上述对齐**结论本身与权威实现一致，故保留**。
    // ★★ 2026-09-17（对齐 GOODScanner `GoodWeaponScanner`）：**武器面板用 FixedDelay，不用指纹闸门** ★★
    //   原因（deepwiki 查证）：同款武器的详情面板**完全相同** ⇒ 指纹检测不到"变化" ⇒
    //   指纹模式会一直等到超时（浪费且无意义）⇒ GOODScanner 对武器用 `PanelWaitMode::FixedDelay`
    //   且延时极短（`DEFAULT_WEAPON_PANEL_DELAY = 50ms`）✓
    val isWeaponPanel = panelKey == "weapon_backpack"
    val fpRects = if (PANEL_FP_GATE_ENABLED && !isWeaponPanel) {
        PanelFingerprint.regions(profile, panelKey)
    } else {
        emptyList()
    }
    if (isWeaponPanel) delay(WEAPON_PANEL_DELAY_MS)
    val fpWaitMs = if (gridCellChanged) PANEL_FP_WAIT_MS else PANEL_FP_DUP_WAIT_MS
    // 每格先按"未出现"处理；检测到指纹变化/新快照即置 true（见下）
    // 三态定案（#55）：**有**闸门 ⇒ 先按"还没出现"记（等不到变化就保持 false = 真停滞信号）；
    //   **没有**闸门（武器 FixedDelay）⇒ null = "没测"，既不谎报"出现过"也不谎报"没出现"。
    lastPanelAppeared = if (fpRects.isEmpty()) null else false
    if (fpRects.isNotEmpty()) {
        val fp0 = PanelFingerprint.capture(frame, fpRects)
        if (fp0 != null && PanelFingerprint.same(fp0, panelFpSnapshot)) {
            var waited = 0L
            while (waited < fpWaitMs) {
                delay(PANEL_FP_POLL_MS)
                waited += PANEL_FP_POLL_MS
                val f2 = runCatchingCancellable { freshFrame() }.getOrNull() ?: break
                val fp2 = PanelFingerprint.capture(f2, fpRects)
                if (fp2 != null && !PanelFingerprint.same(fp2, panelFpSnapshot)) {
                    Log.i(TAG, "面板指纹：等 ${waited}ms 后新面板就绪（陈旧帧已修复）⇒ 换用新帧解析")
                    panelFpSnapshot = fp2
                    lastPanelAppeared = true
                    // ★ A11：把**真正含新面板的帧**交给解析。此前 f2 只做指纹判定（capture 后
                    //   即 release），解析仍用 ctx 里的旧帧 —— 面板冻结 ≥2 格时（详情面板停在
                    //   上一格、闸门等待期间才渲染出新面板）解析出的是**上一格内容** ⇒
                    //   contentKey 撞 seenArtifactKeys 被去重静默吃掉（lastCellKey 在去重前
                    //   已赋值，该件无痕丢失）。帧所有权随 ctx 走：旧帧就地释放、f2 接管
                    //   （visit 结束由 ctx 统一 release；owned 场景同理换到局部 var）。
                    frame.release()
                    if (ctx != null) ctx.frame = f2
                    frame = f2
                    break
                } else {
                    f2.release()
                }
            }
            if (!PanelFingerprint.same(PanelFingerprint.capture(frame, fpRects), panelFpSnapshot)) {
                panelFpSnapshot = PanelFingerprint.capture(frame, fpRects)
            } else if (waited >= fpWaitMs) {
                Log.w(TAG, "面板指纹：${fpWaitMs}ms 内面板未变化（按原样解析，可能陈旧帧）")
                panelFpSnapshot = fp0
            }
        } else if (fp0 != null) {
            // 与上次快照不同 ⇒ 新面板确实出现过 ✓
            lastPanelAppeared = true
            panelFpSnapshot = fp0
        }
    }
    // ★ 调试落盘：放在**指纹闸门之后、OCR 之前** —— 此时 [lastPanelAppeared] 才是本格定案值，
    //   且存的帧就是下面 `parseArtifactPanel` 真正要 OCR 的那一帧。
    //   （2026-09-24 首版误放在取帧处，早于 5072 行的赋值 ⇒ 清单里 1003/1004 格恒报
    //    `appeared:false`，与 `格级` 日志的上千次 `appeared=true` 直接矛盾。埋点埋早了。）
    dumpPanelShot(frame, panelKey, fpWaitMs)
    try {
        if (panelKey == "weapon_backpack") {
            parseWeaponPanel(frame, ocr, step.optJSONObject("dict"))
        } else {
            parseArtifactPanel(frame, ocr, panelKey, step.optJSONObject("dict"))
        }
    } finally {
        if (owned) frame.release()
    }
    // ★ 2026-09-18：把 `match` 真正求值（此前是**死参数**，见 ScanVars.panelMatched 注释）
    vars.panelMatched = evaluatePanelMatch(step)
    // ★★ #93：**只有真的声明并求值了 `match` 才置 `matchHit`** ★★
    //   未声明 `match` 时 `evaluatePanelMatch` 返回 true 是"未设闸"的旧语义，**不是命中信号**。
    //   此前无条件写 ⇒ `auto_equip`（其 parsePanel 不声明 match，命中判据另有 `stopWhen
    //   expr="panelMatch(...)"`）的 `matchHit` 在**第一格**就被置成 true ⇒ foreach 状态映射
    //   落到 `matchHit -> "AlreadyCorrect"` ⇒ **每一项都报"已正确"，连真没找到的也一样**。
    if (step.optString("match").isNotEmpty() && vars.panelMatched) vars.matchHit = true
}

/**
 * 求值 `parsePanel.match`。目前只支持 `hardMatch(…容差X…)`（默认容差 0.1）。
 * 未声明 match ⇒ 返回 true（保持"未设闸"的旧语义，不影响只做读取的流程）。
 */
internal fun ScanEngine.evaluatePanelMatch(step: JSONObject): Boolean {
    val m = step.optString("match")
    if (m.isEmpty()) return true
    if (!m.contains("hardMatch")) {
        Log.w(TAG, "parsePanel.match '$m' 不支持 ⇒ 按未设闸处理")
        return true
    }
    val task = vars.currentTask
    if (task == null) {
        Log.d(TAG, "parsePanel.match: 无 currentTask ⇒ 不判（false）")
        return false
    }
    val tol = Regex("[0-9]+(\\.[0-9]+)?").findAll(m).lastOrNull()?.value?.toDoubleOrNull() ?: 0.1
    val why = StringBuilder()
    val hit = hardMatch(task, tol, why)
    Log.i(TAG, "parsePanel.match hardMatch(tol=$tol) ⇒ $hit | $why")
    return hit
}

internal suspend fun ScanEngine.parseWeaponPanel(frame: Mat, ocr: OcrGateway, dict: JSONObject? = null) {
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
        // ★ 2026-09-17 对齐 GOODScanner（`WeaponOcrRegions.equip`）：**读装备者**
        //   真机核对：`panels.weapon_backpack.equipped`=[2305,1163,2685,1217] 精确套住「珐露珊已装备」✓
        profile.rect("$base.equipped"),
    )
    val texts = ocr.readRois(frame, rects)
    fun at(i: Int): String? = texts.getOrNull(i)?.takeIf { it.isNotBlank() }
    val pieceName = at(0)?.let { StatParser.clean(it) }
    val levelText = at(1)
    val refineText = at(2)
    val equipText = at(3)
    // ★ 2026-09-17 武器面板原文 dump（诊断）：打印 4 槽 OCR 原文
    //   用途：验证"点击不同格却录到同一件"——看那几格的**原文是否逐字相同**（相同 ⇒ 读到同一张面板 = 陈旧帧 ✗）
    if (PANEL_RAW_DUMP) {
        Log.i(
            TAG,
            "武器面板原文: " + listOf(pieceName ?: "-", levelText ?: "-", refineText ?: "-", equipText ?: "-")
                .joinToString(" | "),
        )
    }
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
    // ★ 2026-09-17 对齐 GT：**`refine` 读不到时视作 1**
    //   3★ 及以下武器**没有精炼机制** ⇒ 面板无「精炼N阶」⇒ OCR 读空 ⇒ null ✗
    //   而 GT 对这类写 `refinement: 1` ✓ ⇒ 否则键 `(DebateClub,1,null)` ≠ GT `(DebateClub,1,1)`
    val refine = refineText?.let { StatParser.extractValue(it)?.toInt() } ?: 1
    val rarity = vars.rarity  // vote zone weapon.card.starStrip 已设
    if (rarity < 1 || rarity > 5) return
    if (key == null) return
    // ★ 连续重复件判据（在 dedupe 早退**之前**求值，否则永远数不到）：身份键与下面的 dedupe 完全同构
    if (noteDupAndMaybeStop("$key|L$level|R$refine",
            resultsWeapons.map { "${it.key}|L${it.level}|R${it.refine}" })) return
    // 去重：key + level + refine 唯一
    // ⚠️ 2026-09-27 订正：下面这段原先写"refine 读不到（null）时不参与去重 ⇒ 宁可多存"，
    //   但上面已经把 null 折成 1 ⇒ 该分支**永不触发**，是死规则。别照着它去"恢复" null 语义：
    //   实测 171 次武器面板读数里 `精炼1阶` 出现 **0 次**、空号 **43 次**，而 GT 的 R1 件正好 39 个，
    //   逐条对上 ⇒ **数字"1"是系统性读不出来的**，`?: 1` 不是在造假，是在救这 39 件。
    //   （2026-09-27 对账一度据此怀疑它伪造了 讨龙/黎明 的 R1，重刷 GT 后证明那两件是真的。）
    // ★★ 武器去重按「列表位置」而非内容 ★★
    //   GT 实证：同款同级的真·多把是常态（`DebateClub` 有 8 件）⇒ 内容键会把它们合并 ✗
    //   正确语义：重叠重复扫到 ⇒ **位置相同** ⇒ 跳过；两把同款 ⇒ **位置不同** ⇒ 都保留 ✓
    // ⚠️ 2026-09-17 **删除位置去重**：实测它把 `page1` **整页 21 格误挡**（`global` 漂移 ⇒ 判"已扫过"）
    //    ⇒ 21 件被整页丢弃 = "漏"的真因 ✗。
    //    GOODScanner **不做跨页去重** ✓（靠"滚整页 ⇒ 零重叠"）；我方重叠由**跨页卡格指纹**处理 ✓
    // ★ 装备者（GOODScanner `equip` 槽语义）：「珐露珊已装备」⇒ 取「已装备」前的内容 ⇒ 匹配角色词典得 key；
    //   未装备时该区为空（或文案不含「已装备」）⇒ 置空串（GT 中 117/209 件为空 ✓）
    val location = equipText
        ?.let { equippedOwnerOf(it) }
        ?.let { StatParser.clean(it) }
        ?.takeIf { it.isNotEmpty() }
        ?.let { raw ->
            // ★ 2026-09-17：**旅行者的名字是玩家自定义**，GT 的装备者栏写作不带元素的 `Traveler`
            //   （角色栏才写作 `TravelerCryo`），实测 `SkywardBlade` 我方读到"崽崽" ✗
            //   ⇒ 认不出来时**不臆断**，保留原文 + 打日志。#105 起昵称由用户在管理器页填。
            val key = characterKeyOfDisplay(raw, dict)
            if (key == null) Log.i(TAG, "weapon equip 未匹配角色词典: '$raw'（旅行者昵称等可在管理器页填）")
            key ?: raw
        }
        ?: ""
    // ★ 突破阶：面板不显示 ⇒ 由等级推导（表见 GoodWeapon.ascension 注释，GT 208/209 命中）
    val ascension = when {
        level <= 20 -> 0
        level <= 40 -> 1
        level <= 50 -> 2
        level <= 60 -> 3
        level <= 70 -> 4
        level <= 80 -> 5
        else -> 6
    }
    // ★ 2026-09-17 诊断埋点（武器版）：与圣遗物同格式 ⇒ 离线可看"**每格读到了哪把**"序列，
    //   用于验证"翻页/点击是否造成重复或漏点"（此前武器路径不写 identity ⇒ 格级日志 `item` 全是 `-` ✗）
    lastCellIdentity = "$key/$level/$rarity#R$refine" + (if (location.isNotEmpty()) "@$location" else "")
    if (curCellIdx >= 0) curPageIds[curCellIdx] = lastCellIdentity ?: ""
    // ★ 跨页重叠判定（identity 版）：本格若与**上页同列**的 row1/row2 身份相同 ⇒ 是同一张卡 ⇒ 丢弃
    //   （实测像素指纹跨页零命中 ✗；identity 为 OCR 内容、稳定 ✓；**只比同列** ⇒ 不伤同款多把）
    //   前进量可为 0~2 行（滑不动时整页 3 行全重叠）；前进 2 行时新页 row1/row2 对应上页 row3/row4
    //   —— 上页表里没有那两格 ⇒ 取到 null ⇒ 不误判真·多把 ✓
    // ⚠️ 2026-09-25 #60 第一次试修**已回退**：把窗口放宽成"本格查上页 r..r+2 三行" ⇒ 2560 实测
    //   **负收益**（导出 155→157 件，且丢掉 TravelersHandySword / BloodtaintedGreatsword /
    //   SkyriderSword / FilletBlade / FerrousShadow 5 件真 3★，页级冻结放弃 0→17 格）。
    //   成因：identity **不唯一**（ThrillingTalesOfDragonSlayers 光 L1R5 就有 2 把），窗口一放宽，
    //   "真·同款多把"就会被当成跨页重叠丢掉 ⇒ 判据本身**维持只比 row1/row2**。
    // ★2026-09-25 #60 真正的成因 = **上页表下标错位**：原先喂给本判据的是一张紧凑表
    //   （只存 row1/row2 ⇒ 槽位 0/1），而判据按绝对下标 `(traverseRows-2)*cols+c` /
    //   `(traverseRows-1)*cols+c` 读 ⇒ 第一个取到的其实是 row2、第二个**恒越界 null**
    //   ⇒ 事实上只比 row2 ⇒ "前进 1 行"（新 row0 ← 上页 row1）无人管 ⇒ 尾区假重复。
    //   实测（同码对照轮）：判据命中的 **19** 条全是 `新行 ← 上页 row2`（page3/7 各 6 条 r0 +
    //   page9 6 条 r1 + page5 1 条 r2）；漏掉的 6 件**全是** `page9 row0 ← page8 row1`
    //   （TwinNephrite/EmeraldOrb/OtherworldlyStory/BlackTassel/ThrillingTales×2）。
    //   ⇒ 本轮只换表（紧凑 → [prevAllCellIds] 绝对表），判据逻辑一字不动。
    // ⚠️ 日志此前把行号写死成字面量 `r0` ⇒ "丢弃全落在 row0"是**日志假象**，别拿它当证据。
    //   下标算术已抽成纯函数 [crossPageOverlap] 并单测（这条判据错过一次，代价是一整轮误修）。
    if (crossPageOverlap(prevAllCellIds, curCols, curTraverseRows, curCellRow, curCellCol, lastCellIdentity)) {
        Log.i(TAG, "跨页重复(identity) 丢弃: page=$curPageNo r${curCellRow}c$curCellCol $lastCellIdentity")
        // ★★ #73（2026-09-27 实测定位）：这条 return **必须先把本格登记**，否则它绕过了
        //   下面那段 `weaponCellEmitted.add`，被丢的格在表里是"没来过"的状态 ⇒
        //   同一页的**定点重访**再点这一格时 cellTag 又是新的 ⇒ 又入库一次。
        //   实证（runlog 20260927_3200_weapon_dictfix.log，对账多 1 件 = TheStringless|L1|R5）：
        //     17:22:25  正常扫 page5 r0c1 绝弦 → 跨页重复丢弃（但没登记）
        //     17:22:39  相邻重复指纹 idx=[1,15] ⇒ 定点重访
        //     17:22:40  重访 idx=1（= page5 r0c1）再读到绝弦 → 顺利通过两道判据 → emit 第 120 件 ✗
        //   登记后重访会被"同格重解析"挡下（那条 add 返回 false），行为与 #58② 已声明契约一致：
        //   **同格同身份只入库一次**。身份不同（真·另一张卡）tag 不同 ⇒ 不受影响。
        if (curCellRow >= 0 && curCellCol >= 0) {
            weaponCellEmitted.add("$curPageNo:$curCellRow:$curCellCol:$lastCellIdentity")
        }
        return
    }
    // ★ 陈旧帧保护（见 [weaponSameRun] 注释）：同一件连续超阈值 ⇒ 判为陈旧帧，丢弃并计数
    if (lastCellIdentity == lastWeaponIdentity) {
        weaponSameRun++
        if (weaponSameRun > WEAPON_SAME_IDENTITY_CAP) {
            weaponStaleDropped++
            if (weaponStaleDropped <= 5 || weaponStaleDropped % 20 == 0) {
                Log.w(TAG, "武器陈旧帧丢弃 #$weaponStaleDropped（连读 $weaponSameRun 次）：$lastCellIdentity")
            }
            return
        }
    } else {
        lastWeaponIdentity = lastCellIdentity
        weaponSameRun = 1
    }
    // ★ 同格同身份**只入库一次**（#58②，2026-09-25 武器全量对账定案）
    //   实测：183 次 parse vs 180 格 ⇒ 3 次"同一格被解析两遍"（吞击重发成功后再走一遍收敛帧、
    //   定点重访救回同一格），两遍读到**同一身份** ⇒ 旧代码只挡"跨页重叠"（比 prevAllCellIds 的
    //   row1/row2）与"连读 >CAP 次同款"，**同页同格重解析**这条路径没人挡 ⇒ 一把武器存两件。
    //   GT 对账实证：TheStringless(L1,R5) 2→3、ThrillingTales(L1,R5) 2→3，
    //   且 155(屏幕计数器) = 156(导出) − 2(本判据要挡的) + 1(SilverLight 读残漏的) 恰好闭合。
    //   键取「页 + 行 + 列 + 身份」而非只取格：一格=一张卡=一件武器，但**身份不同**说明
    //   其中一次是陈旧帧（该让重访的那次赢，不能因为"这格来过了"就把真件丢掉）。
    //   只在格坐标有效时介入（snap/rosterFind 等非 pagedGrid 路径 curCellRow=-1 ⇒ 不判）。
    if (curCellRow >= 0 && curCellCol >= 0) {
        val cellTag = "$curPageNo:$curCellRow:$curCellCol:$lastCellIdentity"
        if (!weaponCellEmitted.add(cellTag)) {
            Log.i(TAG, "武器同格重解析丢弃: page=$curPageNo r$curCellRow c$curCellCol $lastCellIdentity")
            return
        }
    }
    // ★ #104 观测（**不丢件**）：同一身份在"非连号"处再次入库 ⇒ 极可能是翻页重叠区把同一张卡
    //   送到了另一个格地址（`weaponCellEmitted` 的键含页/行/列，按构造抓不住这种位移）。
    val ident = "$key|L$level|R$refine"
    val runs = (weaponIdentityRuns[ident] ?: 0) + 1
    weaponIdentityRuns[ident] = runs
    if (runs > 1 && ident != lastEmittedWeaponIdentity) {
        weaponOverlapRepeats++
        Log.w(
            TAG,
            "武器身份非连号再现 #$weaponOverlapRepeats（疑似重叠区重读，已照常入库）：" +
                "$ident 第 $runs 次",
        )
    }
    lastEmittedWeaponIdentity = ident
    val weapon = GoodWeapon(
        key = key,
        level = level,
        rarity = rarity,
        refine = refine,
        lock = VoteJudges.weaponLock(frame, profile).matched,
        location = location,
        ascension = ascension,
    )
    resultsWeapons.add(weapon)
    listener.onProgress("weapon", vars.snapshot() + ("piece" to pieceName) + ("idx" to resultsWeapons.size))
}

/**
 * @param panelKey 面板键（"artifact_backpack" | "artifact_manage"）。
 * ⚠️ 两面板**不同构**（坐标/字段/星带/set_name 可读性皆异），故路径必须由此参数化——
 * 旧码硬编码 panels.artifact_backpack.*，对管理面板会静默取错 ROI。
 */
internal suspend fun ScanEngine.parseArtifactPanel(
    frame: Mat,
    ocr: OcrGateway,
    panelKey: String = "artifact_backpack",
    /** flow 的 `dict` 声明（字段→词典 key / fuzzy / subStats 档位表），全 JSON 驱动。 */
    dict: JSONObject? = null,
) {
    val base = "panels.$panelKey"
    // ══ 祝圣之霜（Sanctifying Elixir）内容下移：**只移 y 轴在横幅以下的那几项** ══════════
    // 【用户 2026-09-16 定稿 + GOODScanner ELIXIR_SHIFT 佐证】
    //   横幅画在面板上部，把**它以下**的内容整体下移 zhushengShiftPx（3200 帧 = 63px）。
    //   ✅ 受影响（位移）：**等级、4 条副词条**（外加同区域的 锁 / 收藏(星标) 两个投票判据，
    //      见 VoteJudges.panelLock / panelAstral 的 yShift 参数）
    //   ❌ **不受影响（绝不可平移）**：**单件名 name、部位 slot、主词条名 mainName、
    //      主词条值 mainValue、管理界面的 set_name** —— 这些槽位在横幅以上/之外，位置固定。
    //   ⚠️ 单件名槽尤其关键：我们**不读 set_name**，setKey 全靠「单件名 → 套装」反推
    //      （mappings.artifactPieces，305 件，由游戏 Reliquary 表生成；用户 2026-09-01 定稿）
    //      ⇒ 一旦把 name 槽也平移，
    //      祝圣件的单件名会读成别的行 ⇒ 反推链直接断掉。**不要"顺手"给 name/slot/main 加 shift。**
    val yShift = if (vars.crafted) profile.zhushengShiftPx else 0

    // 一次批量读全部文本槽（name/slot/mainName/mainValue/level/subStats×4）——单帧单调用，
    // 返回与 rects 一一对应（blank 保留占位，按索引取回）
    val nameRect = profile.rect("$base.name")
    val slotRect = profile.rect("$base.slot")
    val mainNameRect = profile.rect("$base.mainName")
    val mainValueRect = profile.rect("$base.mainValue")
    val levelRect = profile.rect("$base.level").shiftedBy(yShift)
    // ★ P3（2026-09-30）：`rawObject(base)!!` 裸断言改显式校验 —— null 时抛带 key 的明确错误，
    //   不再是难定位的 KotlinNullPointerException。
    val panelObj = profile.rawObject(base)
        ?: throw IllegalStateException("parsePanel: profile 缺少 '$base' 节点（面板未标定或 panelKey 拼写错误）")
    val subStatsArr = panelObj.getJSONArray("subStats")
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

    // ★★ 面板原文 dump（诊断用，见 PANEL_RAW_DUMP）★★
    //   打印该面板全部文本槽：name | slot | mainName | mainValue | level | subStats×4 (+setName)。
    if (PANEL_RAW_DUMP) {
        Log.i(TAG, "面板原文: " + lines.joinToString(" | ") { it })
    }

    // 等级（祝圣面板 yShift）
    val levelText = lines.getOrNull(4)?.takeIf { it.isNotBlank() }
    vars.level = levelText?.let { StatParser.extractValue(it)?.toInt() } ?: 0

    // 星带逐格（⚠️整带列投影 5★ 会合并，必须逐格；祝圣 yShift 不作用于星带——profiles zhusheng.fields 仅 level/subStats/lock/astral）
    val starCount = countStars(frame, panelKey)
    // 交叉校验：banner 色分类 vs 星数——以星数为准
    val rarity = if (starCount > 0) starCount else vars.rarity
    vars.rarity = rarity

    // 副词条（祝圣 yShift；**连续块**读取）
    // ★★ 2026-09-16 **ground truth 定标修正**（旧「5★恒4条 / 4★最高3条」假设被推翻：
    //   design-docs/good-diff-20260916.md，对比 dsl/verify/_audit/good_diff_20260916.json）：
    //   · GT 实测 **5★+0 有 67%（158/235）只有 3 条** ⇒ 旧规则「5★恒 4」会把紧随词条块的
    //     **套装名/套装效果行**当成第 4 条留下 ⇒ **幻影词条**（真机 5★+0 读 4 条的 205 件 vs GT 77 件）。
    //     布局证据：verify/annotated/artifact_backpack.png —— 词条行位 794/858/922/986（间距 64），
    //     y≈1090 起是「套装名: 2件套：…」⇒ 只有 3 条时第 4 行 ROI 正好压在这一行上。
    //   · GT 实测 **4★+16 24/24 全是 4 条** ⇒ 旧规则「4★ 最高 3」把真第 4 条砍掉 ⇒ **少读**（24/24）。
    //   ⇒ 改为**不按稀有度猜条数**：读**连续块**，遇"非空但解析不出 stat"的行即块结束，全局上限 4。
    var substats = StatParser.parseBlock(lines.drop(5), names)

    // 仅保留止扫判定（3★/2★ 不解析）
    // ★★ 2026-09-16 修（新 GT 定标：账号 941 件全 4★/5★、无任何 3★，却有 13 件
    //   TheExile/Instructor「4★+16」被止扫吃掉 —— 散落单格静默丢件）：
    //   `rarity = if (starCount > 0) starCount else vars.rarity` ⇒ **starCount == 0 时用的是 banner 值**。
    //   而**星带是权威判据**：归档实拍 3★/4★ 面板逐格金像素 = 891/903/894/0/0（3 颗）与
    //   969/975/992/1000/0（4 颗），余量近 10 倍 ⇒ **starCount==0 只可能是"这一帧不是有效面板"**
    //   （面板没开/帧陈旧），此时 banner 的低星读数**不可信**，不得作为止扫依据。
    //   处理：不 emit（本格本就没读到）、**不发布低星 rarity**（否则 flow 的 stopWhen 误触发、
    //   把后面的件全切掉）、并让 lastCellKey 保持空 ⇒ 交「空读格回读」重取。
    if (rarity < STOP_MARKER_RARITY && starCount == 0) {
        Log.w(
            TAG,
            "parsePanel: starCount=0（无效面板帧）而 banner 判 rarity=$rarity " +
                "⇒ 不作止扫依据、不发布低星；待空读格回读（piece=${pieceName ?: "?"} level=${vars.level}）",
        )
        vars.rarity = STOP_MARKER_RARITY
        lastCellKey = null
        return
    }
    if (rarity < STOP_MARKER_RARITY) {
        // ★ 2026-09-19 用户定稿：**3★ 起纳入导出**（圣遗物全量对账 GT：3★ 55 件、无 2★）。
        //   判据用**星带**（权威）：starCount>=3 ⇒ 正常 emit（banner 色域对 3★ 的误降由星带纠正）；
        //   仅 starCount 1..2（真 1★/2★，GT 没有，纯保险）才维持止扫不解析。
        if (starCount >= 3) {
            Log.i(
                TAG,
                "parsePanel 3★ 纳入导出：piece=${pieceName ?: "?"} slot=${slotKey ?: "?"} " +
                    "main=${mainStat?.key ?: "?"} 词条=${substats.size}条 starCount=$starCount",
            )
        } else {
            Log.w(
                TAG,
                "parsePanel 止扫命中（1★/2★ 不解析）：rarity=$rarity starCount=$starCount " +
                    "level=${vars.level} piece=${pieceName ?: "?"} slot=${slotKey ?: "?"}",
            )
            return
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
    // ★ A1'（2026-09-16）：把单件名规范化为**词典标准名**，供去重键使用（见内容键注释）。
    //   只影响"入键用的名字"，不改变 setKey 反推本身；词典未命中/未注入词典时回落原始名。
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

    // ★ 2026-09-16（按 GOODScanner roll_solver 补齐）：解「每条强化次数 + 首档值 + 整件 totalRolls」。
    //   不可解 ⇒ 原样导出（宁缺不错）；解出后 initialValue 只在唯一时写入。
    // ★ 2026-09-16：带「(待激活)」标记一起送进 solver —— lv0 时 init 由标记直接定
    //   （GOODScanner result_init = num_active − count(inactive)），不再靠猜；且待激活条
    //   **不计入 totalRolls**、导出时单列 `unactivatedSubstats`（GT 894/894 件都有该字段）。
    val rollIn = substats.map { RollSolver.In(it.key, it.value, it.inactive) }
    val solved = RollSolver.solve(rarity, vars.level, rollIn)
    if (solved == null && substats.isNotEmpty()) RollSolver.logMiss(rarity, vars.level, rollIn)
    // ★ 2026-09-16（GT 定案）：`+0` 件末尾那行「待激活」词条 **要保留** —— Irminsul/GT 把它
    //   **单列**到 `unactivatedSubstats`（894/894 件都有该字段），不是丢弃。
    //   拆分优先级：① OCR 显式「(待激活)」标记（最准）② 无标记但 solver 推断出末尾一条是待激活
    //   （`activeCount` < 总行数）⇒ 按"末行是待激活"切尾。
    val unactFromFlag = substats.count { it.inactive }
    val inferredTail = if (unactFromFlag == 0 && solved != null && solved.activeCount in 1 until substats.size) {
        Log.d(
            TAG,
            "待激活（solver 推断，无 OCR 标记）: 末 ${substats.size - solved.activeCount} 行" +
                "（rarity=$rarity level=${vars.level}）",
        )
        substats.size - solved.activeCount
    } else {
        0
    }
    val cut = unactFromFlag + inferredTail
    val activSubs: List<GoodSubStat>
    val unactSubs: List<GoodSubStat>
    if (solved != null) {
        activSubs = solved.substats.take(solved.substats.size - cut)
            .map { GoodSubStat(it.key, it.value, it.initialValue, it.rollCount) }
        unactSubs = solved.substats.drop(solved.substats.size - cut)
            .map { GoodSubStat(it.key, it.value, it.initialValue, it.rollCount) }
    } else {
        val n = substats.size - inferredTail
        activSubs = substats.take(n).map { GoodSubStat(it.key, it.value) }
        unactSubs = substats.drop(n).map { GoodSubStat(it.key, it.value) }
    }
    if (cut > 0) {
        Log.i(
            TAG,
            "unactivatedSubstats: ${unactSubs.size} 条（OCR 标记=$unactFromFlag 推断=$inferredTail）" +
                " → ${unactSubs.joinToString { "${it.key}=${it.value}" }}",
        )
    }
    // ★ 2026-09-19 修：**装备者（location）此前从未写入** —— `GoodArtifact` 的 `location` 只是
    //   默认空串，构造处**根本没传**它 ⇒ 导出里 location 恒空（GT 1045 件里 262 件有值，我方 0/921 ✗）。
    //   与武器路径同口径：面板底部 `panels.<panelKey>.equipped` 读「XX已装备」⇒ 取「已装备」前的内容，
    //   再按角色词典归一成 GOOD key（旅行者昵称等匹配不到就保留原文，与武器路径一致）。
    val equippedText = runCatchingCancellable {
        val r = profile.rect("panels.$panelKey.equipped")
        ocr.readLines(frame, listOf(r)).joinToString(" ")
    }.getOrElse { "" }
    val location = equippedOwnerOf(equippedText)
        ?.let { StatParser.clean(it) }
        ?.takeIf { it.isNotEmpty() }
        ?.let { raw ->
            val key = characterKeyOfDisplay(raw, dict)
            if (key == null) Log.i(TAG, "artifact equip 未匹配角色词典: '$raw'（旅行者昵称等可在管理器页填）")
            key ?: raw
        }
        ?: ""
    if (location.isNotEmpty()) Log.d(TAG, "artifact location: '$location'（原文 '$equippedText'）")
    val artifact = GoodArtifact(
        setKey = setKey,
        slotKey = slotKey,
        // ★ A18：level+10 候选解出时 OCR 等级是误读值（如 11 丢位读成 1），导出必须用
        //   solver 解出等级，否则 level 与 totalRolls（= init + solvedLevel/4）自相矛盾。
        //   不可解时回落 OCR 原值（totalRolls 也为 null，口径一致）。
        level = solved?.solvedLevel ?: vars.level,
        rarity = rarity,
        mainStatKey = mainStat?.key,
        mainStatValue = mainStat?.value ?: 0.0,
        substats = activSubs,
        unactivatedSubstats = unactSubs,
        lock = vars.locked == true,
        favorited = vars.favorited,
        location = location,
        pieceName = pieceName ?: "", // 仅作 QA/日志，**不入去重键**（见内容键注释）
        totalRolls = solved?.totalRolls,
        elixerCrafted = vars.crafted,
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
            // ★★ 2026-09-16 用户定稿：**名字一律不进去重键** ★★
            //   理由（实测支撑）：① 名字只是"套装的函数"（`artifactPieces` 是 piece→(set,slot) 的函数），
            //   对键**零增量区分力**；② **同名圣遗物极多**（同套同部位同强化的复数件名字完全相同 ⇒
            //   靠名字去重会把它们并掉 ✗ 实测 A1'/A1'' 都在这里翻车：一次 −21 件、一次撞名）；
            //   ③ 名字是**原始 OCR 文本**，同一张卡两次读可能差一个字 ⇒ 键不同 ⇒ 该去重的没去掉。
            //   ⇒ 去重身份只由**内容**决定：set/slot/level/main(+value)/lock/fav/substats。
            //      （"两件副词条完全一样"在游戏里基本不可能 ⇒ 这正是最可靠的判重依据。）
            level.toString(),
            mainStatKey,
            mainStatValue.toString(),
            "L$lock",
            "F$favorited",
            // 内容身份含**全部**读到的行（含待激活那条：它也是该件的真实数据，GT 单列导出）
        ).joinToString("|") + "|" + substats.joinToString("|") { "${it.key}:${it.value}" }
    }
    lastCellKey = contentKey
    // ★ 2026-09-16（新 GT 对账定标）：**垃圾条拦截** —— 实测导出 22 件是 setKey/词条全空的空壳
    //   （只读到等级/稀有度、其余没解析出来）。它们既污染导出（"错"），又让该格不再被回读（"漏"）
    //   ⇒ 不入库 + lastCellKey 置空，交「空读格回读」重读。
    // ⚠️ 判据升级（2026-09-16 二轮真机实测）：**词条为空即视为读失败**，不只是"全空"。
    //   实测有大量"半读条"——setKey/等级/主词条都读到了、`substats=[]`（stale/半幅帧）。
    //   GT 复核（2026-09-24 再验，967 件）：**每件至少 1 条词条，0 条不存在** ⇒ `substats` 空必是失败。
    //   这类条同时造成"错"（多出）与"漏"（挤掉真件），必须丢弃 + 回读。
    // ★★ 2026-09-24：本判据必须**排在去重/身份记录之前** ★★
    //   空壳是**读失败**，不是"一件读到了的重复件"。放在去重之后时：同款空壳第二次读会被
    //   `seenArtifactKeys.add` 判重直接 return（此时 lastCellKey 已非空）⇒ 该格被回读当作
    //   "救活"而不再重点，而这里设计的"置空交回读"永远轮不到；空壳键还永久占坑 seen 集合。
    //   同理不能写身份表：`prevAllCellIds` 那格留空，下页跨页跳过的安全阀才会拒绝跳过它。
    if (substats.isEmpty()) {
        Log.w(
            TAG,
            "丢弃读失败条目（词条 0 条）；setKey=${setKey ?: "null"} piece=${pieceName ?: "?"} " +
                "level=${vars.level} rarity=$rarity ⇒ 交空读格回读重取",
        )
        lastCellKey = null
        return
    }
    // ★★ 2026-09-24：**词典缺口 ≠ 读失败**（两者此前混在一条判据里）★★
    //   真机全量对账实证：尾部 9 件 3★（冒险家 / 祭火礼冠）面板 OCR **完全正确**
    //   （`slot=circlet main=atk_ 词条=2条`），只是单件名→套装反查不到 ⇒ setKey=null ⇒
    //   被上面那条判据当"读失败"丢弃 ⇒ **词典每少一套就静默少扫一批件**，而日志只说读失败。
    //   GOOD 的 artifactSets 只列 4★/5★（56 套）；词典侧已补两层：30 件单件名（gen_mappings.py
    //   的 build_pieces，2026-09-24）与低星 9 套的套装名（build_low_star_sets，2026-10-02），
    //   均来自游戏 Reliquary 全表而非 GOOD ⇒ 这里保留该件并计数，让残余缺口看得见。
    if (setKey.isNullOrEmpty()) {
        unknownSetPieces++
        Log.w(
            TAG,
            "套装词典未命中：piece=${pieceName ?: "?"} rarity=$rarity 词条 ${substats.size} 条 " +
                "⇒ **照常入库**（setKey 留空），累计未命中 $unknownSetPieces 件" +
                "（词典是生成的：先跑 dsl/scripts/gen_mappings.py --refresh 重生成并同步 mappings.json；" +
                "仍缺 ⇒ 游戏 Reliquary 表或 data_cache 落后于版本）",
        )
    }
    // ★★ 件身份（用户方案 2026-09-17）：**必须在去重判断之前记录** ★★
    //   否则被去重跳过的格没有身份 ⇒ "同页内重复 = 该格点偏"的判据会漏掉**全部**重复格
    //   （实测：只在入库处记录 ⇒ 1554 格里仅 901 有身份、653 无身份 ✗，判据完全失效）。
    //   格式 `set/slot/lvl/main#词条数值升序` ⇒ 可与 GT 直接对齐。
    lastCellIdentity = artifact.run {
        "$setKey/$slotKey/$level/$mainStatKey#" +
            substats.map { it.value }.sorted().joinToString(",")
    }
    // ★★ 2026-09-20 修 C' 关键缺陷 ★★ 圣遗物路径**此前从不写 `curPageIds`**（只有武器路径写）
    //   ⇒ 页尾存下的 `prevAllCellIds` 21 格**全空** ⇒ 身份锚定没有对照表 ⇒ C' 全程 0 命中。
    //   真机诊断日志实证：`身份锚定诊断: … | 上页表=[0: 1: 2: … 20:]`（全空）。
    //   写入点与武器路径一致：`curCellIdx >= 0` 才写（越界/未进入格不污染表）。
    if (curCellIdx >= 0) curPageIds[curCellIdx] = lastCellIdentity ?: ""
    // ★ 连续重复件判据：身份键用**与 dedupe 相同的 contentKey**（件名+等级+词条），
    //   不能只用件名 —— 同件名的不同圣遗物会被秒判重复。
    noteDupAndMaybeStop(contentKey, seenArtifactKeys)
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
internal fun ScanEngine.readManageIcons(frame: Mat) {
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

/**
 * 卡格指纹矩形：卡片中心一小块（基准 ±36 ⇒ 72×72，随分辨率缩放）——**只要够区分"同一张卡"即可**。
 *
 * @param prof **必须**是本页的相位视图（`pageProfile`）：本矩形与点击坐标同源，
 *               传原始 profile 会偏离卡心一个 φ（φ 可达 ±126 帧 px，而窗半宽仅 36）
 *               ⇒ 采到卡缝 ⇒ 跨页指纹恒不等（跳过静默失效）。
 */
internal fun ScanEngine.cellFingerprintRect(
    gridKey: String,
    prof: ScreenProfile,
    col: Int,
    row: Int,
): FrameRect? = runCatchingCancellable {
    val g = prof.gridGeometryFor(gridKey) ?: return null
    if (col >= g.colXs.size || row >= g.rowYs.size) return null
    val cx = g.colXs[col] + g.cardW / 2
    val cy = g.rowYs[row] + g.clickDy
    // gridGeometry 是**基准**坐标，而 PanelFingerprint.capture 按帧像素取 Mat ⇒ 必须过 scaleRect
    prof.scaleRect(cx - 36, cy - 36, cx + 36, cy + 36)
}.getOrNull()

/** 星带逐格采样：格内金像素>100 → 该星点亮（profiles starBand.judge 固化阈值）。星带不随祝圣 yShift 移动。 */
internal fun ScanEngine.countStars(frame: Mat, panelKey: String = "artifact_backpack"): Int {
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
