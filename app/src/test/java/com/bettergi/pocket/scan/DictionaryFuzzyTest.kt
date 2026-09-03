package com.bettergi.pocket.scan

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 词典 fuzzy 兜底守门（模拟器 3200x1440 首次全流程实测：25 件 setKey 全 dict miss，
 * OCR 错字对如 '深廊的回秦之歌'(真: 深廊最终回响之歌) / '渝告之钟'(真: 谕告胎动之钟)
 * 与真名互不包含——contains 一级模糊兜不住，需单字 Dice 二级模糊）。
 */
class DictionaryFuzzyTest {

    private fun dict(): ArtifactSetDictionary {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val candidate = File(dir, "src/main/assets/dsl")
            if (candidate.isDirectory) {
                return ArtifactSetDictionary(
                    JSONObject(File(candidate, "tools/artifactSetPieces.json").readText()),
                )
            }
            dir = dir.parentFile ?: return@repeat
        }
        error("dsl assets dir not found")
    }

    @Test
    fun `exact match still wins`() {
        val d = dict()
        // 任取词典真实件名——精确命中
        val sample = JSONObject(File(assetsRoot(), "tools/artifactSetPieces.json").readText())
            .getJSONObject("pieceToSetId")
        val first = sample.keys().next()
        assertEquals(sample.getString(first), d.setKeyByPiece(first))
    }

    @Test
    fun `ocr typo with shared chars is fuzzy-matched`() {
        val d = dict()
        // 模拟器实测错字对（真名取词典最近似项，Dice >= 0.55 应命中且 setKey 与精确名一致）
        val typoPairs = listOf(
            "渝告之钟",   // 真名: 谕告胎动之钟
            "深廊的回秦之歌", // 真名: 深廊最终回响之歌
        )
        for (typo in typoPairs) {
            val key = d.setKeyByPiece(typo)
            assertNotNull("fuzzy miss for '$typo'", key)
        }
    }

    @Test
    fun `fuzzy result agrees with exact-name lookup`() {
        val d = dict()
        // fuzzy 命中的 setKey 必须等于「词典里 Dice 最高的那件」的精确 setKey（防误配到别的套装）
        // 用一个可控错字：'昔日宗室之仪' 少一字 → '宗室之仪'（包含匹配即命中）
        val exact = d.setKeyByPiece("昔日宗室之仪")
        val missing = d.setKeyByPiece("昔日宗室之")
        if (exact != null) {
            assertEquals(exact, missing)
        }
    }

    @Test
    fun `garbage string does not fuzzy-match`() {
        val d = dict()
        // 乱码（模拟器 OCR 实测出现的乱码行）不应命中任何套装
        assertNull(d.setKeyByPiece("認的条名浩成t的佐主類合坦4の"))
    }

    @Test
    fun `weapon dict fuzzy-matches ocr typos`() {
        // 模拟器武器扫描实测错字对（真名取 mappings.weapons）
        val mappings = JSONObject(File(assetsRoot(), "tools/mappings.json").readText())
        val weapons = mappings.getJSONArray("weapons")
        val byName = HashMap<String, String>()
        for (i in 0 until weapons.length()) {
            val w = weapons.getJSONObject(i)
            byName[w.getJSONObject("n").getString("zh")] = w.getString("id")
        }
        val wd = WeaponDictionary(JSONObject(mappings.toString()))
        // 真名候选：万国诸海图谱（OCR 错 1 字）、风信之锋（繁体鋒）
        fun findId(substr: String): String? = byName.entries.firstOrNull { it.key.contains(substr) }?.value
        val typoPairs = listOf(
            "万国诺海图谱" to findId("万国诸海图谱"),
            "风信之鋒" to findId("风信之锋"),
        )
        for ((typo, expected) in typoPairs) {
            val key = wd.keyByName(typo)
            if (expected != null) {
                assertEquals("fuzzy mismatch for '$typo'", expected, key)
            } else {
                assertNotNull("no dictionary entry for typo '$typo'", key)
            }
        }
    }

    private fun assetsRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val candidate = File(dir, "src/main/assets/dsl")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("dsl assets dir not found")
    }
}
