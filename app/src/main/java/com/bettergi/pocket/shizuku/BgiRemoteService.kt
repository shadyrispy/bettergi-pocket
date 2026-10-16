package com.bettergi.pocket.shizuku

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.Keep

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

    override fun createDisplay(): Int {
        val fs = com.bettergi.pocket.vd.VirtualDisplayFrameSource(
            displayId = -1, width = 1280, height = 720,
        ).also { it.start() }
        val id = vd().create(1280, 720, 160, fs.surface())
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

    /**
     * 官方销毁约定：server 经 transact 16777115（AIDL 声明值 16777114）触发，
     * FLAG_ONEWAY —— 本方法跑在服务进程的 binder 线程，exit 后不再返回。
     * 清理逻辑随里程碑追加（M2 显示器、M3 注入器），**exit 必须是最后一行**。
     */
    override fun destroy() {
        Log.i(TAG, "destroy()：server 触发销毁（version 更新或 unbind remove），exit")
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

        /**
         * 服务构建版本：**每次改动服务实现（含 AIDL 接口加方法）必须 ++，
         * 并与 [BgiRemoteManager] 传入 UserServiceArgs.version 的值保持同步** ——
         * server 发现 version 不匹配会自动拉新进程、调旧进程的 destroy()。
         * M1b 骨架首版 = 1。
         */
        const val SERVICE_VERSION: Int = 2
    }
}
