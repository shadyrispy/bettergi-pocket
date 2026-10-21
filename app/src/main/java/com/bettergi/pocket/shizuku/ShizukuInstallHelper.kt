package com.bettergi.pocket.shizuku

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * ★ #310/M0 后台模式：内置 shizuku.apk 的**导出安装**流程（assets → 公共 Downloads → 拉系统安装器）。
 *
 * 为什么不直接 FileProvider 指向内部存储：安装器（PackageInstaller UI）是系统
 * 进程，读不到本 app 私有目录；apk 必须落在公共可读位置，再以 content uri +
 * FLAG_GRANT_READ_URI_PERMISSION 授给安装会话。
 *
 * 版本分叉（minSdk 24 兼容）：
 * - API 24~28：`Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)`
 *   旧 File 路径（写外部公共目录无需运行时权限，scoped storage 前时代）。
 * - API 29+：`MediaStore.Downloads` 插行（贡献者豁免：自己插的行自己可写），
 *   无需任何存储权限。
 *
 * 「已在时复用」按**字节数对比**资产大小：导出中途被杀会留半截文件，
 * 大小不符即视为脏数据重写（apk 不做哈希 —— 体积对了再装不上是安装器的事，
 * 这里只防最常见的半截文件）。
 *
 * 真实 APK **不入库**（.gitignore 已挡 assets/shizuku 下所有 .apk 后缀），
 * 由维护者构建前手动放入（见 assets/shizuku/README.md）。资产缺失时
 * [isBundled] 返回 false，UI 侧（未来）退回「跳 GitHub 下载」路径。
 */
object ShizukuInstallHelper {

    private const val TAG = "BetterGI.Shizuku"

    /** assets 内置安装包路径（维护者手动放置，不入 git）。 */
    private const val ASSET_APK = "shizuku/shizuku.apk"

    /** 导出到公共 Downloads 的文件名（FileProvider file_paths.xml 已按此精确登记单文件）。 */
    private const val EXPORT_NAME = "bettergi-pocket-shizuku-installer.apk"

    /**
     * 导出产物：29+ 是 MediaStore content uri（无真实 File），24~28 是公共
     * Downloads 下的 File。两者都能交给 [launchInstaller]（后者会被 FileProvider
     * 包成 content uri）。
     */
    sealed class Exported {
        /** API 29+：MediaStore.Downloads 行的 content uri。 */
        data class Store(val uri: Uri) : Exported()

        /** API 24~28：公共 Downloads 下的文件。 */
        data class Legacy(val file: File) : Exported()
    }

    /** assets 里是否带着安装包。M0 占位：资产未入库，默认 false 并打日志。 */
    fun isBundled(context: Context): Boolean = try {
        context.assets.open(ASSET_APK).use { it.available() > 0 }
    } catch (_: IOException) {
        Log.i(TAG, "内置 shizuku.apk 缺失（assets/$ASSET_APK），安装动线退回 GitHub 下载")
        false
    }

    /**
     * 导出安装包到公共 Downloads。已在且字节数与资产一致时直接复用（省一次拷贝，
     * 也保住用户手动重装场景下的幂等）；不一致（半截文件/版本更新）则整体重写。
     *
     * @return 导出产物；资产缺失或导出失败返回 null（调用方按「退回 GitHub 下载」处理）
     */
    fun exportInstaller(context: Context): Exported? {
        val assetSize = try {
            context.assets.openFd(ASSET_APK).use { it.declaredLength }
        } catch (_: IOException) {
            Log.w(TAG, "导出失败：内置 shizuku.apk 缺失（assets/$ASSET_APK）")
            return null
        }
        return if (Build.VERSION.SDK_INT >= 29) exportViaMediaStore(context, assetSize)
        else exportLegacy(context, assetSize)
    }

    /** API 29+：MediaStore.Downloads 插行/复用，返回 content uri。 */
    private fun exportViaMediaStore(context: Context, assetSize: Long): Exported.Store? {
        val resolver = context.contentResolver
        val collection =
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        // 同名行已存在且大小一致 → 直接复用（不 append "(1)" 再插一行）
        val existing = resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(EXPORT_NAME),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                val size = cursor.getLong(1)
                if (size == assetSize) id else null
            } else null
        }
        if (existing != null) return Exported.Store(
            Uri.withAppendedPath(collection, existing.toString())
        )

        // 插行 → 拷流 → 确认大小（write 模式打开后流被消费完才算落定）
        val uri = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, EXPORT_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: run {
            Log.w(TAG, "MediaStore.Downloads 插行失败")
            return null
        }
        val ok = runCatching {
            context.assets.open(ASSET_APK).use { input ->
                resolver.openOutputStream(uri)?.use { output -> input.copyTo(output) }
                    ?: return@runCatching false
            }
            // 拷完对比字节数：不符按失败处理并删行，防止半截文件被下次复用
            val written = resolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.SIZE),
                null, null, null,
            )?.use { if (it.moveToFirst()) it.getLong(0) else -1L } ?: -1L
            if (written == assetSize) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null, null,
                )
                true
            } else false
        }.getOrElse { false }
        if (!ok) {
            runCatching { resolver.delete(uri, null, null) }
            Log.w(TAG, "导出到 MediaStore 失败（大小不符或 IO 异常），已清理残留行")
            return null
        }
        return Exported.Store(uri)
    }

    /** API 24~28：公共 Downloads 旧 File 路径（file_paths.xml 的 external-path 单文件登记正对着它）。 */
    private fun exportLegacy(context: Context, assetSize: Long): Exported.Legacy? {
        val target = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            EXPORT_NAME,
        )
        if (target.isFile && target.length() == assetSize) return Exported.Legacy(target)
        val ok = runCatching {
            target.parentFile?.mkdirs()
            context.assets.open(ASSET_APK).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.length() == assetSize
        }.getOrElse { false }
        if (!ok) {
            target.delete()
            Log.w(TAG, "导出到公共 Downloads 失败（大小不符或 IO 异常），已清理残留文件")
            return null
        }
        return Exported.Legacy(target)
    }

    /**
     * 拉起系统安装器。Legacy 产物经 FileProvider 包成 content uri（file_paths.xml
     * 已精确登记该单文件）；Store 产物本身就是 content uri。
     * [Intent.FLAG_GRANT_READ_URI_PERMISSION] 必须带（安装器是别的进程），
     * FLAG_ACTIVITY_NEW_TASK 必须带（M2 从 Service/无 UI 处发起时无任务栈可依附）。
     *
     * @return intent 是否成功发出（resolveActivity 为 null 视为无安装器，返回 false）
     */
    fun launchInstaller(context: Context, exported: Exported): Boolean {
        val uri = when (exported) {
            is Exported.Legacy -> FileProvider.getUriForFile(
                context,
                "${context.packageName}.files",
                exported.file,
            )
            is Exported.Store -> exported.uri
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            Log.w(TAG, "无应用可处理 APK 安装 intent")
            return false
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse { e ->
            Log.w(TAG, "拉起安装器失败：$e")
            false
        }
    }
}
