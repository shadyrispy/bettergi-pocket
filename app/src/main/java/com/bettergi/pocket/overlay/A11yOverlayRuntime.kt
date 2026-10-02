package com.bettergi.pocket.overlay

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bettergi.pocket.genshin.GenshinLauncher
import com.bettergi.pocket.log.RecognitionLog
import com.bettergi.pocket.settings.BridgeSettingsRepository
import com.bettergi.pocket.settings.SettingsBridgeProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * **无障碍进程内的悬浮窗运行时**（2026-09-18 宿主迁移）。
 *
 * 为什么悬浮窗必须住在这里：`TYPE_ACCESSIBILITY_OVERLAY` 只有无障碍服务能建，
 * 而 View 不能跨进程 ⇒ 视图树必须在无障碍进程内构建，控制器随之搬过来。
 *
 * 进程分工：
 * - 本类（无障碍进程）：拥有 [OverlayWindowController] 与窗口；设置经 [BridgeSettingsRepository] 代理主进程。
 * - [OverlayBridge]（主进程）：同名 API 的门面，把扫描 / 自动对话的需求转成桥调用发过来。
 *
 * ⚠️ **不随无障碍连接自动上窗**：无障碍只是"能画"的前提，"什么时候画"仍由主进程的前台服务决定
 * （收到 `overlay_show` 才构建控制器）。否则用户一开无障碍就会看到一个没有 App 支撑的悬浮球。
 *
 * 跨进程注意事项：
 * - 桥调用由 binder 线程进入 ⇒ **凡触碰 View 的一律转到主线程**（[onMain]），否则
 *   `WindowManager` / `ViewRootImpl` 会在无 Looper 的线程上抛异常。
 * - 日志窗内容来自主进程缓冲 ⇒ 窗可见时按 [LOG_POLL_MS] 拉一份镜像（见 [pollLog]）。
 *   这条 IPC 在主线程上发出：主进程侧只做一次加锁快照，不会再回调 :a11y ⇒ 无死锁环。
 */
object A11yOverlayRuntime {

    private const val TAG = "BetterGI.A11yOverlay"

    /** 日志镜像轮询间隔（仅日志窗可见时）。 */
    private const val LOG_POLL_MS = 700L

    /** 主线程转交等待上限：超时即放弃该次调用（宁可漏一次穿透，也不能卡住扫描线程）。 */
    private const val MAIN_TIMEOUT_MS = 2000L

    private var controller: OverlayWindowController? = null
    private var settings: BridgeSettingsRepository? = null
    private var serviceRef: AccessibilityService? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var logPolling = false

    private val logPoller = object : Runnable {
        override fun run() {
            if (!logPolling) return
            pollLog()
            mainHandler.postDelayed(this, LOG_POLL_MS)
        }
    }

    // ---- 生命周期 ----

    /** 无障碍服务连上：只登记引用，**不上窗**（等主进程的 `overlay_show`）。 */
    fun attachService(service: AccessibilityService) {
        serviceRef = service
        Log.i(TAG, "service attached (overlay not shown yet)")
    }

    /** 无障碍服务断开：窗口活不过服务，整体销毁。 */
    fun detachService() {
        stop()
        serviceRef = null
        Log.i(TAG, "service detached")
    }

    /** 按需构建控制器（幂等）。 */
    private fun ensureStarted(): Boolean {
        if (controller != null) return true
        val svc = serviceRef ?: run {
            Log.w(TAG, "ensureStarted: 无障碍未连接，悬浮窗不可用")
            return false
        }
        val bridge = BridgeSettingsRepository(svc).also { it.start() }
        val ctl = OverlayWindowController(
            context = svc,
            settingsRepository = bridge,
            genshinLauncher = GenshinLauncher(svc),
            onExit = { bridge.requestStop() },
            onShareGoodRequested = { bridge.requestShareGood() },
        )
        // 日志双写：本地即时显示 + 送回主进程的单一源（否则会被镜像 replaceAll 冲掉）
        ctl.remoteLogSink = { tag, level, message -> bridge.appendLog(tag, level, message) }
        controller = ctl
        settings = bridge
        logPolling = true
        mainHandler.postDelayed(logPoller, LOG_POLL_MS)
        Log.i(TAG, "overlay runtime started (TYPE_ACCESSIBILITY_OVERLAY)")
        return true
    }

    /** 整体销毁（主进程请求隐藏 = 服务在收摊，控制器对象随之释放）。 */
    fun stop() {
        logPolling = false
        mainHandler.removeCallbacks(logPoller)
        controller?.let { runCatching { it.hide() } }
        controller = null
        settings?.stop()
        settings = null
        Log.i(TAG, "overlay runtime stopped")
    }

    // ---- 桥入口（主进程 OverlayBridge → 无障碍进程）----

    fun handle(method: String, extras: Bundle?): Bundle = when (method) {
        M_SHOW -> Bundle().apply {
            val ok = onMain { ensureStarted() && run { controller?.show(); true } } ?: false
            putBoolean(K_OK, ok)
        }
        M_HIDE -> Bundle().apply {
            onMain { stop(); true }
            putBoolean(K_OK, true)
        }
        M_COLLAPSE -> Bundle().apply {
            putBoolean(K_OK, onMain { controller?.collapse(); true } ?: false)
        }
        M_PROGRESS -> {
            val text = extras?.getString(K_TEXT).orEmpty()
            Bundle().apply { putBoolean(K_OK, onMain { controller?.updateScanProgress(text); true } ?: false) }
        }
        // 逐点击调用：命中悬浮窗矩形才需临时穿透（返回值决定调用方何时还原，语义见 OverlayBridge）
        M_PT_PREPARE -> Bundle().apply {
            val ok = onMain {
                controller?.prepareClickPassthrough(extras?.getInt(K_X) ?: 0, extras?.getInt(K_Y) ?: 0)
            }
            putBoolean(K_OK, ok == true)
        }
        M_PT_RESTORE -> Bundle().apply {
            putBoolean(K_OK, onMain { controller?.restoreClickPassthrough(); true } ?: false)
        }
        M_CLICK_THROUGH -> Bundle().apply {
            val enabled = extras?.getBoolean(K_ENABLED, false) == true
            val hidden = extras?.getBoolean(K_HIDDEN, false) == true
            putBoolean(K_OK, onMain { controller?.setScanClickThrough(enabled, hidden); true } ?: false)
        }
        M_NOTICE -> {
            val level = extras?.getString(K_LEVEL).orEmpty()
            val text = extras?.getString(K_TEXT).orEmpty()
            Bundle().apply {
                putBoolean(K_OK, onMain { controller?.showNotice(level, text); true } ?: false)
            }
        }
        M_EVENT -> {
            val kind = extras?.getString(K_KIND).orEmpty()
            Bundle().apply {
                putBoolean(
                    K_OK,
                    onMain {
                        val c = controller
                        when {
                            c == null -> false
                            kind == EVENT_TALK -> {
                                c.onTalkHistoryMatched(); true
                            }
                            kind == EVENT_CHAT_ICONS -> {
                                c.onChatIconsRecognized(
                                    extras?.getInt(K_COUNT) ?: 0,
                                    extras?.getInt(K_X) ?: 0,
                                    extras?.getInt(K_Y) ?: 0,
                                )
                                true
                            }
                            kind == EVENT_CHAT_CLICK -> {
                                c.onChatIconClicked(extras?.getInt(K_X) ?: 0, extras?.getInt(K_Y) ?: 0)
                                true
                            }
                            else -> false
                        }
                    } ?: false,
                )
            }
        }
        else -> Bundle()
    }

    /**
     * **无障碍进程自产提醒**的统一出口。
     *
     * 为什么不能直接用 [com.bettergi.pocket.notice.NoticeCenter]：它在本进程是**另一个实例**，
     * 既没有展位（悬浮窗展位在主进程的路由里），落下的日志也会被主进程的镜像整体覆盖。
     * 所以这里：① 本地提醒条直接显示 ② 经**已有的 `log_append`** 把文本回流主进程落日志（单一源）。
     */
    fun notice(level: String, text: String) {
        if (text.isBlank()) return
        onMain { controller?.showNotice(level, text) }
        val logLevel = if (level.equals("info", ignoreCase = true)) "I" else "W"
        settings?.appendLog(RecognitionLog.Tag.APP.name, logLevel, text)
    }

    // ---- 内部 ----

    /**
     * 日志镜像：把主进程的识别日志缓冲搬到无障碍进程，喂给本地 [RecognitionLog]
     * （控制器只认本地单例）。
     * ⚠️ 拉全量、**不按标签过滤**：过滤集留在渲染侧，两个进程各自维护，互不干扰。
     */
    private fun pollLog() {
        val svc = serviceRef ?: return
        if (onMain { controller?.isLogWindowVisible() } != true) return
        val bundle = try {
            svc.contentResolver.call(SettingsBridgeProvider.uri(svc), SettingsBridgeProvider.METHOD_LOG_SNAPSHOT, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "log snapshot failed", e)
            null
        } ?: return
        val raw = bundle.getStringArray(SettingsBridgeProvider.KEY_ENTRIES) ?: return
        RecognitionLog.replaceAll(raw.mapNotNull { RecognitionLog.decode(it) })
    }

    /** 转主线程执行并等结果（binder 线程无 Looper，直接碰 View 必炸）。 */
    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var result: T? = null
        mainHandler.post {
            result = runCatching { block() }
                .onFailure { Log.e(TAG, "onMain block failed", it) }
                .getOrNull()
            latch.countDown()
        }
        return try {
            if (!latch.await(MAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "onMain timeout")
            }
            result
        } catch (_: InterruptedException) {
            null
        }
    }

    // ---- 桥方法名（主进程 OverlayBridge 必须用同一组常量）----

    const val M_SHOW = "overlay_show"
    const val M_HIDE = "overlay_hide"
    const val M_COLLAPSE = "overlay_collapse"
    const val M_PROGRESS = "overlay_progress"
    const val M_PT_PREPARE = "overlay_pt_prepare"
    const val M_PT_RESTORE = "overlay_pt_restore"
    const val M_CLICK_THROUGH = "overlay_click_through"
    const val M_EVENT = "overlay_event"
    const val M_NOTICE = "notice_push"

    const val K_OK = "ok"
    const val K_TEXT = "text"
    const val K_X = "x"
    const val K_Y = "y"
    const val K_ENABLED = "enabled"
    const val K_HIDDEN = "hidden"
    const val K_KIND = "kind"
    const val K_COUNT = "count"
    const val K_LEVEL = "level"

    const val EVENT_TALK = "talk"
    const val EVENT_CHAT_ICONS = "chat_icons"
    const val EVENT_CHAT_CLICK = "chat_click"
}
