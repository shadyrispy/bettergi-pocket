package com.bettergi.pocket.feature.autoskip

import android.util.Log
import com.bettergi.pocket.input.ActionEmitter
import com.bettergi.pocket.input.ClickAction
import com.bettergi.pocket.recognition.RecognitionAssets
import com.bettergi.pocket.recognition.area.Region
import com.bettergi.pocket.settings.TriggerSettings
import com.bettergi.pocket.trigger.FeatureTick
import com.bettergi.pocket.trigger.TriggerFeature
import com.bettergi.pocket.trigger.screenBottomCenter

/**
 * 自动对话：匹配到对话历史后视为正在剧情中；
 * 可快速点击屏幕下方继续，并点击最上方的对话选项。
 */
class AutoSkipFeature(
    private val assets: RecognitionAssets,
    private val events: AutoSkipEvents? = null,
) : TriggerFeature {
    override val key: String = "AutoSkip"

    @Volatile
    private var nextOptionClickAtMs: Long = 0L

    override fun isEnabled(settings: TriggerSettings): Boolean =
        settings.screenShareEnabled && settings.autoSkipEnabled

    override fun onTick(tick: FeatureTick, settings: TriggerSettings, actions: ActionEmitter) {
        val content = tick.content ?: return
        if (!isDialogueScene(content, assets)) return
        events?.onTalkHistoryMatched()

        if (settings.quickSkipDialogueEnabled) {
            val (skipX, skipY) = screenBottomCenter(tick.screenWidth, tick.screenHeight)
            actions.emit(ClickAction(skipX, skipY))
        }

        val chatIcon = assets.get(TASK_NAME, "ChatIcon", content.captureRectArea)
        val hits = content.findMulti(chatIcon)
        val top = selectTopChatIcon(hits) ?: return
        val (topX, topY) = top.centerOnNativeCapture()
        events?.onChatIconsRecognized(hits.size, topX, topY)

        val now = System.currentTimeMillis()
        if (now < nextOptionClickAtMs) return

        Log.i(TAG, "click top chat icon at $topX,$topY score=${top.matchScore} count=${hits.size}")
        actions.emit(ClickAction(topX, topY))
        events?.onChatIconClicked(topX, topY)
        nextOptionClickAtMs = now + OPTION_CLICK_COOLDOWN_MS
    }

    companion object {
        const val TASK_NAME = "AutoSkip"
        private const val TAG = "BetterGI.AutoSkip"
        private const val OPTION_CLICK_COOLDOWN_MS = 400L

        fun selectTopChatIcon(hits: List<Region>): Region? = hits.minByOrNull { it.y }
    }
}
