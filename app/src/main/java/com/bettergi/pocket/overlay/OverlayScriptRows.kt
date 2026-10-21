package com.bettergi.pocket.overlay
import android.graphics.Color
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.bettergi.pocket.R
import android.util.Log
import android.view.Gravity
import com.bettergi.pocket.dsl.FlowValidator
import com.bettergi.pocket.dsl.ScriptIcons
import com.bettergi.pocket.dsl.ScriptStore
import com.bettergi.pocket.bridge.OverlayEntryContract
import android.widget.EditText
import com.bettergi.pocket.overlay.OverlayWindowController.Companion.TAG_OVERLAY

/*
 * Stage 3.4：脚本行 / 流程按钮（从 OverlayWindowController.kt 抽出，扩展函数，函数体一字未改）。
 */

internal fun OverlayWindowController.applyFlowSelection(flow: String) {
    if (flowViews.isEmpty()) return
    val running = settingsRepository.get().scanEnabled
    flowViews.forEach { (k, row) ->
        val isCurrent = k == flow
        val isRunning = running && isCurrent
        row.setBackgroundResource(
            if (isCurrent) R.drawable.bg_overlay_launch else R.drawable.bg_overlay_row,
        )
        flowLabelViews[k]?.setTextColor(
            context.getColor(if (isCurrent) R.color.overlay_gold else R.color.overlay_text),
        )
        flowRunButtons[k]?.apply {
            setImageResource(if (isRunning) R.drawable.ic_action_stop else R.drawable.ic_action_run)
            setColorFilter(
                context.getColor(
                    if (isRunning) R.color.overlay_notice_bar_error else R.color.overlay_gold,
                ),
            )
            contentDescription = if (isRunning) "停止" else "开始"
            isEnabled = true // 运行中「停止」必须可点
            alpha = 1f
        }
        row.alpha = if (running && !isCurrent) 0.55f else 1f
    }
    // 逐个动作按钮统一处理（含「更多」展开出来的）
    flowActions.forEach { (key, list) ->
        list.forEach { v ->
            val isRunButton = flowRunButtons[key] === v
            v.isEnabled = if (isRunButton) true else !running
            if (!isRunButton) v.alpha = if (running) 0.45f else 1f
        }
    }
}

/**
 * 重建脚本区：**唯一来源是脚本自身** —— 只渲染「已启用 且 声明了 `ui`」的流程，顺序取 `ui.order`。
 *
 * 每行 = [图标] 名称 …… [动作图标…]，动作来自脚本的 `ui.actions`（kind 决定图标与行为）。
 * 行内最多 [FlowValidator.MAX_ACTIONS] 个；超出的收进该行的「更多」（点一下就地展开，不用系统 PopupMenu）。
 */
internal fun OverlayWindowController.renderFlowButtons() {
    val container = scriptGroup ?: return
    container.removeAllViews()
    flowViews.clear()
    flowLabels.clear()
    flowLabelViews.clear()
    flowRunButtons.clear()
    flowActions.clear()
    val entries = runCatching { ScriptStore.list(context) }
        .getOrDefault(emptyList())
        .filter { it.enabled && it.hasUi }
    Log.i(TAG_OVERLAY, "flow rows ← " + entries.joinToString { "${it.key}(${it.label})" })
    if (entries.isEmpty()) {
        container.addView(
            TextView(themedContext).apply {
                text = "（无启用脚本——长按日志按钮进脚本管理）"
                setTextColor(context.getColor(R.color.overlay_text_muted))
                textSize = 11f
                setPadding(dp(2), dp(6), 0, dp(6))
            },
        )
        return
    }
    for (e in entries) {
        val row = LinearLayout(themedContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setBackgroundResource(R.drawable.bg_overlay_row)
            setPadding(dp(10), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
        }
        row.addView(
            ImageView(themedContext).apply {
                setImageResource(ScriptIcons.script(e.icon))
                setColorFilter(context.getColor(R.color.overlay_text))
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(8) }
            },
        )
        val label = TextView(themedContext).apply {
            text = e.label
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(context.getColor(R.color.overlay_text))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(label)
        val actions = LinearLayout(themedContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val inline = e.actions.take(FlowValidator.MAX_ACTIONS)
        inline.forEach { actions.addView(actionView(it, e.key)) }
        if (e.actions.size > FlowValidator.MAX_ACTIONS) {
            actions.addView(moreView(e.actions.drop(FlowValidator.MAX_ACTIONS), e.key, actions))
        }
        row.addView(actions)
        container.addView(row)
        flowViews[e.key] = row
        flowLabels[e.key] = e.label
        flowLabelViews[e.key] = label
    }
    applyFlowSelection(settingsRepository.get().scanFlow)
}

/** 行内一个动作（图标按钮，视觉 32dp / 触控撑满 48dp 行高）。 */
internal fun OverlayWindowController.actionView(a: FlowValidator.OverlayAction, flowKey: String): ImageView =
    ImageView(themedContext).apply {
        setImageResource(ScriptIcons.action(a.kind))
        setColorFilter(context.getColor(ScriptIcons.actionTint(a.kind)))
        contentDescription = a.label // 无障碍名 + 长按提示（按钮本身只画图标）
        scaleType = ImageView.ScaleType.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.MATCH_PARENT)
            .apply { marginStart = dp(2) }
        isClickable = true
        setOnClickListener { onFlowAction(a.kind, flowKey) }
        flowActions.getOrPut(flowKey) { ArrayList() }.add(this)
        if (a.kind == "run") flowRunButtons[flowKey] = this
    }

/**
 * 「更多」：脚本声明超过行内上限时，其余动作收在这里。
 * 点击**就地展开**到同一行动作区（一次性展开，不做折叠）—— 悬浮窗内不能用系统 PopupMenu。
 */
internal fun OverlayWindowController.moreView(
    rest: List<FlowValidator.OverlayAction>,
    flowKey: String,
    host: LinearLayout,
): ImageView = ImageView(themedContext).apply {
    setImageResource(R.drawable.ic_action_config)
    setColorFilter(context.getColor(R.color.overlay_text_muted))
    contentDescription = "更多动作"
    scaleType = ImageView.ScaleType.CENTER
    layoutParams = LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.MATCH_PARENT)
        .apply { marginStart = dp(2) }
    isClickable = true
    setOnClickListener {
        host.removeView(this)
        rest.forEach { host.addView(actionView(it, flowKey)) }
        applyFlowSelection(settingsRepository.get().scanFlow)
    }
}

/** 行内动作分发。`kind` 决定做什么，界面只认 kind。 */
internal fun OverlayWindowController.onFlowAction(kind: String, flowKey: String) {
    val running = settingsRepository.get().scanEnabled
    when (kind) {
        // 「运行」在运行中翻转为「停止」：同一按钮位，省一格
        "run" -> if (running) settingsRepository.setScanEnabled(false) else startScan(flowKey)
        "stop" -> settingsRepository.setScanEnabled(false)
        "export" ->
            if (running) {
                A11yOverlayRuntime.notice("warn", "运行中，导出请等本轮结束")
            } else {
                onShareGoodRequested()
            }
        // 选择输入文件必须由 Activity 发起（SAF）⇒ 拉起管理器
        "import" -> openScriptManager(pickGood = true)
        "config" -> togglePagesRow()
        "open" -> openScriptManager()
        else -> Log.w(TAG_OVERLAY, "unknown action kind '$kind'")
    }
}

/**
 * 起流程（fix53 三件套保持不变：开扫描开关 + 无障碍自检 + 投影自开）。
 * @param flowKey 为空 ⇒ 用当前已选流程（「开始扫描」按钮走这条）。
 */
internal fun OverlayWindowController.startScan(flowKey: String? = null) {
    if (flowKey != null) settingsRepository.setScanFlow(flowKey)
    settingsRepository.setScanEnabled(true)
    inputGate.ensureEnabled(themedContext, "请开启无障碍权限，才能模拟扫描点击")
    if (!settingsRepository.get().screenShareEnabled) {
        settingsRepository.setScreenShareEnabled(true)
    }
}

/**
 * 拉起脚本管理器（MainActivity，extras 契约见 [OverlayEntryContract]）。
 * @param pickGood true ⇒ 顺带打开输入文件选择器（`import` 动作走这条：SAF 必须由 Activity 发起）
 */
internal fun OverlayWindowController.openScriptManager(pickGood: Boolean = false) {
    // 工单 D：Intent 组装与 extras 键下沉到 bridge/OverlayEntryContract（overlay 侧不再依赖 ui 包）
    val intent = OverlayEntryContract.managerIntent(context, pickGood)
    runCatching { context.startActivity(intent) }.onFailure {
        A11yOverlayRuntime.notice("error", "无法打开管理器：${it.message}")
    }
}
