package com.bettergi.pocket.core

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * §16.3 S1 加载层解耦：DSL 资产加载抽象。
 *
 * 加载顺序（override 优先 → assets 兜底）：
 *   1. `filesDir/scripts/installed/<relPath>` 存在则读（adb push 覆盖，免重编）
 *   2. 否则 `assets/<relPath>`（APK 内置）
 *
 * `<relPath>` 保留 assets 子目录结构，当前实际走这条路的有 `dsl/profiles.json` /
 * `dsl/profiles_<WxH>.json`、`dsl/templates.json` 与模板文件、`dsl/tools/rollTable.json`、`dsl/tools/mappings.json`。
 * 真机调参（例：改 2244 档 profile 后免重编）：
 * `adb push profiles_2244x1080.json /sdcard/Android/data/<pkg>/files/scripts/installed/dsl/`
 * → 下一次加载读 override 副本（日志 `override hit: …`）。
 *
 * ⚠️ flow JSON **不走这里**：`dsl/flows` 目录下的 json 随 DSL 运行时下线（引擎按 flowName 分派到
 *   `scan/flows` 包里的原生步序），assets 侧也不再有那个目录 ⇒ 别照本注释去补拷 `dsl/flows/`。
 *
 * 兼容：未 [install] 时（如 JVM 单测）直走传入的 [AssetManager]，调用方签名不变。
 */
object FlowSource {
    const val OVERRIDE_ROOT = "scripts/installed"

    private const val TAG = "BetterGI.FlowSource"

    /** 由 [PocketApplication.onCreate] 或扫描启动入口安装；[applicationContext] 保证全局单例。 */
    lateinit var appContext: Context
        private set

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    private fun overrideFile(relPath: String): File =
        File(appContext.filesDir, "$OVERRIDE_ROOT/$relPath")

    /** 优先读 override 副本，缺失回退 assets。未 install 时直走 [assets]。 */
    fun open(assets: AssetManager, relPath: String): InputStream {
        if (::appContext.isInitialized) {
            val f = overrideFile(relPath)
            if (f.exists()) {
                Log.i(TAG, "override hit: $relPath -> ${f.absolutePath}")
                return FileInputStream(f)
            }
        }
        return assets.open(relPath)
    }

    fun readText(assets: AssetManager, relPath: String): String =
        open(assets, relPath).bufferedReader().use { it.readText() }

    fun exists(assets: AssetManager, relPath: String): Boolean {
        if (::appContext.isInitialized && overrideFile(relPath).exists()) return true
        return runCatching { assets.open(relPath).close() }.isSuccess
    }

    /** 列出 installed 覆盖下的全部相对路径（调试 / 后续脚本管理 UI 用）。 */
    fun installedPaths(): List<String> {
        if (!::appContext.isInitialized) return emptyList()
        val root = File(appContext.filesDir, OVERRIDE_ROOT)
        if (!root.exists()) return emptyList()
        val out = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile }.forEach {
            out.add(it.relativeTo(root).path)
        }
        return out
    }
}
