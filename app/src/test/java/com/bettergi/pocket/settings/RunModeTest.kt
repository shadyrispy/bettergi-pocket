package com.bettergi.pocket.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * ★ #310/M0：RunMode 的序列化语义。
 *
 * 钉三件事：
 * 1. **wire 双向**——`fromWire(wire)` 是 `toBundle` 的逆（跨进程 Bundle 通道不丢档位）；
 * 2. **未知值回落 FOREGROUND**——旧版本 app / 脏数据写进 prefs 时，行为 = 改动前（保守）；
 * 3. **null = FOREGROUND**——旧 prefs 无此键（升级用户第一启动）的缺省语义。
 * 这三条是"加字段零行为变化"的全部承重点：任何消费方在 FOREGROUND 下的行为
 * 必须与没有 runMode 字段时逐位一致。
 */
class RunModeTest {

    @Test
    fun `wire 双向无损`() {
        for (m in RunMode.entries) {
            assertEquals("wire($m) 应能还原", m, RunMode.fromWire(m.wire))
        }
    }

    @Test
    fun `未知值回落 FOREGROUND`() {
        assertEquals(RunMode.FOREGROUND, RunMode.fromWire("sideload"))
        assertEquals(RunMode.FOREGROUND, RunMode.fromWire(""))
    }

    @Test
    fun `null 缺省 FOREGROUND（旧 prefs 升级路径）`() {
        assertEquals(RunMode.FOREGROUND, RunMode.fromWire(null))
    }

    @Test
    fun `TriggerSettings 缺省即前台（新增字段默认值）`() {
        val s = TriggerSettings(
            screenShareEnabled = false,
            autoPickEnabled = false,
            autoSkipEnabled = false,
            quickSkipDialogueEnabled = true,
        )
        assertEquals("不指定 runMode 的构造 = FOREGROUND", RunMode.FOREGROUND, s.runMode)
    }

    @Test
    fun `两档可区分（不是恒等枚举）`() {
        assertNotEquals(RunMode.FOREGROUND, RunMode.BACKGROUND)
    }
}
