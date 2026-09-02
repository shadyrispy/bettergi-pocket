package com.bettergi.pocket.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.bettergi.pocket.genshin.GenshinPackages

class InputAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "accessibility service connected")
        notifyStateChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearInstance()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearInstance()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg in TRANSIENT_PACKAGES) return
        lastAppPackage = pkg
    }

    override fun onInterrupt() = Unit

    private fun clearInstance() {
        if (instance === this) {
            instance = null
            lastAppPackage = null
            notifyStateChanged()
        }
    }

    private fun notifyStateChanged() {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    companion object {
        private const val TAG = "BetterGI.Input"
        const val DEFAULT_PROMPT = "请开启无障碍权限，才能模拟点击"
        const val CRASHED_PROMPT = "无障碍服务已异常，请先关闭再重新打开"
        const val ACTION_STATE_CHANGED = "com.bettergi.pocket.action.ACCESSIBILITY_CHANGED"
        private const val AUTHORITY_SUFFIX = ".a11y"
        private const val METHOD_STATUS = "status"
        private const val METHOD_CLICK = "click"
        private const val METHOD_SWIPE = "swipe"
        private const val METHOD_SCAN_PROGRESS = "scan_progress"
        private const val METHOD_PROBE = "probe"
        private const val KEY_TEXT = "text"
        private const val KEY_CONNECTED = "connected"
        private const val KEY_LAST_PACKAGE = "last_package"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val KEY_FROM_X = "from_x"
        private const val KEY_FROM_Y = "from_y"
        private const val KEY_TO_X = "to_x"
        private const val KEY_TO_Y = "to_y"
        private const val KEY_SEGMENTS = "segments"
        private const val KEY_DURATION = "duration"
        private const val KEY_OK = "ok"
        // 三段无惯性滑动经验节奏（scanner-app 真机验证值）：快滑 90% → 缓速 10% → 回退 1px
        private const val SWIPE_FAST_MS = 400L
        private const val SWIPE_SLOW_MS = 300L
        private const val SWIPE_BACK_MS = 100L
        private const val SWIPE_FAST_RATIO = 0.9f

        /** 三段总时长（passthrough 恢复延时等外部引用；唯一事实源防漂移）。 */
        const val SWIPE_TOTAL_MS = SWIPE_FAST_MS + SWIPE_SLOW_MS + SWIPE_BACK_MS
        private const val BIND_GRACE_MS = 2000L
        private const val BIND_POLL_MS = 250L

        @Volatile
        private var instance: InputAccessibilityService? = null

        @Volatile
        private var lastAppPackage: String? = null

        @Volatile
        private var appContext: Context? = null

        private val recoverHandler = Handler(Looper.getMainLooper())
        private var recoverCheck: Runnable? = null

        private val TRANSIENT_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        )

        fun attach(context: Context) {
            appContext = context.applicationContext
        }

        fun isConnected(): Boolean {
            if (instance != null) return true
            return remoteStatus()?.getBoolean(KEY_CONNECTED, false) == true
        }

        /** `true`/`false` 表示原神是否在前台；无障碍未连接或尚未观察到窗口时为 `null`。 */
        fun isGenshinInForeground(): Boolean? {
            val (connected, pkg) = currentStatus()
            if (!connected) return null
            val name = pkg ?: return null
            return GenshinPackages.isGenshinPackage(name)
        }

        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            val component = ComponentName(context, InputAccessibilityService::class.java)
            return AccessibilityServiceHealth.isListed(
                enabled,
                component.flattenToString(),
                component.flattenToShortString(),
            )
        }

        fun health(context: Context): AccessibilityServiceHealth.State {
            return AccessibilityServiceHealth.state(isConnected(), isEnabledInSettings(context))
        }

        fun isEnabled(context: Context): Boolean = isConnected()

        fun openSettings(context: Context) {
            val component = ComponentName(context, InputAccessibilityService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    context.startActivity(
                        Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra(Intent.EXTRA_COMPONENT_NAME, component)
                        },
                    )
                    return
                } catch (_: Exception) {
                }
            }
            val highlight = component.flattenToString()
            val args = Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, highlight) }
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(EXTRA_FRAGMENT_ARG_KEY, highlight)
                    putExtra(EXTRA_SHOW_FRAGMENT_ARGS, args)
                },
            )
        }

        fun ensureEnabled(context: Context, message: String = DEFAULT_PROMPT): Boolean {
            if (isConnected()) return true
            val prompt = if (isEnabledInSettings(context)) CRASHED_PROMPT else message
            Toast.makeText(context, prompt, Toast.LENGTH_LONG).show()
            openSettings(context)
            return false
        }

        fun promptIfDisconnected(context: Context, graceMs: Long = BIND_GRACE_MS) {
            val app = context.applicationContext
            cancelRecoverCheck()
            val startedAt = SystemClock.uptimeMillis()
            val runnable = object : Runnable {
                override fun run() {
                    if (isConnected()) return
                    if (SystemClock.uptimeMillis() - startedAt < graceMs) {
                        recoverHandler.postDelayed(this, BIND_POLL_MS)
                        return
                    }
                    if (isEnabledInSettings(app)) {
                        ensureEnabled(app)
                    }
                }
            }
            recoverCheck = runnable
            recoverHandler.post(runnable)
        }

        fun cancelRecoverCheck() {
            recoverCheck?.let(recoverHandler::removeCallbacks)
            recoverCheck = null
        }

        fun click(x: Int, y: Int, durationMs: Long = 50L): Boolean {
            if (instance != null) return clickLocal(x, y, durationMs)
            val extras = Bundle().apply {
                putInt(KEY_X, x)
                putInt(KEY_Y, y)
                putLong(KEY_DURATION, durationMs)
            }
            return remoteCall(METHOD_CLICK, extras)?.getBoolean(KEY_OK, false) == true
        }

        /**
         * 滑动。[segments] = 1 单段惯性放行；3 = 三段消惯性（对齐 scanner-app 已验证实现）：
         * **逐段 dispatchGesture + callback 链**——continueStroke 的设计语义是分段派发，
         * 三段塞同一 GestureDescription 一次 dispatch 会导致后续段不执行（真机"滑动不准"根因）。
         * 段1 快滑 90%/400ms → 段2 缓速 10%/300ms 到终点+1px → 段3 回退 1px/100ms（末速≈0 无 fling）。
         * 返回第一段是否受理；整体结果经 [onDone] 回调。
         */
        fun swipe(
            fromX: Int,
            fromY: Int,
            toX: Int,
            toY: Int,
            durationMs: Long = 400L,
            segments: Int = 3,
            onDone: ((Boolean) -> Unit)? = null,
        ): Boolean {
            if (instance != null) return swipeLocal(fromX, fromY, toX, toY, durationMs, segments, onDone)
            val extras = Bundle().apply {
                putInt(KEY_FROM_X, fromX)
                putInt(KEY_FROM_Y, fromY)
                putInt(KEY_TO_X, toX)
                putInt(KEY_TO_Y, toY)
                putLong(KEY_DURATION, durationMs)
                putInt(KEY_SEGMENTS, segments)
            }
            return remoteCall(METHOD_SWIPE, extras)?.getBoolean(KEY_OK, false) == true
        }

        fun handleBridgeCall(method: String, extras: Bundle?): Bundle {
            return when (method) {
                METHOD_STATUS -> Bundle().apply {
                    putBoolean(KEY_CONNECTED, instance != null)
                    putString(KEY_LAST_PACKAGE, lastAppPackage)
                }
                METHOD_SCAN_PROGRESS -> Bundle().apply {
                    putBoolean(KEY_OK, pushScanProgressLocal(extras?.getString(KEY_TEXT) ?: ""))
                }
                METHOD_PROBE -> Bundle().apply {
                    putBoolean(KEY_OK, toggleProbeOverlay())
                }
                METHOD_CLICK -> Bundle().apply {
                    putBoolean(
                        KEY_OK,
                        clickLocal(
                            extras?.getInt(KEY_X) ?: 0,
                            extras?.getInt(KEY_Y) ?: 0,
                            extras?.getLong(KEY_DURATION, 50L) ?: 50L,
                        ),
                    )
                }
                METHOD_SWIPE -> Bundle().apply {
                    putBoolean(
                        KEY_OK,
                        swipeLocal(
                            extras?.getInt(KEY_FROM_X) ?: 0,
                            extras?.getInt(KEY_FROM_Y) ?: 0,
                            extras?.getInt(KEY_TO_X) ?: 0,
                            extras?.getInt(KEY_TO_Y) ?: 0,
                            extras?.getLong(KEY_DURATION, 400L) ?: 400L,
                            extras?.getInt(KEY_SEGMENTS, 3) ?: 3,
                            null,
                        ),
                    )
                }
                else -> Bundle()
            }
        }

        private fun currentStatus(): Pair<Boolean, String?> {
            if (instance != null) return true to lastAppPackage
            val status = remoteStatus() ?: return false to null
            return status.getBoolean(KEY_CONNECTED, false) to status.getString(KEY_LAST_PACKAGE)
        }

        private fun clickLocal(x: Int, y: Int, durationMs: Long): Boolean {
            val service = instance ?: return false
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1L))
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return service.dispatchGesture(gesture, null, null)
        }

        /**
         * 三段消惯性滑动（对齐 scanner-app IrminsulAccessibilityService 已验证实现）：
         * **逐段 dispatchGesture + GestureResultCallback 链**。段1 快滑 90% → 段2 缓速 10% 到
         * 终点+1px → 段3 回退 1px 到终点（末速≈0，抬手无 fling，净位移精确）。
         * 全程整像素，避免亚像素段间漂移。segments == 1 时退化为单段 stroke（惯性放行）。
         */
        private fun swipeLocal(
            fromX: Int,
            fromY: Int,
            toX: Int,
            toY: Int,
            durationMs: Long,
            segments: Int,
            onDone: ((Boolean) -> Unit)?,
        ): Boolean {
            val service = instance ?: return false
            if (segments <= 1) {
                val path = Path().apply {
                    moveTo(fromX.toFloat(), fromY.toFloat())
                    lineTo(toX.toFloat(), toY.toFloat())
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(
                        GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1L)),
                    )
                    .build()
                return service.dispatchGesture(gesture, callback(onDone), null)
            }

            val dx = toX - fromX
            val dy = toY - fromY
            if (dx == 0 && dy == 0) return false
            // 段1：快滑 90%（整像素，避免亚像素段间漂移）
            val midX = fromX + Math.round(dx * SWIPE_FAST_RATIO)
            val midY = fromY + Math.round(dy * SWIPE_FAST_RATIO)
            // 段2：终点 + 1px 余量（沿运动方向）；段3 回退 1px = 精确落在 toX/toY
            val unitX = if (dx >= 0) 1 else -1
            val unitY = if (dy >= 0) 1 else -1
            val preX = toX + unitX
            val preY = toY + unitY
            val fastMs = SWIPE_FAST_MS.coerceAtMost(durationMs)

            var stroke = GestureDescription.StrokeDescription(
                Path().apply {
                    moveTo(fromX.toFloat(), fromY.toFloat())
                    lineTo(midX.toFloat(), midY.toFloat())
                },
                0L,
                fastMs,
                /* willContinue = */ true,
            )
            // 段1
            val accepted = service.dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(),
                callback { ok ->
                    if (!ok) {
                        onDone?.invoke(false)
                        return@callback
                    }
                    // 段2：缓速 10% → 终点+1px
                    stroke = stroke.continueStroke(
                        Path().apply {
                            moveTo(midX.toFloat(), midY.toFloat())
                            lineTo(preX.toFloat(), preY.toFloat())
                        },
                        0L,
                        SWIPE_SLOW_MS,
                        /* willContinue = */ true,
                    )
                    val accepted2 = service.dispatchGesture(
                        GestureDescription.Builder().addStroke(stroke).build(),
                        callback { ok2 ->
                            if (!ok2) {
                                onDone?.invoke(false)
                                return@callback
                            }
                            // 段3：回退 1px 到精确终点，willContinue=false 结束手势
                            stroke = stroke.continueStroke(
                                Path().apply {
                                    moveTo(preX.toFloat(), preY.toFloat())
                                    lineTo(toX.toFloat(), toY.toFloat())
                                },
                                0L,
                                SWIPE_BACK_MS,
                                /* willContinue = */ false,
                            )
                            service.dispatchGesture(
                                GestureDescription.Builder().addStroke(stroke).build(),
                                callback(onDone),
                                null,
                            )
                        },
                        null,
                    )
                    if (!accepted2) onDone?.invoke(false)
                },
                null,
            )
            return accepted
        }

        /** GestureResultCallback → Boolean 回调适配（回调在主线程）。 */
        private fun callback(onDone: ((Boolean) -> Unit)?) =
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onDone?.invoke(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onDone?.invoke(false)
                }
            }

        // ---- P2 前置探针：TYPE_ACCESSIBILITY_OVERLAY 在 :a11y 进程的挂载/z 序验证 ----
        private var probeView: View? = null
        private var probeProgressView: TextView? = null

        /**
         * 挂/卸探针（主进程入口）。
         * ⚠️ View 不能跨进程——:a11y 小窗必须在 :a11y 进程内构建。
         * 主进程仅发 METHOD_PROBE 指令，:a11y 进程桥内构建并挂载视图（P2 跨进程视图模式验证）。
         */
        fun toggleProbe(context: Context): Boolean {
            if (instance != null) return toggleProbeOverlay()
            return remoteCall(METHOD_PROBE, Bundle())?.getBoolean(KEY_OK, false) == true
        }

        /**
         * 挂/卸 A11y Overlay 探针（:a11y 进程内执行）：色块 + 扫描进度行。
         * 真机在原神上方能看到 = P2「零权限悬浮窗」核心假设成立；
         * 进度行经 METHOD_SCAN_PROGRESS 桥从主进程实时更新 = 跨进程状态桥机制验证。
         */
        private fun toggleProbeOverlay(): Boolean {
            val service = instance ?: return false
            val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val existing = probeView
            if (existing != null) {
                try {
                    wm.removeView(existing)
                } catch (_: Throwable) {
                }
                probeView = null
                probeProgressView = null
                return false
            }
            val container = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.argb(210, 120, 30, 30))
                setPadding(20, 12, 20, 12)
            }
            val title = TextView(service).apply {
                text = "A11y Overlay ✓"
                setTextColor(Color.WHITE)
                textSize = 15f
            }
            val progress = TextView(service).apply {
                text = "扫描进度：待机"
                setTextColor(Color.rgb(255, 220, 150))
                textSize = 12f
            }
            container.addView(title)
            container.addView(progress)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 160
            }
            try {
                wm.addView(container, lp)
                probeView = container
                probeProgressView = progress
                return true
            } catch (_: Throwable) {
                probeView = null
                probeProgressView = null
                return false
            }
        }

        /** 主进程 → :a11y 推送扫描进度文本（probe 挂载时更新进度行；本地直调/远程桥双路径）。 */
        fun pushScanProgress(text: String): Boolean {
            if (instance != null) {
                probeProgressView?.post { probeProgressView?.text = text }
                return probeProgressView != null
            }
            val extras = Bundle().apply { putString(KEY_TEXT, text) }
            return remoteCall(METHOD_SCAN_PROGRESS, extras)?.getBoolean(KEY_OK, false) == true
        }

        private fun pushScanProgressLocal(text: String): Boolean {
            val view = probeProgressView ?: return false
            view.post { view.text = text }
            return true
        }

        // ---- P2-b 地基：:a11y 进程 A11y Overlay 视图挂载通用 API ----
        // 仅在 :a11y 进程（instance != null）有效；A11yOverlayHost（悬浮窗迁移）的执行底座。
        // 探针（toggleProbeOverlay）已验证该挂载路径可行。

        /** 在 :a11y 进程挂载视图（TYPE_ACCESSIBILITY_OVERLAY 零权限）。须由调用方持视图引用。 */
        fun attachA11yView(view: View, lp: WindowManager.LayoutParams): Boolean {
            val service = instance ?: return false
            val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            return try {
                wm.addView(view, lp)
                true
            } catch (_: Throwable) {
                false
            }
        }

        /** 从 :a11y 进程卸载视图。 */
        fun detachA11yView(view: View): Boolean {
            val service = instance ?: return false
            val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            return try {
                wm.removeView(view)
                true
            } catch (_: Throwable) {
                false
            }
        }

        /** 更新 :a11y 进程视图的 LayoutParams（位置/flags 变化）。 */
        fun updateA11yView(view: View, lp: WindowManager.LayoutParams): Boolean {
            val service = instance ?: return false
            val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            return try {
                wm.updateViewLayout(view, lp)
                true
            } catch (_: Throwable) {
                false
            }
        }

        private fun remoteStatus(): Bundle? = remoteCall(METHOD_STATUS)

        private fun remoteCall(method: String, extras: Bundle? = null): Bundle? {
            val ctx = appContext ?: return null
            return try {
                ctx.contentResolver.call(bridgeUri(ctx), method, null, extras)
            } catch (_: Exception) {
                null
            }
        }

        private fun bridgeUri(context: Context): Uri {
            return Uri.parse("content://${context.packageName}$AUTHORITY_SUFFIX")
        }

        private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
            "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
        private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
        private const val EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"
    }
}
