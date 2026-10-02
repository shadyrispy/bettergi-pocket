package com.bettergi.pocket.scan

/**
 * `foreach` 末尾的**结果状态映射**（对齐 GOODScanner `InstructionStatus` 的子集）。
 *
 * 纯决策、无 IO，可离线单测 —— 抽出来是因为 #93 暴露的正是这张表的**输入**没人填：
 * 装配链一次都不置 `matchHit/actTried/actOk`，于是标签与实际做的事无关
 * （`parsePanel` 未声明 `match` 时 `matchHit` 被无条件置 true ⇒ **每一项都报 AlreadyCorrect**）。
 *
 * 判据的语义（与 `ScanVars` 的 KDoc 一致）：
 * - `matchHit`：本格**确实选中了**目标件（由 `stopWhen expr="panelMatch(...)"` 或 `planMatch` 置）；
 * - `actTried`：确实**尝试过写入**（锁钮 / 装配钮点过）；
 * - `actOk`：**动作按语义正确发出**（锁：verify 通过；装配：按钮文本为「装/替」且点了）；
 * - `verified`（#107）：**动作发出之后**独立回读到的结果。`null` = 没复核或读不出。
 *
 * ⚠️ `Success` 从 #107 起**要求 `verified == true`**。在此之前它只证明"点过了"，
 *   而 2026-09-28 实测证明"点过"与"换上"可以脱钩（报 Success 的换装事后核对仍是原件）。
 *   读不回东西时记 `ClickedUnverified` 而不是硬判 `Failed` —— 那是我们**没看见**，
 *   不是它**没发生**；把两者混成一个标签，久了就没法区分"复核器坏了"和"装配真在失败"。
 *
 * @param flowStop 整轮停止（exit/watchdog）——**不是**"本目标的网格止扫"（stopWhen/maxPages），
 *   后者是本条扫描的正常收尾，应记 NotFound 而不是 Skipped（真机实测：4 目标里后 3 个被误记 Skipped）。
 */
internal fun manageStatusOf(
    matchHit: Boolean,
    actTried: Boolean,
    actOk: Boolean,
    verified: Boolean?,
    flowStop: Boolean,
): String = when {
    matchHit && actTried && actOk && verified == true -> "Success"
    matchHit && actTried && actOk && verified == null -> "ClickedUnverified"
    matchHit && actTried -> "Failed"
    matchHit -> "AlreadyCorrect"
    flowStop -> "Skipped"
    else -> "NotFound"
}
