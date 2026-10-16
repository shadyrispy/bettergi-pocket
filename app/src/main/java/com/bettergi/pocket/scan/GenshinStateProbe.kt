package com.bettergi.pocket.scan

import com.bettergi.pocket.input.InputAccessibilityService

/**
 * 游戏前台状态的探针（#310 / M0「探针接口抽取」，为后台模式 virtualdisplay 做准备）。
 *
 * ## 背景
 * 后台模式下帧源换 MediaProjection 虚拟屏、注入换 injectInputEvent，但引擎层
 * （ScanEngine 及全部 freshFrame 调用点）零改动——分叉集中在 ScriptRunner 装配处。
 * M0 只做「抽接口不改行为」：把 ScriptRunner 对无障碍服务**读状态**的两个静态调用
 * （isGenshinInForeground / sdkLoginOnTop，构造 ScanEngine 时作为 lambda 传入）
 * 收口到本接口，行为与抽取前逐位一致。
 *
 * ## 实现
 * - 前台实现 [ACCESSIBILITY] = 无障碍事件流（现网行为）。
 * - 后台实现（未来里程碑）= probeDisplay 轮询。
 *
 * ⚠️ 范围纪律（M0 口径）：只抽「读状态」的探针。`ensureEnabled` / `cancelRecoverCheck`
 * 这类**控制无障碍服务自身**的调用不抽（后台模式下它们仍该工作或被条件跳过，属后续里程碑）；
 * 注入路径（tap/click/swipe/back）已由 ScanEngine.ActionGateway 接口缝承接，同样不在本抽象内。
 */
interface GenshinStateProbe {
    /** 原神游戏界面是否在前台；null = 判不了（与 isGenshinInForeground 的 null 契约一致）。 */
    fun isGameInForeground(): Boolean?

    /** MiHoYo SDK 登录/切号页是否在顶层（S4a #283：与游戏同包名，须按类名分）。 */
    fun isSdkLoginOnTop(): Boolean?

    companion object {
        /** 前台模式实现：无障碍事件流（行为与抽取前逐位一致）。 */
        val ACCESSIBILITY: GenshinStateProbe = object : GenshinStateProbe {
            override fun isGameInForeground(): Boolean? = InputAccessibilityService.isGenshinInForeground()
            override fun isSdkLoginOnTop(): Boolean? = InputAccessibilityService.sdkLoginOnTop()
        }
    }
}
