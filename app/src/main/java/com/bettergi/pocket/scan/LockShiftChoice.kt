package com.bettergi.pocket.scan

/**
 * 「锁钮首选位移没生效」之后**该不该补点第二个坐标**——纯决策，无 IO，可离线单测。
 *
 * ## 背景（#97）
 *
 * 同一件圣遗物的详情面板有两种布局：普通件锁钮在 `rect.centerY`，**祝圣件**因面板上方多一条
 * 横幅、横幅以下内容整体下移 [ScreenProfile.zhushengShiftPx]（3200 档 = 63px），锁钮随之下移。
 * 所以点击锁钮前要选一侧。
 *
 * 首选侧由**计划里的 `elixirCrafted`** 决定（[ScanEngine.lockClickAdaptive] 的 `firstShift`）。
 * 那个值来自扫描当时的判定，可能过期或缺失；于是原实现给了一条兜底：「首选位没生效 ⇒
 * 换另一侧再点一次」。这条兜底有两个问题：
 *
 * ① **对非祝圣件，`+shift` 那个位置根本没有锁钮**。同帧双掩码实测（见 `probeLockPixels`）：
 *    `零位 gold=871 red=900 | +63 gold=0 red=0` —— `+63` 处既不是金锁也不是红锁，像素计数全 0，
 *    是面板上的**另一个控件**。盲点它就是在一个没标定过的坐标上乱点。
 * ② **"换另一侧"在祝圣件上也是错的**：计划里写了 `elixirCrafted` 的项，`TaskMatch` 把它当**硬匹配
 *    字段**，能绑定上就说明该值成立 ⇒ `firstShift` **本就是正确的一侧**。真正需要换侧的情形只有
 *    「计划没给 `elixirCrafted`」这一种。
 *
 * ## 现在的判据
 *
 * 用**屏幕上的事实**（同帧紫横幅投票 `artifact.panel.zhusheng`，与解析路径的 crafted 同源）
 * 决定正确的一侧；**只在它与首选位不同时**才补点一次。相同 ⇒ 正确坐标已经点过、只是没生效，
 * 再点一次等于把锁切换回去（#96 的真因就是"同一坐标点两下、净效果归零"）。
 * 判据拿不到（zone 未标定 / 取帧失败）⇒ **不补点**：宁可直接失败，也不点没标定过的坐标。
 *
 * @param firstShift 首选位移（帧像素，0 或 [zhushengShift]）
 * @param zhushengShift 祝圣 yShift（帧像素）
 * @param craftedOnScreen 同帧横幅投票结果；`null` = 判据不可用
 * @return 要补点的位移（0 或 [zhushengShift]）；`null` = **不要补点**
 */
internal fun followUpLockShift(
    firstShift: Int,
    zhushengShift: Int,
    craftedOnScreen: Boolean?,
): Int? {
    val correct = when (craftedOnScreen) {
        null -> return null // 判据不可用 ⇒ 不点未标定坐标
        true -> zhushengShift
        false -> 0
    }
    // 相同 ⇒ 正确坐标已经点过一次，补点会把锁切回原态
    return if (correct == firstShift) null else correct
}
