package com.bettergi.pocket.dsl.repo

/**
 * §16.3 S3 RepoManager：更新通道 + 归档 URL 模板。
 *
 * GitHub 同时提供 tarball(.tar.gz) 与 zipball(.zip) 两种归档；
 * 为免引入 tar 解析依赖（java.util.zip 只认 ZIP/gzip），统一用 **zipball**
 * （真实 ZIP，java.util.zip.ZipInputStream 可解）。
 */
enum class RepoChannel(val id: String) {
    GITHUB("github"),
    GHPROXY("ghproxy"),
    CUSTOM("custom");

    companion object {
        fun from(id: String): RepoChannel = entries.firstOrNull { it.id == id } ?: GITHUB
    }
}

object RepoUrls {
    /** 通道化归档(zipball) URL。custom 通道用 {owner}/{repo}/{ref} 占位符模板。 */
    fun archiveUrl(
        channel: RepoChannel,
        owner: String,
        repo: String,
        ref: String,
        customTemplate: String? = null,
    ): String = when (channel) {
        RepoChannel.GITHUB ->
            "https://github.com/$owner/$repo/archive/refs/heads/$ref.zip"
        RepoChannel.GHPROXY ->
            "https://ghproxy.com/https://github.com/$owner/$repo/archive/refs/heads/$ref.zip"
        RepoChannel.CUSTOM -> {
            val t = customTemplate
                ?: "https://github.com/$owner/$repo/archive/refs/heads/$ref.zip"
            t.replace("{owner}", owner).replace("{repo}", repo).replace("{ref}", ref)
        }
    }

    /**
     * GitHub zipball 顶层目录名：`<repo>-<ref>`。
     * ref 含 '/'（如 tag `v1/2`）时 GitHub 会转成 '-'，这里同步转换以便剥离。
     */
    fun topDirName(repo: String, ref: String): String = "$repo-${ref.replace('/', '-')}"
}
