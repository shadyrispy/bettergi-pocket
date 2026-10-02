package com.bettergi.pocket.scan

import android.os.SystemClock
import android.util.Log
import com.bettergi.pocket.capture.FrameSource
import com.bettergi.pocket.capture.FrameTimeoutException
import com.bettergi.pocket.log.RecognitionLog
import com.bettergi.pocket.notice.NoticeCenter
import com.bettergi.pocket.recognition.IntRect
import com.bettergi.pocket.recognition.name.GoodNames
import com.bettergi.pocket.recognition.name.NameMatcher
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs

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

    /**
     * 脚本用 `notify` 原语推出的重点信息（P4）。
     * 默认空实现 ⇒ 老的实现类不必改；展示由宿主决定（本应用走 NoticeCenter）。
     */
    fun onNotice(level: String, text: String) {}
}

/** 扫描会话变量（flow vars + visit 产物）。 */
class ScanVars {
    var total: Int? = null
    /** 计数器所在的 rect 路径；`pagedGrid` 收尾前要凭它复读一次（见 `recountCounter`）。 */
    var totalRect: String? = null
    var gridLocked: Boolean? = null
    var crafted: Boolean = false
    var rarity: Int = -1
    var locked: Boolean? = null
    var favorited: Boolean? = null
    /** ocrWithRetry 命中写入（fuzzy 词典匹配 key）。 */
    var ocrMatch: String? = null
    /** vote as=curLock 快照（artifact_lock ifMatch 的 verify toggle 依据）。 */
    var curLock: Boolean? = null
    /**
     * `parsePanel.match` 的求值结果（★ 2026-09-18 新增）。
     *
     * 为什么必须补：`match` 此前**从未被消费** —— 于是引用它的 `ifMatch` 只能写成无条件，
     * 锁定流程就会对**任何**读到"未锁"的格子点锁钮，包括根本不在计划里的 4★ 件
     * （真机实测：套装过滤后走到该套装的 4★ 件便开始点击，靠 `verify FAILED` 才没落地）。
     * 现在由 flow 写 `ifMatch when="panelMatched"` 门控 ⇒ 只对命中目标动作。
     */
    var panelMatched: Boolean = false
    /**
     * 结果契约（★ 2026-09-18，对齐 GOODScanner 的 `InstructionStatus`）：**按计划项**记录
     * ① 是否命中（parsePanel.match）② 是否尝试过写入 ③ 写入后判据是否通过。
     * 由 `foreach` 每项开头复位、末尾汇集，最终出 `ManageSummary`。
     */
    var matchHit: Boolean = false
    var actTried: Boolean = false
    var actOk: Boolean = false

    /**
     * #107：**动作发出之后**有没有拿到"确实生效"的独立证据。
     * `null` = 该路径不复核 / 复核读不出；`true` = 复核确认生效；`false` = 复核确认**没**生效。
     *
     * 为什么必须和 [actOk] 分开：装配链的 `actOk` 含义只是"按钮文本判出来该点、并且点了"，
     * **不含任何点击后的回读** ⇒ `Success` 实际只证明"我们点过了"。2026-09-28 实测撞上：
     * 一次报 Success 的换装，事后逐格核对服务端仍是原件（参考实现 GOODScanner 的
     * `verify_artifact_equipped` 本身就是返回 `Ok(true)` 的 no-op ⇒ 这是同源缺陷，不是我们偏离上游）。
     */
    var actVerified: Boolean? = null

    /** #107：本项是否真点过装配动作钮（= 欠一次点击后复核）。只在 `dualStateButton` 的点击支置。 */
    var equipClicked: Boolean = false

    /**
     * **只有锁定流程**动过锁钮。[actTried] 原本兼任此职，但 #93 让装配链也开始置它 ⇒
     * 必须分开，否则装配点一次「替换」就会让 `parsePanel` 里的加锁确认框探测
     * （`if (actTried) dismissLockConfirm(...)`）在**装配流程**里跑起来 —— 那是"锁写入后才可能
     * 出现的框"，在装配流程里探测它等于在浅色画面上误判并盲点一下确认。
     */
    var lockWriteAttempted: Boolean = false
    /**
     * 本项这一趟 pagedGrid 是**网格到底**（连续重复 ⇒ 回卷/耗尽）结束的，不是命中目标结束的。
     * 与 `stopReason` 分开记，因为回卷止扫与命中止扫历史上共用 `"stopWhen"` 一个字串，
     * 改它会动到 `scan finished: <reason>` 日志与解析它的脚本。
     */
    var gridExhausted: Boolean = false

    /**
     * **绑定表**（★ 2026-09-18，单趟扫描用）：已绑定（处理过）的计划项下标。
     *
     * 为什么需要：`artifact_lock` 原来是 `foreach{ pagedGrid }` —— **每个目标重走一遍同一张网格**，
     * 而"走到底"的判据（回卷/重复件）全是**累积式**的 ⇒ 第 2 个目标起每一件都是"本轮已入库"，
     * 走查被立刻截断（真机实测：目标 1 走 182s，目标 2 只走 64s，真件被判 NotFound）。
     * 改成**单趟扫描**后：一次走查，每格用 [planMatchedIndex] 找出它对应哪个未绑定的计划项并处理，
     * 处理完加进本集合 ⇒ 既没有"重走"，也没有"累积冲突"，耗时还从 N× 降到 1×。
     */
    val consumedPlanIndexes: MutableSet<Int> = mutableSetOf()

    /** 本格绑定到的计划项下标（-1 = 未命中）。由 `planMatch` 写入，`planDone` 消费。 */
    var planMatchedIndex: Int = -1

    /** 尚未绑定的计划项数（供 flow 写 `stopWhen expr="planRemaining == 0"` 提前收尾）。 */
    fun planRemaining(): Int {
        val total = plan?.size ?: 0
        return (0 until total).count { it !in consumedPlanIndexes }
    }

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

    /**
     * 表达式变量表 —— **唯一事实源**：`stopWhen` 与 `ifMatch` 都读这一份。
     *
     * ⚠️ 2026-09-18 统一：此前存在**两份** exprVars（`ScanVars` 只给 rarity/level/total，
     * `ScanEngine` 另给一份更全的），于是同一个表达式在 `stopWhen` 与 `ifMatch` 里结果可能不同
     * （例如 `locked`/`curLock` 在 stopWhen 里恒为 0），属"同一实体两处真值"类缺陷。现只留这一份。
     *
     * ⚠️ `Expr.lookup` 对**缺失变量返回 0**（不抛异常）⇒ 变量名写错是**静默失效**（判据永不触发），
     * 因此这张表要尽量全；新增引擎变量必须同步加在这里。
     */
    fun exprVars(): Map<String, Any?> = mapOf(
        "total" to (total ?: 0),
        "rarity" to rarity,
        "level" to level,
        "locked" to (locked ?: false),
        "gridLocked" to (gridLocked ?: false),
        "favorited" to (favorited ?: false),
        "crafted" to (crafted ?: false),
        "curLock" to (curLock ?: false),
        // 锁定**意图**（{lock,unlock} 清单归一出的项内 wantLock；缺省 true = 应锁定）
        "wantLock" to (currentTask?.optBoolean(GoodPlan.KEY_WANT_LOCK, true) ?: true),
        // 本条 parsePanel 是否命中当前计划项（parsePanel.match 的求值结果）
        "panelMatched" to panelMatched,
        "stopRequested" to stopRequested,
        "charDupStreak" to charDupStreak,
        // 单趟扫描：剩余未绑定的目标数（flow 用 `stopWhen expr="planRemaining == 0"` 提前收尾）
        "planRemaining" to planRemaining(),
        "planTotal" to (plan?.size ?: 0),
        // 计划派生量（函数式表达式经文本替换后引用；缺项时给 0 即"判据不生效"，方向安全）
        // （ScanVars 内就地算，避免依赖 ScanEngine 的私有方法）
        "targetMaxLevel" to (plan?.mapNotNull { t -> t.optInt("level", -1).takeIf { it >= 0 } }?.maxOrNull() ?: 0),
        "targetMinRarity" to (plan?.mapNotNull { t -> t.optInt("rarity", -1).takeIf { it > 0 } }?.minOrNull() ?: 0),
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
    /**
     * #105：玩家自定义昵称 → GOOD key。空表 = 完全按词典/规则走（单测与离线跑就是这个默认）。
     */
    private val nameOverrides: NameOverrides = NameOverrides.EMPTY,
    private val listener: ScanListener,
    private val dedupe: Boolean = true,
    /**
     * #83：**前台闸门**探针 —— 返回 false = 当前前台不是原神 ⇒ 整轮干净收尾。
     * 默认 null = 不判（单测/离线跑不接无障碍）。
     *
     * 为什么要在引擎侧再判一次（注入侧已逐段判）：注入侧的闸门只能"拦住这一次动作"，
     * 拦下之后引擎并不知道发生了什么，会继续按同一个页面重试/回读 ⇒ 白烧格与时间；
     * 而用户口径是**游戏切后台本来就会断线重登** ⇒ 这一轮已经没有继续的意义，应当立刻停轮报出来。
     */
    private val foregroundOk: (() -> Boolean?)? = null,
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
    /**
     * §12.1 **翻页起点**选择：true=几何推导卡缝起点（`advanceStart`），false=profile 字面
     * `advance.from`。**只影响起点**——距离规划 / 残差记账 / 触摸增益补偿对两条路一律生效
     * （2026-09-24 前这里还顺带关掉了整套控制律，见 pagedGrid 主滑处的说明）。
     */
    /** ⚠️ 2026-09-17 默认 false（几何起点在 BlueStacks 上实测致滚动截断，见 TriggerForegroundService）。 */
    private val useGeometryAdvance: Boolean = false,
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
    private val onLockConfirmAbsentLearned: () -> Unit = {},
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
        // ★ 2026-09-24：观察类计数在此复位。它们的 KDoc 一直写着"随 run 重置"，但此前**没有任何复位点**
        //   ⇒ 同进程连跑两轮时第二轮的 `scan finished` 摘要会把上一轮的数一起算进去。
        dupRevisits = 0; dupRevisitRecovered = 0; pageFreezeAbandoned = 0; swallowedClickRetries = 0
        clickGiveups = 0; weaponStaleDropped = 0
        weaponOverlapRepeats = 0; weaponIdentityRuns.clear(); lastEmittedWeaponIdentity = null
        unknownSetPieces = 0
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
            } catch (e: Exception) {
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
                "| 行级闭环 判跳行=$rowCheckSkips 判滑空=$rowCheckStalls " +
                "吞击重发=$swallowedClickRetries 点击放弃=${clickGiveups}格 定点重访=$dupRevisitRecovered/$dupRevisits " +
                "武器陈旧丢弃=${weaponStaleDropped}件 " +
                // ★ #104：只观测、已照常入库 —— 对账"多 N 件"时先看这个数，别把它算成丢件或真多出来的装备
                (if (weaponOverlapRepeats > 0) "武器重叠重读=${weaponOverlapRepeats}件(已入库) " else "") +
                "页级冻结放弃=${pageFreezeAbandoned}格 未知套装=${unknownSetPieces}件 " +
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
    private fun notifyStep(step: JSONObject) {
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

    /**
     * 装备者显示名 → GOOD key：#105 的用户昵称表优先，其次官方词典（含模糊）。认不出 ⇒ null。
     * 三处消费（武器 location / 圣遗物 location / 装配后复核）必须同一口径，否则同一个名字在
     * 导出里是一个键、在回执里是另一个键，对账时无从归因。
     */
    private fun characterKeyOfDisplay(raw: String, dict: JSONObject?): String? =
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
    private suspend fun verifyEquippedBy(expectChar: String, dict: JSONObject?): Boolean? {
        val gateway = ocr ?: return null
        val arr = profile.rawObject("panels.artifact_manage")?.optJSONArray("equipped") ?: return null
        if (arr.length() < 4) return null
        val r = profile.scaleRect(arr.getInt(0), arr.getInt(1), arr.getInt(2), arr.getInt(3))
        var last = EquipVerify.NOTHING_READ
        var lastOwner: String? = null
        for (attempt in 0 until EQUIP_VERIFY_TRIES) {
            val text = runCatching {
                val f = freshFrame()
                try {
                    gateway.readLines(f, listOf(r)).joinToString(" ")
                } finally {
                    f.release()
                }
            }.getOrNull()
            lastOwner = equippedOwnerOf(text)
            last = equipVerifyOf(lastOwner, expectChar) { characterKeyOfDisplay(it, dict) }
            // ⚠️ 连 NOT_APPLIED 也要重试：换装是服务端往返，回读窗口里那一格**可能还写着上一个
            //   持有者**（目标件原本穿在行秋身上时尤其明显）⇒ 首读见到"别人"不能立刻判失败。
            if (last == EquipVerify.APPLIED) {
                if (attempt > 0) Log.i(TAG, "verifyEquip: 第 ${attempt + 1} 次回读才确认 '$expectChar'")
                return true
            }
            if (attempt < EQUIP_VERIFY_TRIES - 1) delay(EQUIP_VERIFY_SETTLE_MS)
        }
        Log.w(
            TAG,
            "verifyEquip: ${EQUIP_VERIFY_TRIES} 次回读仍未确认（装备者栏='${lastOwner ?: "<空>"}' " +
                "期望 '$expectChar'，判据=$last）⇒ " +
                if (last == EquipVerify.NOT_APPLIED) "记 Failed" else "记 ClickedUnverified（没看见，不判成败）",
        )
        return last.verifiedFlag()
    }

    /**
     * 读左下动作钮（`screens.artifact_manage.leftBtn`）的文本 —— **#93 的真回执判据**。
     *
     * 判据来源：GOODScanner `ui_actions.rs::click_equip_button_safe_at` 就是 OCR 这颗钮
     * （`SEL_ACTION_BUTTON_RECT`），含「卸」⇒ 已装备、不点；含「装/替」⇒ 点。
     * 我们这边 profile 的 `leftBtn.states` 登记了同样两态（「替换」/「卸下」）。
     *
     * 读失败一律返回 `null`（调用方走 `EquipAction.UNKNOWN` ⇒ **不点**、记 Failed）——
     * 该 ROI 只有 ~94×54 帧像素。
     * ★ 2026-09-28 BlueStacks@3200 真机已验**读得出来**：目标件正穿在该角色身上时读到
     *   `按钮='卸下'`（`dualStateButton … 不点击 ⇒ AlreadyCorrect`），整步耗时 11ms ⇒ 判据可用、
     *   代价可忽略。⚠️ 但「替换」那一侧**尚未在设备上验过**（跑它会真换装，需授权）⇒
     *   不能拿"读不到"当"没装"，也不能因为这条 ROI 在 3200 档读得动就假定别的档位同样读得动。
     */
    private suspend fun readActionButtonText(rect: FrameRect): String? {
        val gateway = ocr ?: return null
        val frame = try {
            freshFrame()
        } catch (e: Exception) {
            Log.w(TAG, "readActionButtonText: 取帧失败（${e.message}）")
            return null
        }
        return try {
            val raw = gateway.readLines(frame, listOf(rect)).joinToString(" ")
            StatParser.clean(raw).takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "readActionButtonText: OCR 失败（${e.message}）")
            null
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
                // ★★ #93：这颗钮的文本就是**真回执**（对齐 GOODScanner
                //   `ui_actions.rs::click_equip_button_safe_at`：先 OCR 按钮文本，含「卸」⇒ `AlreadyCorrect`
                //   且**不点**；含「装/替」⇒ 点它 ⇒ `Success`）。
                //   此前不读文本、闭眼点 + 不置任何状态 ⇒ 结果标签与实际做的事无关。
                val btnText = readActionButtonText(r)
                val decision = equipDecisionOf(equipActionOf(btnText))
                if (!decision.click) {
                    // 两条"不点"的出口语义相反，日志必须分开打（否则 AlreadyCorrect 看起来像失败）：
                    //  · 卸 ⇒ 目标件已在此角色身上，这是**正常态**，什么都不做 ⇒ AlreadyCorrect。
                    //  · 读不出 ⇒ **也不点**（对齐参照实现：ui_actions.rs:2076 "Neither detected — bail"）。
                    //    这一档曾经过"照点、只是不置回执"，那正是本案最危险的形态：按钮其实写着「卸下」、
                    //    只是 OCR 没读出来，点下去就把该角色的这件装备**卸掉了**，标签还停在 AlreadyCorrect。
                    //    记 actTried 不记 actOk ⇒ 落 Failed：宁可 loudly 失败，也不在判不出该不该动的时候
                    //    动账号数据。
                    if (decision.actTried) {
                        Log.w(
                            TAG,
                            "dualStateButton ref=$path0: 按钮文本判不出语义（读到='$btnText'）⇒ **不点击**，" +
                                "本项落 Failed（不记 Success/AlreadyCorrect）",
                        )
                    } else {
                        Log.i(
                            TAG,
                            "dualStateButton ref=$path0: 按钮='$btnText'（卸下）⇒ 目标件已在该角色身上，" +
                                "不点击 ⇒ AlreadyCorrect",
                        )
                    }
                    vars.actTried = decision.actTried
                    return
                }
                Log.i(TAG, "dualStateButton ref=$path0: 按钮='$btnText'（替换/装备）⇒ 点击 (${r.centerX},${r.centerY})")
                actions.click(r.centerX, r.centerY)
                delay(CLICK_SETTLE_MS)
                // ★ 回执（#93 → #107）：标签由 `foreach` 的状态映射按
                //   (matchHit, actTried, actOk, actVerified) 得出。
                //   `matchHit` 已由本格的 `stopWhen expr="panelMatch(...)"` 置（网格确实选中了目标件）。
                //   走到这里 = 按钮写着「装/替」⇒ 动作**该发**且已发出 ⇒ actOk。
                //   ⚠️ actOk **不等于**装上了。#107 实测过这个脱钩：报 Success 的换装，事后逐格核对
                //      服务端仍是原件。真正的确认要等**换装确认弹窗那一步走完**之后回读装备者栏，
                //      所以这里只登记"欠一次复核"，由 foreach 末尾去读（见 equipClicked）。
                //      跨角色的 `dialog(equipConfirm)` 也在复核之前 ⇒ 确认框没点掉就会落在
                //      ClickedUnverified/Failed，不会再像 #93 之前那样照样记 Success。
                vars.actTried = decision.actTried
                vars.actOk = decision.actOk
                vars.equipClicked = true
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
        vars.totalRect = step.optString("rect").removePrefix("$").ifEmpty { null }
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

    /**
     * 收尾前的计数器复读。只用于"把 968 读成 96/668"这类**非零欠读** —— 那种读数原先没人
     * 再核一次，`collected >= total` 一满足就静默提前收尾，而总数不符告警在 stopRequested
     * 分支被刻意跳过，于是少扫一整屏也报"完成"。
     */
    private suspend fun recountCounter(): Int? {
        val path = vars.totalRect ?: return null
        val gateway = ocr ?: return null
        return try {
            val frame = freshFrame()
            try {
                gateway.readNumber(frame, profile.rect(path))
            } finally {
                frame.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "计数器复读失败 ⇒ 沿用首次读数 ${vars.total}", e)
            null
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
            val skip = pageSkipExpr != null && pageMinLevel != null && runCatching {
                Expr.eval(
                    pageSkipExpr,
                    mapOf(
                        "pageMinLevel" to pageMinLevel!!,
                        "targetMinRarity" to (minTargetRarity() ?: 0),
                        "targetMaxLevel" to (maxTargetLevel() ?: Int.MAX_VALUE),
                    ),
                )
            }.getOrDefault(false)
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
            if (pageNo > 0 && gridKey == "weapon_backpack") delay(CROSS_PAGE_SETTLE_MS)
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
            val pageCellFrame = runCatching { freshFrame() }.getOrNull()
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
                            Log.i(
                                TAG,
                                "格级: page=$pageNo row=${idx / cols} col=${idx % cols} idx=$idx global=$g key=" +
                                    (if (k.isNullOrEmpty()) "**空(未入库)**" else "h${k.hashCode() and 0xFFFF}") +
                                    " item=" + (pageIdentities.lastOrNull()?.takeIf { it.isNotEmpty() } ?: "-") +
                                    " appeared=" + (if (pageAppeared.isEmpty()) "-" else pageAppeared.last()?.toString() ?: "无闸门") +
                                    " gridChanged=$gridCellChanged",
                            )
                        }
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
            runCatching { pageCellFrame?.release() } // 页级卡格帧用完即释放（防 Mat 泄漏）
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
                runCatching {
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
            // ★ 2026-09-18 改为**中止整轮**（原来是只 return ⇒ 后续步骤照跑，带着**上一个角色**继续导航/换装，
            //   2026-09-18 真机事故：一路点漂到「确认退出游戏」）。角色选不对 ⇒ 后面做什么都是错的。
            Log.e(TAG, "rosterFind: 目标 '$target' 未在名册找到 ⇒ 中止整轮（绝不带错角色继续）")
            NoticeCenter.error("未在角色名册找到「$target」，已中止本轮（避免换装到错角色）")
            vars.stopRequested = true
            vars.stopReason = "rosterMiss"
            return
        }
    }

    private fun nameMatches(got: String, target: String): Boolean {
        if (StatParser.clean(got) == StatParser.clean(target)) return true
        // ★ 2026-09-18 修：GoodNames 以**中文名**为键，而 flow 的 `$task.char` 来自 GOOD 计划的
        //   `location`，是**英文键**（`Shenhe`）⇒ 原写法 `names?.match(target)?.key ?: return false`
        //   会**直接返回 false**，于是目标角色**永远匹配不上**（真机实测：rosterFind 从未命中，
        //   却因只 `return` 而**带着错角色继续往下跑**，最终会把件装到别人身上）。
        //   修法：查不到就**把 target 自身当作 key 用**（下面 cleaned.equals(tk) 是大小写不敏感比较）。
        val tk = names?.match(target, GoodNames.Kind.CHARACTER, true)?.key
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
    /** 卡片**选中框**签名（点击是否落到卡片上）：见 [cardFrameMoved]。 */
    private val sigCardBefore = ByteArray(SIG_LEN_MAX)
    private val sigCardCur = ByteArray(SIG_LEN_MAX)

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
    private var dupLimitEffective = 0

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
    private var advGainEma = 0.91

    // ── ★ 行级闭环（2026-09-16 方案 C）：用内容键量「上一页→本页」前进量，判跳行/判滑空并计数 ──
    /** 每格计算出的内容键（含重复件）；页末收集成本页键序列。 */
    private var lastCellKey: String? = null

    /** 上一页的键序列（**含读失败的空串占位**，下标 = 页内序号 —— 占位必须保留，否则前进量算错）。 */
    private var rowCheckPrevKeys: List<String> = emptyList()

    /** 行级闭环统计（进 scan finished 摘要）：判跳行 / 判滑空。 */
    private var rowCheckSkips = 0
    private var rowCheckStalls = 0

    /** 单件名→套装反查未命中的件数（词典缺口，件本身已照常入库；见 emit 处的拆分判据）。 */
    private var unknownSetPieces = 0

    /** 重读期抑制「连续重复」计数：重访/回读必然重读到刚记过的件，会被回卷判据误判成"整页零新增"。 */
    private var suppressDupStreak = false

    /** scope=cell 止扫（3★/2★）的**连续命中计数**：单格误读不得截断整轮。 */
    private var stopWhenCellStreak = 0

    /**
     * ★★ 2026-09-17 用户方案：**格级"件身份"记录** ★★
     * 记下"第 page 页第 idx 格（idx=row*7+col）读到的**是哪一件**"，
     * 格式 `set/slot/lvl/main#v1,v2,v3,v4`（词条数值升序）⇒ 可与 GT **直接对齐**（之前只存键哈希，无法对齐 ✗）。
     * 用途（离线分析）：
     *  - **同页内出现两次的同一件** ⇒ 该格点击**偏到了相邻卡**（同页每格本该不同件；跨页重复是预期重叠 ✓）
     *  - 由此可定位"哪一格点错、它挤掉了谁" ⇒ 与 GT 缺项精确配对 ✓
     */
    private var lastCellIdentity: String? = null

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
    private var lastPanelAppeared: Boolean? = null

    /** 本轮已落盘的识别帧张数（见 [dumpPanelShot]，随 run 重置）。 */
    private var panelShotSeq: Int = 0

    /** 本轮因闸门（张数上限 / 剩余空间不足）被拒的取证张数（#46，随 run 重置）。 */
    private var panelShotDropped: Int = 0

    /** 本轮 `imwrite` 返回 false（磁盘满/编码失败）的次数 —— 这些张**不记 manifest**（#46）。 */
    private var panelShotWriteFailures: Int = 0

    /** 上次检查剩余空间时的 [panelShotSeq]；0 = 本轮还没查过（#46）。 */
    private var panelShotSpaceCheckedAt: Int = 0

    /** 上次空间检查的结论（#46）。 */
    private var panelShotSpaceOk: Boolean = true

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
    private val useTapForCell: Boolean get() = ClickModeOverrides.cellTap

    /** 本轮累计的定点重访次数 / 其中救回件数（2026-09-19 新增，随 run 重置）。 */
    private var dupRevisits: Int = 0
    private var dupRevisitRecovered: Int = 0

    /**
     * 本轮因**页级冻结**被放弃的格数（2026-09-24 新增，随 run 重置）。
     *
     * 为什么要单独计数：这些格对应的圣遗物**从未被读到**，是真实的覆盖损失。此前它藏在
     * "定点重访 0/502 救回"里无声无息 —— 全量对账（945 真值 vs 909 导出）才发现少 37 件。
     * 现在让损失在 `scan finished` 汇总里直接可见。
     */
    private var pageFreezeAbandoned: Int = 0

    /** 本轮累计的"点击被吞"重发次数（观察 BlueStacks 输入吞没窗口频率用，随 run 重置）。 */
    private var swallowedClickRetries: Int = 0
    /** 点击打满重发上限、本格将读到陈旧面板的次数（= 一件静默丢失的机会）。 */
    private var clickGiveups: Int = 0

    private var lastWeaponIdentity: String? = null
    private var weaponSameRun: Int = 0
    private var weaponStaleDropped: Int = 0

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
    private var weaponOverlapRepeats: Int = 0
    private val weaponIdentityRuns = HashMap<String, Int>()
    private var lastEmittedWeaponIdentity: String? = null

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
    private var prevAllCellFps: List<ByteArray?>? = null

    /**
     * 上一页**逐格身份串**（索引 r*cols+c）。与 [prevAllCellFps] 配对使用：
     * 跨页重叠跳过**只在"上一页那格确实入库了"（身份非空）时才允许** —— 否则上一页那件本就漏了，
     * 这一页还跳过 ⇒ 永久丢件。★ 2026-09-20（C：真机为 2 行前进、每页前 7 格是重叠）。
     */
    private var prevAllCellIds: List<String>? = null

    /** 上一页**逐格内容键**（与 [prevAllCellIds] 配对；C' 复制时连键一起带，保证行级闭环可用）。 */
    private var prevAllCellKeys: List<String>? = null

    /**
     * 本页**重叠格表**：本页格 idx → 上页同一件的格 idx。命中即"不点击、不 OCR，身份/内容键照抄"。
     * 两个生产者，各自实际只对一档网格有效（互补，不是重复）：
     * ① 页首**卡格像素**指纹比对 —— 仅武器（圣遗物禁用像素判重，见 [lastCellFp] 的 18 羽毛定谳）；
     * ② 页中**身份串**锚定 [anchoredD] —— 判据本身两档通用，但它要求上页那格**内容键非空**，
     *    而内容键 `lastCellKey` 目前只在圣遗物 emit 里赋值 ⇒ 实际只对圣遗物生效。
     * 同一格被两处都判中时以 ①（直接像素证据）为准 ⇒ ② 只用 `putIfAbsent` 写入。
     */
    private val skipCopyFrom = HashMap<Int, Int>()

    /** 本页身份锚定算出的对齐偏移 d（本页 idx m ≡ 上页 idx d+m）；null = 未锚定（全部照常访问）。 */
    private var anchoredD: Int? = null

    /**
     * 本轮已入库的「页:行:列:身份」四元组（#58② 同格重解析幂等，用法见 [parseWeaponPanel]）。
     * 与 [prevAllCellIds] 的跨页重叠判据互补：那条管"这格是上页某行的同一张卡"，这条管
     * "**同一页同一格被解析了两遍**"（吞击重发/定点重访都会让一格再解析一次）。
     */
    private val weaponCellEmitted = HashSet<String>()
    /** 当前格的行/列（供跨页 identity 比对）。 */
    private var curCellRow: Int = -1
    private var curCellCol: Int = -1
    /**
     * ★★ 本页 **idx → identity** 表（定长语义，每页清空）★★
     * ⚠️ 2026-09-17 修 bug：此前用 `pageIdentities`（**可变长**收集数组）保存跨页比对用的身份串，
     * 而**被跨页丢弃的格 `return` 早于收集** ⇒ 索引错位 ⇒ 保存的数组与 `(row,col)` 不再对应
     * ⇒ 后续比对**全部失配**（实测 `Slingshot` 同列出现 2 次未被去重 ✗）
     * ⇒ 改为按 idx **无条件**写入（早于任何判重），与 `(row,col)` 严格对应 ✓
     */
    private val curPageIds = HashMap<Int, String>()
    private var curCellIdx: Int = -1

    /**
     * 当前角色的**突破阶 0-6**（GOOD `ascension`）。
     * 由 `parseCharacterPanel` 从面板等级文本 `Lv.X/Y` 推导：优先用**上限 Y**；
     * 上限不可得时按 level 分层回退（GT 反推：70~80 ⇒ 5、81+ ⇒ 6）。
     */
    private var lastCharAscension: Int = 0

    /** 页参数镜像（`pagedGrid` 的局部变量对 `parseWeaponPanel` 不可见 ⇒ 用字段传递）。 */
    private var curPageNo: Int = 0
    private var curCols: Int = 7
    private var curTraverseRows: Int = 3

    /** ★ 面板指纹快照（GOODScanner `panel_snapshot`）：上次**稳定**面板的原始像素（仅作加载闸门）。 */
    private var panelFpSnapshot: ByteArray? = null

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
    private var lastCellFp: ByteArray? = null

    /** 本格卡格指纹是否与上一格不同（决定面板闸门要不要等）。 */
    private var gridCellChanged = true

    /**
     * **会话级看门狗心跳**（2026-09-16）：最后一次"确实有进展"的时间戳（`SystemClock.elapsedRealtime()`）。
     * 由 [freshFrame] 打点 —— 覆盖"每格抓帧 + settle 轮询"两条主路径 ⇒
     * **真的卡死（JNI/a11y/MediaProjection 阻塞）时它不会前进**，而只是在慢（如长 settle）时会前进 ✓。
     * 监视方在 `ScriptRunner`（独立 daemon 线程）⇒ 专治**visit 内部挂死**（页级看门狗只在格与格之间检查，抓不到）。
     */
    @Volatile
    var lastProgressAtMs: Long = 0L
        private set

    /**
     * 覆盖率仪表（2026-09-16）：当前页首格在列表里的**全局序号**（按实测前进量累加）。
     * 只靠"每页新增件数"看不出位置；有了全局序号才能看出"哪一段从没被点到"。
     */
    private var rowCheckGlobalStart = 0

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
    /**
     * 逐角色**命座对天赋的加成表**（2026-09-17，Irminsul GT 监督反推）
     * 天赋面板显示的是【含加成值】，而 GOOD/GT 是【基础值】
     * 用法：talents[i] = max(0, 面板值 - bonus[key][c][i])（0=auto 1=skill 2=burst）
     * 来源：真机 92 角色（面板值 − GT 基础值）逐项取正；负值项 = OCR 读失败已置 0；训练集 167/174 = 96.0%
     */
    private val TALENT_BONUS: Map<String, Map<Int, IntArray>> = mapOf(
        "Aino" to mapOf(6 to intArrayOf(0,3,3)),
        "Alyosha" to mapOf(3 to intArrayOf(0,3,0)),
        "Barbara" to mapOf(6 to intArrayOf(0,3,3)),
        "Beidou" to mapOf(6 to intArrayOf(0,3,3)),
        "Bennett" to mapOf(6 to intArrayOf(0,3,3)),
        "Candace" to mapOf(6 to intArrayOf(0,3,3)),
        "Charlotte" to mapOf(6 to intArrayOf(0,3,3)),
        "Chevreuse" to mapOf(3 to intArrayOf(0,3,0)),
        "Chongyun" to mapOf(5 to intArrayOf(0,3,3)),
        "Collei" to mapOf(6 to intArrayOf(0,3,3)),
        "Dahlia" to mapOf(6 to intArrayOf(0,3,3)),
        "Dehya" to mapOf(3 to intArrayOf(0,0,3)),
        "Diluc" to mapOf(5 to intArrayOf(0,3,3)),
        "Diona" to mapOf(6 to intArrayOf(0,3,3)),
        "Dori" to mapOf(6 to intArrayOf(0,3,3)),
        "Faruzan" to mapOf(6 to intArrayOf(0,3,3)),
        "Fischl" to mapOf(6 to intArrayOf(0,3,3)),
        "Freminet" to mapOf(6 to intArrayOf(3,3,0)),
        "Gaming" to mapOf(6 to intArrayOf(0,3,3)),
        "Gorou" to mapOf(6 to intArrayOf(0,3,3)),
        "Ifa" to mapOf(4 to intArrayOf(0,3,0)),
        "Illuga" to mapOf(6 to intArrayOf(0,3,3)),
        "Jahoda" to mapOf(6 to intArrayOf(0,3,3)),
        "Kachina" to mapOf(4 to intArrayOf(0,3,0)),
        "Kaeya" to mapOf(3 to intArrayOf(0,3,0)),
        "Kaveh" to mapOf(3 to intArrayOf(0,0,3)),
        "Keqing" to mapOf(3 to intArrayOf(0,0,3)),
        "Kirara" to mapOf(3 to intArrayOf(0,3,0)),
        "KujouSara" to mapOf(6 to intArrayOf(0,3,3)),
        "KukiShinobu" to mapOf(6 to intArrayOf(0,3,3)),
        "LanYan" to mapOf(3 to intArrayOf(0,3,0)),
        "Layla" to mapOf(6 to intArrayOf(0,3,3)),
        "Lynette" to mapOf(6 to intArrayOf(0,3,3)),
        "Mona" to mapOf(6 to intArrayOf(0,3,3)),
        "Ningguang" to mapOf(4 to intArrayOf(0,0,3)),
        "Noelle" to mapOf(6 to intArrayOf(0,3,3)),
        "Prune" to mapOf(3 to intArrayOf(0,0,3)),
        "Razor" to mapOf(6 to intArrayOf(0,3,3)),
        "Rosaria" to mapOf(6 to intArrayOf(0,3,3)),
        "Sayu" to mapOf(6 to intArrayOf(0,3,3)),
        "Sethos" to mapOf(6 to intArrayOf(3,0,3)),
        "ShikanoinHeizou" to mapOf(3 to intArrayOf(0,3,0)),
        "Sucrose" to mapOf(6 to intArrayOf(0,3,3)),
        "Tartaglia" to mapOf(0 to intArrayOf(1,0,0)),
        "Thoma" to mapOf(6 to intArrayOf(0,3,3)),
        "Xiangling" to mapOf(6 to intArrayOf(0,3,3)),
        "Xingqiu" to mapOf(6 to intArrayOf(0,3,3)),
        "Xinyan" to mapOf(6 to intArrayOf(0,3,3)),
        "Yanfei" to mapOf(6 to intArrayOf(0,3,3)),
        "Yaoyao" to mapOf(3 to intArrayOf(0,3,0)),
        "YunJin" to mapOf(6 to intArrayOf(0,3,3)),
    )
    private val charTalents = MutableList(3) { 0 }

    /** 当前角色的词典 key（供 `readTalent` 查 [TALENT_BONUS] 减加成）。 */
    private var lastCharKey: String? = null

    /**
     * **无命座系统**的角色（GOOD key）：奇偶（Manekin / Manekina）。
     * 其"命之座"页无真实节点 ⇒ 六格饱和度判据恒判满（实测 6/6）⇒ 见 [readConstellation] 末尾的强制归零。
     */
    private val NO_CONSTELLATION_KEYS = setOf("Manekin", "Manekina")

    // 显示名由玩家自定义的角色一律走 NameOverrides（#105）：09-17 那版内置别名表硬写了本账号的两个
    // 昵称、方向还和 GT 对不上（见 NameOverrides 的 KDoc），等于把"一个账号的状态"发给所有用户。
    // 没填 ⇒ 保留原文 + 打日志，宁可漏也不猜错。旅行者不走它 —— 它的 GOOD 键要带元素后缀，
    // 而元素只有角色概览面板里有 ⇒ 交给下面这条**账号无关**的元素规则。

    /** 元素（header 中「X元素」的 X）→ GOOD v3 旅行者键。 */
    private val TRAVELER_BY_ELEMENT = mapOf(
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
    private fun isPlausiblePlayerName(s: String): Boolean =
        s.length in 2..8 && s.all { it in '\u4e00'..'\u9fa5' }

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
                // ⚠️ 实测 OCR 原文形如「等级90/90」「等级80/90」（含"等级"二字 + "/"）✗
                //    ⇒ 取**文本里的数字序列**：第 1 个 = level、第 2 个 = 等级上限（cap）✓
                val lvNums = Regex("(\\d+)").findAll(lvText).map { it.value }.toList()
                val lv = lvNums.getOrNull(0)?.let { d ->
                    d.take(3).toIntOrNull()?.takeIf { it <= 100 } ?: d.take(2).toIntOrNull()
                } ?: 0
                val validCaps = listOf(100, 90, 80, 70, 60, 50, 40, 20)
                val levelCap = lvNums.getOrNull(1)?.toIntOrNull()?.takeIf { it in validCaps }
                    ?: lvNums.getOrNull(0)?.let { d -> validCaps.firstOrNull { d.length > it.toString().length && d.endsWith(it.toString()) } }
                lastCharAscension = run {
                    val cap = levelCap ?: 0
                    when {
                        cap >= 90 -> 6; cap >= 80 -> 5; cap >= 70 -> 4
                        cap >= 60 -> 3; cap >= 50 -> 2; cap >= 40 -> 1; cap > 0 -> 0
                        else -> when { // 回退：按 level 分层（GT 反推：70~80 ⇒ 5、81+ ⇒ 6）
                            lv >= 81 -> 6; lv >= 70 -> 5
                            lv >= 61 -> 4; lv >= 50 -> 3
                            lv >= 41 -> 2; lv >= 21 -> 1; else -> 0
                        }
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
             *            ② 官方词典（good_names，中文名为键）
             *            ③ 元素规则（旅行者 —— 官方键还要 `<元素>` 后缀，只有概览面板读得到元素）
             * ⚠️ ①② 都可换序，③ 必须最后：元素规则会吃掉**任何**未命中且元素已知的名字 ⇒ 前两档
             *    必须先判，否则「随机姓/随机人」（同为冰元素）会被误判成 TravelerCryo ✗（2026-09-17 实测）。
             * ★ 2026-09-17 加固：元素规则加**两道门**（[isPlausiblePlayerName] 且 level>0）——
             *    实测 cver12 鹿野院平藏的 name ROI 首读为 `'.'`（level 也读成 `'.'`）⇒ 旧规则把它
             *    误判成 TravelerAnemo（凭空多一件、且真角色丢失）✗。
             */
            fun resolveKey(rn: String, lv: Int, el: String?): String? =
                nameOverrides.characterKeyOf(rn)?.also { Log.i(TAG, "char: 显示名 '$rn' 命中用户昵称表 ⇒ $it") }
                    ?: lookupName(nameDict, rn, dictFuzzy(step))
                    ?: el?.takeIf { lv > 0 && isPlausiblePlayerName(rn) }
                        ?.let { TRAVELER_BY_ELEMENT[it] }
                        ?.also { Log.i(TAG, "char: 显示名 '$rn' 未命中词典/昵称表（元素=$el lv=$lv）⇒ 判为旅行者 $it") }

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
                val f2 = runCatching { freshFrame() }.getOrNull() ?: break
                try {
                    val t2 = runCatching { gateway.readRois(f2, rects) }.getOrNull()
                    if (t2 != null) { texts = t2; logRaw(t2) }
                } finally {
                    f2.release()
                }
                parsed = parseFrame(texts)
                rawName = parsed.first
                if (parsed.second > level) level = parsed.second   // 欠读方向确定 ⇒ 取 max
                element = parsed.third
                key = resolveKey(rawName, level, element)
                Log.i(TAG, "char: key/lv 可疑 ⇒ 重读 #$nTry name='$rawName' lv=$level el=$element ⇒ key=$key")
            }
            charName = key ?: rawName
            charKey = key
            lastCharKey = key
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
        } catch (_: Exception) {
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
                val f = if (poll == 0) frame else runCatching { freshFrame() }.getOrNull() ?: break
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
                //   未点亮 = **白色锁图标**（低饱和高亮）或**暗底**（低饱和低亮）⇒ 饱和度低 ✓
                val th = CONSTELLATION_SAT_MIN.getOrElse(i) { 21 }
                if (sat >= th) lit++
                if (PANEL_RAW_DUMP) Log.i(TAG, "char.constellation node#$i sat=$sat th=$th lit=${sat >= th}")
            }
        } catch (_: Exception) {
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
        Log.i(TAG, "char: 命座点亮 $lit/${nodes.length()} ｜ 各节点饱和度=$satSamples（阈值 $CONSTELLATION_SAT_MIN）")
    }

    /**
     * 角色界面**页签级**判据（用于命之座页到位检查）：
     * 三个页签左上标题完全相同（都是 `<元素>/<角色名>`），只能靠**页内独有文本**区分：
     *   · 属性页底部 = 「提升指南」  · 天赋页底部 = 「天赋演示 / 战斗天赋」  · 命之座页底部 = **无文本**
     * @return true = 命中属性/天赋页文本 ⇒ **不在命之座页**
     */
    private suspend fun charPanelLooksWrongPage(): Boolean {
        val g = ocr ?: return false
        val f = runCatching { freshFrame() }.getOrNull() ?: return false
        return try {
            val rects = listOf(
                profile.rect("panels.char_profile.bottomHint"),
                profile.rect("panels.char_talent.bottomHint"),
            )
            val t = runCatching { g.readRois(f, rects).joinToString(" ") }.getOrDefault("")
            val wrong = t.contains("提升指南") || t.contains("天赋演示") || t.contains("战斗天赋")
            if (wrong) Log.i(TAG, "char: 页签级判据命中非命之座页文本：'$t'")
            wrong
        } finally {
            f.release()
        }
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
        var texts = try {
            gateway.readRois(frame, rects)
        } finally {
            frame.release()
        }
        for (i in 0 until Math.min(3, texts.size)) {
            charTalents[i] = Regex("(\\d+)").find(StatParser.clean(texts[i]))
                ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        }
        // ★ 2026-09-17（三修）：**多次重读 + 逐项取 max**，直到三项都 >0（或打满 [TALENT_RETRY_MAX]）。
        //   实测 cver13：「天赋」页切换与「命之座」页一样有 ~2.5s 交叉淡入 ⇒ 单次 350ms 重读**仍读不到**
        //   （原文第 3 ROI = `'散步'`/`'設行'` 之类汉字 ⇒ 读数 0；Ayaka/Mona 连续两轮都错在第 3 项）。
        //   ⇒ 停止条件改成「三项都 >0」—— 全部战斗天赋基础等级**恒 ≥1**（GT 最小值 1）✓
        //   合并律仍取 max：OCR 失配恒为**欠读**，且淡入期读到的是**汉字**（无数字 ⇒ 0），不会"多读"✓
        var attempts = 0
        // ★ 停止条件：**至少完成 1 次额外读**（防"非零误读"，实测 cver14 Chevreuse 首读 skill='Lv.1'
        //   而真值 11 ⇒ 只判 `>0` 会直接采信 ✗）**且**三项都 >0（全部战斗天赋基础等级恒 ≥1）。
        while (attempts < TALENT_RETRY_MAX) {
            if (attempts > 0 && charTalents.none { it <= 0 }) break
            attempts++
            delay(TALENT_REREAD_DELAY_MS)
            val f2 = runCatching { freshFrame() }.getOrNull() ?: break
            val t2 = runCatching { gateway.readRois(f2, rects) }.getOrNull()
            f2.release()
            if (t2 == null) continue
            val before = charTalents.toList()
            texts = t2
            for (i in 0 until Math.min(3, t2.size)) {
                val v = Regex("(\\d+)").find(StatParser.clean(t2[i]))
                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                if (v > charTalents[i]) charTalents[i] = v
            }
            Log.i(
                TAG,
                "char: 天赋重读 #$attempts $before → ${charTalents.toList()} ｜ 原文=${t2.take(3).map { "'" + it + "'" }}",
            )
        }
        // ── ★ 2026-09-17（四修）：**「多一行」回退**（冲刺技占行的角色）──
        //   实测 cver15：神里绫华 / 莫娜 的「战斗天赋」组里**多插一行无 Lv 的冲刺技**
        //   （神里流·霰步 / 虚实流动）⇒ 第 3 行（元素爆发）的 Lv 位置读到的是那一行的**行名**，
        //   且 6/6 次重读完全一致（原文 `'散步'`＝霰步、`'实流动'`＝虚实流动）—— **不是时序问题** ✗。
        //   判据：该行读数为 0 **且** 原文含汉字（= 读到了名称）⇒ 真值在**下一行** ⇒
        //   用同一 x、y + TALENT_ROW_PITCH_PX 的 ROI 补读一次（实测行距 153）。
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
                val shifted = FrameRect(
                    r3.left,
                    r3.top + TALENT_ROW_PITCH_PX,
                    r3.right,
                    r3.bottom + TALENT_ROW_PITCH_PX,
                )
                val f3 = runCatching { freshFrame() }.getOrNull()
                if (f3 != null) {
                    val t3 = runCatching { gateway.readRois(f3, listOf(shifted)) }.getOrNull()?.firstOrNull()
                    f3.release()
                    val v = t3?.let {
                        Regex("(\\d+)").find(StatParser.clean(it))?.groupValues?.getOrNull(1)?.toIntOrNull()
                    } ?: 0
                    if (v in 1..TALENT_LEVEL_MAX) {
                        charTalents[2] = v
                        Log.i(TAG, "char: 第3天赋行读到行名（'$burstText'）⇒ 下移一行补读 burst=$v（原文='$t3'）")
                    } else if (v > 0) {
                        Log.w(TAG, "char: 下移一行补读得 $v 越界（非等级值）⇒ 弃用（原文='$t3'）")
                    }
                }
            }
        }
        // ★ 2026-09-17：**减去命座加成**（游戏面板是"含加成值"，GOOD/GT 是"基础值" ✗）
        //   逐角色查 [TALENT_BONUS]；未收录时回退近似规则（c≥3 ⇒ skill-3 / c≥5 ⇒ burst-3）
        val c = charConstellation
        val b = lastCharKey?.let { k -> TALENT_BONUS[k]?.get(c) }
            ?: intArrayOf(0, if (c >= 3) 3 else 0, if (c >= 5) 3 else 0)
        for (i in 0 until 3) charTalents[i] = (charTalents[i] - b.getOrElse(i) { 0 }).coerceAtLeast(0)
        // 诊断：实测我方天赋 = GT + 3（恒差，非命座加成 ✗）⇒ 打印 OCR 原文与 ROI 定位
        Log.i(
            TAG,
            "char: 天赋等级 ${charTalents.toList()} ｜ 原文=${texts.take(3).map { "'" + it + "'" }} " +
                "｜ bonus=${b.toList()} key=$lastCharKey c=$c " +
                "｜ ROI=${rects.take(3).joinToString { "(${it.left},${it.top},${it.right},${it.bottom})" }}",
        )
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
            // ascension：由 `parseCharacterPanel` 算好（优先面板等级上限 Lv.X/Y 的 Y，缺失按 level 分层）
            ascension = lastCharAscension,
            // ★ 2026-09-17 **减去命座对天赋的加成**（游戏面板显示"含加成值"，GT/GOOD 是"基础值"）
            //   规则由 GT 监督离线拟合（92 角色 × 3 天赋）：
            //     c0~2 ⇒ **无加成**（0 差异 100% ✓）
            //     c≥5  ⇒ skill/burst **各 +3**（94% ✓）
            //     c3~4 ⇒ **混合**（各角色命座加成对象不同 ✗）⇒ 取多数派 c≥3 即 −3（≈88%）
            //   ⇒ 近似规则：`c≥3 ⇒ skill −3` / `c≥5 ⇒ burst −3`（auto 无加成）
            //   ⚠️ 精确化需读"命座文本"（GOODScanner 做法）或内置逐角色表（待办）
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

    /** 计划中目标的**最低稀有度**（稀有度止扫判据）。计划为空或无 rarity 字段返回 null ⇒ 判据不生效。 */
    private fun minTargetRarity(): Int? {
        val plan = vars.plan ?: return null
        var min: Int? = null
        for (t in plan) {
            val r = t.optInt("rarity", -1)
            if (r > 0 && (min == null || r < min!!)) min = r
        }
        return min
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
    /** 帧 → 灰度（落地条带 fpband 测量的搜索区用）。失败返回 null；返回值归调用方 release。 */
    private fun grayOf(frame: Mat): Mat? = runCatching {
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
    private fun swipeLogged(gridKey: String, x: Int, y0: Int, y1: Int, why: String): Boolean {
        val t0 = System.nanoTime()
        val ok = actions.swipe(x, y0, x, y1)
        val ms = (System.nanoTime() - t0) / 1_000_000
        Log.i(TAG, "advance[$gridKey]: swipe($why) ($x,$y0)→($x,$y1) accepted=$ok ${ms}ms")
        return ok
    }

    private suspend fun awaitGridStable(
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
        } catch (e: Exception) {
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
    /**
     * **安全守卫：意外界面即中止**（★ 2026-09-18，事故驱动）。
     *
     * 事故：装配流程 `rosterFind` 静默失败后一路点漂，把游戏带到 **「确认退出游戏」** 弹窗
     * （截图 `产物/bs-20260918/05_装配卡住现场.png`）—— 再多点一次「确认」游戏就退了，
     * 而既有 `assertScreen(onFail=back)` **没拦住**。
     *
     * 语义：**每次点击之前**先判"当前是不是已知危险界面"；是则**立即中止整条流程**
     * （`stopReason="safety"` + ERROR 提醒，走 NoticeCenter 唯一通路）。
     * **绝不"点掉它再继续"** —— 中止是唯一安全动作，交还用户。
     *
     * 判据：OCR `screens.dialogs.quitConfirm.title`，出现「退出」即命中。
     * 二字关键词同时覆盖派蒙菜单里的「退出游戏」项（同一处文本），一处判据挡两种入口。
     * 拿不到 OCR / profile 缺项 / 读帧失败 ⇒ **放行**（守卫不应成为新的故障源）。
     */
    private suspend fun safetyGuard(): Boolean {
        val gateway = ocr ?: return true
        val rect = runCatching { profile.rect("screens.dialogs.quitConfirm.title") }.getOrNull() ?: return true
        val expect = runCatching {
            profile.rawObject("screens.dialogs.quitConfirm")?.optString("expect", "退出")
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "退出"
        val frame = try {
            freshFrame()
        } catch (_: Exception) {
            return true
        }
        val text = try {
            gateway.readLines(frame, listOf(rect)).joinToString(" ")
        } catch (_: Exception) {
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

    private suspend fun clickAt(x: Int, y: Int, settleMs: Long = CLICK_SETTLE_MS) {
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
    private suspend fun dismissLockConfirm(timeoutMs: Long = 12000L): Boolean {
        // ⚠️ 判据用**像素白底占比**，不用中文 OCR —— 实测该弹框的正文 ROI 设备端 OCR 读空（''），
        //   而同一帧该区域白底像素 ≈357560（占 54%）、无框时是背包网格（暗）。
        //   本类已被"设备端 OCR 对多行/长文本 ROI 不可靠"坑过两次（面板标题、这里），故改像素。
        val obj = profile.rawObject("screens.dialogs.lockConfirm") ?: run {
            Log.w(TAG, "dismissLockConfirm: profile 缺 screens.dialogs.lockConfirm ⇒ 跳过")
            return false
        }
        val probe = runCatching { profile.rect("screens.dialogs.lockConfirm.probe") }.getOrNull() ?: run {
            Log.w(TAG, "dismissLockConfirm: 缺 probe ⇒ 跳过")
            return false
        }
        val confirm = runCatching { profile.rect("screens.dialogs.lockConfirm.confirm") }.getOrNull() ?: run {
            Log.w(TAG, "dismissLockConfirm: 缺 confirm ⇒ 跳过")
            return false
        }
        val need = obj.optDouble("whiteRatio", 0.25)
        val deadline = clock() + timeoutMs
        while (true) {
            val frame = try {
                freshFrame()
            } catch (_: Exception) {
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
    private fun lockOverlayWhiteRatio(frame: Mat, probe: FrameRect): Double {
        var white = 0
        var total = 0
        var y = probe.top
        while (y < probe.bottom) {
            var x = probe.left
            while (x < probe.right) {
                val px = frame.get(y, x)
                val v = ((px[0].toInt() and 0xFF) + (px[1].toInt() and 0xFF) + (px[2].toInt() and 0xFF)) / 3
                if (v > 225) white++
                total++
                x += 12
            }
            y += 12
        }
        return if (total == 0) 0.0 else white.toDouble() / total
    }
    /**
     * 双区判"已锁"：普通位与祝圣位任一击中即算（跨 `zhushengShiftPx` 位移）。
     * 原为 [lockClickAdaptive] 内的局部 lambda ⇒ 抽成成员，供 [settleLockState] 共用。
     */
    private fun lockVoteAny(frame: Mat): Boolean =
        VoteJudges.panelLock(frame, profile, 0).matched ||
            VoteJudges.panelLock(frame, profile, profile.zhushengShiftPx).matched

    /**
     * 面板锁钮点击（fast.at=$zones.artifact.panel.lock 专用）。
     *
     * 2560 实测：同一单件名存在普通（锁徽 center 2309,744）与祝圣（+zhushengShiftPx ≈808）两种面板，
     * 固定坐标必漏一种。
     *
     * ★ 2026-09-18（P2⑧）两处修正：
     * 1. **方向**：原来的"双区判定已锁 ⇒ 跳过点击"只看**当前态**、不看**目标态** ⇒
     *    解锁方向（curLock=true、wantLock=false）会被这里静默跳过 ⇒ **永远解不了锁**。
     *    现在改成 `当前态 == 目标态 才跳过`，两个方向都正确。
     * 2. **确定性选位**：目标**显式**给了 `elixirCrafted` 时直接按它选普通位/祝圣位
     *    （GOODScanner：`Lock toggles use the scanned piece's elixirCrafted to apply a 40px Y-shift`），
     *    省掉"先点普通位 → 回读 → 未生效再点祝圣位"的第二次点击；只有没给（手写最小计划）才退回试错。
     *
     * ★ 2026-09-27（#97）：未生效后的兜底改为 [followUpLockShift] —— 见那个函数的 KDoc。
     *   原来无条件"换另一侧 +zhushengShiftPx"，对**非祝圣件**那是个没标定过的坐标
     *   （同帧实测 gold=0/red=0）；对**祝圣件**则等于把刚点过的位置再点一次（净效果归零）。
     */
    private suspend fun lockClickAdaptive(): Pair<Int, Int> {
        val obj = profile.zone("artifact.panel.lock")
            ?: throw IllegalStateException("zone artifact.panel.lock missing")
        val r = obj.getJSONArray("rect")
        val rect = profile.scaleRect(r.getInt(0), r.getInt(1), r.getInt(2), r.getInt(3))
        val sh = profile.zhushengShiftPx
        // 结果契约：一旦真的要动锁钮就记"尝试过写入"（后面 settleLockState 决定成败）。
        // 两个标志都置：`actTried` 供 foreach 状态映射，#93 起另置 `lockWriteAttempted`
        // 专供"加锁确认框只可能在锁写入后出现"那条探测闸门（装配链不碰它）。
        vars.actTried = true
        vars.lockWriteAttempted = true
        // 目标态：来自 {lock,unlock} 清单归一出的项内 wantLock（缺省 true = 应锁定）
        val desired = vars.currentTask?.optBoolean(GoodPlan.KEY_WANT_LOCK, true) ?: true
        // 确定性选位：显式给了 elixirCrafted 就不试错
        val wantElixir = vars.currentTask?.let {
            if (it.has("elixirCrafted")) it.optBoolean("elixirCrafted") else null
        }
        val firstShift = when (wantElixir) {
            true -> sh
            false -> 0
            null -> 0 // 未知 ⇒ 仍先试普通位（与旧行为一致）
        }
        var f = freshFrame()
        val pre = try { lockVoteAny(f) } finally { f.release() }
        // ★ 只有"当前态已等于目标态"才可跳过 —— 原来只看"已锁"⇒ 解锁方向被静默跳过
        if (pre == desired) {
            Log.i(TAG, "lockClickAdaptive: 已处于目标态（locked=$pre desired=$desired），跳过点击")
            return rect.centerX to rect.centerY
        }
        Log.i(
            TAG,
            "lockClickAdaptive: locked=$pre desired=$desired elixirCrafted=$wantElixir 首选位移=$firstShift",
        )
        // 原语取 `click`（2px 微滑 + 按压）。⚠️ **tap vs click 之争是伪命题**：
        //   09-18「真机 clickLocal accepted=true 但毫无反应」与 09-27「纯 tap 三轮全背包锁态零变化」
        //   两条互相矛盾的取证，其实都被**同一个真 bug** 污染了 —— 调用方（click 步骤）在
        //   `lockClickAdaptive()` 点完之后，又把返回的同一坐标**点了一遍**（详见 click 步骤里
        //   `clickedWhileResolving` 闸门）⇒ 锁被切两下 = 净效果归零。用"净效果归零"的路径去
        //   判别哪种原语有效，两种都能"证伪"，所以那两条结论都不作数。
        //   唯一干净的证据是 09-27 的 `DEBUG_CLICK`（外部单次微滑）：一次就翻态 ⇒ 坐标与注入通道正常。
        //   这里保留 `click` 是因为它与那条唯一有效的取证同形，**不是**因为 tap 被判过刑。
        actions.click(rect.centerX, rect.centerY + firstShift, LOCK_PRESS_MS)
        delay(CLICK_SETTLE_MS)
        if (settleLockState(desired)) return rect.centerX to (rect.centerY + firstShift)
        // ★★ #97：首选位未生效时的兜底 —— **只点"屏幕上真的是锁钮"的那一侧，且绝不重复点同一坐标** ★★
        //   判据与完整理由见 [followUpLockShift]（纯函数、有单测）。
        //   这里只负责把屏幕事实取出来：同帧紫横幅投票，与 [parseArtifactPanel] 的 crafted 同源。
        val craftedOnScreen = runCatching {
            val f2 = freshFrame()
            try {
                VoteJudges.panelZhusheng(f2, profile).matched
            } finally {
                f2.release()
            }
        }.getOrElse { e ->
            Log.w(TAG, "lockClickAdaptive: 祝圣横幅判据不可用（${e.message}）⇒ 不补点第二坐标")
            null
        }
        val followUp = followUpLockShift(firstShift, sh, craftedOnScreen)
        if (followUp == null) {
            Log.w(
                TAG,
                "lockClickAdaptive: 首选位($firstShift)未生效 ⇒ 不补点" +
                    "（屏幕祝圣态=$craftedOnScreen 与首选位同侧，或判据不可用）",
            )
            return rect.centerX to (rect.centerY + firstShift)
        }
        Log.i(
            TAG,
            "lockClickAdaptive: 首选位($firstShift)未生效 ⇒ 按屏幕祝圣态($craftedOnScreen)补点 +$followUp",
        )
        actions.click(rect.centerX, rect.centerY + followUp, LOCK_PRESS_MS)
        delay(CLICK_SETTLE_MS)
        val ok = settleLockState(desired)
        Log.i(TAG, "lockClickAdaptive: 补点($followUp) → ${if (ok) "达到目标态" else "仍未达目标态"}")
        return rect.centerX to (rect.centerY + followUp)
    }

    /**
     * 诊断（#96，已定案）：把面板锁钮区在**同一帧**上的 gold / 红锁 两种掩码计数都打出来。
     *
     * 当初的疑问是「`settle` 读到已锁、1.5s 后 `verify` 读到未锁」，两个候选解释（按下动画帧 /
     * 判据方向反了）**都不成立**：真因是调用方把 `lockClickAdaptive()` 返回的坐标又点了一次
     * （见 click 步骤里 `clickedWhileResolving` 的注释）。实测稳态读数：
     * **已锁 = gold≈871 且 red≈900**（金边红锁，两掩码在 G∈[120,140] 本就重叠，不是二选一）；
     * **未锁 = gold=0 且 red=0**。⇒ `panelLock` 的 gold 判据方向是对的。
     * 留着这段是因为"读数自相矛盾"这类问题只有同帧双判据能一次分辨，重跑流程看不出来。
     */
    private fun probeLockPixels(frame: Mat, tag: String) {
        val obj = profile.zone("artifact.panel.lock") ?: return
        val r = obj.getJSONArray("rect")
        val base = profile.scaleRect(r.getInt(0), r.getInt(1), r.getInt(2), r.getInt(3))
        val sh = profile.zhushengShiftPx
        val a = base.shiftedBy(0)
        val b = base.shiftedBy(sh)
        Log.i(
            TAG,
            "lockPixels[$tag]: 零位 gold=${VoteJudges.countMatches(frame, a, VoteJudges.GOLD)}" +
                " red=${VoteJudges.countMatches(frame, a, VoteJudges.RED_LOCK)} |" +
                " +$sh gold=${VoteJudges.countMatches(frame, b, VoteJudges.GOLD)}" +
                " red=${VoteJudges.countMatches(frame, b, VoteJudges.RED_LOCK)}",
        )
    }

    /**
     * 点完锁钮后的**收敛等待**：交错执行「处置迟到的确认弹框 + 复读锁态」，直到到达目标态或预算耗尽。
     *
     * ⚠️ **必须交错，不能只在开头 dismiss 一次**。真机实测（2026-09-18）：锁定是**服务端操作**，
     *   加锁确认弹框可能**迟至约 60s** 才出现 —— 点完 60s 后截屏才发现框正开着，
     *   而期间每次轮询都读到 `白底占比=0.022`（即"没框"）。旧实现只在点击后 dismiss 一次，
     *   随后 3 次纯复读**不再处置弹框** ⇒ 晚到的框永远点不掉、锁态也永远读不对。
     *   （当时把这条当成 `verify FAILED` 的解释，其实**不是** —— 那条另有真因，
     *   见 [probeLockPixels] 与 click 步骤里的 `clickedWhileResolving`。交错本身仍然要留：
     *   晚到的框会留在屏上把后续点击全吃掉。）
     *
     * 预算取 [SETTLE_LOCK_BUDGET_MS]（90s）：实测迟到极值 ~60s，留一半余量；超预算打 warn 后继续，不空转。
     */
    private suspend fun settleLockState(desired: Boolean, budgetMs: Long = SETTLE_LOCK_BUDGET_MS): Boolean {
        val deadline = clock() + budgetMs
        var round = 0
        while (true) {
            round++
            // 确认框是**一次性提示**：账户在本设备确认过一次之后就不再弹。学到这点之后每轮
            // 仍做**一次零等待探测**（0L）—— 晚到 60s 的框照样点掉，只是不再为它每轮白等 1500ms。
            val dismissed = dismissLockConfirm(if (lockConfirmAbsent) 0L else 1500L)
            if (dismissed) {
                // 又弹了 ⇒ 之前的"不再弹"结论作废，回到带等待的档位并重新计数。
                cleanLocksNoDialog = 0
                lockConfirmAbsent = false
            }
            val f = freshFrame()
            val now = try {
                probeLockPixels(f, "settle#$round")
                lockVoteAny(f)
            } finally { f.release() }
            if (now == desired) {
                if (!dismissed) noteCleanLockWithoutDialog()
                Log.i(TAG, "settleLockState: 第 $round 轮达到目标态（locked=$now）${if (dismissed) "，本轮点掉了弹框确认" else ""}")
                return true
            }
            if (clock() >= deadline) {
                Log.w(TAG, "settleLockState: ${budgetMs}ms 预算用尽仍未达目标态（第 $round 轮 locked=$now desired=$desired）")
                return false
            }
            delay(2000L)
        }
    }

    /**
     * 「本设备已不再弹上锁确认框」的学习：连续 [LOCK_CLEAN_BEFORE_ABSENT] 次写锁成功且
     * 全程没点掉过弹框，才认定不再弹，并回写持久层（跨轮生效 —— 一次性提示的性质是账户级的，
     * 每轮从零重新学一遍等于每轮白等）。
     */
    private fun noteCleanLockWithoutDialog() {
        if (++cleanLocksNoDialog < LOCK_CLEAN_BEFORE_ABSENT) return
        if (lockConfirmAbsent) return
        lockConfirmAbsent = true
        Log.i(
            TAG,
            "settleLockState: 连续 $cleanLocksNoDialog 次写锁都没出现确认弹框 ⇒ 本设备按「不再弹」处理" +
                "（每轮仍做一次零等待探测，晚到的框照样点掉）",
        )
        onLockConfirmAbsentLearned()
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
    /** 每条计划项的落库结果（下标 / label / InstructionStatus），由 [foreach] 或 [planDone] 写入。 */
    private val manageResults = mutableListOf<Triple<Int, String, String>>()

    /** 本轮内：连续多少次"写锁成功且没点掉过弹框"（见 [noteCleanLockWithoutDialog]）。 */
    private var cleanLocksNoDialog = 0
    /** 本轮内是否按"不再弹"处理；由 [lockConfirmAbsentLearned] 起步，真弹出来就当场作废。 */
    private var lockConfirmAbsent = lockConfirmAbsentLearned

    private fun filterTargets(): Set<String> = TaskMatch.targets(vars.currentTask, vars.plan)

    /**
     * `planMatch` 原语（★ 2026-09-18，单趟扫描核心）：
     * 把**本格刚解析出来的圣遗物**拿去和 `plan` 里**尚未绑定**的项逐一比对，命中则
     * 把 `vars.currentTask` 指向该项、记下 `planMatchedIndex`，供下游 `wantLock`/`curLock`
     * 与 `hardMatch` 沿用（**下游逻辑零改动**）。
     *
     * 与旧写法的区别：旧写法是 `foreach(task){ pagedGrid{ parsePanel.match } }` ——
     * 外层锁定"这一轮只找一个目标"，于是每格都要与**同一个**任务比、且每换一个任务就重走整张网格。
     * 现在反过来：**网格只走一遍**，每格问"我是谁"。
     *
     * 绑定语义（对齐 GOODScanner 的绑定表）：**一个计划项只被绑定一次**；已绑定的项不再参与比对，
     * 因此同件重复出现（翻页重叠）不会被重复处理 ⇒ 幂等。
     *
     * ```json
     * { "do": "planMatch", "tol": 0.100001 }
     * ```
     */
    private fun planMatch(step: JSONObject) {
        val plan = vars.plan
        // 每格先复位：宁可"不动作"，也不留下上一格的命中值
        vars.currentTask = null
        vars.planMatchedIndex = -1
        vars.panelMatched = false
        vars.matchHit = false
        vars.actOk = false
        if (plan.isNullOrEmpty()) {
            Log.d(TAG, "planMatch: plan 为空 ⇒ 跳过")
            return
        }
        val a = results.lastOrNull()
        if (a == null) {
            Log.d(TAG, "planMatch: 无已解析产物（本格 parsePanel 未入库）⇒ 跳过")
            return
        }
        val tol = step.optDouble("tol", 0.100001)
        for ((i, t) in plan.withIndex()) {
            if (i in vars.consumedPlanIndexes) continue
            val why = StringBuilder()
            if (TaskMatch.hardMatch(t, a, tol, why)) {
                vars.currentTask = t
                vars.planMatchedIndex = i
                vars.panelMatched = true
                vars.matchHit = true
                Log.i(TAG, "planMatch: 本格绑定第 $i 项 ${taskLabel(t)}（剩余未绑定 ${vars.planRemaining()}）")
                return
            }
        }
        Log.d(TAG, "planMatch: 本格未命中任何未绑定项")
    }

    /**
     * `planDone` 原语：把**本格绑定的项**标记为已绑定并记录结果状态。
     * **首次写入生效**（同一项被多个分支调用时后者忽略）⇒ 可安全地在多条 `ifMatch` 分支里都放一句。
     *
     * ```json
     * { "do": "planDone", "status": "Success" }        // 动作执行且 verify 通过后
     * { "do": "planDone", "status": "AlreadyCorrect" } // 命中但无需动作时
     * ```
     */
    private fun planDone(step: JSONObject) {
        val i = vars.planMatchedIndex
        if (i < 0) {
            Log.d(TAG, "planDone: 本格未绑定任何项 ⇒ 忽略")
            return
        }
        if (!vars.consumedPlanIndexes.add(i)) {
            Log.d(TAG, "planDone: 第 $i 项已绑定过 ⇒ 忽略（${step.optString("status")}）")
            return
        }
        val label = vars.plan?.getOrNull(i)?.let { taskLabel(it) } ?: "#$i"
        val want = step.optString("status", "Success")
        // `Success` 是「服务端真的改了」的唯一出口。#89 起它由 verify 置的 `actOk` 驱动，
        // #107 起还要 `actVerified == true` —— 即**必须有写后回读的正面证据**，
        // 不接受"动作按语义发出了"这种代理。verify 不符时只打日志、不改控制流，原先这里照抄
        // flow 写的 "Success" ⇒ 迟到弹框把 verify 打成 FAILED 的那件照样记成功，
        // 而且已进绑定表**永不再补**。
        val status = when {
            want != "Success" || (vars.actOk && vars.actVerified == true) -> want
            else -> {
                Log.w(
                    TAG,
                    "planDone: 第 $i 项 $label 要求 Success 但 actOk=${vars.actOk} " +
                        "actVerified=${vars.actVerified}（verify 未过或没跑）⇒ 记 Failed",
                )
                RecognitionLog.log(
                    logTag,
                    RecognitionLog.Level.W,
                    "结果降级 Success→Failed：写后复核未通过（$label）",
                )
                "Failed"
            }
        }
        manageResults.add(Triple(i, label, status))
        Log.i(TAG, "planDone: 第 $i 项 $label \u21d2 $status（剩余未绑定 ${vars.planRemaining()}）")
    }

    /** `planSummary`：把**始终没被绑定**的项补记 `NotFound`，然后出汇总（照 `emitManageSummary`）。 */
    private fun planSummary() {
        val plan = vars.plan ?: return
        for (i in plan.indices) {
            if (i !in vars.consumedPlanIndexes) {
                manageResults.add(Triple(i, taskLabel(plan[i]), "NotFound"))
            }
        }
        emitManageSummary()
    }

    /**
     * `clickSlotTab` 原语（2026-09-18 P2⑪）：点**槽位页签**，槽位取自**当前计划项**的 `slotKey`。
     *
     * 为什么必须有这一步：GOODScanner 的装配流程是
     * "Clicks the artifact slot matching the artifact's slotKey"，**再**做套装筛选。
     * 我们此前完全不点槽位页签 ⇒ 选择网格停在上一次进入时的槽位 ⇒ 可能选到**错部位**的件
     * （目标身份含 slotKey，最终 hardMatch 会拒掉，表现为"整轮 NotFound"）。
     *
     * 坐标来自 `screens.artifact_manage.slotTabs`（5 个部位，机核零偏移）。
     * 未知 slotKey ⇒ 记警告并跳过（不猜、不乱点）。
     */
    private suspend fun clickSlotTab(step: JSONObject) {
        val cur = vars.currentTask
        val key = cur?.optString("slotKey").takeUnless { it.isNullOrBlank() }
            ?: cur?.optString("slot").orEmpty()
        val cn = when (key) {
            "flower" -> "生之花"
            "plume" -> "死之羽"
            "sands" -> "时之沙"
            "goblet" -> "空之杯"
            "circlet" -> "理之冠"
            else -> step.optString("default", "")
        }
        if (cn.isEmpty()) {
            Log.w(TAG, "clickSlotTab: 未知 slotKey='" + key + "' ⇒ 跳过（不猜）")
            return
        }
        val arr = profile.rawObject("screens.artifact_manage.slotTabs")?.optJSONArray(cn)
        if (arr == null) {
            Log.w(TAG, "clickSlotTab: profile 缺 screens.artifact_manage.slotTabs." + cn + " ⇒ 跳过")
            return
        }
        clickAt(arr.getInt(0), arr.getInt(1))
        Log.i(TAG, "clickSlotTab: slotKey=" + key + " → 「" + cn + "」(" + arr.getInt(0) + "," + arr.getInt(1) + ")")
    }

    /** 当前处于「背包 / 圣遗物筛选主面板 / 圣遗物套装子面板」哪一态。 */
    private enum class FilterPanelState { BACKPACK, MAIN_PANEL, SET_PANEL, UNKNOWN }

    /**
     * 读左上标题条判定当前面板态（2026-09-18 P0 新增）。
     *
     * 为什么必须判态：`setFilter` 打开的既有**套装子面板**（全屏「圣遗物**套装**筛选」）也有
     * **主面板**（窄栏「圣遗物筛选」），两者底部按钮完全不同。旧实现在子面板上点主面板坐标
     * ⇒ 点空白 ⇒ 面板不关 ⇒ 后面整段流程站在错屏上跑（真机实测）。
     *
     * 判据用三态互斥词：子面板含「套装」、背包含「背包」、主面板含「筛选」——
     * ⚠️ **不能只判「圣遗物」或「筛选」**，三态标题都含这两个词，必须靠「套装」「背包」区分。
     */
    private suspend fun filterPanelState(): FilterPanelState {
        val gateway = ocr ?: return FilterPanelState.UNKNOWN
        // ⚠️ 2026-09-18 返工（真机暴露）：**必须只读标题那一行**。
        //   先用 `screens._common.titleBar`（y21..136）时，ROI 同时含标题与副标题
        //   「将筛选展示满足条件的圣遗物」，而设备端 OCR **只回了副标题且读成乱码**
        //   （实测 title='柔满物奈满芷亲件的圣遗物'）⇒ 不含「套装/背包/筛选」⇒ 一路 UNKNOWN
        //   ⇒ `ensureSetPanel` 判"没进子面板"、`leaveFilterPanels` 又不敢按 BACK ⇒ 卡在面板上。
        //   （旁证：同一 ROI 用 paddleocr 离线读能正确得到「圣遗物套装筛选」+ 副标题两行，
        //     说明 ROI 本身没切错，是设备端只回了一行。）
        //   ⇒ 收紧到 `grids.set_filter_popup.titleLine`（只框标题行）后就不会被副标题挤掉。
        // 读**两个** ROI 再 OR：紧 ROI（只框标题行）避免被副标题挤掉；宽 ROI（通用标题条）对
        // 边缘裁切更宽容。实测同一帧里「背包/圣遗物」在紧 ROI 被读成「背句/又遗物」而在宽 ROI 正确 ——
        // 单靠任一个都会误判，故取并集。
        val rects = listOfNotNull(
            runCatching { profile.rect("grids.set_filter_popup.titleLine") }.getOrNull(),
            runCatching { profile.rect("screens._common.titleBar") }.getOrNull(),
        )
        if (rects.isEmpty()) return FilterPanelState.UNKNOWN
        val frame = try {
            freshFrame()
        } catch (_: Exception) {
            return FilterPanelState.UNKNOWN
        }
        // ⚠️ 必须用循环而不是 joinToString{}：lambda 里不能调 suspend 函数（readLines 是 suspend）
        val sb = StringBuilder()
        try {
            for (r in rects) {
                val one = gateway.readLines(frame, listOf(r)).joinToString(" ")
                if (one.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(one)
                }
            }
        } catch (_: Exception) {
            return FilterPanelState.UNKNOWN
        } finally {
            frame.release()
        }
        val text = sb.toString()
        val st = when {
            text.contains("套装") -> FilterPanelState.SET_PANEL
            text.contains("背包") -> FilterPanelState.BACKPACK
            text.contains("筛选") -> FilterPanelState.MAIN_PANEL
            else -> FilterPanelState.UNKNOWN
        }
        Log.i(TAG, "filterPanelState: title='$text' ⇒ $st")
        return st
    }

    /** 套装子面板的按钮中心（`profiles.grids.set_filter_popup.<key>`：okBtn / clearBtn / title）。 */
    private fun setPanelCenter(key: String): IntArray? {
        val g = profile.rawObject("grids.set_filter_popup") ?: return null
        val r = g.optJSONArray(key) ?: return null
        if (r.length() < 4) return null
        return intArrayOf((r.getInt(0) + r.getInt(2)) / 2, (r.getInt(1) + r.getInt(3)) / 2)
    }

    /**
     * 开启面板并确认**确实进了子面板**（可重试一次）。
     * 未确认时按 BACK 清掉可能卡住的层再试；仍不行由调用方决定降级。
     * 照 `filterReset` 的「绝不盲点」原则：不确定就不点后面的按钮。
     */
    private suspend fun ensureSetPanel(open: suspend () -> Unit): Boolean {
        repeat(2) { attempt ->
            if (filterPanelState() == FilterPanelState.SET_PANEL) return true
            if (attempt > 0) {
                Log.w(TAG, "ensureSetPanel: 第 $attempt 次仍未进子面板 ⇒ BACK 清一层后重试")
                runCatching { actions.back() }
                delay(FILTER_PANEL_BACK_MS)
            }
            open()
            for (i in 1..6) {
                delay(FILTER_PANEL_POLL_MS)
                if (filterPanelState() == FilterPanelState.SET_PANEL) return true
            }
        }
        return false
    }

    /**
     * 退掉筛选面板回到背包：用**系统 BACK**（分辨率无关、免标定；GOODScanner 同样用 Escape 关这个面板），
     * 每次按前先判态 —— **回到背包就不再按**，避免多按一次把背包本身关掉。
     */
    private suspend fun leaveFilterPanels(reason: String, aggressive: Boolean = true): Boolean {
        // ⚠️ 2026-09-18 返工（真机暴露）：原来 `UNKNOWN -> return false`（怕误按），
        //   结果是"判态读不到 ⇒ 一次 BACK 都不按 ⇒ 面板永远开着" ⇒ 后续整段流程站在面板上跑。
        //   正确语义是：**只有确认回到背包才停**，其余（含 UNKNOWN）都该退一层；
        //   次数封顶 3，且一旦确认背包立刻停（不会把背包本身关掉）。
        //   实测：子面板 → 主面板 → 背包，两层各一次 BACK。
        repeat(3) {
            val st = filterPanelState()
            if (st == FilterPanelState.BACKPACK) return true
            // ★ aggressive=false（"面板可能压根没打开"的路径专用）：只按**正向证据**退 ——
            //   读不清（UNKNOWN）时不按 BACK，否则会把**背包本身**关掉（真机实测：把这轮整条流程
            //   带到了世界界面，之后每格都是"无已解析产物"）。收尾交给 flow 的 assertScreen 兜。
            if (!aggressive && st == FilterPanelState.UNKNOWN) {
                Log.w(TAG, "  leaveFilterPanels($reason): 判态 UNKNOWN 且非激进模式 ⇒ 不按 BACK（交给 assertScreen 兜）")
                return false
            }
            Log.i(TAG, "  leaveFilterPanels($reason): 当前=$st ⇒ 按 BACK 退一层")
            runCatching { actions.back() }
            delay(FILTER_PANEL_BACK_MS)
        }
        return filterPanelState() == FilterPanelState.BACKPACK
    }

    private suspend fun setFilter(step: JSONObject) {
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
            leaveFilterPanels("setFilter 放弃", aggressive = false)
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
            leaveFilterPanels("无筛选目标")
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
        var guard = 0
        var turns = 0
        var rescanUsed = false
        var selected = 0
        while (pending.isNotEmpty() && guard++ < MAX_FILTER_PAGES) {
            if (vars.stopRequested) break
            var hits = 0
            for (i in 0 until rowYTop.length()) {
                if (pending.isEmpty()) break
                val y = rowYTop.getInt(i)
                val rowCenterY = y + rowHeight / 2
                if (matchFilterRow(ocrGateway, lookup, leftBox, y, rowHeight, leftX, rowCenterY, pending, "left")) {
                    hits++; selected++
                }
                if (pending.isEmpty()) break
                if (matchFilterRow(ocrGateway, lookup, rightBox, y, rowHeight, rightX, rowCenterY, pending, "right")) {
                    hits++; selected++
                }
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
            leaveFilterPanels("selected=0")
            return
        }
        Log.i(TAG, "setFilter: 已勾选 $selected 个目标（翻页 $turns 次，未点完=$pending）⇒ 确认筛选")
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
        // ★ 2026-09-18（P0① 核心修正）：点**套装子面板**右下角的「确认筛选」。
        //
        // 旧实现点的是 `screens.dialogs.filterPanel.ok/confirm` —— 那是**主面板**（窄栏「圣遗物筛选」，
        // 底部是「重置」+「确认」）的坐标；而 setFilter 的 chain（漏斗 → 所属套装「+」）
        // 打开的是**子面板**（全屏「圣遗物**套装**筛选」，底部左「清空条件」右「确认筛选」）。
        // ⇒ 点主面板坐标 = 点空白 ⇒ 子面板永不关闭 ⇒ 之后 foreach 全程对着子面板跑
        //   （真机实测：每格 panelMatch 未命中 + verify artifact.panel.lock FAILED）。整轮白跑。
        //
        // 根因是"同一块面板两处真值"：dsl/verify/_artifact_template.json 里
        // `set_filter_grid.buttons`（子面板）与 `filter_panel_popup`（主面板）早就分别记对了，
        // 但 profiles 只把主面板那份搬进 screens.dialogs.filterPanel。现已把子面板按钮入档
        // （grids.set_filter_popup.okBtn / clearBtn，3200 实测）。
        val subPt = setPanelCenter("okBtn")
        val pt = subPt ?: filterPanelCenter("ok")
        if (pt == null) {
            Log.w(TAG, "confirmFilter: 子面板 okBtn 与主面板 ok 均未标定 ⇒ 无法确认，按 BACK 退出")
            leaveFilterPanels("confirmFilter 未标定")
            return
        }
        Log.i(
            TAG,
            "confirmFilter: 确认筛选=(${pt[0]},${pt[1]}) 来源=${if (subPt != null) "子面板 okBtn" else "主面板 ok（回退）"}",
        )
        clickAt(pt[0], pt[1])
        delay(FILTER_PANEL_STEP_MS)
        // 子面板关掉后可能**露出主面板**（背包入口是两层）⇒ 复核并收尾，否则后面会站在主面板上跑。
        when (filterPanelState()) {
            FilterPanelState.MAIN_PANEL, FilterPanelState.SET_PANEL -> {
                Log.i(TAG, "confirmFilter: 仍有面板残留 ⇒ BACK 收尾")
                leaveFilterPanels("confirmFilter 收尾")
            }
            else -> Unit
        }
        // 兼容旧配置的 tailGuard 语义已在上面被"判态 + BACK"取代，step 参数保留给日志/未来扩展
        if (step.optString("tailGuard").isNotEmpty()) {
            Log.i(TAG, "confirmFilter: tailGuard='${step.optString("tailGuard").take(40)}' 已由判态收尾取代")
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
        // ★ 逐目标循环必须**每项清累积**（见 resetScanAccumulation 的说明）
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
            val pagesAtItemStart = tmPages
            Log.i(TAG, "foreach iter $asName[$idx/${items.size}]: $label")
            for (i in 0 until subSteps.length()) {
                executeStep(subSteps.getJSONObject(i))
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
                val expect = item.optString("char")
                vars.actVerified = expect.takeIf { it.isNotEmpty() }?.let { verifyEquippedBy(it, null) }
            }
            // ⚠️ 状态映射**不能**看 `vars.stopRequested` 就判 Skipped：本目标的 pagedGrid 因
            //   **稀有度止扫**（目标全 5★ ⇒ 走到 4★ 即停）而结束时也会置该标志，那是**本条扫描的正常收尾**，
            //   应记 `NotFound`（走完了、没命中）。真机实测：4 目标里后 3 个被误记 Skipped。
            //   只有**整轮停止**（exit/watchdog）才是 Skipped。
            val flowStop = vars.stopRequested &&
                vars.stopReason != "stopWhen" && vars.stopReason != "maxPages"
            val status = manageStatusOf(vars.matchHit, vars.actTried, vars.actOk, vars.actVerified, flowStop)
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
     * 清空**扫描累积**（逐目标循环专用）。★ 2026-09-18
     *
     * 为什么必须有：artifact_lock 是**逐目标**跑同一张网格（每个目标一次完整走查），
     * 而"走到底"的判据全是**累积式**的 —— ① 重复件计数（`charDupStreak` / `dupPageStreak`）
     * ② 回卷止扫（连续整页零新增）。第 2 个目标开始，**每一件都是"本轮已入库"** ⇒
     * 回卷止扫在第一整页就断言"到底"并截断，排在后面的真目标永远扫不到。
     * 真机实测：目标 1 走 120s，目标 2 只走 58s，真件 `ScarletProof/flower` 被判 `NotFound`。
     * ⇒ 每项开头清空累积，让每次走查自成一体（代价 = N 次全量走查 —— 本来就是逐目标循环）。
     */
    private fun resetScanAccumulation() {
        results.clear()
        resultsWeapons.clear()
        weaponCellEmitted.clear()
        weaponIdentityRuns.clear()
        lastEmittedWeaponIdentity = null
        resultsCharacters.clear()
        charDupStreak = 0
        vars.charDupStreak = 0
        dupPageStreak = 0
        dupPageDecided = false
        dupPageStopConfirmed = false
        Log.i(TAG, "resetScanAccumulation: 已清空扫描累积（results/重复计数/回卷状态）")
    }

    /** 每条计划项的可读标签（角色/套装/部位，缺省 "?"）。 */
    private fun taskLabel(t: JSONObject): String {
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
    private fun emitManageSummary() {
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
        // ★ 读判据前先清一次加锁确认弹框：它是全屏模态，盖住锁图标 ⇒ 否则恒读 false、假 FAILED
        // （只有锁定流程会用这条 verify；#93 起仍用 `actTried` 作闸是安全的 —— 该 zone 仅锁流程投）
        if (zone == "artifact.panel.lock" && vars.actTried) dismissLockConfirm(1200L)
        val frame = freshFrame()
        val actual = try {
            when (zone) {
                "artifact.panel.astral" -> VoteJudges.panelAstral(frame, profile, 0).matched
                "artifact.panel.lock" -> {
                    probeLockPixels(frame, "verify")
                    VoteJudges.panelLock(frame, profile, 0).matched ||
                        VoteJudges.panelLock(frame, profile, profile.zhushengShiftPx).matched
                }
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
        } else {
            // ★ 2026-09-18：**成功也要留一行**。原先只在失败时打日志 ⇒
            //   "点击后状态确实翻转了"与"这一步根本没执行"在日志上**不可区分**
            //   （排查解锁方向时被这条坑过：没有 FAILED 就以为没执行）。
            Log.i(TAG, "verify OK: zone=$zone expect=$expect actual=$actual")
            // #107：锁的 verify **就是**写后回读 ⇒ 它同时满足"点击后复核"这一档，两处一起置，
            // 好让 planDone 能用同一个口径要 Success。
            if (zone == "artifact.panel.lock") {
                vars.actOk = true
                vars.actVerified = true
            }
        }
    }

    /**
     * 升序索引列表里**最长连续段**的长度（`[3,4,5,9]` → 3）。
     *
     * 用途：区分「零星几格读失败」（定点重访有效）与「整页面板冻结」（定点重访实测 0/502 救回，
     * 见调用处）。入参须已 `distinct().sorted()`。
     */
    private fun longestRunLen(sortedIdx: List<Int>): Int {
        var best = 0
        var cur = 0
        var prev = Int.MIN_VALUE
        for (i in sortedIdx) {
            cur = if (i == prev + 1) cur + 1 else 1
            if (cur > best) best = cur
            prev = i
        }
        return best
    }

    /**
     * **定点重访**（★ 2026-09-19 用户定稿，取代「退 1 行 + 整页重扫」）。
     *
     * 背景：格点击**不移动列表**（点击只改选中、不滚动）⇒ 某格读取失败时该格内容**整页不变** ⇒
     * 直接重访该格即可读到，无需退行、无需整页重走。位置信息本就在手
     * （页内行主序 `idx` → `row = idx / cols, col = idx % cols`），此前实现把它丢了才被迫整页重扫。
     *
     * 失败格判据（上游算好传入）：**本格身份 == 上一格身份**（相邻重复 = 本格没读成功；
     * 真机定位：5/5 真漏与 5 次相邻重复一一对应）。
     *
     * ⚠️ 重访期间抑制「连续重复件」计数：重访必然重读到刚记过的件，否则会把 duplicateStreak
     *   拉到阈值误触止扫。
     */
    private suspend fun revisitFailedCells(
        visit: JSONArray,
        gridKey: String,
        prof: ScreenProfile,
        cols: Int,
        idxList: List<Int>,
        pageKeys: MutableList<String>,
        pageIdentities: MutableList<String>,
    ): Int {
        suppressDupStreak = true
        var recovered = 0
        try {
            // ★ 2026-09-19「压窗口」：把**连续失败格**聚成"窗口"——先等窗口过去再整段重扫；
            //   孤立失败格仍走短退避。依据：吞击窗口可跨 6 格/~12s（宿主 screencap 对拍实证），
            //   窗口内立即重发无效（同窗口重复点击已被实测证伪：两轮重访无增益），
            //   **等窗口就位**才是正确动作。
            val runs = ArrayList<ArrayList<Int>>()
            for (idx in idxList.sorted()) {
                val last = runs.lastOrNull()
                if (last != null && idx == last.last() + 1) last.add(idx) else runs.add(arrayListOf(idx))
            }
            for (run in runs) {
                val isWindow = run.size >= WINDOW_MIN_CELLS
                val waitMs = if (isWindow) WINDOW_SETTLE_MS else REVISIT_BACKOFF_MS
                Log.w(
                    TAG,
                    "定点重访: ${if (isWindow) "窗口(${run.size}格)" else "单格"} idx=${run.first()}..${run.last()}" +
                        " ⇒ 先等 ${waitMs}ms 让窗口就位",
                )
                delay(waitMs)
                for (idx in run) {
                    val row = idx / cols
                    val col = idx % cols
                    val before = results.size + resultsWeapons.size + resultsCharacters.size
                    // 与空读格回读同理：emit 按 [curCellIdx] 落 `curPageIds`，逐格改写才不会记错槽位
                    curCellRow = row
                    curCellCol = col
                    curCellIdx = idx
                    runVisit(visit, gridKey, col, row, idx, prof)
                    curCellIdx = -1
                    val k = lastCellKey
                    val id = lastCellIdentity
                    lastCellKey = null
                    lastCellIdentity = null
                    // 回填页表看**读到了没有**，不看"是否新增入库"：重访读到一件已入库的重复件时
                    // results 不增长，但那一格确实有身份了 —— 按增长回填会让 pageKeys 与 curPageIds 打架。
                    if (!k.isNullOrEmpty() && idx < pageKeys.size) pageKeys[idx] = k
                    if (!id.isNullOrEmpty() && idx < pageIdentities.size) pageIdentities[idx] = id
                    if (results.size + resultsWeapons.size + resultsCharacters.size - before > 0) recovered++
                }
            }
        } finally {
            suppressDupStreak = false
        }
        return recovered
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
    /**
     * ifMatch 的表达式变量：**直接复用 [ScanVars.exprVars]**（2026-09-18 统一）。
     * 此前这里是**第二份**实现，与 stopWhen 那份不同 ⇒ 同一个表达式两处结果不一致。
     * 现在只保留 ScanVars 那一份作为唯一事实源，这里只做转发。
     */
    private fun exprVars(): Map<String, Any?> = vars.exprVars()

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
    private suspend fun waitPanelNameReady(
        nameRect: FrameRect,
        ocr: OcrGateway,
        cleanBefore: String?,
    ): Boolean {
        val pStep = TimingOverrides.panelPollMs
        val needSame = (TimingOverrides.panelStableFallbackMs / pStep).coerceAtLeast(1L)
        var waited = 0L
        var prev: String? = null
        var stableSame = 0
        var sawChange = false
        while (waited < PANEL_CHANGE_WAIT_MAX_MS) {
            delay(pStep); waited += pStep; tmPanelMs += pStep
            val f = freshFrame()
            val now = try { ocr.readLines(f, listOf(nameRect)).firstOrNull() } finally { f.release() }
            val c = now?.let { StatParser.clean(it) }?.takeIf { it.isNotEmpty() }
            if (c != null && c != cleanBefore) sawChange = true
            if (c != null && sawChange && c == prev) return true
            // 名字未变但已连续稳定：可能是相邻同名件，也可能点击被吞 —— 交给调用方判（见返回值）。
            stableSame = if (c != null && c == prev) stableSame + 1 else 0
            if (stableSame.toLong() >= needSame) return sawChange
            prev = c
        }
        return sawChange
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
                    // ★ 2026-09-27 #96 真因：`lockClickAdaptive()` **自己就是一次点击**（点完还 settle 收敛），
                    //   而下面的通用分支会把返回的坐标**再点一次** ⇒ 锁钮被切两下 = 净效果归零，
                    //   锁态回到原样。所以 `verify` 读到"未锁"是**读数正确**、不是判据假阴；
                    //   09-18 真机那条「clickLocal accepted=true 但毫无反应」也是同一件事。
                    //   ⇒ 解析阶段已点过的，这里必须跳过第二次点击。
                    var clickedWhileResolving = false
                    val (cx, cy) = runCatching {
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
                                val regionRect = try { profile.rect(region) } catch (_: Exception) { null }
                                if (regionRect != null) {
                                    val ok = runCatching {
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
                    val zr = VoteJudges.panelZhusheng(frame, profile)
                    // 诊断：命中数 + 三点采样（排查"祝圣件没被识别"用；GT 实测本账号 20 件 elixerCrafted）
                    Log.i(TAG, "vote zhusheng matched=${zr.matched} hits=${zr.count}（≥${VoteJudges.Thresholds.BANNER_POINTS_REQUIRED}/3 且紫占比>${VoteJudges.Thresholds.BANNER_PURPLE_RATIO}）col=$col row=$row")
                    vars.crafted = zr.matched
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
    private fun writeShot(dir: java.io.File, name: String, seq: Int, json: JSONObject, frame: Mat): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        // ★ #46 ④：`MatOfInt` 是**原生内存**，靠 GC/finalize 回收不及时 ⇒ 显式 release。
        //   原实现每次 imwrite 都 new 一个且从不释放（一轮 1256 张 = 1256 个泄漏）。
        val params = org.opencv.core.MatOfInt(
            Imgcodecs.IMWRITE_JPEG_QUALITY, PANEL_SHOT_JPEG_QUALITY,
        )
        val ok = try {
            runCatching {
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
    private fun shotGateOpen(dir: java.io.File): Boolean {
        if (panelShotSeq + panelShotDropped >= PANEL_SHOT_MAX_PER_RUN) {
            panelShotDropped++
            if (panelShotDropped == 1) {
                Log.w(TAG, "取证已达本轮上限 ${PANEL_SHOT_MAX_PER_RUN} 张 ⇒ 后续只计数不落盘")
            }
            return false
        }
        if (panelShotSpaceCheckedAt == 0 || panelShotSeq - panelShotSpaceCheckedAt >= PANEL_SHOT_SPACE_CHECK_EVERY) {
            panelShotSpaceCheckedAt = panelShotSeq
            panelShotSpaceOk = runCatching { dir.usableSpace }.getOrDefault(Long.MAX_VALUE) >=
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
    private fun dumpPanelShot(frame: Mat, panelKey: String, fpWaitMs: Long) {
        val dir = panelShotDir ?: return
        if (!shotGateOpen(dir)) return
        runCatching {
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
    private suspend fun dumpStallShot(
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
        if (phase == "giveup") clickGiveups++
        val dir = panelShotDir ?: return
        // 闸门放在 freshFrame **之前**：到顶/没空间时连帧都不抓（与"关着开关不抓帧"同一口径）。
        if (!shotGateOpen(dir)) return
        runCatching {
            val seq = ++panelShotSeq
            val name = String.format("stall_p%03d_i%02d_%s%d_s%04d", curPageNo, index, phase, attempt, seq)
            val f = freshFrame()
            // 同一帧顺带判遮罩：白底占比 + 是否达到 [dismissLockConfirm] 的命中线（**只判不点**）
            val overlay = runCatching {
                val obj = profile.rawObject("screens.dialogs.lockConfirm")
                val probe = runCatching { profile.rect("screens.dialogs.lockConfirm.probe") }.getOrNull()
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

    private suspend fun parsePanel(step: JSONObject, ctx: CellFrameContext? = null) {
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
        val frame = ctx?.acquire { freshFrame() } ?: freshFrame()
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
                    val f2 = runCatching { freshFrame() }.getOrNull() ?: break
                    val fp2 = try {
                        PanelFingerprint.capture(f2, fpRects)
                    } finally {
                        f2.release()
                    }
                    if (fp2 != null && !PanelFingerprint.same(fp2, panelFpSnapshot)) {
                        Log.i(TAG, "面板指纹：等 ${waited}ms 后新面板就绪（陈旧帧已修复）")
                        panelFpSnapshot = fp2
                        lastPanelAppeared = true
                        break
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
    private fun evaluatePanelMatch(step: JSONObject): Boolean {
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
    private suspend fun parseArtifactPanel(
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
        //      （good_names.artifactPieces，305 件，由游戏 Reliquary 表生成；用户 2026-09-01 定稿）
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
        val equippedText = runCatching {
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
            level = vars.level,
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
        //   GOOD 的 artifactSets 只列 4★/5★ 的 56 套，3★ 层整层不在其中（词典侧已补 30 件名，
        //   见 gen_good_names.py 的 extraPieceToSetId）⇒ 这里保留该件并计数，让缺口看得见。
        if (setKey.isNullOrEmpty()) {
            unknownSetPieces++
            Log.w(
                TAG,
                "套装词典未命中：piece=${pieceName ?: "?"} rarity=$rarity 词条 ${substats.size} 条 " +
                    "⇒ **照常入库**（setKey 留空），累计未命中 $unknownSetPieces 件" +
                    "（词典是生成的：先跑 dsl/scripts/gen_mappings.py --refresh 重生成 mappings.json，" +
                    "再跑 gen_good_names.py；仍缺 ⇒ 游戏 Reliquary 表或 data_cache 落后于版本）",
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

    /**
     * 卡格指纹矩形：卡片中心一小块（基准 ±36 ⇒ 72×72，随分辨率缩放）——**只要够区分"同一张卡"即可**。
     *
     * @param prof **必须**是本页的相位视图（`pageProfile`）：本矩形与点击坐标同源，
     *               传原始 profile 会偏离卡心一个 φ（φ 可达 ±126 帧 px，而窗半宽仅 36）
     *               ⇒ 采到卡缝 ⇒ 跨页指纹恒不等（跳过静默失效）。
     */
    private fun cellFingerprintRect(
        gridKey: String,
        prof: ScreenProfile,
        col: Int,
        row: Int,
    ): FrameRect? = runCatching {
        val g = prof.gridGeometryFor(gridKey) ?: return null
        if (col >= g.colXs.size || row >= g.rowYs.size) return null
        val cx = g.colXs[col] + g.cardW / 2
        val cy = g.rowYs[row] + g.clickDy
        // gridGeometry 是**基准**坐标，而 PanelFingerprint.capture 按帧像素取 Mat ⇒ 必须过 scaleRect
        prof.scaleRect(cx - 36, cy - 36, cx + 36, cy + 36)
    }.getOrNull()

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
                // ★★ #93：命中即"本格就是目标件" ⇒ 置 `matchHit` ★★
                //   auto_equip 的命中判据**只在这条路径上**（它的 parsePanel 不声明 `match`）；
                //   不置的话 foreach 状态映射拿不到"命中"这个事实，装好了也只能报 NotFound。
                //   （artifact_lock 走 `planMatch`，那条路本来自带置位，不受影响。）
                vars.matchHit = true
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
        // ★ 2026-09-18：函数式写法（max(plan.level) / min(plan.rarity)）Expr 不支持 ⇒ 与 pageSkip
        //   一样先做文本替换。此前只替换了 pageSkip 的表达式，`stopWhen` 里的 min(plan.rarity)
        //   直接被 Expr 拒（unexpected char '('）⇒ 稀有度止扫**静默失效**（按未命中处理，不报错）。
        val expr2 = expr
            .replace("max(targets.level)", "targetMaxLevel")
            .replace("max(plan.level)", "targetMaxLevel")
            .replace("min(plan.rarity)", "targetMinRarity")
        val hit = runCatching { Expr.eval(expr2, vars.exprVars()) }
            .onFailure { Log.w(TAG, "stopWhen 表达式求值失败，按未命中处理：$expr2 (${it.message})") }
            .getOrDefault(false)
        if (hit) {
            // ★ 2026-09-16 守卫（真机实测驱动）：`scope=cell` 的 3★/2★ 止扫**必须连续 2 格命中**才认。
            //   本账号实测**没有 3★/2★**（背包 941 全 5★/4★）⇒ 任何 `rarity < 4` 读数都是**单格星级误读**；
            //   一次误读即置 stopRequested 会把整轮扫描在第 10 页截断（实测只入库 149/941）。
            //   连续 2 格同时误读同一 rarity<4 的概率极低 ⇒ 代价可忽略，收益是避免整轮报废。
            stopWhenCellStreak++
            if (scope == "cell" && stopWhenCellStreak < 2) {
                Log.i(TAG, "stopWhen ($scope) 命中但未连续确认（$stopWhenCellStreak/2）⇒ 继续扫描：$expr")
                return
            }
            // scope=cell（本页后停）：置 flag，页遍历结束后停止；scope=page 立即停
            vars.stopRequested = true
            vars.stopReason = "stopWhen"
            Log.i(TAG, "stopWhen triggered ($scope): $expr")
        } else {
            stopWhenCellStreak = 0
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
     * @return **整个等待期是否至少观察到一次变化**。`false` = 面板签名**从未变过** ⇒ 极可能
     *   **点击被送达但被游戏吞掉**（2026-09-16 run6 实证：该页 21 格耗时**零方差且最快**
     *   = 就绪轮询一上来签名即稳定 = 内容从未变化；第 1 格是新件、第 2~21 格全重复）。
     *   调用方（cell 路径）据此做**同坐标重发**兜底。
     *   ⚠️ 与内部 `changedEver` **不同**：后者会被 `confirm 空读重试`重置（见下），不可用作该判据。
     *   签名不可用而中止 ⇒ 无法判断 ⇒ 返回 `true`（按"已送到"处理，不触发重发）。
     */
    private suspend fun waitReadyBySignature(
        before: ByteArray,
        a: ByteArray,
        b: ByteArray,
        roi: FrameRect,
        /** 与「动作前基准」**必须同一网格**（块数变了 = 每块覆盖的物理区域变了 = 一比就"变了"）。 */
        blocksX: Int,
        blocksY: Int,
        step: Long,
        maxMs: Long,
        minMs: Long,
        fallbackMs: Long,
        what: String,
        /** 认定"已稳定"所需的**跨帧**连同样本数（默认 [SIG_STABLE_SAMPLES]）。 */
        stableSamples: Int = SIG_STABLE_SAMPLES,
        confirm: (suspend () -> Boolean)? = null,
    ): Boolean {
        val roiI = roi.toIntRect()
        val sigBlocks = blocksX * blocksY
        var waited = 0L
        var first = true
        var lastGen = Long.MIN_VALUE
        var changedEver = false
        // ★ 2026-09-16：与 changedEver 并行、**永不重置**的"见过变化"标记（返回值判据，见 KDoc）
        var sawAnyChangeEver = false
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
            if (!frameSource.sampleSignature(roiI, cur, blocksX, blocksY)) {
                Log.w(TAG, "$what signature unavailable mid-poll, abort")
                return true   // 无法判断 ⇒ 不判"点击被吞"
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
            val changed = changedFraction(before, cur, sigBlocks) > 0f
            if (changed && firstChangeAt < 0) {
                firstChangeAt = waited
                // 诊断（`sigdebug=1`）：**首次检出变化**时打出块网格掩码 ——
                // 用来回答"band 里到底是**哪一块**先变"（若只是边框/背景先动，说明该把 ROI 收窄）。
                if (TimingOverrides.sigDebug && what == "panel") {
                    Log.i(TAG, "sigFirstChange[${roiI.left},${roiI.top},${roiI.right},${roiI.bottom}] " +
                        "waited=${waited}ms\n${blockMask(before, cur, blocksX, blocksY)}")
                }
            }
            val p = prev
            var stable = false
            when {
                changed -> {
                    changedEver = true
                    sawAnyChangeEver = true
                    // 未动判据**严格**：任何一块不同都不算"同一状态"（见 SIG_LEN 旁的事故记录）
                    val sameAsPrev = p != null && changedFraction(p, cur, sigBlocks) == 0f
                    sameStreak = if (sameAsPrev && genChanged) sameStreak + 1 else 1
                    if (genChanged) tmSigSamples++
                    stable = sameStreak >= stableSamples && waited >= minMs
                }
                !changedEver -> {
                    if (genChanged) tmSigSamples++
                    // 未变兜底：用"距动作的墙钟"（静态屏也能兜底）
                    if (waited >= maxOf(fallbackMs, minMs)) {
                        logWait("unchanged-fallback")
                        return sawAnyChangeEver   // false = 全程未变 ⇒ 疑似点击被吞
                    }
                }
                else -> sameStreak = 0
            }
            if (stable) {
                val ok = confirm?.invoke() ?: true
                if (ok) {
                    logWait("stable")
                    return sawAnyChangeEver
                }
                if (retries >= SIG_CONFIRM_RETRIES) {
                    Log.w(TAG, "$what confirm still empty after $retries retries, proceed")
                    return sawAnyChangeEver
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
        return sawAnyChangeEver
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
     * - **artifact_backpack → 16×8（细）**：圣遗物面板的内容在**约 1 帧内换完**，细网格能立刻检出。
     *   ⚠️ 但当年记的 "`firstChange=40ms`、等待 240→80ms、perCell −20%" **不可信**：那时 [waitReadyBySignature]
     *   的轮询把网格**写死成 8×4**，与这里的 16×8 基准不同网格 ⇒ 一上来就判"已变"（见审计 P1-1），
     *   80ms 退出是**在空白平台期早退**，不是内容就绪。改完（基准与轮询同网格）后这组数字要**重测**再定。
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

    /**
     * 被点卡片自身的矩形（帧坐标）。
     *
     * 必须用**页面视图** [prof]（含 §12.5 的相位偏移）取，与 `prof.cellCenter` 同一套坐标，
     * 否则签名区会跟点击点错开一格。
     */
    private fun cardRoi(prof: ScreenProfile, gridKey: String, col: Int, row: Int): IntRect? {
        val g = prof.gridGeometryFor(gridKey) ?: return null
        return prof.cardRelRect(gridKey, intArrayOf(0, 0, g.cardW, g.cardH), col, row).toIntRect()
    }

    /**
     * 「点击是否落到卡片上」——看**被点卡片的选中框**有没有出现，而不是看详情面板。
     *
     * 为什么需要它（2026-09-23，BlueStacks 实测）：面板签名判"未变"有两种完全不同的成因，
     * 而旧代码把它们当成一种：
     * 1. **点击被吞**（真该重发）；
     * 2. **相邻两格内容本来就一样** —— 武器尤其常见（同名同等级同精炼），面板像素一模一样，
     *    指纹**永远不会变**（`WEAPON_SAME_IDENTITY_CAP` 上方那条 🔴 注释说的就是这个死结）。
     * 实测代价：1 页武器 21 格里 7 格被判"疑似吞击"，白烧 35 次重发 ≈ 15s。
     *
     * 选中框能把它们分开：点中了 ⇒ 高亮框换到被点卡（实测被点卡 11/64 块变、原选中卡 10/64、
     * 其余 21 格 0/64）；点击没送达 ⇒ 被点卡一块都不动。
     *
     * @param before [cardRoi] 上**点击前**采好的签名
     * @return false = 采样不可用（按"没移动"处理，保守走重发）
     */
    private fun cardFrameMoved(roi: IntRect, before: ByteArray): Boolean {
        val n = CARD_SIG_BLOCKS * CARD_SIG_BLOCKS
        if (!frameSource.sampleSignature(roi, sigCardCur, CARD_SIG_BLOCKS, CARD_SIG_BLOCKS)) return false
        return changedFraction(before, sigCardCur, n) * n >= CARD_MOVED_MIN_BLOCKS
    }

    private fun panelHasStarBand(panelKey: String): Boolean {
        val band = profile.rawObject("panels.$panelKey")?.optJSONObject("starBand") ?: return false
        return band.has("y") && band.has("x0")
    }

    /**
     * 两签名差异占比（复用既有容差语义；长度不一致/越界按"变了"处理）。
     *
     * ⚠️ [blocks] 必须显式给：签名缓冲是**复用的定长 scratch**（`SIG_LEN_MAX`），
     *   只比较一次采样真正写入的前 `blocksX*blocksY*3` 字节。整数组比较会把**没写过的尾部**
     *   也当成数据 ⇒ 换个网格就"全变了"（2026-09-23 审计 P1-1 的成因）。
     */
    private fun changedFraction(x: ByteArray, y: ByteArray, blocks: Int = -1): Float =
        VoteJudges.thumbChangedFraction(x, y, VoteJudges.THUMB_DIFF_TOL, blocks) ?: 1f

    /** 块网格差异掩码（'.'=同 '#'=变，第一行=ROI 上沿）；仅供 `sigdebug` 诊断。 */
    private fun blockMask(a: ByteArray, b: ByteArray, bx: Int, by: Int): String {
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

    internal companion object {
        const val TAG = "BetterGI.Scan"
        const val ENTER_SETTLE_MS = 1200L
        const val SCREEN_SETTLE_MS = 1500L
        const val CLICK_SETTLE_MS = 600L
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
         * 天赋面板**行距**（帧 px，3200 档实测 153：Lv 行中心 301/455/607；2244 档会按 scaleY 缩放）。
         * 用于「读到行名 ⇒ 下移一行补读」的回退（神里绫华 / 莫娜 的冲刺技占行）。
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
         * 点亮 = 彩色技能图标（S 高）；未点亮 = 白色锁图标或暗底（S 低）。
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

        /** 翻页后、**抓卡格帧**前的固定等待（让列表惯性停稳；仅影响跨页指纹，不影响点击）。 */
        const val CROSS_PAGE_SETTLE_MS = 250L
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
        )
    }
}
