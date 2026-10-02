package com.bettergi.pocket.scan

import android.os.SystemClock
import android.util.Log
import com.bettergi.pocket.capture.FrameSource
import com.bettergi.pocket.capture.FrameTimeoutException
import com.bettergi.pocket.log.RecognitionLog
import com.bettergi.pocket.notice.NoticeCenter
import com.bettergi.pocket.core.IntRect
import com.bettergi.pocket.recognition.name.GoodNames
import com.bettergi.pocket.recognition.name.NameMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs

/**
 * ★ Stage 3.3 拆分地图（2026-10-01）：ScanEngine 曾 8592 行，现按域拆出（均为**扩展函数**，
 * 函数体一字未改、调用点不变）：
 * - `ArtifactDomainPanels.kt`  —— 圣遗物/武器面板解析（parsePanel/parseArtifactPanel/parseWeaponPanel + 取证出口）
 * - `CharacterDomainPanels.kt` —— 角色扫描域（rosterFind/parseCharacterPanel/readTalent/charFilter 等）
 * - `ScanNavigationDomain.kt`  —— 导航族（enterScreen/assertScreen/returnToHome/navigate/dialog/exitStep 等）
 * - `ScanJudgmentDomain.kt`    —— 判读族（vote/dualStateButton/verify / lock 系列 / setFilter / plan 系列 / 就绪签名 等）
 * - `ScanGridDomain.kt`        —— 网格族（pagedGrid/swipeGridToTop/awaitGridStable/freshFrame）
 * - `ScanControlDomain.kt`     —— 控制族（foreach / emit 系列 / stopWhen/visit 执行/词典吸附 等）
 * 本文件保留：类与构造参数、状态字段、`run()` 编排、`executeStep` 分发、companion 常量。
 *
 * ★ A17（2026-09-30）：取消透明的 `runCatching`。
 *
 * `kotlinx.coroutines.CancellationException` 是 `Exception` 的子类 —— 包住 suspend 调用
 * （freshFrame/OCR/delay/actions）的 `runCatching` 会把协程取消当成普通失败吞掉，
 * 让已取消的扫描继续跑完后续步骤。此版本在成功/失败两分支之外**先行放行取消**：
 * 非挂起 lambda 里永不抛 CancellationException ⇒ 对既有非挂起调用点行为逐位一致。
 */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
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
    internal val frameSource: FrameSource,
    internal val actions: ActionGateway,
    internal val ocr: OcrGateway?,
    /**
     * 单一名称词典（角色/武器/套装/圣遗物单件/词条/部位）。
     * 所有名称反查统一走 [NameMatcher]，本引擎不再持有各写一份模糊逻辑的词典类。
     */
    internal val names: GoodNames? = null,
    /**
     * #105：玩家自定义昵称 → GOOD key。空表 = 完全按词典/规则走（单测与离线跑就是这个默认）。
     */
    internal val nameOverrides: NameOverrides = NameOverrides.EMPTY,
    internal val listener: ScanListener,
    internal val dedupe: Boolean = true,
    /**
     * #83：**前台闸门**探针 —— 返回 false = 当前前台不是原神 ⇒ 整轮干净收尾。
     * 默认 null = 不判（单测/离线跑不接无障碍）。
     *
     * 为什么要在引擎侧再判一次（注入侧已逐段判）：注入侧的闸门只能"拦住这一次动作"，
     * 拦下之后引擎并不知道发生了什么，会继续按同一个页面重试/回读 ⇒ 白烧格与时间；
     * 而用户口径是**游戏切后台本来就会断线重登** ⇒ 这一轮已经没有继续的意义，应当立刻停轮报出来。
     */
    internal val foregroundOk: (() -> Boolean?)? = null,
    /** 翻 N 页早停（调试翻页准确性用；默认不限制）。 */
    internal val maxPages: Int = Int.MAX_VALUE,
    /** 单测注入真实时钟用：JVM 里 SystemClock 被 returnDefaultValues 恒返回 0（会挂死轮询）。 */
    internal val clock: () -> Long = { SystemClock.elapsedRealtime() },
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
    /**
     * §12.1 **翻页起点**选择：true=几何推导卡缝起点（`advanceStart`），false=profile 字面
     * `advance.from`。**只影响起点**——距离规划 / 残差记账 / 触摸增益补偿对两条路一律生效
     * （2026-09-24 前这里还顺带关掉了整套控制律，见 pagedGrid 主滑处的说明）。
     */
    /** ⚠️ 2026-09-17 默认 false（几何起点在 BlueStacks 上实测致滚动截断，见 TriggerForegroundService）。 */
    internal val useGeometryAdvance: Boolean = false,
    /**
     * 外部注入任务计划（P4 规则层）。`foreach over=$plan` 消费并逐项写入 [ScanVars.currentTask]，
     * `ifMatch` 以 currentTask 非空为闸 → artifact_lock / auto_equip 依赖此注入，未注入则 ifMatch 段整段跳过。
     */
    private val plan: List<JSONObject>? = null,
    /** 流程名（ScriptRunner 传入），仅用于 §13 识别日志的 [RecognitionLog.Tag] 归属。 */
    private val flowName: String = "artifact_scan",
    /**
     * 「上锁确认框不再弹」是否已经学到（账户级性质，跨轮持久化，见 [ScriptRunner]）。
     * false = 每轮仍为它付 1.5s/轮的等待。
     */
    private val lockConfirmAbsentLearned: Boolean = false,
    /** 学到"不再弹"时回写持久层。默认空实现 ⇒ 不破坏既有调用点与单测。 */
    internal val onLockConfirmAbsentLearned: () -> Unit = {},
) {
    val vars = ScanVars().apply { this.plan = this@ScanEngine.plan }

    /** §13：流程 → 日志来源标签。 */
    internal val logTag: RecognitionLog.Tag
        get() = when {
            flowName.contains("lock") -> RecognitionLog.Tag.LOCK
            flowName.contains("equip") -> RecognitionLog.Tag.EQUIP
            flowName.contains("char") -> RecognitionLog.Tag.CHAR
            else -> RecognitionLog.Tag.SCAN
        }

    /** 扫描产物（parsePanel emit 收集，endConditions 后由 ScriptRunner 导出）。 */
    val results = ArrayList<GoodArtifact>()

    /** 入库内容指纹（去重键，见 parseArtifactPanel）。 */
    internal val seenArtifactKeys = HashSet<String>()

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
        // ★ 2026-09-24：观察类计数在此复位。它们的 KDoc 一直写着"随 run 重置"，但此前**没有任何复位点**
        //   ⇒ 同进程连跑两轮时第二轮的 `scan finished` 摘要会把上一轮的数一起算进去。
        dupRevisits = 0; dupRevisitRecovered = 0; pageFreezeAbandoned = 0; swallowedClickRetries = 0
        clickGiveups = 0; weaponStaleDropped = 0
        weaponOverlapRepeats = 0; weaponIdentityRuns.clear(); lastEmittedWeaponIdentity = null
        unknownSetPieces = 0; charUnresolved = 0
        panelShotSeq = 0; panelShotDropped = 0; panelShotWriteFailures = 0
        panelShotSpaceCheckedAt = 0; panelShotSpaceOk = true
        PerfProbe.reset() // 只读探针：每轮扫描独立统计
        Log.i(TAG, "timing: ${TimingOverrides.summary()}")
        // §15 流程前置归位：GOODScanner `GenshinGameController::return_to_main_ui` 同思想
        // （循环点返回直至主界面），各流程启动前统一回到 game_home，避免残留弹窗/界面错位。
        // 默认 false：仅 ScriptRunner 真机启动路径显式开启（干跑单测不注入 → 不产生归位点击/取帧）
        if (flowJson.optBoolean("returnHome", false)) {
            val tHome = System.nanoTime()
            try {
                returnToHome()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                // 归位是尽力而为的前置，失败不应中断流程（首步 enterScreen 仍会校验）
                Log.w(TAG, "returnToHome 异常，继续流程：${e.message}")
            } finally {
                PerfProbe.addStep("returnToHome", System.nanoTime() - tHome)
            }
        }
        val steps = flowJson.getJSONArray("steps")
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            // `planSummary` 是结果契约的出口，不能被 stopRequested 跳过：flow 用
            // `stopWhen planRemaining == 0` 正常收尾、以及回卷止扫/安全中止都会置该标志，
            // 原先一 break 就连汇总都不出 ⇒「全部达成」与「什么都没报」在结果上同形。
            if (vars.stopRequested && step.optString("do") != "planSummary") break
            executeStep(step)
        }
        // endConditions：total 核对（止扫中断时允许不符——止扫即异常策略）
        // ★ A10：对账口径必须与 pagedGrid 的「件数达标」判据**同源**（那边就是三者之和）。
        //   单轮扫描只有一个 flow 域的容器在增长（weapon_scan 只进 resultsWeapons、artifact_scan
        //   只进 results、character_scan 只进 resultsCharacters），合计即该域实收件数；
        //   旧代码只看 results.size（只装圣遗物）⇒ weapon_scan 正常收尾时 0 != N ⇒
        //   必报假 WARN「总数不符」+ total_mismatch。
        val collected = results.size + resultsWeapons.size + resultsCharacters.size
        val total = vars.total
        if (total != null && !vars.stopRequested && total != collected) {
            Log.w(TAG, "total mismatch: readCount=$total scanned=$collected")
            RecognitionLog.log(
                logTag,
                RecognitionLog.Level.W,
                "总数不符 读到=$total 实扫=$collected",
            )
            listener.onProgress("total_mismatch", mapOf("total" to total, "scanned" to collected))
        }
        val reason = vars.stopReason ?: if (vars.stopRequested) "stopWhen" else "completed"
        val elapsed = (clock() - tmStartMs).coerceAtLeast(0L)
        val perCell = if (tmCells > 0) elapsed / tmCells else 0L
        val perPage = if (tmPages > 0) elapsed / tmPages else 0L
        Log.i(
            TAG,
            "scan finished: $reason elapsed=${elapsed}ms cells=$tmCells pages=$tmPages " +
                "| 行级闭环 判跳行=$rowCheckSkips 判滑空=$rowCheckStalls " +
                "吞击重发=$swallowedClickRetries 点击放弃=${clickGiveups}格 定点重访=$dupRevisitRecovered/$dupRevisits " +
                "武器陈旧丢弃=${weaponStaleDropped}件 " +
                // ★ #104：只观测、已照常入库 —— 对账"多 N 件"时先看这个数，别把它算成丢件或真多出来的装备
                (if (weaponOverlapRepeats > 0) "武器重叠重读=${weaponOverlapRepeats}件(已入库) " else "") +
                "页级冻结放弃=${pageFreezeAbandoned}格 未知套装=${unknownSetPieces}件 " +
                // ★ #156：身份未解析的位数（照常入库、导出无 key）。>0 就说明昵称表又漏人了，
                //   对账侧会同时报"多 N 条未解析 + 缺 N 个真角色"。
                "未解析身份=${charUnresolved}位 " +
                // ★ #46：取证落盘的闸门计数只在**有话说**时出现（默认关取证 ⇒ 全 0 ⇒ 不打，免噪声）
                (if (panelShotSeq > 0 || panelShotDropped > 0 || panelShotWriteFailures > 0) {
                    "| 取证图 落=${panelShotSeq}张 闸门丢弃=${panelShotDropped}张 写失败=${panelShotWriteFailures}张 "
                } else "") +
                "perCell=${perCell}ms perPage=${perPage}ms | waits nav=${tmNavMs} panel=${tmPanelMs} settle=${tmSettleMs}",
        )
        // 只读性能探针：click/swipe 真实注入耗时 + OCR 网关耗时（分量实测，供
        // dsl/verify/_audit/IMAGE-PATH-COST.md §8 决策）。**独立一行**，避免打乱既有日志正则。
        Log.i(TAG, "probe ${PerfProbe.summary()} | sigSamples=$tmSigSamples")
        listener.onFinished(reason)
    }

    internal suspend fun executeStep(step: JSONObject) {
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
            "clickSlotTab" -> clickSlotTab(step)
            "planMatch" -> planMatch(step)
            "planDone" -> planDone(step)
            "planSummary" -> planSummary()
            "exit" -> exitStep(step)
            "verify" -> verify(step)
            "emit" -> listener.onProgress("emit", vars.snapshot())
            "notify" -> notifyStep(step)
            else -> {
                Log.w(TAG, "unknown step '$op', skipped")
                RecognitionLog.log(logTag, RecognitionLog.Level.W, "未知原语 $op 已跳过")
            }
        }
    }

    /**
     * `notify` 原语（P4）：脚本随时推一条重点信息给用户。
     *
     * ```json
     * { "do": "notify", "level": "warn", "text": "已入库 $total 件，重复过多提前结束" }
     * ```
     * `text` 支持 `$var` 插值（复用 DSL 既有的「`$` 前缀 = 引用」约定）；
     * 可用的变量 = [ScanVars.snapshot] 里的字段（total / level / rarity / locked / favorited /
     * crafted / gridLocked / charDupStreak / stopRequested），未知名字替换成空串。
     */
    internal fun notifyStep(step: JSONObject) {
        val raw = step.optString("text", "")
        if (raw.isBlank()) return
        val level = step.optString("level", "info").lowercase()
        val text = interpolateVars(raw)
        RecognitionLog.log(logTag, RecognitionLog.Level.I, "notify[$level] $text")
        listener.onNotice(level, text)
    }

    /** `$name` → 会话变量值；非标量转 JSON 串；未知名字 → 空串。 */
    private fun interpolateVars(raw: String): String {
        if (!raw.contains('$')) return raw
        val snapshot = vars.snapshot()
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '$') { sb.append(c); i++; continue }
            var j = i + 1
            while (j < raw.length && (raw[j].isLetterOrDigit() || raw[j] == '_')) j++
            val name = raw.substring(i + 1, j)
            if (name.isEmpty()) { sb.append('$'); i++; continue }
            sb.append(snapshot[name]?.toString() ?: "")
            i = j
        }
        return sb.toString()
    }

    // ---- #1 enterScreen：入口链跳转 + anchor OCR 断言（重试 3 次）----
    internal var enterScreenStep: JSONObject? = null

    /**
     * 主界面判据：`home.bagpack` / `home.character` 任一模板命中（两钮均为主界面独有）。
     *
     * @return `null` = 模板不可用（干跑单测/未下发模板）⇒ **判不了** —— 调用方必须把它与
     *         "不在主界面" 区分开（判不了时不许拦流程，否则干跑与首启环境全废）。
     */
    internal val panelSettleMs: Long
        get() = TimingOverrides.panelSettleMs.takeIf { it > 0L } ?: PANEL_SETTLE_MS

    val resultsCharacters = ArrayList<GoodCharacter>()

    /** 单角色草稿：parsePanel(char_profile) → navigate(命座/天赋) → emit。 */
    // ── 耗时归因（调试标定用；见 TimingOverrides）──
    private var tmStartMs = 0L
    internal var tmCells = 0
    internal var tmPages = 0
    internal var tmNavMs = 0L          // navigate 页切换等待累计
    internal var tmPanelMs = 0L        // 点格后面板名轮询累计
    internal var tmSettleMs = 0L       // 翻页 settle 轮询累计
    /** 签名轮询实际跨帧样本数（用于 `scan finished` 归因；0 = 走了旧路径）。 */
    internal var tmSigSamples = 0
    /** 就绪内容带只打印一次（便于现场确认用的是哪块 ROI）。 */
    internal var sigBandLogged = false

    /** "名字 ROI 是否可读"的流程级门：本流程首格求值一次后缓存（`siggate=0` 则逐格求值）。 */
    internal var sigGateCached: Boolean? = null

    // ── 就绪信号直采（2026-09-12）：实例级 scratch，避免每格分配 ──
    // 按**最大块网格**分配（`sigblocks` 可覆盖到 24×12），实际用多少由 TimingOverrides 决定。
    internal val sigBefore = ByteArray(SIG_LEN_MAX)
    internal val sigA = ByteArray(SIG_LEN_MAX)
    internal val sigB = ByteArray(SIG_LEN_MAX)
    /** 卡片**选中框**签名（点击是否落到卡片上）：见 [cardFrameMoved]。 */
    internal val sigCardBefore = ByteArray(SIG_LEN_MAX)
    internal val sigCardCur = ByteArray(SIG_LEN_MAX)

    internal var charName = ""
    /**
     * 最近一次**成功入库**的角色名。⚠️ `charName` 在 `emitCharacter` 末尾会被清空（重置草稿），
     * 而 flow 顺序是 `emit` → `stopWhen` ⇒ 止扫判据若读 `charName` 会**恒为空、永不触发**
     * （2026-09-11 实测：`name == roster[0]` 从未生效，10 角色那次实为 `reachedEnd` 收尾）。
     * 本字段不清空，专门供止扫判据求值。
     */
    internal var lastCharName = ""
    /** 连续「已入库过的角色」个数（回卷/遍历完判据）；由 stopWhen mode=duplicateStreak 启用。 */
    internal var charDupStreak = 0
    internal var charDupStreakLimit = 0

    /**
     * **回卷止扫的有效阈值**（`pagedGrid` 内 = 该网格**一页 + 一行** `cols × (traverseRows + 1)`）。
     *
     * 为什么随网格推而不写死（用户定稿 2026-09-12）：一页卡片数**随流程/网格不同**
     *   （圣遗物 7×3=21、武器/角色各自不同），而"回卷"的本质是**整页格子全是已入库的件**。
     *   阈值写死 3 太敏感：翻页相位抖动会让页首与上一页重叠 ⇒ 凑出 3 个连续重复件 ⇒ 误判回卷
     *   （实测只扫 100/932 件）；写死 8 也只到 205 件。
     *
     * 为什么 2026-09-26 又抬一行（`+ cols`）：单次翻页滑动没落地（实测约 1/17 页）造出的重复件
     *   **正好是一页** ⇒ 卡在旧阈值上，一次滑空就能误停整轮（09-12 实测 210/933、110/933）。
     *   抬一行之后至少要**连续两次**滑空才凑得满，语义变成"点完一整页无新增、再翻一页、
     *   第一行仍无新增"。配套要求：计数**跨翻页累积**（pagedGrid 已去掉开页清零），
     *   且 artifact/weapon 两条 flow 的 `dupPageConfirm` 置 1（一次命中即等于跨了一页+一行）。
     *
     * `0` = 未设置 ⇒ 回退到 flow 的 `charDupStreakLimit`（snap / rosterFind 等非翻页路径仍用它）。
     */
    internal var dupLimitEffective = 0

    /**
     * 翻页滑动**落地增益**的跨页 EMA（实测落地 px ÷ 本页实际命令 px）。
     *
     * ⚠️ 当前**仅作诊断**（2026-09-16 用户定稿）：命令一律不带增益（`planMainSwipe(…, gain = 1.0, …)`）
     *   且上界封顶在 target ⇒ 本值不参与任何决策，只进日志 `增益=`。
     *   （历史上它参与过 `cmd = 目标 / 增益`：增益 <1 时预补偿系统性欠滚。定稿理由是"放大命令 ⇒ 过滚 ⇒
     *   静默跳行漏件"，宁可欠滚重复点。）
     * ⚠️ 初值 = **0.91**（2026-09-16 真机实测定标，非"无先验"）：3200/BS 上 39 次翻页实测
     * `L/target = 753~833 / 876`，均值 **0.910**。
     * 更新点在 [VoteJudges.landingDecision]：仅当 `cmd > 0` 且 `|L − target| ≤ ADV_RESIDUAL_TOL` 才采纳。
     */
    internal var advGainEma = 0.91

    // ── ★ 行级闭环（2026-09-16 方案 C）：用内容键量「上一页→本页」前进量，判跳行/判滑空并计数 ──
    /** 每格计算出的内容键（含重复件）；页末收集成本页键序列。 */
    internal var lastCellKey: String? = null

    /** 上一页的键序列（**含读失败的空串占位**，下标 = 页内序号 —— 占位必须保留，否则前进量算错）。 */
    internal var rowCheckPrevKeys: List<String> = emptyList()

    /** 行级闭环统计（进 scan finished 摘要）：判跳行 / 判滑空。 */
    internal var rowCheckSkips = 0
    internal var rowCheckStalls = 0

    /** 单件名→套装反查未命中的件数（词典缺口，件本身已照常入库；见 emit 处的拆分判据）。 */
    internal var unknownSetPieces = 0

    /** 三档解析全未命中、按显示名入库的角色数（#156；这些记录**没有 GOOD key**，去管理器页补昵称）。 */
    internal var charUnresolved = 0

    /** 重读期抑制「连续重复」计数：重访/回读必然重读到刚记过的件，会被回卷判据误判成"整页零新增"。 */
    internal var suppressDupStreak = false

    /** scope=cell 止扫（3★/2★）的**连续命中计数**：单格误读不得截断整轮。 */
    internal var stopWhenCellStreak = 0

    /**
     * ★★ 2026-09-17 用户方案：**格级"件身份"记录** ★★
     * 记下"第 page 页第 idx 格（idx=row*7+col）读到的**是哪一件**"，
     * 格式 `set/slot/lvl/main#v1,v2,v3,v4`（词条数值升序）⇒ 可与 GT **直接对齐**（之前只存键哈希，无法对齐 ✗）。
     * 用途（离线分析）：
     *  - **同页内出现两次的同一件** ⇒ 该格点击**偏到了相邻卡**（同页每格本该不同件；跨页重复是预期重叠 ✓）
     *  - 由此可定位"哪一格点错、它挤掉了谁" ⇒ 与 GT 缺项精确配对 ✓
     */
    internal var lastCellIdentity: String? = null

    /**
     * ★★ 本轮：每格「面板是否出现过」★★
     * true = 解析前检测到面板指纹**变化过**（新面板确实渲染出来了）；
     * false = 等满上限仍是旧指纹 ⇒ **该格点击后没有新面板**（点击没生效/被吞/面板未打开）。
     * null = **本档根本没有指纹闸门**（武器走 FixedDelay，见下方 [PANEL_FP_GATE_ENABLED] 分支）
     *   ⇒ 既不是"出现过"也不是"没出现"，是**没测**。
     * 用途：跑完把"漏件所在格"与 appeared=false 的格对照 ⇒ 一次区分"点击没生效" vs "读了但错"。
     *
     * ⚠️ #55（2026-09-25）：此前无闸门时写的是 `true`（`fpRects.isEmpty()` 直接算"出现过"）
     *   ⇒ 武器整轮的 appeared **恒真**，"没有吞击/没有冻结"是**推出来的**而不是**测出来的**。
     *   这与本项目反复踩的三类假象同型（`total=` 恒 967、`accepted=` 恒 true、恒零死计数器）。
     *   注意**不要**顺手给武器加指纹闸门：同款武器的详情面板逐像素相同 ⇒ 指纹永远不变，
     *   闸门会一直等到超时（2026-09-17 对齐上游 GOODScanner `GoodWeaponScanner` 时已定案用
     *   FixedDelay）。诚实标"没测"才是对的修法。
     */
    internal var lastPanelAppeared: Boolean? = null

    /** 本轮已落盘的识别帧张数（见 [dumpPanelShot]，随 run 重置）。 */
    internal var panelShotSeq: Int = 0

    /** 本轮因闸门（张数上限 / 剩余空间不足）被拒的取证张数（#46，随 run 重置）。 */
    internal var panelShotDropped: Int = 0

    /** 本轮 `imwrite` 返回 false（磁盘满/编码失败）的次数 —— 这些张**不记 manifest**（#46）。 */
    internal var panelShotWriteFailures: Int = 0

    /** 上次检查剩余空间时的 [panelShotSeq]；0 = 本轮还没查过（#46）。 */
    internal var panelShotSpaceCheckedAt: Int = 0

    /** 上次空间检查的结论（#46）。 */
    internal var panelShotSpaceOk: Boolean = true

    /**
     * 武器「连续同一 identity」陈旧帧保护（★ 2026-09-19）。
     *
     * 病根：**点击未生效时右侧详情面板停在上一件**，连续多格读到同一件
     * （真机实测：`LionsRoar/L90/R5` 在导出里**连续出现 19 次**、`/L1/R2` 又一次 19 次）。
     * 位置判据（page,row,col）在这些格上是**递增**的 ⇒ 抓不到它；内容判据又会把真·多把合并。
     *
     * 判据：同一 identity 连续出现超过 [WEAPON_SAME_IDENTITY_CAP] 次 ⇒ 从第 CAP+1 次起**丢弃并计数**。
     * 为什么留 CAP=3 而非全丢：GT 里确实存在**内容完全相同**的多件（`BlackTassel L1R1` ×3），
     *   相邻同 content 的真·多把通常 ≤3；而陈旧帧表现为**长串**（十几到几十次）。
     * ⚠️ 这是**缓解不是根治**：根治要让"点击后详情面板确实更新"可靠（另立待办）。
     */
    /** 格点击模式（A/B 用，见 [ClickModeOverrides]）。 */
    internal val useTapForCell: Boolean get() = ClickModeOverrides.cellTap

    /** 本轮累计的定点重访次数 / 其中救回件数（2026-09-19 新增，随 run 重置）。 */
    internal var dupRevisits: Int = 0
    internal var dupRevisitRecovered: Int = 0

    /**
     * 本轮因**页级冻结**被放弃的格数（2026-09-24 新增，随 run 重置）。
     *
     * 为什么要单独计数：这些格对应的圣遗物**从未被读到**，是真实的覆盖损失。此前它藏在
     * "定点重访 0/502 救回"里无声无息 —— 全量对账（945 真值 vs 909 导出）才发现少 37 件。
     * 现在让损失在 `scan finished` 汇总里直接可见。
     */
    internal var pageFreezeAbandoned: Int = 0

    /** 本轮累计的"点击被吞"重发次数（观察 BlueStacks 输入吞没窗口频率用，随 run 重置）。 */
    internal var swallowedClickRetries: Int = 0
    /** 点击打满重发上限、本格将读到陈旧面板的次数（= 一件静默丢失的机会）。 */
    internal var clickGiveups: Int = 0

    internal var lastWeaponIdentity: String? = null
    internal var weaponSameRun: Int = 0
    internal var weaponStaleDropped: Int = 0

    /**
     * 「同一身份在**非连号**处再次入库」的次数 —— 只观测、**不丢件**（#104，2026-09-27）。
     *
     * 为什么不能丢：背包排序由用户手动决定，flow 里**没有**排序步骤，所以"同款多把必然连号"
     * 只是本轮的观察（实测 160 次带格号读数、13 个身份被读到多次，12 个连号、唯一非连号的
     * `TheStringless|L1|R5` 正是对账多出的那 1 件），不是可依赖的前提 —— 换成按"最近获得"排序
     * 就会误删真件。而放宽既有去重窗口有明确记录的负收益（见 [crossPageOverlap] 上方注释：
     * 曾误删 5 件真 3★、页级冻结放弃 0→17 格）。
     *
     * ⚠️ **2026-09-27 订正方向**（原注释说"修法是换算绝对列表序号（#70）"，试过并已回退）：
     * 用逐页**实测位移 L 累加**算绝对下标算术上不成立 —— 日志实测 L=809~868px 而每页真实前进
     * 3 行=876px，`round` 累加 4 页就差 1 行 ⇒ 离线重放立刻造出 7 处**异身份**假碰撞。
     * 而它想解决的"重叠区把同卡送到另一页内行"**现有 [crossPageOverlap] 已经拦住了**：
     * 同轮日志有 `跨页重复(identity) 丢弃: page=4 r2c1 / page=5 r0c0..c6` 共 7 格（含 3 格绝弦）。
     * ⇒ 真漏点不在几何去重，而在**定点重访那条路**（重读已覆盖的格并以"救回"身份入库，#73）。
     * 所以这里只留观测；别再把位移累加捡回来当判据。
     */
    internal var weaponOverlapRepeats: Int = 0
    internal val weaponIdentityRuns = HashMap<String, Int>()
    internal var lastEmittedWeaponIdentity: String? = null

    /**
     * ★★ 2026-09-17 跨页重叠对齐（deepwiki 方案，武器专用）★★
     * 上一页**全页**（traverseRows × cols）各列卡格指纹，索引 r*cols+c。
     * 起因：新页首行与上页末行**内容重叠**（同列同件）⇒ 不去重会让同款多把被重复入库
     * （实测 `Slingshot/1/1` ×22 ✗）。
     * 判据：**卡格位置在屏幕上固定**（只有内容随滚动变）⇒ 新页某格与上页**同列任意行**指纹相同
     * ⇒ 判为重叠重复 ⇒ 跳过该格（见 [skipCopyFrom]）。
     * ⚠️ 比对范围必须是**全页同列**而非"上页末行"：落地有 φ 偏差（实测 φ=33~73 ⇒ 实际滚动 2.0 行 ± φ）
     *    ⇒ 重叠量非精确整数行，只比末行**实测跳过 0 次** ✗。
     * （不能用内容键 —— 武器同款多把是常态；也不能用 `global` 估计 —— 实测累计漂移 ✗）
     */
    internal var prevAllCellFps: List<ByteArray?>? = null

    /**
     * 上一页**逐格身份串**（索引 r*cols+c）。与 [prevAllCellFps] 配对使用：
     * 跨页重叠跳过**只在"上一页那格确实入库了"（身份非空）时才允许** —— 否则上一页那件本就漏了，
     * 这一页还跳过 ⇒ 永久丢件。★ 2026-09-20（C：真机为 2 行前进、每页前 7 格是重叠）。
     */
    internal var prevAllCellIds: List<String>? = null

    /** 上一页**逐格内容键**（与 [prevAllCellIds] 配对；C' 复制时连键一起带，保证行级闭环可用）。 */
    internal var prevAllCellKeys: List<String>? = null

    /**
     * 本页**重叠格表**：本页格 idx → 上页同一件的格 idx。命中即"不点击、不 OCR，身份/内容键照抄"。
     * 两个生产者，各自实际只对一档网格有效（互补，不是重复）：
     * ① 页首**卡格像素**指纹比对 —— 仅武器（圣遗物禁用像素判重，见 [lastCellFp] 的 18 羽毛定谳）；
     * ② 页中**身份串**锚定 [anchoredD] —— 判据本身两档通用，但它要求上页那格**内容键非空**，
     *    而内容键 `lastCellKey` 目前只在圣遗物 emit 里赋值 ⇒ 实际只对圣遗物生效。
     * 同一格被两处都判中时以 ①（直接像素证据）为准 ⇒ ② 只用 `putIfAbsent` 写入。
     */
    internal val skipCopyFrom = HashMap<Int, Int>()

    /** 本页身份锚定算出的对齐偏移 d（本页 idx m ≡ 上页 idx d+m）；null = 未锚定（全部照常访问）。 */
    internal var anchoredD: Int? = null

    /**
     * 本轮已入库的「页:行:列:身份」四元组（#58② 同格重解析幂等，用法见 [parseWeaponPanel]）。
     * 与 [prevAllCellIds] 的跨页重叠判据互补：那条管"这格是上页某行的同一张卡"，这条管
     * "**同一页同一格被解析了两遍**"（吞击重发/定点重访都会让一格再解析一次）。
     */
    internal val weaponCellEmitted = HashSet<String>()
    /** 当前格的行/列（供跨页 identity 比对）。 */
    internal var curCellRow: Int = -1
    internal var curCellCol: Int = -1
    /**
     * ★★ 本页 **idx → identity** 表（定长语义，每页清空）★★
     * ⚠️ 2026-09-17 修 bug：此前用 `pageIdentities`（**可变长**收集数组）保存跨页比对用的身份串，
     * 而**被跨页丢弃的格 `return` 早于收集** ⇒ 索引错位 ⇒ 保存的数组与 `(row,col)` 不再对应
     * ⇒ 后续比对**全部失配**（实测 `Slingshot` 同列出现 2 次未被去重 ✗）
     * ⇒ 改为按 idx **无条件**写入（早于任何判重），与 `(row,col)` 严格对应 ✓
     */
    internal val curPageIds = HashMap<Int, String>()
    internal var curCellIdx: Int = -1

    /**
     * 当前角色的**突破阶 0-6**（GOOD `ascension`）。
     * 由 `parseCharacterPanel` 从面板等级文本 `Lv.X/Y` 推导：优先用**上限 Y**；
     * 上限不可得时按 level 分层回退（GT 反推：70~80 ⇒ 5、81+ ⇒ 6）。
     */
    internal var lastCharAscension: Int = 0

    /**
     * 本格角色入库时的 `key/lv` 标签（**仅**给「格级:」日志用，见 #122）。
     *
     * 为什么需要它：那条日志的 `key` 栏读的是 `pageKeys`（内容键），而内容键只有圣遗物/武器
     * 路径会写 ⇒ 角色格恒打成 `**空(未入库)**`，与紧挨着的 `char emit #N` 直接矛盾。
     * 这里由 [emitCharacter] 在**入库那一刻**写、`pagedGrid` 打完本格日志即清 ⇒ 与"这格有没有入库"
     * 严格同源。⚠️ 它**不是**判据，任何分支都不许读它。
     */
    internal var lastCharCellLabel: String? = null

    /** 页参数镜像（`pagedGrid` 的局部变量对 `parseWeaponPanel` 不可见 ⇒ 用字段传递）。 */
    internal var curPageNo: Int = 0
    internal var curCols: Int = 7
    internal var curTraverseRows: Int = 3

    /** ★ 面板指纹快照（GOODScanner `panel_snapshot`）：上次**稳定**面板的原始像素（仅作加载闸门）。 */
    internal var panelFpSnapshot: ByteArray? = null

    /**
     * ★ 卡格（网格卡片）指纹：上一格的像素快照。
     *
     * ⚠️ **只用来决定"要不要等面板"，绝不用来跳过点击/解析**（2026-09-16 定谳）：
     * 本账号实测存在 **18 张同套同部位同强化的 MarechausseeHunter 羽毛** ⇒ 它们的**卡片像素几乎全同**
     * （同图标 + 同 5★ 星带 + 同锁标）⇒ 若按卡格像素判重直接跳过点击，会把这 18 张**并成 1 张** ✗✗。
     * （GOODScanner `detect_grid_duplicates` 的原始用例是**武器**：同款武器面板完全相同，"多份同款"正合适。）
     * ⇒ 这里只用它回答"**这一格的卡与上一格是不是同一张**"：同 ⇒ 面板本该不变 ⇒ **无需等待**（省 1.5s）；
     *   不同 ⇒ 面板应变 ⇒ 若还没变就是**陈旧帧** ⇒ 才进等待闸门。
     */
    internal var lastCellFp: ByteArray? = null

    /** 本格卡格指纹是否与上一格不同（决定面板闸门要不要等）。 */
    internal var gridCellChanged = true

    /**
     * **会话级看门狗心跳**（2026-09-16）：最后一次"确实有进展"的时间戳（`SystemClock.elapsedRealtime()`）。
     * 由 [freshFrame] 打点 —— 覆盖"每格抓帧 + settle 轮询"两条主路径 ⇒
     * **真的卡死（JNI/a11y/MediaProjection 阻塞）时它不会前进**，而只是在慢（如长 settle）时会前进 ✓。
     * 监视方在 `ScriptRunner`（独立 daemon 线程）⇒ 专治**visit 内部挂死**（页级看门狗只在格与格之间检查，抓不到）。
     */
    @Volatile
    var lastProgressAtMs: Long = 0L
        internal set

    /**
     * 覆盖率仪表（2026-09-16）：当前页首格在列表里的**全局序号**（按实测前进量累加）。
     * 只靠"每页新增件数"看不出位置；有了全局序号才能看出"哪一段从没被点到"。
     */
    internal var rowCheckGlobalStart = 0

    /**
     * 连续「整页零新增」的页数；达到 [dupPageConfirm] 才断言列表回卷。
     *
     * ⚠️ 与 `charDupStreak`（连续重复件计数，**每页开头清零**）配套：那个是**页内**判据，这个是**页级确认**。
     * 为什么需要页级确认（2026-09-12 实测）：实测翻页一页只前进 ~1.9 行（应 3 行），于是上一页尾部的重复
     * 会与本页头部的重复**跨页累计**（实测 page6 尾 8 + page7 头 13 = 21 = 一页卡片数）⇒ 全量扫描在第 8 页
     * 就误判回卷停住（导出 111 件 / 应 933）。开页清零解决跨页累计；本确认再挡「单次滑空」。
     */
    internal var dupPageStreak = 0

    /** 断言回卷所需的连续整页零新增页数（flow `stopWhen.dupPageConfirm`，默认 2；=1 恢复旧的立即停行为）。 */
    internal var dupPageConfirm = 2

    /** 本页是否已做过回卷判定（同一页 item 级 + 格级两处调用只记账一次）。每页开头复位。 */
    internal var dupPageDecided = false

    /** 本页判定的结果（配合 [dupPageDecided] 在同一页内复用，避免重复计数）。 */
    internal var dupPageStopConfirmed = false

    /**
     * 连续重复达到「一页卡片数」时的**统一处置**（item 级 `noteDupAndMaybeStop` 与格级检查共用同一判据）。
     *
     * 首次命中多为**滑空 / 半页重叠** ⇒ 不算回卷：清计数继续翻页；
     * 只有**连续 [dupPageConfirm] 个整页**都零新增，才断言列表回卷并停止。
     * （真回卷里「后续每一页全是已入库件」⇒ 必然连续满足；误判代价不对称：错停=整轮报废，多扫一页=~6s。）
     *
     * @return `true` = 确认回卷，调用方应停止；`false` = 疑似滑空，调用方应清计数并继续。
     */
    internal var charKey: String? = null
    internal var charLevel = 0
    internal var charElement: String? = null
    internal var charConstellation = 0
    internal val charTalents = MutableList(3) { 0 }

    /** 当前角色的词典 key（供 `readTalent` 查词典 c3/c5 减命座加成）。 */
    internal var lastCharKey: String? = null

    /**
     * **无命座系统**的角色（GOOD key）：奇偶（Manekin / Manekina）。
     * 其"命之座"页无真实节点 ⇒ 六格饱和度判据恒判满（实测 6/6）⇒ 见 [readConstellation] 末尾的强制归零。
     */
    internal val NO_CONSTELLATION_KEYS = setOf("Manekin", "Manekina")

    // 显示名由玩家自定义的角色一律走 NameOverrides（#105）：09-17 那版内置别名表硬写了本账号的两个
    // 昵称、方向还和 GT 对不上（见 NameOverrides 的 KDoc），等于把"一个账号的状态"发给所有用户。
    // 没填 ⇒ 保留原文 + 打日志，宁可漏也不猜错。旅行者不走它 —— 它的 GOOD 键要带元素后缀，
    // 而元素只有角色概览面板里有 ⇒ 交给下面这条**账号无关**的元素规则。

    /** 元素（header 中「X元素」的 X）→ GOOD v3 旅行者键。 */
    internal val TRAVELER_BY_ELEMENT = mapOf(
        "风" to "TravelerAnemo",
        "岩" to "TravelerGeo",
        "雷" to "TravelerElectro",
        "草" to "TravelerDendro",
        "水" to "TravelerHydro",
        "火" to "TravelerPyro",
        "冰" to "TravelerCryo",
    )

    /**
     * 玩家自定义名的**可信性**判据 —— 旅行者元素规则的**前置门**。
     * 实测（cver12）OCR 失败会给出 `'.'` / `''` / `'2一一天赋演示'` 之类 ⇒ 若不设门，
     * 这些垃圾名会被元素规则吞成 `TravelerXxx`（凭空多一件 + 真角色丢失）✗。
     * 规则：2~8 个**纯汉字**（玩家昵称的唯一合理形态）。
     */
    internal val manageResults = mutableListOf<Triple<Int, String, String>>()

    /** 本轮内：连续多少次"写锁成功且没点掉过弹框"（见 [noteCleanLockWithoutDialog]）。 */
    internal var cleanLocksNoDialog = 0
    /** 本轮内是否按"不再弹"处理；由 [lockConfirmAbsentLearned] 起步，真弹出来就当场作废。 */
    internal var lockConfirmAbsent = lockConfirmAbsentLearned

    internal fun dictFuzzy(step: JSONObject): Boolean = dictFuzzyOf(step.optJSONObject("dict"))

    /** [dictFuzzy] 的 dict 对象版（子解析函数只拿到 dict 时用）。 */
    internal class CellFrameContext {
        var frame: Mat? = null

        /** 已有共享帧则复用；否则抓新帧并持有（visit 结束统一 release）。 */
        suspend fun acquire(fresh: suspend () -> Mat): Mat = frame ?: fresh().also { frame = it }

        fun release() {
            frame?.release()
            frame = null
        }
    }

    /** §15 P2：ifMatch when 表达式变量表（Boolean→0/1，与 [Expr] 求值约定一致；null 视为 false/0）。 */
    /**
     * ifMatch 的表达式变量：**直接复用 [ScanVars.exprVars]**（2026-09-18 统一）。
     * 此前这里是**第二份**实现，与 stopWhen 那份不同 ⇒ 同一个表达式两处结果不一致。
     * 现在只保留 ScanVars 那一份作为唯一事实源，这里只做转发。
     */
    internal fun exprVars(): Map<String, Any?> = vars.exprVars()

    internal fun FrameRect.toIntRect(): IntRect = IntRect(left, top, width, height)

    /**
     * 动作后取新帧：帧阈值由 ActionGateway 实现侧 markActionAt 管理（P0 机制），
     * afterTimestampMs 传 0 仅作占位；超时回退最新帧（R1：单应用共享静止停帧兜底）。
     */
    internal companion object {
        const val TAG = "BetterGI.Scan"
        const val ENTER_SETTLE_MS = 1200L
        const val SCREEN_SETTLE_MS = 1500L
        const val CLICK_SETTLE_MS = 600L
        /** 角色筛选面板内每步的等待（#127）。取手工实测节奏 1.5s，探针跑通后再收紧。 */
        /** 面板每步等待的**默认**值；可被 `timing=panel=NNNN` 覆盖（见 [TimingOverrides.panelSettleMs]）。 */
        const val PANEL_SETTLE_MS = 1500L
        /** 筛选面板"滚到锚点确认"的最大手势次数（惯性 + 起点未知，见 [ensureCharPanelScrolled]）。 */
        const val CHAR_PANEL_SCROLL_TRIES = 4
        /**
         * 角色筛选面板的判勾线（±8px 邻域最大亮度）。
         *
         * ⚠️ **不能**复用 [checkboxChecked] 的 >150：那是套装面板验证过的，而这里"已选"有两种读数
         * ——米色底 227 与绿勾本身 125，150 会把后者判成未勾。2560 实测未选 50~52（圆环最亮 75）、
         * 已选 125~227 ⇒ 取两侧中点 100。
         */
        const val CHAR_BOX_CHECKED_MIN = 100
        /** 锁钮按压时长（与 `DEBUG_CLICK` 实测生效的那次一致）。 */
        const val LOCK_PRESS_MS = 120L
        /** 连续几次"写锁成功且全程没见弹框"才认定本设备不再弹（不把一次观测当定案）。 */
        const val LOCK_CLEAN_BEFORE_ABSENT = 3
        /** 锁态收敛预算：真机实测确认框迟到极值 ~60s（2026-09-18），留一半余量。 */
        const val SETTLE_LOCK_BUDGET_MS = 90000L
        const val STAR_GOLD_THRESHOLD = 100 // starBand.judge: 格内金像素>100 → 星亮
        const val ANCHOR_RETRIES = 3
        const val ANCHOR_RETRY_DELAY_MS = 1500L
        // §15 归位主界面（GOODScanner return_to_main_ui 同参数）
        const val RETURN_HOME_ATTEMPTS = 8
        /** #139：走兜底坐标（返回钮模板未命中）时最多点几次 —— 盲点与"模板命中"不共用上限。 */
        const val RETURN_HOME_BLIND_MAX = 3
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

        // ── 卡片**选中框**判据（2026-09-23，见 [cardFrameMoved]）──
        /** 卡片签名网格边长（8×8=64 块，覆盖 196×253 的卡）。 */
        const val CARD_SIG_BLOCKS = 8
        /**
         * 判"选中框移动"所需的最少变化块数。
         *
         * 实测（BlueStacks 2560×1440 武器背包，点相邻卡前后两帧按引擎同口径 tol=2 比对）：
         * 被点卡 **11/64**、原选中卡 **10/64**（框被摘走）、其余 21 格 **0/64** ⇒ 3 有 3 倍余量。
         */
        const val CARD_MOVED_MIN_BLOCKS = 3f
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
         * cell 点击后「面板全程未见变化」时的**同坐标重发**上限（2026-09-16）。
         *
         * 为什么安全：重发用的是**完全相同的点击坐标** ⇒ 最坏只是重复读同一张卡（幂等），
         * 不会误加相邻件；代价 ≈ 1 次点击 + 一轮等待。取 1（共 2 次尝试）——
         * 观测到的"被吞窗口"可长达 20 击，重发 1 次已能给多数瞬时吞点击兜底，
         * 且不把偶发问题变成常态耗时。
         */
        /**
         * cell 点击后「面板全程未见变化」时的**同坐标重发**上限（2026-09-16 引入时为 1）。
         *
         * ★ 2026-09-19 由 1 → **5**：用「引擎格级日志 + 宿主机侧独立 screencap 对拍」定位到
         *   BlueStacks 存在**周期约 12 秒、每次吞约 6 次点击**的输入吞没窗口（每页 row0 col1..6 处出现、
         *   宿主机截屏同步证明**屏幕确实没动** ⇒ 排除我方采集缓存，是游戏/模拟器输入层真卡）。
         *   重发 1 次只能覆盖 ~2s 窗口 ⇒ 6 格全丢；重发 5 次 ≈ 多耗 ~8s ⇒ 第一个被吞格就能把
         *   12s 窗口烧完、后续 5 格恢复正常 ⇒ **每次窗口的丢失从 6 格降到 ≤1 格**。
         *   安全性不变：同坐标重发幂等，最坏重读同一张卡 ⇒ 内容键相同、被去重吸收。
         *   ⚠️ 别把 `appeared` 当安全网：它**只进 `格级:` 日志与 stall manifest**，
         *      没有任何入库判据读它（2026-09-25 核查：全仓 8 处引用无一是分支条件）。
         *   代价：被吞窗口内每格多等 ~1.6s×5；正常路径**零成本**（只在"全程未见变化"分支才重发）。
         */
        const val CLICK_RETRY_MAX_ON_NOCHANGE = 5

        /**
         * 「珐露珊已装备」⇒ `珐露珊`；文案里没有装备标记 ⇒ null（= 未装备，导出空串）。
         *
         * ⚠️ 必须容忍**尾部截断**（武器/圣遗物两条路径共用，2026-09-25 武器全量实测）：
         *   97 个带「已装…」的格子里 **15 个 OCR 把末字「备」吃掉**（读成 `九条裟罗已装`），
         *   旧写法 `takeIf { it.contains("已装备") }` 让这 15 件的装备者**静默变空** ⇒
         *   与 GT 按 (key,rarity,level,refine,location,lock) 严格对齐时全部判成错身份。
         *   角色名不会以「已 / 装 / 备」结尾，故按尾缀逐级截断是安全的。
         */
        internal fun equippedOwnerOf(text: String?): String? {
            val t = text?.trim().orEmpty()
            if (t.isEmpty()) return null
            val cut = when {
                t.contains("已装备") -> t.substringBefore("已装备")
                t.endsWith("已装") -> t.removeSuffix("已装")
                t.endsWith("已") -> t.removeSuffix("已")
                else -> return null
            }
            return StatParser.clean(cut).takeIf { it.isNotEmpty() }
        }


        /**
         * 定点重访的单格退避（2026-09-19 效率 A/B 定标）。
         *
         * **实测两轮不比一轮多救回**（一轮 32 格救回 8 件；两轮 31 格救回 7 件，第二轮基本空转）
         * ⇒ 只重访一次。若将来发现失败呈"长窗口"而非瞬时，应加大本退避而不是加轮数 ——
         * 在同一窗口内重复点击无效（与"读失败成窗口"的结论一致）。
         */
        const val REVISIT_BACKOFF_MS = 300L

        /** 连续失败 ≥ 此格数 ⇒ 判为"吞击窗口"：先等 [WINDOW_SETTLE_MS] 再整段重扫。 */
        const val WINDOW_MIN_CELLS = 2

        /** 连续 ≥ 此格数**副词条块相同** ⇒ 判为"半新半旧面板"（副词条区停滞），纳入定点重访。 */
        const val SUBST_STALE_MIN = 3

        /**
         * 身份串 `#` 之后是不是**圣遗物副词条数值块**（如 `7.4,9.3,10.5,53.0`）。
         *
         * ⚠️ #52（2026-09-25）：武器身份串是 `key/level/rarity#R<精炼>@<位置>`（在 [parseWeaponPanel] 里
         *   就地拼，没有独立的 identity 构造函数），`#` 之后是**精炼等级**，而绝大多数武器都是 R1 ⇒ "连续 ≥3 格相同"是**常态**，
         *   判据会在每一页都造出一串假失败格，喂给定点重访白烧时间，并在
         *   `longest*2 >= pageSize` 那条**页级冻结快速放弃**判定下把整页静默丢掉。
         *   所以按**语义**门控（这块到底是不是数值列表），不按 gridKey 硬编码：
         *   将来任何一档把副词条放进身份串，这条判据自动对它生效。
         *
         * ⚠️ 不要求"至少一个逗号"：run12 尾区实证 1 副词条的 3★ 件身份串就是 `…critRate_#2.8`
         *   （单值无逗号），要求逗号会把这类真件从判据里悄悄摘掉 —— 那是比武器误判更隐蔽的回归。
         */
        internal fun isSubstatBlock(s: String): Boolean =
            s.isNotEmpty() && s.split(',').all { it.trim().toDoubleOrNull() != null }

        /**
         * 武器**跨页重叠**判据（纯函数）：本格是否就是上页那张已经读过的卡。
         *
         * 表必须是**绝对下标** `r*cols+c` 的全行表（`prevAllCellIds`）——#60 就是喂了一张
         * 只存 row1/row2 的紧凑表却仍按绝对下标读，于是"row1"这一路静默失效（见调用处注释）。
         *
         * 只比**同列**的上页最后两行（`traverseRows-2` / `traverseRows-1`）：
         * 一页 3 行时，前进 1 行 ⇒ 新 row0 ← 上页 row1；前进 2 行 ⇒ 新 row0 ← 上页 row2。
         * 前进量 >2 行的部分对应上页 row3/row4，表里没有 ⇒ `getOrNull` 给 null ⇒ 不误伤真·同款多把。
         *
         * 注意 `row` **不参与**取数下标（判据问的是"这个身份在上页末两行出现过吗"，
         * 不是"上页第 row 行是谁"）——但 `row`/`col` 为 -1 时（snap / rosterFind 等非 pagedGrid 路径）
         * 本格没有网格位置可谈，一律不判。
         */
        internal fun crossPageOverlap(
            prev: List<String>?,
            cols: Int,
            traverseRows: Int,
            row: Int,
            col: Int,
            identity: String?,
        ): Boolean {
            if (prev == null || row < 0 || col < 0) return false
            // 空身份 = 这格根本没读到东西，不能拿去和表里的空格"相等"（那会把读失败的格当成重叠）。
            if (identity.isNullOrEmpty()) return false
            if (traverseRows < 2 || cols < 1) return false
            return prev.getOrNull((traverseRows - 2) * cols + col) == identity ||
                prev.getOrNull((traverseRows - 1) * cols + col) == identity
        }

        /**
         * 圣遗物**身份锚定**的配对搜索（纯函数）：在本页锚点 `(i1, i2)` 与上页身份表 [prev] 之间
         * 找**逐位相邻**的落点，返回上页下标 `hit`（`i1 ≡ prev[hit]`、`i2 ≡ prev[hit+1]`），无解 -1。
         *
         * 命中即隐含 `d = hit - 1` —— d 是**本页相对上页滚过的格数**；列表一行固定 `cols` 件、
         * 滚动只会整行换人 ⇒ d 必为 `cols` 的整数倍 ⇔ `hit ≡ 1 (mod cols)`。
         * 这条不是巧合而是结构：实测 6 轮 ~100 次逐位命中**无一例外**满足（2560/武器 hit=13、
         * 2244 hit=15 即 d=12=2×6 / d=14=2×7）。调用处另设了取模哨兵。
         *
         * ⚠️ 判据一律带词条数值，**不做"只比 `#` 前缀"的宽松回退**：那条路在 5★ 长段里熵≈0
         *   （同套同部位同等级同主词条连排），实测 10 次命中 10 次落错行，而每次误判都会让
         *   一整段格子"不点击、不 OCR"地被抄走身份 ⇒ 静默丢件（#66，账见调用处）。
         */
        internal fun anchorHit(prev: List<String>, i1: String, i2: String): Int {
            for (j in 0..(prev.size - 2)) {
                if (prev[j] == i1 && prev[j + 1] == i2) return j
            }
            return -1
        }

        /** 窗口就位的等待时长（吞击窗口典型 ~0.5~3s；取 1.2s 覆盖多数，过长则拖时长）。 */
        const val WINDOW_SETTLE_MS = 1200L

        /**
         * 调试：非空 ⇒ 每格把**识别实际使用的那一帧**落到该目录（见 [dumpPanelShot]）+ 一行
         * `manifest.jsonl`。由 `ACTION_DEBUG_SET_PANEL_SHOTS` 开/关；默认 null ⇒ 热路径零开销。
         */
        @Volatile
        var panelShotDir: java.io.File? = null

        /** 落盘 JPEG 质量：够看清卡片白边与面板文字残影，又不至于几百张就吃满存储。 */
        const val PANEL_SHOT_JPEG_QUALITY = 80

        /**
         * 单轮取证图**张数上限**（#46）。
         *
         * 实测 JPEG q80 全屏 3200×1440 约 **351KB/张**（`_dev/shots/panelshots_093655` 21 张 / 8.4MB）
         * ⇒ 一轮 1256 格全落 = **~430MB**。取证只需样本，不需要全量；120 张 ≈ 42MB 已足够
         * 覆盖"每页头几格 + 各停滞点"。
         */
        const val PANEL_SHOT_MAX_PER_RUN = 120

        /**
         * 剩余空间**下限**（#46）：低于此值不再落盘，避免把设备写满。
         * 取 200MB ≈ 570 张余量，够本次取证跑完且不挤占系统。
         */
        const val PANEL_SHOT_MIN_FREE_BYTES = 200L * 1024 * 1024

        /** 每落多少张复查一次剩余空间（`getUsableSpace` 要 statfs，不必每张都做）。 */
        const val PANEL_SHOT_SPACE_CHECK_EVERY = 20
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

        /**
         * ★ 2026-09-14（[awaitGridStable] 的 `requireChange` 专用）：命令滑动后，在此窗口内必须
         * **先观察到一次画面变化**才允许判「已稳定」。堵「手势异步派发 ⇒ 起步前的静止帧被当稳定帧」这个
         * 静默误判（实测手势时长 780ms，故留 1500ms 余量；超窗未变 = 滑动未送达/列表到底）。
         */
        const val SETTLE_CHANGE_WINDOW_MS = 1500L


        const val SETTLE_FRAME_TIMEOUT_MS = 800L
        /** `pollAnchorReady` 的轮询步长（enterScreen 末端就绪）。一次采样 = 抓帧 + 1 次 rec ≈ 10~25ms。 */
        const val ANCHOR_POLL_MS = 150L

        /** 入口幂等：开链前先短轮询锚点的预算（命中即跳过入口链，避免把已开的界面点关）。 */
        const val ENTRY_IDEMPOTENT_POLL_MS = 800L

        /** C' 身份锚定：用本页 idx1、idx2 两格的身份序列在上页身份表里找对齐（要求两格都精确命中）。 */
        const val OVERLAP_ANCHOR_COUNT = 2
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

        /** §14 A3：snap 遍历最大轮数兜底（防指纹判据失效时死循环；正常由 peek 消失终止）。 */
        const val MAX_SNAP_ROUNDS = 400

        /**
         * 武器：同一 identity 连续出现超过本次数即判"陈旧帧"丢弃（见 [weaponSameRun]）。
         *
         * ⚠️ **CAP=3 实测过紧**（2026-09-19 复验）：`多 51→1` 达成，但 `漏 43→27` ——
         * 被切掉的正是**真·多把**（`DebateClub/L1/R1 ×4`、`Slingshot ×4`、`HarbingerOfDawn ×4`…，
         * 全是 3★ L1R1，用户本就有一堆）。
         * **CAP 上调到 8**：真·多把的观测上限 ≈8（历史注释：`DebateClub` 有 8 件），
         * 而陈旧帧表现为**十几到几十次**的长串 ⇒ 8 能保住真·多把，同时把长串截到 8。
         * 🔴 **纯内容判据本质上无法区分这两种情形**（二者都是"同一 3★ 武器连续出现"）——
         * 正解是换判据：看**网格选中框是否移动**（真·多把 ⇒ 选中框换格；点击失效 ⇒ 选中框不动）。
         * 本常量只是**缓解**，根治见待办「点击后详情面板/选中框未更新的可靠性」。
         */
        const val WEAPON_SAME_IDENTITY_CAP = 8
        const val MAX_SCROLL_TOP_ROUNDS = 12
        const val GRID_TOP_STABLE_ROUNDS = 2

        /**
         * 「按件数推算的翻页数上限」的余量（2026-09-26 方案 C）：`ceil(total / 每页格数) + 本值`。
         *
         * 这是 pagedGrid 收尾的**唯一兜底**（连续零新增那条链已降级成诊断，见 `noteDupAndMaybeStop`）。
         * 取 5 的根据：一次滑动没落地只会白读一页（实测约 1/17 页），5 页余量足够吸收偶发连续滑空；
         * 而计数器比列表实际能扫到的件数偏大时（已知计数器与抓包真值可差 ~8 件），最多多花 5 页 ≈ 半分钟。
         */
        const val PAGE_CAP_MARGIN = 5

        /**
         * 页级看门狗（2026-09-16）：真机偶发「主滑派发后无任何后续日志」的硬挂
         * （本轮实测卡死 >5min，整轮扫描报废）。正常单页 ~6s ⇒ 超过本阈值即判挂死，
         * 置 stopRequested 让 pagedGrid 正常收尾（**已入库结果照常导出**，不白跑）。
         */
        const val PAGE_WATCHDOG_MS = 90_000L

        /** 面板指纹**等待**上限/轮询（GOODScanner `wait_until_panel_loaded`）：面板未换时最多等这么久。 */
        //   ⚠️ 2026-09-16 真机实测：20 次"等待成功"**全部只需 120ms**（= 一个轮询间隔）⇒
        //   1.5s 上限纯属浪费（41 次超时每次都白烧 1.5s ≈ 61s ⇒ 整轮 +14% 时长）⇒ 砍到 400ms。
        const val PANEL_FP_WAIT_MS = 400L

        /**
         * **重复格**（卡格指纹与上一格相同）的面板等待上限。
         * GOODScanner `PANEL_LOAD_FAST_TIMEOUT_MS = 100ms`（重复件只缩短超时、**不跳过 OCR**）✓
         */
        const val PANEL_FP_DUP_WAIT_MS = 100L

        /** 武器面板**固定延迟**（GOODScanner `DEFAULT_WEAPON_PANEL_DELAY = 50ms`）：同款武器面板全同 ⇒ 指纹无效。 */
        /**
         * 武器面板**固定延迟**。GOODScanner 用 `DEFAULT_WEAPON_PANEL_DELAY = 50ms`，
         * ⚠️ 但**真机实测 50ms 不够**：面板动画未完成就抓帧 ⇒ 精炼/等级行读到空
         * （首轮 209 件里 57 条的 `refine` 读成 `null` ⇒ 同款 3★ 键全同 ⇒ 大量误判重复
         *  ⇒ 触发 `duplicateStreak` 早停 ⇒ 3★ 区被截断 ⇒ 漏 100 件 ✗✗）。
         * 提到 **300ms**（与我方 artifact 的 clickDelay 同量级），给面板渲染留足时间。
         */
        const val WEAPON_PANEL_DELAY_MS = 300L

        /** 天赋读数为 0 时（时序性读失败 / 切页淡入）**最多重读次数**与间隔（ms）。 */
        const val TALENT_RETRY_MAX = 6
        const val TALENT_REREAD_DELAY_MS = 700L

        /**
         * 「读到行名 ⇒ 下移一行补读」要用的**行距**：优先从本档 `lvRois` 自身推（相邻 ROI 的 top 差），
         * 推不出来时才回落到 [TALENT_ROW_PITCH_PX]。
         *
         * 2026-09-30 #152：原实现写死常量，而该常量只在 2560/3200 成立（实测 151~153）；
         * 2244 的 lvRois 行距是 **113/114** ⇒ 补读框落到空处、OCR 空手而归 ⇒ 静默保持 burst=0。
         * 判据取证：三档同跑 character_scan，3200/2560 各 3 条 `下移一行补读 burst=10/12`，
         * 2244 一条都没有（连"越界弃用"都没有 = 根本没读到数）。
         */
        internal fun talentRowPitch(rects: List<FrameRect>): Int {
            for (i in rects.size - 1 downTo 1) {
                val d = rects[i].top - rects[i - 1].top
                if (d in 40..400) return d
            }
            return TALENT_ROW_PITCH_PX
        }

        /**
         * 天赋格子文本 → 等级值。返回 `0` = 没读到，`-1` = 读到了但**不是等级**（越界）。
         *
         * ★ 2026-09-30 #153：**锚定在 `Lv` 的「v」之后取数**，而不是「串里第一个数」。
         *   2244 六轮实测的错读原文分两类：
         *   · **左侧邻格碎字**：`4Lv.1`(真值1) / `.L4`(真值1) / `Lv.1-` / `•Lv.9` —— 旧规则取第一个数
         *     ⇒ `4Lv.1` 直接得 4 ✗；锚定后取 v 之后的 1 ✓。
         *   · **首字母被切**：`iv.1` / `Ev.10` / `Iv.12` / `Lv.I` —— 这些本来就能取对（`iv.1`→1），
         *     但 `Lv.I`/`Lv.r`/`Lv.J`（把 1 认成字母）会得 0 ⇒ 交给重读投票，不硬猜。
         *   找不到 `v` 时回落到「第一个数」，保持旧行为不引入新失败面。
         */
        internal fun talentLevelOf(text: String?): Int {
            if (text.isNullOrEmpty()) return 0
            val clean = StatParser.clean(text)
            val anchored = Regex("(?i)v[^0-9]{0,3}(\\d{1,2})").find(clean)?.groupValues?.getOrNull(1)
            val raw = (anchored ?: Regex("(\\d+)").find(clean)?.groupValues?.getOrNull(1))?.toIntOrNull() ?: 0
            return when {
                raw in 1..TALENT_LEVEL_MAX -> raw
                raw > 0 -> -1
                else -> 0
            }
        }

        /**
         * 多次读数的**投票合并**（#153 引入多数票；#166 把平票方向从「取更晚」改成「取更大」）。
         * 只投非零票（0 = 这次没读到，不是读到 0）。
         *
         * ★ 2026-10-01 #166 平票方向：三档实测里**每一条掉字错读都偏小**（`Lv.10`→`Lv.1o`→1、
         *   `Lv.12`→`•Lv.i2`→2、`Lv.11`→`Lv.1`→1），因为 [talentLevelOf] 锚在 `v` 之后取
         *   `\d{1,2}` —— 首位被认成字母就整个丢掉。所以平票时**更大的那一侧**才是没掉字的那一帧。
         *   取证：2244 三轮 `character_scan`（19:30 / 19:45 / 20:58）共 12 次"读数有分歧"的访问，
         *   扣掉 3 次绫华/莫娜的「读到行名 ⇒ 下移补读」（那不是合并律的样本），
         *   余 9 次：「取更晚」对 6、「取在先」对 3、「取更大」**9/9**。
         *   旧律（取更晚）在这里造成过一条**已入库**的错值：19:30 轮 随机姓名(=Manekina)
         *   `auto` 读成 1（真值 10），导出件 `20261001_1941_2244_character_HEAD.json` 里就是 1。
         *   ⚠️ 反向风险没被排除：若哪天出现**偏大**错读（邻格数字粘到右侧），平票时取更大会选中它——
         *   但那种读数只可能是 1 票，只要 [#166] 的停止律真的采到了第 3 帧就会被多数票否掉；
         *   打到 TALENT_RETRY_MAX 仍平票时调用方会打 loud 日志（不再静默）。
         */
        internal fun voteTalentLevel(samples: List<IntArray>, slot: Int): Int {
            val count = HashMap<Int, Int>()
            for (si in samples.indices) {
                val v = samples[si].getOrNull(slot) ?: 0
                if (v <= 0) continue
                count[v] = (count[v] ?: 0) + 1
            }
            if (count.isEmpty()) return 0
            val top = count.values.max()
            return count.filterValues { it == top }.keys.maxOrNull() ?: 0
        }

        /**
         * 各格读数里是否还有**平票**（#166）：任一格有两个不同的非零读数**同列最高票**即为 `true`。
         * 和 [voteTalentLevel] 配对用——多数票的前提是"有多数"；平票时取值完全由平票方向
         * 单方面决定，等于把投票退化成一帧定终身。
         * 非零过滤与投票同口径：`0`/`-1`（没读到 / 读到但不是等级）都不算一票，也不构成平票。
         */
        internal fun talentSamplesTied(samples: List<IntArray>): Boolean {
            for (slot in 0 until 3) {
                val count = HashMap<Int, Int>()
                for (s in samples) {
                    val v = s.getOrNull(slot) ?: 0
                    if (v > 0) count[v] = (count[v] ?: 0) + 1
                }
                if (count.isEmpty()) continue
                val top = count.values.max()
                if (count.count { it.value == top } > 1) return true
            }
            return false
        }

        /**
         * 天赋面板**行距兜底常量**（帧 px）。⚠️ 只作 [talentRowPitch] 推不出来时的回退：
         * 各档行距不同（3200/2560 实测 151~153、2244 实测 113/114）⇒ **不要**直接用它做位移。
         */
        const val TALENT_ROW_PITCH_PX = 153

        /**
         * 命之座页**重导航**次数与等待（ms）：页签级判据认定"不在命之座页"时，重击左菜单「命之座」。
         * 实测 cver14 Skirk 命之座页没切过去（读到属性页 ⇒ 误判 c6）。
         */
        const val CONSTELLATION_PAGE_RETRY = 2
        const val CONSTELLATION_RENAV_MS = 1300L

        /**
         * 角色名**未命中**（词典+别名+旅行者规则全落空）时的重读次数与间隔。
         * 实测（cver12）鹿野院平藏的 name ROI 首读为 `'.'`（level 也读成 `'.'` ⇒ 0）⇒ 直接判失败 ⇒ 重读救回。
         */
        const val CHAR_NAME_MAX_TRIES = 3
        const val CHAR_NAME_REREAD_MS = 400L

        /**
         * #107 装配后复核的重试参数。换装是**服务端**往返（跨角色还要先点确认弹窗），
         * 比本地 UI 慢 ⇒ 单次 [CLICK_SETTLE_MS] 不够，读到空也不能立刻判没生效。
         */
        const val EQUIP_VERIFY_TRIES = 3
        const val EQUIP_VERIFY_SETTLE_MS = 400L

        /** 等级读数**可信下限**：低于此值视为"丢位欠读"⇒ 触发重读（不参与取值，取值只走 max）。 */
        const val CHAR_LEVEL_MIN_PLAUSIBLE = 10

        /** 天赋等级**合法上界**（基础 10 + 命座 3 = 13）—— 用于「下移一行」补读结果校验。 */
        const val TALENT_LEVEL_MAX = 13

        /**
         * 命座页**稳定轮询**参数：切到「命之座」后有 ~2.5s 交叉淡入（节点 ROI 会吃到上一页彩色内容
         * ⇒ 饱和度被单向抬高）⇒ 每 [CONSTELLATION_POLL_GAP_MS] 采一次，相邻两次**逐节点差
         * ≤ [CONSTELLATION_STABLE_TOL]** 即认定已稳定并采用；打满 [CONSTELLATION_POLL_MAX] 次
         * 仍未稳定 ⇒ 兜底取「首帧 vs 末帧」的**逐节点 min**（污染单向 ⇒ min 是安全方向）。
         * 成本：稳定时仅 2 次采样（+400ms），未稳定时最多 8 次（+2.8s）。
         */
        const val CONSTELLATION_POLL_MAX = 8
        const val CONSTELLATION_POLL_GAP_MS = 400L
        const val CONSTELLATION_STABLE_TOL = 4

        /**
         * 命座节点**点亮判据**：节点中心区**平均饱和度**（max−min，0~255）的**逐节点阈值**。
         * 点亮 = 彩色技能图标（S 高）；未点亮 ⇒ S 低。
         * ⚠️ 2026-09-30 #124 订正：未点亮节点的**锁图标是金色的**，正中心 S 最高 —— 采样的不是节点中心，
         *   而是 `panels.char_constellation.nodes` 那套**偏上暗背景**的点（详见同段 nodesNote）。
         * ★ 本值是 **2560 档基准**；`roi` 是绝对像素、不随档缩 ⇒ 暗节点底噪随档变，
         *   各档可用 `panels.char_constellation.satMin` 覆盖（2244 = [20,35,41,21,21,24]）。
         *
         * ★ 2026-09-17 **最终（五轮合并 + 最大余量）GT 监督拟合** —— 样本 = cver13~17 五轮稳定读数
         *   （首读优先；剔奇偶 Manekin/Manekina；剔 Skirk 错页离群 max>80 且 GTc=0）共 **448** 个：
         * | 节点 | 阈值 | 点亮范围 | 未点亮范围 | 余量(下/上) |
         * |---|---|---|---|---|
         * | #0 | **16** | 20-57 | 2-12 | 4 / 4 |
         * | #1 | **19** | 22-60 | 3-17 | 2 / 3 |
         * | #2 | **31** | 33-76 | 9-30 | 1 / 2 ⚠️ |
         * | #3 | **16** | 20-61 | 2-12 | 4 / 4 |
         * | #4 | **14** | 16-65 | 3-12 | 2 / 2 |
         * | #5 | **19** | 25-72 | 4-14 | 5 / 6 |
         * ⇒ 节点级 **2688/2688 = 100%**，角色级 **448/448 = 100%**（五轮各自 0 错，唯一例外 = Skirk 错页）
         * 🔴 **阈值取「间隙中点」而非「任一最优值」**：同样是 100% 准确率，取中点 = **最大余量**。
         *   实测教训：旧法（取第一个达最大准确率的 t）把 #5 定成 15 ⇒ cver17 Skirk 的未亮点读到 **14** 就翻判 ✗；
         *   取中点 19 后余量 5/6 ⇒ 抗跨轮抖动（|Δ| 中位 2）。
         * ⚠️ **样本轮数决定可信度**：2 轮拟合 → cver15/16 冒出"未亮点=12" ⇒ 误判；**必须 ≥4~5 轮**。
         * ⚠️ #2 余量仅 1/2（未亮 30 / 亮 33）：该特征对 #2 最弱 —— 若要再加固需换特征
         *   （饱和度受**元素背景色**影响：同为 c6 全亮，火元素 34-49、冰元素 58-99）⇒ 待办：
         *   改"节点环的红度 / 环带亮度中位"。
         * ⚠️ 单阈值（曾试 40）不可行：各节点"点亮"下界低至 16~25 ✗（漏判）—— 必须**逐节点**阈值
         *   （GOODScanner 同做法）✓
         */
        val CONSTELLATION_SAT_MIN = intArrayOf(16, 19, 31, 16, 14, 19)

        /** 翻页后、**抓卡格帧**前的固定等待（让列表惯性停稳；仅影响跨页指纹，不影响点击）。
         *  ⚠️ A40 起 weapon_backpack 走 [awaitCrossPageSettle]（锚位移判稳），本值仅作该档
         *  无 landingBand 几何时回退 + 非 weapon 网格的未来接入点。 */
        const val CROSS_PAGE_SETTLE_MS = 250L

        // ── A40（#147）跨页锚位移判稳参数（见 [awaitCrossPageSettle]）──
        /** 相邻两帧锚位移的稳定阈值（帧 px）：静止列表模板匹配读数 0~1px（量化+采集抖动），
         *  3 ⇒ 残余慢漂 ≥30px/s（@100ms 轮询）即被拒。真机如需放宽/收紧，先对拍实测锚读数分布。 */
        const val ANCHOR_SETTLE_MAX_DY_PX = 3
        /** 需连续多少**对**帧位移达标才算稳（2~3 帧口径；3 = 容忍单帧抖动两次）。 */
        const val ANCHOR_SETTLE_STABLE_PAIRS = 3
        /** 锚位移轮询步长（ms）。 */
        const val ANCHOR_SETTLE_POLL_MS = 100L
        /** 判稳预算上限（ms）：锚长期不可测（Reject/几何不足）时不白烧，超时按原定时语义继续。 */
        const val ANCHOR_SETTLE_MAX_MS = 1200L
        /** 位移搜索窗（条带位置 ± px）：相邻帧位移有限，无需全屏搜索（也降低孪生峰概率）。 */
        const val ANCHOR_SETTLE_WINDOW_PX = 64
        const val PANEL_FP_POLL_MS = 120L

        /**
         * 面板指纹闸门**总开关（true = 已接入；卡格指纹条件化后不会再白等）**。
         *
         * ⚠️ 2026-09-16 真机两轮实测结论：
         * - 当"重复件"用时 ✗：把"面板未变"当重复直接跳过解析 ⇒ 导出 **937 → 817（丢 120 件）** ✗✗
         * - 当"加载闸门"用时 ✗：语义正确（等新面板渲染），但**我方半页重叠是预期行为** ⇒
         *   重复格的面板本来就"不变" ⇒ 每格白等 `PANEL_FP_WAIT_MS` ⇒ **吞吐崩**（5 分钟仍在第 1 页）
         *
         * 正解（GOODScanner 原设计）：**去重放在"点击前"** —— `detect_grid_duplicates` 比**网格卡格**像素，
         * 判为重复格则**根本不点**（既不等待也不跑 OCR）；面板 snapshot 只负责"等加载完"。
         * 该实现待做（`PanelFingerprint` 已具备像素抓取/比较能力，复用即可）。
         */
        const val PANEL_FP_GATE_ENABLED = true

        /** 档位吸附容差（1 位小数显示值的 OCR 抖动级）：超此差值**不吸附**，保留画面原值。 */
        const val SNAP_TOL = 0.06

        /**
         * 逐格打印面板**原始 OCR 文本**（诊断用）。
         * 用途：定位"读失败件" —— 跑完按目标件的特征值搜日志，看当时面板读成了什么
         * （整行丢 / 数字错 / 读到上一件的面板）。定位完可关（false）。
         */
        const val PANEL_RAW_DUMP = true

        /**
         * 翻页滑动的**终点 y 到底边至少留出的余量**（帧 px）。
         *
         * 起点是固定值（几何 `advanceStart` 或 profile 字面 `advance.from`），命令距离过大就会把
         * 终点算到屏幕外 ⇒ 手势被系统钳制、实际更短。
         * 故命令距离上限 = `起点 y − 本余量`（`touchScale` 放大注入像素时同样受此约束）。
         */
        const val ADV_MIN_END_Y = 60
        const val MAX_ROSTER_PAGES = 50
        /** §14 P1：筛选面板翻页上限（防指纹判据失效时死循环）。 */
        const val MAX_FILTER_PAGES = 60

        /**
         * 套装筛选列表的**翻页次数上限**（2026-09-18 P0③）。
         * 对齐 GOODScanner `ui_actions.rs` 的 `max_scrolls = 5`：用固定预算代替
         * "指纹不变 ⇒ 到底"判据 —— 后者会被异步手势骗（见 setFilter 内注释），
         * 是"只走一页就退出"的真机根因。
         * 依据：列表共 56 套 / 每页 16 套 = 3.5 页 ⇒ 5 页有余量。
         */
        const val MAX_FILTER_PAGE_TURNS = 5

        /**
         * ★ A41（#147）：套装筛选**整页读数自洽度**下限 —— 判"这页读稳了没"的内容判据。
         *
         * 定义：`认出套装名的行数 / 读到非空文本的行数`。套装子面板列的是**全部**套装，
         * 正常页应接近 1.0；残余慢漂（实测 ~1-2px/120ms，24×16 缩略图量化不出来）把 90px
         * 行带切成跨行 ⇒ 整页"自信乱码"⇒ 比值塌向 0。
         * 取 0.75 而不是 1.0：留 4 行余量给个别读坏但仍算稳定的行，避免正常页白重读；
         * 灰化套（count=0）读成**空**，不进分母 ⇒ 底部那页不会被这条判据误伤（那是 #147 ⑥ 的另一条洞）。
         */
        const val FILTER_PAGE_CONSISTENCY_MIN = 0.75f

        /** 自洽度不达标时**重读本页**的次数与间隔（ms）。间隔取天赋重读同一档节奏 700ms。 */
        const val FILTER_PAGE_CONSISTENCY_RETRY = 2
        const val FILTER_PAGE_CONSISTENCY_WAIT_MS = 700L
        /**
         * §14 flow 未声明 `dict` 时的回落词典（**仅作默认**，flow 已声明一律以声明为准）。
         * 与 `GoodNames.kindOf` 的键名一致。
         */
        const val DEFAULT_SET_DICT = "mappings.artifactSets"
        const val DEFAULT_CHAR_DICT = "mappings.characters"
        /**
         * ⚠️ 2026-09-16 GT 定标（旧两条常量 MAX_SUBS_5STAR=4 / MAX_SUBS_4STAR=3 **已删除**）：
         * 按稀有度猜条数与真值矛盾 —— GT 实测 5★+0 有 67% 只有 3 条、4★+16 100% 是 4 条
         * ⇒ 一多（幻影第 4 条）一少（砍掉真第 4 条）两类错。现由 [StatParser.parseBlock]
         * 的连续块读取 + [StatParser.MAX_SUBS] 上限统一处理，不再按稀有度截断。
         * 条数真值校验见 design-docs/good-diff-20260916.md。
         */
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
            // 武器扫描开局归零：网格右下角「↑↓」排序方向钮（点两下强制重排+回顶）；缺该 profile 键⇒无字面坐标⇒安全跳过
            "weaponSort" to "screens.weapon_backpack.sort",
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
            // exit 步骤的右上角返回/关闭钮（2026-09-29 #132）：flow 字面 [2913,41] 是 3200 占位，
            // 在 2560 档 x=2913 **在屏外**（宽只有 2560）⇒ 点了没反应，整轮跑完人还留在圣遗物管理页。
            // 缺该 profile 键的档位（3200/2244 尚未实测）⇒ 照旧回落字面。
            "return" to "screens._common.returnBtn",
        )
    }
}
