package com.bettergi.pocket.ui

import android.content.Intent
import android.view.View
import android.widget.TextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Button
import androidx.core.content.ContextCompat
import com.bettergi.pocket.R
import com.bettergi.pocket.notice.NoticeCenter
import com.bettergi.pocket.input.InputAccessibilityService
import com.bettergi.pocket.input.SwipeMethod
import com.bettergi.pocket.input.SwipeTestRunner
import com.bettergi.pocket.scan.GoodRepository
import com.bettergi.pocket.scan.NameOverrides
import com.bettergi.pocket.service.TriggerForegroundService
import com.bettergi.pocket.settings.TriggerSettingsRepository
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
    managerShown = false          // 与 showScriptManager 对称：两屏互斥，见那边的注释
    setContentView(R.layout.activity_onboarding)

    findViewById<TextView>(R.id.step1_action).setOnClickListener { openA11yIfNeeded() }
    findViewById<TextView>(R.id.step2_action).setOnClickListener {
        // ★ #311：第 2 步动作按 runMode 分流——前台=屏幕共享知晓（记 KEY_SHARE_ACK）；
        //   后台=发起 Shizuku 授权（授权与否由 Shizuku 卡轮询显示，点「知道了」也放行）。
        if (currentOnboardRunMode() == com.bettergi.pocket.settings.RunMode.BACKGROUND) {
            ensureTriggerService()
            requestShizukuPermission()
        } else {
            prefs.edit().putBoolean(KEY_SHARE_ACK, true).apply()
        }
        renderOnboarding()
    }
    findViewById<TextView>(R.id.onboarding_next).setOnClickListener {
        if (!onboardingReady()) return@setOnClickListener
        prefs.edit().putBoolean(KEY_ONBOARD_DONE, true).apply()
        // ★ #311：完成去向按 runMode 分流——前台=悬浮窗（launchOverlayAndExit）；
        //   后台=脚本管理页（无悬浮窗，管理页是后台模式唯一控制面）。
        if (currentOnboardRunMode() == com.bettergi.pocket.settings.RunMode.BACKGROUND) {
            showScriptManager()
        } else {
            launchOverlayAndExit()
        }
    }
    // ★ #311：模式单选切换 ⇒ 落盘 runMode + 重绘（第 2/3 步的标题与完成文案随模式变）
    findViewById<RadioButton>(R.id.onboard_mode_foreground).setOnCheckedChangeListener { _, checked ->
        if (checked) {
            TriggerSettingsRepository.app(this).setRunMode(com.bettergi.pocket.settings.RunMode.FOREGROUND)
            renderOnboarding()
        }
    }
    findViewById<RadioButton>(R.id.onboard_mode_background).setOnCheckedChangeListener { _, checked ->
        if (checked) {
            ensureTriggerService()                        // Shizuku 轮询/授权在服务进程侧
            TriggerSettingsRepository.app(this).setRunMode(com.bettergi.pocket.settings.RunMode.BACKGROUND)
            renderOnboarding()
        }
    }
    renderOnboarding()
}

/** 拉起触发服务（ACTION_START；Shizuku 轮询与授权弹窗都挂在服务上）。 */
internal fun MainActivity.ensureTriggerService() {
    val intent = Intent(this, TriggerForegroundService::class.java).apply {
        action = TriggerForegroundService.ACTION_START
    }
    androidx.core.content.ContextCompat.startForegroundService(this, intent)
}

/** 引导页当前选中的模式（单选态 → RunMode；设置是同一份，页面单选只是它的视图）。 */
private fun MainActivity.currentOnboardRunMode(): com.bettergi.pocket.settings.RunMode =
    TriggerSettingsRepository.app(this).get().runMode

/** ① 无障碍已开；② 第 2 步按 runMode：前台=屏幕共享已知晓；后台=不拦（Shizuku 授权在管理页继续引导）。 */
internal fun MainActivity.onboardingReady(): Boolean =
    InputAccessibilityService.isConnected() &&
        (currentOnboardRunMode() == com.bettergi.pocket.settings.RunMode.BACKGROUND ||
            prefs.getBoolean(KEY_SHARE_ACK, false))

internal fun MainActivity.renderOnboarding() {
    val a11y = InputAccessibilityService.isConnected()
    val background = currentOnboardRunMode() == com.bettergi.pocket.settings.RunMode.BACKGROUND
    val ack = if (background) true else prefs.getBoolean(KEY_SHARE_ACK, false)
    val ok = ContextCompat.getColor(this, R.color.pocket_ok)
    val muted = ContextCompat.getColor(this, R.color.pocket_text_muted)

    findViewById<TextView>(R.id.step0_mark).apply {
        text = "✓"
        setTextColor(ok)
    }
    findViewById<RadioButton>(R.id.onboard_mode_foreground).isChecked = !background
    findViewById<RadioButton>(R.id.onboard_mode_background).isChecked = background

    // 第 2 步：标题/说明随模式切换（前台=屏幕共享知晓；后台=Shizuku 提权）
    findViewById<TextView>(R.id.step2_title).text =
        if (background) "Shizuku 提权" else "屏幕共享"
    findViewById<TextView>(R.id.step2_body).text =
        if (background) {
            "后台模式把原神跑在虚拟屏上：点「请求授权」后在弹窗选「始终允许」。\n" +
                "Shizuku 未启动时，先打开 Shizuku 应用按引导启动服务（重启手机后需重新启动，属正常）。\n" +
                "授权状态进管理页后可随时查看（页顶 Shizuku 卡）。"
        } else {
            "第一次开始扫描时，系统会问「要开始录制或投放内容吗」→ 点「立即开始」。\n" +
                "它只用来**看游戏画面**做识别：画面不会保存、不会外传，随时能在悬浮窗里关掉。"
        }
    findViewById<TextView>(R.id.step2_action).apply {
        text = when {
            background -> "请求授权"
            ack -> "已完成"
            else -> "知道了"
        }
        visibility = when {
            background -> View.VISIBLE   // Shizuku 随时可（重新）发起
            ack -> View.GONE
            else -> View.VISIBLE
        }
    }

    findViewById<TextView>(R.id.step1_mark).apply {
        text = if (a11y) "✓" else "○"
        setTextColor(if (a11y) ok else muted)
    }
    findViewById<TextView>(R.id.step1_action).apply {
        text = if (a11y) "已完成" else "去开启"
        visibility = if (a11y) View.GONE else View.VISIBLE
    }
    findViewById<TextView>(R.id.step2_mark).apply {
        text = if (ack) "✓" else "○"
        setTextColor(if (ack) ok else muted)
    }
    findViewById<TextView>(R.id.step3_mark).apply {
        text = if (onboardingReady()) "✓" else "○"
        setTextColor(if (onboardingReady()) ok else muted)
    }
    // 第 3 步：完成去向说明随模式切
    findViewById<TextView>(R.id.step3_body).text =
        if (background) {
            "后台模式没有悬浮窗：所有操作都在**脚本管理页**完成（行内「开始/停止」发流程，页顶看虚拟屏状态）。\n" +
                "从启动器再点开「更好的原神」即可回到本页。"
        } else {
            "悬浮窗是游戏边上的小圆球。\n以后想改脚本、开关流程，**长按那个圆球面板里的「日志」按钮**就能回到本页。\n（单击那个按钮是开合识别日志。）"
        }

    val next = findViewById<TextView>(R.id.onboarding_next)
    next.text = if (background) "完成，去脚本管理页" else "完成，去悬浮窗"
    next.alpha = if (onboardingReady()) 1f else 0.4f
    next.isEnabled = onboardingReady()
    findViewById<TextView>(R.id.onboarding_hint).text = when {
        onboardingReady() -> ""
        !a11y -> "先在①里开启无障碍，再回到本页"
        background -> "点第②步的「请求授权」完成 Shizuku 提权（或先进管理页继续）"
        else -> "看完第 2 步并点「知道了」，就能进悬浮窗"
    }
}
