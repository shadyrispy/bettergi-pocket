package com.bettergi.pocket.settings

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * **无障碍进程侧**的设置仓库：经 [SettingsBridgeProvider] 代理主进程的权威实例。
 *
 * 行为约定（与主进程 [TriggerSettingsRepository] 同接口，UI 代码无需感知进程边界）：
 * - [get] 返回**本地缓存**（异步刷新，不阻塞 UI 线程）。
 * - 写操作先同步下发主进程，成功即**乐观更新本地缓存**并通知本地监听器（悬浮窗即时反馈）；
 *   随后主进程的 `notifyChange` 会回来再刷一次（权威校正）。
 * - 主进程写了设置（例如通知栏开关）⇒ `notifyChange` ⇒ [refresh] ⇒ 本地监听器 → 悬浮窗自动跟随。
 *
 * ⚠️ 主进程未起（服务没跑）时写会失败：此时悬浮窗本就无意义，只记一行日志，不抛异常。
 */
class BridgeSettingsRepository(private val context: Context) : SettingsGateway {

    private val uri: Uri = SettingsBridgeProvider.uri(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(TriggerSettings) -> Unit>()

    @Volatile
    private var cached: TriggerSettings = SettingsWire.DEFAULTS

    private var observer: ContentObserver? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                refresh()
            }
        }.also {
            runCatching { context.contentResolver.registerContentObserver(uri, false, it) }
        }
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        observer?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
        observer = null
    }

    override fun get(): TriggerSettings = cached

    override fun addListener(listener: (TriggerSettings) -> Unit) {
        listeners.add(listener)
        mainHandler.post { listener(cached) }
    }

    override fun removeListener(listener: (TriggerSettings) -> Unit) {
        listeners.remove(listener)
    }

    override fun setScreenShareEnabled(enabled: Boolean) = setField(
        SettingsWire.F_SCREEN_SHARE, Bundle().apply { putBoolean(SettingsBridgeProvider.KEY_VALUE, enabled) },
    ) { it.copy(screenShareEnabled = enabled) }

    override fun setAutoPickEnabled(enabled: Boolean) = setField(
        SettingsWire.F_AUTO_PICK, Bundle().apply { putBoolean(SettingsBridgeProvider.KEY_VALUE, enabled) },
    ) { it.copy(autoPickEnabled = enabled) }

    override fun setAutoSkipEnabled(enabled: Boolean) = setField(
        SettingsWire.F_AUTO_SKIP, Bundle().apply { putBoolean(SettingsBridgeProvider.KEY_VALUE, enabled) },
    ) { it.copy(autoSkipEnabled = enabled) }

    override fun setQuickSkipDialogueEnabled(enabled: Boolean) = setField(
        SettingsWire.F_QUICK_SKIP, Bundle().apply { putBoolean(SettingsBridgeProvider.KEY_VALUE, enabled) },
    ) { it.copy(quickSkipDialogueEnabled = enabled) }

    override fun setAutoLaunchGenshinEnabled(enabled: Boolean) = setField(
        SettingsWire.F_AUTO_LAUNCH, Bundle().apply { putBoolean(SettingsBridgeProvider.KEY_VALUE, enabled) },
    ) { it.copy(autoLaunchGenshinEnabled = enabled) }

    override fun setScanEnabled(enabled: Boolean) = setField(
        SettingsWire.F_SCAN, Bundle().apply { putBoolean(SettingsBridgeProvider.KEY_VALUE, enabled) },
    ) { it.copy(scanEnabled = enabled) }

    override fun setScanFlow(flow: String) = setField(
        SettingsWire.F_SCAN_FLOW, Bundle().apply { putString(SettingsBridgeProvider.KEY_VALUE, flow) },
    ) { it.copy(scanFlow = flow) }

    override fun setScanMaxPages(pages: Int) = setField(
        SettingsWire.F_SCAN_MAX_PAGES, Bundle().apply { putInt(SettingsBridgeProvider.KEY_VALUE, pages) },
    ) { it.copy(scanMaxPages = pages) }

    /**
     * 把一条日志写给主进程（**单一日志源**在主进程）。
     * 悬浮窗在无障碍进程里也有一个 [RecognitionLog] 单例，但它的缓冲会被日志镜像
     * （`log_snapshot` → `replaceAll`）整体覆盖 ⇒ 无障碍侧写的行必须先送回主进程，否则会被冲掉。
     */
    fun appendLog(tag: String, level: String, message: String): Boolean =
        call(
            SettingsBridgeProvider.METHOD_LOG_APPEND,
            null,
            Bundle().apply {
                putString(SettingsBridgeProvider.KEY_TAG, tag)
                putString(SettingsBridgeProvider.KEY_LEVEL, level)
                putString(SettingsBridgeProvider.KEY_MESSAGE, message)
            },
        )?.getBoolean(SettingsBridgeProvider.KEY_OK) == true

    /** 请求主进程导出 GOOD（悬浮窗「开始导出」按钮）。 */
    fun requestShareGood(): Boolean = call(SettingsBridgeProvider.METHOD_SHARE_GOOD, null, null) != null

    /** 请求主进程停服务（悬浮窗「退出」按钮）。 */
    fun requestStop(): Boolean = call(SettingsBridgeProvider.METHOD_STOP, null, null) != null

    private inline fun setField(
        field: String,
        extras: Bundle,
        crossinline optimistic: (TriggerSettings) -> TriggerSettings,
    ) {
        val ok = call(SettingsBridgeProvider.METHOD_SET, field, extras)?.getBoolean(SettingsBridgeProvider.KEY_OK) == true
        if (ok) {
            // 乐观更新：UI 不等一次往返（主进程随后 notifyChange 会给权威值再校正一次）
            val next = optimistic(cached)
            if (next != cached) {
                cached = next
                notifyListeners()
            }
        } else {
            Log.w(TAG, "set $field rejected (主进程未持有设置实例？)")
        }
    }

    /** 拉取权威值；变化才通知（避免每 700ms 轮询式刷新刷爆 UI）。 */
    fun refresh() {
        val bundle = call(SettingsBridgeProvider.METHOD_GET, null, null) ?: return
        val payload = bundle.getBundle(SettingsBridgeProvider.KEY_SETTINGS) ?: return
        val fresh = SettingsWire.fromBundle(payload)
        if (fresh == cached) return
        cached = fresh
        notifyListeners()
    }

    private fun notifyListeners() {
        val snapshot = cached
        mainHandler.post { listeners.forEach { runCatching { it(snapshot) } } }
    }

    private fun call(method: String, arg: String?, extras: Bundle?): Bundle? = try {
        context.contentResolver.call(uri, method, arg, extras)
    } catch (e: Exception) {
        Log.w(TAG, "bridge call $method failed", e)
        null
    }

    private companion object {
        const val TAG = "BetterGI.SettingsBridge"
    }
}
