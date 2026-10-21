package com.bettergi.pocket.scan

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #317（2026-10-19）：后台帧新鲜度补偿的**门控与语义**守门。
 *
 * 背景（前台 vs 后台的物理差异）：前台投影是常驻帧流（游戏每渲染一帧推一帧）⇒「点击已消化」
 * ≡「下一帧必到」，帧指针天然同步；后台 VD 的 ImageReader 是**内容驱动**（只有合成出新内容
 * 才回调）⇒ 点击注入与游戏渲染之间没有同步点，偶发抓到旧帧/过渡帧。
 *
 * r5 全量实证（1280 后台）：残留漏 6 件全部是「整格面板未到达」形态（数值指纹日志 0 命中、
 * 导出无近邻残件）；同轮有 6 次 `面板指纹：400ms 内面板未变化（按原样解析，可能陈旧帧）`。
 *
 * 本类钉三件事：
 * ① **门控默认关**（`backgroundFrameCompensation` 缺省 false）⇒ 前台/单测/离线跑逐位不变；
 * ② 两个补偿常量的取值与语义（重击 1 次、稳定确认 1 拍）——改值必须显式过来改并说明依据；
 * ③ 重击上限是**有界**的（不是无限循环）——无限重击会把真冻结格（列表尾素材格）拖成死循环。
 */
class BackgroundFrameCompensationGateTest {

    private fun assetsDir(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val c = File(dir, "src/main/assets/dsl")
            if (c.isDirectory) return c
            dir = dir.parentFile ?: return@repeat
        }
        error("src/main/assets/dsl not found")
    }

    /**
     * ① 门控默认关闭 —— 这是**前台零回归的结构保证**。
     *
     * 引擎构造参数缺省 `backgroundFrameCompensation = false`，而 ScriptRunner 只在
     * `activeActions != null`（后台装配）时传 true ⇒ 前台、单测、干跑一律走原路径。
     * 若哪天有人把缺省改成 true，前台扫描会开始多做「重击 + 稳定确认」两轮等待，
     * 本用例会先红。
     */
    @Test
    fun `compensation defaults off so foreground path is untouched`() {
        val ctor = ScanEngine::class.java.constructors.firstOrNull()
            ?: error("ScanEngine 无构造器？")
        // 默认值只能从 Kotlin 元数据/调用点看；这里用「源码声明」级别的守门：
        // 直接读源码确认缺省是 false（单测跑在 JVM，反射拿不到 Kotlin 默认参数值）。
        val src = File(assetsDir().parentFile!!.parentFile!!.parentFile!!.parentFile!!, "src/main/java")
        var f = File(src, "com/bettergi/pocket/scan/ScanEngine.kt")
        if (!f.isFile) {
            // AGP 单测工作目录可能是 app/ ⇒ 走相对路径
            f = File("src/main/java/com/bettergi/pocket/scan/ScanEngine.kt")
        }
        assertTrue("找不到 ScanEngine.kt（${f.absolutePath}）", f.isFile)
        val text = f.readText()
        assertTrue(
            "backgroundFrameCompensation 的缺省值必须是 false —— 前台零回归的结构保证",
            text.contains("internal val backgroundFrameCompensation: Boolean = false"),
        )
    }

    /**
     * ② 重击上限 **1 次**（有界，防真冻结格死循环）。
     *
     * 取 1 的依据：r5 实测「等满未变」全轮仅 6 次（1017 格的 0.6%），重发一轮 ≈ +400ms/格，
     * 真冻结格重发后仍不变、照旧解析 ⇒ 保底不劣化。取 >1 会把列表尾素材格的无谓点击放大 N 倍。
     *
     * ⚠️ **本类曾经还有「稳定确认」一条，已按 r6 实测删除**（2026-10-19）：把放行从
     * 「指纹一变」推迟到「变化 + 再确认一拍」会让 13.5% 的格子晚 120ms 换帧 ⇒ 整轮节拍后移、
     * 与 BS 周期吞击窗口对齐 ⇒ 漏件 6 → 30（差 5 倍）。**别再把它加回来**，除非有新的
     * 全量对账数据证明节拍影响已被吸收。
     */
    @Test
    fun `compensation constants stay bounded`() {
        assertEquals("重击上限应为 1（有界，防真冻结格死循环）", 1, ScanEngine.Companion.BG_GATE_RECLICK_MAX)
        assertEquals(
            "双帧裁决拍长应与指纹轮询同源（同一物理节奏）",
            ScanEngine.Companion.PANEL_FP_POLL_MS,
            ScanEngine.Companion.ZHUSHENG_CONFIRM_POLL_MS,
        )
    }

    /**
     * ②b r6 回归守门：**指纹闸门不得有「变化后再确认一拍」的二次等待**。
     *
     * 这条是把 r6 那次 6→30 的退化钉成可执行判据 —— 谁再把稳定确认加回来，这里先红。
     */
    @Test
    fun `fingerprint gate has no stability confirm pass`() {
        var f = File("src/main/java/com/bettergi/pocket/scan/ArtifactDomainPanels.kt")
        if (!f.isFile) {
            f = File(assetsDir().parentFile!!.parentFile!!.parentFile!!.parentFile!!,
                "src/main/java/com/bettergi/pocket/scan/ArtifactDomainPanels.kt")
        }
        assertTrue("找不到 ArtifactDomainPanels.kt", f.isFile)
        val text = f.readText()
        assertFalse(
            "指纹闸门出现了「稳定确认」二次等待 —— r6 实测该改动让漏件 6→30（节拍后移撞 BS 吞击窗口），" +
                "已否决，勿重新加入",
            text.contains("BG_STABLE_CONFIRM_POLLS") || text.contains("新面板就绪且稳定"),
        )
    }

    /**
     * ③ ScriptRunner 的接线必须是「后台才 true」—— 门控真值来自 `activeActions != null`。
     * 这条是 ① 的运行期对应物：缺省 false 只在构造处被显式传 true 时才变。
     */
    @Test
    fun `script runner gates compensation on background assembly`() {
        var f = File("src/main/java/com/bettergi/pocket/scan/ScriptRunner.kt")
        if (!f.isFile) {
            f = File(assetsDir().parentFile!!.parentFile!!.parentFile!!.parentFile!!,
                "src/main/java/com/bettergi/pocket/scan/ScriptRunner.kt")
        }
        assertTrue("找不到 ScriptRunner.kt（${f.absolutePath}）", f.isFile)
        val text = f.readText()
        assertTrue(
            "backgroundFrameCompensation 必须由 activeActions != null 推导（后台装配信号）——" +
                "写成恒定 true 会把补偿施加到前台",
            text.contains("backgroundFrameCompensation = activeActions != null"),
        )
        // applyAssembly 的语义前提：Background 分支写 activeActions，else 分支置 null
        assertTrue(
            "applyAssembly 的 else 分支必须置 activeActions = null（前台识别依据）",
            text.contains("activeActions = null"),
        )
    }

    /**
     * ④ 补偿不改变**解析语义**，只改变等待/重试 —— 断言闸门源码里没有新增「丢弃/跳过」分支。
     * 这条守的是 #55 的教训：任何"等不到就跳过解析"的改动都会静默丢件（曾 937→817）。
     */
    @Test
    fun `compensation never skips parsing`() {
        var f = File("src/main/java/com/bettergi/pocket/scan/ArtifactDomainPanels.kt")
        if (!f.isFile) {
            f = File(assetsDir().parentFile!!.parentFile!!.parentFile!!.parentFile!!,
                "src/main/java/com/bettergi/pocket/scan/ArtifactDomainPanels.kt")
        }
        assertTrue("找不到 ArtifactDomainPanels.kt", f.isFile)
        val text = f.readText()
        // 补偿分支必须在闸门内、且闸门后仍走原解析路径（没有 early return 跳过 parse）
        val gateIdx = text.indexOf("后台补偿：同坐标重击")
        assertTrue("找不到后台补偿重击分支（#317 未落地？）", gateIdx > 0)
        val afterGate = text.substring(gateIdx)
        assertFalse(
            "补偿分支后不得出现「跳过解析」的早退（#55 教训：等不到就跳过 = 静默丢件）",
            Regex("""后台补偿[^\n]{0,200}\n\s*return\b""").containsMatchIn(afterGate.take(800)),
        )
    }
}