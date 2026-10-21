package com.bettergi.pocket.capture

import android.content.Context
import android.content.Intent

/**
 * **授权回传契约**（工单 D 反向边反转）。
 *
 * 之前 `CapturePermissionActivity`（投影）与 `pcdata/CaptureConsentActivity`（VPN）直接
 * `Intent(this, TriggerForegroundService::class.java)` 回传授权结果 ⇒ capture/pcdata → service
 * 反向依赖。现在 action/extras 常量与回传 Intent 的组装集中到本契约：
 * service 侧 import 契约（service → capture 单向保留），两个 Activity 只 import 本契约。
 *
 * ⚠️ **协议值逐字不变**：这些字符串是已真机验证的协议面，只改"谁定义、从哪调用"。
 * 回传 Intent 仍显式指向 [SERVICE_CLASS_NAME]（原 `Intent(ctx, TriggerForegroundService::class.java)`
 * 的等价显式组件形式，行为逐位不变）。
 */
object CaptureContract {

    /** 投影授权成功（resultCode/data 走进程内单例 `CaptureResultHolder`，不进 extras）。 */
    const val ACTION_CAPTURE_RESULT = "com.bettergi.pocket.action.CAPTURE_RESULT"

    /** 投影授权被拒/失败。 */
    const val ACTION_CAPTURE_DENIED = "com.bettergi.pocket.action.CAPTURE_DENIED"

    /** 投影授权的 resultCode extra（仅 ACTION_CAPTURE_RESULT 携带）。 */
    const val EXTRA_RESULT_CODE = "extra_result_code"

    /** VPN 授权结果（同意/拒绝统一走这条，extra 布尔区分）。 */
    const val ACTION_VPN_RESULT = "com.bettergi.pocket.action.VPN_RESULT"

    /** VPN 授权是否同意的 extra 键。 */
    const val EXTRA_VPN_OK = "vpn_ok"

    /** 回传目标：主进程前台服务（显式组件，字符串与类 FQCN 一致）。 */
    const val SERVICE_CLASS_NAME = "com.bettergi.pocket.service.TriggerForegroundService"

    /**
     * 组装一条**显式组件**的回传 Intent（等价于原来的
     * `Intent(context, TriggerForegroundService::class.java).setAction(action)`，
     * 不 import service 类本身以保持 capture → 无 service 依赖）。
     */
    fun resultIntent(context: Context, action: String): Intent =
        Intent().setClassName(context, SERVICE_CLASS_NAME).setAction(action)
}
