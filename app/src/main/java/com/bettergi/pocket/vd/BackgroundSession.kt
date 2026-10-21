package com.bettergi.pocket.vd

import android.os.SystemClock
import android.util.Log
import com.bettergi.pocket.genshin.GenshinPackages
import com.bettergi.pocket.scan.GenshinStateProbe
import com.bettergi.pocket.shizuku.BgiRemoteManager

/**
 * ★ #310/M6-audit：后台模式的三件套**app 侧代理**（VdStateProbe via AIDL / VD 注入 via AIDL /
 * VD 帧源 app 本地）。
 *
 * 架构定案（审计修复的核心）：
 * - **帧源留在 app 进程**：app 建 [VirtualDisplayFrameSource]（ImageReader），其 Surface 经
 *   AIDL `createDisplayWithSurface` 交给服务进程建 VD ⇒ 帧直接回流 app 侧 ImageReader，
 *   **零跨进程帧传输**，引擎拿到的就是同一份 Mat 管线。
 * - **注入/顶层查询反向走 AIDL**：injectInputEvent 与全量 getRunningTasks 需要 shell uid
 *   ⇒ 经 [BgiRemoteManager.service()] 调服务端方法。
 *
 * 生命周期：`start()`（建源+起 AIDL 建屏+拉游戏）/ `stop()`（停源+destroyDisplay）。
 * 一个实例对应一次后台会话；会话结束必须 stop（服务端 KeepAlive/Guard 有状态）。
 */
class BackgroundSession(
    private val width: Int = WIDTH,
    private val height: Int = HEIGHT,
    private val dpi: Int = DPI,
) {

    companion object {
        /**
         * ★ #310/M6-audit：进程级当前会话（UI 的"启动虚拟屏"创建、TriggerForegroundService 的
         * 装配解析器消费；同进程 ⇒ 直接静态持有，无需跨进程）。会话结束（UI stop / destroy）
         * 时置 null。
         */
        @Volatile
        var current: BackgroundSession? = null
            private set

        fun install(session: BackgroundSession) {
            current = session
        }

        fun clear() {
            current = null
        }

        private const val TAG = "BetterGI.VD"
        private const val GAME = "com.miHoYo.Yuanshen"

        /**
         * ★ 2026-10-19 定档：后台模式虚拟屏**统一 1280×720@160**（此前 2560×1440@240）。
         *
         * 为什么降档：
         * - 华为 Kirin 970 上 2560×1440 的 VD 渲染把整机拖到 **25+ 分钟**才出首帧（实测）；
         * - 1280×720 与 2560×1440 同为 16:9、比例恰 0.5 ⇒ 有 `profiles_1280x720.json` 可换算，
         *   不需要 3200(20:9)→16:9 那种两轴不等比的致命换算；
         * - 预览窗宽高比固定 16:9，在竖屏管理页里按宽度铺满即可（黑边来源见布局注释）。
         *
         * ⚠️ 改这两个数 = 换档：`profiles_1280x720.json` 的所有坐标都要跟着复核
         *   （生成器 dsl/scripts/gen_profiles_1280x720.py，含实测回灌口）。
         */
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val DPI = 160

        /** 顶层查询的 AIDL 失败重试（判不出时的保守口径由 probe 决定）。 */
        private const val TOP_QUERY_MAX_FAILS = 3
    }

    /** app 进程的帧源（Surface 的拥有者）。 */
    var frameSource: VirtualDisplayFrameSource? = null
        private set

    var displayId: Int = -1
        private set

    /** 注入代理（每次调用经 AIDL 进服务进程；槽位表在服务端 VdInjectActionGateway 实例内
     *  —— ⚠️ 服务端 tap/swipeOnDisplay 每次 new Gateway ⇒ 槽位表不跨调用保留。
     *  审计判定：M6 扫描流程全是 tap 与整段 swipe（DOWN…UP 都在单次调用内闭合），
     *  跨调用残留的唯一形态是"上次 UP 丢失"，而 VdInput 的 cancelFirst 只在同一次
     *  调用内部生效。⇒ 已知边界，记录于此；修复归属 M7（服务端缓存 Gateway 实例）。 */
    val actions = object : com.bettergi.pocket.scan.ScanEngine.ActionGateway {
        override fun tap(x: Int, y: Int): Boolean = remote { it.tapOnDisplay(displayId, x, y) }
        override fun click(x: Int, y: Int, durationMs: Long): Boolean =
            remote { it.tapOnDisplay(displayId, x, y) } // VD 点击无 duration 语义，归一到 tap
        override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean =
            remote { it.swipeOnDisplay(displayId, fromX, fromY, toX, toY) }
        override fun back(): Boolean = remote { it.backOnDisplay(displayId) }
        override fun resetPassthrough() = Unit // 后台模式无悬浮窗
        private inline fun remote(block: (com.bettergi.pocket.shizuku.IBgiRemoteService) -> Boolean): Boolean {
            val svc = BgiRemoteManager.service()
            if (svc == null) {
                Log.w(TAG, "BackgroundSession.actions: remote 未连接")
                return false
            }
            return runCatching { block(svc) }.getOrElse {
                Log.w(TAG, "BackgroundSession.actions remote call failed: ${it.message}")
                false
            }
        }
    }

    /** 前台状态探针（后台形态）：经 AIDL 查 VD 顶层 task，折叠语义与 VdStateProbe 一致。 */
    val probe: GenshinStateProbe = object : GenshinStateProbe {
        private var consecutiveFails = 0

        override fun isGameInForeground(): Boolean? {
            val top = topActivity() ?: return null
            val (pkg, cls) = top
            if (pkg == null) return false // 空数组 = VD 上没有 task
            return GenshinPackages.isGameForeground(pkg, cls)
        }

        override fun isSdkLoginOnTop(): Boolean? {
            val top = topActivity() ?: return null
            val (_, cls) = top
            return GenshinPackages.isSdkActivity(cls)
        }

        /** (pkg, cls)：空 VD = (null,null)；AIDL 失败/服务未连 = null。 */
        private fun topActivity(): Pair<String?, String?>? {
            val svc = BgiRemoteManager.service()
            if (svc == null) {
                if (++consecutiveFails <= TOP_QUERY_MAX_FAILS) Log.w(TAG, "topActivity: 服务未连接")
                return null
            }
            val arr = runCatching { svc.topActivityOnDisplay(displayId) }.getOrNull()
            if (arr == null) {
                if (++consecutiveFails <= TOP_QUERY_MAX_FAILS) Log.w(TAG, "topActivityOnDisplay 判不出")
                return null
            }
            consecutiveFails = 0
            if (arr.isEmpty()) return Pair(null, null)
            return Pair(arr.getOrNull(0), arr.getOrNull(1))
        }
    }

    /**
     * 建屏并拉起游戏。@return null = 成功；否则为可读失败原因。
     * 调用前提：BgiRemoteManager 已 CONNECTED（startVdChain 保证）。
     */
    fun start(): String? {
        if (displayId >= 0) return null // 幂等
        val svc = BgiRemoteManager.service() ?: return "远程服务未连接"
        val fs = VirtualDisplayFrameSource(displayId = -1, width = width, height = height).also { it.start() }
        frameSource = fs
        val id = runCatching {
            svc.createDisplayWithSurface(fs.surface(), width, height, dpi)
        }.getOrElse {
            fs.stop()
            frameSource = null
            Log.w(TAG, "createDisplayWithSurface failed: ${it.message}")
            return "建屏失败：${it.message}"
        }
        if (id < 0) {
            fs.stop()
            frameSource = null
            return "建屏失败（服务端返回 -1，检查 Shizuku 权限/日志）"
        }
        displayId = id
        Log.i(TAG, "后台会话建屏成功 displayId=$id ${width}x$height（帧源在 app 进程）")
        if (!svc.startAppOnDisplay(GAME, id)) {
            // 建屏成功但游戏没拉起来——不认为是致命（可能游戏已在 VD 上/稍后 repin），如实上报
            return "虚拟屏已建（displayId=$id）但游戏启动未受理（可试「游戏调回前台」或重试）"
        }
        return null
    }

    /** 收尾：停帧源 + 服务端销屏。幂等。 */
    fun stop() {
        frameSource?.stop()
        frameSource = null
        val svc = BgiRemoteManager.service()
        if (svc != null && displayId >= 0) {
            runCatching { svc.destroyDisplay() }
                .onFailure { Log.w(TAG, "destroyDisplay failed: ${it.message}") }
        }
        displayId = -1
        Log.i(TAG, "后台会话已收尾")
    }

    /** 自检：会话可用（帧源活着 + 服务连着 + 屏已建）。 */
    fun isHealthy(): Boolean =
        displayId >= 0 && frameSource?.isRunning() == true && BgiRemoteManager.service() != null

    /** 供 UI 的帧率直读（帧源在 app 进程 ⇒ 无需 AIDL 的 getGameFps）。 */
    fun frameGeneration(): Long = frameSource?.frameGeneration() ?: 0L

    /** 帧率估计（两次调用差分；首次/无样本返回 -1）。 */
    private var lastFpsGen = -1L
    private var lastFpsWall = 0L
    fun fpsEstimate(): Int {
        val g = frameGeneration()
        val now = SystemClock.elapsedRealtime()
        if (g <= 0) return -1
        val dg = if (lastFpsGen >= 0) g - lastFpsGen else -1
        val dw = now - lastFpsWall
        lastFpsGen = g
        lastFpsWall = now
        // 无样本 / 代数未动（黑帧 -1 或静帧）/ 时间未前进 ⇒ 无新数据
        return if (dg <= 0 || dw <= 0 || dw > 5000) -1 else ((dg * 1000) / dw).toInt()
    }

    /**
     * 预览小图：app 进程从帧源缓存直接采样（每 ~32px 取一点、缩到 ~480 宽）。
     * 不压 JPEG 不过 binder——扫描与预览读同一块帧缓存（零拷贝路径的消费方之一）。
     */
    fun previewBitmap(): android.graphics.Bitmap? {
        val s = frameSource?.latestRgbaSnapshot() ?: return null
        val maxW = 480
        val step = (s.width / maxW).coerceAtLeast(1)
        val w = s.width / step
        val h = s.height / step
        val px = IntArray(w * h)
        var oi = 0
        var y = 0
        while (y < h) {
            val sy = y * step
            var x = 0
            while (x < w) {
                val si = sy * s.rowStride + (x * step) * 4
                if (si + 3 < s.rgba.size) {
                    val r = s.rgba[si].toInt() and 0xFF
                    val g = s.rgba[si + 1].toInt() and 0xFF
                    val b = s.rgba[si + 2].toInt() and 0xFF
                    px[oi] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                oi++
                x++
            }
            y++
        }
        return android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
    }
}