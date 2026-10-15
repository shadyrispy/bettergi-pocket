package com.bettergi.pocket.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

/**
 * [ScreenStateJudge] 的两层验证。
 *
 * **背景（为什么必须有这个测试类）**：2026-10-15 补拍时发现 TITLE 判据有个真缺陷 ——
 * 加载屏的 100% 进度条同样是"横贯全宽的暗条"，三档全部被误判成标题屏
 * （`loading_progress100` 实测 `consist=1.000/0.999/0.936`）。
 * 那次是**靠实拍**才发现的；本类把"判据在真实帧上的行为"钉进单测，
 * 以后调阈值会立刻在这里红，而不是等到上机才炸。
 *
 * 两层：
 *  1. **合成帧**锁阈值边界（不依赖任何外部文件，CI 恒可跑）；
 *  2. **真实帧**全量分类（走父仓 `dsl/verify/runlogs/` 的三档归档；
 *     仓库内找不到就 [Assume] 跳过 —— 既有先例：`DupPageConfirmFlowTest`、`DslAssetsIntegrityTest`）。
 *
 * 真实帧那层的价值：**它复现的正是 2026-10-15 那个缺陷** —— 若有人把
 * `belowMinusStrip` 条件删掉，`loading_progress100` 三帧会立刻判成 TITLE 而红。
 */
class ScreenStateJudgeTest {

    companion object {
        /** 与既有测试同款：整个测试类只加载一次 native（`VoteJudgesSubstatRowTest` 同写法）。 */
        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            nu.pattern.OpenCV.loadLocally()
            Mat(2, 2, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0)).release()
        }

        /** 父仓根（本模块在 `bettergi-pocket/app`，父仓是 `genshin-scanner`）。 */
        private fun repoRoot(): File? {
            var dir = File(System.getProperty("user.dir") ?: ".")
            repeat(4) {
                val candidate = File(dir, "dsl/dev/title_home_248.py")
                if (candidate.isFile) return dir
                dir = dir.parentFile ?: return null
            }
            return null
        }

        /**
         * 归档帧的**期望口径**。注意它不等于 [ScreenStateJudge.ScreenClass]：
         * `HOME` 由派蒙模板判（不在光度三态里），这里只是用来表达"这一帧必须是主界面类"。
         */
        enum class Expect {
            /** 必须判 TITLE（允许 fail-safe 漏判，见测试 KDoc）。 */
            TITLE,
            /** 必须是等待类（近白/近黑/进度条）—— 一次都不能点。 */
            WAIT,
            /** 必须是主界面类（不许判 TITLE / WAIT）。 */
            HOME,
            /** 只要求"不是 TITLE"（home_modal 等定稿允许判 HOME 的）。 */
            NOT_TITLE,
            /** 必须是常驻界面类（不许判 TITLE / WAIT）。 */
            OTHER,
        }

        /** 真帧归档（三档补拍 + 248 归档 + 洞穴/S7）。返回 文件 → 期望口径。 */
        private fun archivedFrames(root: File): List<Pair<File, Expect>> {
            val out = ArrayList<Pair<File, Expect>>()
            fun add(file: File, cls: Expect) {
                if (file.isFile && file.name.endsWith(".png")) out += file to cls
            }
            // 语料只保住了这些（74 帧语料的历史见 248states README §3），命名规范见各目录 README
            val roots = listOf(
                "dsl/verify/runlogs/248states_20261014/evidence",
                "dsl/verify/runlogs/startupcapture_2244_20261015_091024/evidence",
                "dsl/verify/runlogs/startupcapture_2560_20261015_092825/evidence",
                "dsl/verify/runlogs/startupcapture_3200_20261015_093220/evidence",
                "dsl/verify/runlogs/s7cave_2244_20261015_100427/evidence",
                "dsl/verify/runlogs/s7cave_2560_20261015_100529/evidence",
                "dsl/verify/runlogs/s7cave_3200_20261015_100557/evidence",
            )
            for (rel in roots) {
                val d = File(root, rel)
                if (!d.isDirectory) continue
                d.listFiles()?.forEach { f ->
                    val n = f.name
                    val cls = when {
                        n.startsWith("title_") -> Expect.TITLE
                        n.startsWith("home_cave_clean_") -> Expect.HOME
                        // home_modal（断线框/退出框）按定稿口径判 HOME 是设计允许的 ⇒ 只要求非 TITLE
                        n.startsWith("home_modal_") -> Expect.NOT_TITLE
                        n.startsWith("home_") -> Expect.HOME
                        n.startsWith("menu_") || n.startsWith("other_") -> Expect.OTHER
                        n.startsWith("s7_quitconfirm_") -> Expect.OTHER
                        // 加载屏（含 100% 进度条）与版号屏：**必须等，一次都不能点**
                        // ⚠️ 例外：`loading_black_2560_c25.png` 文件名叫 black 但**内容不黑**
                        //    （mean=118 的灰屏，资源加载中间态）⇒ 它不属任何明确态，只要求非 TITLE。
                        n == "loading_black_2560_c25.png" -> Expect.NOT_TITLE
                        n.startsWith("loading_") || n.startsWith("splash_") -> Expect.WAIT
                        else -> Expect.NOT_TITLE
                    }
                    add(f, cls)
                }
            }
            return out
        }
    }

    // ---------------- 第一层：合成帧锁阈值 ----------------

    @Test
    fun `空帧返回 null 不抛异常`() {
        assertNull(ScreenStateJudge.photometric(Mat()))
        assertNull(ScreenStateJudge.photometric(Mat(0, 0, CvType.CV_8UC3)))
    }

    @Test
    fun `全白帧判 WAIT（近白阈值上侧）`() {
        val white = Mat(100, 100, CvType.CV_8UC3, Scalar(255.0, 255.0, 255.0))
        try {
            val p = ScreenStateJudge.photometric(white)
            assertNotNull(p)
            assertEquals(1.0, p!!.nearWhite, 1e-6)
            assertEquals(ScreenStateJudge.ScreenClass.WAIT, ScreenStateJudge.classify(p))
        } finally {
            white.release()
        }
    }

    @Test
    fun `全黑帧判 WAIT（冷启动过渡期，不误走刹车链）`() {
        // ★ 这条是 2026-10-15 上机验证暴露的缺口：冷启动有一个**真黑帧**
        //   （归档 loading_black_2244_c02.png，全帧均值 0.13、max<24 占 99.9%），
        //   改前它落进 OTHER ⇒ 归位链先烧 3 次盲点返回才轮到等待类屏。
        val black = Mat(100, 100, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0))
        try {
            val p = ScreenStateJudge.photometric(black)!!
            assertEquals(1.0, p.nearBlack, 1e-6)
            assertEquals(ScreenStateJudge.ScreenClass.WAIT, ScreenStateJudge.classify(p))
        } finally {
            black.release()
        }
    }

    /**
     * 近黑阈值边界：**暗场景不许被吞进 WAIT**。
     * 在盘帧里最暗的真实画面是"退出确认框压暗后的洞穴"（`near_black=0.354`）⇒ 0.95 有余量。
     */
    @Test
    fun `暗场景不误判 WAIT（近黑阈值下侧）`() {
        val dark = ScreenStateJudge.Photometric(
            nearWhite = 0.0,
            nearBlack = ScreenStateJudge.NEAR_BLACK_MIN - 0.01,
            consist = 0.2, ratio = 1.0, belowMinusStrip = -9.0,
        )
        assertEquals(ScreenStateJudge.ScreenClass.OTHER, ScreenStateJudge.classify(dark))
    }

    /**
     * 阈值边界：`below − strip` **恰好等于** +20 且 `consist` 落在 TITLE 区间（<0.90）时判 **OTHER**
     * （条件写的是 `>` 而非 `>=`）。
     *
     * ⚠️ `consist` 必须取 **0.85**（TITLE 与进度条两段之间）而不是 1.0：`consist ≥ 0.90`
     * 会命中**进度条**分支判 WAIT —— 那是 2026-10-15 加的正确行为，不是本测试要验的点。
     */
    @Test
    fun `below-strip 恰在阈值上判 OTHER`() {
        val p = ScreenStateJudge.Photometric(
            nearWhite = 0.0,
            nearBlack = 0.0,
            consist = 0.85,   // TITLE 区间（0.60–0.90），不进进度条分支
            ratio = 0.5,
            belowMinusStrip = ScreenStateJudge.BELOW_MINUS_STRIP_MIN,
        )
        assertEquals(ScreenStateJudge.ScreenClass.OTHER, ScreenStateJudge.classify(p))
        // 越过一点点就判 TITLE
        assertEquals(
            ScreenStateJudge.ScreenClass.TITLE,
            ScreenStateJudge.classify(p.copy(belowMinusStrip = ScreenStateJudge.BELOW_MINUS_STRIP_MIN + 0.1)),
        )
    }

    /**
     * 进度条分支（2026-10-15 上机暴露的缺口）：同 TITLE 形状但 `below−strip` 在下侧 ⇒ WAIT。
     * 判据两侧的分离实测：进度条 −18…−2 / 标题屏 ≥+46。
     */
    @Test
    fun `进度条形状判 WAIT 而不是 TITLE`() {
        val progress = ScreenStateJudge.Photometric(
            nearWhite = 0.05, nearBlack = 0.0,
            consist = 0.99, ratio = 0.75, belowMinusStrip = -17.0,
        )
        assertEquals(ScreenStateJudge.ScreenClass.WAIT, ScreenStateJudge.classify(progress))
        // 但 consist 不够严（<0.90）时**不该**判 WAIT —— 这刀是为了排除「圣遗物替换确认框」
        // （实拍 consist=0.799、below−strip=−7，它必须落到 OTHER 而不是 WAIT）
        assertEquals(
            ScreenStateJudge.ScreenClass.OTHER,
            ScreenStateJudge.classify(progress.copy(consist = 0.799, belowMinusStrip = -7.0)),
        )
    }

    /**
     * **优先级**：近白屏即使同时满足 TITLE 条件也判 WAIT。
     * 依据：等待永远比点击安全（加载屏点一下就打断渲染）。
     */
    @Test
    fun `WAIT 优先于 TITLE`() {
        val p = ScreenStateJudge.Photometric(
            nearWhite = 0.95, nearBlack = 0.0, consist = 1.0, ratio = 0.5, belowMinusStrip = 100.0,
        )
        assertEquals(ScreenStateJudge.ScreenClass.WAIT, ScreenStateJudge.classify(p))
    }

    @Test
    fun `consist 与 ratio 的两条线各自生效`() {
        // consist 不足 ⇒ OTHER（即使 ratio 与 below-strip 都过）
        assertEquals(
            ScreenStateJudge.ScreenClass.OTHER,
            ScreenStateJudge.classify(
                ScreenStateJudge.Photometric(0.0, 0.0, ScreenStateJudge.CONSIST_MIN - 0.01, 0.5, 100.0),
            ),
        )
        // ratio 超线 ⇒ OTHER
        assertEquals(
            ScreenStateJudge.ScreenClass.OTHER,
            ScreenStateJudge.classify(
                ScreenStateJudge.Photometric(0.0, 0.0, 1.0, ScreenStateJudge.RATIO_MAX + 0.01, 100.0),
            ),
        )
        // 两条都过 ⇒ TITLE
        assertEquals(
            ScreenStateJudge.ScreenClass.TITLE,
            ScreenStateJudge.classify(
                ScreenStateJudge.Photometric(0.0, 0.0, ScreenStateJudge.CONSIST_MIN, ScreenStateJudge.RATIO_MAX, 100.0),
            ),
        )
    }

    // ---------------- 第二层：真实帧全量分类 ----------------

    /**
     * 归档帧全量分类。**这是本类最重要的一条** —— 它复现 2026-10-15 的进度条缺陷。
     *
     * 断言口径（两档）：
     *  · 已知 `title_*` ⇒ 必须判 TITLE（**漏判容忍**：`title_after_exiticon3` 整帧偏暗、
     *    `below-strip=−5`，属已知 fail-safe 反例，允许判 OTHER；故这里只统计不硬断言）
     *  · **`loading_*` 一律不许判 TITLE** ⇒ 硬断言（这正是缺陷的判据）
     *  · `home_*` ⇒ 不许判 TITLE / WAIT
     *  · `menu_*`/`other_*`/`s7_quitconfirm_*` ⇒ 不许判 TITLE / WAIT
     */
    @Test
    fun `归档真实帧分类不许出现加载屏误判为标题屏`() {
        val root = repoRoot()
        Assume.assumeTrue("仓库内未找到 dsl 归档 ⇒ 本用例不适用（跳过，非通过）", root != null)
        val frames = archivedFrames(root!!)
        Assume.assumeTrue("归档帧缺失 ⇒ 本用例不适用（跳过，非通过）", frames.size >= 40)

        val misclassified = ArrayList<String>()
        var titleHit = 0
        var titleSeen = 0
        for ((file, expected) in frames) {
            val mat = Imgcodecs.imread(file.absolutePath)
            if (mat.empty()) {
                mat.release()
                misclassified += "${file.name}: 读帧失败"
                continue
            }
            val cls = try {
                val p = ScreenStateJudge.photometric(mat) ?: run {
                    misclassified += "${file.name}: photometric 返回 null"
                    continue
                }
                ScreenStateJudge.classify(p)
            } finally {
                mat.release()
            }
            when (expected) {
                Expect.TITLE -> {
                    titleSeen++
                    if (cls == ScreenStateJudge.ScreenClass.TITLE) titleHit++
                }
                // 等待类：必须判 WAIT（这批含冷启动的真黑帧 + 100% 进度条那三帧 ——
                // 后者是 2026-10-15 上机暴露的缺口：它们曾落进 OTHER 走刹车链烧 3 次盲点）
                Expect.WAIT -> if (cls != ScreenStateJudge.ScreenClass.WAIT) {
                    misclassified += "${file.name}: 期望 WAIT，实判 $cls"
                }
                // ★ 硬断言：home_modal 绝不能判成标题屏
                Expect.NOT_TITLE -> if (cls == ScreenStateJudge.ScreenClass.TITLE) {
                    misclassified += "${file.name}: 期望非 TITLE，实判 TITLE"
                }
                // 主界面/常驻界面：不许判 TITLE（会误点）也不许判 WAIT（会白等 90s）
                Expect.HOME, Expect.OTHER -> if (cls != ScreenStateJudge.ScreenClass.OTHER) {
                    misclassified += "${file.name}: 期望 $expected，实判 $cls"
                }
            }
        }
        assertTrue(
            "非标题屏被误判成 TITLE（#2026-10-15 进度条缺陷回归）：\n" + misclassified.joinToString("\n"),
            misclassified.isEmpty(),
        )
        // 每次跑都打一行分布：判据调参时这行是"改了什么"的第一手证据
        // （单测 stdout 会落进 TEST-*.xml 的 system-out，可事后核对）
        println(
            "ScreenStateJudge 归档审计: 共 ${frames.size} 帧 | 标题屏 $titleHit/$titleSeen 命中 | " +
                "误判 ${misclassified.size}",
        )
        // 标题屏召回率：至少 80%（已知 1 个 fail-safe 反例；低于此线说明判据整体坏了）
        Assume.assumeTrue("归档里没有标题屏帧 ⇒ 跳过召回断言", titleSeen > 0)
        assertTrue(
            "标题屏召回率过低：$titleHit/$titleSeen（已知 1 个 fail-safe 反例可接受）",
            titleHit.toDouble() / titleSeen >= 0.80,
        )
    }
}
