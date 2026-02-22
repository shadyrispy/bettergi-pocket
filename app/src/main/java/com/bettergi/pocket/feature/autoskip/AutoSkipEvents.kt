package com.bettergi.pocket.feature.autoskip

interface AutoSkipEvents {
    fun onTalkHistoryMatched()
    fun onChatIconsRecognized(count: Int, topX: Int, topY: Int)
    fun onChatIconClicked(x: Int, y: Int)
}
