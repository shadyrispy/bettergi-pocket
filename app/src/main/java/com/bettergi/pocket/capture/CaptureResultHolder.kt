package com.bettergi.pocket.capture

import android.content.Intent

/**
 * 进程内单例：跨 Activity→Service 传递 MediaProjection 授权结果 Intent。
 *
 * 根因：MediaProjection 的授权结果 [Intent] 内部携带 IBinder extra
 * (`android.media.projection.extra.EXTRA_MEDIA_PROJECTION`)。若把这个 Intent 再作为
 * 另一个 Intent 的 Parcelable extra 通过 `startForegroundService` 传递，嵌套 parcel 时
 * 内层 IBinder 会丢失（system_server 侧抛 `ClassCastException: String cannot be cast to
 * Integer`），导致服务侧 `getParcelableExtra` 拿到 null、capture 永远起不来。
 *
 * 规避：授权 Activity 把 result Intent 存进本单例（同进程 object，IBinder 引用完好），
 * 只通过 service intent 传 resultCode；服务从单例取 resultData，用完即清空。
 */
object CaptureResultHolder {
    @Volatile
    var pendingResultData: Intent? = null

    fun take(): Intent? = pendingResultData.also { pendingResultData = null }
}
