package com.bettergi.pocket.scan

/** 进度上报（悬浮窗/通知，P1-c 接视图）。 */
interface ScanListener {
    fun onProgress(stage: String, vars: Map<String, Any?>)
    fun onFinished(reason: String)

    /**
     * 脚本用 `notify` 原语推出的重点信息（P4）。
     * 默认空实现 ⇒ 老的实现类不必改；展示由宿主决定（本应用走 NoticeCenter）。
     */
    fun onNotice(level: String, text: String) {}
}
