package com.bettergi.pocket.scan

import com.bettergi.pocket.scan.ScanEngine.Companion.ARTIFACT_STOP_NAMES
import com.bettergi.pocket.scan.ScanEngine.Companion.WEAPON_FORGING_STOP_NAMES
import com.bettergi.pocket.scan.ScanEngine.Companion.hitsStopName
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #216 素材停止标识的守门（2026-10-04，参照 GOODScanner 但**不照抄**）。
 *
 * 这里最要紧的不是"名单能不能命中"（那三行代码不会写错），而是**边界**：
 * GOODScanner 除了精炼矿名单，还把 10 件 1★/2★ 武器也当停止符
 * （`WEAPON_STOP_NAMES` = 无锋剑/银剑/猎弓/学徒笔记/…）。我们**故意不收这一半**，
 * 因为抓包真值里 `ApprenticesNotes|1|1` 是货真价实的一件武器
 * （`dsl/verify/sweeps/gt_20261003_bs.json`，rarity 分布 {1:1, 3:77, 4:112, 5:16}）
 * ⇒ 跟着它在这里止扫就**永久少一件**，正是 #188 那条漏件的形状。
 * 下面第 3、4 条用例就是把这个决定钉住：谁哪天"对齐 GOODScanner"把那 10 件加进停止名单，
 * 或者把它们从词典里摘掉，这里立刻红。
 */
class ScanStopMarkerTest {

    private fun mappings(): JSONObject {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val candidate = File(dir, "src/main/assets/dsl/tools/mappings.json")
            if (candidate.isFile) return JSONObject(candidate.readText())
            dir = dir.parentFile ?: return@repeat
        }
        error("assets/dsl/tools/mappings.json not found from ${System.getProperty("user.dir")}")
    }

    /**
     * 取一段里所有中文名。⚠️ **两段形状不同**（2026-10-04 实测踩到）：
     * weapons / artifactSets 是 `{"id":…,"n":{"zh":…}}`，
     * artifactPieces 是 `{"piece":…,"setId":…,"slot":…}`（没有 `n`，键名也不是 `id`）。
     */
    private fun zhNames(section: String): Set<String> {
        val arr = mappings().getJSONArray(section)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            when {
                o.has("n") -> o.getJSONObject("n").getString("zh")
                o.has("piece") -> o.getString("piece")
                else -> error("$section[$i] 既没有 n.zh 也没有 piece，形状与预期不符")
            }
        }.toSet()
    }

    @Test
    fun `ore and elixir names hit, real equipment names do not`() {
        // 实测原文形状：素材那一格第 2 槽是"圣遗物强化素/武器经验表"这类被切断的描述行，
        // 名字槽本身也可能带尾巴 ⇒ 判据用 contains（与 GOODScanner 同口径）。
        assertEquals("精锻用魔矿", hitsStopName("精锻用魔矿", WEAPON_FORGING_STOP_NAMES))
        assertEquals("精锻用杂矿", hitsStopName("精锻用杂矿×5", WEAPON_FORGING_STOP_NAMES))
        assertEquals("祝圣精华", hitsStopName("祝圣精华", ARTIFACT_STOP_NAMES))
        // 真装备一律不许命中
        assertNull(hitsStopName("黎明神剑", WEAPON_FORGING_STOP_NAMES))
        assertNull(hitsStopName("绝弦", WEAPON_FORGING_STOP_NAMES))
        assertNull(hitsStopName("流浪者的花朵", ARTIFACT_STOP_NAMES))
        assertNull(hitsStopName(null, ARTIFACT_STOP_NAMES))
    }

    @Test
    fun `stop names must not collide with the name dictionary`() {
        // 停止标识与词典是**两条互斥**的集合：一条名字既是"要入库的装备"又是"见到就停"，
        // 就等于把那条装备永久屏蔽。词典是生成物（gen_mappings.py），会随游戏版本长，
        // 所以这条要当回归门而不是靠人记。
        val weapons = zhNames("weapons")
        val artifacts = zhNames("artifactPieces") + zhNames("artifactSets")
        assertTrue(
            "武器停止名单与武器词典重叠：${WEAPON_FORGING_STOP_NAMES.filter { it in weapons }}",
            WEAPON_FORGING_STOP_NAMES.none { it in weapons },
        )
        assertTrue(
            "圣遗物停止名单与圣遗物词典重叠：${ARTIFACT_STOP_NAMES.filter { it in artifacts }}",
            ARTIFACT_STOP_NAMES.none { it in artifacts },
        )
    }

    @Test
    fun `low-star weapons are recorded, not treated as stop markers`() {
        // #188 的结论：GOODScanner 的 WEAPON_STOP_NAMES 那 10 件低星武器在我们这边是**要入库的**。
        val weapons = zhNames("weapons")
        val goodScannerWeaponStops = listOf(
            "无锋剑", "银剑", "猎弓", "学徒笔记", "新手长枪",
            "训练大剑", "佣兵重剑", "铁尖枪", "口袋魔导书", "历练的猎弓",
        )
        val missing = goodScannerWeaponStops.filter { it !in weapons }
        assertTrue("低星武器不在词典里（A' 段被摘了？）：$missing", missing.isEmpty())
        val leaked = goodScannerWeaponStops.filter { it in WEAPON_FORGING_STOP_NAMES }
        assertTrue("低星武器被当成停止符（会少件，见类注释）：$leaked", leaked.isEmpty())
    }

    @Test
    fun `lists are exactly the names observed on device`() {
        // 钉住"名单是从我们自己的日志里量出来的，不是抄来的"：
        //   武器 20261003_bs_run_bettergi.log（page=10 尾行 学徒笔记 → 魔矿/良矿/杂矿）
        //   圣遗物 20260925_2244_artifact_64fix.log（page=51/52 global=948 = 祝圣精华）
        // 新素材上线时改这里，并同步类注释里的"为什么不照抄低星那一半"。
        assertEquals(listOf("精锻用魔矿", "精锻用良矿", "精锻用杂矿"), WEAPON_FORGING_STOP_NAMES)
        assertEquals(listOf("祝圣油膏", "祝圣精华"), ARTIFACT_STOP_NAMES)
    }
}
