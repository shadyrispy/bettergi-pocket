package com.bettergi.pocket.pcdata

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.bettergi.pocket.service.TriggerForegroundService
import com.esc.irminsul.capture.IrminsulCapture

/**
 * VPN 授权中转页（透明、无内容、拿完结果就走）—— 与 `CapturePermissionActivity`（投影）**同一形态**：
 * 本页不做任何业务决定，只把「同意 / 拒绝」回报给 [TriggerForegroundService]，
 * 由主进程决定起不起隧道、要不要提醒、开关落不落回。
 *
 * 为什么必须有这个 Activity：`VpnService.prepare()` 给的是一张**系统弹窗**的 Intent，
 * 得由前台 Activity 发起；从服务里 `startActivity` 在部分 ROM 上会被静默吞掉
 * （表现就是"点了没反应"）。所以：服务直接拉本页，拉不动则先拉 MainActivity
 * 到前台再由前台发起（见 `requestVpnConsent` 与 `EXTRA_AUTO_REQUEST_VPN`）。
 */
class CaptureConsentActivity : ComponentActivity() {

    private val consent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> report(result.resultCode == RESULT_OK) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = IrminsulCapture.vpnConsentIntent(this)
        if (intent == null) {
            // 授权早就给过了：不必弹系统页，直接回报成功让主进程开跑。
            Log.i(TAG, "vpn already granted")
            report(true)
        } else {
            Log.i(TAG, "requesting vpn consent")
            consent.launch(intent)
        }
    }

    private fun report(ok: Boolean) {
        ContextCompat.startForegroundService(
            applicationContext,
            Intent(applicationContext, TriggerForegroundService::class.java)
                .setAction(TriggerForegroundService.ACTION_VPN_RESULT)
                .putExtra(TriggerForegroundService.EXTRA_VPN_OK, ok),
        )
        finish()
    }

    private companion object {
        const val TAG = "BetterGI.Capture"
    }
}
