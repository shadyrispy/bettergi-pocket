package com.bettergi.pocket.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView

/**
 * ★ 2026-09-18 **BAL 探针专用 Activity**（诊断态，可随结论删除）。
 *
 * 为什么需要它：`MainActivity` 启动后立即 `finish()`（它是跳板），AM 来不及打 `Displayed` 行，
 * 也无法用 `mCurrentFocus` 判定 —— 导致「后台启动是否被 BAL 拦截」**无法取证**（三种情形全假阴性 ✗）。
 * 本 Activity **常驻不 finish**（点一下才关），使 `mCurrentFocus` / `dumpsys activity activities`
 * 成为可靠判据。
 *
 * 用法：`InputAccessibilityService.probeBal` 把 startActivity 目标指向本 Activity；
 * 结论只看 `adb shell dumpsys window | grep mCurrentFocus` 是否变为本 Activity。
 */
class BalProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("BetterGI.BalProbe", "BalProbeActivity onCreate ⇒ 后台启动已成功（可见窗口建立）")
        setContentView(
            TextView(this).apply {
                text = "BAL 探针：MainActivity 类目标已拉起 ✓（点我关闭）"
                textSize = 22f
                gravity = Gravity.CENTER
                setOnClickListener { finish() }
            },
        )
    }

    override fun onStart() {
        super.onStart()
        Log.i("BetterGI.BalProbe", "BalProbeActivity onStart（可见）")
    }

    override fun onResume() {
        super.onResume()
        Log.i("BetterGI.BalProbe", "BalProbeActivity onResume（前台）")
    }
}
