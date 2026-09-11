package com.bettergi.pocket.dsl.repo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.bettergi.pocket.dsl.FlowSource
import com.bettergi.pocket.dsl.FlowValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * §16.3 S3 RepoManager（P4 主体）：脚本仓库订阅管理。
 *
 * 流程：repo.json 解析 → 通道化 zipball 下载（OkHttp，无 git 依赖）→ 解压到
 * `filesDir/scripts/repos/<name>/` → 复制订阅路径到 `installed/`（更新不打断运行中脚本）→
 * `SubscribedScriptPaths + LastUpdateTime + AutoUpdatePeriodDays` 持久化。
 *
 * 安装映射：仓库布局 `pocket/...`（§9.2 PC/pocket 同仓分目录）映射到 FlowSource 约定
 * `dsl/...`（assets 即 `dsl/flows|tools|...`），故 `pocket/flows/x.json` →
 * `installed/dsl/flows/x.json`，下游 ScriptRunner/FlowSource 直读无需改动。
 */

/** 下载抽象：便于单测注入假实现（不触真实网络）。 */
interface Downloader {
    suspend fun download(url: String, dest: File)
}

class OkHttpDownloader : Downloader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun download(url: String, dest: File) {
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} for $url")
                val body = resp.body ?: throw RuntimeException("empty body for $url")
                dest.parentFile?.mkdirs()
                body.byteStream().use { src ->
                    dest.outputStream().use { out -> src.copyTo(out) }
                }
            }
        }
    }
}

/** 订阅表持久化抽象：便于单测注入内存实现。 */
interface SubscriptionsStore {
    fun load(): List<Subscription>
    fun save(list: List<Subscription>)
}

class SharedPreferencesSubscriptionsStore(context: Context) : SubscriptionsStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("bettergi.repos", Context.MODE_PRIVATE)
    private val KEY = "subscriptions"

    override fun load(): List<Subscription> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Subscription.parse(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    override fun save(list: List<Subscription>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }
}

data class Subscription(
    val name: String,
    val channel: RepoChannel,
    val owner: String,
    val repo: String,
    val ref: String,
    val customTemplate: String?,
    val subscribedPaths: List<String>,
    val lastUpdateTime: Long,
    val autoUpdatePeriodDays: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("channel", channel.id)
        put("owner", owner)
        put("repo", repo)
        put("ref", ref)
        put("customTemplate", customTemplate ?: "")
        put("subscribedPaths", JSONArray(subscribedPaths))
        put("lastUpdateTime", lastUpdateTime)
        put("autoUpdatePeriodDays", autoUpdatePeriodDays)
    }

    companion object {
        fun parse(o: JSONObject): Subscription {
            val arr = o.optJSONArray("subscribedPaths")
            val paths = (0 until (arr?.length() ?: 0)).map { arr!!.getString(it) }
            return Subscription(
                name = o.getString("name"),
                channel = RepoChannel.from(o.optString("channel", "github")),
                owner = o.getString("owner"),
                repo = o.getString("repo"),
                ref = o.optString("ref", "main"),
                customTemplate = o.optString("customTemplate", "").takeIf { it.isNotEmpty() },
                subscribedPaths = paths,
                lastUpdateTime = o.optLong("lastUpdateTime", 0),
                autoUpdatePeriodDays = o.optInt("autoUpdatePeriodDays", 7),
            )
        }
    }
}

data class SubscribeSpec(
    val owner: String,
    val repo: String,
    val ref: String = "main",
    val channel: RepoChannel = RepoChannel.GITHUB,
    val customTemplate: String? = null,
    val name: String? = null,
    val paths: List<String>? = null,
    val autoUpdatePeriodDays: Int = 7,
)

class RepoManager(
    private val context: Context,
    private val store: SubscriptionsStore = SharedPreferencesSubscriptionsStore(context),
    private val downloader: Downloader = OkHttpDownloader(),
) {
    private val TAG = "BetterGI.RepoManager"
    private val reposRoot = File(context.filesDir, "scripts/repos")
    private val installedRoot = File(context.filesDir, FlowSource.OVERRIDE_ROOT)

    fun listSubscriptions(): List<Subscription> = store.load()

    fun unsubscribe(name: String) {
        store.save(store.load().filter { it.name != name })
    }

    /** 订阅：下载+解压+解析 repo.json，订阅指定 paths（缺省=全部 pocket-script），安装并持久化。 */
    suspend fun subscribe(spec: SubscribeSpec): Result<Unit> = runCatching {
        val subName = spec.name ?: "${spec.owner}/${spec.repo}"
        val extracted = fetchAndExtract(subName, spec.channel, spec.owner, spec.repo, spec.ref, spec.customTemplate)
        val manifest = RepoManifest.parse(File(extracted, "repo.json").readText())
        val paths = spec.paths
            ?: manifest.projects.filter { it.type == "pocket-script" }.map { it.path }
        require(paths.isNotEmpty()) { "无订阅路径（repo 无 pocket-script 项目且未指定 paths）" }
        installProjects(extracted, manifest, paths)
        val sub = Subscription(
            name = subName,
            channel = spec.channel,
            owner = spec.owner,
            repo = spec.repo,
            ref = spec.ref,
            customTemplate = spec.customTemplate,
            subscribedPaths = paths,
            lastUpdateTime = System.currentTimeMillis(),
            autoUpdatePeriodDays = spec.autoUpdatePeriodDays,
        )
        store.save(store.load().filter { it.name != subName } + sub)
        Log.i(TAG, "subscribe ok: $subName paths=${paths.size}")
    }

    /** 更新已订阅仓库：重新下载并安装其订阅路径。 */
    suspend fun update(name: String): Result<Unit> = runCatching {
        val sub = store.load().firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("未订阅: $name")
        val extracted = fetchAndExtract(name, sub.channel, sub.owner, sub.repo, sub.ref, sub.customTemplate)
        val manifest = RepoManifest.parse(File(extracted, "repo.json").readText())
        installProjects(extracted, manifest, sub.subscribedPaths)
        val now = System.currentTimeMillis()
        store.save(store.load().map { if (it.name == name) it.copy(lastUpdateTime = now) else it })
        Log.i(TAG, "update ok: $name")
    }

    /** 到期自动更新（启动时/执行前调用）：仅更新到期订阅。返回 仓库名→结果。 */
    suspend fun updateAllIfDue(): List<Pair<String, Result<Unit>>> {
        val due = store.load().filter {
            it.autoUpdatePeriodDays > 0 &&
                System.currentTimeMillis() - it.lastUpdateTime >= it.autoUpdatePeriodDays * 86_400_000L
        }
        return due.map { it.name to update(it.name) }
    }

    /** 手动全量更新（UI 检查更新用）：忽略到期，更新全部订阅。返回 仓库名→结果。 */
    suspend fun updateAll(): List<Pair<String, Result<Unit>>> {
        return store.load().map { it.name to update(it.name) }
    }

    // ---- 内部 ----

    private suspend fun fetchAndExtract(
        subName: String,
        channel: RepoChannel,
        owner: String,
        repo: String,
        ref: String,
        customTemplate: String?,
    ): File {
        val url = RepoUrls.archiveUrl(channel, owner, repo, ref, customTemplate)
        val cacheDir = File(reposRoot, subName).apply { deleteRecursively(); mkdirs() }
        val zipFile = File(cacheDir, "archive.zip")
        downloader.download(url, zipFile)
        val extractDir = File(cacheDir, "extracted").apply { mkdirs() }
        extractZip(zipFile, extractDir)
        return resolveExtracted(extractDir, repo, ref)
    }

    /** repo.json 可能在剥离顶层后的根，也可能在未剥离的一级子目录（custom 通道无顶层目录）。 */
    private fun resolveExtracted(extractDir: File, repo: String, ref: String): File {
        if (File(extractDir, "repo.json").exists()) return extractDir
        val top = File(extractDir, RepoUrls.topDirName(repo, ref))
        if (top.isDirectory && File(top, "repo.json").exists()) return top
        return extractDir.listFiles()
            ?.firstOrNull { it.isDirectory && File(it, "repo.json").exists() } ?: extractDir
    }

    private fun installProjects(extracted: File, manifest: RepoManifest, paths: List<String>) {
        val host = hostVersion()
        for (path in paths) {
            val project = manifest.projects.firstOrNull { it.path == path }
            if (project != null && project.minHostVersion != "0.0.0" &&
                !FlowValidator.hostSatisfies(project.minHostVersion, host)
            ) {
                Log.w(TAG, "skip install ${project.path}: 需宿主 >= ${project.minHostVersion}，当前 $host")
                continue
            }
            val src = File(extracted, path)
            if (!src.exists()) {
                Log.w(TAG, "subscribed path 不存在于仓库: $path")
                continue
            }
            installFile(src, installedRoot, path)
            Log.i(TAG, "installed $path -> ${installRelPath(path)}")
        }
    }

    private fun hostVersion(): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    }.getOrDefault("0.0.0")
}

/**
 * repo 路径 → installed 相对路径：仓库布局 `pocket/...` 映射到 FlowSource 约定 `dsl/...`。
 * 用字符串拼接避免嵌套模板歧义。
 */
internal fun installRelPath(repoPath: String): String =
    if (repoPath.startsWith("pocket/")) "dsl/" + repoPath.substring("pocket/".length) else repoPath

/**
 * 解压 zip 并 **剥离首个路径段**（GitHub zipball 顶层目录 `<repo>-<ref>/`）。
 * 文件级纯函数，便于单测。
 */
fun extractZip(zip: File, dest: File) {
    dest.mkdirs()
    ZipInputStream(zip.inputStream()).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            val rel = entry.name.substringAfter('/', entry.name)
            if (rel.isNotEmpty() && !rel.endsWith('/')) {
                val outFile = File(dest, rel)
                outFile.parentFile?.mkdirs()
                outFile.outputStream().use { zis.copyTo(it) }
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
    }
}

/**
 * 原子安装单个订阅文件：先写 `.tmp` 再 rename 覆盖（更新不打断运行中脚本读取）。
 * 文件级纯函数。
 */
internal fun installFile(src: File, installedRoot: File, repoPath: String) {
    val dest = File(installedRoot, installRelPath(repoPath))
    dest.parentFile?.mkdirs()
    val tmp = File(dest.parentFile, "${dest.name}.tmp")
    src.inputStream().use { s -> tmp.outputStream().use { s.copyTo(it) } }
    tmp.renameTo(dest)
}
