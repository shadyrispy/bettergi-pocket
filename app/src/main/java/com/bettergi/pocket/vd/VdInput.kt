package com.bettergi.pocket.vd

import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.KeyEvent

/**
 * ★ #310/M3：`injectInputEvent` 的 hidden API 反射壳（形态参考 scrcpy 的 InputManager wrapper，
 * Apache-2.0；只照结构不搬代码）。
 *
 * 必须在**提权进程**（shell/root uid）运行——注入权限 INJECT_EVENTS 由 uid 自带。
 * 反射两条链：
 * 1. `InputManager.getInstance().injectInputEvent(event, mode)` —— API 31+ 公开实例方法
 *    （实际是 hidden：public `InputManager.getInstance()` 在 31 引入，injectInputEvent 是
 *    @hide）。反射 `android.hardware.input.InputManager#getInstance` + `injectInputEvent`。
 * 2. `InputEvent.setDisplayId(int)` —— hidden 方法，反射调用；把事件打到目标 displayId。
 *    AndroidHiddenApiBypass（M0 依赖）兜被 meta-restriction 拦的方法。
 */
object VdInput {

    private const val TAG = "BetterGI.VD"

    /** 注入模式：DOWN 用 WAIT_FOR_FINISH（起始状态必须被系统确认），其余 ASYNC。 */
    const val MODE_WAIT_FOR_FINISH = 2
    const val MODE_ASYNC = 0

    @Volatile
    private var inputManagerInstance: Any? = null
    @Volatile
    private var injectMethod: java.lang.reflect.Method? = null
    @Volatile
    private var setDisplayIdMethod: java.lang.reflect.Method? = null

    /** 初始化反射（提权进程启动后调一次；失败打日志，后续 inject 返回 false）。 */
    fun init(): Boolean {
        try {
            val imClass = Class.forName("android.hardware.input.InputManager")
            val getInstance = imClass.getDeclaredMethod("getInstance")
            inputManagerInstance = getInstance.invoke(null)
            injectMethod = imClass.getDeclaredMethod(
                "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType,
            )
            setDisplayIdMethod = InputEvent::class.java.getDeclaredMethod(
                "setDisplayId", Int::class.javaPrimitiveType,
            )
            Log.i(TAG, "vd input reflection ok (api=${Build.VERSION.SDK_INT})")
            return true
        } catch (e: Throwable) {
            Log.w(TAG, "vd input reflection failed: ${e.message}")
            // HiddenApiBypass 兜底：逐个方法重试
            try {
                val imClass = Class.forName("android.hardware.input.InputManager")
                inputManagerInstance = org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(
                    imClass, null, "getInstance",
                )
                injectMethod = org.lsposed.hiddenapibypass.HiddenApiBypass.getDeclaredMethod(
                    imClass, "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType,
                ) as java.lang.reflect.Method
                setDisplayIdMethod = org.lsposed.hiddenapibypass.HiddenApiBypass.getDeclaredMethod(
                    InputEvent::class.java, "setDisplayId", Int::class.javaPrimitiveType,
                ) as java.lang.reflect.Method
                Log.i(TAG, "vd input reflection ok via bypass")
                return true
            } catch (e2: Throwable) {
                Log.w(TAG, "vd input bypass also failed: ${e2.message}")
                return false
            }
        }
    }

    /** 反射设置事件目标屏（displayId == 0 时跳过——主屏无需反射）。设完读回校验并打日志。 */
    fun setDisplayId(event: InputEvent, displayId: Int): Boolean {
        if (displayId == 0) return true
        return runCatching {
            setDisplayIdMethod?.invoke(event, displayId)
            val readBack = runCatching {
                event.javaClass.getMethod("getDisplayId").invoke(event) as Int
            }.getOrDefault(-99)
            // 读回校验：invoke 不抛 ≠ 生效（2026-10-18 BS：engine 模式 ok=true 但事件
            // 去向不明 —— 把「写了什么」变成证据）
            Log.d(TAG, "setDisplayId want=$displayId got=$readBack")
            readBack == displayId
        }.getOrElse {
            Log.w(TAG, "setDisplayId failed: ${it.message}")
            false
        }
    }

    /** 注入一个事件；返回系统是否受理。 */
    fun inject(event: InputEvent, async: Boolean): Boolean {
        val im = inputManagerInstance ?: return false
        val m = injectMethod ?: return false
        return runCatching {
            m.invoke(im, event, if (async) MODE_ASYNC else MODE_WAIT_FOR_FINISH) == true
        }.getOrElse {
            Log.w(TAG, "injectInputEvent failed: ${it.message}")
            false
        }
    }

    /** 快捷：注入一个已规划好的事件（先 setDisplayId 再 inject；失败回执给 planner）。 */
    fun injectPlanned(planned: InjectPlanner.Planned, displayId: Int, planner: InjectPlanner): Boolean {
        val ev = planned.toMotionEvent(displayId)
        return try {
            if (!setDisplayId(ev, displayId)) return false
            inject(ev, planned.async)
        } finally {
            runCatching { ev.recycle() }
        }
    }
}
