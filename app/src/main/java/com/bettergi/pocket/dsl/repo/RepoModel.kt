package com.bettergi.pocket.dsl.repo

import org.json.JSONArray
import org.json.JSONObject

/**
 * §16.3 S3 RepoManager：repo.json 数据模型（与 PC BetterGI repo.json 同构）。
 *
 * 纯数据 + 解析（org.json，无 Android 依赖 → JVM 单测可直覆盖）。
 * 字段缺失时给安全默认，避免订阅一个格式不严的仓库直接崩。
 */
data class RepoProject(
    val path: String,
    val type: String,
    val name: String,
    val version: String,
    val minHostVersion: String,
    val description: String,
) {
    companion object {
        fun parse(o: JSONObject): RepoProject = RepoProject(
            path = o.optString("path", ""),
            type = o.optString("type", "pocket-script"),
            name = o.optString("name", o.optString("path", "")),
            version = o.optString("version", "0.0.0"),
            minHostVersion = o.optString("min_host_version", "0.0.0"),
            description = o.optString("description", ""),
        )
    }
}

data class RepoManifest(
    val name: String,
    val version: String,
    val updatedAt: String,
    val projects: List<RepoProject>,
) {
    companion object {
        fun parse(text: String): RepoManifest {
            val o = JSONObject(text)
            val arr = o.optJSONArray("projects") ?: JSONArray()
            val projects = (0 until arr.length()).map { RepoProject.parse(arr.getJSONObject(it)) }
            return RepoManifest(
                name = o.optString("name", ""),
                version = o.optString("version", "0.0.0"),
                updatedAt = o.optString("updated_at", ""),
                projects = projects,
            )
        }
    }
}
