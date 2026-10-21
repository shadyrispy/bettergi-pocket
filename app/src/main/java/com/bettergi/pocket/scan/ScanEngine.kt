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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs
import com.bettergi.pocket.scan.flows.LockFlow
import com.bettergi.pocket.scan.flows.runArtifactSteps
import com.bettergi.pocket.scan.flows.runCharacterSteps
import com.bettergi.pocket.scan.flows.runCharCalibrateSteps
import com.bettergi.pocket.scan.flows.runEquipSteps
import com.bettergi.pocket.scan.flows.runLockSteps
import com.bettergi.pocket.scan.flows.runSetFilterCalibrateSteps
import com.bettergi.pocket.scan.flows.runWeaponSteps

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
    /**
     * ★ 2026-10-15 S4a：**同包名的 MiHoYo SDK 登录/切号页探针** —— 返回 true = 前台是登录页。
     * 默认 null = 不判（单测/离线跑不接无障碍）。
     *
     * 为什么必须单独一条（不能并进 [foregroundOk]）：SDK 登录页与游戏**同包名**
     * （`com.miHoYo.Yuanshen/com.miHoYo.sdk.platform.SdkActivity`），前台闸按包名判恒为"是原神"
     * ⇒ 放行。2026-10-14 06:10 真机实测：被顶号后 SDK 页冒出来，character_scan 当场崩在
     * `anchor not reached: /元素/`（`20261014_huawei_283/evidence/`）—— 注入侧虽已拦（#283），
     * 但引擎不知道，会一路重试到超时。
     *
     * 处置口径 = **停手 + 通知**（项目红线：一旦冒出登录表单立刻交回用户，绝不自动填）。
     * 判据走 Activity **类名**（`GenshinPackages.isSdkActivity`），不是画面 —— 画面级判据
     * 对同包名的登录页无效（详见 `ScreenStateJudge` 顶部"为什么画面判据不够"）。
     */
    internal val sdkLoginOnTop: (() -> Boolean?)? = null,
    /**
     * ★ #317（2026-10-19）：**后台模式（VD）帧新鲜度补偿开关**。
     *
     * 只有后台模式置 true（由 ScriptRunner 按本轮装配写入，见 applyAssembly/ScanEngine 构造）。
     * 前台投影是**常驻帧流**（游戏每渲染一帧就推一帧）⇒「点击已消化」≡「下一帧必到」，
     * 帧指针天然同步；后台 VD 的 ImageReader 是**内容驱动**（只有合成出新内容才回调）
     * ⇒ 点击注入与游戏渲染之间没有同步点，偶发抓到旧帧/过渡帧。
     *
     * 开启后两处闸门加固（均只在后台生效，前台逐位不变）：
     * ① 指纹闸门「等满未变」不再直接按原样解析，而是**重发同坐标点击**（幂等）再等一轮；
     * ② 指纹闸门放行条件从「指纹变了」收紧到「变了且下一拍仍稳定」（治半渲染过渡帧）。
     */
    internal val backgroundFrameCompensation: Boolean = false,
    /**
     * 翻 N 页早停（调试翻页准确性用；默认不限制）。 */
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
        // ★ 复核（2026-10-13）：**先定深度再跑前导** —— `runPreamble` 会打一行本轮生效的
        //   `timing: … pipe=N …`（canonical 摘要），而 `applyFlowPipeDefault` 以前在它之后跑 ⇒
        //   摘要里报的是"命令里给的"深度，不是**实际生效**的（例如白名单流程被显式 pipe=2 拉回 0 时，
        //   摘要仍写 pipe=2）。归因时这行是被当作事实读的，不能报一个本轮没用的值。
        applyFlowPipeDefault()
        runPreamble()
        // ★ simple 分支（spec §1/§4，2026-10-07）：DSL 运行时已下线 —— 按 flowName 分派到
        //   原生步序（weapon/artifact/character/lock/equip + 两条标定流；dict/vars/步序常量
        //   已由 scan/flows/* 携带）。未知名字回落 artifact_scan（与 ScriptRunner 同口径）。
        when (flowName) {
            "weapon_scan" -> runWeaponSteps()
            "artifact_scan" -> runArtifactSteps()
            // ★ 2026-10-14：套装筛选后扫描（adb 调试工具）——在 artifact_scan 前先按
            //   LockFlow.SET_FILTER 应用一次套装筛选；筛选未生效即在 runArtifactSteps 内抛
            //   ScanAbortedException 中止，绝不退化全量扫。pipe 默认 0（不在 PIPE_DEFAULT_BY_FLOW）。
            "artifact_scan_set" -> runArtifactSteps(setFilterStep = LockFlow.SET_FILTER)
            "character_scan" -> runCharacterSteps()
            "artifact_lock" -> runLockSteps()
            "auto_equip" -> runEquipSteps()
            "char_calibrate" -> runCharCalibrateSteps()
            "setfilter_calibrate" -> runSetFilterCalibrateSteps()
            else -> {
                Log.w(TAG, "unknown flow '$flowName'; default to artifact_scan")
                runArtifactSteps()
            }
        }
        runPostamble()
    }

    /**
     * ★ A③：把本流程的**默认管道深度**落到 [TimingOverrides.pipeDepth]（在分派前、取帧前置之后）。
     *
     * 显式给过 `pipe=` 时一字不动（A/B 与回退把手见 [TimingOverrides.pipeExplicit]）；
     * 与 `timing` 的 `pipe` 分支同口径：深度 >0 必须同时开 `crop`，因为消费者只拿得到裁片，
     * 「整帧路径 + 流水线路径」并存会留语义漂移（原注释见 TimingOverrides.pipeDepth KDoc）。
     */
    internal fun applyFlowPipeDefault() {
        val allowed = PIPE_DEFAULT_BY_FLOW.containsKey(flowName)
        if (!allowed && TimingOverrides.pipeDepth > 0) {
            // ★ 写类流程（artifact_lock / auto_equip）与角色扫描**一律不许开管道**，
            //   连显式 `pipe=` 也不行：它们在**下一格开头**读回本格投票位来决定点不点写操作
            //   （LockFlow.kt 的 curLock、ScanJudgmentDomain 的写流程分支），而管道下这些位
            //   滞后 ≤pipeDepth 格 ⇒ 拿旧状态点写按钮。以前这条约束只写在注释里，
            //   而 `pipeExplicit` 让任何人都能把它绕过去 ⇒ 约束落到代码上。
            Log.e(
                TAG,
                "pipe=${TimingOverrides.pipeDepth} 对 flow=$flowName 不生效 ⇒ 强制回同步（写类/角色流程不能开管道，见 PIPE_DEFAULT_BY_FLOW）",
            )
            TimingOverrides.pipeDepth = 0
            return
        }
        if (TimingOverrides.pipeExplicit) {
            Log.i(TAG, "pipe 深度取显式值=${TimingOverrides.pipeDepth}（flow=$flowName 的默认被覆盖）")
            return
        }
        val d = PIPE_DEFAULT_BY_FLOW[flowName] ?: 0
        if (d == TimingOverrides.pipeDepth) return
        TimingOverrides.pipeDepth = d
        if (d > 0) TimingOverrides.panelCrop = true
        Log.i(TAG, "pipe 深度取流程默认：flow=$flowName ⇒ depth=$d${if (d > 0) "（crop 强制开）" else ""}")
    }


    /**
     * run() 的**前半段**（simple 分支抽出，一字未改）：计时/观察计数复位、PerfProbe.reset、
     * §15 流程前置归位（returnHome）。原生入口（runWeaponScanNative 等）与 JSON 路径共用。
     *
     * @param returnHomeOverride 原生流程的显式归位声明（null = 读 flowJson.returnHome，JSON 路径）。
     *   标定流 char_calibrate 原 flow 显式 `returnHome:false`、setfilter_calibrate 未声明
     *   （真机启动路径默认 true）—— 原生层以此参数平移该语义。
     */
    internal suspend fun runPreamble(returnHomeOverride: Boolean? = null) {
        tmStartMs = clock()
        tmCells = 0; tmPages = 0; tmNavMs = 0; tmPanelMs = 0; tmSettleMs = 0
        RollSolver.resetMissCount() // P0-B：solver 未解计数随 run 复位（同观察类计数口径）
        // ★ 2026-09-24：观察类计数在此复位。它们的 KDoc 一直写着"随 run 重置"，但此前**没有任何复位点**
        //   ⇒ 同进程连跑两轮时第二轮的 `scan finished` 摘要会把上一轮的数一起算进去。
        dupRevisits = 0; dupRevisitRecovered = 0; pageFreezeAbandoned = 0; swallowedClickRetries = 0
        clickGiveups = 0; weaponStaleDropped = 0
        dupRollbackVetoes = 0 // 件数否决计数（P0 2026-10-21）：随 run 复位，与上面同一批观察量同点
        previewRowCells = 0; previewRowAdded = 0; advanceBacktracks = 0
        navRedundantSkipped = 0 // #233 单2：冗余复位导航的跳过计数（与上面同一批观察量同点复位）
        // ★ #241（2026-10-13）：重裁链三计数同点复位 —— 不给复位点就会重演 2026-09-24 那次
        //   「第二轮摘要把上一轮的观察数一起报」（同 :239-240 的教训）。
        recropAttempts = 0; recropNewCandidates = 0; recropRescued = 0
        craftedRescues = 0
        weaponOverlapRepeats = 0; weaponIdentityRuns.clear(); lastEmittedWeaponIdentity = null
        unknownSetPieces = 0; charUnresolved = 0; charTalentUnread = 0
        // ★ #265：随 run 复位，与其它观察量同一处。这个量只在 visit 第 ② 步被读，而那时**本格还没投票**
        //   （星级在第 ⑤ 步才投）⇒ 它天生读的是"上一格的读数"；不清零就会让新一轮/新计划项的
        //   第 1 格直接继承上一轮尾部那件低星 ⇒ 一开场就止扫。
        lastVotedWeaponRarity = -1
        weaponDictMiss = 0; weaponDictMissNames.clear()
        panelShotSeq = 0; panelShotDropped = 0; panelShotWriteFailures = 0
        panelShotSpaceCheckedAt = 0; panelShotSpaceOk = true
        // 帧新鲜性四个计数以前**不在这里**复位（引擎目前每轮新建 ⇒ 没暴露成 bug）。
        // 一并纳进来：它们和上面那批一样是"每轮独立"的观察量，靠构造函数默认值撑着，
        // 谁哪天改成复用引擎就会重演 2026-09-24 那次"第二轮把上一轮的数一起报"。
        freshFrameTimeouts = 0; frameRefusedTotal = 0
        settleFrameTimeouts = 0; settleStaleFramesUsed = 0
        PerfProbe.reset() // 只读探针：每轮扫描独立统计
        Log.i(TAG, "timing: ${TimingOverrides.summary()} | ${CpuTelemetry.snapshot()}")
        // §15 流程前置归位：GOODScanner `GenshinGameController::return_to_main_ui` 同思想
        // （循环点返回直至主界面），各流程启动前统一回到 game_home，避免残留弹窗/界面错位。
        // 默认 false：仅 ScriptRunner 真机启动路径显式开启（干跑单测不注入 → 不产生归位点击/取帧）
        val returnHome = returnHomeOverride ?: flowJson.optBoolean("returnHome", false)
        if (returnHome) {
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
    }

    /**
     * run() 的**后半段**（simple 分支抽出，一字未改）：endConditions 的 total 核对、
     * `scan finished` 摘要行、probe 行、listener.onFinished。原生入口与 JSON 路径共用。
     */
    internal suspend fun runPostamble() {
        // endConditions：total 核对。
        // ★ A10：对账口径必须与 pagedGrid 的「件数达标」判据**同源**（那边就是三者之和）。
        //   单轮扫描只有一个 flow 域的容器在增长（weapon_scan 只进 resultsWeapons、artifact_scan
        //   只进 results、character_scan 只进 resultsCharacters），合计即该域实收件数；
        //   旧代码只看 results.size（只装圣遗物）⇒ weapon_scan 正常收尾时 0 != N ⇒
        //   必报假 WARN「总数不符」+ total_mismatch。
        // ★ #188（2026-10-04）：豁免条件从「只要 stopRequested 就不查」收窄成
        //   「**提前收工**才不查」。旧条件把「回卷止扫」也当成异常中断一并豁免了，而回卷
        //   （连续重复件 ≥ 阈值）与「连续整页跳过」恰恰是全量扫描**正常遍历完**的收尾方式 ——
        //   于是 10-03 那轮武器 读到 206 / 实收 203 一声不响。
        //   （订正：原记"尾页 5 格解析成空、静默丢 2 件"是拿一个**武器路径从不赋值**的日志栏
        //     下的判决，2026-10-04 复盘已推翻，真因见 #188/#216；差额本身是真的，成因不是它。）
        //   判"到底"用现成的 `gridExhausted`（只有那两处置 true），
        //   再叠 `!matchHit` 与 foreach 那道"到底但没命中目标"的守卫同口径。
        // ★ #216（2026-10-04）：命中**素材停止标识**也算"看到尽头"。强化素材排在列表最末
        //   （武器：学徒笔记 → 魔矿/良矿/杂矿；圣遗物：最后一件真圣遗物 global=947 → 祝圣精华 948），
        //   所以撞到素材 = 装备读完了 ⇒ 总数必须对得上。这一条同时是 #216 自己的**验收判据**：
        //   标识若误触发在装备中间，这里会立刻报"总数不符"而不是悄悄少一批。
        //   ✅ #213（2026-10-04 同日补）：`pagedGrid`/`snapTraverse` 以「peek 消失（指纹不变）」
        //     收尾时以前不置 `gridExhausted`（见 ScanJudgmentDomain:237 记的那次错装事故）⇒ 那条路
        //     照旧豁免。现在那条出口会置 `gridExhausted=true`（只在 reachedEnd 那一支；
        //     轮数兜底/stopRequested 两个"中途放弃"出口不置），所以也被这条核对覆盖了。
        //     ⚠️ 但覆盖到不等于量到：走 snap 的只有 `select_3col`（auto_equip），而它不读背包
        //     计数器 ⇒ `total==null`，这里今天仍然不会为它报警。真实收益在 foreach 那道守卫上。
        val collected = results.size + resultsWeapons.size + resultsCharacters.size
        // ★ 筛选流程（artifact_scan_set）**不读**表头总数：那 974/2700 是全量持有量、不随筛选变，
        //   与筛选后的网格不同量纲 ⇒ `total` 保持 null，本判据在筛选下自动不启用（而非误报）。
        val total = vars.total
        val sawWholeList = !vars.stopRequested || (vars.gridExhausted && !vars.matchHit) ||
            vars.stopReason == STOP_REASON_MATERIAL
        if (total != null && sawWholeList && total != collected) {
            Log.w(TAG, "total mismatch: readCount=$total scanned=$collected stopReason=${vars.stopReason}")
            RecognitionLog.log(
                logTag,
                RecognitionLog.Level.W,
                "总数不符 读到=$total 实扫=$collected（收尾=${vars.stopReason ?: "自然结束"}）",
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
                "预览行补读=${previewRowCells}格/${previewRowAdded}件 " +
                "落地回退=${advanceBacktracks}次 " +
                "吞击重发=$swallowedClickRetries 点击放弃=${clickGiveups}格 定点重访=$dupRevisitRecovered/$dupRevisits " +
                "武器陈旧丢弃=${weaponStaleDropped}件 " +
                // ★ #188：只数 rarity>=3 的未命中（素材未命中是对的，见计数器 KDoc）。
                //   >0 就是**词典缺一件真武器** ⇒ 这一格会静默不入库，对账侧表现为"缺 N 件"。
                (if (weaponDictMiss > 0) {
                    "武器词典缺=${weaponDictMiss}格(${weaponDictMissNames.joinToString("/")}) "
                } else "") +
                // ★ #104：只观测、已照常入库 —— 对账"多 N 件"时先看这个数，别把它算成丢件或真多出来的装备
                (if (weaponOverlapRepeats > 0) "武器重叠重读=${weaponOverlapRepeats}件(已入库) " else "") +
                "页级冻结放弃=${pageFreezeAbandoned}格 未知套装=${unknownSetPieces}件 " +
                // ★ P0-B（2026-10-08）：solver 不可解率观测 —— >0 ⇒ 有件按"原样导出"走（roll 未解出），
                //   持续 >0 就是补图像级重试链的依据（GS 对齐 P0-2，见 simple-conversion-spec §3.6）。
                (if (RollSolver.missCount > 0) "roll未解=${RollSolver.missCount}件 " else "") +
                // ★ #241（2026-10-13）：重裁抢救链三口径，紧挨 roll未解 放（两者同解「读失败」这一个问题）：
                //   进链件数/重读新增候选（去重后）/重裁解出件数。链没进（正常轮）整段不打 ——
                //   同 roll未解 的条件式零噪声；`抢救>0` 才证明链在兜底在救人，`重裁-抢救` 的差
                //   就是链跑了却没救回的件数，`新候选≈0` 则说明重读根本没读出东西。
                (if (recropAttempts > 0) {
                    "重裁 n=${recropAttempts}/新候选 ${recropNewCandidates}/抢救 ${recropRescued} "
                } else "") +
                // ★ 2026-10-20：祝圣横幅漏投的空词条自愈数（条件式，正常轮为 0 不出声）。
                (if (craftedRescues > 0) "祝圣对齐自愈=${craftedRescues}件 " else "") +
                // ★ #156：身份未解析的位数（照常入库、导出无 key）。>0 就说明昵称表又漏人了，
                //   对账侧会同时报"多 N 条未解析 + 缺 N 个真角色"。
                // ★ #274：天赋没读成的角色数（0 不是合法值）——条件式打印，与 `未解析身份` 同一族。
                //   这条计数的存在理由见字段 KDoc：交替页签序漏了反序格那半，跑完只看得见件数。
                (if (charTalentUnread > 0) "天赋未读=${charTalentUnread}件 " else "") +
                "未解析身份=${charUnresolved}位 " +
                // ★ #46：取证落盘的闸门计数只在**有话说**时出现（默认关取证 ⇒ 全 0 ⇒ 不打，免噪声）
                (if (panelShotSeq > 0 || panelShotDropped > 0 || panelShotWriteFailures > 0) {
                    "| 取证图 落=${panelShotSeq}张 闸门丢弃=${panelShotDropped}张 写失败=${panelShotWriteFailures}张 "
                } else "") +
                // ★ 批2-A：帧新鲜性计数，同样只在非 0 时打。`旧帧顶替>0` 就意味着那一格
                //   识别的是**动作之前**的画面 —— 与"画面真的没变"在下游同形，是静默错读的口。
                (if (freshFrameTimeouts > 0) {
                    "| 帧新鲜性 超时=${freshFrameTimeouts}次 不回退旧帧=${frameRefusedTotal}次 "
                } else "") +
                // ★ #196：判稳侧单独一档。它非 0 **不算问题**（静止画面本来就不推新帧），
                //   报出来是为了和上面那条对照 —— 以前两类共用一个名字，`旧帧顶替=1`
                //   到底是"某格拿过期画面做了识别"还是"静止页上判稳等了一下"分不开。
                (if (settleFrameTimeouts > 0) {
                    "判稳无新帧=${settleFrameTimeouts}次(用缓存帧 ${settleStaleFramesUsed}次) "
                } else "") +
                "perCell=${perCell}ms perPage=${perPage}ms | waits nav=${tmNavMs} panel=${tmPanelMs} settle=${tmSettleMs} " +
                    // ★ #233 单2：**无条件**打印（不走"非 0 才打"那条惯例）—— 这一条是 A/B 的
                    //   机制判据，恒 0 意味着页判据从没命中过（故障而非"没优化到"），
                    //   条件式打印会把"没省"和"没打这行"糊成同一件事。
                    "| 正序复位导航 跳过=${navRedundantSkipped}格",
        )
        // 只读性能探针：click/swipe 真实注入耗时 + OCR 网关耗时（分量实测，供
        // dsl/verify/_audit/IMAGE-PATH-COST.md §8 决策）。**独立一行**，避免打乱既有日志正则。
        // ★ #230：行尾再读一次钳频/温度，与 `timing:` 那行的**起跑**读数配对 ——
        //   没有这一对，"这一轮慢"就分不清是代码慢还是中途被 EMUI 压了频。
        Log.i(TAG, "probe ${PerfProbe.summary()} | sigSamples=$tmSigSamples | ${CpuTelemetry.snapshot()}")
        listener.onFinished(reason)
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

    /**
     * 已入库件数（三条容器的合计；单轮只有一个域在增长，口径同 [runPostamble] 的 total 核对）。
     * 目前只有一个读者：管道消费者按 job 前后求差得「本格追加了几件」（#257）。
     */
    internal fun engineCellCount(): Int = results.size + resultsWeapons.size + resultsCharacters.size

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
    // ★ #233 单2④（2026-10-14 删）：这里原本有个**不清空**的 `lastCharName`，专门喂给
    //   `stopWhen` 的 `name == roster[0]` 特判（`emit` 会清 `charName`，那条判据直接读它会恒空）。
    //   判据本身已随分支一起删（角色流改用 mode=duplicateStreak，见 CharDupJudge），字段留着就是
    //   一个"只有人写、没人读"的变量 ⇒ 下一轮诊断会以为角色轮还有"回绕即停"这条路。
    /** 连续「已入库过的角色」个数（回卷/遍历完判据）；由 stopWhen mode=duplicateStreak 启用。 */
    internal var charDupStreak = 0
    internal var charDupStreakLimit = 0
    /**
     * #206：本格 visit 已判重命中（该角色本轮已入库）⇒ 命之座/天赋两跳可省。
     * `runVisit` 每格清零；置位后后续 visit 步只放行 `stopWhen`/`emit`（止扫记账与"重复不入库"都靠它们）。
     */
    internal var visitSkipTail = false

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

    /** S1 裁片复用缓冲（#69）：每格 copyTo 进这里，尺寸不变时零重分配；ScriptRunner 收尾 release。 */
    internal val panelCropBuf = Mat()

    /** 上一页的键序列（**含读失败的空串占位**，下标 = 页内序号 —— 占位必须保留，否则前进量算错）。 */
    internal var rowCheckPrevKeys: List<String> = emptyList()

    /** 行级闭环统计（进 scan finished 摘要）：判跳行 / 判滑空。 */
    internal var rowCheckSkips = 0
    internal var rowCheckStalls = 0
    /** ★ 2026-10-20：OVERLAP 页补读第 4 行的格数/新增件数（进 scan finished 摘要）。 */
    internal var previewRowCells = 0
    internal var previewRowAdded = 0
    /** ★ 2026-10-20：落地条带拒测后「回退一行做缺口校验」的次数（进 scan finished 摘要）。 */
    internal var advanceBacktracks = 0

    /** 单件名→套装反查未命中的件数（词典缺口，件本身已照常入库；见 emit 处的拆分判据）。 */
    internal var unknownSetPieces = 0

    /** 三档解析全未命中、按显示名入库的角色数（#156；这些记录**没有 GOOD key**，去管理器页补昵称）。 */
    internal var charUnresolved = 0

    /**
     * 入库时**三项天赋全为 0** 的角色数（#274）。0 不是合法值（全部战斗天赋基础等级恒 ≥1，
     * 见 `CharacterDomainPanels.kt:1320` 的实测注释），所以这个数就是"天赋根本没读成"的件数。
     * 立这条计数的原因：2026-10-10 的「交替页签序」把**反序格**的天赋读整条漏掉了，
     * 10-13 全量轮实测 43/95 件 `t=[0,0,0]`，而日志里连一条"跳过了"都没有 ⇒
     * 静默分支必须留下可数的痕迹（同 #152 那条 else 日志的教训）。
     */
    internal var charTalentUnread = 0

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

    /**
     * ★ #233 单2（2026-10-14）：本轮**判定已在属性页 ⇒ 省掉那次复位导航**的格数（随 run 复位）。
     *
     * 为什么单独记一个数：正序格的 `navigate(属性)` 是按构造冗余的（进正序格的前提就是面板已在
     * 属性页），但它今天**每格都点**。改成先做一次正向页判定之后，"省了多少"只能从这个计数看，
     * 因为 `waits nav=` 只反映总时长、说不出是不是同一件事在少。
     * ⚠️ 计数恒 0 = 判据从来没命中 ⇒ 那不是"没优化成"，那是页判据读不出来（ROI 漂了/词典外文本），
     *   要当故障查（同 [[feedback-verify-action-by-reread-state]]：走了另一条路要能读出来）。
     */
    internal var navRedundantSkipped: Int = 0


    internal var lastWeaponIdentity: String? = null
    internal var weaponSameRun: Int = 0
    internal var weaponStaleDropped: Int = 0

    /**
     * ★ #241（2026-10-13）：roll 重裁抢救链的会话计数（随 run 复位，进 `scan finished` 摘要）。
     *
     * 为什么要这三个：链此前**只有失败可见** —— 失败经 `RollSolver.logMiss` 进 `roll未解`，
     * 而重裁救回与「首读就解出、链没进过」在计数上完全同相 ⇒ 摘要永远说不出
     * 「这 N 件本来更多、是链救回来的」，也读不出 ≈1% 进链件的成败比。
     * - [recropAttempts]：进入重裁链的件数（链入口 +1；只可能是 4★/5★，3★ 被闸门挡在链外）；
     * - [recropNewCandidates]：重读经去重门槛真正 append 的候选总数（append 处累计，成败都算）；
     * - [recropRescued]：重裁后解出的件数（「重裁抢救成功」分支 +1）。
     * 三数合看才成立：`抢救>0` 证明链在兜底；`重裁>0 而 新候选≈0` ⇒ 重读根本没读出东西
     * （去查链内按裁法的重读异常/丢字）；`重裁-抢救` 的差就是跑了没救回的件数。
     */
    internal var recropAttempts: Int = 0
    internal var recropNewCandidates: Int = 0
    internal var recropRescued: Int = 0

    /**
     * ★ 2026-10-20：祝圣横幅漏投后经「另一套 yShift 对齐重读 + solver 校验」救回的件数
     * （随 run 复位，进 `scan finished` 摘要）。
     *
     * 恒 0 且后台仍报「丢弃读失败条目（词条 0 条）」 ⇒ 说明丢的**不只是**横幅漏投这一类，
     * 别把这条兜底当成"0 漏"的证明；>0 则每一件都能和 GT 里 elixerCrafted=true 的件对上。
     */
    internal var craftedRescues: Int = 0

    /**
     * ★ #265：**生产者自己**在本格投出来的武器星级（`vote` 的 `weapon.panel.starBand` 两条分支写）。
     *
     * 只有一个读者：[stopWhenRarityAtMost2]，它在 visit 第 ② 步求值 ⇒ 量的是**上一格**的星级。
     * 同步臂下 `vars.rarity` 恰好等于这个值（vote 直接写 vars），所以旧写法看不出差别；
     * 管道臂下 `vars` 由消费者按它自己的格装载 ⇒ 止扫点会随积压深度漂 ⇒ 尾部那几件低星
     * 两臂收得不一样（`PAGES=3` 的验收跑走不到列表末尾，撞不到这条）。
     * 与 [CellVotes] 是同一族的教训：**生产者要用的量必须留在生产者侧**，不能借共享 vars 传话。
     * `-1` = 未判定哨兵（与 `vars.rarity` 同口径，见 [stopWhenRarityAtMost2] 的守卫）。
     */
    internal var lastVotedWeaponRarity: Int = -1

    /**
     * **在扫描星级区间内**读到了武器名、却查不到词典 key 的格数（#188，2026-10-04）。
     *
     * 为什么不能只数"未命中"：武器背包尾部混着**强化素材**（精锻用魔矿/良矿/杂矿），
     * 它们不是武器、GT 也不收 ⇒ 查不到词典是**正确行为**，全记进这个计数器就变成
     * 每轮都有的噪声，没人会看。
     * ⚠️ 两个门槛都是结构性的，**不是**"素材星级低所以挡得住"：实测同一轮里
     *    「精锻用魔矿」星带读数是 **rarity=3**（良矿 2、杂矿 1）⇒ 只按 `rarity>=3` 挡会漏，
     *    10-04 头一版就每轮把魔矿记成缺件。第二条 `level>0` 才挡得住：素材面板**没有
     *    `Lv.N/M` 那一栏**（原文第 2 槽是"武器经验道具"，实测四格全 `Lv=0`），
     *    真武器恒有等级行（学徒笔记 `Lv.1/20` ⇒ level=1）。
     *    两处判据见 [ArtifactDomainPanels] 的 parseWeaponPanel。
     * 剩下的"未命中"就只剩一种含义：**词典漏了一件真武器**（A' 段之后仍漏 = 新武器上线）。
     * 名字去重后一并打出来，好让人直接去 `gen_mappings.py` 查是哪张源表缺了。
     */
    internal var weaponDictMiss: Int = 0
    internal val weaponDictMissNames = LinkedHashSet<String>()

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
     * ★ #292：本页**实测前进行数** `advanceRows = anchoredD / cols`；-1 = 未锚定
     * （前进量未知 ⇒ [crossPageOverlap] fail-open 不判）。
     *
     * 为什么前进量必须来自 [anchoredD] 而不是几何量：`GridAlign.rowsAdvanced` 在每一页都读
     * `行数=3`（含已知只前进 2 行的页）⇒ 有固有 +1 偏置、不可用；[GridRowCheck] 的 advance 要到
     * **页末**才有 ⇒ 页内逐格判据拿不到。C' 锚点的 `d` 是内容实测（逐位相邻命中），且与页末
     * 行级闭环读数一致（实测 d=14 与「前进 14」同值）。
     */
    internal var curAdvanceRows: Int = -1

    /**
     * ★ #292：本页**武器格 idx → [resultsWeapons] 下标**（页内局部语义，每页清空）。
     *
     * 动机：锚点在读完 idx2 之后才定出 `d`，而那时 idx0~2 **已经访问并入库**了 ⇒ 它们的跨页
     * 判据当时拿不到前进量（fail-open，没丢）。锚点定出 `d` 后靠这张表**追认**：
     * 若 idx0~2 按同一个门控公式命中上页重叠格，就把已入库的那件撤回来（武器侧**没有**内容键
     * 去重 ⇒ 不撤就多一件）。
     *
     * 只武器路径写（[crossPageOverlap] 的唯一调用点）；圣遗物侧恒空（走 `seenArtifactKeys` 内容键去重）。
     */
    internal val curPageWeaponEmit = HashMap<Int, Int>()

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
     * ★ #317：最近一次格点击的**帧坐标**（visitClick 写入）。
     * 供 [parsePanel] 的指纹闸门在「等满未变」时**同坐标重击**（幂等，见
     * [backgroundFrameCompensation]）。-1 = 本格未点过（snap/rosterFind 等非 pagedGrid 路径）
     * ⇒ 闸门不做重击。
     */
    internal var lastCellClickX: Int = -1
    internal var lastCellClickY: Int = -1
    /** 本格点击注入时刻（elapsedRealtime）；0 = 本格未点过。用于面板闭锁图标的沉降闸。 */
    internal var lastCellClickAtMs: Long = 0L
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
     *
     * ⚠️ 上面那句"MediaProjection 阻塞时不会前进"**与代码相反**（2026-10-03 核对）：
     * [freshFrame] 把这一行打在真正取帧**之前**，所以投影彻底停摆、每轮都超时回退时它照样前进，
     * 会话看门狗永远不触发。修法在批 2-B（连同删回退一起做），本批只把计数补上。
     */
    @Volatile
    var lastProgressAtMs: Long = 0L
        internal set

    /**
     * ★ 批2-A（2026-10-03，**只观测不判决**）：[freshFrame] 等不到"动作后新帧"的次数。
     *
     * 为什么要单独数：`freshFrame` 超时后会**回退拿缓存里的最后一帧**继续识别
     * （`ScanGridDomain.kt:1404-1419`），而那条回退按构造拿到的就是被新鲜性判据拒掉的那一帧
     * （`FrameSource.grabFresh` 的循环条件就是 `ts > threshold`，回退读的又是同一个
     * `publishedBgr` 槽，`frameGeneration()` 与它同源同锁）。于是
     * **"取不到新帧"和"画面真的没变"在下游完全同形** —— #147/#154/#148 那一族症状没法判别，
     * 很可能就是因为缺这个数。
     *
     * ⚠️ 本批**不改任何判决**：`lastProgressAtMs` 仍打在取帧之前（下面那条注释说的正是这件事），
     * 会话看门狗因此看不见"无帧"。等这一轮拿到分布，再在批 2-B 同时改"打点位置 + 删回退 + 配额判决"。
     */
    @Volatile
    var freshFrameTimeouts: Int = 0
        internal set

    /**
     * 超时后**按策略不回退**（识别用帧）或**无缓存帧**（判稳用帧）⇒ 最终抛给上层的次数。
     *
     * ★ #196 第二版（2026-10-04）改了语义：以前只有"连旧帧都没有"才计这里，
     *   有旧帧就静默顶上去（那一栏叫 `旧帧顶替`）。现在识别侧**一律不回退** ⇒
     *   华为那种退化轮里这个数会从 0 变成 >0，而**这正是想要的现象**：
     *   失败变响，而不是拿动作之前的画面做 OCR 当成新格入库。
     *   `旧帧顶替` 那一栏因此被删 —— 它已经结构性不可能 >0，留着一个永不动的计数
     *   就是我们都反对的那种"看着有监控、其实没人读"的死判据。
     */
    @Volatile
    var frameRefusedTotal: Int = 0
        internal set

    /**
     * #196 拆分（2026-10-04）：**判稳/重读**侧的对应两个计数，与上面两个**不是一回事**，别合并。
     *
     * 在判稳语义下"没有新帧"是**证据**（画面没再动）而不是故障 ⇒ 这两个数非 0 不代表有问题。
     * 它们存在的意义是给识别侧那两条当**对照组**：以前两件事共用 `freshFrame` 一个名字，
     * 所以摘要里 `旧帧顶替=1` 到底是"某格拿过期画面做了识别"还是"静止页上判稳等了一下"
     * **根本分不开** —— 这正是 #196 的原始症状。
     */
    @Volatile
    var settleFrameTimeouts: Int = 0
        internal set

    @Volatile
    var settleStaleFramesUsed: Int = 0
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

    /**
     * 断言回卷所需的连续整页零新增页数（flow `stopWhen.dupPageConfirm`，默认 2；=1 恢复旧的立即停行为）。
     *
     * ⚠️ #219：唯一赋值点是 [stopWhenRegisterDuplicateStreak]。**五条原生 flow 现在各自在
     * `pagedGridNative(...)` 之前登记一次**（Artifact 3/1、Weapon 3/1、Lock 3/2、Character 3/2、
     * Equip 3/2）⇒ 格循环第一次读它时就已经是 flow 声明的值；流程体内保留的同值登记被守卫
     * 静默跳过（同步臂此前是**逐格**重复登记并逐格打日志，把"值真的变了"淹掉）。
     * 为什么值得提前：判据在**格循环里**读（`dupRollbackConfirmed` 每页只判一次，`dupPageDecided` 闩住），
     * 而 page0 的第一读发生在第 0 格 visit 体之前 —— 旧写法下那里读到默认 2，flow 声明 1 意图
     * "一次命中即停"的流程要多耗一整页才停（并让入口日志报假值）。
     * flow 未声明 `dupPageConfirm` 时保持本默认 2（`stopWhen` 的 `optInt(…, 2)`；标定流即此档）。
     */
    internal var dupPageConfirm = 2

    /** 本页是否已做过回卷判定（同一页 item 级 + 格级两处调用只记账一次）。每页开头复位。 */
    internal var dupPageDecided = false

    /** 本页判定的结果（配合 [dupPageDecided] 在同一页内复用，避免重复计数）。 */
    internal var dupPageStopConfirmed = false

    /**
     * 件数否决计数（P0，2026-10-21）：pagedGrid 尾部欠滚时「整页重复」可能是滑空造成的假回卷，
     * `dupRollbackConfirmed` 在「剩余量 ≤ 一页+一行」时否决停止；本计数限制**连续**否决次数
     * （上限 3），用尽后不再否决、允许回卷止扫收尾（防计数器偏大时无限翻页）。
     */
    internal var dupRollbackVetoes = 0

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

    /**
     * ★ #281：本格是否已经扣过命座天赋加成（`resetCharDraft` 复位）。
     * 扣减点从 `readTalent` 挪到入库时点之后，**幂等就不能再靠调用顺序保证** ——
     * 重复扣与漏扣在导出里都长得像合法等级（静默），所以宁可用一个布尔位钉住。
     */
    internal var talentBonusApplied = false

    /** 当前角色的词典 key（供入库时的 `applyTalentBonusForEmit` 查词典 c3/c5 减命座加成）。 */
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

    // ── S2b 解析流水线（#69，timing key pipe=K；0=同步路径零执行）────────────────────
    //
    // 生产者（visit 的 parse 步）：指纹闸/取证/裁片拷贝 → send；消费者（单协程，
    // Dispatchers.Default）按 FIFO 序做 OCR/解析/判据/emit。K 只决定"藏多深"
    // （点击/门/翻页窗口藏进消费者积压），不增加并行度（§9：双消费者默认不上）。
    // 页末 join（pipeJoinPage）保证页末机制（空读回读/锚定/dupPageConfirm/stopWhen total）
    // 拿到的结果与现状同时刻可用；取消语义（§6.1）：close 后缓冲里的 job 只释放裁片。
    // 池帧早还（§4）：裁片拷进 job 私有 Mat 后立即 ctx.release（vote 已在此之前完成）。

    /** 每格解析 job：生产者（visit）闸门+裁片后发送，单消费者按 FIFO 序处理（take 序=格序）。 */
    internal class ParseJob(
        val id: Int,
        val epoch: Int,              // 该格重访纪元（回读/定点重访重发时 ++）
        val gridKey: String,
        val col: Int, val row: Int, val page: Int,
        val crop: Mat,               // job 私有裁片（池化复用，complete 归还；非池帧别名）
        val origin: FramePoint,
        val parseStep: JSONObject,   // parsePanel 的 step JSON（dict/match）
        val emitStep: JSONObject?,   // 后随 emit 步（GoodCharacter 的止扫判据在 emit 求值）
        val stopStep: JSONObject?,   // ★ S2b：本格 stopWhen 步（逐格步，判据要读本格 vars ⇒ 必须随 job）
        val panelKey: String,
        val idx: Int,                // 本格在 pageKeys/pageIdentities 的槽位（页末排水用）
        val votes: CellVotes,        // ★ #69：本格投票位快照（生产者写 vars 会与消费者串格，见 CellVotes）
        val cols: Int,               // ★ #263：发送时的网格列数/遍历行数（跨页积压时消费者不读活值）
        val traverseRows: Int,
    ) {
        val cellKey: String get() = "$gridKey:$col:$row"
        // ★ #263：本格坐标上下文 —— 消费者解析时必须用它自己的，不能读引擎上的活值。
        fun cellCoord() = CellCoord(page, row, col, idx, cols, traverseRows)
        // 消费者解析完成后回填（单消费者顺序执行，下一 job 解析前必已被生产者读取）：
        var resultKey: String? = null       // ← lastCellKey（contentKey；空读为 null/空）
        var resultIdentity: String? = ""    // ← lastCellIdentity
        var resultAdded: Int = 0            // ← 本格实际追加进 results/resultsWeapons/resultsCharacters 的件数
    }

    /** 页末排水的一条：本格（idx = pageKeys/pageIdentities 槽位）由消费者产出的结果。 */
    internal data class PipeCellResult(
        val idx: Int,
        val key: String?,
        val identity: String,
        val added: Int,
    )

    /**
     * ★ #263：**本格坐标**（页号 + 行 + 列 + 页内格序 + 该页的列数/遍历行数）。
     *
     * 引擎上的 `curPageNo/curCellRow/curCellCol/curCellIdx/curCols/curTraverseRows` 是**生产者正在
     * 推进的活值**；`pipe>0` 时解析与 emit 跑在消费者线程上，生产者此刻已走到后面 ≤pipeDepth 格 ⇒
     * 消费者直接读活值就是「按 B 格的内容判 A 格的坐标」。干跑里消费者 0ms 就追得上 ⇒
     * 两者恒等、单测抓不到；真机一格 ≈0.5~11s ⇒ 会真的错位。
     *
     * 危害不止日志：武器的 `crossPageOverlap`（比上页同列 row1/row2）与 `weaponCellEmitted`
     * 的「页:行:列:身份」同格键都拿坐标当**丢件判据**，坐标错一格就是丢件/漏挡 ——
     * #60（上页表下标错位）、#73（跨页丢弃没登记）两支都是同一族。
     * 与 [com.bettergi.pocket.scan.VoteJudges.weaponLock] 的 `origin` 同一做法：
     * 参数显式传，同步路径传活值（行为一字不变），消费者传 job 自己的。
     */
    internal class CellCoord(
        val page: Int,
        val row: Int,
        val col: Int,
        val idx: Int,
        val cols: Int,
        val traverseRows: Int,
    )

    /** 同步（非管道）路径的本格坐标：就是引擎上的活值。 */
    internal fun liveCellCoord() =
        CellCoord(curPageNo, curCellRow, curCellCol, curCellIdx, curCols, curTraverseRows)

    private var pipeChannel: Channel<ParseJob>? = null
    private var pipeScope: CoroutineScope? = null
    private var pipeConsumerJob: Job? = null
    private var pipeNextId = 0
    private var pipeInFlight = 0
    /**
     * 消费者**已处理**的 job 累计数（丢弃与解析都算一条），只在 [pipeLock] 下读写。
     * 它是 [pipeAwaitAnchorIdentities] 的活性信号：那条停等要判的是"消费者还活着吗"，
     * 而活性的定义是"有 job 被处理完"，不是"过了多少毫秒"（见 #260 后半的说明）。
     */
    private var pipeDoneCount = 0L
    private var pipeCancelled = false
    private var pipePageNo = 0
    internal var pipeEmitStep: JSONObject? = null
    /** ★ S2b：本格 stopWhen 步（逐格步；生产者在下一格前非阻塞查 stopRequested）。 */
    internal var pipeStopStep: JSONObject? = null
    internal var pipeCellJobSent = false
    /**
     * ★ #69：本格投票位是否暂存在 [pipePendingVars]（`pipe>0` 的格开头为真，见 [CellVotes]；
     * 走了 [pipeAdoptVotesInline] 就转假）。
     * 若某格最终**没走管道**（`panel=char_profile` 的同步解析支），生产者在解析前把暂存值
     * 回写 vars —— 该流程从不 send job ⇒ 没有消费者并发写 vars ⇒ 回写安全；
     * 若两者同时出现（消费者已在跑）则不回写并记 W，宁可慢不可串格。
     */
    internal var pipeCellVotesStaged = false
    /**
     * 生产者的**投票暂存载体**（整只 [ScanVars]，只为让 [com.bettergi.pocket.scan.vote] 的
     * `sink.crafted = …` 与同步路径同型）。只有 [CellVotes] 里那六个逐格字段会被写，
     * 会话字段（plan/total/…）一律不碰 —— 交回与快照都只搬那六位。
     */
    internal val pipePendingVars = ScanVars()

    /**
     * visit 开头（每格一次）：`pipe>0` ⇒ 本格投票位暂存到 [pipePendingVars]，
     * 由 [pipeSend] 随 job 交付消费者。`pipe=0` ⇒ 沿用共享 vars（同步路径零变化）。
     */
    internal fun pipeBeginCell() {
        pipeCellVotesStaged = TimingOverrides.pipeDepth > 0
    }

    /**
     * 本格**不走管道**（同步解析支，如 `panel=char_profile`）时把暂存投票位交回 vars，
     * 使后续 stopWhen/emit 读到的仍是本格的值。
     *
     * ⚠️ 只在"没有消费者在跑"时交回：character_scan 全程不发 job ⇒ [pipeChannel] 恒 null。
     * 若一条 flow 同时有角色面板与管道面板，这里会有活跃消费者正按它自己的格写 vars ⇒
     * 两者互写就是本缺陷的原始形状，所以宁可不交回并记 W（可见退化，绝不静默串格）。
     */
    internal fun pipeAdoptVotesInline(gridKey: String?, col: Int, row: Int) {
        if (!pipeCellVotesStaged) return
        if (pipeChannel != null) {
            Log.w(TAG, "pipe 投票位交回跳过：消费者在跑 grid=$gridKey col=$col row=$row（本格同步解析将读到滞后投票位）")
            return
        }
        CellVotes.of(pipePendingVars).applyTo(vars)
        pipeCellVotesStaged = false
    }

    /** 本格投票位的当前真值来源：暂存中取 [pipePendingVars]，否则取共享 vars（`pipe=0` 同步路径）。 */
    internal fun currentCellVotes(): CellVotes =
        CellVotes.of(if (pipeCellVotesStaged) pipePendingVars else vars)

    /**
     * ★ safetyGuard 降频缓存（②，2026-10-10 用户拍板）：守卫读「退出确认框」要一整帧 + 一次 OCR，
     * auto_equip 一次导航序列里 clickAt 前要跑 ~9 次 ⇒ 光守卫就 9 帧 + 9 OCR。
     * 退出框只可能由 **BACK 键或游戏侧事件**产生，**不可能由 clickAt 产生** ⇒ 同一次 visit 的
     * 连续 clickAt 之间框状态不会变，结果可复用；跨 visit 边界（那里才可能发生 BACK/导航）
     * 必查 —— 每格开头由 [com.bettergi.pocket.scan.ScanControlDomain] 的 runVisit 失效。
     * 一旦某次检查读到「退出」⇒ stopRequested ⇒ 整轮不再点击，缓存无所谓。
     */
    internal var safetyGuardCacheValid = false
    internal var safetyGuardCacheOk = false
    private val pipeSentEpoch = HashMap<String, Int>()
    private val pipeJobPool = ArrayDeque<ParseJob>()
    private val pipeDoneResults = ArrayList<ParseJob>()
    private val pipeDoneLock = Any()
    private val pipeLock = Any()

    /** 页号载体（pagedGrid 每页设置；job 遥测用）。 */
    internal fun pipeSetPage(page: Int) {
        synchronized(pipeLock) { pipePageNo = page }
    }

    private fun pipeAcquireCropLocked(w: Int, h: Int): Mat {
        val it = pipeJobPool.iterator()
        while (it.hasNext()) {
            val j = it.next()
            if (j.crop.cols() == w && j.crop.rows() == h) {
                it.remove()
                return j.crop
            }
            it.remove()
            j.crop.release()
        }
        return Mat(h, w, CvType.CV_8UC3)
    }

    /** 生产者：裁片拷进 job 私有 Mat → send（背压=队列满挂起）。 */
    internal suspend fun pipeSend(
        gridKey: String,
        col: Int, row: Int, idx: Int,
        cropRect: FrameRect,
        frame: Mat,
        parseStep: JSONObject,
        panelKey: String,
    ) {
        val ch = pipeChannel ?: Channel<ParseJob>(capacity = TimingOverrides.pipeDepth.coerceAtLeast(1)).also { created ->
            pipeChannel = created
            pipeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            // ⚠️ 显式传 `created`，**不用 it**：launch 的 lambda 有自己的隐式 it(Unit)，
            //   写 `pipeConsumerLoop(it)` 会在嵌套 lambda 里绑到 launch 的参数（实测排查出的坑）。
            pipeConsumerJob = pipeScope!!.launch { pipeConsumerLoop(created) }
        }
        val key = "$gridKey:$col:$row"
        // ★ 纪元 = **本 job 的世代**（自增后再取）。消费者判据是 `job.epoch < pipeSentEpoch[cellKey]`
        //   （"该格已有更新的世代发出 ⇒ 我是过期件，丢弃"）。若用**自增前**的值打标，最新发出的
        //   那件也恒小于表内值 ⇒ **每格每件都被判过期丢弃**（实测 send/take 各 292、proc 0、join 13，
        //   消费者空转、背压永不成立 ⇒ 引擎一路翻页直到超时）。
        val epoch = (pipeSentEpoch[key] ?: 0) + 1
        pipeSentEpoch[key] = epoch
        val x0 = cropRect.left.coerceIn(0, frame.cols() - 1)
        val y0 = cropRect.top.coerceIn(0, frame.rows() - 1)
        val x1 = cropRect.right.coerceIn(x0 + 1, frame.cols())
        val y1 = cropRect.bottom.coerceIn(y0 + 1, frame.rows())
        val crop = synchronized(pipeLock) { pipeAcquireCropLocked(x1 - x0, y1 - y0) }
        val view = Mat(frame, Rect(x0, y0, x1 - x0, y1 - y0))
        // ★ #252：这次 memcpy 以前**不进任何探针**（管道臂的 `PerfProbe` 摘要里 `crop n=0`），
        //   所以"管道臂每格到底为裁片付多少"一直没人量过 ⇒ 先让它可见，再谈要不要省。
        //   复用同步臂已有的 `crop` 计数（同一件事：建视图 + 拷像素），不新增计数器。
        val tCrop = System.nanoTime()
        try {
            view.copyTo(crop)
        } finally {
            view.release()
        }
        PerfProbe.addCrop(System.nanoTime() - tCrop)
        val job = ParseJob(
            id = pipeNextId++, epoch = epoch, gridKey = gridKey, col = col, row = row,
            page = synchronized(pipeLock) { pipePageNo },
            crop = crop, origin = FramePoint(x0, y0),
            parseStep = parseStep, emitStep = pipeEmitStep, stopStep = pipeStopStep,
            panelKey = panelKey,
            idx = idx,
            votes = currentCellVotes(),
            cols = curCols, traverseRows = curTraverseRows,
        )
        synchronized(pipeLock) { pipeInFlight++ }
        pipeCellJobSent = true
        val t0 = System.nanoTime()
        ch.send(job)
        PerfProbe.addPipeWait(System.nanoTime() - t0)
    }

    /** 消费者循环：FIFO 取 job → 过期丢弃（§6.2）→ 解析/判据/emit → 归还裁片。 */
    private suspend fun pipeConsumerLoop(channel: Channel<ParseJob>) {
        for (job in channel) {
            val stale = synchronized(pipeLock) {
                val latest = pipeSentEpoch[job.cellKey] ?: job.epoch
                job.epoch < latest
            }
            if (pipeCancelled || stale) {
                Log.d(TAG, "pipe job #${job.id} ${if (stale) "过期丢弃" else "取消丢弃"}")
                PerfProbe.addPipeDiscard()
                synchronized(pipeLock) { pipeInFlight--; pipeDoneCount++; pipeReturnJob(job) }
                continue
            }
            val jobBefore = engineCellCount()
            pipeProcess(job)
            synchronized(pipeLock) {
                pipeInFlight--
                pipeDoneCount++
                // 消费者解析完成 ⇒ 回填本格结果（页末排水按 idx 序写 pageKeys，§3.3）
                job.resultKey = lastCellKey
                job.resultIdentity = lastCellIdentity
                // ★ #257：本格**追加了几件**只能在消费者侧量。生产者在 pagedGrid 里那份 addedCell 读的是
                //   它此刻的 results 长度，管道臂下消费者还没走到本格 ⇒ 恒 0 —— 那是与 #188
                //   「拿 key 栏下判决」同一坑的另一栏，事后看日志会把成功格读成未入库。
                job.resultAdded = engineCellCount() - jobBefore
                synchronized(pipeDoneLock) { pipeDoneResults.add(job) }
                pipeReturnJob(job)
            }
        }
    }

    /** 消费者单 job：解析（裁片+origin）→ match 闸求值 → emit（判据在 emitCharacter）。 */
    private suspend fun pipeProcess(job: ParseJob) {
        val t0 = System.nanoTime()
        val gw = ocr ?: run {
            Log.w(TAG, "pipe job #${job.id}: OcrGateway 为空（同步路径前置校验已兜底）")
            return
        }
        try {
            // ★ #69（§14.5 根因）：本格投票位随 job 进来 —— pipe>0 时生产者的 vote 写的是
            //   暂存位（不是共享 vars），所以这里必须自己装载，否则读到的就是**上一格**的值。
            //   华为实测：vars.crafted 串格 ⇒ 祝圣 yShift(47px@2244) 被错加/漏加在别的格上，
            //   等级读成 0、首条副词条被顶掉、末行读到套装名 ⇒ 内容键变 ⇒ 去重当成新件。
            job.votes.applyTo(vars)
            // ★ #263：本格坐标随 job 进来（同 #69 的投票位）。引擎上的 curPageNo/curCellRow/
            //   curCellCol/curCellIdx 此刻是**生产者正在走的那一格**，消费者读它 = 按 B 格的
            //   内容判 A 格的坐标 ⇒ 武器跨页/同格丢件判据错位。
            val cell = job.cellCoord()
            if (job.panelKey == "weapon_backpack") {
                parseWeaponPanel(job.crop, gw, job.parseStep.optJSONObject("dict"), job.origin, cell)
            } else {
                parseArtifactPanel(job.crop, gw, job.panelKey, job.parseStep.optJSONObject("dict"), job.origin, cell)
            }
            vars.panelMatched = evaluatePanelMatch(job.parseStep)
            if (job.parseStep.optString("match").isNotEmpty() && vars.panelMatched) vars.matchHit = true
            job.emitStep?.let { emitByStep(it) }
            // ★ S2b：stopWhen 是**逐格步**（visit 步序列第 10~11 位），判据读本格 vars
            //   （rarity 连击/素材标识/total 核对）⇒ 必须与 parse/emit 同序在消费者求值。
            //   生产者侧只在下一格前**非阻塞**查 vars.stopRequested（止扫语义仍是"本页后停"，
            //   见 pagedGrid 页末检查）⇒ 不破坏流水线的并发。
            job.stopStep?.let { stopWhen(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // §6.3：单 job 抛异常按"解析失败"记（不入库+交回读），消费者协程不得死
            // ★ 计数是**必需的**，不只打日志：干跑 `isReturnDefaultValues` 把 Log 全吞，
            //   没有 `pipeFail` 这条，"消费抛异常"与"读到空"在单测里原理上不可分
            //   （#69 排查时多花了整整一轮才分清，见 design-docs §13.5）。
            PerfProbe.addPipeFail()
            Log.w(TAG, "pipe job #${job.id} 消费失败: ${e.message}")
        } finally {
            PerfProbe.addConsOcr(System.nanoTime() - t0)
        }
    }

    private fun pipeReturnJob(job: ParseJob) {
        if (pipeCancelled || pipeJobPool.size >= TimingOverrides.pipeDepth.coerceAtLeast(1)) {
            job.crop.release()
        } else {
            pipeJobPool.addLast(job)
        }
    }

    /** 页末 join：等在途 job 全部消费完成（背压保证 send 已返回 ⇒ 只等消费完成）。 */
    internal suspend fun pipeJoinPage() {
        if (pipeChannel == null) return
        while (true) {
            val n = synchronized(pipeLock) { pipeInFlight }
            if (n == 0) break
            delay(PIPE_POLL_MS)
        }
        // 只等齐，不清空 —— 清空由 [pipeDrainResults] 在排水后做（否则调用方读到空）
    }

    /**
     * 页末排水（本页 job 结果，按 job id 序 = 生产者发送序 = 格序）。
     * 返回本格（idx 是它在 pageKeys/pageIdentities 的槽位）的**内容键 / 身份 / 追加件数**。
     * ⚠️ 必须先 [pipeJoinPage]；`pipe=0`（无消费者）时返回空。
     */
    internal fun pipeDrainResults(): List<PipeCellResult> =
        synchronized(pipeDoneLock) {
            val page = synchronized(pipeLock) { pipePageNo }
            val out = pipeDoneResults.sortedBy { it.id }
                .filter { it.page == page }
                .map { PipeCellResult(it.idx, it.resultKey, it.resultIdentity ?: "", it.resultAdded) }
            // 本页结果已全部交付 ⇒ 清空（pipeDrainAppeared 必须在本次调用之前或同批读取，见调用点顺序）
            pipeDoneResults.removeAll { it.page == page }
            out
        }

    /**
     * ★ #253：锚点早到交付（有界停等）。
     *
     * pipe>0 时生产者侧 `pageIdentities` 写的是从共享 vars 读到的竞态残留 ⇒ 跨页身份锚定
     * 按构造恒不命中（真机实测多做 6.5% 功）。锚点只要本页 idx1/idx2 的身份，而此刻生产者
     * 至多发出过本页前 3 格的 job ⇒ join 到在途清零的代价被积压深度封顶，且**每页只发生一次**
     * （不是逐格）。**不摘取结果** —— pageKeys/curPageIds 的交付仍归页末 [pipeDrainResults]。
     * 无进展到上限 / 未交付 ⇒ 对应格按「身份为空」处理，调用侧退回照常访问，绝不无限等。
     *
     * ★ 交付完整性论证（2026-10-13 复核 #260）：走到"在途已归零"那一步时，每个已发送 job 的结果
     * **必然已在** [pipeDoneResults] 里 —— 消费者把 `pipeInFlight--` 与回填结果放在同一段
     * [pipeLock] 内，而本函数读在途数也在同一段锁里 ⇒ 不存在"已归零但尚未回填"的中间态。
     * 提前 return（无进展到上限）按空身份处理，语义与旧超时分支逐字相同。
     */
    internal suspend fun pipeAwaitAnchorIdentities(
        page: Int,
        idxs: IntArray,
        idlePolls: Long = PIPE_ANCHOR_IDLE_POLLS,
    ): Map<Int, String> {
        if (pipeChannel == null) return emptyMap()
        var idle = 0L
        var prevDone = synchronized(pipeLock) { pipeDoneCount }
        while (true) {
            val (inFlight, done) = synchronized(pipeLock) { pipeInFlight to pipeDoneCount }
            if (inFlight == 0) break
            // 消费者处理完过任何一个 job（含丢弃）⇒ 它活着，预算重新给满。
            // 正常路径就是靠这条退出：在途深度 ≤ pipeDepth+锚点那 3 格，不会一直"无进展"。
            if (done != prevDone) {
                prevDone = done
                idle = 0
            } else if (++idle >= idlePolls) {
                Log.w(
                    TAG,
                    "pipe 锚点早到交付：连续 ${idlePolls} 次轮询（每次 >=${PIPE_POLL_MS}ms）消费者没有处理完任何 job" +
                        "（page=${page}，在途 ${inFlight}）⇒ 未交付格按空身份处理，锚定退回「照常访问」" +
                        "（消费者被 Error 打死或取消未排空；注意这条**不是**超时，机器越忙只会等得越久）",
                )
                return emptyMap()
            }
            delay(PIPE_POLL_MS)
        }
        // 在途已归零 ⇒ 本页不会有未交付的格，直接取即可，不需要额外的"等齐 idx 集"分支。
        return synchronized(pipeDoneLock) {
            pipeDoneResults.filter { it.page == page && it.idx in idxs }
                .associate { it.idx to (it.resultIdentity ?: "") }
        }
    }

    /** 取消（§6.1）：close 后消费者把缓冲 job 只释放裁片，不 emit/不记账。 */
    internal fun pipeCancel() {
        val ch = pipeChannel ?: return
        synchronized(pipeLock) { pipeCancelled = true }
        ch.close()
        pipeChannel = null
        pipeConsumerJob = null
        synchronized(pipeLock) {
            pipeJobPool.forEach { it.crop.release() }
            pipeJobPool.clear()
        }
    }

    /** 扫描开始复位（取消上一轮残留的消费者/池）。 */
    internal fun pipeReset() {
        pipeCancel()
        synchronized(pipeLock) {
            pipeCancelled = false
            pipeNextId = 0
            pipeInFlight = 0
            pipeDoneCount = 0
            pipePageNo = 0
            pipeSentEpoch.clear()
        }
    }

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

        /**
         * ★ A③（#69 收尾，2026-10-13）：管道深度的**流程级默认**（"①流程属性"形态）。
         *
         * 依据是这一轮的设备实测，不是推测：
         * - 华为 EML-AL00 正反序、圣遗物四臂：`pipe=2` 比 `pipe=0` **−10.8%**（反序对，两臂做功
         *   逐位相同且同步臂单价更低）；BS 2244 同流程 **−5.7%**；两序同向。
         * - 等价性：BS/华为各四臂导出**全字节相同**（#253/#259/#261 三个串格缺陷都修在管道侧，
         *   同步臂与管道臂互验后才敢定默认）。
         *
         * 只有"读背包"的两条进来。**写类流程一律不列**（按构造排除，不靠注释提醒）：
         * `artifact_lock`/`auto_equip` 的判据要在**下一格的开头**读回本格投票位（`curLock`
         * 决定要不要真点锁钮，见 LockFlow.kt:204 与 ScanJudgmentDomain 的 vote），
         * 而管道下这些位滞后 ≤pipeDepth 格 ⇒ 开了就是拿旧状态点写操作。
         * 角色扫描 (`character_scan`) 也不列：它的本格要**多次取帧 + 中途分支**
         * （dedupeSkip 消费解析结果决定跳不跳命座/天赋页），一张裁片的 job 表达不了（#262）。
         *
         * 显式 `--es timing "pipe=N"` 永远覆盖本表（[TimingOverrides.pipeExplicit]）。
         */
        val PIPE_DEFAULT_BY_FLOW: Map<String, Int> = mapOf(
            "artifact_scan" to 2,
            // ★ 2026-10-15 #307：套装筛选流程与全量同一条读背包链（visit 步序一字不差，
            //   只多一个 setFilter 前置），华为四臂 -10.8% 的依据同样适用；此前漏登记 ⇒
            //   筛选轮一直跑 pipe=0（2026-10-15 实测 timing 行 pipe=0 而全量是 2）。
            "artifact_scan_set" to 2,
            "weapon_scan" to 2,
        )
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

        /**
         * ★ #266：「点任意处进入」之后等进主界面的**轮询预算**与步长。
         *
         * 华为实测（08:38）：标题屏 → 世界的加载远大于 `RETURN_HOME_SETTLE_MS(900)`，
         * 只等一轮就被"盲点 3 次"那条闸截住 ⇒ 点打了、整轮仍然 `returnHomeStuck`。
         * 预算取 45s：2026-10-13 华为实测**冷进世界用了 27.7s**（那会儿整机被钳频，
         * 同轮 OCR 均时 969ms/槽）—— 30s 只剩 2s 余量，太紧；这里多留 1.5 倍。
         * 到点仍未进游戏就停手（这一试是最后一步，不会回到盲点循环）。
         */
        const val TITLE_ENTER_WAIT_MS = 45_000L
        const val TITLE_ENTER_POLL_MS = 1500L

        /**
         * ★ 2026-10-15：`returnToHome` 判出 **WAIT 类**（版号屏/加载屏，`near_white ≥ 0.90`）时
         * 的等待预算与步长。
         *
         * 为什么必须"等"而不是点：[ScreenStateJudge] 的 KDoc 记着——这类屏是游戏**自己还在渲染**，
         * 点它只会打断加载。改动前它们落进"非主界面"，归位链会先烧 3 次盲点返回 + 1 次 BACK
         * 才轮到 `#266` 的点屏幕（华为实测 41.2s）。
         *
         * 预算取 90s：覆盖冷启动从版号屏到世界的最长实测（`startupcapture` 三档冷启动观测
         * 从版号屏到标题屏约 12~15s，标题屏到世界另有 27.7s 的钳频实测）⇒ 90s 有充足余量，
         * 且 < `ScriptRunner.SESSION_STALL_MS(120s)`（不会触发会话看门狗抢跑）。
         * 每拍**重判三态**而不是睡满：加载屏 → 标题屏的转换必须被抓住（否则白等到超时）。
         */
        const val WAIT_SCREEN_BUDGET_MS = 90_000L
        const val WAIT_SCREEN_POLL_MS = 2000L

        /**
         * ★ 2026-10-15 S4b：断线框点「确认」后**等游戏自动 reload** 的预算与步长。
         *
         * reload 是"游戏自己重连 + 回标题屏"，实测（`device-log` 10-03/10-06 两节）点完
         * "自动 reload 到标题屏"、再点「点击进入」即回世界。等待上限取 30s：
         * 网络正常时 reload 在秒级完成；30s 还没消失说明不是普通断线（可能真掉登录），
         * 那就该停手报出来而不是继续等。每拍重判"框还在不在"，一消失立刻交回。
         */
        const val DISCONNECT_RELOAD_WAIT_MS = 30_000L
        const val DISCONNECT_POLL_MS = 1500L
        // §15 P0-1：左侧菜单切页后的稳定等待（长于普通点击，动画约 600~800ms）
        /**
         * 页签切换后的等待。**2026-09-12 由 900 → 700**（档位实测：屏幕侧内容就绪 397~529ms；
         * 700 留 32% 余量，全量 90 角色实测 −23%，质量与 900 等价）。
         * ⚠️ 再往下（600/450/300）实测读取错误率翻倍并开始丢件 ⇒ 不要调。
         */
        const val NAVIGATE_PAGE_SETTLE_MS = 700L
        /**
         * 签名不可用时那条**固定等待**（角色流水每格都落到这里：`visitClick` 是 visit 首步
         * ⇒ ctx.frame 空 ⇒ sigUsable 恒假）。武器不走这一档（实测武器 `panel=312ms/格`，是签名闸）。
         *
         * ★ 2026-10-14 #233 单2：**试过 400，实测撤回 550**。BS 2244 五臂账（证据
         *   `/tmp/ab_233/prefresh/` 与 `/tmp/ab_233/p400_default/`）：
         *     550 两臂：emit 93、93 ⇒ `t=[0, 0, 0]` 零条 ⇒ `c0` 20、20
         *     400 三臂：emit 93、93、92 ⇒ `t=[0, 0, 0]` 0、1、3 条 ⇒ `c0` 20、21、23
         *   直接机制在同格对比：cell(0,5) 在 400 下 `headerOnly 预读` 读到的是**上一格**的面板
         *   （'水元素/沃雅妮' ⇒ Vodyanitsa）⇒ `dedupeSkip：已入库 ⇒ 跳过命之座/天赋`
         *   ⇒ 该角色以 `c0 t=[0, 0, 0]` 入库，**不报错、件数也还是对的**。
         *   收益只有 **−4.6%**（`panel=` 差额 23700ms = 150ms × 158 格；elapsed 差的另外 8.7s 在记账之外）。
         * ⇒ 550 不是"没测过的保守值"，是**这条静默归零故障的下界**。要再降必须先解决
         *   "预读用签名/稳定判据而不是靠时长"，见 #233 单2 的"跳过的格别等面板"那条（那才是安全的 6.6%）。
         * 对照杠杆：`--es timing "prefresh=<ms>"`（`-1` = 用本常数）。
         * ⚠️ 闸门教训：本单的等价判据原先只看「件数 + 对 GT 命中数」，**400 那臂 93 件全在**
         *   却已经把 3 个角色的命座/天赋写成 0 ⇒ 角色轮的必查项要加
         *   `char emit #` 行里 `t=[0, 0, 0]` 的条数与 `c0` 计数。
         */
        const val PANEL_REFRESH_MS = 550L
        /** 面板名「未变但已稳定」的兜底等待：相邻同名时不至于打满 6s 上限（实测省 ~54s/100s）。 */
        const val PANEL_STABLE_FALLBACK_MS = 400L
        // ★ P1（2026-10-06）：6000→400。对齐 GOODScanner Fingerprint{timeout 200, initial 20}
        //   （game_controller.rs:666）；BS 实测就绪门+settle 占每格 68%（508ms 里 345ms）。
        //   语义不变：超时仍是「按原样解析」，只是不再先白等 6 秒。
        const val PANEL_CHANGE_WAIT_MAX_MS = 400L

        // ── 管道停等的打点与上限（#260 后半，2026-10-13）──
        /** 在途 job 归零的轮询间隔（[pipeJoinPage] 与 [pipeAwaitAnchorIdentities] 共用）。 */
        const val PIPE_POLL_MS = 2L
        /**
         * [pipeAwaitAnchorIdentities] 的放弃条件：**连续这么多次轮询消费者没有处理完任何一个 job**。
         *
         * ★ 为什么不再按墙钟（旧值＝[PAGE_WATCHDOG_MS] 90s 的 `clock() + budget`）：
         * 那条预算量的是"这台机器多久能跑完 ≤[TimingOverrides.pipeDepth] 个 job"，也就是**机器负载**，
         * 而不是"消费者是否还活着"。后果有两头：
         * - 干跑：满载复跑（load 12）实测被打满 ⇒ 锚定退回「照常访问」⇒ 管道臂比同步臂**多击一整条
         *   重叠带**（43→50），单测红的是机器而不是语义（当时的处置是给 `panchor` 一个大值把它失效，
         *   见 §16.20 之前的记录 —— 那是掩码不是修）。
         * - 真机：慢机（华为钳频那两轮 rec 单价 338ms）同样可能在 90s 内跑不完 2~3 个 job ⇒
         *   **静默降级成"每页多击数格"**，只有一条 W 日志。
         *
         * 按"没有进展的轮询次数"计就没有这个毛病：`kotlinx.coroutines.delay` 的契约是
         * **"for at least the given time"**（只会晚于、不会早于请求时长），
         * 所以 N 次轮询的真实时长随负载**只增不减** —— 机器越忙，这条越宽松，原理上不可能因为忙
         * 而误判消费者已死。真正确死/Error 杀掉协程时，进展恒 0 ⇒ 仍然会在有限次轮询后收手。
         * 名义时长 45_000 × 2ms ≈ 90s，与旧预算同量级（"90s 里一个 job 都没处理完"＝死了）；
         * 消费者**活着**时本函数照旧在"在途归零"那一刻返回，不会因为换了判据而多等一步。
         * 真机上"消费者真的不干活"由页级看门狗（[PAGE_WATCHDOG_MS]，按格间进展判挂死）中止整轮兜住。
         */
        const val PIPE_ANCHOR_IDLE_POLLS = 45_000L

        // ── ★ 就绪信号直采（2026-09-12，取代上面的「抓帧 + OCR」轮询）──
        /**
         * 签名轮询步长。**实测面板内容 133ms（中位）就绪**（PERF-timing.md，样本 67/133/298），
         * 旧路径却花 428ms/格（= delay(120×3) + 每轮抓帧 + 每轮 OCR）。
         * ★ 2026-10-14：40→20。全量 artifact 扫描 A/B（BS，57 页 / 1026 格）：sigpoll=20 臂与
         * 默认臂 **cells 逐位相同、判滑空=0、吞击重发落在 5~7 的同轮噪声带内**，
         * panel 263593→242657ms ⇒ −4.6%。可由 `--es timing "sigpoll=…"` 覆盖。
         */
        const val PANEL_SIG_POLL_MS = 20L
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
         * 装备者行文本 → 角色名。**双语义**（2026-10-09）：
         *
         * ① `artifact_manage`（装配/卸下复核）读全行「XX已装备」→ 截断取标记前的名字，
         *    兼容尾部截断（2026-09-25 武器全量实测：97 格里 15 个末字「备」被吃掉；
         *    角色名不以「已/装/备」结尾，逐级截断安全）。
         * ② `artifact_backpack`/`weapon_backpack`（懒识别后）equipped ROI 已收窄到**角色名
         *    3 字区**（头像/「已装备」不入画面）→ 文本**就是名字**，直接清洗返回。
         *    （顺带修掉旧 bug：旧 backpack ROI 从头像中间起切，det 把头像当主文本，
         *    125/125 历史实读全只露出名字首字。）
         *
         * 空文本 ⇒ null（= 未装备，导出空串）。
         */
        internal fun equippedOwnerOf(text: String?): String? {
            val t = text?.trim().orEmpty()
            if (t.isEmpty()) return null
            if (!t.contains("已") && !t.contains("装") && !t.contains("备")) {
                return StatParser.clean(t).takeIf { it.isNotEmpty() }
            }
            val cut = when {
                t.contains("已装备") -> t.substringBefore("已装备")
                t.endsWith("已装") -> t.removeSuffix("已装")
                t.endsWith("已") -> t.removeSuffix("已")
                // 名字里合法含「装/备/已」字（如「装备」词不在名字里但字符可能混入 OCR 噪声）
                // → 走到这说明文本不含完整标记又不以截断尾缀结束：按 manage 全行带噪处理，
                //   交给上层词典匹配判弃。
                else -> t
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
         * 武器**跨页重叠**判据（纯函数，#292）：给定本页相对上页的**实测前进量** [advanceRows]，
         * 判断本格是不是上页那张已经读过的卡。
         *
         * 表必须是**绝对下标** `r*cols+c` 的全行表（`prevAllCellIds`）——#60 就是喂了一张
         * 只存 row1/row2 的紧凑表却仍按绝对下标读，于是"row1"这一路静默失效（见调用处注释）。
         *
         * ## 判据（#292：把"固定比末两行"换成"按前进量门控"）
         * 前进 `a` 行 ⇒ 本页 `(row,col)` 那格在上页的同一位是 `(row+a, col)`：
         * ```
         * 丢弃 ⟺ row + a < traverseRows  且  prev[(row+a)*cols + col] == identity
         * ```
         * 于是三种情形都由同一条式子给出：`a=0`（整页没动）退化成"比上页同位置" `prev[row*cols+col]`；
         * `a=1` ⇒ 本页 row0 ← 上页 row1、row1 ← 上页 row2；`a=2` ⇒ 本页 row0 ← 上页 row2；
         * `a≥traverseRows`（前进 ≥ 一整页 ⇒ 零重叠）**一律不丢** —— 这正是 #215 的根因：
         * 旧实现不管前进多少，都拿上页 `row1/row2` **固定**比，于是**前进一整页**的页里，本页
         * 任一格与上页 row1/row2 同列的"同款同列"巧合被当成重叠 ⇒ **静默丢真件**（同款多把）。
         *
         * ⚠️ 前进量**未知**（首页无上页表 / 锚点未命中 ⇒ `advanceRows < 0`）⇒ 一律不判（fail-open：
         * 宁可多留一件，也不误丢）。这是有意的：判据一旦错判就是**静默丢件**，代价高于多一件待办。
         *
         * `row`/`col` 为 -1 时（snap / rosterFind 等非 pagedGrid 路径）本格没有网格位置可谈，一律不判；
         * 空身份也不判（不能与表里的空格"相等"，否则读失败的格会被当成重叠）。
         */
        internal fun crossPageOverlap(
            prev: List<String>?,
            cols: Int,
            traverseRows: Int,
            row: Int,
            col: Int,
            identity: String?,
            advanceRows: Int,
        ): Boolean {
            if (prev == null || row < 0 || col < 0) return false
            // 空身份 = 这格根本没读到东西，不能拿去和表里的空格"相等"（那会把读失败的格当成重叠）。
            if (identity.isNullOrEmpty()) return false
            if (traverseRows < 2 || cols < 1) return false
            // 前进量未知/为负 ⇒ fail-open（见 KDoc）。
            if (advanceRows < 0) return false
            val pr = row + advanceRows
            // pr >= traverseRows 覆盖 advanceRows >= traverseRows（row>=0）：前进 ≥ 一整页 = 零重叠。
            if (pr >= traverseRows) return false
            return prev.getOrNull(pr * cols + col) == identity
        }

        /**
         * ★ 2026-10-15 #305：页末**跨页身份重放撤回**的判定（纯函数，供窄测试）。
         *
         * 武器的重叠区重读统一出口：同张卡在相邻两页各被读一次、各入库一次。既有三道判据
         * 都盖不住（像素指纹 0 命中 / `weaponCellEmitted` 键含页行列挡不住位移 / 锚定时刻的
         * 追认撤不到 idx0——那时它的身份还是生产者竞态残值）。本函数在**页末**跑：此刻
         * `curPageIds` 已被 pipe 排水回填成权威身份，按本页实测前进量 [advanceRows] 重放
         * 逐格比对，返回应撤的格下标（= 身份逐位等于上页对齐位的格）。
         *
         * 门控与 [crossPageOverlap] 同族：`advanceRows <= 0`（无跨页位移）或
         * `>= traverseRows`（整页前进零重叠）⇒ 空集；空身份不判（读失败的格不能与空格"相等"）。
         * 天然同款不受影响：判的是**逐位全等身份**（含词条数值），真列表里同款件相隔几十件，
         * 不可能落进同一前进量的对齐位。
         *
         * @param emitted 本页**emit 过**的格下标集（复制格/未 emit 格不在内 ⇒ 天然幂等；
         *                锚定时刻追认已撤的格其 emit 记录已 remove，同样不在内）。
         */
        internal fun replayWithdrawIdxs(
            curIds: List<String>,
            prev: List<String>,
            cols: Int,
            traverseRows: Int,
            advanceRows: Int,
            emitted: Set<Int>,
        ): List<Int> {
            if (advanceRows <= 0 || advanceRows >= traverseRows) return emptyList()
            if (cols < 1 || traverseRows < 2) return emptyList()
            val out = ArrayList<Int>(2)
            for (idx in emitted) {
                if (idx < 0 || idx >= curIds.size) continue
                val id = curIds[idx]
                if (id.isEmpty()) continue
                val pr = idx / cols + advanceRows
                val pc = idx % cols
                if (pr >= traverseRows) continue
                if (prev.getOrNull(pr * cols + pc) == id) out.add(idx)
            }
            return out.sorted()
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
         * ★ 2026-10-09 同步 ROI 瘦身：slot/mainValue 槽已删（slot 词典反查、主词条不读值），
         *   武器 baseLabel/baseValue/mainLabel 亦不读；列表只保留现存键。
         */
        val PANEL_BAND_TYPICAL_KEYS = arrayOf(
            "name", "level", "mainName",
            "refine", "subStats", "lock", "stars", "starColor", "banner", "equipped",
        )
        // 自适应 settle（替代固定 PAGE_SETTLE）：轮询指纹至连续稳定，自动标定各设备翻页动画时长
        const val SETTLE_POLL_MS = 120L
        // ★ 2026-09-13：300→700。实测（7 轮 A/B，每轮前列表回顶、timing 生效已验证）：
        //   300ms 时被吞率 53~67%、落地中位 13~19px；700ms 时被吞率 33~40%、中位 128~139px。
        //   机制：滑动后存在 ~0.4s 低速惯性尾（帧间变化恰在 0.10 阈值附近）⇒ 300ms 会在尾巴里判"稳定"
        //   ⇒ 下一次滑动打在滚动动画中 ⇒ 被整段吞掉。700ms 让 settle 跨过尾巴。
        // ★ 2026-10-14：700→350。**上述 09-13 的坑已被 09-14 的 requireChange 守卫堵死**
        //   （命令滑动后必须先观察到一次变化才允许判稳定，见 SETTLE_CHANGE_WINDOW_MS），
        //   故 350ms 不会再误判惯性尾为稳定帧。全量 artifact 扫描 A/B（BS，57 页 / 1026 格）：
        //   sstable=350 臂与默认臂 **cells 逐位相同（1026）、判滑空=0、吞击重发=5 落在默认臂 5~7 的噪声带内**，
        //   settle 87960→67320ms ⇒ 整轮 −4.8%。可由 `--es timing "sstable=…"` 覆盖。
        const val SETTLE_STABLE_MS = 350L
        const val SETTLE_MAX_MS = 6000L

        /**
         * ★ 2026-09-14（[awaitGridStable] 的 `requireChange` 专用）：命令滑动后，在此窗口内必须
         * **先观察到一次画面变化**才允许判「已稳定」。堵「手势异步派发 ⇒ 起步前的静止帧被当稳定帧」这个
         * 静默误判（实测手势时长 780ms，故留 1500ms 余量；超窗未变 = 滑动未送达/列表到底）。
         */
        const val SETTLE_CHANGE_WINDOW_MS = 1500L

        /**
         * ★ 2026-10-14（#293）：命令滑动后 [awaitGridStable] 的**最早判稳时刻**（ms，从 settle 起点算）。
         *
         * 为什么需要它（实测，BS 2244，`20261014_192411_2244_artifact_lock_dbg.log`）：
         * 注入的三段式滑动**标称 800ms**（400 快滑 90% + 300 缓爬 10% + 100 回退），实测整链闭合
         * `+804ms`；而 `actions.swipe` 是**异步派发**（不等手势走完），段1 走完 90% 位移后
         * 段2/3 只剩 ~10% 慢爬 —— 24×16 缩略图**看不见**（`diff=0.000`）⇒ settle 在 `629ms`
         * 就判稳返回，返回帧是**手势中途的位移帧**。顶页 row0（套装列表第一格 = 目标套装所在）
         * 的字被移出 [textLineCrop] 的 ±slack 搜索窗 ⇒ `行位没量到 ⇒ 不读不点`（+32.272 / +32.524），
         * 直至甩尾停稳（+32.781）才读得到 ⇒ `selected=0` ⇒ `not_applied` ⇒ 退化全量扫。
         * 同一根因还让多轮回顶时「下一轮派发打断上一链」（`swipe3 段2 断…手指未抬起`）。
         *
         * 取值 **900** = 整链 804ms + ~96ms 余量（覆盖派发起点对齐误差与 ~1fps 的采样抖动）。
         * 只**延后判稳起点**，不改 moved/到顶口径、不改释放路径；[SETTLE_MAX_MS]=6000 仍是硬上限。
         */
        const val SWIPE_CHAIN_MS = 900L

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
         * 页级看门狗（2026-09-16）：真机偶发「主滑派发后无任何后续日志」的硬挂
         * （本轮实测卡死 >5min，整轮扫描报废）。置 stopRequested 让 pagedGrid 正常收尾
         * （**已入库结果照常导出**，不白跑）。
         *
         * ⚠️ 2026-10-04：判据已从「本页累计耗时」改成「**距上一格走完的间隔**」
         * （见 `ScanGridDomain` 的 `lastProgressAt`）。原来的写法把"机器慢"判成"挂死"：
         * 注释里的"正常单页 ~6s"是 BlueStacks 的数，麒麟 970 上圣遗物 perCell=11.0s、
         * 一页 12 格 = 132s ⇒ 每页必越 90s 线，实测 945 件跑到第 11 件就整轮 `watchdog` 中止。
         * 按间隔量之后这个值与机器速度无关：BS 单格 ~0.5s（180× 余量）、华为 11.0s（8× 余量）。
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

        /**
         * 圣遗物面板**闭锁图标**的最短"点击龄"（从点击注入算起）。
         *
         * 2026-10-21 a6 全量取证：点击后 ~200–270ms 抓到的帧里，名字/词条已是新件、**锁徽仍是
         * 上一件的红闭锁**（p040 实证：面板文字=新件、卡片无徽、GT=未锁，抓到红锁）⇒ 锁票误判。
         * 同设备手测 ~450ms 时已转灰。故在收敛帧抓取前补等到本值，再重抓一帧（只作用于
         * artifact_backpack；武器面板有独立的 `wpsettle`）。`locksettle=<ms>` 可覆盖，0=关。
         */
        const val PANEL_LOCK_SETTLE_MS = 450L

        /** 武器面板**固定延迟**（GOODScanner `DEFAULT_WEAPON_PANEL_DELAY = 50ms`）：同款武器面板全同 ⇒ 指纹无效。 */
        // ★ 2026-10-14 #233：这里原来有 `WEAPON_PANEL_DELAY_MS = 300L`，**已删**。
        //   它作用在 `frame` 已经抓妥之后（武器不走换帧分支）⇒ 只是在盲等一张定格的帧；
        //   原先记在 KDoc 里那条「50ms 实测不够 ⇒ 57 条 refine 读空 ⇒ 漏 100 件」的因果**三条都站不住**：
        //   ① 形状上不可能（延迟在抓帧之后）② refine 空号是系统性读不出数字「1」
        //      （同文件账：171 次里「精炼1阶」命中 0 次、空号 43 次，GT 的 R1 正好 39 件），不是时序问题
        //   ③ 那次事故查无落盘件，只随 10-02 的 squash 提交 b1e2d5a 进了注释。
        //   GOODScanner 的 50ms 也不是裸等：后面紧跟 ensure_panel_stable(rect,100)；
        //   而我方的稳定判定本来就在 visitClick 的签名就绪闸里做过了。
        //   对照杠杆：`--es timing "wpdelay=<ms>"`（默认 0）。实测数见 ArtifactDomainPanels 的注释。

        /**
         * 天赋读数为 0 时（时序性读失败 / 切页淡入）**最多重读次数**与间隔（ms）。
         * 单位要说清：这是**重读**次数，样本数 = 首读 1 + 本值 ⇒ 2 就是「最多 3 个样本」。
         *
         * ★ 2026-10-14 #233 单2③：**试过 2（=最多 3 个样本），实测撤回 6**。
         *   离线那半边是成立的：拿 8 臂 742 格的逐格样本序列回放，截到 3 样本与现行
         *   **逐格全等（0 救 0 坏）**，每轮省 ≈5.7s（正文 `dsl/docs/talent-sample-count-20261014.md`）；
         *   BS 实测也对得上（`runlogs/bs_pipe_20261014_055758` c1：直方图再无 #3..#6、
         *   `仍有平票`=0、`仍有未读出行`=0、`天赋等级` 92 = emit 92、对 GT 归一后真差 0）。
         *   **但真机把它推翻了**：华为同一件产物、同一天两轮 ——
         *   cap-6 那轮（`runlogs/ui_scan_20261014_043735…`）摘要里**没有** `天赋未读`；
         *   cap-3 那轮（`…_064526…`）摘要 **`天赋未读=1件`**，导出里 Fischl
         *   `c0 t=[0, 0, 0]` 而 GT 是 `c6 t=(8,10,9)`。
         *   ⇒ 离线回放只能证明"**已有样本序列下**结论不变"，证明不了"**多等几帧会不会等到**"：
         *   慢机上那 4~6 帧里有一部分不是空转，是在等交叉淡入（`#166` 记的 ~2.5s 就是这台机量的）。
         *   收益 5.7s/轮（≈0.5~1.3%）对价是"每轮可能有一个角色三项天赋静默归零"⇒ **不划算，撤回**。
         * ⚠️ **不要往下调到 1（=2 样本）**：13 个分歧槽的正确性全部由第 3 帧的多数票兑现
         *   （现行 13/13 对；反事实"平票取更晚"只有 5/10 ≈ 抛硬币、"取更大即停"8/10 会漏掉
         *   Alyosha/Charlotte 两笔偏大错读）。第二样本本身不产生正确性，它产生的是
         *   **"触发第三样本"的资格** —— 这正是它 700ms 的价钱买的东西。
         *   离线口径同时给出：只取首读会错 ≈4.9 格/轮（现行 1 格/8 轮）。
         */
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
         * ★ #316（2026-10-19）：祝圣横幅「双帧裁决」的复投等待（ms）。
         *
         * 首投紫横幅命中后，等这一拍再抓帧复投：真祝圣件横幅恒在（复投仍命中）；
         * 上一格祝圣件的**残留横幅**在新面板渲染的这一拍内褪去（复投消失 ⇒ 判非祝圣）。
         * 取 [PANEL_FP_POLL_MS] 同拍长——面板指纹闸门的实测值，即「新面板渲染完成」的
         * 典型节奏；r4 全量实测该路径仅 2.4% 格子触发 ⇒ 总开销 ≈3s/轮（0.6%）。
         */
        const val ZHUSHENG_CONFIRM_POLL_MS = PANEL_FP_POLL_MS

        /**
         * ★ #317（2026-10-19）：后台模式指纹闸门「等满未变 ⇒ 重击一次」的重发次数上限。
         *
         * 只作用于 [ScanEngine.backgroundFrameCompensation]（后台 VD）。语义与既有的
         * [CLICK_RETRY_MAX_ON_NOCHANGE]（BS 吞击窗口修法）同族：同坐标重发**幂等**，最坏
         * 重读同一张卡、被 contentKey 去重吸收。取 1：r5 实测「等满未变」全轮仅 6 次
         * （1017 格的 0.6%），重发一轮 ≈ +400ms/格；真冻结格（列表尾素材格）重发后仍不变，
         * 照旧按原样解析 —— 保底不劣化。
         */
        const val BG_GATE_RECLICK_MAX = 1


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

        /**
         * 尾区**强化素材**的停止标识（#216，2026-10-04，参照 GOODScanner）。
         *
         * 为什么必须按**名字**而不是按稀有度：这两类素材的星带读数会落进"真装备"区间。
         *   · 圣遗物：实测「祝圣精华」面板 `rarity=4` ⇒ `rarity < STOP_MARKER_RARITY` 那条挡不住它，
         *     于是它一路走到 setKey 反查 → 词典未命中 → 「丢弃读失败条目（词条 0 条）」→
         *     **再被「空读格回读」重取一遍**（白点一格 + 白跑一次 OCR）。
         *   · 武器：「精锻用魔矿」实测星带 `rarity=3` ⇒ 同理挡不干净。
         *
         * 名单来源与**刻意不照抄**的部分（deepwiki 实查 `Anyrainel/GOODScanner`）：
         *   GOODScanner 有两张武器名单：
         *     `WEAPON_FORGING_STOP_NAMES` = 精锻用魔矿/良矿/杂矿 —— **照抄**（素材不是武器，GT 也不收）；
         *     `WEAPON_STOP_NAMES` = 无锋剑/银剑/猎弓/学徒笔记/… 共 10 件 1★/2★ 武器 —— **不照抄**：
         *       它把"碰到第一件低星武器"当列表尽头，但我们这边**抓包真值不这么看**：
         *       `dsl/verify/sweeps/gt_20261003_bs.json` 里 `ApprenticesNotes|1|1` 是货真价实的一件
         *       （rarity 分布 {1:1, 3:77, 4:112, 5:16}）。跟着它在这里止扫就会**永久少这一件**。
         *       ⇒ 低星武器照常入库（词典 A' 段已补齐），只有真的走到素材才停。
         *
         * 位置证据（决定"命中即停"是安全的）：素材排在**列表最末**——
         *   圣遗物 `20260925_2244_artifact_64fix.log`：page=51（该轮共 52 页）global=947 是最后一件
         *   Instructor 沙子，紧接着 global=948 就是「祝圣精华」；
         *   武器 `20261003_bs_run_bettergi.log`：page=10 尾行 黎明神剑 → 学徒笔记 → 魔矿/良矿/杂矿。
         */
        val WEAPON_FORGING_STOP_NAMES = listOf("精锻用魔矿", "精锻用良矿", "精锻用杂矿")
        val ARTIFACT_STOP_NAMES = listOf("祝圣油膏", "祝圣精华")

        /**
         * 命中素材停止标识时写进 `vars.stopReason` 的值。
         * 收尾原因不是自由文案：total 核对要靠它区分"提前收工"与"读到尽头"（见 run() 里的
         * `sawWholeList`），所以钉成常量。
         */
        const val STOP_REASON_MATERIAL = "stopName"

        /**
         * 名字命中停止标识 ⇒ 置止扫并返回 true（调用方**直接 return，不入库**）。
         *
         * 用 `contains` 而不是 `==`：与 GOODScanner 同口径，且 OCR 读素材名常带尾巴
         * （实测原文第 2 槽是"圣遗物强化素"这种被切断的描述行）。
         */
        internal fun hitsStopName(name: String?, list: List<String>): String? =
            list.firstOrNull { cand -> name != null && name.contains(cand) }

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
            // ★ #255 圣遗物背包的「1≡」排序钮：点开「排序方式」弹窗，再点 `sortPopupConfirm` ⇒ 游戏重排并把列表拉回顶部。
            //   2026-10-12 BS 2244 现场验：弹窗里**没有选中项**时点确认**不改排序规则**，只把视图回到顶
            //   （回顶后首格=生之花「熔铸者的揣度」，与确认前的顺序一致；确认钮实测胶囊 (126,966)-(773,1043)）。
            //   ⚠️ 只标了 2244/2560 两档：3200 没有 artifact_backpack 截图可量 ⇒ 该档缺键 ⇒ 链项无字面坐标
            //   ⇒ `clickChainEntry` 打 W 后跳过（不猜坐标），代价是那一档等于没回顶。上 3200 前必须补量。
            "artifactSort" to "screens.artifact_backpack.sortBtn",
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
