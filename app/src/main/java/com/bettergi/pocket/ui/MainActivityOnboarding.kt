package com.bettergi.pocket.ui

import android.view.View
import android.widget.TextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Button
import androidx.core.content.ContextCompat
import com.bettergi.pocket.R
import com.bettergi.pocket.notice.NoticeCenter
import com.bettergi.pocket.dsl.ScriptStore
import com.bettergi.pocket.dsl.ScriptIcons
import com.bettergi.pocket.input.InputAccessibilityService
import com.bettergi.pocket.input.SwipeMethod
import com.bettergi.pocket.input.SwipeTestRunner
import com.bettergi.pocket.scan.GoodRepository
import com.bettergi.pocket.scan.NameOverrides
import com.bettergi.pocket.service.TriggerForegroundService
import com.esc.irminsul.capture.CaptureResult
import com.esc.irminsul.capture.IrminsulCapture
import com.esc.irminsul.capture.PermissionKind
import androidx.core.widget.doAfterTextChanged
import com.bettergi.pocket.capture.CapturePermissionActivity
import com.bettergi.pocket.ui.MainActivity.Companion.KEY_ONBOARD_DONE
import com.bettergi.pocket.ui.MainActivity.Companion.KEY_SHARE_ACK

/*
 * Stage 3.6：引导屏（Onboarding 屏）从 MainActivity.kt 抽出（扩展函数，函数体一字未改）。
 */


internal fun MainActivity.showOnboarding() {
    onboardingShown = true
    setContentView(R.layout.activity_onboarding)

    findViewById<TextView>(R.id.step1_action).setOnClickListener { openA11yIfNeeded() }
    findViewById<TextView>(R.id.step2_action).setOnClickListener {
        prefs.edit().putBoolean(KEY_SHARE_ACK, true).apply()
        renderOnboarding()
    }
    findViewById<TextView>(R.id.onboarding_next).setOnClickListener {
        if (!onboardingReady()) return@setOnClickListener
        prefs.edit().putBoolean(KEY_ONBOARD_DONE, true).apply()
        launchOverlayAndExit()
    }
    renderOnboarding()
}

/** ① 无障碍已开；② 屏幕共享已知晓。两条都满足才允许「完成」。 */
internal fun MainActivity.onboardingReady(): Boolean =
    InputAccessibilityService.isConnected() && prefs.getBoolean(KEY_SHARE_ACK, false)

internal fun MainActivity.renderOnboarding() {
    val a11y = InputAccessibilityService.isConnected()
    val ack = prefs.getBoolean(KEY_SHARE_ACK, false)
    val ok = ContextCompat.getColor(this, R.color.pocket_ok)
    val muted = ContextCompat.getColor(this, R.color.pocket_text_muted)

    findViewById<TextView>(R.id.step1_mark).apply {
        text = if (a11y) "✓" else "○"
        setTextColor(if (a11y) ok else muted)
    }
    findViewById<TextView>(R.id.step1_action).apply {
        text = if (a11y) "已完成" else "去开启"
        visibility = if (a11y) android.view.View.GONE else android.view.View.VISIBLE
    }
    findViewById<TextView>(R.id.step2_mark).apply {
        text = if (ack) "✓" else "○"
        setTextColor(if (ack) ok else muted)
    }
    findViewById<TextView>(R.id.step2_action).visibility =
        if (ack) android.view.View.GONE else android.view.View.VISIBLE
    findViewById<TextView>(R.id.step3_mark).apply {
        text = if (onboardingReady()) "✓" else "○"
        setTextColor(if (onboardingReady()) ok else muted)
    }

    val next = findViewById<TextView>(R.id.onboarding_next)
    next.alpha = if (onboardingReady()) 1f else 0.4f
    next.isEnabled = onboardingReady()
    findViewById<TextView>(R.id.onboarding_hint).text = when {
        onboardingReady() -> ""
        !a11y -> "先在①里开启无障碍，再回到本页"
        else -> "看完第 2 步并点「知道了」，就能进悬浮窗"
    }
}
