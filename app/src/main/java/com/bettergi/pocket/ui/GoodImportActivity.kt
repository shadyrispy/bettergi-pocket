package com.bettergi.pocket.ui

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bettergi.pocket.notice.NoticeCenter
import com.bettergi.pocket.scan.GoodRepository

/**
 * 「导入 JSON」的透明中转页（#205，用户反馈"点了导入被强制打开管理页"）。
 *
 * 为什么原先要绕管理器页：SAF 的文件选择器**必须由前台 Activity 发起**，悬浮窗所在的
 * `:a11y` 进程里 `startActivity` 会被系统静默吞掉（同 [com.bettergi.pocket.capture.CapturePermissionActivity]
 * 记的那条）。但"必须由 Activity 发起"不等于"必须由**管理器页**发起"——
 * 原先的实现把两件事绑在一起，用户点一下导入就被顶到一个陌生的整屏页面。
 *
 * 本页只做一件事：弹选择器 → 把选中的文本交给 [GoodRepository.save] → 关掉自己。
 * 不需要回传通道：GOOD 存的是**复制进来的正文**（`filesDir/scripts/good/current.json`，
 * 见 [GoodRepository.file]），不是 URI，所以选完就可以立刻结束，管理器页下次进来照常读得到。
 */
class GoodImportActivity : AppCompatActivity() {

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // 回执两条都要落日志：这条链的失败态（选择器起来了、结果没人收）在界面上
        // 与"用户按了返回"完全同形，只有这里能分开（#205 真机实测踩过一次）。
        if (uri == null) {
            Log.i(TAG, "good import cancelled")
        } else {
            Log.i(TAG, "good document picked uri=$uri")
            import(uri)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "requesting good document")
        picker.launch(arrayOf("application/json", "text/plain", "*/*"))
    }

    private fun import(uri: Uri) {
        val text = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            NoticeCenter.error("读取失败")
            return
        }
        val name = GoodRepository.displayName(this, uri)
            ?: (uri.lastPathSegment?.substringAfterLast('/') ?: "input.json")
        val (ok, msg) = GoodRepository.save(this, name, text)
        Log.i(TAG, "good import name=$name bytes=${text.length} ok=$ok msg=$msg")
        NoticeCenter.post(if (ok) NoticeCenter.Level.INFO else NoticeCenter.Level.ERROR, msg)
    }

    companion object {
        private const val TAG = "BetterGI.GoodImport"
    }
}
