package com.bettergi.pocket.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
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
        private const val KEY_CONNECTED = "connected"
        private const val KEY_LAST_PACKAGE = "last_package"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val KEY_DURATION = "duration"
        private const val KEY_OK = "ok"
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

        fun handleBridgeCall(method: String, extras: Bundle?): Bundle {
            return when (method) {
                METHOD_STATUS -> Bundle().apply {
                    putBoolean(KEY_CONNECTED, instance != null)
                    putString(KEY_LAST_PACKAGE, lastAppPackage)
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
