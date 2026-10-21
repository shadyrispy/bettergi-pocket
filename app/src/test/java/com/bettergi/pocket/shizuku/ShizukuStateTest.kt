package com.bettergi.pocket.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ★ #310/M0：Shizuku 四态状态机全表单测（纯 JVM，无 Robolectric）。
 *
 * [ShizukuState.assess] 的两个注入点（[ShizukuApi] 假实现 + `isInstalled` lambda）
 * 让判定表不需要 Android Context；Context 分支（[ShizukuState.isShizukuInstalled]
 * 的 PackageManager 查询）属薄封装，真机验证。
 *
 * 覆盖语义（与 ShizukuState.kt 的 KDoc 判定表一一对应）：
 * 1. 未装 → NOT_INSTALLED（**即使 api 报 granted** —— NOT_INSTALLED 优先于一切）
 * 2. 已装 + ping false → NOT_RUNNING
 * 3. 已装 + ping true + 未授权 → NOT_GRANTED
 * 4. 已装 + granted → READY，serverUid 0（root/Sui）与 2000（adb）两档都透传
 * 5. api 抛异常 → 按 NOT_RUNNING 处理（保守：判不出按「需要用户动作」，绝不假装 READY）
 */
class ShizukuStateTest {

    /** 可编程假 api：三个布尔/可空值，外加「每次调用都抛」的崩溃模式。 */
    private class FakeApi(
        var ping: Boolean = false,
        var granted: Boolean = false,
        var uid: Int? = null,
        var throwEverything: Boolean = false,
    ) : ShizukuApi {
        override fun pingBinder(): Boolean =
            if (throwEverything) error("binder 未连接（Shizuku 静态面常态异常）") else ping

        override fun isGranted(): Boolean =
            if (throwEverything) error("binder 未连接") else granted

        override fun serverUid(): Int? =
            if (throwEverything) error("binder 未连接") else uid
    }

    private fun assess(
        installed: Boolean,
        ping: Boolean = false,
        granted: Boolean = false,
        uid: Int? = null,
    ) = ShizukuState.assess(FakeApi(ping, granted, uid)) { installed }

    // ── 1. NOT_INSTALLED 优先于一切 ─────────────────────────────

    @Test
    fun not_installed_wins_even_if_api_claims_granted() {
        // 装都没装，ping 一定 false，光看 binder 区分不出「未装/未启动」——
        // 但假实现和边角时序里 api 可能报 granted，状态机仍必须给 NOT_INSTALLED。
        val status = assess(installed = false, ping = true, granted = true, uid = 0)
        assertEquals(ShizukuPhase.NOT_INSTALLED, status.phase)
        assertNull(status.serverUid)
    }

    @Test
    fun not_installed_with_all_signals_off() {
        val status = assess(installed = false)
        assertEquals(ShizukuPhase.NOT_INSTALLED, status.phase)
        assertNull(status.serverUid)
    }

    // ── 2. 已装 + ping false → NOT_RUNNING ──────────────────────

    @Test
    fun installed_but_binder_down_is_not_running() {
        val status = assess(installed = true, ping = false)
        assertEquals(ShizukuPhase.NOT_RUNNING, status.phase)
        assertNull(status.serverUid)
    }

    // ── 3. ping true + 未授权 → NOT_GRANTED ─────────────────────

    @Test
    fun installed_and_running_but_ungranted() {
        val status = assess(installed = true, ping = true, granted = false)
        assertEquals(ShizukuPhase.NOT_GRANTED, status.phase)
        assertNull(status.serverUid)
    }

    // ── 4. granted → READY（uid 两档）──────────────────────────

    @Test
    fun granted_with_root_uid_is_ready() {
        // root / Sui 通道：getUid() = 0
        val status = assess(installed = true, ping = true, granted = true, uid = 0)
        assertEquals(ShizukuPhase.READY, status.phase)
        assertEquals(0, status.serverUid)
    }

    @Test
    fun granted_with_adb_uid_is_ready() {
        // 无线调试通道：getUid() = 2000
        val status = assess(installed = true, ping = true, granted = true, uid = 2000)
        assertEquals(ShizukuPhase.READY, status.phase)
        assertEquals(2000, status.serverUid)
    }

    @Test
    fun granted_but_uid_unavailable_still_ready() {
        // 授权是硬判定；uid 只是附加信息（M2 区分 root/adb 用），缺了不降级
        val status = assess(installed = true, ping = true, granted = true, uid = null)
        assertEquals(ShizukuPhase.READY, status.phase)
        assertNull(status.serverUid)
    }

    // ── 5. api 异常 → 保守按 NOT_RUNNING ────────────────────────

    @Test
    fun api_throwing_is_treated_as_not_running() {
        // PROD_API 把异常折叠成 ping=false/granted=false/uid=null；这里直接验证
        // 判定主体面对「调用即抛」的 api 也绝不允许 READY / NOT_GRANTED 冒头
        // —— 这两个态都以 binder 活着为前提。
        val crash = FakeApi(throwEverything = true)
        val status = ShizukuState.assess(crash) { true }
        assertEquals(ShizukuPhase.NOT_RUNNING, status.phase)
        assertNull(status.serverUid)
    }

    // ── 边角：isInstalled 抛异常也按未装（保守原则对称）────────

    @Test
    fun installed_check_throwing_is_treated_as_not_installed() {
        val status = ShizukuState.assess(FakeApi(ping = true, granted = true, uid = 0)) {
            throw IllegalStateException("PackageManager 查询失败")
        }
        assertEquals(ShizukuPhase.NOT_INSTALLED, status.phase)
        assertNull(status.serverUid)
    }
}
