package com.bettergi.pocket.scan

/**
 * 角色扫描「连续重复」收尾判据（2026-09-11 用户定：**连续 3 个重复角色 = 遍历完**）。
 *
 * 为什么需要它：
 * - `pagedGrid` 的 `reachedEnd`（翻页前后指纹不变）只对**非回卷**列表有效；Genshin 左侧头像条
 *   可**循环滚动**，回卷时永远不触发 ⇒ 需要一个与"到底"无关的收尾判据。
 * - 滑动失效（起点落在触摸区外等）也会表现为"读到已扫过的角色"，本判据同样能兜住。
 *
 * 「重复」定义：该角色的**词典身份 key**（`mappings.characters` 的 id，角色全局唯一）**已在本轮入库过**。
 * ⚠️ **不按名字判**：两个不同角色若 OCR 后名字撞了，按名字判会**误判重复而丢件**；
 * 且名字可能读错（原样回落）。而 `key == null`（词典未解析出）时**既不算重复也不清零**——
 * 宁可多扫一个也不可错杀（实测风险：88 件 vs 已知 92 件）。
 *
 * ⚠️ 三个易错点（写成纯函数就是为了能被单测钉住）：
 * 1. **只能在拿到名字的那一刻求值**：`emitCharacter` 末尾会清空 `charName`，而 flow 的 `stopWhen`
 *    排在 `emit` 之后 ⇒ 在 `stopWhen` 里读名字恒为空（原 `name == roster[0]` 判据就是这样静默失效的）。
 * 2. **非重复必须清零**，否则"零散重复"会累积成误停。
 * 3. `limit <= 0`（未登记）= 完全不介入，保证未启用该模式的流程行为不变。
 */
object CharDupJudge {

    /**
     * @param seen 已成功入库角色的 **key** 集合（`GoodCharacter.key`）
     * @param key 当前角色的词典 key（`null` = 未解析出身份 → 不介入）
     * @param streak 上一次的连续重复计数
     * @param limit 阈值（`<=0` = 未启用）
     * @return `新计数 to 是否应止扫`
     */
    fun step(seen: Collection<String>, key: String?, streak: Int, limit: Int): Pair<Int, Boolean> {
        if (limit <= 0) return streak to false      // 未启用：完全不介入（计数也不动）
        if (key == null) return streak to false     // 身份未知：既不算重复也不清零（避免丢件）
        val dup = seen.contains(key)
        val next = if (dup) streak + 1 else 0
        return next to (dup && next >= limit)
    }
}
