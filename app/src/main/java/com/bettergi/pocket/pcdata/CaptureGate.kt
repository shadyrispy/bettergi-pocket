package com.bettergi.pocket.pcdata

import java.util.concurrent.atomic.AtomicLong

/**
 * 同一时刻只允许一条抓包通路（在线隧道 / pcap 回放）。
 *
 * 两条通路都会 `close() + initNative()` 重建原生 sniffer，而 sniffer 是**进程级单例**
 * （库的 `IrminsulCapture` 因此是 object 而不是可构造的会话）⇒ 并发跑就是互相把对方的
 * 收集状态清掉，而且谁都不报错。这里只做"占用/让位"、不排队：抓包是用户主动动作，
 * 撞车时明确拒绝比悄悄串行更有用。
 *
 * ★ 释放要**凭据**（2026-09-23 审计 P2-1）：原先 `exit()` 无条件置空，而"谁在收尾"和
 * "锁是谁拿的"不一定对得上 —— 旧会话的 watcher 还在 `delay` 里，用户已经「停止 → 立刻重开」
 * ⇒ 新会话刚 `tryEnter()` 成功，旧 watcher 醒来执行 `exit()` 就把**别人的**锁放了
 * ⇒ 一次 pcap 回放能与在线会话并发，正是本闸门要拦的那件互踩事。
 * ⇒ [tryEnter] 发一个递增凭据，[exit] 只认自己那一份；放别人的锁是**空操作**。
 */
internal object CaptureGate {

    /** 0 = 空闲；>0 = 当前持有者的凭据。 */
    private val holder = AtomicLong(0L)
    private val seq = AtomicLong(0L)

    /** @return 0 = 已有抓包在跑（调用方把原因回给用户）；>0 = 本次的凭据，[exit] 时原样交回。 */
    fun tryEnter(): Long {
        val mine = seq.incrementAndGet()
        return if (holder.compareAndSet(0L, mine)) mine else 0L
    }

    /** 交还凭据。凭据为 0、或锁已易主 ⇒ 空操作（绝不替别人开门）。 */
    fun exit(ticket: Long) {
        if (ticket != 0L) holder.compareAndSet(ticket, 0L)
    }
}
