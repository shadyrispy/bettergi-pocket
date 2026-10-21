package com.bettergi.pocket.scan

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * #241：「roll 重裁抢救」链的**失败侧**能不能被看见 —— 本类是该链（`ArtifactDomainPanels.kt:966-1032`）
 * 唯一能在 JVM 单测里落地的接缝钉桩。
 *
 * 链的形状（:975 起）：首读单候选 `rollIn` → [:976] `RollSolver.solve(rarity, level, rollIn)` 无解
 * → [:981] 把每行包成候选列表 → 每行右缘裁 8%/16% 各重读一次 → 新读数按 [:1008] 的去重门槛
 * （同键且 |Δ|<0.01 视为重复）进该行的候选列表 → [:1015] `RollSolver.solve(rarity, level, candidates)`
 * 再解 → 解出记 :1017「重裁抢救成功」、仍无解记 :1026「重裁抢救失败（跑了 N 种裁法、新增候选 M 个仍未解出）」
 * 并在 [:1030] 调 `RollSolver.logMiss`。
 *
 * ⚠️ **为什么本类不驱动真链**（诚实边界，不是偷懒）：那两个 `solve` 调用嵌在
 * `internal suspend fun ScanEngine.parseArtifactPanel(frame: Mat, ocr: OcrGateway, …)` 里，触发它需要
 * ① 真实 `Mat` 像素（[:1005] 的 `VoteJudges.substatRowPending` 是**颜色判据**，决定候选带不带「(待激活)」）
 * ② 能按裁法返回逐行文本的 `OcrGateway` ③ 标定过的 profile ROI ④ `GoodNames` 词典。
 * 桩化这四样 = 把「重读到底读出了什么」写进测试自己 ⇒ 测试只会验证我塞进去的假读数，
 * 永远验证不了真机上读不读得出来（#241 要的恰恰是后者）。故本类只钉**链 bottoms out 的那两个生产调用**
 * （:976 与 :1015 是同一个 `RollSolver.solve` 的单候选/行候选两个重载）以及链的**唯一可观测状态**
 * `RollSolver.missCount`。
 *
 * ⚠️ **今天仍不可测**：:986-990 的裁法几何（右缘内收 + `coerceAtLeast(20)`）、:1008 的去重门槛、
 * :991-1000 的按裁法异常跳过（`cropTried` 与 `newCands` 是函数内局部变量，出了作用域就没了）、
 * :1005 的待激活标记补写 —— 全是 `parseArtifactPanel` 体内的内联语句，**一个 seam 都没抽出来**，
 * 本类碰不到它们，只能把它们依赖的下游判据（同一份候选列表进同一个 solver）钉住。
 *
 * ✅ #241 已补（2026-10-13）：三个会话计数落在 **`ScanEngine`** 上并进了 `scan finished` 摘要
 * （`recropAttempts` 进链件数 / `recropNewCandidates` 重读新增候选 / `recropRescued` 重裁解出件数，
 * 条件式打印 ⇒ 链没进就整段不打）。链入口同时加了闸门（只放 4★/5★ 进链，3★ 按构造无解 ⇒
 * 白跑两批重读还污染 `roll未解`）。⇒ 现在读得出来的东西：`抢救>0` 证明链在救人，
 * `重裁-抢救` 是跑了没救回的件数，`新候选≈0` 说明重读根本没读出东西；异常吞裁法仍只有
 * `重裁 n` 与失败日志的 `跑了 N 种裁法` 对不上时才能看出来（:996 那条路径没单独计数）。
 */
class RollRecropFailureTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadTable() {
            RollTable.attachJson(JSONObject(File(assetsDir(), "tools/rollTable.json").readText()))
        }

        private fun assetsDir(): File {
            var dir = File(System.getProperty("user.dir") ?: ".")
            repeat(4) {
                val c = File(dir, "src/main/assets/dsl")
                if (c.isDirectory) return c
                dir = dir.parentFile ?: return@repeat
            }
            error("src/main/assets/dsl not found")
        }
    }

    private val rarity = 5
    private val level = 20

    /** :975 的 `rollIn` —— 真机 motivating 现场（GS 对齐 P0-2）：enerRech 5.8 被读成 5.89（8→9 形近）。 */
    private fun firstPass(enerRech: Double) = listOf(
        RollSolver.In("atk_", 16.3),
        RollSolver.In("enerRech_", enerRech),
        RollSolver.In("critRate_", 3.9),
        RollSolver.In("critDMG_", 21.0),
    )

    /** :981 的形状：每行只有首读那一个候选。 */
    private fun singleCandidateLines(enerRech: Double) = firstPass(enerRech).map { listOf(it) }

    /** 重裁产出「表内正确读数」后的候选列表（= :1009 把新候选 append 进第 1 行）。 */
    private fun rescuedLines(second: Double) = listOf(
        listOf(RollSolver.In("atk_", 16.3)),
        listOf(RollSolver.In("enerRech_", 5.89), RollSolver.In("enerRech_", second)),
        listOf(RollSolver.In("critRate_", 3.9)),
        listOf(RollSolver.In("critDMG_", 21.0)),
    )

    /**
     * 接缝主用例 + 防空跑负对照。
     *
     * ⚠️ 三条断言**少一条本类就没意义**：中间那条（把首读原样包成「行候选列表」仍无解）
     * 证明下面那条 `assertNotNull` 归功于**第二个候选**，而不是归功于「换了解决重载调用」。
     * 少了它，绿可能只是 `solve(List<List<In>>)` 恒能解 —— 那正是 #241 说的「全绿可能是空跑」。
     */
    @Test
    fun `首读单候选无解 补上正确第二候选才解出`() {
        // 5.89 距档位 5.8 差 0.09 > SNAP_TOL(0.06) ⇒ 首读的档位吸附(:941)救不回来；
        // 表定位容差 0.05（RollTable.combosOf）与档位枚举容差 0.02（canReach）也都够不着 ⇒ 整件无解。
        assertNull(
            "前提：5.89 这条首读必须**真的无解**（否则链根本不会被触发，下面全是空跑）",
            RollSolver.solve(rarity, level, firstPass(5.89)),
        )
        assertNull(
            "同一份首读包成行候选列表(:981)不改变结果 ⇒ 下一段的绿只能来自第二候选",
            RollSolver.solve(rarity, level, singleCandidateLines(5.89)),
        )
        val solved = RollSolver.solve(rarity, level, rescuedLines(5.8))
        assertNotNull("补第二候选 5.8 后必须解出（= :1017「重裁抢救成功」那条分支的判据）", solved)
        assertEquals(
            "解出所用的读数必须是重读出的 5.8，不是首读留着的 5.89",
            5.8, solved!!.substats.first { it.key == "enerRech_" }.value, 1e-9,
        )
        assertEquals("4 行候选 ⇒ 解出 4 条已激活词条（链不增删行，只换值）", 4, solved.activeCount)
    }

    /**
     * :978-981「行对应关系」那条注释的**正向钉桩**：正确读数挂到别的行 ⇒ 依旧无解。
     * （现场是 22:36 真机 IndexOutOfBounds —— 候选列表按 `subRects` 而非 `rollIn` 构建。）
     * 哪天有人把合并改成「往列表尾部 append」或按 rect 下标对齐，这条会红。
     */
    @Test
    fun `第二候选挂错行仍然无解`() {
        val misplaced = listOf(
            listOf(RollSolver.In("atk_", 16.3), RollSolver.In("enerRech_", 5.8)), // 5.8 跑到第 0 行
            listOf(RollSolver.In("enerRech_", 5.89)),
            listOf(RollSolver.In("critRate_", 3.9)),
            listOf(RollSolver.In("critDMG_", 21.0)),
        )
        assertNull(
            "同名候选挂错行 ⇒ 组合不是键重复（RollSolver.solve:133 拦掉）就是仍带表外值 5.89，" +
                "不得判成「抢救成功」。钉的是「合并必须按 rollIn 行号对齐」这条不变式。",
            RollSolver.solve(rarity, level, misplaced),
        )
    }

    /**
     * 失败分支(:1023-1031)**可达**，而且日志里的「新增候选 M 个」里 M 可以 > 0 —— 这正是 #241 的盲区：
     * 「新增候选 1 个仍未解出」既读不出「重裁根本没读出东西」，也读不出「读出来了但还是歪的」。
     *
     * 5.7 距档位 5.8 差 0.1 ⇒ :1008 的去重门槛(|Δ|<0.01)会**放行**它（记成新增候选），
     * 但表定位 0.05 / 枚举 0.02 两重容差都够不着 ⇒ 两种裁法跑完仍无解。
     */
    @Test
    fun `新增候选仍是表外值 失败分支可达`() {
        val noise = RollSolver.solve(rarity, level, rescuedLines(5.7))
        assertNull(
            "跑了 2 种裁法、新增候选 1 个仍未解出 ⇒ :1026 失败分支的判据（本用例证明它不是死码）",
            noise,
        )
        val lines = rescuedLines(5.7)
        assertEquals("候选列表长度=行数（链不会增删行）", 4, lines.size)
        assertEquals("第二候选确实进了列表 ⇒ 失败日志的 newCands 会计 1", 2, lines[1].size)
    }

    /**
     * #241 的核心断言：**链成功 与 链根本没跑，会话状态一模一样**。
     *
     * `RollSolver.missCount`（打进 `scan finished` 摘要的 `roll未解=N件`，`ScanEngine.kt:338`）
     * 只由 :1030 那句手写的 `logMiss` 增加，`solve` 自己两边都不碰它。于是：
     * - 链跑完仍无解 ⇒ 计数 +1，但**只有这一种**结局有信号；
     * - 链救回来（:1017）⇒ 计数纹丝不动 ⇒ 与「这件首读就解出、链压根没进」**完全同相**；
     * - 某裁法重读抛异常被跳过(:996)、或新候选被 :1008 去重丢弃 ⇒ 同样零信号。
     * ⇒ 摘要只说「还剩几件没解」，**永远说不出「这 N 件本来更多、是链救回来的」**。
     * 第③步的反证是给本用例自己用的：先证明字段是活的，①② 的「不变」才不是在读死变量。
     */
    @Test
    fun `抢救成败在会话计数上不可区分`() {
        RollSolver.resetMissCount()
        try {
            val baseline = RollSolver.missCount

            // ① 链失败：首读无解 + 两种裁法跑完（新增候选仍是表外值）仍无解
            RollSolver.solve(rarity, level, singleCandidateLines(5.89))
            RollSolver.solve(rarity, level, rescuedLines(5.7))
            assertEquals(
                "solve 不自我上报 ⇒ 失败侧唯一的信号是 :1030 那句手写的 logMiss",
                baseline, RollSolver.missCount,
            )

            // ② 链成功：第二候选 5.8 救回来
            assertNotNull("前提复查：这条必须解出，否则①②就是在比两次失败", RollSolver.solve(rarity, level, rescuedLines(5.8)))
            assertEquals(
                "抢救成功同样不留痕 ⇒ 与「链从未进过」在可观测状态上不可区分（#241 的病灶）",
                baseline, RollSolver.missCount,
            )

            // ③ 反证：字段是活的 —— :1030 真调一次就 +1
            RollSolver.logMiss(rarity, level, firstPass(5.89))
            assertEquals("logMiss 确实在写 missCount（①②的「不变」不是读死字段）", baseline + 1, RollSolver.missCount)
        } finally {
            // 会话计数复位口径与 ScanEngine.run()(:238) 一致，别把脏计数留给别的用例
            RollSolver.resetMissCount()
        }
    }

    /**
     * #241 闸门依据（2026-10-13）：`solve` 对 rarity ∉ {4,5} **按构造恒 null**（`RollSolver.kt:109`
     * 函数首行即返回，不看数值/等级）⇒ 3★ 件（2026-09-19 起纳入导出）若照旧进链，首读 solve 必无解、
     * 链必然跑满两批多 ROI 重读（右缘 -8%/-16%），再经失败分支记进 `roll未解`。
     * `parseArtifactPanel` 链入口的闸门（只放 4★/5★ 进链）依据的就是本用例钉的这个契约。
     *
     * 两个重载都钉：首读走单候选重载、链内走行候选重载 —— 对 3★ 的任意输入两条都出不了解，
     * 「无闸门时链必空跑」不是巧合而是构造。（引用链内位置一律不写行号：本次改动增删了几行，
     * 本类 KDoc 里的旧行号已按改动前口径，别再添会漂的。）
     *
     * ⚠️ 诚实边界（同本类 KDoc）：闸门本体在 `parseArtifactPanel` 内，属引擎/设备级，本类驱动
     * 不了真链 —— 所以这里能证明的只有**契约**（闸门的依据），不是闸门执行本身；3★ 照常导出
     * 那一半同样没有 JVM 用例。类 KDoc TODO(#241) 所需的三个会话计数现已落在 `ScanEngine`
     * （`recropAttempts`/`recropNewCandidates`/`recropRescued`，进 `scan finished` 摘要；
     * 闸门使 3★ 不进链 ⇒ 三数只含 4★/5★），但同为引擎级状态，本类只能由契约间接背书。
     */
    @Test
    fun `3星件求解按构造恒 null 故重裁链必须设闸门`() {
        // 形状按 3★ 真件取（≤3 条词条、level ≤ 12）；数值不参与判定 —— rarity 检查在函数
        // 首行、先于一切数值/档位表查询，任意输入都改不了 null 这个结论。
        val threeStar = listOf(
            RollSolver.In("atk_", 7.0),
            RollSolver.In("def", 19.0),
            RollSolver.In("eleMas", 16.0),
        )
        assertNull(
            "3★ 首读（单候选重载）按构造无解 ⇒ 无闸门时链必进、两批多 ROI 重读必空跑",
            RollSolver.solve(3, 12, threeStar),
        )
        assertNull(
            "行候选重载对 3★ 同样按构造无解 ⇒ 链内没有任何出口，进链=纯开销+污染 roll未解",
            RollSolver.solve(3, 12, threeStar.map { listOf(it) }),
        )
    }
}
