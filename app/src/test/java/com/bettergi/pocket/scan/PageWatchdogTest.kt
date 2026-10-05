package com.bettergi.pocket.scan

import com.bettergi.pocket.scan.ScanEngine.Companion.PAGE_WATCHDOG_MS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页级看门狗的口径（2026-10-04 华为 EML-AL00 实测后改）。
 *
 * 缺陷现场：圣遗物全量跑到第 11 件就 `scan finished: watchdog cells=11 pages=1`。
 * 旧判据量「本页累计耗时 > 90s」，而 90s 的依据是注释里的「正常单页 ~6s」——那是
 * BlueStacks 的数。麒麟 970 上 perCell=11.0s、一页 12 格 ⇒ 累计 132s，**每页必越线**，
 * 于是把"机器慢"判成"挂死"，945 件的量只跑了 11 件。
 */
class PageWatchdogTest {

    /** 旧口径（负对照用）：距**本页开始**的累计耗时。 */
    private fun oldRule(pageStartAt: Long, nowAt: Long, budgetMs: Long) = nowAt - pageStartAt > budgetMs

    @Test
    fun `a slow but advancing device must not be judged hung`() {
        val pageStart = 1_000_000L
        val perCell = 11_012L          // 华为 2026-10-04 实测 perCell
        var lastProgress = pageStart
        for (cell in 1..12) {
            val now = lastProgress + perCell
            assertFalse("第 $cell 格前不该判挂死", pageWatchdogFired(lastProgress, now, PAGE_WATCHDOG_MS))
            lastProgress = now
        }
        // 同一串时间戳喂给旧口径：第 9 格就越线 ⇒ 这条用例证明修的是真缺陷，不是换个写法。
        assertTrue(
            "负对照：旧累计口径在同一份华为读数上必须误触发",
            oldRule(pageStart, pageStart + 9 * perCell, PAGE_WATCHDOG_MS),
        )
    }

    @Test
    fun `a real hang is still caught regardless of page elapsed time`() {
        val pageStart = 1_000_000L
        // 前一格很快走完，随后主滑派发后无任何后续日志（原始症状）
        val afterFastCell = pageStart + 500L
        assertFalse(pageWatchdogFired(pageStart, afterFastCell, PAGE_WATCHDOG_MS))
        assertTrue(pageWatchdogFired(afterFastCell, afterFastCell + PAGE_WATCHDOG_MS + 1, PAGE_WATCHDOG_MS))
    }

    @Test
    fun `budget boundary is strict`() {
        assertFalse(pageWatchdogFired(0L, PAGE_WATCHDOG_MS, PAGE_WATCHDOG_MS))
        assertTrue(pageWatchdogFired(0L, PAGE_WATCHDOG_MS + 1, PAGE_WATCHDOG_MS))
    }

    @Test
    fun `blue stack pace keeps a wide margin under the same budget`() {
        // BS 单格 ~0.5s（「正常单页 ~6s」/12 格）：改成间隔口径后余量从 15 页变成 180 倍单格。
        assertFalse(pageWatchdogFired(0L, 500L, PAGE_WATCHDOG_MS))
    }
}
