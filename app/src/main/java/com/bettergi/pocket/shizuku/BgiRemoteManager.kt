package com.bettergi.pocket.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import rikka.shizuku.Shizuku

/**
 * ★ #310/M1b 后台模式（virtualdisplay 分支）：BgiRemoteService 的**绑定生命周期管理器**。
 *
 * 职责就三件：① 前置校验（[ShizukuState] 四态必须 READY）→ ② `Shizuku.bindUserService`
 * 拉起提权服务并维护连接状态 → ③ 对上层暴露状态监听与 [service] 访问器。
 * **不做自动重连**（进程被杀/断开后的重拉由上层决策；M4 的监视区 UI 决定何时重按）。
 *
 * ## 官方依据（Shizuku-API README「UserService」+ demo DemoActivity.java）
 * - `bindUserService(args, conn)`：args 像 Intent 决定拉起哪个类与选项；conn 只用
 *   onServiceConnected / onServiceDisconnected 两个回调（Shizuku 内部在主线程分发，
 *   见 ShizukuServiceConnection 的 MAIN_HANDLER）。
 * - `UserServiceArgs`：**tag 必须显式设置** —— README 明说 tag 缺省用类名，而类名
 *   "is unstable after ProGuard/R8"；tag 同时是 server 端 service record 的键，
 *   version 变更时 server 按 tag 找到旧 record → 触发旧进程 destroy() → 拉新进程。
 * - `version`：与 [BgiRemoteService.SERVICE_VERSION] 同步。**接口演进（M2 加
 *   canCreateDisplay/createVirtualDisplay、M3 加注入方法）= AIDL 尾部追加方法 +
 *   两处 version 同步 ++**，server 自动完成旧进程换新进程，app 侧无感。
 * - `unbindUserService(args, conn, remove)`：**remove=true 才会真杀服务进程**
 *   （server 调 record.destroy() → transact 16777115 → 服务端 System.exit()）；
 *   remove=false 只摘 app 侧回调，shell/root 进程会泄漏在后台 —— 所以 [stop] 用 true。
 *
 * ## 状态机（[RemoteServiceState]）
 * ```
 * STOPPED ──start() 且 READY──> CONNECTING ──connected + ping 通──> CONNECTED
 *    ↑                              │                                 │
 *    │                              └──断链（died/断开/ping 败）───────┘──> LOST
 *    └──── stop() / 前置校验失败 / bind 调用失败 <──────────────────────────┘
 * ```
 * - LOST 语义 = 「链路曾经建立（或正在建立）后断开」；CONNECTING 途中连接死亡也报
 *   LOST —— 若报 STOPPED 会把「服务进程挂了」伪装成「从没绑过」，M4 的 UI 无法区分
 *   「需要重启 Shizuku」还是「只需要重按开关」。
 * - start() 幂等：CONNECTING/CONNECTED 时直接拒绝；LOST/STOPPED 允许重新 bind。
 * - stop() 幂等：STOPPED 时 no-op。
 *
 * ## 可测性设计（M0 同款纪律）
 * Shizuku 调用全部收敛进 [Deps]（assess / bind / unbind / remoteService 四个注入点，
 * **注入面刻意不含 Shizuku 类型**——单测假实现全程不触碰 ComponentName/UserServiceArgs
 * 等 android 存根构造）；ping 校验跑在可注入的 ping 执行器上（单测换同步 executor）；
 * 状态转换本体抽成纯 JVM 的 [Machine]，单测不碰真 binder / Shizuku 静态面。
 *
 * ## 线程与锁纪律
 * 回调来自主线程（Shizuku 分发）与 ping 线程；状态与 binder 引用读写都在对象锁内。
 * **监听器一律在锁外触发**（[transitionLocked] 在锁内记下迁移结果，锁外统一分发），
 * 不放大持锁窗口；监听器若再回调本 object 的 synchronized 方法，同线程可重入不死锁。
 * 回调线程不定（主线程或 ping 线程），UI 侧自行 post 到主线程。
 */
object BgiRemoteManager {

    private const val TAG = "BetterGI.BgiRemote"

    // ── 状态 ────────────────────────────────────────────────────

    /**
     * 远端服务链路状态（app 进程视角）。
     * 与 M0 [ShizukuPhase] 分工：那边判「Shizuku 通道可用性」，这边判
     * 「提权服务进程是否连着」—— 两态机独立演进，UI 按 ShizukuPhase → RemoteServiceState
     * 的顺序消费（通道都不通时本状态无意义，恒 STOPPED）。
     */
    enum class RemoteServiceState {
        /** 未绑定（初始 / stop 后 / 前置校验失败 / bind 调用失败）。 */
        STOPPED,

        /** bindUserService 已发出，等 onServiceConnected。 */
        CONNECTING,

        /** 已连接（ping 握手异步进行，失败会在监听里看到 LOST）。 */
        CONNECTED,

        /** 链路断开：曾 CONNECTED（或 CONNECTING 途中）进程被杀 / ping 握手失败。 */
        LOST,
    }

    // ── 纯函数规则（单测直接覆盖）─────────────────────────────

    /**
     * ping 握手规则：pong 必须严格等于 `token + 1`（服务端实现 [BgiRemoteService.ping]）。
     * 防「onServiceConnected 已回调但远端半死」的假 binder —— 真活着的进程才能把
     * token 原样加一送回来。ping 抛异常时调用方以 `pong = null` 到这里，判无效。
     */
    fun isPongValid(token: Long, pong: Long?): Boolean = pong != null && pong == token + 1

    // ── 可注入面 ────────────────────────────────────────────────

    /**
     * Shizuku / binder 的可注入面（单测假实现；生产 [PROD_DEPS] 真调静态 API）。
     * 与 M0 [ShizukuApi] 同款纪律：生产实现必须 runCatching 兜底，**不许把异常抛进
     * 状态机** —— binder 未连接时 Shizuku 静态方法抛 IllegalStateException 是常态。
     *
     * 刻意**不含 assess(Context)**：前置校验作为 `() -> ShizukuStatus` lambda 注入
     * [start]（M0 的 `assess(api, isInstalled)` 同款先例），注入面便完全不出现
     * Context —— 单测假实现不触碰 android 存根成员，纯 JVM 可跑。
     */
    interface Deps {
        /** 拉起 UserService；false = 调用失败（binder 未连接 / server 拒绝）。 */
        fun bind(conn: ServiceConnection): Boolean

        /** 解绑；remove=true 时 server 顺带触发远端 destroy()（杀进程）。 */
        fun unbind(conn: ServiceConnection, remove: Boolean): Boolean

        /**
         * binder → AIDL 接口。binder 为 null 或包装/死 binder 等异常一律返回 null
         * （调用方按断链处理）。
         */
        fun remoteService(binder: IBinder?): IBgiRemoteService?
    }

    /**
     * 生产 Deps：直接调 `rikka.shizuku.Shizuku`。runCatching 纪律在这里落实 ——
     * binder 未连接时 `Shizuku.bindUserService` 抛 IllegalStateException 属常态，
     * 折叠成 false / null 交给状态机走 LOST/STOPPED，绝不外抛。
     */
    private val PROD_DEPS: Deps = object : Deps {
        override fun bind(conn: ServiceConnection): Boolean =
            runCatching { Shizuku.bindUserService(ARGS, conn) }.isSuccess

        override fun unbind(conn: ServiceConnection, remove: Boolean): Boolean =
            runCatching { Shizuku.unbindUserService(ARGS, conn, remove) }.isSuccess

        override fun remoteService(binder: IBinder?): IBgiRemoteService? =
            runCatching { IBgiRemoteService.Stub.asInterface(binder) }.getOrNull()
    }

    /** 单测注入点（生产代码勿碰）：Deps 替身。 */
    internal var testDeps: Deps? = null

    /** 单测注入点（生产代码勿碰）：ping 执行器（首次发 ping 时取值，须先设再 start）。 */
    internal var testPingExecutor: Executor? = null

    /**
     * 单测隔离口（生产代码勿碰）：清空单例全部运行时状态（状态机、连接、binder、
     * 监听、迁移事件缓冲）。BgiRemoteManager 是 object，不 reset 则用例间互相污染。
     */
    internal fun resetForTest() {
        synchronized(lock) {
            machine.resetForTest()
            pendingTransition = null
            conn = null
            remote = null
        }
        stateListeners.clear()
        errorListeners.clear()
    }

    /**
     * UserService 绑定参数（README/demo 依据见类 KDoc）：tag 显式固定（R8 下类名
     * 不可靠）、独立进程后缀 `bgi_remote`、debuggable=false（提权进程不进 JDWP）、
     * version 与 [BgiRemoteService.SERVICE_VERSION] 锁死 —— **服务实现变更时
     * BgiRemoteService.SERVICE_VERSION 与此处 `.version(...)` 两处同步 ++**。
     */
    private val ARGS: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName("com.bettergi.pocket", BgiRemoteService::class.java.name),
        )
            .tag("bgi_remote")
            .processNameSuffix("bgi_remote")
            .version(BgiRemoteService.SERVICE_VERSION)
            .debuggable(false)
    }

    // ── 状态机本体（纯 JVM，可独立单测）───────────────────────

    /**
     * 状态转换核心：只认事件不认 Shizuku（事件由 [BgiRemoteManager] 从回调翻译来）。
     * 合法迁移见 [BgiRemoteManager] 类 KDoc 图；非法事件按「保持原态」处理（幂等，不炸）。
     * 转换发生即经 [onTransition] 通知一次（调用方保证锁外分发监听）。
     */
    internal class Machine(private val onTransition: (RemoteServiceState) -> Unit) {
        var state: RemoteServiceState = RemoteServiceState.STOPPED
            private set

        /** start() 发起绑定：STOPPED/LOST → CONNECTING；CONNECTING/CONNECTED 拒绝（幂等）。 */
        fun onBindRequested(): Boolean {
            if (state == RemoteServiceState.CONNECTING || state == RemoteServiceState.CONNECTED) {
                return false
            }
            transition(RemoteServiceState.CONNECTING)
            return true
        }

        /**
         * onServiceConnected 到达：CONNECTING → CONNECTED 并返回 true；
         * 其余态（stop 后迟到的回调等）忽略并返回 false —— 调用方据此决定
         * 是否持有 binder 引用。
         */
        fun onLinkUp(): Boolean {
            if (state == RemoteServiceState.CONNECTING) {
                transition(RemoteServiceState.CONNECTED)
                return true
            }
            return false
        }

        /**
         * 链路断开（onServiceDisconnected / ping 失败 / binder 转换失败）：
         * CONNECTED 或 CONNECTING → LOST。LOST 再断仍是 LOST；STOPPED 收到断开
         * 事件（stop 后迟到回调）保持 STOPPED。
         */
        fun onLinkDown() {
            if (state == RemoteServiceState.CONNECTED || state == RemoteServiceState.CONNECTING) {
                transition(RemoteServiceState.LOST)
            }
        }

        /** stop() / 前置校验失败 / bind 失败：任何态 → STOPPED。 */
        fun onStop() {
            if (state != RemoteServiceState.STOPPED) transition(RemoteServiceState.STOPPED)
        }

        /** 测试专用：静默归零（不发迁移事件），隔离单例状态用。生产代码勿碰。 */
        internal fun resetForTest() {
            state = RemoteServiceState.STOPPED
        }

        private fun transition(next: RemoteServiceState) {
            if (state == next) return
            Log.i(TAG, "RemoteServiceState: $state -> $next")
            state = next
            onTransition(next)
        }
    }

    // ── 运行时状态 ──────────────────────────────────────────────

    private val lock = Any()
    private val stateListeners = CopyOnWriteArrayList<(RemoteServiceState) -> Unit>()
    private val errorListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val tokenSeq = AtomicLong(System.currentTimeMillis())
    private val pingExecutor: Executor by lazy {
        testPingExecutor
            ?: Executors.newSingleThreadExecutor { r -> Thread(r, "bgi-remote-ping") }
    }

    // 迁移事件经 Machine 回调落到 pendingTransition（锁内写），transitionLocked
    // 在出锁前取走并置空 —— 保证「一次迁移恰好分发一次」，start/stop/回调三条
    // 入口共用同一条分发路径。
    private var pendingTransition: RemoteServiceState? = null
    private val machine = Machine { next -> pendingTransition = next }

    private var conn: ServiceConnection? = null
    private var remote: IBgiRemoteService? = null

    /** 当前链路状态（快照读）。 */
    val state: RemoteServiceState get() = synchronized(lock) { machine.state }

    /** 当前远端接口；null = 未连接（CONNECTING/LOST/STOPPED 都可能为 null）。 */
    fun service(): IBgiRemoteService? = synchronized(lock) { remote }

    // ── 监听 ────────────────────────────────────────────────────

    /**
     * 注册状态监听，**注册时立刻回放当前态**（sticky，M4 UI 进页面即得初值，不用猜）。
     * 回调线程不定（主线程或 ping 线程），UI 侧自行 post 到主线程。
     */
    fun addStateListener(listener: (RemoteServiceState) -> Unit) {
        stateListeners += listener
        listener(state)
    }

    fun removeStateListener(listener: (RemoteServiceState) -> Unit) {
        stateListeners -= listener
    }

    /**
     * 错误回调（断链原因 / 前置校验失败原因等，人话给 UI 与日志）。与状态监听正交：
     * 同一事件先发状态再发错误，UI 只想看状态时可以不注册错误监听。
     */
    fun addErrorListener(listener: (String) -> Unit) {
        errorListeners += listener
    }

    fun removeErrorListener(listener: (String) -> Unit) {
        errorListeners -= listener
    }

    private fun dispatchState(next: RemoteServiceState) {
        for (l in stateListeners) runCatching { l(next) }
    }

    private fun dispatchError(message: String) {
        Log.w(TAG, message)
        for (l in errorListeners) runCatching { l(message) }
    }

    /** 锁内推进状态机（记下迁移结果），锁外统一分发监听。 */
    private fun transitionLocked(block: () -> Unit) {
        val fired: RemoteServiceState?
        synchronized(lock) {
            block()
            fired = pendingTransition
            pendingTransition = null
        }
        if (fired != null) dispatchState(fired)
    }

    // ── 生命周期 ────────────────────────────────────────────────

    /**
     * 绑定提权服务（生产入口）：前置校验直通 M0 [ShizukuState.assess]（那边只用
     * packageManager，任意 Context 均可），其余见核心重载 [start]。
     */
    fun start(context: Context): Boolean = start { ShizukuState.assess(context) }

    /**
     * 绑定提权服务（核心，前置校验注入 —— M0 `assess(api, isInstalled)` 同款先例，
     * 单测传假值即可，注入面不出现 Context）。前置校验不过（[ShizukuPhase] 非
     * READY）→ 状态归 STOPPED + 错误回调，**绝不尝试 bind**（对未授权的 server
     * 发 bind 必抛，属可预期失败）。
     *
     * 幂等：CONNECTING/CONNECTED 时直接返回 false 不重复 bind。
     *
     * @param assess 前置校验注入点（生产入口传 M0 四态机；假实现可抛异常，
     *   这里 runCatching 兜底按 NOT_RUNNING 处理）
     * @return true = 已发起绑定（状态进入 CONNECTING）；false = 幂等跳过或校验/调用失败
     */
    fun start(assess: () -> ShizukuStatus): Boolean {
        val deps = testDeps ?: PROD_DEPS

        synchronized(lock) {
            val s = machine.state
            if (s == RemoteServiceState.CONNECTING || s == RemoteServiceState.CONNECTED) {
                return false
            }
        }

        // 前置校验：Shizuku 四态必须 READY（M0 的判定复用，不在这重写一遍）
        val status = runCatching(assess).getOrElse {
            ShizukuStatus(ShizukuPhase.NOT_RUNNING, serverUid = null)
        }
        if (status.phase != ShizukuPhase.READY) {
            transitionLocked { machine.onStop() }
            dispatchError("远程服务前置校验未过：Shizuku 状态 ${status.phase}（READY 才能绑定）")
            return false
        }

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                handleConnected(binder, deps)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                handleDisconnected("onServiceDisconnected（服务进程断开）")
            }
        }

        var requested = false
        var bindFailed = false
        transitionLocked {
            requested = machine.onBindRequested()
            if (requested) {
                conn = connection
                if (!deps.bind(connection)) {
                    conn = null
                    machine.onStop()
                    bindFailed = true
                }
            }
        }
        if (!requested) return false // 并发 start：别人已经绑了
        if (bindFailed) {
            dispatchError("bindUserService 调用失败（binder 未连接或 server 拒绝）")
            return false
        }
        Log.i(TAG, "bindUserService 已发起（serviceVersion=${BgiRemoteService.SERVICE_VERSION}）")
        return true
    }

    /**
     * 解绑并请求 server 杀掉服务进程（remove=true → record.destroy() → transact
     * 16777115 → 服务端 System.exit，见 [BgiRemoteService.destroy]）。幂等：
     * 已 STOPPED 且无连接（重复 stop / 从未 start）时 no-op。
     */
    fun stop() {
        val deps = testDeps ?: PROD_DEPS
        var toUnbind: ServiceConnection? = null
        transitionLocked {
            if (machine.state != RemoteServiceState.STOPPED || conn != null) {
                toUnbind = conn
                conn = null
                remote = null
                machine.onStop()
            }
        }
        // remove=true：真杀进程（README：unbind 本身不杀，remove 才触发 destroy）
        if (toUnbind != null) deps.unbind(toUnbind!!, true)
        Log.i(TAG, "stop()：已解绑（remove=true，远端 destroy 已请求）")
    }

    // ── 连接事件处理（回调线程）───────────────────────────────

    /**
     * onServiceConnected：记 binder → CONNECTING→CONNECTED → 异步 ping 握手。
     * 顺序说明：先给 CONNECTED 再握手（UI 立即拿到「连上了」），握手失败再降 LOST ——
     * 若先握手再给态，主线程回调要等一个 binder 往返，且「半死 binder」语义会混进
     * CONNECTING 阶段。
     */
    private fun handleConnected(binder: IBinder?, deps: Deps) {
        val svc = deps.remoteService(binder)
        var handshake: Pair<Long, IBgiRemoteService>? = null
        transitionLocked {
            if (svc == null) {
                machine.onLinkDown() // CONNECTING → LOST：binder 转换失败按断链处理
            } else {
                // 只有真的 CONNECTING→CONNECTED（stop 后迟到回调会被拒）才持有引用
                if (machine.onLinkUp()) {
                    remote = svc
                    handshake = tokenSeq.incrementAndGet() to svc
                }
            }
        }
        if (svc == null) {
            dispatchError("onServiceConnected 但 binder 转 IBgiRemoteService 失败（半死/被杀）")
            return
        }
        // 迟到回调（stop 后才到，onLinkUp 被拒）：无握手可做，直接吞掉
        val (token, service) = handshake ?: return
        pingExecutor.execute {
            val pong = runCatching { service.ping(token) }.getOrNull()
            if (!isPongValid(token, pong)) {
                var demoted = false
                transitionLocked {
                    // 期间可能已 stop 或重连（remote 换了新实例），只降级仍持有的旧连接
                    if (remote === service) {
                        remote = null
                        machine.onLinkDown()
                        demoted = true
                    }
                }
                if (demoted) {
                    dispatchError("ping 握手失败（pong 不匹配/抛异常），判 LOST")
                }
            } else {
                Log.i(TAG, "ping 握手通过（token=$token, pong=$pong）")
            }
        }
    }

    private fun handleDisconnected(reason: String) {
        transitionLocked {
            remote = null
            machine.onLinkDown()
        }
        dispatchError(reason)
    }
}
