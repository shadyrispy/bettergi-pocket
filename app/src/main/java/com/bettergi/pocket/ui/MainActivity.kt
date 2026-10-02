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
import com.bettergi.pocket.dsl.ScriptIcons
import com.bettergi.pocket.dsl.ScriptStore
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

    /** 打开导入器（SAF，本地文件，不需要网络/存储权限）。 */
    internal val importLauncher = registerForActivityResult(
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
        val key = runCatching { org.json.JSONObject(text).optString("flow", "") }.getOrDefault("")
            .ifBlank { uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".json") ?: "imported" }
        val (ok, msg) = com.bettergi.pocket.dsl.ScriptStore.importFlow(this, key, text)
        NoticeCenter.post(if (ok) NoticeCenter.Level.INFO else NoticeCenter.Level.ERROR, msg)
        if (ok) renderAll()
    }

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
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "input.json"
        val (ok, msg) = com.bettergi.pocket.scan.GoodRepository.save(this, name, text)
        NoticeCenter.post(if (ok) NoticeCenter.Level.INFO else NoticeCenter.Level.ERROR, msg)
    }

    /** 引导屏已展示（onResume 用它区分两屏的刷新路径）。 */
    internal var onboardingShown = false

    private var pendingFromOverlay = false
    internal var pendingPickGood = false

    /** P3：管理器界面已展示 ⇒ onResume 不得再用旧的「悬浮窗授权」分支覆盖它。 */
    internal var managerShown = false
    private var pendingCaptureRequest = false
    private var pendingVpnRequest = false
    private var pendingShareFile: String? = null

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
        // 引导页：从系统设置返回时刷新（开了无障碍就该立刻打勾），不参与下面的中转分支
        if (onboardingShown) {
            renderOnboarding()
            return
        }
        // 管理器界面优先，不能被下面两个中转分支顶掉（曾实测被覆盖 ⇒ 首启仍显示别的界面 ✗）
        if (managerShown) {
            // 从系统设置页回来 ⇒ 抓包权限小节要重新判一遍（其余界面不重绘，避免打断操作）
            renderCaptureSection()
            return
        }
        // ⚠️ 2026-09-18：这里原先还有一个「显示在上层」授权分支 —— 悬浮窗搬到无障碍进程后
        //    （TYPE_ACCESSIBILITY_OVERLAY 零权限）已整体删除，只剩投影授权与分享两个中转。
        when {
            // 前台内发起投影授权（fix53：悬浮球路径的兜底，activity 前台时启动合法）
            pendingCaptureRequest -> {
                pendingCaptureRequest = false
                startActivity(
                    Intent(this, CapturePermissionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            // 前台内发起 VPN 授权（抓包隧道；服务后台拉中转页被 ROM 拦时的同一条兜底）
            pendingVpnRequest -> {
                pendingVpnRequest = false
                startActivity(
                    Intent(this, CaptureConsentActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            // 前台内起系统分享 chooser（service 后台起 chooser 需要 Activity 上下文，不可靠）
            else -> {
                val shareFile = pendingShareFile
                if (shareFile != null) {
                    pendingShareFile = null
                    shareGood(shareFile)
                } else {
                    launchOverlayAndExit()
                }
            }
        }
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
