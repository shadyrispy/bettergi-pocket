package com.bettergi.pocket.log

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 全局识别日志汇（方案 §13.2.1）。
 *
 * **为何要抽出来**：原先写入端是 `OverlayWindowController.appendLog`（private），只服务自动对话，
 * 扫描/加锁/装备等 DSL 流程无法写入；且 `if (!logWindowVisible) return` 导致**关窗期间日志全丢**。
 * 本类把日志从 UI 解耦为进程内单例：
 * - **恒缓冲**：不论日志窗显隐均入环形缓冲 → 开窗即回放全部历史（修「关窗丢日志」）。
 * - **来源标签 + 分级**：[Tag] 区分流程，[Level] 控制密度（默认只记 I+W，D 级需开 [verbose]）。
 * - **线程安全**：写入方含扫描协程（Dispatchers.Default）与 a11y 回调（main）→ 加锁；
 *   监听器统一投递主线程渲染。
 *
 * 行宽按 260dp/10sp 等宽约 40 字符裁剪（详情仍走 logcat，不裁）。
 */
object RecognitionLog {
    /** 日志来源（对应五流程 + 自动对话 + 应用级提醒）。 */
    enum class Tag { AUTOSKIP, SCAN, LOCK, EQUIP, CHAR, APP }

    /** 级别：I=页/任务级（默认）、D=逐格/逐次识别、W=告警/失败。 */
    enum class Level { I, D, W }

    private const val MAX_LINES = 60
    private const val MAX_LINE_CHARS = 40

    /** 结构化日志条目（§13.5#6：着色与过滤需要 tag/level 元数据，不能只存拼接后的字符串）。 */
    data class Entry(
        val time: String,
        val tag: Tag,
        val level: Level,
        val message: String,
    ) {
        /** 单行渲染文本（与旧格式一致：`HH:mm:ss [TAG] L message`）。 */
        fun render(): String = "$time [${tag.name}] ${level.name} $message"
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    private val lock = Any()
    private val buffer = ArrayDeque<Entry>(MAX_LINES)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<(List<Entry>) -> Unit>()

    /** 可见标签集合（§13.5#6 过滤）。默认全开；改动会触发重渲染。 */
    private val visible = Tag.entries.toMutableSet()

    /** D 级（逐格/逐次）开关：扫描每页 21 格 × 字段，默认关闭以免刷屏。 */
    @Volatile
    var verbose: Boolean = false

    /**
     * 写一行。任意线程可调。
     * @param level D 级在 [verbose] 关闭时被丢弃；I/W 恒记录。
     */
    fun log(tag: Tag, level: Level, message: String) {
        if (level == Level.D && !verbose) return
        val body = message.replace('\n', ' ').let {
            if (it.length > MAX_LINE_CHARS) it.take(MAX_LINE_CHARS - 1) + "…" else it
        }
        val entry = Entry(timeFormat.format(Date()), tag, level, body)
        val snapshot: List<Entry>
        synchronized(lock) {
            while (buffer.size >= MAX_LINES) buffer.removeFirst()
            buffer.addLast(entry)
            snapshot = filteredLocked()
        }
        if (listeners.isEmpty()) return
        mainHandler.post { listeners.forEach { runCatching { it(snapshot) } } }
    }

    /** 便捷重载：默认 I 级。 */
    fun log(tag: Tag, message: String) = log(tag, Level.I, message)

    /** 当前可见条目快照（已按 [visible] 过滤）。 */
    fun entries(): List<Entry> = synchronized(lock) { filteredLocked() }

    /** 纯文本渲染（调试/导出用，不含着色）。 */
    fun text(): String = entries().joinToString("\n") { it.render() }

    /** 标签是否可见。 */
    fun isVisible(tag: Tag): Boolean = synchronized(lock) { tag in visible }

    /** 切换标签可见性 → 立即重渲染。 */
    fun setVisible(tag: Tag, on: Boolean) {
        val snapshot: List<Entry>
        synchronized(lock) {
            if (on) visible.add(tag) else visible.remove(tag)
            snapshot = filteredLocked()
        }
        mainHandler.post { listeners.forEach { runCatching { it(snapshot) } } }
    }

    /** 订阅渲染（回调在主线程，参数为可见条目）。返回句柄用于 [removeListener]。 */
    fun addListener(listener: (List<Entry>) -> Unit): (List<Entry>) -> Unit {
        mainHandler.post {
            synchronized(lock) { listeners.add(listener) }
            listener(filteredLocked())
        }
        return listener
    }

    fun removeListener(listener: (List<Entry>) -> Unit) {
        mainHandler.post { synchronized(lock) { listeners.remove(listener) } }
    }

    fun clear() {
        synchronized(lock) { buffer.clear() }
        mainHandler.post { listeners.forEach { runCatching { it(emptyList()) } } }
    }

    // ---- 跨进程投递（2026-09-18 悬浮窗宿主迁到无障碍进程后新增）----
    // 日志缓冲只存在于**写日志的那个进程**（主进程：扫描引擎/自动对话都在那儿）。
    // 无障碍进程的日志窗因此需要一份"镜像"：主进程给全量快照，无障碍进程本地按标签过滤渲染
    // ——过滤集留在渲染侧，两个进程各自维护，不再互相干扰。

    /** 全量快照（**不按可见标签过滤**）：供跨进程投递。 */
    fun snapshotAll(): List<Entry> = synchronized(lock) { buffer.toList() }

    /**
     * 用远端快照整体替换缓冲（仅镜像侧使用）。
     * @return 内容确有变化为 true；相同则原地返回 false（避免每秒无谓重渲染）。
     */
    fun replaceAll(entries: List<Entry>): Boolean {
        synchronized(lock) {
            if (buffer.size == entries.size && buffer.toList() == entries) return false
            buffer.clear()
            for (e in entries.takeLast(MAX_LINES)) buffer.addLast(e)
        }
        mainHandler.post { listeners.forEach { runCatching { it(filteredLocked()) } } }
        return true
    }

    /**
     * 跨进程**写入**（无障碍进程发来一条日志）。
     * 枚举名不合法就丢弃（不抛）——日志不该成为跨进程的崩溃点。
     */
    fun appendWire(tag: String, level: String, message: String): Boolean {
        val t = runCatching { Tag.valueOf(tag) }.getOrNull() ?: return false
        val l = runCatching { Level.valueOf(level) }.getOrNull() ?: return false
        log(t, l, message)
        return true
    }

    /** 条目 → 跨进程线格式（用不可见分隔符，避免与日志正文里的可见字符冲突）。 */
    fun encode(entry: Entry): String =
        entry.time + SEP + entry.tag.name + SEP + entry.level.name + SEP + entry.message

    /** 跨进程线格式 → 条目；字段数不对或枚举不认识则返回 null（丢弃脏数据，不抛）。 */
    fun decode(raw: String): Entry? {
        val parts = raw.split(SEP)
        if (parts.size < 4) return null
        val tag = runCatching { Tag.valueOf(parts[1]) }.getOrNull() ?: return null
        val level = runCatching { Level.valueOf(parts[2]) }.getOrNull() ?: return null
        // 正文里若混入分隔符，第 4 段之后要拼回去
        val message = if (parts.size > 4) parts.subList(3, parts.size).joinToString(SEP.toString()) else parts[3]
        return Entry(parts[0], tag, level, message)
    }

    private const val SEP = '\u0001'

    /** 调用方须持 [lock]。 */
    private fun filteredLocked(): List<Entry> = buffer.filter { it.tag in visible }
}
