package com.bettergi.pocket.scan

import com.bettergi.pocket.capture.FrameSource

/**
 * ★ #310/M6-audit：一次扫描的**装配解析结果**。
 *
 * 由 ScriptRunner 在 `startScan` 入口解析一次、整个扫描期冻结（扫描单飞 ⇒ 中途不可能换装配；
 * "设置改了下一轮生效"与既有语义一致）。
 *
 * 为什么不在构造期定死：ScriptRunner 是服务级单例（随 TriggerForegroundService 一生），
 * 而 runMode 是用户可随时切换的设置；构造期定死则"切了模式不重启 app 不生效"。
 */
sealed class AssemblyResolution {
    /** 前台装配（null 解析器 / FOREGROUND 模式）：走 ScriptRunner 内置的投影+无障碍链。 */
    object Foreground : AssemblyResolution()

    /** 后台装配：三件套 + 校验策略全部来自 BackgroundSession。 */
    class Background(
        val frameSource: FrameSource,
        val probe: GenshinStateProbe,
        val actions: ScanEngine.ActionGateway,
        /** 就绪校验：null = 就绪；否则 = 拒绝理由。 */
        val readiness: () -> String?,
    ) : AssemblyResolution()

    /** 后台模式已选但会话未就绪（未建屏/服务断线）——明确拒绝，不静默退化成前台。 */
    class BackgroundNotReady(val reason: String) : AssemblyResolution()
}
