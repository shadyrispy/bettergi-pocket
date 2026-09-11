package com.bettergi.pocket.dsl.repo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RepoManagerTest {

    /** Kotlin 2.x 移除了 File.createTempDir()，改用 createTempFile + 转目录。 */
    private fun tempDir(prefix: String): File {
        val f = File.createTempFile(prefix, "")
        f.delete()
        f.mkdirs()
        return f
    }

    @Test
    fun parseManifest() {
        val json = """
        {
          "name": "demo",
          "version": "1.0.0",
          "updated_at": "2026-09-07",
          "projects": [
            {
              "path": "pocket/flows/weapon_scan.json",
              "type": "pocket-script",
              "name": "武器扫描",
              "version": "1.1.0",
              "min_host_version": "0.1.0",
              "description": "d"
            }
          ]
        }
        """.trimIndent()
        val m = RepoManifest.parse(json)
        assertEquals("demo", m.name)
        assertEquals(1, m.projects.size)
        assertEquals("pocket/flows/weapon_scan.json", m.projects[0].path)
        assertEquals("0.1.0", m.projects[0].minHostVersion)
    }

    @Test
    fun archiveUrlTemplates() {
        assertEquals(
            "https://github.com/o/r/archive/refs/heads/main.zip",
            RepoUrls.archiveUrl(RepoChannel.GITHUB, "o", "r", "main"),
        )
        assertTrue(
            RepoUrls.archiveUrl(RepoChannel.GHPROXY, "o", "r", "main")
                .startsWith("https://ghproxy.com/"),
        )
        assertEquals(
            "https://x.com/o/r/main.zip",
            RepoUrls.archiveUrl(RepoChannel.CUSTOM, "o", "r", "main", "https://x.com/{owner}/{repo}/{ref}.zip"),
        )
    }

    @Test
    fun extractStripsTopDir() {
        val zip = File.createTempFile("repo", ".zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("demo-main/repo.json"))
            zos.write("{}".toByteArray()); zos.closeEntry()
            zos.putNextEntry(ZipEntry("demo-main/pocket/flows/x.json"))
            zos.write("{}".toByteArray()); zos.closeEntry()
        }
        val dest = tempDir("ext")
        extractZip(zip, dest)
        assertTrue(File(dest, "repo.json").exists())
        assertTrue(File(dest, "pocket/flows/x.json").exists())
        assertFalse(File(dest, "demo-main").exists())
    }

    @Test
    fun installRemapsPocketToDsl() {
        val src = File.createTempFile("src", ".json")
        src.writeText("{}")
        val installed = tempDir("inst")
        installFile(src, installed, "pocket/flows/weapon_scan.json")
        assertTrue(File(installed, "dsl/flows/weapon_scan.json").exists())
    }
}
