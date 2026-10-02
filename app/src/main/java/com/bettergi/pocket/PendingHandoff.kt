package com.bettergi.pocket

/**
 * **主进程内部「服务 → MainActivity」的敏感中转**（★ A29 深链加固）。
 *
 * 背景：MainActivity 是 exported 的（深链 `bettergi://p/launch` + LAUNCHER），原先三类敏感中转
 * ——投影授权（EXTRA_AUTO_REQUEST_CAPTURE）、VPN 授权（EXTRA_AUTO_REQUEST_VPN）、
 * GOOD 分享（EXTRA_AUTO_SHARE）——全靠 intent extras 传递。而 handleIntent 无法区分 extras
 * 是"本 app 服务放进来的"还是"任意第三方 app 对 exported Activity putExtra 进来的"
 * （startActivity 没有可靠的调用方身份可校验）⇒ 任意 app 可拉投影授权弹窗、诱导分享 filesDir 文件。
 *
 * 修法：**敏感中转不走 extras**。服务与 MainActivity 同在主进程（AndroidManifest 里服务未声明
 * android:process）⇒ 改为进程内静态字段交接：服务 startService/startActivity 前 [post]，
 * MainActivity 的 handleIntent 里 [consume]（一次性，读后即清）。外部 app 写不进本进程静态字段，
 * extras 里的同名键一律不再读取。
 *
 * 非敏感 extras（EXTRA_FROM_OVERLAY / EXTRA_PICK_GOOD，来自 :a11y 进程的悬浮窗，静态字段跨不了
 * 进程所以必须走 extras）保留：最坏后果只是"打开脚本管理器/文件选择器"，不涉权限与数据外泄。
 */
object PendingHandoff {

    sealed class Request {
        /** 前台内发起投影授权（原 EXTRA_AUTO_REQUEST_CAPTURE）。 */
        object RequestCapture : Request()

        /** 前台内发起 VPN 授权（原 EXTRA_AUTO_REQUEST_VPN）。 */
        object RequestVpn : Request()

        /** 前台内起分享 chooser（值为 GOOD 导出文件名，原 EXTRA_AUTO_SHARE）。 */
        data class Share(val fileName: String) : Request()
    }

    @Volatile
    private var pending: Request? = null

    /** 服务侧：发起 startActivity 之前登记（后写覆盖先写 —— 同一时刻只有一个中转在途）。 */
    fun post(request: Request) {
        pending = request
    }

    /** MainActivity 侧：取走并清空（一次性）。 */
    fun consume(): Request? {
        val r = pending
        pending = null
        return r
    }
}
