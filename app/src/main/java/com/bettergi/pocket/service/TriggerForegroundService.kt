package com.bettergi.pocket.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import com.bettergi.pocket.scan.NameOverrides
import com.bettergi.pocket.PendingHandoff
import com.bettergi.pocket.ui.MainActivity
import com.bettergi.pocket.R
import com.bettergi.pocket.capture.CapturePermissionActivity
import com.bettergi.pocket.capture.CaptureContract
import com.bettergi.pocket.pcdata.CaptureConsentActivity
import com.bettergi.pocket.pcdata.CaptureSession
import com.bettergi.pocket.capture.CaptureResultHolder
import com.bettergi.pocket.capture.ProjectionFrameSource
import com.bettergi.pocket.capture.ScreenCaptureController
import com.bettergi.pocket.feature.autopick.AutoPickFeature
import com.bettergi.pocket.feature.autoskip.AutoSkipFeature
import com.bettergi.pocket.genshin.GenshinLaunchMonitor
import com.bettergi.pocket.notice.NoticeCenter
import com.bettergi.pocket.scan.GoodRepository
import com.bettergi.pocket.genshin.GenshinLauncher
import com.bettergi.pocket.input.AccessibilityAutomationController
import com.bettergi.pocket.input.InputAccessibilityService
import com.bettergi.pocket.input.SwipeMethod
import com.bettergi.pocket.bridge.OverlayBridge
import com.bettergi.pocket.recognition.RecognitionAssets
import com.bettergi.pocket.recognition.ocr.OcrFactory
import com.bettergi.pocket.scan.ScanListener
import com.bettergi.pocket.scan.ScriptRunner
import com.bettergi.pocket.settings.TriggerSettings
import com.bettergi.pocket.settings.TriggerSettingsRepository
import com.bettergi.pocket.trigger.TriggerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.bettergi.pocket.scan.ClickModeOverrides

class TriggerForegroundService : Service() {
    private lateinit var settingsRepository: TriggerSettingsRepository
    private lateinit var captureController: ScreenCaptureController
    /**
     * 悬浮窗门面（2026-09-18 宿主迁移）。
     * 悬浮窗本体已搬到无障碍进程（`TYPE_ACCESSIBILITY_OVERLAY` 零权限），本服务只持有转发门面；
     * 方法签名与原控制器一致 ⇒ 扫描/自动对话侧零改动。
     */
    private lateinit var overlayController: OverlayBridge
    private lateinit var genshinLauncher: GenshinLauncher
    private lateinit var genshinLaunchMonitor: GenshinLaunchMonitor
    private lateinit var engine: TriggerEngine
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var frameSource: ProjectionFrameSource
    private lateinit var scriptRunner: ScriptRunner

    @Volatile
    private var requestingCapturePermission = false

    /**
     * FGS 前台态是否已在本次实例内落过一次。任何经 `startForegroundService` 的启动
     * （adb DEBUG_* / 授权回调）都必须在 [onStartCommand] 同步补齐前台态，否则触发
     * [ForegroundServiceDidNotStartInTimeException]（见 onStartCommand 顶部注释）。
     */
    @Volatile
    private var foregroundSatisfied = false

    /** VPN 授权弹窗在路上的标记（去重 + 弹窗期间不自动启动游戏，与投影那条同语义）。 */
    private var requestingVpnConsent = false

    /**
     * 「无人应答」兜底回调（2026-09-23 审计 P2-2）。
     *
     * 两个授权标志原本只在结果 intent 到达时复位。用户把系统弹窗**划掉**（既不点允许也不点拒绝）
     * 时不会有任何回报 ⇒ 标志永久为 true ⇒ [canAutoLaunch] 恒假（"游戏启动后自动开扫描"静默失效）、
     * 抓包开关点了没反应。到点无条件把标志放下，通路重新可点。
     */
    private var vpnConsentTimeout: Runnable? = null
    private var captureConsentTimeout: Runnable? = null

    /**
     * #250：投影授权「弹没弹出来」探针的延迟回调（与 [captureConsentTimeout] 各管一件事：
     * 这条管"启动被 ROM 静默丢弃"，那条管"弹窗挂在那儿没人应答"）。
     */
    private var captureConsentProbe: Runnable? = null

    @Volatile
    private var shutDown = false

    /** 无障碍状态变化 ⇒ 重连后把悬浮窗请求回来（见 onCreate 注释）。★ #311：后台模式不开悬浮窗。 */
    private val a11yStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (shutDown) return
            mainHandler.post { showOverlayIfForeground() }
        }
    }

    /**
     * ★ #311：悬浮窗按 runMode 守卫——BACKGROUND 不 show（后台模式无悬浮窗，控制面 =
     * 脚本管理页）。所有 show 调用点统一走这里，模式切换（管理页 runmode_row）落盘后
     * 下一次重连自然跟随。
     */
    private fun showOverlayIfForeground() {
        if (settingsRepository.get().runMode == com.bettergi.pocket.settings.RunMode.BACKGROUND) {
            Log.i(TAG, "后台模式：跳过悬浮窗显示（控制面 = 脚本管理页）")
            return
        }
        overlayController.show()
    }

    private val settingsListener: (TriggerSettings) -> Unit = { settings ->
        // ★ #310（2026-10-17 BS 实测）：后台模式不需要投影（帧来自 VD 的 ImageReader）。
        //   起跑闸门按 runMode 分流：前台 = scanEnabled + 投影在跑（既有语义逐位不变）；
        //   后台 = 只要求 scanEnabled（投影开关在后台被 startScan 跳过，若也卡投影
        //   扫描永远起不来）。stop 路径两条共用，行为不变。
        val background = settings.runMode == com.bettergi.pocket.settings.RunMode.BACKGROUND
        if (settings.screenShareEnabled && !captureController.isRunning() && !background) {
            requestCapturePermission()
        }
        if (settings.screenShareEnabled) {
            engine.start()
        } else {
            stopScreenShare()
        }
        // 自动扫描：前台=投影就绪即开跑；后台=VD 会话健康即开跑；关闭即停（P1-c 悬浮窗入口）
        val scanGate = if (background) {
            settings.scanEnabled &&
                com.bettergi.pocket.vd.BackgroundSession.current?.isHealthy() == true
        } else {
            settings.scanEnabled && settings.screenShareEnabled && captureController.isRunning()
        }
        if (background && settings.scanEnabled) {
            // 诊断日志：后台起跑闸的三个条件逐项可见（BS 实测排查教训——静默不跑没法查）
            val s = com.bettergi.pocket.vd.BackgroundSession.current
            Log.i(
                TAG,
                "后台起跑闸：scanEnabled=${settings.scanEnabled} " +
                    "session=${if (s == null) "null" else "id=${s.displayId} healthy=${s.isHealthy()}"}",
            )
        }
        if (scanGate) {
            if (!scriptRunner.isRunning()) {
                startFlowIfReady(settings.scanFlow, maxPagesOrDefault(settings.scanMaxPages))
            }
        } else if (settings.scanEnabled && !settings.screenShareEnabled && scriptRunner.isRunning()) {
            // ★ 批2-A（D3，2026-10-03）：原来只有下面那一条 `!scanEnabled && running` ⇒
            //   用户**扫描途中关掉「共享屏幕」**时 scanEnabled 仍是 true，两条都不成立，
            //   于是扫描在没有画面的状态下继续跑（取帧侧还会退回最后一帧旧图，看着像还在推进）。
            //   关投影就是"别再动了"：这里只落开关 + 说明，**stop 交给下面那条 !scanEnabled 分支做**，
            //   避免同一轮被停两次。
            NoticeCenter.warn("已停止扫描：共享屏幕被关，没有画面可识别")
            overlayController.updateScanProgress("共享屏幕已关")
            settingsRepository.setScanEnabled(false)
        } else if (!settings.scanEnabled && scriptRunner.isRunning()) {
            scriptRunner.stop()
            overlayController.updateScanProgress("已手动停止")
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 进程内单例：设置桥 Provider 必须拿到同一实例，否则两个缓存互相打架
        settingsRepository = TriggerSettingsRepository.app(applicationContext)
        captureController = ScreenCaptureController(applicationContext) {
            if (settingsRepository.get().screenShareEnabled) {
                settingsRepository.setScreenShareEnabled(false)
                NoticeCenter.warn("屏幕共享已停止，可能被其他录制应用占用")
            }
        }
        // ★ #183：面板那颗「共享屏幕」药丸显示**现状**，权威值只有本进程这个活的 controller 知道。
        //   用探针而不是镜像：`isRunning()` 那三件（projection/virtualDisplay/imageReader）被系统单方面
        //   收走时会立刻变 false，抄一份布尔迟早与真值分叉（#183 当年就是为躲这个才搁着的）。
        settingsRepository.captureRunningProbe = { captureController.isRunning() }
        genshinLauncher = GenshinLauncher(applicationContext)
        // 「退出 / 开始导出」由无障碍进程经设置桥回调（METHOD_STOP / METHOD_SHARE_GOOD），
        // 因此这里不再需要 onExit / onShareGoodRequested 回调。
        overlayController = OverlayBridge(applicationContext)
        genshinLaunchMonitor = GenshinLaunchMonitor(
            settingsRepository = settingsRepository,
            launcher = genshinLauncher,
            isGenshinInForeground = { InputAccessibilityService.isGenshinInForeground() },
            canAutoLaunch = { !requestingCapturePermission && !requestingVpnConsent && !shutDown },
        )
        val recognitionAssets = RecognitionAssets(applicationContext.assets)
        frameSource = ProjectionFrameSource(captureController)
        // ★ 批0：自动对话/自动拾取与扫描**共用过同一本新鲜度账**（一个 thresholdFrameNs）。
        //   engine 常驻（投影起来就 start，只有 shutdown 才 stop），所以开着「自动对话」跑扫描时，
        //   自动跳过的那次点击会把扫描的阈值往后推 ⇒ 扫描 grabFresh 更容易超时、退回旧帧。
        //   各拿一本：TriggerEngine 用视图，扫描仍用 frameSource 本体。
        val triggerFrameSource = frameSource.newFreshnessScope()
        engine = TriggerEngine(
            settingsRepository = settingsRepository,
            frameSource = triggerFrameSource,
            features = listOf(
                AutoPickFeature(),
                AutoSkipFeature(recognitionAssets, overlayController),
            ),
            actionController = AccessibilityAutomationController(overlayController) {
                // 动作受理后标记帧源时间戳：**只推自己那本账**（批0），不再动扫描的阈值
                triggerFrameSource.markActionAt(SystemClock.elapsedRealtime())
            },
        )
        scriptRunner = ScriptRunner(
            context = applicationContext,
            frameSource = frameSource,
            captureController = captureController,
            overlayController = overlayController,
            listener = scanListener,
            // ★ #310/M6-audit：按 runMode 解析本轮装配（RunMode 的最终消费方）。
            //   前台 ⇒ null 语义（Foreground object）⇒ ScriptRunner 内置链，行为逐位不变；
            //   后台 ⇒ BackgroundSession 三件套；会话缺失/断线 ⇒ BackgroundNotReady 拒绝
            //   （绝不静默退化成前台——那会把游戏按到主屏上点，与用户意图相反）。
            assemblyResolver = {
                when (settingsRepository.get().runMode) {
                    com.bettergi.pocket.settings.RunMode.FOREGROUND ->
                        com.bettergi.pocket.scan.AssemblyResolution.Foreground
                    com.bettergi.pocket.settings.RunMode.BACKGROUND -> {
                        val session = com.bettergi.pocket.vd.BackgroundSession.current
                        when {
                            session == null -> com.bettergi.pocket.scan.AssemblyResolution.BackgroundNotReady(
                                "后台模式已选但虚拟屏未启动（先在页顶点「启动虚拟屏」）",
                            )
                            !session.isHealthy() ->
                                com.bettergi.pocket.scan.AssemblyResolution.BackgroundNotReady(
                                    "后台会话不健康（Shizuku 断线或帧源停止）⇒ 点「关闭虚拟屏」后重新启动",
                                )
                            else -> com.bettergi.pocket.scan.AssemblyResolution.Background(
                                frameSource = session.frameSource!!,
                                probe = session.probe,
                                actions = session.actions,
                                readiness = {
                                    // VD 就绪校验：帧源活 + 游戏在 VD 上（probe 三态折叠——
                                    // 判不出(null)按放行，与前台 fail-open 同口径）。不查
                                    // "游戏在物理屏前台"——后台模式它就是不该在前台。
                                    when (val fg = session.probe.isGameInForeground()) {
                                        false -> "虚拟屏上没看到原神（先点「启动虚拟屏」或用「游戏调回前台」）"
                                        else -> null
                                    }
                                },
                            )
                        }
                    }
                }
            },
        )
        settingsRepository.addListener(settingsListener)
        // ⚠️ 2026-09-18 上机实测：无障碍重连后**悬浮窗不会自己回来**（旧窗口随服务销毁，
        //    而没有新的 overlay_show 请求）⇒ 表现为「助手在跑但球没了」，只能重启助手。
        //    这里订阅无障碍状态变化，重连后补一次显示请求（幂等：已显示时 show() 直接返回）。
        ContextCompat.registerReceiver(
            applicationContext,
            a11yStateReceiver,
            IntentFilter(InputAccessibilityService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        genshinLaunchMonitor.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ★ FGS 时限兜底（2026-10-01 真机实锤 12:08 crash）：全新安装（无"记住授权"）时
        //   adb 直发 DEBUG_SET_SCREEN_SHARE=true ⇒ settingsListener 走 requestCapturePermission()
        //   弹授权页，原实现要等 ACTION_CAPTURE_RESULT 才 startInForeground —— 弹窗挂起期间
        //   超过系统 5s 时限 ⇒ ForegroundServiceDidNotStartInTimeException 杀进程。
        //   修法：任何启动命令先同步落一次前台态（dataSync 型），授权结果到达时既有分支
        //   再升级为 mediaProjection 型（startForeground 可重复调用，后到覆盖通知与类型）。
        if (!foregroundSatisfied) {
            startInForeground(sharing = captureController.isRunning())
        }
        when (intent?.action) {
            // ★ P3：START_STICKY 的 null intent 重启分支 —— 原先这里既不 startForeground 也不
            //   stopSelf，服务以"无通知的 started service"滞留（后台超时即被系统再回收，循环往复）。
            //   修法：重新走 startInForeground(sharing=false) 把前台态保住（防立刻再被回收），
            //   但**绝不**误启投影：sharing=false 只是通知文案/FGS 类型回 dataSync，
            //   投影的真正闸门在 settingsListener（screenShareEnabled 开关）——而投影授权无法
            //   跨进程重启存活，readFromPrefs 反序列化时本就强制 screenShare=false
            //   （TriggerSettingsRepository.readFromPrefs），重启后不可能自动进投影链。
            null -> {
                // 只补前台态；悬浮窗是否该显示随原会话状态不可考，不在此处主动 show
                //（ACTION_START 路径会 show，重启后用户从通知/悬浮窗入口正常恢复）。
                startInForeground(sharing = false)
            }
            ACTION_START -> {
                startInForeground(sharing = captureController.isRunning())
                showOverlayIfForeground()
                InputAccessibilityService.promptIfDisconnected(applicationContext)
            }
            ACTION_CAPTURE_START -> {
                // 悬浮窗开关点亮：授权与隧道一律在主进程发起（与投影授权同一条路子）。
                requestVpnConsent()
            }
            ACTION_VPN_RESULT -> {
                requestingVpnConsent = false
                clearVpnConsentTimeout()
                if (intent.getBooleanExtra(EXTRA_VPN_OK, false)) {
                    CaptureSession.start(applicationContext)?.let { NoticeCenter.error(it) }
                } else {
                    // 被拒：一句话提醒（不跳系统设置页，用户拍板"落回就够"）。
                    // 抓包开关现在只在管理器页，它直接订阅 CaptureSession.ui ⇒ 无需推给面板。
                    NoticeCenter.warn("没同意 VPN 授权，抓包没启动")
                }
            }
            ACTION_CAPTURE_STOP -> {
                // 悬浮窗（:a11y）的开关关下来：会话在本进程，只能由本进程停。
                Log.i(TAG, CaptureSession.stop(applicationContext))
            }
            ACTION_STOP -> {
                shutdown()
                stopSelf()
            }
            ACTION_SHARE_GOOD -> {
                // 悬浮窗「开始导出」（无障碍进程发起）
                shareGood()
            }
            ACTION_CAPTURE_RESULT -> {
                requestingCapturePermission = false
                clearCaptureConsentTimeout()
                if (!settingsRepository.get().screenShareEnabled) {
                    captureController.stop()
                    CaptureResultHolder.take() // 丢弃未消费的结果，避免下次误用
                    startInForeground(sharing = false)
                    return START_STICKY
                }
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                // 从进程内单例取授权结果 Intent（避免嵌套 parcel 丢失 IBinder extra）
                val resultData = CaptureResultHolder.take()
                if (resultData != null) {
                    startInForeground(sharing = true)
                    // ★ A27：controller.start 保证不抛（token 失效的 SecurityException、
                    //   异常 ROM 的 IllegalStateException 都在内部消化），失败拿摘要而不是裸崩主进程。
                    val startFailure = captureController.start(resultCode, resultData)
                    if (startFailure != null) {
                        Log.w(TAG, "capture start failed: $startFailure")
                        NoticeCenter.error("屏幕共享启动失败：$startFailure")
                        // 开关落回走既有通路：settingsListener else 分支 ⇒ stopScreenShare()
                        //（engine.stop + captureController.stop + 前台通知翻回非共享），
                        // 与下面 resultData == null 分支同款处理。
                        settingsRepository.setScreenShareEnabled(false)
                        return START_STICKY
                    }
                    Log.i(TAG, "capture started; running=${captureController.isRunning()}")
                    engine.start()
                    InputAccessibilityService.ensureEnabled(applicationContext)
                    // 投影刚就绪：若扫描开关已开，直接启动（悬浮窗先开扫描再授权的场景）
                    if (settingsRepository.get().scanEnabled) {
                        val s = settingsRepository.get()
                        startFlowIfReady(s.scanFlow, maxPagesOrDefault(s.scanMaxPages))
                    }
                } else {
                    settingsRepository.setScreenShareEnabled(false)
                }
            }
            ACTION_CAPTURE_DENIED -> {
                requestingCapturePermission = false
                settingsRepository.setScreenShareEnabled(false)
            }
            ACTION_SCAN_START -> {
                // 零授权扫描入口：通知栏直接开启（悬浮窗面板开关之外的通道）
                settingsRepository.setScanEnabled(true)
            }
            ACTION_SCAN_STOP -> {
                // 关设置：UI/悬浮窗入口靠 applySettings 的 `!scanEnabled && isRunning` 分支取消本轮
                settingsRepository.setScanEnabled(false)
                // ⚠️ 兜底直停：adb 调试路径（DEBUG_SCAN_FLOW）**不经 scanEnabled 启动**，
                //   此时 setScanEnabled(false) 是 **no-op**（值未变 ⇒ settingsListener 不触发）
                //   ⇒ 上面那句停不掉在跑的扫描，以前只能 `am force-stop`（连带丢掉 MediaProjection 授权）。
                if (scriptRunner.isRunning()) {
                    scriptRunner.stop()
                    Log.i(TAG, "scan stop requested (direct abort)")
                }
            }
            ACTION_DEBUG_SET_SCREEN_SHARE -> {
                // adb 调试入口：必须用本服务的 repo 实例（否则 settingsListener 不触发）
                settingsRepository.setScreenShareEnabled(
                    intent.getBooleanExtra(EXTRA_ENABLED, false),
                )
            }
            ACTION_DEBUG_SET_SCAN -> {
                settingsRepository.setScanEnabled(intent.getBooleanExtra(EXTRA_ENABLED, false))
            }
            ACTION_DEBUG_SET_CELL_CLICK -> {
                val mode = intent.getStringExtra(EXTRA_MODE) ?: "swipe"
                ClickModeOverrides.cellTap = mode.equals("tap", ignoreCase = true)
                Log.i(TAG, "debug set cell click: mode=$mode ⇒ ${ClickModeOverrides.summary()}")
            }
            ACTION_DEBUG_VD_TAP -> {
                // 诊断通路：与引擎**完全同一注入链**（BackgroundSession.actions.tap）点 VD。
                // 用途：分辨「注入参数问题」vs「窗口/时序问题」——点一下立刻见分晓。
                // ⚠️ 2026-10-18 根因研究后**刻意精简**：曾短暂存在 tap0/mainproc/async/inproc
                //    四个二分实验分支，用于定位"进程内注入不落地"。结论已定（真因是多指
                //    MotionEvent.obtain 重载坐标被清零，见 InjectPlanner KDoc），分支全部删除
                //    ——留着只会让后来人以为这些模式还有语义。要复现实验读 git 历史。
                val x = intent.getIntExtra(EXTRA_X, -1)
                val y = intent.getIntExtra(EXTRA_Y, -1)
                val session = com.bettergi.pocket.vd.BackgroundSession.current
                if (session == null || x < 0) {
                    Log.w(TAG, "debug vd tap: session=${session != null} x=$x y=$y ⇒ 无法执行")
                } else {
                    val ok = session.actions.tap(x, y)
                    Log.i(TAG, "debug vd tap: ($x,$y) on display ${session.displayId} ⇒ ok=$ok")
                }
            }
            ACTION_DEBUG_SET_MAX_PAGES -> {
                val pages = intent.getIntExtra(EXTRA_PAGES, 0)
                settingsRepository.setScanMaxPages(pages)
                Log.i(TAG, "debug set max pages: $pages")
            }
            ACTION_DEBUG_SET_NAME_OVERRIDE -> {
                val field = intent.getStringExtra(EXTRA_NAME_FIELD) ?: ""
                val value = intent.getStringExtra(EXTRA_NAME_VALUE)
                if (NameOverrides.FIELDS.none { it.first == field }) {
                    Log.w(
                        TAG,
                        "debug set name override: 未知字段 '$field' ⇒ 可选 " +
                            NameOverrides.FIELDS.joinToString { "${it.first}(${it.second})" },
                    )
                } else {
                    NameOverrides.save(
                        applicationContext,
                        NameOverrides.load(applicationContext).withField(field, value),
                    )
                    // 回读而不是回显入参：证明**落盘后**表里确实是这个值（含纯空白按"没填"处理那条规则）
                    Log.i(TAG, "debug set name override: $field='${value ?: "(清空)"}' ⇒ 现表 ${NameOverrides.load(applicationContext)}")
                }
            }
            ACTION_DEBUG_SET_GOOD -> {
                // 调试链：把设备上的 GOOD/计划文件复制成"当前输入"（用户侧入口在管理器，由 SAF 提供）
                val src = intent.getStringExtra(EXTRA_GOOD_SRC) ?: ""
                val (ok, msg) = GoodRepository.importFrom(applicationContext, java.io.File(src))
                Log.i(TAG, "debug set good: ok=$ok msg=$msg src=$src")
                NoticeCenter.post(
                    if (ok) NoticeCenter.Level.INFO else NoticeCenter.Level.ERROR,
                    "GOOD 输入：$msg",
                )
            }
            ACTION_DEBUG_DUMP_GOOD -> {
                val f = lastGoodFile
                if (f == null) {
                    Log.w(TAG, "dump good: 无 lastGoodFile（本轮未导出？）")
                } else {
                    runCatching {
                        val dstDir = getExternalFilesDir(null) ?: filesDir
                        val dst = java.io.File(dstDir, "sweep_last_good.json")
                        java.io.File(filesDir, f).copyTo(dst, overwrite = true)
                        Log.i(TAG, "dump good: $f -> ${dst.absolutePath} (${dst.length()}B)")
                    }.onFailure { Log.w(TAG, "dump good failed", it) }
                }
            }
            ACTION_DEBUG_SET_PANEL_SHOTS -> {
                // 开：建一个带时间戳的空目录挂到 ScanEngine，之后每格识别前落一张帧。
                // 关：摘掉引用并计数（目录与已落文件留在原地，供 adb pull 复盘）。
                val on = intent.getBooleanExtra(EXTRA_ENABLED, false)
                if (on) {
                    // ★ #46 ⑤：目录名原来只到 `HHmmss` ⇒ 同一秒重开会**复用同一目录**，且下面的
                    //   `manifest.jsonl` delete 会把上一轮的清单抹掉（图还在、索引没了）。
                    //   补上日期 + 毫秒，让每次开取证都是独立目录。
                    val dir = java.io.File(
                        getExternalFilesDir(null) ?: filesDir,
                        "panelshots_" + java.text.SimpleDateFormat("MMdd_HHmmss_SSS", java.util.Locale.US)
                            .format(java.util.Date()),
                    )
                    runCatching { dir.mkdirs() }
                    if (dir.isDirectory) {
                        com.bettergi.pocket.scan.ScanEngine.panelShotDir = dir
                        Log.i(TAG, "panel shots: ON -> ${dir.absolutePath}")
                    } else {
                        Log.w(TAG, "panel shots: 目录创建失败 ${dir.absolutePath}")
                    }
                } else {
                    val dir = com.bettergi.pocket.scan.ScanEngine.panelShotDir
                    com.bettergi.pocket.scan.ScanEngine.panelShotDir = null
                    Log.i(TAG, "panel shots: OFF（已落 ${dir?.listFiles()?.count { it.name.endsWith(".jpg") } ?: 0} 张）")
                }
            }
            ACTION_DEBUG_SET_REC_DUMPS -> {
                // 开：建一个带时间戳的空目录挂到 RecInferenceDump，之后每次真实 rec 推理逐行落
                // 输入像素图 + manifest（含空读/失败/回退重试）。
                // 关：摘掉引用。**历史运行目录一律不删不压**（原始扫描证据规约），时间戳名即运行身份。
                val on = intent.getBooleanExtra(EXTRA_ENABLED, false)
                if (on) {
                    val dir = java.io.File(
                        getExternalFilesDir(null) ?: filesDir,
                        "recdumps_" + java.text.SimpleDateFormat("MMdd_HHmmss_SSS", java.util.Locale.US)
                            .format(java.util.Date()),
                    )
                    runCatching { dir.mkdirs() }
                    if (dir.isDirectory) {
                        com.bettergi.pocket.recognition.ocr.onnx.RecInferenceDump.start(dir)
                        Log.i(TAG, "rec dumps: ON -> ${dir.absolutePath}")
                    } else {
                        Log.w(TAG, "rec dumps: 目录创建失败 ${dir.absolutePath}")
                    }
                } else {
                    com.bettergi.pocket.recognition.ocr.onnx.RecInferenceDump.stop()
                }
            }
            ACTION_DEBUG_DUMP_INPUT -> {
                // 输入库在 filesDir/scripts/good/current.json —— 模拟器上 `run-as` 被拒
                // （setegid(AID_PACKAGE_INFO) Operation not permitted），只能由本进程自己拷到外置目录再 pull。
                runCatching {
                    val src = GoodRepository.file(applicationContext)
                    if (!src.exists()) {
                        Log.w(TAG, "dump input: 还没有输入库（${src.absolutePath} 不存在）")
                    } else {
                        val dstDir = getExternalFilesDir(null) ?: filesDir
                        val dst = java.io.File(dstDir, "good_input_current.json")
                        src.copyTo(dst, overwrite = true)
                        Log.i(TAG, "dump input: ${src.absolutePath} -> ${dst.absolutePath} (${dst.length()}B)")
                    }
                }.onFailure { Log.w(TAG, "dump input failed", it) }
            }
            ACTION_DEBUG_STATUS -> {
                val s = settingsRepository.get()
                Log.i(
                    TAG,
                    "status screenShare=${s.screenShareEnabled} scan=${s.scanEnabled} " +
                        "autoSkip=${s.autoSkipEnabled} autoPick=${s.autoPickEnabled} " +
                        "captureRunning=${captureController.isRunning()}",
                )
            }
            ACTION_DEBUG_SET_PROBE -> {
                // 切换 :a11y Overlay 探针（桥模式：服务侧 toggle 调用即 :a11y 进程内执行）
                // ★ 2026-09-18 扩展：`--ez bal true` ⇒ 走 BAL 探针（挂**可点击**的 a11y overlay +
                //   立即/点击两条 startActivity 路径），用于核实「长按浮窗 → 启动 MainActivity」是否被拦。
                //   `--ez mount false` ⇒ 对照组（不挂 overlay，只为对比 BAL 判定差异）。
                val bal = intent.getBooleanExtra("bal", false)
                if (bal) {
                    val mount = intent.getBooleanExtra("mount", true)
                    Log.i(TAG, "debug: BAL probe 触发 mount=$mount")
                    InputAccessibilityService.probeBal(this, mount)
                } else {
                    InputAccessibilityService.toggleProbe(this)
                }
            }
            ACTION_DEBUG_SET_VERBOSE -> {
                // §13：识别日志 D 级开关（逐格/逐次明细，默认关闭以免一页 21 行刷屏）
                val on = intent.getBooleanExtra(EXTRA_ENABLED, false)
                com.bettergi.pocket.log.RecognitionLog.verbose = on
                Log.i(TAG, "recognition log verbose=$on")
            }
            ACTION_DEBUG_PERF_PROBE -> {
                // ★ 只读性能探针（2026-09-12）：帧路径拆段 + ROI 口径 + rec 宽度基准。
                //   目的：为「就绪信号廉价化 / ROI 级 BGR 转换」提供分量实测值
                //   （见 dsl/verify/_audit/IMAGE-PATH-COST.md §8）。**不改任何行为**。
                val runs = intent.getIntExtra(EXTRA_RUNS, 30)
                val roisB64 = intent.getStringExtra(EXTRA_ROIS_B64).orEmpty()
                val roisCsv = intent.getStringExtra(EXTRA_ROIS).orEmpty()
                val widthsCsv = intent.getStringExtra(EXTRA_WIDTHS).orEmpty()
                val stabilityB64 = intent.getStringExtra(EXTRA_STABILITY_B64).orEmpty()
                if (scriptRunner.isRunning()) {
                    Log.w(PERF_TAG, "perf probe: 扫描进行中，测量结果会被干扰（建议空闲时跑）")
                }
                val rois = if (roisB64.isNotEmpty()) parseRoiCsv(decodeB64(roisB64)) else parseRoiCsv(roisCsv)
                // ★ ROI 稳定性探针（只读）：`stabilityB64` = base64("name:x,y,w,h;name:…")
                val stability = if (stabilityB64.isNotEmpty()) parseNamedRois(decodeB64(stabilityB64)) else emptyList()
                val stabilityFrames = intent.getIntExtra(EXTRA_FRAMES, 60)
                // ★ OCR 并行度探针（只读）：`ocrpar="1:1,1:2,2:1,3:1"`（k:intra 列表）
                val ocrPar = intent.getStringExtra(EXTRA_OCR_PAR).orEmpty()
                // ★ 帧率探针（只读）：`--ei frate 1200`
                val frateMs = intent.getIntExtra(EXTRA_FRAME_RATE, 0)
                val widths = if (widthsCsv.isBlank()) {
                    intArrayOf(145, 220, 405, 684)
                } else {
                    widthsCsv.split(',').mapNotNull { it.trim().toIntOrNull() }.toIntArray()
                }
                val capturing = captureController.isRunning()
                scriptRunner.scope.launch {
                    if (!capturing) {
                        Log.i(PERF_TAG, "perf probe frame: 投影未运行（frame 段跳过）")
                    } else {
                        Log.i(PERF_TAG, "perf probe frame: ${captureController.benchFramePath(runs, rois)}")
                        // ROI 稳定性探针（只读）：为「自适应就绪锚」挑选真正静止的 ROI
                        if (stability.isNotEmpty()) {
                            Log.i(PERF_TAG, captureController.probeRoiStability(stability, stabilityFrames))
                        }
                    }
                    Log.i(PERF_TAG, "perf probe rec:\n${OcrFactory.recProbe(widths, runs.coerceAtLeast(5))}")
                    if (frateMs > 0) {
                        Log.i(PERF_TAG, "perf probe " + captureController.probeFrameRate(frateMs.toLong()))
                    }
                    if (ocrPar.isNotEmpty()) {
                        Log.i(PERF_TAG, "perf probe ocrPar:\n" + OcrFactory.ocrParallelProbe(ocrPar, widths, runs.coerceAtLeast(3)))
                    }
                    Log.i(PERF_TAG, "perf probe done")
                }
            }
            ACTION_DEBUG_SWIPE_TEST -> {
                val startY = intent.getIntExtra(EXTRA_START_Y, 1150)
                val dist = intent.getIntExtra(EXTRA_DIST, 876)
                val measure = intent.getBooleanExtra(EXTRA_MEASURE, false)
                // 可测任意网格（weapon_backpack/char_popup/...）；缺省 artifact_backpack
                val gridKey = intent.getStringExtra(EXTRA_GRID) ?: "artifact_backpack"
                // ★ 2026-09-13 补参数（默认值 = 原硬编码值，行为不变）：
                //   fromX：2244 上 1614 落在**详情面板**（面板 x≥1450）⇒ 测网格必须显式传（网格 x≈275..1435）
                //   durMs/segments：1 = **单段匀速**（一条直线段、指定时长，无 90/10 分段、无回退）
                val fromX = intent.getIntExtra(EXTRA_FROM_X, 1614)
                val durMs = intent.getLongExtra(EXTRA_DUR_MS, 400L)
                val segs = intent.getIntExtra(EXTRA_SEGS, 3)
                // method：0=路标链(默认,多次dispatch) 1=三段式(单次dispatch的continueStroke链)
                val mtd = if (intent.getIntExtra(EXTRA_METHOD, 0) == 1)
                    SwipeMethod.THREE_SEGMENT
                else SwipeMethod.WAYPOINT_CHAIN
                if (startY != null && dist != 0) {
                    val toY = startY - dist
                    // 派发滑动到主线程（InputAccessibilityService.swipe 通过桥即可：主进程调起即用 :a11y 实例）
                    // dist>0 上滑翻页；dist<0 下滑回顶（网格在顶端钳制，用于逐点标定前复位到首页）
                    mainHandler.post {
                        val ok = InputAccessibilityService.swipe(fromX, startY, fromX, toY, durationMs = durMs, segments = segs, method = mtd)
                        Log.i(TAG, "debug swipe ($fromX,$startY)->($fromX,$toY) dist=$dist ok=$ok")
                        // §12.2 标定：滑动结束 + 动画 settle 后抓帧测上沿相位误差，供距离自适应算法对标真值
                        if (measure && ok) {
                            scriptRunner.scope.launch {
                                delay(700)
                                val err = scriptRunner.measureTopEdge(gridKey)
                                Log.i(
                                    TAG,
                                    "align[$gridKey]: swipeMeasured dist=$dist startY=$startY err=${err ?: "null"}",
                                )
                            }
                        }
                    }
                } else if (startY != null && dist == 0 && measure) {
                    // 仅测量模式（dist=0）：不滑动，直接抓当前帧测上沿误差（逐点标定前取基准）
                    scriptRunner.scope.launch {
                        delay(200)
                        val err = scriptRunner.measureTopEdge(gridKey)
                        Log.i(TAG, "align[$gridKey]: measureOnly err=${err ?: "null"}")
                    }
                } else {
                    Log.w(TAG, "debug swipe: startY invalid or dist=0 without measure")
                }
            }
            ACTION_DEBUG_START_FLOW -> {
                val key = intent.getStringExtra(EXTRA_FLOW) ?: settingsRepository.get().scanFlow
                val req = intent.getIntExtra(EXTRA_MAX_PAGES, 0)
                if (scriptRunner.isRunning()) {
                    Log.w(TAG, "debug start flow: 已在运行 ⇒ 忽略（先发 SCAN_STOP）")
                    NoticeCenter.warn("已有流程在跑，先停再起")
                } else {
                    settingsRepository.setScanFlow(key)
                    // ⚠️ **只落设置，不显式起跑** —— 真正的起跑由 `settingsListener` 的**唯一入口**
                    //   （`scanEnabled && screenShare && captureRunning` ⇒ `startFlowIfReady`）负责，
                    //   与悬浮窗 ▶ / 通知栏开关完全同一条路。
                    //   ★ 2026-09-18 真机实测：原先"setScanEnabled(true) + 再显式 startFlowIfReady"会
                    //   **双重启动** —— `start flow`/`foreach iter` 各出现两次、两条流程并发抢屏，
                    //   然后 `foreach` 之后即哑火（两条互踩）。`isRunning()` 守卫挡不住这个竞态。
                    //   若 scanEnabled 已是 true，先复位一次，保证下面的 true 是一次**跳变**（否则 listener 不触发）。
                    if (settingsRepository.get().scanEnabled) {
                        settingsRepository.setScanEnabled(false)
                    }
                    settingsRepository.setScanEnabled(true)
                    Log.i(TAG, "debug start flow: key=$key（只落设置，起跑交给 settingsListener 唯一入口）")
                }
            }
            ACTION_DEBUG_SCAN_FLOW -> {
                // ★ 2026-10-14 套装筛选后扫描（adb 调试工具）：`--es sets HeartOfTheFurnace[,<key2>…]`
                //   直接指定套装词典 key ⇒ 自动选 `artifact_scan_set` 流程并合成 `[{"setKey":"…"}]` 计划。
                //   之所以传 **setKey**（而非 setName）：GOOD 导出项只有 setKey、没有 setName，且
                //   `TaskMatch.targets` 比对的就是词典 key（失配回落原文）⇒ 传词典 key 恒可归一。
                //   该 plan 只经 `filterTargets()` 决定 setFilter 的白名单目标集；artifact_scan 的
                //   visit body 不读 plan ⇒ 无写副作用（不像 lock/equip 会改游戏内状态）。
                val sets = intent.getStringExtra(EXTRA_SETS)
                    ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?: emptyList()
                val flow = intent.getStringExtra(EXTRA_FLOW)
                    ?: if (sets.isNotEmpty()) "artifact_scan_set" else "artifact_scan"
                // ★ 钳位与产线同口径（[maxPagesOrDefault]：0/负数 = 不限 → Int.MAX_VALUE）：
                //   原先直传 EXTRA_MAX_PAGES 给 startScan，绕过了产线（settings.scanMaxPages）
                //   必过的这道钳位 ⇒ 只有显式 `--ei maxPages 0` 会变成"扫 0 页"而不是"不限"。
                //   改走同一函数后，调试入口仍可用 `--ei maxPages 0` 表达"不限"。
                val maxPages = maxPagesOrDefault(intent.getIntExtra(EXTRA_MAX_PAGES, 0))
                // ⚠️ 2026-09-17 默认改为 **false**：几何起点（x=638，两卡缝隙）实测让 BS 的滚动**被截断**
                // （落地条带 L 仅 501~516px = **1.7 行**，而遍历 3 行 ⇒ 重叠 1.3 行 ⇒ 同件大量重复，
                //  武器扫描"多 126"）；改用 profile 坐标（x=1614）后落地 **777~812px = 2.7 行** ✓ 稳定。
                // ★ 2026-09-27：`DebugControlReceiver` 的转发默认已从 true 一并改成 false —— 原先调试
                //   跑的是产线不走的"几何起点"，而 `scan finished` 日志上两者同形，A/B 结论因此落到了
                //   没跑过的分支上。要量几何起点现在必须显式 `--ez geoAdvance true`。
                val geoAdvance = intent.getBooleanExtra(EXTRA_GEO_ADVANCE, false)
                // §16.4 标定/调试用 plan 注入：EXTRA_PLAN 直接 JSON（adb shell 会吞双引号→失效），
                // EXTRA_PLAN_B64 为 base64(JSON)（仅 [A-Za-z0-9+/=]，device sh 不吞，标定稳定通道）。
                val plan = (intent.getStringExtra(EXTRA_PLAN)
                    ?: intent.getStringExtra(EXTRA_PLAN_B64)?.let { b64 ->
                        try { String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT)) }
                        catch (e: Exception) { Log.w(TAG, "debug scan flow: bad planB64", e); null }
                    })?.let { raw ->
                    try {
                        JSONArray(raw).let { arr ->
                            (0 until arr.length()).map { arr.getJSONObject(it) }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "debug scan flow: invalid plan JSON, ignored", e)
                        null
                    }
                }
                // 时序覆盖：交给 startScan 单入口 apply（其余入口传 null ⇒ 复位，防跨轮残留）
                val timingSpec = intent.getStringExtra(EXTRA_TIMING)
                // sets ⇒ 合成 plan（只对 `artifact_scan_set` 生效）。显式把 sets 配给别的 flow 是
                // 误用：那些流程的 plan 项形状不同（如 lock 需要 mainStatKey/subStats 才能定位，
                // 这里只给得出 setKey）⇒ 恒匹配不到、静默空跑，故不注入并 W 出声。
                val setsPlan = when {
                    sets.isEmpty() -> null
                    flow == "artifact_scan_set" -> sets.map { key -> JSONObject().put("setKey", key) }
                    else -> {
                        Log.w(TAG, "debug scan flow: sets=$sets 配给了 flow=$flow（非 artifact_scan_set），已忽略")
                        null
                    }
                }
                // ★ 没给内联 plan 时**回落到用户在管理器里选好的输入**（与产线 `startFlowIfReady` 同源）。
                //   原先这条调试通道只能靠 `planB64` 喂，`DEBUG_SET_GOOD` 导入的文件它根本不读 ⇒
                //   起跑后 `foreach` 见 `$plan` 为空，整轮"跑完了但什么都没做"（07:46 实测：
                //   returnToHome 8 次 + `foreach: 'plan' not available or empty` + cells=0）。
                //   与 #94 的 geoAdvance 是同一条教训：**调试入口不能走产线不走的路径**。
                //   内联 `plan/planB64` 仍然优先 —— 标定跑要用的是"临时改一条计划"，不是换数据源。
                val planWithFallback = plan ?: setsPlan ?: GoodRepository.planFor(applicationContext, flow)?.also {
                    Log.i(TAG, "debug scan flow: plan 取自已导入输入（${it.size} 项，flow=$flow）")
                }
                Log.i(TAG, "debug scan flow: flow=$flow maxPages=$maxPages timing='${timingSpec ?: ""}' sets=$sets")
                // ★ #310（2026-10-18）：后台模式帧来自 VD（无投影）——起跑闸按 runMode 分流
                //   （与 settingsListener 的 scanGate 同口径）。原先只查 captureController，
                //   后台模式被 "projection not running" 静默拒绝。
                val isBackground = settingsRepository.get().runMode ==
                    com.bettergi.pocket.settings.RunMode.BACKGROUND
                val scanReady = if (isBackground) {
                    com.bettergi.pocket.vd.BackgroundSession.current?.isHealthy() == true
                } else {
                    captureController.isRunning()
                }
                if (scanReady) {
                    scriptRunner.startScan(
                        flow, maxPages,
                        useGeometryAdvance = geoAdvance,
                        plan = planWithFallback,
                        timingSpec = timingSpec,
                    )
                } else {
                    Log.w(
                        TAG,
                        if (isBackground) "scan flow request ignored: background session not healthy"
                        else "scan flow request ignored: projection not running",
                    )
                }
            }
            ACTION_DEBUG_OCR_BENCH -> {
                // 真机 OCR 基准：det/rec 中位耗时（EP 选型/瓶颈定位，IO 线程不阻塞主线程）
                scriptRunner.scope.launch(Dispatchers.IO) {
                    Log.i(TAG, "ocr bench: ${OcrFactory.bench()}")
                }
            }
            ACTION_DEBUG_OCR_EP -> {
                // 「这台机器的风险档 EP（NNAPI 的 USE_FP16 / XNNPACK）到底能不能启用」——
                // 一次性另建引擎实测，不碰生产档位。见 [OcrFactory.epProbe] 的动机说明。
                // 与扫描互斥：探测会再起一份 det+rec 会话，而 NNAPI 那条路的失败方式是**原生崩溃**
                // （抓不住，进程直接没了）⇒ 扫描在跑时不发。
                val risky = intent.getBooleanExtra(EXTRA_RISKY_EP, false)
                if (scriptRunner.isRunning()) {
                    Log.w(TAG, "DEBUG_OCR_EP 忽略：扫描在跑，不做 EP 探测")
                } else {
                    scriptRunner.scope.launch(Dispatchers.IO) {
                        Log.i(TAG, OcrFactory.epProbe(applicationContext, risky))
                    }
                }
            }
            ACTION_DEBUG_OCR_DET -> {
                // ONNX det 全管线真机探针（任意界面可跑，为移除 ML Kit 做验收）
                scriptRunner.scope.launch(Dispatchers.IO) {
                    val out = runCatching { scriptRunner.ocrDetProbe() }
                        .getOrDefault("probe failed: ${captureController.isRunning()}")
                    Log.i(TAG, "ocr det probe: $out")
                }
            }
            ACTION_DEBUG_REPLAY_PCAP -> {
                // 抓包数据源 C1：回放 pcap → 原生解码 → GOOD → GoodRepository 输入仓库。
                // 只走解码链，不建隧道 ⇒ 不需要 VPN 授权，游戏装没装都无关。
                val path = intent.getStringExtra(EXTRA_PCAP_PATH) ?: ""
                scriptRunner.scope.launch(Dispatchers.IO) {
                    runCatching { com.bettergi.pocket.pcdata.CaptureReplay.replay(applicationContext, path) }
                        .onFailure { Log.w(TAG, "capture replay crashed", it) }
                }
            }
            ACTION_DEBUG_CAPTURE -> {
                // 抓包数据源 C2 的 adb 通道：与悬浮窗同一条路（发起权在主进程）。
                if (intent.getBooleanExtra(EXTRA_ENABLED, false)) requestVpnConsent()
                else Log.i(TAG, CaptureSession.stop(applicationContext))
            }
            ACTION_DEBUG_CLICK -> {
                val x = intent.getIntExtra(EXTRA_CLICK_X, 0)
                val y = intent.getIntExtra(EXTRA_CLICK_Y, 0)
                val duration = intent.getIntExtra(EXTRA_CLICK_DURATION, 120).toLong()
                Log.i(TAG, "debug click at ($x,$y) duration=$duration")
                val ok = InputAccessibilityService.click(x, y, duration)
                Log.i(TAG, "debug click at ($x,$y) accepted=$ok")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // #183：主进程还要活着（Provider 会被 :a11y 的读唤起来）时，探针必须换成"一律 false" ——
        // 服务都没了就不该再回答"投影开着"，那是 #183 要治的同一个病。
        settingsRepository.captureRunningProbe = { false }
        settingsRepository.removeListener(settingsListener)
        shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** P1-c 扫描监听：进度更新悬浮窗副文本 + 前台通知（P2 后的零授权进度通道）+ 结束后开关自动复位。 */
    private val scanListener = object : ScanListener {
        override fun onProgress(stage: String, vars: Map<String, Any?>) {
            mainHandler.post {
                val text = when (stage) {
                    "artifact" -> "扫描中：第 ${vars["idx"]} 件 ${vars["piece"] ?: ""}"
                    "character" -> "扫描中：第 ${vars["idx"]} 位 ${vars["name"] ?: ""}"
                    "weapon" -> "扫描中：第 ${vars["idx"]} 把 ${vars["piece"] ?: ""}"
                    "exported" -> "完成：${vars["count"]} 件已导出"
                    "total_mismatch" -> "总数不符：读到 ${vars["total"]} 实扫 ${vars["scanned"]}"
                    else -> "扫描中…"
                }
                overlayController.updateScanProgress(text)
                // :a11y 探针挂载时同步进度（跨进程状态桥，P2 机制验证）
                InputAccessibilityService.pushScanProgress(text)
                updateForegroundNotification(text)
                (vars["file"] as? String)?.let {
                    lastGoodFile = it
                    GoodRepository.setLastExport(applicationContext, it)
                }
                Log.i("BetterGI.Scan", "progress[$stage]: $vars")
            }
        }

        override fun onNotice(level: String, text: String) {
            // 脚本推的重点信息 → 全应用唯一提醒通路
            NoticeCenter.post(
                when (level.lowercase()) {
                    "error" -> NoticeCenter.Level.ERROR
                    "warn" -> NoticeCenter.Level.WARN
                    else -> NoticeCenter.Level.INFO
                },
                text,
            )
        }

        override fun onFinished(reason: String) {
            mainHandler.post {
                val failed = reason.startsWith("error")
                val detail = reason.removePrefix("error:").removePrefix(" ").trim()
                val doneText = if (failed) "失败：$detail" else "已结束（$reason）"
                overlayController.updateScanProgress(doneText)
                updateForegroundNotification("扫描$doneText")
                // 进度文本落在**已收起**的面板上（自动化起跑前 collapse() 过）⇒ 凡是"本轮其实什么
                // 都没发生"的收尾都必须同时走 NoticeCenter，否则用户只看得到"点了没反应"。
                // returnHomeStuck 不在此列：#139 的刹车自己已经喊过一句，别重复。
                if (failed) {
                    NoticeCenter.error("扫描失败：$detail")
                } else when (reason) {
                    "already_running" -> NoticeCenter.warn("上一轮还在跑，本次起跑请求已忽略")
                    "not_in_genshin" -> NoticeCenter.warn("原神不在前台，已取消起跑：请先切回游戏主界面")
                    "no_projection" -> NoticeCenter.warn("屏幕共享没开，已取消起跑：请先在悬浮窗开启共享屏幕")
                    "no_frame_size" -> NoticeCenter.warn("拿不到投影帧尺寸，已取消起跑")
                }
                if (settingsRepository.get().scanEnabled) {
                    settingsRepository.setScanEnabled(false)
                }
            }
            Log.i("BetterGI.Scan", "scan finished: $reason")
        }
    }

    /** 最近一次 GOOD 导出文件名（悬浮窗「开始导出」用）。 */
    @Volatile
    private var lastGoodFile: String? = null

    /**
     * 悬浮窗「开始导出」：拉 app 前台（MainActivity 中转）再起系统分享 chooser——
     * service 后台直接 startActivity(chooser) 虽命中「UID 持有可见窗口」豁免，
     * 但分享面板需要 Activity 上下文，Android 14+ ROM 上直接起不可靠。
     */
    private fun shareGood() {
        val file = lastGoodFile
        if (file == null) {
            NoticeCenter.warn("还没有 GOOD 导出，先完成一次扫描")
            return
        }
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        // ★ A29：分享文件名不再走 extras（防第三方伪造 putExtra 诱导分享任意 filesDir 文件），
        //   改经 PendingHandoff；MainActivity 侧另有 good_export_* 文件名白名单与快照拷贝。
        PendingHandoff.post(PendingHandoff.Request.Share(file))
        startActivity(intent)
    }

    /** 更新前台服务通知内容（同 ID notify，保留 foreground 语义）。 */
    private fun updateForegroundNotification(progressText: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            // ★ #310/M4：后台模式的进度带「后台·」前缀（读权威设置，与运行模式切换同源）——
            //   扫描在虚拟屏上跑时通知栏一眼可辨，与前台模式进度区分。
            val prefix =
                if (settingsRepository.get().runMode == com.bettergi.pocket.settings.RunMode.BACKGROUND) "后台·" else ""
            manager.notify(
                NOTIFICATION_ID,
                buildNotification(
                    sharing = captureController.isRunning(),
                    progressText = prefix + progressText,
                ),
            )
        } catch (e: Exception) {
            Log.w("BetterGI.Service", "update scan notification failed", e)
        }
    }

    private fun shutdown() {
        if (shutDown) return
        shutDown = true
        // stop() 只掐当前扫描作业；scope 上的常驻收集器（抓包状态、探针）要随宿主一起走（P3-2）。
        scriptRunner.shutdown()
        clearVpnConsentTimeout()
        clearCaptureConsentTimeout()
        clearCaptureConsentProbe()
        // 服务没了还留着 VPN = 白占设备唯一的隧道位、且再没人收数据（会话在本进程）。
        if (CaptureSession.isRunning()) Log.i(TAG, CaptureSession.stop(applicationContext))
        InputAccessibilityService.cancelRecoverCheck()
        genshinLaunchMonitor.stop()
        engine.release()
        captureController.stop()
        overlayController.hide()
        runCatching { unregisterReceiver(a11yStateReceiver) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        // 下次 startForegroundService 必须重新满足时限（本实例的前台态已随 stopForeground 撤销）
        foregroundSatisfied = false
    }

    private fun stopScreenShare() {
        Log.i(TAG, "stopScreenShare：本进程主动收投影（开关落回 / 授权失败 / 退出）")
        requestingCapturePermission = false
        engine.stop()
        captureController.stop()
        if (!shutDown) {
            startInForeground(sharing = false)
        }
    }

    /** 0 = 不限（悬浮窗约定）→ ScanEngine Int.MAX_VALUE。 */
    private fun maxPagesOrDefault(raw: Int): Int = if (raw <= 0) Int.MAX_VALUE else raw

    /**
     * **起流程的唯一入口**（2026-09-18 修"点了没反应"）。
     *
     * 修复前的缺陷：`startScan` 的 `plan` 参数**只有 adb 调试通道会传**，用户从悬浮窗点
     * 「圣遗物锁定 / 自动装备」时 `plan = null` ⇒ `ScanEngine.foreach` 打一行 warn 就返回，
     * 表现为"跑了一遍界面什么都没做"。
     *
     * 现在的行为：按脚本自己的 `ui.actions`（有没有 `import`）+ `vars`（要什么数据）取输入；
     * 需要输入却没有 / 这份文件喂不了它 ⇒ **发一条提醒并拒绝起跑**，不再静默空跑。
     */
    private fun startFlowIfReady(flowKey: String, maxPages: Int) {
        val ctx = applicationContext
        // 起扫前台闸在 ScriptRunner.startScan（5 个起跑入口的共同汇聚点），这里不重复判。
        if (GoodRepository.needsInput(ctx, flowKey)) {
            val plan = GoodRepository.planFor(ctx, flowKey)
            if (plan == null) {
                // 三种成因各自的文案由 GoodRepository 给（没文件 / 形态不对 / 缺 char），
                // 避免「已经选过文件」时仍被提示「请先选文件」而误导用户。
                NoticeCenter.warn(
                    GoodRepository.inputProblem(ctx, flowKey) ?: "「$flowKey」缺少输入数据，已取消起跑",
                )
                if (settingsRepository.get().scanEnabled) {
                    settingsRepository.setScanEnabled(false)
                }
                return
            }
            overlayController.collapse() // 自动化执行前收面板（与滑动测试缩球对称）
            Log.i(TAG, "start flow=$flowKey plan=${plan.size} (from GoodRepository)")
            scriptRunner.startScan(flowKey, maxPages, plan = plan)
            return
        }
        overlayController.collapse()
        scriptRunner.startScan(flowKey, maxPages)
    }

    private fun requestCapturePermission() {
        if (requestingCapturePermission) return
        requestingCapturePermission = true
        // ★ #250：发起时刻**先**记下来（elapsedRealtime，与 CapturePermissionActivity 落的那笔同源），
        //   两条兜底（1.2s 探针 / 60s 无人应答）都拿它判"这一次启动落没落地"。
        val requestedAtElapsed = SystemClock.elapsedRealtime()
        armCaptureConsentTimeout(requestedAtElapsed)
        try {
            // 悬浮窗点击 = 用户交互，且本应用 UID 持有可见窗口（无障碍进程里的悬浮窗）⇒
            // 命中「允许后台启动 Activity」的豁免，无需 SAW 也能直接拉起授权 activity
            // （2026-09-18 已用探针实测：吊销「显示在上层」权限后仍可启动）
            val intent = Intent(this, CapturePermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            // ★ #250：启动被 ROM **静默丢弃**时 startActivity 不抛异常（见 CapturePermissionActivity
            //   的 launchedAtElapsed 注释）⇒ 光靠上面的 catch 兜不到，必须延迟主动问一次。
            armCaptureConsentProbe(requestedAtElapsed)
        } catch (e: Exception) {
            Log.w(TAG, "direct capture launch failed, bringing app to front", e)
            bringAppToFrontForCapture()
        }
    }

    /**
     * 投影授权的兜底通路（原 `requestCapturePermission` 的 catch 分支，一字未改语义）：
     * 部分 ROM 收紧后台启动限制 ⇒ 拉 app 前台，由 MainActivity 在前台内重发起。
     *
     * #250 起有两个调用点：`startActivity` **抛异常**（原路径），以及探针**正面问到**授权页被
     * 静默丢弃（[CaptureConsentProbe.Verdict.Swallowed]）。
     */
    private fun bringAppToFrontForCapture() {
        requestingCapturePermission = false
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        // ★ A29：敏感中转不再走 extras（exported 的 MainActivity 可被任意 app putExtra 伪造），
        //   改为进程内静态交接——服务与 MainActivity 同在主进程，先登记再拉前台。
        //   顺序也保证：就算下面的 startActivity 被 ROM 拦下（不抛、只是 no-op，见 #250 实测），
        //   这笔登记还在 ⇒ 用户自己点开 app（通知栏 `openIntent` 也算）就能接着发起授权。
        PendingHandoff.post(PendingHandoff.Request.RequestCapture)
        // #250：本函数现在有三个调用点（catch / 1.2s 探针 / 60s 超时），后两个不在任何 try 里，
        //   而后台 startActivity 在部分 ROM 上就是会抛 SecurityException/IllegalStateException
        //   （A27 当年为同一个理由把 controller.start 改成"保证不抛"）。
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "capture 前台兜底自身起不来（只剩 handoff 登记）：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * #250：发起授权后 [CaptureConsentProbe.PROBE_DELAY_MS]，**主动问一次**授权页到底弹没弹。
     *
     * 判据与常量见 [CaptureConsentProbe] 的类注释（依据 2026-10-13 华为 EML-AL00 实测量）。
     * 与既有的 [armCaptureConsentTimeout]（60s 只复位标志）**并存**，不替换它：那条管的是
     * 「页面上有人但没人应答」，这条管的是「页面根本没出来」。
     */
    private fun armCaptureConsentProbe(requestedAtElapsed: Long) {
        captureConsentProbe?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable {
            captureConsentProbe = null
            // 标志已被结果回调放下 ⇒ 授权链路自己走完了（成功/被拒），没什么可问的
            if (!requestingCapturePermission) return@Runnable
            // 现问在 :a11y 主线程卡住时最长等 1s（liveActivePackage 的 latch），服务主线程等不起
            // ⇒ 问在后台线程，判完只有需要兜底时才 post 回主线程。
            Thread(
                {
                    val outcome = CaptureConsentProbe.ask(
                        stillPending = { requestingCapturePermission },
                        relayLaunched = { CapturePermissionActivity.launchedAtElapsed >= requestedAtElapsed },
                        ownPackage = packageName,
                        consentPackages = InputAccessibilityService.TRANSIENT_PACKAGES,
                    ) { InputAccessibilityService.activeWindowPackage() }
                    Log.i(TAG, "capture consent 探针: ${outcome.describe()}")
                    if (outcome.verdict == CaptureConsentProbe.Verdict.Swallowed) {
                        mainHandler.post { captureConsentSwallowed(outcome) }
                    }
                },
                "capture-consent-probe",
            ).start()
        }
        captureConsentProbe = task
        mainHandler.postDelayed(task, CaptureConsentProbe.PROBE_DELAY_MS)
    }

    /**
     * 探针判定「授权页被静默丢弃」：走与 catch 完全同一条兜底。
     * 回主线程这段路上结果可能刚好到达（用户手快），所以再确认一次标志。
     */
    private fun captureConsentSwallowed(outcome: CaptureConsentProbe.Outcome) {
        if (!requestingCapturePermission) {
            Log.i(TAG, "capture consent 探针: ${outcome.describe()}⇒ 但结果已回，不动")
            return
        }
        Log.w(TAG, "capture consent 探针: ${outcome.describe()}⇒ 走前台兜底")
        bringAppToFrontForCapture()
    }

    /**
     * 发起 VPN 授权（抓包隧道的系统弹窗），**与 [requestCapturePermission] 同一条路子**：
     * 悬浮窗点击算用户交互 ⇒ 直接拉中转页；被 ROM 拦下就拉 MainActivity 到前台再发起。
     *
     * 已经授权过就不弹窗：`vpnConsentIntent()` 返回 null，中转页直接回报 OK。
     */
    private fun requestVpnConsent() {
        if (requestingVpnConsent) return
        requestingVpnConsent = true
        armVpnConsentTimeout()
        try {
            startActivity(
                Intent(this, CaptureConsentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            requestingVpnConsent = false
            clearVpnConsentTimeout()
            Log.w(TAG, "direct vpn consent launch failed, bringing app to front", e)
            // ★ A29：同投影兜底——敏感中转改经 PendingHandoff（进程内静态交接），extras 不再承载。
            PendingHandoff.post(PendingHandoff.Request.RequestVpn)
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
            )
        }
    }

    /** 授权弹窗「无人应答」兜底：见 [vpnConsentTimeout] 的说明。到点只复位标志，不碰会话。 */
    private fun armVpnConsentTimeout() {
        vpnConsentTimeout?.let { mainHandler.removeCallbacks(it) }
        vpnConsentTimeout = Runnable {
            vpnConsentTimeout = null
            if (!requestingVpnConsent) return@Runnable
            requestingVpnConsent = false
            Log.w(TAG, "vpn consent 无人应答（${CONSENT_TIMEOUT_MS}ms）⇒ 复位发起标志（否则 canAutoLaunch 恒假）")
        }.also { mainHandler.postDelayed(it, CONSENT_TIMEOUT_MS) }
    }

    /**
     * 60s「无人应答」兜底。#250 起它**分两种**，因为这两种的用户处境完全不同：
     * - 中转页在这一次发起**之后被创建过** ⇒ 页面确实出来了、只是没人答 ⇒ 照旧只复位标志，
     *   不去抢前台（用户可能正盯着弹窗）；
     * - 中转页在这一次发起**之后从没被创建** ⇒ 这次 `startActivity` 就是没落地。
     *   这时候现问也帮不上（被吞的典型成因就是 `:a11y` 不在，[CaptureConsentProbe] 只能报
     *   `Unknown`），但**这一条判据不需要现问**：任何真启动在 1.2s 内就会创建本页
     *   （实测 onCreate 距发起 84~461ms），60s 还没有就是没起来 ⇒ 走与 catch 同一条前台兜底。
     *
     * ⚠️ 只在"标志还挂着"时判（结果已回 ⇒ `requestingCapturePermission=false`，上面直接 return）。
     */
    private fun armCaptureConsentTimeout(requestedAtElapsed: Long) {
        captureConsentTimeout?.let { mainHandler.removeCallbacks(it) }
        captureConsentTimeout = Runnable {
            captureConsentTimeout = null
            if (!requestingCapturePermission) return@Runnable
            val neverLaunched = CaptureConsentProbe.timedOutWithoutRelay(
                requestedAtElapsed = requestedAtElapsed,
                relayLaunched = CapturePermissionActivity.launchedAtElapsed >= requestedAtElapsed,
            )
            if (neverLaunched) {
                Log.w(
                    TAG,
                    "capture consent 无人应答（${CONSENT_TIMEOUT_MS}ms）且中转页在这次发起后从未创建" +
                        "⇒ 判这次启动没落地，走前台兜底",
                )
                bringAppToFrontForCapture()
                // ★ 2026-10-13 21:24 华为实测：这个前台兜底**在同一次被拦的处境里也起不来**
                //   （`START {…MainActivity} result: 102`，`mCurrentFocus` 一直停在桌面）——
                //   被吞的成因就是整个 UID 没有前台凭据，兜底自己也是后台 startActivity。
                //   唯一还通得了的通道是 FGS 常驻通知（`openIntent` → MainActivity →
                //   `pendingCaptureRequest` 前台内重发起），所以这里必须把它明确指出来，
                //   而不是留一个自顾自亮着的开关。
                NoticeCenter.error(
                    "屏幕共享授权页被系统拦下（后台启动受限）：请在本应用里关一次再开「屏幕共享」",
                )
            } else {
                requestingCapturePermission = false
                Log.w(TAG, "capture consent 无人应答（${CONSENT_TIMEOUT_MS}ms）⇒ 复位发起标志（页面已出，不抢前台）")
            }
        }.also { mainHandler.postDelayed(it, CONSENT_TIMEOUT_MS) }
    }

    private fun clearVpnConsentTimeout() {
        vpnConsentTimeout?.let { mainHandler.removeCallbacks(it) }
        vpnConsentTimeout = null
    }

    private fun clearCaptureConsentTimeout() {
        captureConsentTimeout?.let { mainHandler.removeCallbacks(it) }
        captureConsentTimeout = null
    }

    /** #250：服务退出时把在途的授权探针一起撤掉（退出后不该再把任何页面顶到前台）。 */
    private fun clearCaptureConsentProbe() {
        captureConsentProbe?.let { mainHandler.removeCallbacks(it) }
        captureConsentProbe = null
    }

    private fun startInForeground(sharing: Boolean) {
        createNotificationChannelIfNeeded()
        val notification = buildNotification(sharing)
        if (Build.VERSION.SDK_INT >= 29) {
            val serviceType =
                if (sharing) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundSatisfied = true
    }

    private fun buildNotification(
        sharing: Boolean,
        progressText: String? = null,
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = progressText
            ?: if (sharing) "正在共享屏幕" else "点悬浮球可开启共享屏幕"
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        // 仅 FGS 常驻通知：开始/停止扫描与分享 GOOD 全部走悬浮窗面板（零通知依赖，fix53）
        builder
            .setContentTitle("更好的原神")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
        return builder.build()
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        val existing = manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "BetterGIPocket",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    /**
     * 解析探针的 ROI 串：`"x,y,w,h;x,y,w,h;…"` → `List<IntRect>`（帧坐标）。
     * 非法片段静默跳过（探针不该因输入错误而失败）。
     *
     * ⚠️ **不要用 `--es` 直接传含 `;` 的串**：`adb shell` 会把 `;` 当**命令分隔符**，
     * 只有第一个 ROI 能到达（实测踩过）。故新增 [EXTRA_ROIS_B64]（base64）作为主通道。
     */
    private fun parseRoiCsv(csv: String): List<com.bettergi.pocket.core.IntRect> {
        if (csv.isBlank()) return emptyList()
        return csv.split(';').mapNotNull { seg ->
            val p = seg.trim().split(',').mapNotNull { it.trim().toIntOrNull() }
            if (p.size >= 4) com.bettergi.pocket.core.IntRect(p[0], p[1], p[2], p[3]) else null
        }
    }

    /** base64 解码（探针参数通道；非法输入返回空串，绝不抛）。 */
    private fun decodeB64(s: String): String = try {
        String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), Charsets.UTF_8)
    } catch (_: Exception) {
        ""
    }

    /**
     * 解析带名字的 ROI 串：`"name:x,y,w,h;name:x,y,w,h;…"` → `List<Pair<String,IntRect>>`。
     * 供 **ROI 稳定性探针** 用（要报出每个候选的名字）。非法片段跳过。
     */
    private fun parseNamedRois(s: String): List<Pair<String, com.bettergi.pocket.core.IntRect>> {
        if (s.isBlank()) return emptyList()
        return s.split(';').mapNotNull { seg ->
            val i = seg.indexOf(':')
            if (i <= 0) return@mapNotNull null
            val name = seg.substring(0, i).trim()
            val p = seg.substring(i + 1).trim().split(',').mapNotNull { it.trim().toIntOrNull() }
            if (name.isEmpty() || p.size < 4) {
                null
            } else {
                name to com.bettergi.pocket.core.IntRect(p[0], p[1], p[2], p[3])
            }
        }
    }

    companion object {
        const val TAG = "BetterGI.Service"
        const val ACTION_START = "com.bettergi.pocket.action.START"
        const val ACTION_STOP = "com.bettergi.pocket.action.STOP"
        /** 抓包会话停止（悬浮窗开关经 SettingsBridgeProvider 转发过来）。 */
        const val ACTION_CAPTURE_STOP = "com.bettergi.pocket.action.CAPTURE_STOP"
        /** 抓包会话开始（同样经桥过来：发起权在主进程，面板只发指令）。 */
        const val ACTION_CAPTURE_START = "com.bettergi.pocket.action.CAPTURE_START"
        /** VPN 授权中转页的回报（OK 或玩家拒绝）。工单 D：值定义收口到 capture/CaptureContract，此处仅别名引用（值逐字不变）。 */
        const val ACTION_VPN_RESULT = CaptureContract.ACTION_VPN_RESULT
        const val EXTRA_VPN_OK = CaptureContract.EXTRA_VPN_OK
        const val ACTION_CAPTURE_RESULT = CaptureContract.ACTION_CAPTURE_RESULT
        const val ACTION_CAPTURE_DENIED = CaptureContract.ACTION_CAPTURE_DENIED
        /**
         * 悬浮窗「开始导出」：由无障碍进程经设置桥（`overlay_share_good`）转成服务指令。
         * 之所以不在无障碍进程直接起分享：导出文件名只有本服务知道（`lastGoodFile`），
         * 且系统分享面板必须由带 Activity 的进程拉前台。
         */
        const val ACTION_SHARE_GOOD = "com.bettergi.pocket.action.SHARE_GOOD"
        const val ACTION_SCAN_START = "com.bettergi.pocket.action.SCAN_START"
        const val ACTION_SCAN_STOP = "com.bettergi.pocket.action.SCAN_STOP"
        const val ACTION_DEBUG_SET_SCREEN_SHARE = "com.bettergi.pocket.action.DEBUG_SET_SCREEN_SHARE"
        const val ACTION_DEBUG_SET_SCAN = "com.bettergi.pocket.action.DEBUG_SET_SCAN"
        /** A/B：格点击形态（tap = 零位移；swipe = 2px 微滑）。 */
        const val ACTION_DEBUG_SET_CELL_CLICK = "com.bettergi.pocket.action.DEBUG_SET_CELL_CLICK"

        /** 诊断通路：与引擎同一注入链点 VD（见 onStartCommand 分支注释）。 */
        const val ACTION_DEBUG_VD_TAP = "com.bettergi.pocket.action.DEBUG_VD_TAP"
        /** A/B：限制扫描页数（小样本用）。 */
        const val ACTION_DEBUG_SET_MAX_PAGES = "com.bettergi.pocket.action.DEBUG_SET_MAX_PAGES"
        const val EXTRA_MODE = "mode"

        /** DEBUG_VD_TAP 的坐标 extras。 */
        const val EXTRA_X = "x"
        const val EXTRA_Y = "y"
        const val EXTRA_PAGES = "pages"
        const val ACTION_DEBUG_STATUS = "com.bettergi.pocket.action.DEBUG_STATUS"

        /**
         * 把最近一次 GOOD 导出**拷贝到外部目录**供 adb 拉取（标定/回归比对用）。
         * 背景：BlueStacks 禁用 `run-as`（setegid 失败）且无 su ⇒ 私有 filesDir 读不到。
         * 落点：`/sdcard/Android/data/<pkg>/files/sweep_last_good.json`（adb shell 有 ext_data_rw 可读）。
         */
        const val ACTION_DEBUG_DUMP_GOOD = "com.bettergi.pocket.action.DEBUG_DUMP_GOOD"
        /** 调试：把**当前输入库**（扫描产物或抓包入库）拷到外置目录，供 adb pull 做对照。 */
        const val ACTION_DEBUG_DUMP_INPUT = "com.bettergi.pocket.action.DEBUG_DUMP_INPUT"
        /**
         * 调试：逐格落盘「识别实际使用的那一帧」（`--ez enabled true|false`）。
         * 落点 `<外置files>/panelshots_<HHmmss>/`，含 `manifest.jsonl`。用于判定整页面板冻结
         * 到底是游戏侧真没换面板，还是我方误识别 —— 见 ScanEngine.dumpPanelShot。
         */
        const val ACTION_DEBUG_SET_PANEL_SHOTS = "com.bettergi.pocket.action.DEBUG_SET_PANEL_SHOTS"
        /**
         * 调试：逐**推理行**落盘「模型实际看到的输入像素」（`--ez enabled true|false`）。
         * 落点 `<外置files>/recdumps_<MMdd_HHmmss_SSS>/`，含 `manifest.jsonl`（panel/field、ROI、
         * scaledW、组宽与 pad、空读、文本、置信度、推理时长、异常）。与 panelshots 的分工：
         * 那个证「帧新旧」，这个证「OCR 边界读到了什么」——见 RecInferenceDump。
         */
        const val ACTION_DEBUG_SET_REC_DUMPS = "com.bettergi.pocket.action.DEBUG_SET_REC_DUMPS"
        /** 调试：把设备上的 GOOD/配装计划文件复制成当前输入（`--es src /sdcard/xxx.json`）。 */
        const val ACTION_DEBUG_SET_GOOD = "com.bettergi.pocket.action.DEBUG_SET_GOOD"
        const val EXTRA_GOOD_SRC = "src"
        /**
         * 调试：写「角色昵称」表（#105 四格之一）—— `--es field traveler|wanderer|manekin|manekina`
         * `--es value <显示名>`，不给 `value` 即清空该格。
         *
         * 为什么要有这条：这几格的值是**账号侧**的显示名（本账号两个未上线角色在游戏里显示为
         * 「随机姓名 / 随机人名」），而 BlueStacks 上 `run-as` 被拒（写不进 shared_prefs）、
         * `input text` 又打不进中文 ⇒ 管理器页那四个 EditText 在自动化链路上填不了，
         * 角色对账就没法复现。走的是与页面**同一个** [com.bettergi.pocket.scan.NameOverrides.save]，
         * 且引擎每次起扫现读该表 ⇒ 不需要重启进程。
         */
        const val ACTION_DEBUG_SET_NAME_OVERRIDE = "com.bettergi.pocket.action.DEBUG_SET_NAME_OVERRIDE"
        const val EXTRA_NAME_FIELD = "field"
        const val EXTRA_NAME_VALUE = "value"
        const val ACTION_DEBUG_SET_PROBE = "com.bettergi.pocket.action.DEBUG_SET_PROBE"
        const val ACTION_DEBUG_SET_VERBOSE = "com.bettergi.pocket.action.DEBUG_SET_VERBOSE"
        const val ACTION_DEBUG_SWIPE_TEST = "com.bettergi.pocket.action.DEBUG_SWIPE_TEST"
        const val ACTION_DEBUG_SCAN_FLOW = "com.bettergi.pocket.action.DEBUG_SCAN_FLOW"

        /**
         * 调试：按**与悬浮窗 ▶ 完全相同的入口**起流程（★ 2026-09-18）。
         *
         * 与 [ACTION_DEBUG_SCAN_FLOW] 的区别：那个分支直接 `startScan(flow,maxPages,plan)`，
         * **绕过** `startFlowIfReady` 的输入守卫与 `GoodRepository` 注入 ⇒ 测不到真实用户路径。
         * 本动作走 `startFlowIfReady`，与悬浮窗点击同一条路，但不需要点悬浮窗
         * （实测点悬浮窗三重不稳：球位漂移 / 行 y 随面板高度变 / ■ 态残留）。
         */
        const val ACTION_DEBUG_START_FLOW = "com.bettergi.pocket.action.DEBUG_START_FLOW"
        const val ACTION_DEBUG_CLICK = "com.bettergi.pocket.action.DEBUG_CLICK"
        const val ACTION_DEBUG_OCR_BENCH = "com.bettergi.pocket.action.DEBUG_OCR_BENCH"
        /**
         * 风险档 EP 探测（`--ez risky true|false`）：一次性另建引擎，按 `candidatesFor(caps, risky)`
         * 定档并跑 det/rec 基准 ⇒ 用来回答"这台机器的 NNAPI/FP16 能不能启用、快不快"。
         * 生产档位不受影响（探测完即 close），扫描在跑时直接忽略。
         */
        const val ACTION_DEBUG_OCR_EP = "com.bettergi.pocket.action.DEBUG_OCR_EP"
        const val EXTRA_RISKY_EP = "risky"
        const val ACTION_DEBUG_OCR_DET = "com.bettergi.pocket.action.DEBUG_OCR_DET"
        /** 抓包数据源 C1：离线回放一个 pcap 到 GOOD（`EXTRA_PCAP_PATH` 指路径）。 */
        const val ACTION_DEBUG_REPLAY_PCAP = "com.bettergi.pocket.action.DEBUG_REPLAY_PCAP"
        const val EXTRA_PCAP_PATH = "pcapPath"
        /** 抓包 C2：`--ez enabled true` 开在线隧道，false 收隧道并入库。 */
        const val ACTION_DEBUG_CAPTURE = "com.bettergi.pocket.action.DEBUG_CAPTURE"

        /**
         * ★ 只读性能探针（2026-09-12）：帧路径拆段（alloc/put/cvtColor/全帧 vs ROI 口径）
         * + rec 宽度基准（含输出时间步 T）。**不改任何行为**，为
         * `dsl/verify/_audit/IMAGE-PATH-COST.md` §8 的决策提供分量实测值。
         * 用法：`-a <本 action> --ei runs 30 --es rois "x,y,w,h;…" --es widths "145,220,405,684"`
         */
        const val ACTION_DEBUG_PERF_PROBE = "com.bettergi.pocket.action.DEBUG_PERF_PROBE"
        const val EXTRA_RUNS = "runs"
        /** ROI 列表（帧坐标）：`"x,y,w,h;x,y,w,h"`；空 = 跳过 ROI 段。⚠️ adb shell 会吞 `;` ⇒ 优先用 B64。 */
        const val EXTRA_ROIS = "rois"
        /** ★ 首选通道：`base64("x,y,w,h;…")`。避开 device shell 对 `;` 的命令分隔解析。 */
        const val EXTRA_ROIS_B64 = "roisB64"
        /** rec 宽度列表（逗号分隔）；空 = 默认 `145,220,405,684`。 */
        const val EXTRA_WIDTHS = "widths"
        /**
         * ★ ROI **稳定性**探针输入：`base64("name:x,y,w,h;name:…")`。
         * 用于挑选"渲染完成后跨帧像素恒等"的 ROI 作自适应就绪锚（见 PIPELINE-FEASIBILITY.md §11.3）。
         */
        const val EXTRA_STABILITY_B64 = "stabilityB64"
        /** 稳定性探针的采样帧数（默认 60 ≈ 2s @30fps）。 */
        const val EXTRA_FRAMES = "frames"
        /**
         * ★ OCR 并行度探针输入：`"k:intra,k:intra,…"`（如 `"1:1,1:2,2:1,3:1"`）。
         * 用于判断"该调 intra 还是该做多会话并行池"，见 `OcrParallelProbe` 的 KDoc。
         */
        const val EXTRA_OCR_PAR = "ocrpar"
        /** ★ 帧率探针窗口（ms）：`--ei frate 1200`；0 = 跳过。 */
        const val EXTRA_FRAME_RATE = "frate"

        /** 探针日志独立 tag，便于 `logcat -s BetterGI.Perf` 直取。 */
        const val PERF_TAG = "BetterGI.Perf"

        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_START_Y = "startY"
        const val EXTRA_DIST = "dist"
        const val EXTRA_MEASURE = "measure"
        const val EXTRA_GRID = "grid"
        const val EXTRA_FROM_X = "fromX"
        const val EXTRA_DUR_MS = "durMs"
        const val EXTRA_SEGS = "segments"
        const val EXTRA_METHOD = "method"
        const val EXTRA_FLOW = "flow"
        /**
         * 2026-10-14 套装筛选后扫描（adb 调试工具）：逗号分隔的套装词典 key
         * （`tools/mappings.json` 的 `artifactSets[].id`，如 `HeartOfTheFurnace`）。
         * 给了它而没显式给 [EXTRA_FLOW] ⇒ 自动选 `artifact_scan_set` 并合成 setKey 计划。
         */
        const val EXTRA_SETS = "sets"
        const val EXTRA_MAX_PAGES = "maxPages"
        /** §14 P4：外部任务计划 JSON 数组（明文，adb shell 会吞双引号，标定用请走 planB64）。 */
        const val EXTRA_PLAN = "plan"
        /** §16.4 标定稳定通道：base64(JSON) 的 plan，device sh 不吞引号，标定/调试首选。 */
        const val EXTRA_PLAN_B64 = "planB64"

        /**
         * 时序覆盖（仅调试标定）：`--es timing "nav=650,sstable=150,swFastMs=110,..."`
         * 见 [com.bettergi.pocket.scan.TimingOverrides] 与 dsl/verify/_audit/PERF-timing.md 档位计划。
         * 空/缺省 → 全默认（与改动前逐位一致）。
         */
        const val EXTRA_TIMING = "timing"
        /** §12.1：true=几何推导翻页起点（卡缝），false=profiles 写死 `advance.from`。只切起点。 */
        const val EXTRA_GEO_ADVANCE = "geoAdvance"
        const val EXTRA_CLICK_X = "x"
        const val EXTRA_CLICK_Y = "y"
        const val EXTRA_CLICK_DURATION = "duration"
        /** 投影授权页回传的 resultCode（工单 D：值定义收口到 capture/CaptureContract，此处仅别名引用，值逐字不变）。 */
        const val EXTRA_RESULT_CODE = CaptureContract.EXTRA_RESULT_CODE
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val NOTIFICATION_CHANNEL_ID = "bettergi_pocket_trigger"
        private const val NOTIFICATION_ID = 1001

        /**
         * 授权弹窗的「无人应答」上限（见 [armVpnConsentTimeout]）：正常作答几秒内，
         * 划掉弹窗则永远没有回报 ⇒ 授权标志需要一个复位点，否则 `canAutoLaunch` 永久为假。
         */
        private const val CONSENT_TIMEOUT_MS = 60_000L
    }
}

/**
 * #250（2026-10-13）：投影授权「到底弹出来了没有」的**主动问** —— 把判据从「等异常」换成
 * 「发起之后，正面问到授权链路落地了没有」。
 * 纯 JVM（探针 / 睡眠 / 时钟全部注入，无 Android 依赖）⇒ `CaptureConsentProbeTest` 直接驱动桩探针。
 *
 * ### 缺陷形状（实测依据）
 * 旧实现只在 `catch (e: Exception)` 里兜底，前提是「后台启动被 ROM 拦下会抛」。华为 EML-AL00
 * （EMUI 13 / Android 10）上这条不成立：2026-10-11 量到 `startActivity` 正常返回、ATMS 只打
 * `Background activity start … isBgStartWhitelisted: false` + `result: 102` 就把启动丢掉，
 * app 侧唯一的痕迹是 60s 后那句「capture consent 无人应答」—— 兜底分支在**最需要它的时刻不可达**。
 * ⇒ 判据必须是正向的：问到"落地了"就等着，问到"什么都没动"才兜底。
 * ⚠️ 2026-10-13 本轮**没能再让它丢一次**：拆掉悬浮球（发起时没有任何自家可见窗口）也从
 * `areBackgroundActivityStartsAllowed return false` 里被华为自己的分支救回来 ——
 * `allow if the caller is bound by a UID that's currently foreground` + `…:com.bettergi.pocket:a11y
 * is BackgroundActivityStartsAllowed` ⇒ 无障碍在位时整个 UID 被豁免。被吞最可能要求那条豁免也不成立
 * （`:a11y` 不在 / 无障碍没连），本轮没验那条（要动用户的无障碍）。详见那份 docs 的 §2。
 *
 * ### 两条信号，AND 起来才判「被吞」（fail-safe 方向）
 * 1. **本页被创建过**：`CapturePermissionActivity.launchedAtElapsed >= 发起时刻`。实测落地很快
 *    （2026-10-13 三轮，都按"距服务那次 startActivity"计：窗口 `Making visible` +157ms、
 *    onCreate 日志 +220ms、`:a11y` 收到本页 window-state +73~461ms），所以延迟到 [PROBE_DELAY_MS]
 *    之后仍然没有这笔时间戳，就是真没落地。
 * 2. **现问说前台还是外来包**：`InputAccessibilityService.activeWindowPackage()` 问到非 null，
 *    且既不是自己也不在系统弹层名单里。
 *
 * 为什么**不能**只靠现问（本轮实测的关键一条）：授权页不是应用窗口！真弹出来时 `dumpsys window` 打
 * `mCurrentFocus=Window{… com.android.systemui}`，而窗口记录是
 * `ty=SYSTEM_ALERT appop=SYSTEM_ALERT_WINDOW package=com.android.systemui`。现问的第一条路
 * `rootInActiveWindow` 在这种非应用窗上能不能落到值**没量过**（本轮不编译，没有调用点）；
 * 而它写死的兜底路 `activePackageFromWindows` 只挑 `TYPE_APPLICATION` —— 按上面那条窗口记录，
 * 这一条在弹窗正开着时只会把**游戏**当成活动应用窗读出来（代码直读可判，未实测）。
 * 只凭现问判「被吞」就会在用户面前把 MainActivity 顶上去、把弹窗盖掉。
 * 两条都成立才动手，任何一条说"落地了"就停手。
 *
 * ### 常量怎么定的（数值全部来自 2026-10-13 的实测量，见 dsl/docs/capture-consent-launch-20261013.md）
 * - **延迟 [PROBE_DELAY_MS] = 1200ms**：必须在授权页落地之后再问。实测两种"起来了"的形态：
 *   · 弹窗真弹（`cmd appops set … PROJECT_MEDIA ignore` 逼出来的）：下一个采样点（≤0.4s）
 *     `mCurrentFocus` 就变成 com.android.systemui，并一直停在那儿（+1.2s 时仍在）。
 *   · 秒授权快路（appops=allow，今天这台机最常见的形态）：发起 → onCreate +220ms →
 *     结果回调 +200~474ms（`onCreate → result` 本身 136~254ms）⇒ **标志几百毫秒就被放下了**。
 *     延迟必须显著大于这条，否则探针会在一条已经成功的路上读到"前台是游戏"。
 *     1200ms 排在观测到最慢一次落地（systemui 页 `Displayed`，距发起 459ms）之后有余量，
 *     又远早于 60s 的复位点。
 * - **间隔 200ms / 至多 [ASK_ATTEMPTS_MAX]=2 次 / 总预算 [ASK_BUDGET_MS]=500ms**：与
 *   `scan/ScriptRunner.StartGateLiveAsk` 逐字同参 —— 同一个现问原语、同一个「华为点 ▶ 那刻
 *   rootInActiveWindow 恒 null（焦点切换中）」的 #197 实测（那边五次全空、已从 5 降到 2），
 *   这里没有任何新证据支持给得更宽。问不到就是问不到（[Verdict.Unknown]），不硬判。
 * - 现问全在**后台线程**上做：单次在 `:a11y` 主线程卡住时最长 1s（`liveActivePackage` 的 latch），
 *   最坏 2×1s + 200ms 也不能压在服务主线程上。
 */
internal object CaptureConsentProbe {

    /** 发起 startActivity 后多久开始问（ms）。见类注释「常量怎么定的」。 */
    const val PROBE_DELAY_MS = 1200L

    /** 现问最多问几次（含第一次）。 */
    const val ASK_ATTEMPTS_MAX = 2

    /** 两次现问之间的等待（ms）。 */
    const val ASK_INTERVAL_MS = 200L

    /** 整轮现问的**总耗时**上限（ms）：只管超时截断，不削减快失败路径的次数。 */
    const val ASK_BUDGET_MS = 500L

    /**
     * 60s「无人应答」那半条判据 —— **不依赖现问**的"这次启动没落地"。
     *
     * 为什么要有它（2026-10-13 华为实测的推论，装机验见 dsl/docs/capture-consent-launch-20261013.md §8）：
     * 被吞的典型成因就是 `:a11y` 不在，而 [ask] 的信号 2 恰恰要靠它 ⇒ 只能报 `Unknown` ⇒
     * 探针这半边在最需要它的场景里是哑的。但信号 1 是进程内的、不受影响：
     * 真启动时中转页 `onCreate` 距发起只 84~461ms（§1 实测量），60s 还没有就是没起来。
     *
     * @param requestedAtElapsed 本次发起的时刻（elapsedRealtime）；`<=0` = 没这笔记录 ⇒ 一律不判
     * @param relayLaunched      中转页在这次发起之后有没有被创建过
     */
    fun timedOutWithoutRelay(requestedAtElapsed: Long, relayLaunched: Boolean): Boolean =
        requestedAtElapsed > 0L && !relayLaunched

    enum class Verdict {
        /** 发起标志已放下 ⇒ 授权链路自己走完了（成功或被拒），无需判定也无需兜底。 */
        Settled,

        /** 有"落地了"的证据（本页创建过，或现问读到自家/系统弹层占着前台）⇒ 等着，别抢前台。 */
        CameUp,

        /** 本页从没被创建 **且** 现问正面读到外来包 ⇒ 这次启动被静默丢弃，该兜底了。 */
        Swallowed,

        /** 两条都没证据（无障碍没连 / 焦点切换中恒 null）⇒ 问不到，按不动处理。 */
        Unknown,
    }

    /**
     * @param verdict     结论
     * @param pkg         最后一次问到的活动窗口包名（`Settled` / `Unknown` 时为 null）
     * @param attempts    实际问了几次（Settled 可能是 0）
     * @param elapsedMs   整轮耗时（含重试间隔）
     * @param relayLaunched  本页「被创建过」那条信号当时是否为真
     * @param budgetCut   true = 因总预算提前收手（没问满 [ASK_ATTEMPTS_MAX] 次）
     */
    data class Outcome(
        val verdict: Verdict,
        val pkg: String?,
        val attempts: Int,
        val elapsedMs: Long,
        val relayLaunched: Boolean,
        val budgetCut: Boolean = false,
    ) {
        /** 四种结局（没问 / 已落地 / 判被吞 / 问不到）与停手原因，日志里一眼可分。 */
        fun describe(): String = when (verdict) {
            Verdict.Settled -> "标志已复位⇒授权链路自己走完了，不必问(${elapsedMs}ms/${attempts}次)"
            // pkg 为 null 的 CameUp 只可能是「本页创建过但现问读不到」，写清楚免得看成失败
            Verdict.CameUp -> "判定已落地 问${attempts}次/${elapsedMs}ms（现问=${pkg ?: "读不到"} 本页创建=$relayLaunched）⇒ 等用户应答"
            Verdict.Swallowed -> "判定被静默丢弃 问${attempts}次/${elapsedMs}ms（现问=$pkg 本页创建=false）"
            Verdict.Unknown -> "${attempts}次都没问到(${elapsedMs}ms${if (budgetCut) "/预算截断" else ""})⇒ 不动"
        }
    }

    /**
     * 问一轮。任一条"落地了"的证据成立就立刻停手 ⇒ 重试只在"两条都还没证据"时发生，
     * 绝不把"问到但不利"磨成"多问几次直到有利"（与 `scan/ScriptRunner` 里的 `StartGateLiveAsk` 同一条口径）。
     *
     * ⚠️ 探针放在**最后一个参数**：调用点是尾随 lambda，尾随 lambda 绑末位形参。
     *
     * @param stillPending   发起标志还在不在（结果回调一落地就该停手）
     * @param relayLaunched  信号 1：`CapturePermissionActivity.onCreate` 在本次发起之后跑过
     * @param ownPackage     自己包名（读前台的 Activity 也算"落地了"，不能据此抢前台）
     * @param consentPackages 授权页可能占着前台的系统弹层包名（实测弹窗属 com.android.systemui）
     * @param sleep          间隔实现（生产 = Thread.sleep，测试注入记录器）
     * @param clockMs        耗时基准（生产 = System.currentTimeMillis，测试注入假钟）
     * @param probe          信号 2：现问活动窗口包名（生产 = `InputAccessibilityService.activeWindowPackage()`）
     */
    fun ask(
        stillPending: () -> Boolean,
        relayLaunched: () -> Boolean,
        ownPackage: String,
        consentPackages: Set<String>,
        attemptsMax: Int = ASK_ATTEMPTS_MAX,
        intervalMs: Long = ASK_INTERVAL_MS,
        budgetMs: Long = ASK_BUDGET_MS,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        clockMs: () -> Long = { System.currentTimeMillis() },
        probe: () -> String?,
    ): Outcome {
        val t0 = clockMs()
        var attempts = 0
        while (true) {
            if (!stillPending()) {
                return Outcome(Verdict.Settled, null, attempts, clockMs() - t0, relayLaunched())
            }
            attempts++
            val launched = relayLaunched()
            val pkg = probe()
            val elapsed = clockMs() - t0
            // 「外来包」= 问到值了，且既不是自己也不是授权弹层可能占着前台的系统包。
            // 注意 pkg == null 时 foreign 也是 false —— 那是「问不到」，不能当「落地了」用，
            // 所以下面三条按 具体包名 → 本页创建 → 被吞 的顺序判，null 只能落到最后的重试。
            val foreign = pkg != null && pkg != ownPackage && pkg !in consentPackages
            if (pkg != null && !foreign) {
                return Outcome(Verdict.CameUp, pkg, attempts, elapsed, launched)
            }
            if (launched) {
                // 信号 1 成立就够了：前台现在是谁都不重要，这次启动没有被丢
                return Outcome(Verdict.CameUp, pkg, attempts, elapsed, relayLaunched = true)
            }
            if (pkg != null) {
                return Outcome(Verdict.Swallowed, pkg, attempts, elapsed, relayLaunched = false)
            }
            if (attempts >= attemptsMax) {
                return Outcome(Verdict.Unknown, null, attempts, elapsed, relayLaunched = false)
            }
            if (elapsed + intervalMs > budgetMs) {
                return Outcome(Verdict.Unknown, null, attempts, elapsed, relayLaunched = false, budgetCut = true)
            }
            sleep(intervalMs)
        }
    }
}
