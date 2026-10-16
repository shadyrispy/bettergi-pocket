package com.bettergi.pocket.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #309 游戏死亡哨兵的**判定表**（借鉴 MAA-Meow AppWatchdog 的保守三原则，
 * 动作改走我们自己的 stopRequested 收尾——判定纯逻辑，全表可单测）。
 *
 * 喂拍节奏 = 看门狗线程 5s/拍 ⇒ CONFIRM_POLLS=2 ≈ 10s 判死。
 */
class GameDeathSentinelTest {

    @Test
    fun `起跑预热期不判死（首见 IN_GAME 前恒 false）`() {
        val s = GameDeathSentinel()
        // 起跑链：S1 版号屏 → S2 加载 → S3 标题屏都是 NOT_GAME，游戏本来就不在前台
        repeat(10) { i ->
            assertFalse("预热期第 $i 拍 NOT_GAME 不得判死", s.onTick(GameDeathSentinel.Status.NOT_GAME))
        }
    }

    @Test
    fun `上岗后连续 2 拍 NOT_GAME 判死`() {
        val s = GameDeathSentinel()
        assertFalse("IN_GAME 拍未中止（上岗是副作用不是返回值）", s.onTick(GameDeathSentinel.Status.IN_GAME))
        assertFalse("第 1 拍 NOT_GAME 只是计数", s.onTick(GameDeathSentinel.Status.NOT_GAME))
        assertTrue("第 2 拍 NOT_GAME 判死", s.onTick(GameDeathSentinel.Status.NOT_GAME))
    }

    @Test
    fun `瞬态 NOT_GAME 被IN_GAME 清零（reload 与 SDK 页不误杀）`() {
        val s = GameDeathSentinel()
        s.onTick(GameDeathSentinel.Status.IN_GAME)
        assertFalse(s.onTick(GameDeathSentinel.Status.NOT_GAME))
        // S4b 重载/SDK 登录页瞬态：回到游戏 ⇒ 计数清零，重新起算
        assertFalse(s.onTick(GameDeathSentinel.Status.IN_GAME))
        assertFalse("清零后单拍 NOT_GAME 不得判死", s.onTick(GameDeathSentinel.Status.NOT_GAME))
    }

    @Test
    fun `UNKNOWN 不计次也不清零（无障碍断开不是死亡证据）`() {
        val s = GameDeathSentinel()
        assertFalse(s.onTick(GameDeathSentinel.Status.IN_GAME))
        assertFalse(s.onTick(GameDeathSentinel.Status.NOT_GAME))
        // 无障碍断开几拍：既不推进也不清零
        repeat(5) {
            assertEquals(
                "UNKNOWN 拍返回值必须保持当前状态（未判死=false）",
                false, s.onTick(GameDeathSentinel.Status.UNKNOWN),
            )
        }
        assertEquals("UNKNOWN 不得清零：streak 保持 1", 1, s.notGameStreakForTest())
        assertTrue("UNKNOWN 后第 2 拍 NOT_GAME 照样判死（计数保持）", s.onTick(GameDeathSentinel.Status.NOT_GAME))
    }

    @Test
    fun `IN_GAME 之后的 UNKNOWN 才是宽限 预热期 UNKNOWN 也不上岗`() {
        val s = GameDeathSentinel()
        repeat(5) { s.onTick(GameDeathSentinel.Status.UNKNOWN) }
        repeat(3) { s.onTick(GameDeathSentinel.Status.NOT_GAME) }
        assertFalse("预热期（UNKNOWN+NOT_GAME 混合）不得上岗判死", s.onTick(GameDeathSentinel.Status.NOT_GAME))
        assertFalse("随后见到 IN_GAME 才上岗（上岗是副作用）", s.onTick(GameDeathSentinel.Status.IN_GAME))
        assertFalse(s.onTick(GameDeathSentinel.Status.NOT_GAME))
        assertTrue(s.onTick(GameDeathSentinel.Status.NOT_GAME))
    }

    @Test
    fun `判死后幂等（重复喂任何状态恒 true）`() {
        val s = GameDeathSentinel()
        s.onTick(GameDeathSentinel.Status.IN_GAME)
        s.onTick(GameDeathSentinel.Status.NOT_GAME)
        assertTrue(s.onTick(GameDeathSentinel.Status.NOT_GAME))
        repeat(3) {
            assertTrue("已判死后任何喂拍恒 true（幂等）", s.onTick(GameDeathSentinel.Status.IN_GAME))
        }
    }

    @Test
    fun `fold 把三态折叠对齐`() {
        assertEquals(GameDeathSentinel.Status.IN_GAME, GameDeathSentinel.fold(true))
        assertEquals(GameDeathSentinel.Status.NOT_GAME, GameDeathSentinel.fold(false))
        assertEquals(
            "null（无障碍断开/未观察到窗口）= UNKNOWN",
            GameDeathSentinel.Status.UNKNOWN, GameDeathSentinel.fold(null),
        )
    }

    @Test
    fun `自定义确认拍数生效`() {
        val s = GameDeathSentinel(confirmPolls = 3)
        s.onTick(GameDeathSentinel.Status.IN_GAME)
        assertFalse(s.onTick(GameDeathSentinel.Status.NOT_GAME))
        assertFalse(s.onTick(GameDeathSentinel.Status.NOT_GAME))
        assertTrue("第 3 拍才判死", s.onTick(GameDeathSentinel.Status.NOT_GAME))
    }
}
