package com.bettergi.pocket.vd

import android.content.Context
import android.content.ContextWrapper
import android.os.Process

/**
 * ★ #310 BS 上机实测修复（2026-10-17）：Shizuku UserService 进程（shell uid）里
 * `DisplayManager.createVirtualDisplay` 抛
 * `SecurityException: packageName must match the calling uid`。
 *
 * 根因：AOSP `DisplayManagerService.createVirtualDisplay` 校验
 * `validatePackageName(uid, packageName)` —— 包名必须属于调用 uid。shell uid (2000)
 * 只拥有 `com.android.shell`（BS 实测 `pm list packages --uid 2000` 唯一命中），
 * 而 Shizuku server 传进来的 context（createPackageContextAsUser 产物）的
 * opPackageName 是**我们的包名** → 校验失败。
 *
 * MAA-Meow 的过法（scrcpy FakeContext 同款）：造一个假 Context 包住真实 context，
 * `getPackageName()` / `getOpPackageName()` 返回 `com.android.shell` —— 恰好匹配
 * shell uid，校验通过。ContextWrapper 而不是直接子类化 Context（Context 有大量
 * final 成员与版本变体，手写子类全量 override 不可维护）。
 *
 * 边界（借来的环境凭证，红线同 BgiRemoteService）：
 * - **只用于 DisplayManager 创建**，不 registerReceiver / 不查 ContentProvider；
 * - 非 shell uid（root/Sui uid=0 不触发 packageName 校验）直通不包装。
 */
internal class VdShellContext private constructor(
    base: Context,
) : ContextWrapper(base) {

    companion object {
        /** shell uid 拥有的唯一包名（validatePackageName 唯一放行值）。 */
        const val SHELL_PACKAGE = "com.android.shell"

        /**
         * 服务进程入口：uid=2000（shell，Shizuku adb 模式）⇒ 包名伪装；
         * 其余 uid（root/Sui）直通不包装（root 不触发 packageName 校验）。
         */
        fun wrap(context: Context): Context {
            val uid = runCatching { Process.myUid() }.getOrDefault(-1)
            return if (uid == Process.SHELL_UID) VdShellContext(context) else context
        }
    }

    // ---- 校验点读的两个名字（DisplayManagerGlobal 写 binder 参数用 opPackageName）----
    override fun getPackageName(): String = SHELL_PACKAGE

    override fun getOpPackageName(): String = SHELL_PACKAGE

    override fun getAttributionTag(): String? = null

    // ---- getSystemService：DISPLAY_SERVICE 返回绑定假 context 的 DisplayManager ----
    // base 的实例绑的是原 context（内部把 context 传给 DisplayManagerGlobal 走
    // getOpPackageName），必须反射重建让它看到假包名。
    override fun getSystemService(name: String): Any? {
        val svc = super.getSystemService(name)
        if (name == Context.DISPLAY_SERVICE && svc is android.hardware.display.DisplayManager) {
            return runCatching {
                val ctor = android.hardware.display.DisplayManager::class.java
                    .getDeclaredConstructor(Context::class.java)
                ctor.isAccessible = true
                ctor.newInstance(this)
            }.getOrDefault(svc)
        }
        return svc
    }
}
