package com.bettergi.pocket.vd

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.bettergi.pocket.genshin.GenshinPackages
import com.bettergi.pocket.scan.GenshinStateProbe

/**
 * ★ #310/M3：后台模式的前台状态探针——判据从"无障碍窗口事件流"换成
 * "虚拟屏任务表 + 顶层 Activity 类名"（`getRunningTasks(100)` + 反射
 * `RunningTaskInfo.displayId`（hidden 字段）与 `topActivity`（public））。
 *
 * 判定（与前台版语义逐位对齐，`GenshinStateProbe` 契约不变）：
 * - `isGameInForeground()`：VD 顶层 task 的包名是原神且类名非 SDK 页 ⇒ true；
 *   VD 上没有 task（Empty）或顶层非原神 ⇒ false；**判不出（反射失败/权限被拒）⇒ null**
 *   （保守三原则之一：Unknown 折叠成 fail-open/不判死，与前台版 null 契约同构）。
 * - `isSdkLoginOnTop()`：VD 顶层类名是 SdkActivity ⇒ true；判不出 ⇒ null（同上）。
 *
 * 轮询节奏由消费方决定：死亡哨兵 5s/拍（够）；S4a 窗口 ~1.4s ⇒ 探针自身无节流，
 * M4 的监视区以 250ms 采样兜 S4a（MAA 文档实证 SdkActivity 只闪 ~1.4s）。
 */
class VdStateProbe(
    private val context: Context,
    private val displayId: Int,
) : GenshinStateProbe {

    companion object {
        private const val TAG = "BetterGI.VD"

        /** 反射读取 RunningTaskInfo.displayId（hidden 字段，提权进程可读）。 */
        private fun taskDisplayId(info: ActivityManager.RunningTaskInfo): Int? = runCatching {
            val f = ActivityManager.RunningTaskInfo::class.java.getField("displayId")
            f.getInt(info)
        }.getOrElse {
            Log.w(TAG, "RunningTaskInfo.displayId 反射失败: ${it.message}")
            null
        }

        /** 本 VD 上的顶层 task（null = 判不出）。 */
        fun topTaskOnDisplay(context: Context, displayId: Int): Pair<String?, String?>? {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
            return runCatching {
                val tasks = am.getRunningTasks(100)
                val onVd = tasks.mapNotNull { t -> taskDisplayId(t)?.let { t to it } }
                    .firstOrNull { it.second == displayId }?.first ?: return Pair(null, null)
                // topActivity 是 public 字段（ComponentName）
                val top = onVd.topActivity
                Pair(top?.packageName, top?.className)
            }.getOrElse {
                Log.w(TAG, "getRunningTasks 失败: ${it.message}")
                null
            }
        }
    }

    override fun isGameInForeground(): Boolean? {
        val top = topTaskOnDisplay(context, displayId) ?: return null
        val (pkg, cls) = top
        if (pkg == null) {
            // VD 上没有 task = 虚拟屏空（游戏死了/被杀）⇒ 明确的 false
            return false
        }
        return GenshinPackages.isGameForeground(pkg, cls)
    }

    override fun isSdkLoginOnTop(): Boolean? {
        val top = topTaskOnDisplay(context, displayId) ?: return null
        val (_, cls) = top
        return GenshinPackages.isSdkActivity(cls)
    }
}
