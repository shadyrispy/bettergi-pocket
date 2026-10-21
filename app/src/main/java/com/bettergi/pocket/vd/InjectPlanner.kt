package com.bettergi.pocket.vd

import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * ★ #310/M3：注入事件规划器（纯逻辑，无 Android 运行时依赖——MotionEvent 仅用于构造）。
 *
 * 借鉴 MAA-Meow `TouchPointerSequence` 的槽位表状态机语义（照思路重写，不搬代码）：
 * - `slots` 镜像"系统侧 believed down"的手指（contact → pointer id）；
 * - **注入失败则槽位不动**（调用方把 injectInputEvent 的返回值交给 [plan] 的结果处置）；
 * - 同一 contact 再次按下且槽位有残留 ⇒ 规划 `CANCEL 全部残留 → 全新 DOWN`（自愈）；
 * - DOWN 用同步模式（`WAIT_FOR_FINISH`），MOVE/UP 用异步——起始状态必须被系统确认，
 *   热路径不阻塞（失败自愈由下一次 DOWN 的 cancelFirst 兜底）。
 *
 * Android 原神用不到多指（我们的流程全是单指 tap/swipe），但保留槽位表：
 * swipe 的 DOWN…MOVE…UP 若中途某段注入失败，槽位表能正确描述"系统认为手指还按着"，
 * 下一次点击的 CANCEL 才能把系统态清干净（否则 phantom touch 卡住游戏）。
 */
class InjectPlanner {

    /** 槽位表：系统侧 believed down 的 contact → pointerId。 */
    private val slots = LinkedHashMap<Int, Int>()

    /** gestureDownTime：slots 从空到非空时重置（一次手势全体共用一个 downTime）。 */
    var downTime: Long = 0L
        private set

    fun residualCount(): Int = slots.size

    /** 规划一次 DOWN。返回要注入的 MOVE 事件序列（cancelFirst 时含前置 CANCEL）与是否同步模式。 */
    fun planDown(contact: Int, pointerId: Int, x: Float, y: Float, nowMs: Long): List<Planned> {
        val actions = ArrayList<Planned>(2)
        if (contact in slots) {
            // 自愈：系统侧该 contact 还按着（上次 UP 丢失）⇒ 先 CANCEL 全部，再干净 DOWN
            actions += plannedCancel(nowMs)
            slots.clear()
        }
        if (slots.isEmpty()) {
            downTime = nowMs
            actions += Planned.down(contact, pointerId, x, y, nowMs, async = false) // DOWN 同步
        } else {
            actions += Planned.pointerDown(contact, pointerId, x, y, nowMs, async = false)
        }
        slots[contact] = pointerId
        return actions
    }

    /** 规划一次 MOVE（要求 contact 在场——不在场说明系统态已乱，返回空由调用方记失败）。 */
    fun planMove(contact: Int, x: Float, y: Float, nowMs: Long): List<Planned> {
        if (contact !in slots) return emptyList()
        return listOf(Planned.move(contact, slots.getValue(contact), x, y, downTime, nowMs))
    }

    /** 规划 UP（唯一在按的手指 → ACTION_UP，多指 → POINTER_UP）。 */
    fun planUp(contact: Int, x: Float, y: Float, nowMs: Long): List<Planned> {
        val pid = slots[contact] ?: return emptyList()
        val actions = ArrayList<Planned>(1)
        if (slots.size == 1) {
            actions += Planned.up(contact, pid, x, y, downTime, nowMs)
        } else {
            actions += Planned.pointerUp(contact, pid, x, y, downTime, nowMs)
        }
        slots.remove(contact)
        return actions
    }

    /** 显式 CANCEL（清系统态 + 清表）。槽位空 = 无操作。 */
    fun planCancelAll(nowMs: Long): List<Planned> {
        if (slots.isEmpty()) return emptyList()
        val a = plannedCancel(nowMs)
        slots.clear()
        return listOf(a)
    }

    /** 注入失败回调：槽位不动（planX 已经只在新事件真正构造后改表——失败处置是**不删**）。 */
    fun onInjectFailed(action: Planned) {
        Log.w(TAG, "inject failed: ${action.maskName()} contact=${action.contact} ⇒ 槽位保持（下次同 contact DOWN 自愈）")
    }

    // ── 事件构造 ──

    private fun plannedCancel(nowMs: Long): Planned = Planned(
        mask = MotionEvent.ACTION_CANCEL, contact = -1, pointerId = -1,
        x = 0f, y = 0f, downTime = downTime, eventTime = nowMs, async = false,
    )

    /** 一次"要注入的事件"描述（MotionEvent 由调用方在 Android 侧构造——纯 JVM 可单测）。 */
    class Planned(
        val mask: Int,
        val contact: Int,
        val pointerId: Int,
        val x: Float,
        val y: Float,
        val downTime: Long,
        val eventTime: Long,
        /** false = 注入用 WAIT_FOR_FINISH（DOWN），true = ASYNC（MOVE/UP）。 */
        val async: Boolean,
    ) {
        companion object {
            fun down(c: Int, pid: Int, x: Float, y: Float, now: Long, async: Boolean) =
                Planned(MotionEvent.ACTION_DOWN, c, pid, x, y, now, now, async)
            fun pointerDown(c: Int, pid: Int, x: Float, y: Float, now: Long, async: Boolean) =
                Planned(MotionEvent.ACTION_POINTER_DOWN, c, pid, x, y, now, now, async)
            fun move(c: Int, pid: Int, x: Float, y: Float, downT: Long, now: Long) =
                Planned(MotionEvent.ACTION_MOVE, c, pid, x, y, downT, now, async = true)
            fun up(c: Int, pid: Int, x: Float, y: Float, downT: Long, now: Long) =
                Planned(MotionEvent.ACTION_UP, c, pid, x, y, downT, now, async = true)
            fun pointerUp(c: Int, pid: Int, x: Float, y: Float, downT: Long, now: Long) =
                Planned(MotionEvent.ACTION_POINTER_UP, c, pid, x, y, downT, now, async = true)
        }

        fun maskName(): String = when (mask) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
            MotionEvent.ACTION_MOVE -> "MOVE"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            else -> "mask=$mask"
        }

        /** 构造单指 MotionEvent（Android 侧调用；slots 多指在 M3 只用单指路径，先保正确性）。 */
        fun toMotionEvent(displayId: Int): MotionEvent {
            val props = arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = if (pointerId >= 0) pointerId else 0
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                },
            )
            val coords = arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = x; this.y = y
                    pressure = if (mask == MotionEvent.ACTION_UP || mask == MotionEvent.ACTION_CANCEL) 0f else 1f
                    size = 1f
                },
            )
            // ★ 2026-10-18 根因修复（BS 实测，dumpsys input RecentQueue 抓到的证据）：
            //   多指重载 obtain(..., pointerCount, props, coords, ...) 在 BS 上**坐标被清零**
            //   （事件确实进 RecentQueue、policyFlags/时间戳都正常，但 `pointers=[0: (0.0, 0.0)]`）
            //   ⇒ 点击/滑动全部落在屏幕左上角，表现为"注入受理（ret=true）但毫无效果"。
            //   而 adb `input` 命令的事件坐标正常 —— 长期被误判成"进程内注入不落地/被丢弃"。
            //   改用**单点标量 14 参 obtain**（Kotlin 重载解析会绑到多指版，故用反射显式选取）
            //   后，进程内注入在 VD 上立即落地（实测：点「点击进入」把游戏从标题屏送进世界）。
            val pressure = if (mask == MotionEvent.ACTION_UP || mask == MotionEvent.ACTION_CANCEL) 0f else 1f
            val ev: MotionEvent = runCatching {
                val m = MotionEvent::class.java.getMethod(
                    "obtain",
                    java.lang.Long.TYPE, java.lang.Long.TYPE, java.lang.Integer.TYPE,
                    java.lang.Float.TYPE, java.lang.Float.TYPE,
                    java.lang.Float.TYPE, java.lang.Float.TYPE,
                    java.lang.Integer.TYPE, java.lang.Float.TYPE, java.lang.Float.TYPE,
                    java.lang.Integer.TYPE, java.lang.Integer.TYPE,
                    java.lang.Integer.TYPE, java.lang.Integer.TYPE,
                )
                m.invoke(
                    null,
                    downTime, eventTime, mask,
                    x, y, pressure, 1f,
                    0, 1f, 1f,
                    0, 0,
                    InputDevice.SOURCE_TOUCHSCREEN, 0,
                ) as MotionEvent
            }.getOrElse {
                // 兜底：多指重载（坐标在部分 ROM 上会清零，但至少不崩）
                MotionEvent.obtain(
                    downTime, eventTime, mask, 1, props, coords,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
                )
            }
            // 反射 setDisplayId 在调用方（InputManager wrapper）做——保持本类零反射可单测
            return ev
        }
    }

    companion object {
        private const val TAG = "BetterGI.VD"
    }
}
