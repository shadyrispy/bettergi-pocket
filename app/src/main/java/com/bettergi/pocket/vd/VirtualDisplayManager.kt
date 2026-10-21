package com.bettergi.pocket.vd

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface

/**
 * ★ #310/M2 后台模式：虚拟显示器管理器（显示核心，纯类库——不接 UI、不接引擎）。
 *
 * ## 能力边界（读代码前先读这段）
 * - **显示类操作不需要 root**：createVirtualDisplay（带特权 flag）、freezeRotation、
 *   setForcedDisplaySize、moveRootTaskToDisplay、userActivity 全部是 shell uid 即可
 *   调的系统接口——本类设计运行在 Shizuku UserService 进程（shell/root uid），
 *   Context 注入的是该进程的 Context（Shizuku v13 传入的 package context）。
 * - **输入注入才是另一回事**：Android 14+ 收紧了 adb-uid 的注入限制
 *   （`INJECT_EVENTS` 对 shell 不再无条件放行），那部分由 VdInput/M3 处理并另行
 *   验证。本类的 KDoc 只承诺"显示面"在 shell uid 下的可用性。
 * - app 进程（普通 uid）也能调 public `createVirtualDisplay`，但特权 flag 一律被
 *   丢弃（系统对 non-system uid 只认 PUBLIC/PRESENTATION/SECURE 等公开位）——
 *   所以本类有**双路径**：提权进程走 hidden 8 参重载 + 特权 flag（主路径），
 *   app 进程退化到 public 6 参（降级路径，打 W 说明 flag 未生效，仅用于开发期
 *   无 Shizuku 的冒烟）。
 *
 * ## 生命周期
 * `create(...)` → `isAppOnDisplay`/`startApp`/`repin`/`ensureAppOnDisplay` →
 * `destroy()`。一次 create 只支持一个 VirtualDisplay（M3 的帧源/注入器都以
 * [displayId] 为单值锚点）；重复 create 前必须先 destroy（内部有防护）。
 *
 * ## hidden API 反射点清单（全部 runCatching + 日志；P+ 限制经 HiddenApiBypass 豁免）
 * | 目标 | 方式 | 依据 |
 * |---|---|---|
 * | `DisplayManager.createVirtualDisplay(name,w,h,dpi,surface,flags,callback,handler)` | getMethod 8 参 | public 6 参不含 callback/handler；hidden 版仅签名不同 |
 * | `WindowManagerGlobal.getWindowManagerService()` → `IWindowManager.freezeRotation(displayId, caller)` | 静态反射 | A13/A15 javap 核实（31+ 双参；29/30 单参，见 [freezeRotation]） |
 * | `IWindowManager.setForcedDisplaySize(displayId,w,h)` | 同上 | javap 核实 |
 * | `PowerManager.userActivity(...)`（4s keep-alive） | 委托 [KeepAlive] | 见 KeepAlive KDoc |
 * | `ActivityTaskManager.getService()` → `moveRootTaskToDisplay(taskId, displayId)` | 静态反射 | API 31+；29/30 走 `ActivityManager.getService()` 的 `moveStackToDisplay` |
 * | `RunningTaskInfo.displayId`（hidden 字段） | getField | TaskInfo 基类字段，javap 核实 |
 * | `ActivityOptions.setLaunchDisplayId(displayId)` | **public API（API 26+）**，不反射 | SDK stub 核实 |
 */
class VirtualDisplayManager(
    /**
     * 运行进程的 Context：UserService 进程里是 Shizuku v13 传入的 package context
     * （提权主路径），app 进程里是普通 app context（降级路径）。
     * 只用于 getSystemService / packageManager / resources，不 registerReceiver。
     */
    private val context: Context,
) {
    companion object {
        private const val TAG = "BetterGI.VD"

        /** MAA 默认档（简报指定）。 */
        const val DEFAULT_W = 1280
        const val DEFAULT_H = 720
        const val DEFAULT_DPI = 160

        /** 防漂移重钉最大尝试数（MAA 语义：超过即放弃，避免每 tick 投放启动请求）。 */
        const val REPIN_MAX_ATTEMPTS = 3

        /** [ensureAppOnDisplay] 的轮询间隔 ms。 */
        private const val ENSURE_POLL_MS = 500L

        /** 虚拟显示器命名（dumpsys display 里可辨认）。 */
        private const val DISPLAY_NAME = "BetterGIVD"

        /**
         * 防漂移判定（MAA"无限重试等于每 tick 投放启动请求"的语义）：
         * 不在目标屏 && 未超尝试上限 ⇒ 重钉；到上限 ⇒ 放弃（调用方告警）。
         * 判不出（null）⇒ 不重钉（保守：对判不出的状态盲目投放是浪费）。
         * 纯函数，[VdRepinPolicyTest] 锁行为。
         */
        fun shouldRepin(isOnDisplay: Boolean?, driftCount: Int, maxAttempts: Int = REPIN_MAX_ATTEMPTS): Boolean =
            isOnDisplay == false && driftCount < maxAttempts

        /**
         * TODO(M3)：Surface 正式来源 = ImageReader（VirtualDisplayFrameSource 已就位，
         * 由它 new Surface(reader)）。M2 期间调用方可用本函数造 SurfaceTexture 占位
         * surface 过通链路。生命周期归调用方管。
         */
        fun createPlaceholderSurface(width: Int, height: Int): Surface {
            val st = android.graphics.SurfaceTexture(false)
            st.setDefaultBufferSize(width, height)
            return Surface(st)
        }
    }

    /** 当前虚拟显示器（null = 未创建）。displayId 由它派生。 */
    @Volatile
    private var display: VirtualDisplay? = null

    /** keep-alive 调度器（create 时随显示器建）。 */
    @Volatile
    private var keepAlive: KeepAlive? = null

    /**
     * ★ #310/M5：fps 估计器随 create 挂上 KeepAlive 轮询（服务端 1s 一采的 frameGeneration
     * 差分；BgiRemoteService.getGameFps 经它读值）。null = 未创建（getGameFps 返 -1）。
     */
    @Volatile
    var fpsEstimator: FrameCountFpsEstimator? = null
        private set

    /** [ensureAppOnDisplay]/[repin] 等共用的工作线程（注册回调要带 Looper）。 */
    private var worker: HandlerThread? = null

    /** 当前 displayId（未创建 = -1）。 */
    val displayId: Int
        get() = display?.display?.displayId ?: -1

    /** 是否已创建。 */
    val isCreated: Boolean
        get() = display != null

    // ── 创建 / 销毁 ─────────────────────────────────────────────────────

    /**
     * 创建虚拟显示器。返回 displayId，失败 -1。
     *
     * @param surface 由调用方提供（M3 正式接 ImageReader 的 Surface；M2 期间用
     *   SurfaceTexture 占位——见 [createPlaceholderSurface]）。
     * @param width/height/dpi MAA 默认档（1280×720@160）。
     */
    @Synchronized
    fun create(
        width: Int = DEFAULT_W,
        height: Int = DEFAULT_H,
        dpi: Int = DEFAULT_DPI,
        surface: Surface,
        /** ★ #310/M5：帧源代数探针（fps 估计器采样用；null = 不挂估计器）。 */
        frameSourceGenerationProbe: (() -> Long)? = null,
    ): Int {
        if (display != null) {
            Log.w(TAG, "create：已有虚拟显示器（displayId=$displayId），先 destroy 再建")
            return -1
        }
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        if (dm == null) {
            Log.w(TAG, "create：拿不到 DisplayManager")
            return -1
        }
        val sdk = Build.VERSION.SDK_INT
        val flags = DisplayFlags.flagsFor(sdk)

        // 双路径：提权进程走 hidden 8 参（特权 flag 生效）；否则 public 6 参（flag 丢弃）。
        val vd: VirtualDisplay? = if (isPrivilegedProcess()) {
            createWithPrivilegedFlags(dm, DISPLAY_NAME, width, height, dpi, surface, flags)
        } else {
            Log.w(
                TAG,
                "create：非提权进程（uid=${privilegeUid()}），特权 flag（TRUSTED/ALWAYS_UNLOCKED 等）" +
                    "不会被系统接受，退化到 public createVirtualDisplay——仅供开发冒烟，后台模式不可用",
            )
            runCatching {
                dm.createVirtualDisplay(DISPLAY_NAME, width, height, dpi, surface, flags)
            }.onFailure { Log.w(TAG, "public createVirtualDisplay 失败: ${it.message}") }
                .getOrNull()
        }
        if (vd == null) {
            Log.w(TAG, "create：全部路径失败")
            return -1
        }
        display = vd
        val id = vd.display?.displayId ?: -1
        if (id < 0) {
            Log.w(TAG, "create：显示器创建但 displayId 无效，回滚")
            runCatching { vd.release() }
            display = null
            return -1
        }

        // 竖屏锁定 + 横屏物理屏的强制分辨率（见方法 KDoc；全部尽力而为）
        ensurePortrait(id, width, height)

        // keep-alive：4s userActivity 防虚拟屏休眠
        KeepAlive(id).also {
            it.attachContext(context)
            it.start()
            keepAlive = it
        }

        // ★ #310/M5：fps 估计器挂到 KeepAlive 的 4s 拍上（采样间隔 1s ⇒ 每拍最多采 1~4 次，
        //   够 UI 显示粒度；不新建线程——本帧源是 createDisplay 传入的 surface 拥有者，
        //   代数从它读）。
        val fsGen = frameSourceGenerationProbe
        if (fsGen != null) {
            fpsEstimator = FrameCountFpsEstimator().also { est ->
                keepAlive?.addPeriodicObserver(
                    observer = { wallMs -> est.sample(fsGen(), wallMs) },
                    periodMs = est.sampleIntervalMs(),
                )
            }
        }

        ensureWorker()
        Log.i(
            TAG,
            "create：displayId=$id ${width}x${height}@${dpi} flags=0x${flags.toString(16)} " +
                "sdk=$sdk uid=${privilegeUid()}",
        )
        return id
    }

    /** 销毁：stop keep-alive + release 显示器 + 收线程。幂等。 */
    @Synchronized
    fun destroy() {
        keepAlive?.stop()
        keepAlive = null
        display?.let { vd ->
            runCatching { vd.release() }
                .onFailure { Log.w(TAG, "VirtualDisplay.release 失败: ${it.message}") }
        }
        display = null
        worker?.quitSafely()
        worker = null
        fpsEstimator = null
        Log.i(TAG, "destroy：虚拟显示器已释放")
    }

    // ── 应用投放到 VD 与校验 ─────────────────────────────────────────────

    /**
     * 把 `packageName` 启动到 [onDisplayId] 指定的显示器。
     *
     * `ActivityOptions.setLaunchDisplayId` 是 public API（API 26+，minSdk 24 内
     * 26 以下不支持——按 false 处理并打日志）。提权进程里 startActivity 无
     * BAL（background activity launch）限制；app 进程路径受 BAL 约束（M3 只在
     * 服务进程实例化本类，此处不绕）。
     */
    fun startApp(packageName: String, onDisplayId: Int): Boolean {
        if (onDisplayId < 0) {
            Log.w(TAG, "startApp：displayId 无效")
            return false
        }
        val pm = context.packageManager ?: return false
        val intent = runCatching {
            pm.getLaunchIntentForPackage(packageName)
        }.getOrNull()
        if (intent == null) {
            Log.w(TAG, "startApp：$packageName 拿不到 launch intent（未安装？）")
            return false
        }
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
        )
        return runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                val opts = ActivityOptions.makeBasic().setLaunchDisplayId(onDisplayId)
                context.startActivity(intent, opts.toBundle())
            } else {
                Log.w(TAG, "startApp：API < 26 无 setLaunchDisplayId，退默认屏启动")
                context.startActivity(intent)
            }
            Log.i(TAG, "startApp：$packageName → display $onDisplayId 已投放")
            true
        }.getOrElse {
            Log.w(TAG, "startApp 失败: ${it.message}")
            false
        }
    }

    /**
     * 判断 `packageName` 的 task 是否在当前 VD 上。
     * 三态：true/false = 判定；null = 判不出（getRunningTasks 失败或反射失败）。
     */
    fun isAppOnDisplay(packageName: String): Boolean? {
        val id = displayId
        if (id < 0) return null
        return isAppOnDisplay(context, packageName, id)
    }

    /**
     * 三级拉回（防漂移——游戏被 ROM 挪回主屏时把它拽回 VD）。逐级尝试：
     * 1. `IActivityTaskManager.moveRootTaskToDisplay(taskId, displayId)`（API 31+；
     *    29/30 是 `IActivityManager.moveStackToDisplay(stackId, displayId)`）；
     * 2. shell 命令 `am display move-stack <stackId> <displayId>`（提权进程内
     *    ProcessBuilder——shell uid 直接有权限）；
     * 3. 重投 launch intent（`ActivityOptions.setLaunchDisplayId`）。
     *
     * @return 最终是否判定"已回到 VD"（第三级投放成功后立即查一次 task 表，
     *   activity 启动是异步的，此刻可能尚未落位——见 [ensureAppOnDisplay] 的
     *   轮询语义；这里返回 false 只代表"此刻未确认"，调用方用 ensure 轮询）。
     */
    fun repin(packageName: String): Boolean {
        val id = displayId
        if (id < 0) {
            Log.w(TAG, "repin：未创建虚拟显示器")
            return false
        }
        val taskId = findTaskIdOnAnyDisplay(packageName)
        if (taskId != null) {
            // ── 第 1 级：moveRootTaskToDisplay / moveStackToDisplay ──
            if (moveTaskToDisplay(taskId, id)) {
                val now = isAppOnDisplay(context, packageName, id)
                Log.i(TAG, "repin L1（move-task）：taskId=$taskId → display $id，落位=$now")
                return now == true
            }
            // ── 第 2 级：am display move-stack ──
            if (amMoveStack(taskId, id)) {
                val now = isAppOnDisplay(context, packageName, id)
                Log.i(TAG, "repin L2（am move-stack）：taskId=$taskId → display $id，落位=$now")
                return now == true
            }
        } else {
            Log.i(TAG, "repin：$packageName 无存活 task（未启动/被杀），直接走 L3 投放")
        }
        // ── 第 3 级：重投 launch intent ──
        val l3 = startApp(packageName, id)
        Log.i(TAG, "repin L3（重投 intent）：startApp=$l3")
        if (!l3) return false
        return isAppOnDisplay(context, packageName, id) == true
    }

    /**
     * 轮询校验"游戏在 VD 上"，最多 [timeoutMs]；期间每 500ms 查一次 task 表，
     * 不在屏则触发一次 [repin]（重钉次数由 [shouldRepin] 的 maxAttempts 语义约束，
     * 超过 [REPIN_MAX_ATTEMPTS] 次放弃——MAA"无限重试=每 tick 投放启动请求"）。
     *
     * 阻塞调用（在调用线程 sleep 轮询）——调用方保证不在主线程（服务进程工作线程）。
     */
    fun ensureAppOnDisplay(packageName: String, timeoutMs: Long = 10_000): Boolean {
        val id = displayId
        if (id < 0) return false
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var repinAttempts = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            when (isAppOnDisplay(context, packageName, id)) {
                true -> return true
                false -> {
                    if (shouldRepin(false, repinAttempts)) {
                        repinAttempts++
                        Log.w(TAG, "ensure：游戏不在 VD（第 $repinAttempts 次重钉）")
                        repin(packageName)
                    } else if (repinAttempts >= REPIN_MAX_ATTEMPTS) {
                        Log.w(TAG, "ensure：重钉 $repinAttempts 次仍不在 VD，放弃（建议告警用户）")
                        return false
                    }
                }
                null -> Log.d(TAG, "ensure：task 表判不出，继续等")
            }
            runCatching { Thread.sleep(ENSURE_POLL_MS) }
        }
        val final = isAppOnDisplay(context, packageName, id)
        Log.i(TAG, "ensure：超时（${timeoutMs}ms），最终落位=$final")
        return final == true
    }

    // ── 内部：创建的双路径 ───────────────────────────────────────────────

    /**
     * 提权主路径：hidden 8 参 `createVirtualDisplay(name, w, h, dpi, surface, flags,
     * callback, handler)`。public 6 参走的是同一条 binder（DisplayManagerGlobal），
     * 特权 flag 能否生效取决于**调用 uid**，与重载无关——但 8 参重载是 MAA/scrcpy
     * 验证过的形态，且是 @SystemApi，经 HiddenApiBypass 豁免后调用。先试裸反射
     * （UserService 进程无 non-SDK 限制时直接过），失败再 HiddenApiBypass。
     */
    private fun createWithPrivilegedFlags(
        dm: DisplayManager,
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        flags: Int,
    ): VirtualDisplay? {
        val direct = runCatching {
            val m = DisplayManager::class.java.getMethod(
                "createVirtualDisplay",
                String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Surface::class.java, Int::class.javaPrimitiveType,
                VirtualDisplay.Callback::class.java, Handler::class.java,
            )
            m.isAccessible = true
            m.invoke(dm, name, width, height, dpi, surface, flags, null, null) as VirtualDisplay
        }.onFailure { Log.d(TAG, "hidden 8 参 createVirtualDisplay 裸反射失败: ${it.message}") }
            .getOrNull()
        if (direct != null) return direct
        // HiddenApiBypass 兜底（P+ hidden API 元数据限制）
        return runCatching {
            org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(
                DisplayManager::class.java, dm, "createVirtualDisplay",
                name, width, height, dpi, surface, flags, null, null,
            ) as VirtualDisplay
        }.onFailure { Log.w(TAG, "HiddenApiBypass createVirtualDisplay 也失败: ${it.message}") }
            .getOrNull()
    }

    /**
     * 竖屏锁定 + 横屏物理屏的强制分辨率：
     * - `freezeRotation(displayId, caller)`：锁 ROTATION_0（竖直）。hidden API。
     *   A13/A15 签名双参 `(int, String)`；29/30 是单参 `(int)`——按参数个数探测。
     * - `setForcedDisplaySize(displayId, w, h)`：仅当**物理主屏是横屏**时调
     *   （`context.resources.configuration.orientation` == ORIENTATION_LANDSCAPE），
     *   把 VD 的默认分辨率压成与请求一致，避免系统按主屏方向出帧。
     * 全部尽力而为：失败只打日志不回滚（锁不了旋转游戏大概率仍能跑）。
     */
    private fun ensurePortrait(displayId: Int, width: Int, height: Int) {
        freezeRotation(displayId)
        val landscape = runCatching {
            context.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        }.getOrDefault(false)
        if (landscape) {
            Log.i(TAG, "ensurePortrait：物理屏横屏 → setForcedDisplaySize($displayId, $width, $height)")
            withWindowManager { wm ->
                wm.javaClass.getMethod(
                    "setForcedDisplaySize",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                ).invoke(wm, displayId, width, height)
            }
        }
    }

    /**
     * `IWindowManager.freezeRotation(displayId, caller)` 反射（31+ 双参版；
     * 29/30 单参版在方法里按参数个数探测兜底）。ROTATION_0 = 0。
     */
    private fun freezeRotation(displayId: Int) {
        val ok = withWindowManager { wm ->
            val methods = wm.javaClass.methods.filter { it.name == "freezeRotation" }
            val m2 = methods.firstOrNull { it.parameterTypes.size == 2 }
            if (m2 != null) {
                m2.isAccessible = true
                m2.invoke(wm, displayId, "BetterGI")
            } else {
                val m1 = methods.first { it.parameterTypes.size == 1 }
                m1.isAccessible = true
                m1.invoke(wm, displayId)
            }
        }
        if (ok) {
            Log.i(TAG, "freezeRotation($displayId, ROTATION_0) 成功")
        } else {
            Log.w(TAG, "freezeRotation($displayId) 失败（旋转未锁，尽力而为继续）")
        }
    }

    /**
     * 反射取 `WindowManagerGlobal.getWindowManagerService()`（IWindowManager）并
     * 执行 [block]。任一环节抛异常（类被 hidden 拦、binder 异常）⇒ false。
     * 注：WindowManagerGlobal 是 hidden 类（public stub 没有），必须 Class.forName；
     * UserService 进程无 hidden 限制时直过，被拦时再升级 HiddenApiBypass（拿到
     * 的是 binder proxy，接口方法反射一般不受元数据限制影响）。
     */
    private fun withWindowManager(block: (Any) -> Unit): Boolean = runCatching {
        val wm = windowManagerService() ?: error("getWindowManagerService 返回 null")
        block(wm)
    }.onFailure { e ->
        Log.w(TAG, "IWindowManager 调用失败: ${e.javaClass.simpleName}: ${e.message}")
    }.isSuccess

    /** `WindowManagerGlobal.getWindowManagerService()`（hidden，全反射 + bypass 兜底）。 */
    private fun windowManagerService(): Any? {
        val cls = runCatching { Class.forName("android.view.WindowManagerGlobal") }
            .onFailure { Log.d(TAG, "WindowManagerGlobal forName 失败: ${it.message}") }
            .getOrNull() ?: return null
        val direct = runCatching {
            cls.getMethod("getWindowManagerService").invoke(null)
        }.getOrNull()
        if (direct != null) return direct
        return runCatching {
            org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(
                cls, null, "getWindowManagerService",
            )
        }.onFailure { Log.w(TAG, "HiddenApiBypass getWindowManagerService 失败: ${it.message}") }
            .getOrNull()
    }

    // ── 内部：防漂移三级拉回 ─────────────────────────────────────────────

    /**
     * 在所有 task 里找 `packageName` 的 taskId（任意屏）。null = 无存活 task。
     * 用 `ActivityManager.getRunningTasks(100)` + 反射 `RunningTaskInfo.displayId`
     * （hidden 字段，TaskInfo 基类）。
     */
    private fun findTaskIdOnAnyDisplay(packageName: String): Int? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return null
        return runCatching {
            am.getRunningTasks(100).firstOrNull { info ->
                runCatching { info.topActivity?.packageName == packageName }
                    .getOrDefault(false) ||
                    runCatching { info.baseActivity?.packageName == packageName }
                        .getOrDefault(false)
            }?.taskId
        }.getOrElse {
            Log.w(TAG, "getRunningTasks 失败: ${it.message}")
            null
        }
    }

    /**
     * 第 1 级：binder 直调 move-to-display。
     * - API 31+：`ActivityTaskManager.getService().moveRootTaskToDisplay(taskId, displayId)`；
     * - API 30：`ActivityTaskManager.getService().moveStackToDisplay(stackId, displayId)`
     *   （IActivityTaskManager 上的方法名仍是 moveStackToDisplay，javap A11 jar 核实）；
     * - API 29：`ActivityManager.getService().moveStackToDisplay(...)`（29 还没有
     *   ActivityTaskManager 类，IActivityManager 上同名，javap A10 jar 核实）。
     *   minSdk 24 → 24-28 无 binder 路径，直接落第 2/3 级。
     */
    private fun moveTaskToDisplay(taskId: Int, displayId: Int): Boolean {
        val sdk = Build.VERSION.SDK_INT
        val methodName = when {
            sdk >= 31 -> "moveRootTaskToDisplay"
            sdk == 30 -> "moveStackToDisplay"
            sdk == 29 -> "moveStackToDisplay"
            else -> return false // 24-28：无可靠 binder 面，直接落 am/L3
        }
        val serviceClassName = if (sdk >= 30) "android.app.ActivityTaskManager" else "android.app.ActivityManager"
        val ok = runCatching {
            val service = Class.forName(serviceClassName)
                .getMethod("getService").invoke(null)
            service.javaClass
                .getMethod(
                    methodName,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                )
                .invoke(service, taskId, displayId)
        }.onFailure { Log.w(TAG, "$methodName 裸反射失败: ${it.message}") }
            .isSuccess
        if (ok) return true
        // 裸反射被 hidden 拦 ⇒ HiddenApiBypass 再试一次
        return runCatching {
            val service = org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(
                Class.forName(serviceClassName), null, "getService",
            )
            org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(
                service.javaClass, service, methodName, taskId, displayId,
            )
            true
        }.onFailure { Log.w(TAG, "HiddenApiBypass $methodName 失败: ${it.message}") }
            .getOrDefault(false)
    }

    /**
     * 第 2 级：`am display move-stack <stackId> <displayId>` shell 命令。
     * 提权进程（shell uid）内 ProcessBuilder 直跑有权限；stack 与 task 同 id 传
     * （A10+ task 即根 stack）。非提权进程该命令会 Permission denied——失败返回
     * false 走 L3。
     */
    private fun amMoveStack(taskId: Int, displayId: Int): Boolean {
        return runCatching {
            val p = ProcessBuilder(
                "am", "display", "move-stack", taskId.toString(), displayId.toString(),
            ).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readText().trim() }
            val code = p.waitFor()
            Log.d(TAG, "am move-stack exit=$code out=${out.take(200)}")
            code == 0
        }.getOrElse {
            Log.w(TAG, "am move-stack 执行失败: ${it.message}")
            false
        }
    }

    // ── 内部：判定与工具 ─────────────────────────────────────────────────

    /**
     * task 表判定（Context 入参版本，供 [repin] 内部即时复查复用）。
     * [VdStateProbe] 是 M3 引擎侧的顶层探针（含 SDK 页判定），与本函数分工：
     * 这里只回答"包名是否落在 displayId"。
     */
    private fun isAppOnDisplay(context: Context, packageName: String, displayId: Int): Boolean? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return null
        return runCatching {
            val tasks = am.getRunningTasks(100)
            val field = ActivityManager.RunningTaskInfo::class.java.getField("displayId")
            tasks.any { info ->
                val pkg = runCatching { info.topActivity?.packageName }.getOrNull()
                    ?: runCatching { info.baseActivity?.packageName }.getOrNull()
                pkg == packageName && field.getInt(info) == displayId
            }
        }.getOrElse {
            Log.w(TAG, "isAppOnDisplay 判定失败: ${it.message}")
            null
        }
    }

    /** 是否提权进程（shell uid 2000 / root 0 / Shizuku root）。 */
    private fun isPrivilegedProcess(): Boolean {
        val uid = privilegeUid()
        return uid == 0 || uid == 2000
    }

    private fun privilegeUid(): Int = runCatching { android.os.Process.myUid() }.getOrDefault(-1)

    /** 工作线程（懒建；destroy 时回收）。 */
    private fun ensureWorker() {
        if (worker?.isAlive == true) return
        worker = HandlerThread("BgiVdWorker").also { it.start() }
    }
}
