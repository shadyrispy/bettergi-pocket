package com.bettergi.pocket.pcdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CaptureGate] 的凭据语义 —— 抓包只有一条通路能持锁，且**释放只认自己那份凭据**。
 *
 * 守的是审计 ①-d 的第一条：`CaptureSession.stop()` 现在按 `gateTicket != 0` 决定要不要拆
 * 进程级管线，而这个前提正是"非持有者 exit 是空操作"。P2-1 之前 `exit()` 无条件置空，
 * 旧会话的 watcher 醒来就能把新会话的锁放掉 ⇒ 一次 pcap 回放能与在线会话并发，
 * 两条通路互相 `close() + initNative()` 清对方的收集状态且都不报错。
 *
 * 闸门是 `object`（进程级全局），所以用例按顺序自己收尾，并在末尾断言回到空闲态。
 */
class CaptureGateTest {

    @Test
    fun `first entrant gets a non-zero credential and the gate blocks the second`() {
        val mine = CaptureGate.tryEnter()
        try {
            assertNotEquals("首次占用必须拿到非零凭据", 0L, mine)
            assertEquals("持锁期间第二个来占必须被拒（返回 0）", 0L, CaptureGate.tryEnter())
        } finally {
            CaptureGate.exit(mine)
        }
    }

    @Test
    fun `releasing someone else s credential is a no-op`() {
        val holder = CaptureGate.tryEnter()
        assertNotEquals(0L, holder)
        // 别的会话（凭据 0 = 它自己没占到）与另一个真实号码都不许替持有者开门
        CaptureGate.exit(0L)
        CaptureGate.exit(if (holder == 1L) 2L else 1L)
        assertEquals("非持有者 exit 之后锁必须还在 ⇒ 第二路仍被拒", 0L, CaptureGate.tryEnter())
        CaptureGate.exit(holder)
    }

    @Test
    fun `owner release frees the gate and the next entrant gets a fresh credential`() {
        val first = CaptureGate.tryEnter()
        CaptureGate.exit(first)
        val second = CaptureGate.tryEnter()
        try {
            assertNotEquals(0L, second)
            assertTrue("凭据必须递增（复用一个号码就无法区分新旧会话）", second > first)
        } finally {
            CaptureGate.exit(second)
        }
    }

    @Test
    fun `gate ends free`() {
        // 前三条各自收尾；这里再确认没有泄漏的持锁状态污染同 JVM 里的其他测试
        val probe = CaptureGate.tryEnter()
        assertNotEquals("闸门收尾时必须是空闲的", 0L, probe)
        CaptureGate.exit(probe)
    }
}
