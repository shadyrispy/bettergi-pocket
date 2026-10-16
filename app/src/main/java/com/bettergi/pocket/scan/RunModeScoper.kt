package com.bettergi.pocket.scan

import com.bettergi.pocket.capture.FrameSource
import com.bettergi.pocket.settings.RunMode

/**
 * ★ #310/M3：运行模式的**装配描述**——ScriptRunner 起扫所需三件套的取值面。
 *
 * 为什么这一层：前台模式的三件套（投影帧源 / a11y 动作 / a11y 探针）的**实例归
 * TriggerForegroundService 持有**（一个实例喂 TriggerEngine + ScriptRunner 两个消费者，
 * 见 [FrameSource.newFreshnessScope] 的 KDoc），而后台模式的三件套是**每次后台会话
 * 新建**（VD 随会话开合）。两者生命周期不同 ⇒ 不能用同一个"创建"入口。
 * [RunModeScoper] 只描述"怎么取"，实例归属留给两侧的宿主：
 * - 前台：`TriggerForegroundService` 构造时创建、全程复用；
 * - 后台：`BackgroundSession`（M3 引入）起扫时创建、收扫释放。
 *
 * M0-M2 期间只有 [foreground] 一个实现；[background] 在 M3 接线（依赖 M1b 的
 * BgiRemoteService 与 M2 的 VirtualDisplayManager）。
 */
class RunModeScoper(
    /** 前台取值（现网三件套，由 TriggerForegroundService 注入它的既有实例）。 */
    private val foregroundFrameSource: FrameSource,
    private val foregroundProbe: GenshinStateProbe,
) {
    /** 一次起扫的装配结果。 */
    class Scoped(
        val frameSource: FrameSource,
        val probe: GenshinStateProbe,
    )

    fun scope(mode: RunMode): Scoped = when (mode) {
        RunMode.FOREGROUND -> Scoped(foregroundFrameSource, foregroundProbe)
        RunMode.BACKGROUND -> error(
            "BACKGROUND 装配在 M3 接线（依赖 M1b BgiRemoteService + M2 VirtualDisplayManager）；" +
                "起扫前应先校验 runMode==FOREGROUND 或后台就绪",
        )
    }
}
