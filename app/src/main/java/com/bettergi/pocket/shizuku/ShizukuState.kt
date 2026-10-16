package com.bettergi.pocket.shizuku

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * ★ #310/M0 后台模式（virtualdisplay 分支）：Shizuku 授权**四态状态机**。
 *
 * 引导叙事抄 MAA-Meow 的 ShizukuManager（文档站实证的形态）：用户眼里是一条
 * 线性动线「装 Shizuku → 启动它 → 授权给我们」，每一态给出**恰好一个**下一步
 * 动作，完成信号明确（不猜、不轮询黑盒）：
 *
 * | 态                 | 判定                            | 按钮语义（M2 接 UI）      |
 * |--------------------|---------------------------------|---------------------------|
 * | [NOT_INSTALLED]    | 包管理器查不到 Shizuku 应用     | 获取 Shizuku              |
 * | [NOT_RUNNING]      | 已装，但 `pingBinder()` = false | 打开 Shizuku 应用         |
 * | [NOT_GRANTED]      | binder 活着，但未授权           | 授权                       |
 * | [READY]            | 已授权                          | 显示虚拟屏开关            |
 *
 * ## 判定优先级（语义要点，写死在 [assess] 里）
 * 1. **NOT_INSTALLED 优先于一切** —— 装都没装时 `pingBinder()` 一定 false，光看
 *    binder 区分不出「未装」和「已装未启动」，所以先查包名。
 * 2. 已安装 + binder 不通 = NOT_RUNNING。
 * 3. binder 通 + `checkSelfPermission() != PERMISSION_GRANTED` = NOT_GRANTED。
 * 4. 授权了 = READY，附带 `serverUid`（0=root/Sui，2000=adb —— M2 起区分能力）。
 * 5. **任何 Shizuku API 异常一律按 NOT_RUNNING 处理**（保守原则：判不出就按
 *    「需要用户动作」对待，绝不假装 READY —— 虚拟屏开到一半才发现没权限，
 *    比让用户多点一次按钮贵得多）。binder 未连接时 `Shizuku.*` 静态方法会抛
 *    IllegalStateException，这是**常态不是错误**，[PROD_API] 里 runCatching 吃掉。
 *
 * ## 可测性设计
 * Shizuku 调用全部收敛到 [ShizukuApi] 接口（薄的适配层），单测注入假实现即可
 * 覆盖全判定表；安装判定同样以 `isInstalled: () -> Boolean` 注入 —— 纯 JVM 单测
 * 不需要 Android Context（[assess] 主体不带 Context，[assess] 的 Context 重载
 * 才是生产入口）。
 */
enum class ShizukuPhase {
    /** 未装：按钮 = 获取 Shizuku（内置 apk 导出安装或跳 GitHub 下载，见 ShizukuInstallHelper）。 */
    NOT_INSTALLED,

    /** 已装未运行：按钮 = 打开 Shizuku 应用（用户在里头无线调试/Root 启动服务）。 */
    NOT_RUNNING,

    /** 已运行未授权：按钮 = 授权（`Shizuku.requestPermission` + 结果监听，M2 接 UI）。 */
    NOT_GRANTED,

    /** 已授权：显示虚拟屏开关（M2 接 UI）。 */
    READY,
}

/**
 * Shizuku 原生 API 的可注入面（单测用假实现；生产默认 [ShizukuState.PROD_API] 真调 `rikka.shizuku.Shizuku.*`）。
 *
 * 对应关系（MAA-Meow ShizukuManager 同款调用序列）：
 * - [pingBinder] ← `Shizuku.pingBinder()`
 * - [isGranted] ← `Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED`
 * - [serverUid] ← `Shizuku.getUid()`（0=root/Sui，2000=adb；binder 不通时返回 null）
 */
interface ShizukuApi {
    /** 服务是否在跑（binder 是否连通）。 */
    fun pingBinder(): Boolean

    /** 本 app 是否已获授权。 */
    fun isGranted(): Boolean

    /** 服务端身份：0=root/Sui，2000=adb；不可用（binder 断/异常）返回 null。 */
    fun serverUid(): Int?
}

/** 一次评估的快照（值对象，UI 拿去直接渲染，不回查 Shizuku）。 */
data class ShizukuStatus(
    val phase: ShizukuPhase,
    /** READY 时的服务身份：0=root/Sui，2000=adb。其余态恒 null（没意义，别让 UI 猜）。 */
    val serverUid: Int?,
)

/**
 * 四态机本体。object + 纯函数，无状态：每次 [assess] 都是对当下世界的一次快照判定，
 * 不缓存、不推送（变更监听 `addBinderReceivedListenerSticky` 等 M2 接 UI 时再加）。
 */
object ShizukuState {

    /** Shizuku 官方应用包名（queries 已声明，targetSdk 30+ 包可见性过滤放行）。 */
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /**
     * 生产实现：直接调 `rikka.shizuku.Shizuku`。所有调用 runCatching 包裹 ——
     * binder 未连接时 Shizuku 静态方法抛 IllegalStateException 是常态（「已装
     * 未启动」正是这种），任何异常都折叠成「没服务」三件套（ping=false /
     * granted=false / uid=null），交给 [assess] 判成 NOT_RUNNING。
     */
    val PROD_API: ShizukuApi = object : ShizukuApi {
        override fun pingBinder(): Boolean =
            runCatching { Shizuku.pingBinder() }.getOrDefault(false)

        override fun isGranted(): Boolean =
            runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)

        override fun serverUid(): Int? =
            runCatching { Shizuku.getUid() }.getOrNull()
    }

    /**
     * 是否安装了 Shizuku 应用。`getPackageInfo` 抛 NameNotFoundException 即未装；
     * 查询失败（极少见）也按未装处理 —— 保守原则同 [assess]：宁可让用户走一遍
     * 「获取 Shizuku」动线，也不要在判不出的态里卡死。
     */
    fun isShizukuInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * 判定当前四态（生产入口）：安装判定用 [Context]，运行/授权判定走 [PROD_API]。
     */
    fun assess(context: Context, api: ShizukuApi = PROD_API): ShizukuStatus =
        assess(api) { isShizukuInstalled(context) }

    /**
     * 判定主体（纯 JVM 可测，无 Android 依赖）：安装判定也注入化，单测用 lambda
     * 覆盖全表，不需要 Context / Robolectric。
     *
     * 对调用方的承诺是**永不抛**（全函数）：三个注入点（[ShizukuApi] 三个方法 +
     * `isInstalled`）全部 runCatching 兜底 —— [PROD_API] 已经按调用折叠过 binder
     * 未连接的常态异常，这里是第二层保险：任何一层抛出（假实现、PackageManager
     * 瞬时失败、未来换 api 实现）都折叠成保守值，UI 拿到的永远是四态之一。
     *
     * 注意 NOT_INSTALLED 优先于一切 —— 即使 api 报 granted（理论上不可能，但假
     * 实现和边角时序里会出现），没装就是没装，状态机不给出超越前提的结论。
     */
    fun assess(api: ShizukuApi, isInstalled: () -> Boolean): ShizukuStatus {
        if (!runCatching(isInstalled).getOrDefault(false)) {
            return ShizukuStatus(ShizukuPhase.NOT_INSTALLED, serverUid = null)
        }
        if (!runCatching(api::pingBinder).getOrDefault(false)) {
            return ShizukuStatus(ShizukuPhase.NOT_RUNNING, serverUid = null)
        }
        if (!runCatching(api::isGranted).getOrDefault(false)) {
            return ShizukuStatus(ShizukuPhase.NOT_GRANTED, serverUid = null)
        }
        // READY：serverUid 抓不到（null）也照样 READY —— 授权是硬判定，uid 只是
        // 附加信息（M2 区分 root/adb 能力用），缺了不降级、不误报。
        return ShizukuStatus(ShizukuPhase.READY, serverUid = runCatching(api::serverUid).getOrNull())
    }
}
