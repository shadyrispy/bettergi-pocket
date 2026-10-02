package com.bettergi.pocket.pcdata

import android.content.Context
import android.util.Log
import com.bettergi.pocket.notice.NoticeCenter
import com.bettergi.pocket.scan.GoodRepository
import com.esc.irminsul.capture.CaptureResult
import com.esc.irminsul.capture.CaptureSource
import com.esc.irminsul.capture.DataStatus
import com.esc.irminsul.capture.DataStatusSink
import com.esc.irminsul.capture.IrminsulCapture
import com.esc.irminsul.capture.SessionPhase
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 两条抓包通路共用：3★ 起导（与 OCR 数据源同口径，`GoodPlan` 已放宽到 3★~5★）。 */
internal const val GOOD_EXPORT_SETTINGS = """{"min_artifact_rarity":3}"""

/**
 * **在线**抓包会话（集成方案 C2）：VPN 隧道 → 解密 → 四段数据齐 → 自动导库存 → 收隧道。
 *
 * 与 [CaptureReplay] 共用一条解码路，区别只在包源。这里的状态全部**派生**自库的
 * flow（`sessionPhase` / `traffic` / `completion`），本对象不自存会话事实，
 * 所以不会出现"界面说在跑、隧道其实早掉了"那种两套真相。
 *
 * 三件库侧教给我的事，写在这里以免被改回去：
 * 1. **密钥只在新登录经过隧道时才有**（`SessionPhase.AwaitingLogin` 且 traffic 在动
 *    = 盲会话）。库自带 `forceRelogin` 需要 `KILL_BACKGROUND_PROCESSES`，本 app 不申请，
 *    所以这里只**报**不给玩家动游戏。
 * 2. 采集完成即**收隧道**：隧道只吸原神包（库按包名白名单），但没理由在用完之后
 *    继续占着设备的"同时只能一个 VPN"名额。
 * 3. 只在主进程跑（库的 `CaptureError.WrongProcess` 会直接拒）。
 */
object CaptureSession {

    private const val TAG = "BetterGI.Capture"

    /** 隧道里有流量却解不出密钥多久，就提示玩家重新进游戏。 */
    private const val BLIND_HINT_MS = 12_000L
    private const val POLL_MS = 500L

    /** 授权给完到 `isCapturing` 置真之间有空窗（实测 ~65ms，慢机更久），别把"还没起来"当成"掉了"。 */
    private const val TUNNEL_UP_GRACE_MS = 20_000L

    /** 悬浮窗副文本要看的一切。 */
    data class Ui(
        val running: Boolean = false,
        val phase: SessionPhase = SessionPhase.Idle,
        val progress: String = "",
        val hint: String = "",
    ) {
        /** 面板一行（≈40 字符口径，详情走 logcat）。 */
        fun brief(): String = when {
            !running && hint.isNotEmpty() -> hint
            !running -> "未抓包"
            hint.isNotEmpty() -> "$hint · $progress"
            else -> phaseZh() + (if (progress.isEmpty()) "" else " · $progress")
        }

        fun phaseZh(): String = when (phase) {
            SessionPhase.Idle -> "未抓包"
            SessionPhase.AwaitingLogin -> "等登录"
            SessionPhase.Collecting -> "采集中"
            SessionPhase.Complete -> "已齐"
        }
    }

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val latest = MutableStateFlow(DataStatus())
    private val exported = AtomicBoolean(false)
    private var watcher: Job? = null
    private var blindSince = 0L

    /** 一次会话的标识：提醒的 onceKey 挂它，保证"同一句提示每会话最多报一次"。 */
    private var sessionToken = 0L

    /** [CaptureGate] 的凭据：只有拿着它的那次会话能让位（0 = 没占着闸门）。 */
    @Volatile
    private var gateTicket = 0L

    private val sink = object : DataStatusSink {
        override fun publish(status: DataStatus) {
            latest.value = status
        }
    }

    fun isRunning(): Boolean = IrminsulCapture.isCapturing.value

    /** @return 没能开始的用户可读原因；null = 隧道已在起。 */
    fun start(context: Context): String? {
        val ticket = CaptureGate.tryEnter()
        if (ticket == 0L) return say("已有抓包在跑（在线会话或 pcap 回放），本次不启动")
        gateTicket = ticket
        return try {
            startLocked(context)
        } catch (e: Exception) {
            releaseGate()
            say("抓包启动异常：${e.javaClass.simpleName} ${e.message}")
        }
    }

    /** [start] 的实体；所有失败出口都要经 [refuse] 让出闸门。 */
    private fun startLocked(context: Context): String? {
        val app = context.applicationContext
        when (val support = IrminsulCapture.probeNativeSupport()) {
            is CaptureResult.Ok -> Unit
            is CaptureResult.Err -> return refuse("本机跑不了抓包栈：${support.error}")
        }
        if (IrminsulCapture.vpnConsentIntent(app) != null) {
            return refuse("VPN 授权还没给：从悬浮窗或页面点「抓包采集」重新发起")
        }
        // 每轮干净 sniffer：原生收集跨会话累加，不清就把上一轮的库存混进这一轮导出。
        runCatching { stopTunnel(app) }
        runCatching { IrminsulCapture.close() }
        when (val init = IrminsulCapture.initNative(app)) {
            is CaptureResult.Ok -> Unit
            is CaptureResult.Err -> return refuse("原生栈初始化失败：${init.error}")
        }
        val blocked = IrminsulCapture.refreshPermissions(app)
        latest.value = DataStatus()
        exported.set(false)
        blindSince = 0L
        sessionToken = System.currentTimeMillis()
        when (val started = IrminsulCapture.start(app, CaptureSource.Vpn, sink,
            IrminsulCapture.Config(completionNotification = false))) {
            is CaptureResult.Ok -> Unit
            is CaptureResult.Err -> return refuse("隧道没起来：${started.error}")
        }
        _ui.value = Ui(running = true, phase = SessionPhase.AwaitingLogin)
        say("抓包已启动：隧道只吸原神流量，进游戏（或回标题屏重进）后即可采集")
        NoticeCenter.post(NoticeCenter.Level.INFO, "抓包已启动：进游戏或回标题屏重进即可采集")
        if (!blocked.vpnPermissionGranted) {
            Log.w(TAG, "vpn reported not granted right after start: $blocked")
        }
        watch(app)
        return null
    }

    /** 起不来的统一出口：让出闸门 + 记一句原因。 */
    private fun refuse(text: String): String? {
        releaseGate()
        return say(text)
    }

    /** 只还**自己**那份凭据；已被别人接手时是空操作（见 [CaptureGate] 的 P2-1 说明）。 */
    private fun releaseGate() {
        val t = gateTicket
        gateTicket = 0L
        CaptureGate.exit(t)
    }

    /**
     * 玩家手动停止：收隧道 + 让出闸门，**不写输入仓库**。
     *
     * 中途停止的库存是半截的（可能只解到圣遗物、没解到武器），而
     * `GoodRepository` 是单文件覆盖写 ⇒ 一存就把上一份完整输入顶掉，
     * 而 `artifact_lock` / `auto_equip` 会拿它当计划依据。只有四段齐（completion）
     * 那一次才落盘；这里只报"本次解到多少、没入库"。
     */
    fun stop(context: Context): String {
        val app = context.applicationContext
        val wasRunning = isRunning()
        stopTunnel(app)
        watcher?.cancel()
        watcher = null
        releaseGate()
        val seen = if (wasRunning) countsOf(latest.value) else "无"
        val text = "抓包停止：本次解到 $seen，未入库（上一份输入保持不变）"
        _ui.value = Ui(hint = "已停止")
        NoticeCenter.post(NoticeCenter.Level.WARN, "中途停止：本次数据未入库")
        return say(text)
    }

    private fun stopTunnel(app: Context) {
        runCatching { IrminsulCapture.stop(app) }
            .onFailure { Log.w(TAG, "stop failed", it) }
    }

    /**
     * 轮询而不是串四个 flow：状态是**派生值**，500ms 一次的开销远低于把
     * phase/traffic/completion/dropped 拼成一个组合流的复杂度，而且少一处能写错的时序。
     */
    private fun watch(app: Context) {
        watcher?.cancel()
        // 会话纪元：[startLocked] 每轮换一个 `sessionToken`。收尾时靠它判断"字段还指着我吗"——
        // 直接 `watcher?.cancel()` 会在「停止 → 立刻重开」时掐掉**新会话**的 watcher。
        val myToken = sessionToken
        watcher = scope.launch {
            var everUp = false
            val startedAt = System.currentTimeMillis()
            while (isActive) {
                val capturing = IrminsulCapture.isCapturing.value
                if (capturing) {
                    everUp = true
                } else if (everUp || System.currentTimeMillis() - startedAt > TUNNEL_UP_GRACE_MS) {
                    // 真掉了（或空窗到点还没起来）：这才够资格说"隧道已断开"。
                    _ui.value = Ui(hint = if (everUp) "隧道已断开" else "隧道没起来")
                    releaseGate()
                    if (myToken == sessionToken) watcher = null
                    Log.w(TAG, "capture tunnel ${if (everUp) "dropped" else "never came up"}")
                    return@launch
                }
                val phase = IrminsulCapture.sessionPhase.value
                val traffic = IrminsulCapture.traffic.value
                val dropped = IrminsulCapture.droppedPackets.value
                val hint = when {
                    phase == SessionPhase.AwaitingLogin && traffic.totalBytes > 0 -> {
                        if (blindSince == 0L) blindSince = System.currentTimeMillis()
                        if (System.currentTimeMillis() - blindSince > BLIND_HINT_MS)
                            "解不出密钥：隧道里没有这次登录 ⇒ 回标题屏重新进游戏"
                        else ""
                    }
                    dropped > 0 -> "解码跟不上，已丢 $dropped 包"
                    else -> ""
                }
                _ui.value = Ui(running = true, phase = phase, progress = progressText(), hint = hint)
                // 提醒只报"变化"：onceKey 按会话去重，500ms 轮询不会把提醒条刷成瀑布
                if (hint.isNotEmpty()) {
                    NoticeCenter.post(NoticeCenter.Level.WARN, hint, "capture-hint-$sessionToken")
                }
                if (IrminsulCapture.completion.value != null && exported.compareAndSet(false, true)) {
                    val stored = exportAndStore(app, "四段数据齐")
                    stopTunnel(app)
                    if (myToken == sessionToken) watcher = null
                    releaseGate()
                    _ui.value = Ui(hint = "采集完成：$stored")
                    NoticeCenter.info("抓包完成，隧道已收回：$stored")
                    return@launch
                }
                delay(POLL_MS)
            }
        }
    }

    private fun exportAndStore(app: Context, why: String): String =
        when (val export = IrminsulCapture.exportGood(GOOD_EXPORT_SETTINGS)) {
            is CaptureResult.Err -> say("导出失败（$why）：${export.error}")
            is CaptureResult.Ok -> {
                val (ok, message) = GoodRepository.save(app, "抓包·在线会话", export.value)
                if (!ok) say("存不进输入仓库（$why）：$message")
                else {
                    val line = "$message ${countsOf(export.value)}"
                    say("入库（$why）：$line")
                    line
                }
            }
        }

    private fun progressText(): String {
        val s = latest.value
        val origin = IrminsulCapture.keyOrigin.value
        val parts = buildList {
            if (s.charactersCount > 0) add("角色${s.charactersCount}")
            if (s.artifactsCount > 0) add("圣遗物${s.artifactsCount}")
            if (s.weaponsCount > 0) add("武器${s.weaponsCount}")
            if (s.achievementsCount > 0) add("成就${s.achievementsCount}")
        }
        val shown = if (parts.isEmpty()) "还没解到数据" else parts.joinToString(" ")
        return if (origin == null) shown else "$shown · 密钥=${origin.name}"
    }

    /** 停止时报告"这一轮解到多少"——用 sink 推进来的进度，不碰输入仓库。 */
    private fun countsOf(s: DataStatus): String =
        "角色${s.charactersCount} 圣遗物${s.artifactsCount} 武器${s.weaponsCount} " +
            "成就${s.achievementsCount} 材料${s.materialsCount}"

    private fun countsOf(json: String): String {
        val root = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return ""
        fun n(key: String) = root.optJSONArray(key)?.length() ?: 0
        return "角色=${n("characters")} 圣遗物=${n("artifacts")} 武器=${n("weapons")} " +
            "材料=${root.optJSONObject("materials")?.length() ?: 0}"
    }

    private fun say(text: String): String {
        Log.i(TAG, text)
        return text
    }
}
