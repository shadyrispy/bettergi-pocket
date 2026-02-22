package com.bettergi.pocket.feature.autoskip

import com.bettergi.pocket.recognition.CaptureContent
import com.bettergi.pocket.recognition.RecognitionAssets

fun isDialogueScene(content: CaptureContent, assets: RecognitionAssets): Boolean {
    val talkHistory = assets.get(AutoSkipFeature.TASK_NAME, "TalkHistory", content.captureRectArea)
    return content.find(talkHistory).isExist()
}
