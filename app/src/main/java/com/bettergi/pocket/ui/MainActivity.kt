package com.bettergi.pocket.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import com.bettergi.pocket.PendingHandoff
import com.bettergi.pocket.R
import com.bettergi.pocket.bridge.OverlayEntryContract
import com.bettergi.pocket.overlay.ScriptIcons
import com.bettergi.pocket.input.InputAccessibilityService
import com.bettergi.pocket.notice.NoticeCenter
import com.bettergi.pocket.notice.NoticeRouter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import com.bettergi.pocket.scan.NameOverrides
import com.bettergi.pocket.capture.CapturePermissionActivity
import com.bettergi.pocket.pcdata.CaptureConsentActivity
import com.esc.irminsul.capture.CaptureResult
import com.esc.irminsul.capture.IrminsulCapture
import com.esc.irminsul.capture.PermissionKind
import com.bettergi.pocket.scan.GoodRepository
import com.bettergi.pocket.input.SwipeMethod
import com.bettergi.pocket.input.SwipeTestRunner
import com.bettergi.pocket.service.TriggerForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

/**
 * 启动壳 + service 中转（fix53）：
 * - 正常路径：交棒悬浮窗后即退出（零常驻）
 * - service 兜底拉前台：★ A29 起三类敏感中转（投影授权 / VPN 授权 / GOOD 分享）改走
 *   PendingHandoff（进程内静态交接），extras 通道废弃 —— exported 的 Activity 的 extras 任何
 *   第三方 app 都能伪造
 *
 * ★ Stage 3.6 拆分地图（2026-10-01）：本文件曾 775 行，两屏各自的渲染/绑定逻辑已按屏拆出
 * （均为**扩展函数**，函数体一字未改、调用点不变；`this` 即 MainActivity 实例）：
 * - `MainActivityScriptManager.kt` —— 脚本管理器屏（renderAll/renderStatus/renderCaptureSection/
 *   renderNameSection/renderScriptRows/renderGoodSection/bindCalibrateRow/bindSwipeRow 等）
 * - `MainActivityOnboarding.kt`    —— 引导屏（showOnboarding/renderOnboarding/onboardingReady）
 * 本文件保留：类与 companion、Intent/深链处理、onCreate/onResume/onPause 生命周期、授权与分享中转。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** service 兜底：拉前台后前台内发起投影授权。⚠️ A29：extras 通道已废弃（任何第三方都能伪造），改走 PendingHandoff。 */
        const val EXTRA_AUTO_REQUEST_CAPTURE = "auto_request_capture"

        /** service 兜底：拉前台后前台内发起 VPN 授权（抓包隧道，与投影同一条兜底路子）。⚠️ A29：extras 通道已废弃，改走 PendingHandoff。 */
        const val EXTRA_AUTO_REQUEST_VPN = "auto_request_vpn"

        /** service 兜底：拉前台后前台内起分享 chooser（值为 files 下文件名）。⚠️ A29：extras 通道已废弃，见 PendingHandoff。 */
        const val EXTRA_AUTO_SHARE = "auto_share"

        /** ★ A29：分享快照子目录（file_paths.xml 与 file_paths 收窄对齐）。 */
        const val GOOD_EXPORT_SUBDIR = "good_exports"

        /** 悬浮窗入口 extras（工单 D：键值定义下沉到 bridge/OverlayEntryContract，此处仅别名引用，值逐字不变）。 */
        val EXTRA_FROM_OVERLAY = OverlayEntryContract.EXTRA_FROM_OVERLAY

        /** 悬浮窗的 `import` 动作：进入管理器的同时打开输入文件选择器（SAF 必须由 Activity 发起）。 */
        val EXTRA_PICK_GOOD = OverlayEntryContract.EXTRA_PICK_GOOD

        internal const val PREFS = "pocket"

        /** 引导是否走完（走完之前每次打开 App 都回到引导页）。 */
        internal const val KEY_ONBOARD_DONE = "onboarding_done"

        /** ②「屏幕共享」是否已知晓（纯粹是让用户读完再往下走）。 */
        internal const val KEY_SHARE_ACK = "share_ack"

        /** 滑动测试：退到后台到注入手势之间的等待（等系统把前台还给游戏）。 */
        internal const val SWIPE_TEST_BACK_DELAY_MS = 700L

        /**
         * 深链白名单（纯函数，供单测，★ A29）：scheme=bettergi 且 host=p 且 path 精确为 /launch。
         * 清单 intent-filter 声明的就是这个字面量；外部传入的其他任何 data 都不放行。
         */
        fun isLaunchDeepLink(scheme: String?, host: String?, path: String?): Boolean =
            scheme == "bettergi" && host == "p" && path == "/launch"
    }

    internal val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    /** 输入文件（GOOD / 配装计划）选择器——SAF 只能由 Activity 发起，所以悬浮窗只能"拉起本页再选"。 */
    internal val goodImportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            NoticeCenter.error("读取失败")
            return@registerForActivityResult
        }
        // ★ 2026-10-03：不能用 `uri.lastPathSegment` 当显示名 —— SAF 经 MediaStore 选中的文件，
        //   它的最后一段是 `msf:1000000475` 这种内部 id（实测「执行输入」就显示成它），
        //   用户看不出自己选了哪份。要查 OpenableColumns.DISPLAY_NAME，查不到才回退。
        val name = displayOf(uri) ?: (uri.lastPathSegment?.substringAfterLast('/') ?: "input.json")
        val (ok, msg) = com.bettergi.pocket.scan.GoodRepository.save(this, name, text)
        NoticeCenter.post(if (ok) NoticeCenter.Level.INFO else NoticeCenter.Level.ERROR, msg)
        // ★ 2026-10-03（D7）：写成功必须立刻重绘。上面那条 Notice 只进横幅，
        //   「执行输入」那格读的是 GoodRepository.state() —— 不重绘就还显示导入前那份，
        //   实测用户视角是"导入没生效"（要退出页面再进来才看到新名字）。
        //   与脚本导入 [importLauncher] 的 `if (ok) renderAll()` 同口径。
        if (ok) renderGoodSection()
    }

    /** 从 SAF URI 问出真实文件名（#205 起与透明中转页共用 [GoodRepository.displayName] 一份实现）。 */
    private fun displayOf(uri: android.net.Uri): String? = GoodRepository.displayName(this, uri)

    /** 引导屏已展示（onResume 用它区分两屏的刷新路径）。 */
    internal var onboardingShown = false

    private var pendingFromOverlay = false
    internal var pendingPickGood = false

    /** P3：管理器界面已展示 ⇒ onResume 不得再用旧的「悬浮窗授权」分支覆盖它。 */
    internal var managerShown = false
    private var pendingCaptureRequest = false
    private var pendingVpnRequest = false
    private var pendingShareFile: String? = null

    /**
     * 管理器页订阅主进程运行时状态用的作用域（抓包开关读 `CaptureSession.ui`）。
     * 本页与 CaptureSession 同进程，直读即可；随 Activity 销毁收掉，不留收集协程。
     */
    internal val pageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 抓包开关的订阅任务：管理器页可被反复打开，重绑前先收掉上一个，避免多个收集协程并存。 */
    internal var captureTestJob: Job? = null

    /** #203：管理器页订阅设置变化（扫描起止要当场改「导出 JSON」的可用性），随 Activity 注销。 */
    internal var scanStateWatcher: ((com.bettergi.pocket.settings.TriggerSettings) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        // 1) 从悬浮窗进来（长按日志按钮 / 脚本的「导入」动作）⇒ 一律进管理器
        if (pendingFromOverlay) {
            pendingFromOverlay = false
            showScriptManager()
            return
        }
        // 2) 引导没走完，或无障碍没开 ⇒ 回引导页。
        //    ⚠️ 这一条修的是「打开 App 一闪就没了」：悬浮窗只活在无障碍服务里，
        //    无障碍没开时点开本 App 什么都看不到，原先也没有任何指引。
        if (needsOnboarding()) {
            showOnboarding()
            return
        }
        continueLaunch()
    }

    /** 需要引导的条件：**引导未完成 或 无障碍未开**（后者保证关掉无障碍后再开 App 还能找到路）。 */
    private fun needsOnboarding(): Boolean =
        !prefs.getBoolean(KEY_ONBOARD_DONE, false) || !InputAccessibilityService.isConnected()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        // ⚠️ Activity 已在运行时（例如刚从权限页/管理器返回后再长按悬浮窗），`am start` 只走 onNewIntent：
        //    这里必须也能切到管理器，否则「长按浮窗设置」在二次进入时失效（曾实测 ✗）。
        if (pendingFromOverlay) {
            pendingFromOverlay = false
            showScriptManager()
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        // ★ A29 深链白名单：带 data 的进入（浏览器/第三方深链，本页 exported）只认精确的
        //   `bettergi://p/launch`；其余 data 一律整个忽略（extras 也不读），当普通打开处理。
        //   依据：host/path 是清单里声明过的唯一对外入口，白名单收紧不会断任何内部链路——
        //   内部 intent（服务/悬浮窗拉起）从不带 data。
        if (intent.data != null && !isLaunchDeepLink(
                intent.data?.scheme, intent.data?.host, intent.data?.path,
            )
        ) {
            return
        }
        if (intent.getBooleanExtra(EXTRA_FROM_OVERLAY, false)) pendingFromOverlay = true
        if (intent.getBooleanExtra(EXTRA_PICK_GOOD, false)) pendingPickGood = true
        // ★ A29：敏感中转（投影/VPN 授权、GOOD 分享）不再从 extras 读取 —— extras 任何第三方
        //   app 都能伪造。改经进程内 PendingHandoff 静态交接（见该类 KDoc），extras 的同名键
        //   （EXTRA_AUTO_REQUEST_CAPTURE / EXTRA_AUTO_REQUEST_VPN / EXTRA_AUTO_SHARE）保留
        //   常量仅为文档与编译兼容，已无读取方。
        when (val handoff = PendingHandoff.consume()) {
            is PendingHandoff.Request.Share -> pendingShareFile = handoff.fileName
            PendingHandoff.Request.RequestCapture -> pendingCaptureRequest = true
            PendingHandoff.Request.RequestVpn -> pendingVpnRequest = true
            null -> Unit
        }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        // 管理器在前台 ⇒ 提醒显示在本页横幅（而不是再弹一份到悬浮窗）
        com.bettergi.pocket.notice.NoticeRouter.attachManager(noticeSink)
        // ★ 分享命令必须在**两个界面分支之前**消费（2026-10-03 实测修 D1）。
        //   它和下面两个中转分支不一样：投影/VPN 授权是"起跑顺带要办的兜底"，被界面顶掉只是延后；
        //   而分享**就是用户点出来的那件事** —— 放在界面分支之后 ⇒ 悬浮窗点「导出」只把本页顶到前台，
        //   chooser 永远不起，pendingShareFile 永久留在字段里，用户看到的正是"点了没反应"。
        //   实测：引导没走完时点导出弹出来的是**引导页**；停在管理器页点导出焦点仍停在 MainActivity。
        if (consumeShareCommand()) return
        // ★ #276：投影/VPN 授权中继与分享**同性质**，也必须在界面分支之前消费。
        //   它是 A29 删掉 extras 通道后**唯一**的恢复路径，而 PendingHandoff 是**一次性**交接
        //   （consume 只读一次，onCreate 那条已在 handleIntent 里用掉）⇒ 一旦被下面的
        //   onboarding/manager 早返回吞掉就**永久丢失**——没有任何重放者。
        //   实测路径（真机/模拟器都成立）：用户停在管理器页点悬浮窗的投影授权 ⇒ 服务
        //   post(RequestCapture) 后以 FLAG_ACTIVITY_REORDER_TO_FRONT 把本页顶到前台 ⇒
        //   onNewIntent 记账 → onResume 被 managerShown 分支吞掉 ⇒ 标志留在字段里、
        //   界面一动不动，正是用户看到的"点了没反应"。
        //   旧注释说"被界面顶掉只是延后"与事实不符（没人重放 ⇒ 就是丢失），故改到此处置前。
        if (consumeRelayCommand()) return
        // 引导页：从系统设置返回时刷新（开了无障碍就该立刻打勾），不参与下面的自动起悬浮窗分支
        if (onboardingShown) {
            renderOnboarding()
            return
        }
        // 管理器界面优先，不能被下面**自动起悬浮窗**的分支顶掉（曾实测被覆盖 ⇒ 首启仍显示别的界面 ✗）
        if (managerShown) {
            // 从系统设置页回来 ⇒ 抓包权限小节要重新判一遍（其余界面不重绘，避免打断操作）
            renderCaptureSection()
            // 「扫描结果」也要刷：停在页面上时刚跑完一轮，不刷就还显示上一份（2026-10-03 实测显示 35h 前那份）
            renderGoodSection()
            return
        }
        // ⚠️ 2026-09-18：这里原先还有一个「显示在上层」授权分支 —— 悬浮窗搬到无障碍进程后
        //    （TYPE_ACCESSIBILITY_OVERLAY 零权限）已整体删除。
        // 走到这里：无分享、无授权中继、非引导页、非管理器页 ⇒ 正常路径：起悬浮窗并收掉本页。
        // （分享已在 [consumeShareCommand] 消费 ⇒ 此处不再有 pendingShareFile 分支。）
        launchOverlayAndExit()
    }

    /**
     * 消费"投影/VPN 授权"这条一次性中继（#276）。
     * 与分享同性质的界面层命令：进了界面分支就会被吞掉、且 [PendingHandoff] 一次性
     * ⇒ 必须最先消费，否则这次用户请求永久丢失。
     * @return true 表示本次 onResume 已由授权中继接管，调用方必须**直接 return**。
     */
    private fun consumeRelayCommand(): Boolean {
        when {
            // 前台内发起投影授权（fix53：悬浮球路径的兜底，activity 前台时启动合法）
            pendingCaptureRequest -> {
                pendingCaptureRequest = false
                android.util.Log.i("BetterGI.Main", "授权中继: 前台内补发起投影授权")
                startActivity(
                    Intent(this, CapturePermissionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return true
            }
            // 前台内发起 VPN 授权（抓包隧道；服务后台拉中转页被 ROM 拦时的同一条兜底）
            pendingVpnRequest -> {
                pendingVpnRequest = false
                android.util.Log.i("BetterGI.Main", "授权中继: 前台内补发起 VPN 授权")
                startActivity(
                    Intent(this, CaptureConsentActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return true
            }
        }
        return false
    }

    /**
     * 消费"分享 GOOD"这条一次性命令。
     * @return true 表示本次 onResume 已由分享接管，调用方必须**直接 return**
     *   —— 再往下走到 [launchOverlayAndExit] 会 finish 本页，把刚起的 chooser 一起带走。
     */
    private fun consumeShareCommand(): Boolean {
        val file = pendingShareFile ?: return false
        pendingShareFile = null
        shareGood(file)
        return true
    }

    override fun onDestroy() {
        pageScope.cancel()
        unwatchScanState()
        super.onDestroy()
    }

    /** GOOD 导出文件名的白名单：本页经 FileProvider 分享的**只有**扫描导出产物。 */
    private val goodExportNameRegex = Regex("good_export_\\d+\\.json")

    private fun shareGood(fileName: String) {
        // ★ A29：文件名白名单 —— EXTRA_AUTO_SHARE 时代这里曾把 filesDir 下任意文件名交给
        //   FileProvider（path="." 暴露整个 filesDir）。现在只认扫描导出产物的命名形态，
        //   其它一律拒绝（防伪造入口即使存在也分享不出私有文件）。
        if (!goodExportNameRegex.matches(fileName)) {
            NoticeCenter.error("拒绝分享非导出文件：$fileName")
            launchOverlayAndExit()
            return
        }
        val file = java.io.File(filesDir, fileName)
        if (!file.exists()) {
            NoticeCenter.error("GOOD 文件不存在：$fileName")
            launchOverlayAndExit()
            return
        }
        // ★ A29：FileProvider 收窄 —— 导出原件仍由 GoodExporter 落在 filesDir 根（该落点在
        //   scan/ 包，本工单不动），但**分享的是快照**：先拷进 filesDir/good_exports/ 子目录，
        //   再经 FileProvider 分享快照。file_paths.xml 因此可以只登记 good_exports/ 一个子目录，
        //   不再 `<files-path path="." />` 暴露整个 filesDir（脚本库、设置、日志等私有文件出局）。
        val exportDir = java.io.File(filesDir, GOOD_EXPORT_SUBDIR)
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            NoticeCenter.error("无法创建导出目录")
            launchOverlayAndExit()
            return
        }
        val snapshot = java.io.File(exportDir, fileName)
        val copied = runCatching {
            file.copyTo(snapshot, overwrite = true)
        }.onFailure {
            NoticeCenter.error("导出快照失败：${it.message}")
        }.isSuccess
        if (!copied) {
            launchOverlayAndExit()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", snapshot)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // ★ 2026-10-03（D7②）：没有可接收 application/json 的目标时**不要**起 chooser ——
        //   BlueStacks 实测面板内容是「没有应用可执行此操作。」，用户对着一个空面板什么也拿不到，
        //   而文件其实已经在设备上。改成直接告诉他文件在哪（零权限路径，不动 MediaStore）。
        val targets = runCatching {
            packageManager.queryIntentActivities(share, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrDefault(emptyList())
        if (targets.isEmpty()) {
            NoticeCenter.info("没有可接收 JSON 的应用；导出文件在 $packageName 的 files/${snapshot.name}")
            launchOverlayAndExit()
            return
        }
        startActivity(Intent.createChooser(share, "分享 GOOD 导出"))
        // 分享面板退出后交棒悬浮窗（onResume pending 已清 → launchOverlayAndExit）
    }

    private fun continueLaunch() {
        if (pendingCaptureRequest || pendingShareFile != null) return // 有中转任务，onResume 处理
        launchOverlayAndExit()
    }

    // ---- P3 脚本管理器：列表 + 开关（本地导入，不联网）----

    // ---- 提醒横幅（NoticeCenter 的管理器展位）----

    private val noticeSink = object : com.bettergi.pocket.notice.NoticeCenter.Sink {
        override fun show(notice: com.bettergi.pocket.notice.NoticeCenter.Notice) {
            val banner = findViewById<TextView>(R.id.notice_banner) ?: return
            val (bg, fg) = when (notice.level) {
                com.bettergi.pocket.notice.NoticeCenter.Level.ERROR ->
                    R.color.pocket_danger to R.color.pocket_text
                com.bettergi.pocket.notice.NoticeCenter.Level.WARN ->
                    R.color.pocket_warn to R.color.pocket_text
                else ->
                    R.color.pocket_accent to R.color.pocket_text
            }
            banner.text = notice.text
            banner.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this@MainActivity, bg))
            banner.setTextColor(androidx.core.content.ContextCompat.getColor(this@MainActivity, fg))
            banner.visibility = android.view.View.VISIBLE
            banner.removeCallbacks(hideNotice)
            banner.postDelayed(hideNotice, if (notice.level == com.bettergi.pocket.notice.NoticeCenter.Level.INFO) 3_000L else 6_000L)
        }
    }

    private val hideNotice = Runnable { findViewById<TextView>(R.id.notice_banner)?.visibility = android.view.View.GONE }

    internal fun launchOverlayAndExit() {
        if (isFinishing) return
        val intent = Intent(this, TriggerForegroundService::class.java).apply {
            action = TriggerForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        finish()
    }

    override fun onPause() {
        super.onPause()
        com.bettergi.pocket.notice.NoticeRouter.detachManager(noticeSink)
    }
}

/** 3.6 抽屏辅助：扩展函数里没有 `this@MainActivity` 标签，用本函数取回 Activity 实例。 */
internal fun MainActivity.activity(): MainActivity = this
