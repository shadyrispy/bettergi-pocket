package com.bettergi.pocket.shizuku

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.Keep
import com.bettergi.pocket.genshin.GenshinPackages

/**
 * ★ #310/M1b 后台模式（virtualdisplay 分支）：跑在 Shizuku UserService 进程里的**提权服务本体**。
 *
 * Shizuku server 以 shell(2000)/root(0) uid 把本类实例化在独立进程
 * （进程名后缀 `bgi_remote`，见 [BgiRemoteManager.ARGS]），app 进程经
 * `IBgiRemoteService` binder 代理调用它。M1b 只做骨架（存活/版本/能力探测），
 * 虚拟显示器与输入注入由后续里程碑在同接口尾部追加（version++ 隔离新旧进程）。
 *
 * ## 构造约定（Shizuku-API README + 官方 demo UserService.java 逐字依据）
 * - v13 起 server **优先尝试带 `Context` 参数的构造函数**（`createPackageContextAsUser`
 *   产出的 context）；旧版 Shizuku 走无参构造。两个构造都必须存在：无参是兜底，
 *   带 Context 的是 v13 主路径。
 * - 带 Context 的构造**必须 `@Keep`**（demo 原注释："This method need to be
 *   annotated with Keep to prevent ProGuard from removing it"）—— server 按
 *   签名反射找构造函数，R8 移除/重签名即崩。项目 proguard-rules.pro 虽有
 *   三方库 keep 盘点，但本类的 keep 需求由 @Keep 注解直接覆盖。
 *
 * ## 服务进程环境红线（README："not a valid Android application process"）
 * 即便拿到 `Context`，`Context#registerReceiver`、`Context#getContentResolver`
 * 等 app 进程专属 API **不可用**。本类纪律：
 * - 不碰 registerReceiver / getContentResolver / startActivity 等一切 app 框架面；
 * - context 只用于记录环境信息（uid/pid/包名日志），不调它的业务方法；
 * - 用到的系统调用只限 `Build.*`、`Os.*` 这类纯内核面（M1b 只用 Build）。
 *   M2 的 DisplayManager / M3 的 InputManager 直接经 `getSystemService`
 *   拿 binder（UserService 进程无 non-SDK 限制）——那是后续里程碑的坑，此处不预埋。
 *
 * ## destroy 语义
 * AIDL 里 `destroy() = 16777114`（server 实际 transact 16777115，见 AIDL KDoc）。
 * 两个触发源：① UserServiceArgs.version 变更时 server 拉新进程并销毁旧进程；
 * ② `unbindUserService(args, conn, remove=true)`。本实现只打日志 + `System.exit(0)`
 * —— M1b 无任何需清理的资源（显示器/注入句柄都是后续里程碑的事）。
 */
class BgiRemoteService : IBgiRemoteService.Stub {

    /**
     * v13 主路径构造：Shizuku server 优先反射调用它（@Keep 防移除，见类 KDoc）。
     * 红线：这个 context 是「借来的环境凭证」，不是 app 进程的合法 context ——
     * 只读基本信息，绝不 registerReceiver / getContentResolver。
     */
    @Keep
    constructor(context: Context) {
        this.savedContext = context
        this.contextInfo = "uid=${tryCatching { android.os.Process.myUid() }} " +
            "pkg=${tryCatching { context.packageName }}"
        Log.i(TAG, "构造（v13 Context 路径）：$contextInfo")
    }

    /** 兜底构造：旧版 Shizuku（< v13）走这里。无 context 可记，照样存活。 */
    constructor() {
        this.contextInfo = "no-context"
        Log.i(TAG, "构造（无参兜底路径，Shizuku < v13）")
    }

    private val contextInfo: String

    override fun ping(token: Long): Long {
        Log.d(TAG, "ping(token=$token) -> ${token + 1}")
        return token + 1
    }

    override fun serviceVersion(): String {
        return SERVICE_VERSION.toString()
    }

    override fun apiLevel(): Int {
        return Build.VERSION.SDK_INT
    }

    // ── ★ #310/M3：虚拟显示器与输入注入（version 2）──
    // 懒初始化：M1b 骨架首版不碰显示栈；首个显示方法调用时才实例化 M2 组件。
    // ⚠️ 服务进程内 VdInput 反射/DisplayManager 反射可用（无 non-SDK 限制——
    //    UserService 进程不在 hidden API enforcement 名单，见 M2 报告）。

    @Volatile
    private var vdManager: com.bettergi.pocket.vd.VirtualDisplayManager? = null

    private fun vd(): com.bettergi.pocket.vd.VirtualDisplayManager {
        // BgiRemoteService 的 context 构造路径才有 context；无参兜底（旧 Shizuku）下
        // VD 能力不可用（getSystemService 需要 context）⇒ 抛错让调用方显式看到。
        val ctx = requireContext()
        return vdManager ?: com.bettergi.pocket.vd.VirtualDisplayManager(ctx).also {
            vdManager = it
            Log.i(TAG, "VirtualDisplayManager 初始化（服务进程内）")
        }
    }

    /** M3 createDisplay 建出的帧源（服务进程内持有：JPEG 预览 / 黑帧 Guard / fps 估计共用）。 */
    @Volatile
    var vdFrameSource: com.bettergi.pocket.vd.VirtualDisplayFrameSource? = null
        private set

    /** M5 黑帧 Guard（VD 停更自守；createDisplay 时起，destroy 时停）。 */
    @Volatile
    private var frameGuard: com.bettergi.pocket.vd.VdBlackFrameGuard? = null

    override fun createDisplay(): Int {
        val fs = com.bettergi.pocket.vd.VirtualDisplayFrameSource(
            displayId = -1, width = 1280, height = 720,
        ).also { it.start() }
        vdFrameSource = fs
        // frameSourceGenerationProbe：fps 估计器经它读代数（KeepAlive 轮询 → estimator）
        val id = vd().create(1280, 720, 160, fs.surface()) { fs.frameGeneration() }
        // M5：VD 建成后起黑帧 Guard（服务进程内自守——MAA StaleFrameGuard 机制）。
        // create 失败（id<0）也要起 Guard：它内部靠 isAppOnDisplay 判不出即保守不动作，
        // 等 repin/重建把显示救回来后自然恢复观测。
        if (frameGuard == null) {
            frameGuard = com.bettergi.pocket.vd.VdBlackFrameGuard(
                frameSourceProvider = { vdFrameSource },
                isAppOnDisplay = {
                    GenshinPackages.CANDIDATES.any { pkg -> vd().isAppOnDisplay(pkg) == true }
                },
                isGameAlive = { gameAliveByPidof() },
                publishBlackFrame = { vdFrameSource?.publishBlackFrame() ?: false },
            ).also {
                it.start()
                Log.i(TAG, "VdBlackFrameGuard 已启动（服务进程内自守）")
            }
        }
        Log.i(TAG, "createDisplay -> $id（帧源已起，surface 已接）")
        return id
    }

    override fun startAppOnDisplay(packageName: String, displayId: Int): Boolean {
        val ok = runCatching { vd().startApp(packageName, displayId) }
            .onFailure { Log.w(TAG, "startAppOnDisplay failed: ${it.message}") }
            .getOrDefault(false)
        return ok
    }

    override fun repinToDisplay(packageName: String, displayId: Int): Boolean {
        // M2 管理器是单 VD 持有者（displayId 在 create 时记忆）；displayId 参数只做
        // 与管理器状态的一致性校验（调用方传错 = 状态错乱，拒绝而不是静默拉错屏）。
        val ok = runCatching {
            check(vd().displayId == displayId) {
                "repin displayId=$displayId != 管理器持有 ${vd().displayId}"
            }
            vd().repin(packageName)
        }.onFailure { Log.w(TAG, "repinToDisplay failed: ${it.message}") }
            .getOrDefault(false)
        return ok
    }

    override fun tapOnDisplay(displayId: Int, x: Int, y: Int): Boolean {
        return runCatching {
            com.bettergi.pocket.vd.VdInput.init()
            val gw = com.bettergi.pocket.vd.VdInjectActionGateway(displayId)
            gw.tap(x, y)
        }.onFailure { Log.w(TAG, "tapOnDisplay failed: ${it.message}") }
            .getOrDefault(false)
    }

    override fun swipeOnDisplay(displayId: Int, fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean {
        return runCatching {
            com.bettergi.pocket.vd.VdInput.init()
            val gw = com.bettergi.pocket.vd.VdInjectActionGateway(displayId)
            gw.swipe(fromX, fromY, toX, toY)
        }.onFailure { Log.w(TAG, "swipeOnDisplay failed: ${it.message}") }
            .getOrDefault(false)
    }

    // ── ★ #310/M5：帧流与监视（version 3）──────────────────────────

    /**
     * VD 最新帧 → JPEG（app 进程经 AIDL 取预览）。
     * 帧缓存为空 / VD 未建 ⇒ null（调用方按「预览不可用」处理）；压缩失败也 null。
     * bitmap 全部在调用栈内回收（createBitmap 出来的复制品，recycle 不影响帧源缓存）。
     */
    override fun capturePreviewJpeg(): ByteArray? {
        val src = vdFrameSource ?: return null
        return runCatching {
            val snap = src.latestRgbaSnapshot() ?: return@runCatching null
            val bmp = snapshotToBitmap(snap) ?: return@runCatching null
            try {
                val longEdge = maxOf(bmp.width, bmp.height)
                val scale = if (longEdge > PREVIEW_MAX_EDGE) {
                    PREVIEW_MAX_EDGE.toFloat() / longEdge
                } else 1f
                val out = if (scale < 1f) {
                    android.graphics.Bitmap.createScaledBitmap(
                        bmp,
                        (bmp.width * scale).toInt().coerceAtLeast(1),
                        (bmp.height * scale).toInt().coerceAtLeast(1),
                        true,
                    )
                } else bmp
                try {
                    val bos = java.io.ByteArrayOutputStream()
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, PREVIEW_JPEG_QUALITY, bos)
                    bos.toByteArray()
                } finally {
                    if (out !== bmp) out.recycle()
                }
            } finally {
                bmp.recycle()
            }
        }.onFailure { Log.w(TAG, "capturePreviewJpeg failed: ${it.message}") }
            .getOrNull()
    }

    /** 快照 → Bitmap（RGBA_8888 内存布局直拷，处理 rowStride > w*4 的行对齐填充）。 */
    private fun snapshotToBitmap(s: com.bettergi.pocket.vd.VirtualDisplayFrameSource.Snapshot): android.graphics.Bitmap? {
        if (s.rgba.size < s.rowStride * (s.height - 1) + s.width * 4) return null
        val bmp = android.graphics.Bitmap.createBitmap(s.width, s.height, android.graphics.Bitmap.Config.ARGB_8888)
        val row = java.nio.ByteBuffer.allocate(s.width * 4)
        val packed = java.nio.ByteBuffer.allocate(s.width * s.height * 4)
        for (y in 0 until s.height) {
            row.clear()
            row.put(s.rgba, y * s.rowStride, s.width * 4)
            row.rewind()
            packed.put(row)
        }
        packed.rewind()
        bmp.copyPixelsFromBuffer(packed)
        return bmp
    }

    override fun isGameAlive(): Boolean = gameAliveByPidof()

    override fun getGameFps(): Int {
        val e = vd().fpsEstimator ?: return -1
        val v = e.lastFps()
        return if (v < 0) -1 else v.toInt()
    }

    /**
     * `pidof com.miHoYo.Yuanshen`（服务进程 ProcessBuilder）——只认与包名同名的主进程
     * （pidof <pkg> 的输出天然不含 :pushcore 等以包名**开头**的子进程名？不——`pidof` 按
     * 进程名精确匹配，而 Android 子进程名是 `com.miHoYo.Yuanshen:pushcore`，与主进程名
     * 不相等，所以不会命中）。输出非空即活；异常/超时按保守 true（MAA"宁可漏报不误杀"）。
     */
    private fun gameAliveByPidof(): Boolean {
        return runCatching {
            val p = ProcessBuilder("pidof", GAME_ALIVE_PACKAGE)
                .redirectErrorStream(true)
                .start()
            val finished = p.waitFor(PIDOF_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                Log.w(TAG, "pidof 超时（${PIDOF_TIMEOUT_MS}ms）⇒ 按保守 true")
                p.destroy()
                return true
            }
            val out = p.inputStream.bufferedReader().readText().trim()
            out.isNotEmpty()
        }.getOrElse {
            Log.w(TAG, "pidof 执行失败（${it.message}）⇒ 按保守 true")
            true
        }
    }

    /**
     * 官方销毁约定：server 经 transact 16777115（AIDL 声明值 16777114）触发，
     * FLAG_ONEWAY —— 本方法跑在服务进程的 binder 线程，exit 后不再返回。
     * 清理逻辑随里程碑追加（M2 显示器、M3 注入器），**exit 必须是最后一行**。
     */
    // ── ★ #310/M6-audit：装配接线补口（version 4）──

    override fun createDisplayWithSurface(
        surface: android.view.Surface,
        width: Int,
        height: Int,
        dpi: Int,
    ): Int {
        return runCatching {
            vd().create(width, height, dpi, surface) { vdFrameSourceOrNull()?.frameGeneration() ?: 0L }
        }.onFailure { Log.w(TAG, "createDisplayWithSurface failed: ${it.message}") }
            .getOrDefault(-1)
    }

    override fun destroyDisplay() {
        runCatching {
            frameGuard?.stop()
            frameGuard = null
            vd().destroy()
        }.onFailure { Log.w(TAG, "destroyDisplay failed: ${it.message}") }
    }

    override fun topActivityOnDisplay(displayId: Int): Array<String>? {
        // 服务进程（shell uid）的 getRunningTasks 返回全量 task；反射 displayId 字段
        // （hidden，shell 可读）。VD 空返回空数组、判不出返回 null——调用方（VdStateProbe
        // 远端版）按"空=false / null=未知"折叠。
        return runCatching {
            val am = requireContext().getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as? android.app.ActivityManager ?: return null
            val tasks = am.getRunningTasks(100)
            val f = android.app.ActivityManager.RunningTaskInfo::class.java.getField("displayId")
            val onVd = tasks.firstOrNull { runCatching { f.getInt(it) }.getOrDefault(-1) == displayId }
                ?: return emptyArray()
            val top = onVd.topActivity
            if (top == null) emptyArray()
            else arrayOf(top.packageName ?: "", top.className ?: "")
        }.getOrElse {
            Log.w(TAG, "topActivityOnDisplay failed: ${it.message}")
            null
        }
    }

    override fun backOnDisplay(displayId: Int): Boolean {
        return runCatching {
            com.bettergi.pocket.vd.VdInput.init()
            val gw = com.bettergi.pocket.vd.VdInjectActionGateway(displayId)
            gw.back()
        }.onFailure { Log.w(TAG, "backOnDisplay failed: ${it.message}") }
            .getOrDefault(false)
    }

    private fun vdFrameSourceOrNull(): com.bettergi.pocket.vd.VirtualDisplayFrameSource? = vdFrameSource

    override fun destroy() {
        Log.i(TAG, "destroy()：server 触发销毁（version 更新或 unbind remove），exit")
        runCatching { frameGuard?.stop() }
        runCatching { vdFrameSource?.stop() }
        System.exit(0)
    }

    /** context 由 v13 构造保存（无参兜底时为 null ⇒ VD/注入方法显式失败）。 */
    private var savedContext: Context? = null
    private fun requireContext(): Context =
        savedContext ?: error("BgiRemoteService 走了无参兜底构造（Shizuku < v13）⇒ VD 能力不可用")

    /** 装饰性日志用的安全取值（服务进程初始化早期，任何取值都不许炸构造）。 */
    private inline fun <T> tryCatching(block: () -> T): String =
        runCatching(block).getOrNull()?.toString() ?: "?"

    companion object {
        private const val TAG = "BetterGI.BgiRemote"

        /** pidof 超时（ms）——超时按保守 true。 */
        private const val PIDOF_TIMEOUT_MS = 1_500L

        /** 活性检查的包名（M5 硬编码国服原神，与 M4 的 BG_GAME_PACKAGE 同源语义）。 */
        private const val GAME_ALIVE_PACKAGE = "com.miHoYo.Yuanshen"

        /** 预览 JPEG 长边上限（MAA 同款 1280；VD 本身就是 1280 宽，多数帧走"不缩放"路径）。 */
        private const val PREVIEW_MAX_EDGE = 1280

        /** 预览 JPEG 压缩质量（MAA 同款 70）。 */
        private const val PREVIEW_JPEG_QUALITY = 70

        /**
         * 服务构建版本：**每次改动服务实现（含 AIDL 接口加方法）必须 ++，
         * 并与 [BgiRemoteManager] 传入 UserServiceArgs.version 的值保持同步** ——
         * server 发现 version 不匹配会自动拉新进程、调旧进程的 destroy()。
         * M1b 骨架首版 = 1；M3（VD/注入）= 2；M5（帧流/监视）= 3。
         */
        const val SERVICE_VERSION: Int = 4
    }
}
