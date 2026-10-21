package com.bettergi.pocket.scan

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #93 抽出、#107 扩写：`foreach` 结果状态映射。
 *
 * #93 的病根是这张表的**输入没人填**（修前 `auto_equip` 走 `parsePanel` 不声明 `match`
 * ⇒ `matchHit` 恒 true ⇒ 每一项都落 AlreadyCorrect，连真没找到的也一样）。
 * #107 的病根相反：输入都填了，但 `actOk` 的含义只是"按钮文本判出来该点、且点了"，
 * **不含点击后的回读** ⇒ `Success` 只证明"我们点过了"。真机撞上过一次报 Success 的换装
 * 事后核对服务端仍是原件（GOODScanner 的 `verify_artifact_equipped` 本身是 `Ok(true)` 的
 * no-op，所以这是同源缺陷）。⇒ 从此 `Success` 还要 `verified == true`。
 *
 * ⚠️ 三档"没确认"必须分开：`verified == false` 是**看见它没生效**（Failed），
 * `verified == null` 是**没看见**（ClickedUnverified）。合成一档就再也分不清
 * "复核器坏了/昵称表没填"和"装配真在失败"。
 */
class ManageStatusTest {

    @Test
    fun `hit plus verified action is success`() {
        assertEquals(
            "Success",
            manageStatusOf(matchHit = true, actTried = true, actOk = true, verified = true, flowStop = false),
        )
    }

    @Test
    fun `clicked but not verified is its own state, not success`() {
        // #107 的本体：点过了、但装备者栏读不出人（昵称表没填 / ROI 空）⇒ 不许冒充 Success，
        // 也不许冒充 Failed（我们没观察到失败，只是没观察到成功）。
        assertEquals(
            "ClickedUnverified",
            manageStatusOf(matchHit = true, actTried = true, actOk = true, verified = null, flowStop = false),
        )
    }

    @Test
    fun `verification saying it did not apply is a hard failure`() {
        // 回读到的装备者不是目标 ⇒ 确实没换上。
        assertEquals(
            "Failed",
            manageStatusOf(matchHit = true, actTried = true, actOk = true, verified = false, flowStop = false),
        )
    }

    @Test
    fun `hit plus attempted but unverified action is failed`() {
        // 点了但连"该不该点"都判不出（按钮文本读不出）⇒ 不许报成 AlreadyCorrect
        assertEquals(
            "Failed",
            manageStatusOf(matchHit = true, actTried = true, actOk = false, verified = null, flowStop = false),
        )
    }

    @Test
    fun `hit with no action needed is already correct`() {
        // 装配链读到「卸下」⇒ 不点 ⇒ 落这里（这是 AlreadyCorrect 的**唯一**合法来源）
        assertEquals(
            "AlreadyCorrect",
            manageStatusOf(matchHit = true, actTried = false, actOk = false, verified = null, flowStop = false),
        )
    }

    @Test
    fun `no hit and clean end is not found`() {
        // #93 修前 auto_equip 的**真没找到**也会落 AlreadyCorrect（matchHit 被污染）；
        // 修后同样输入 ⇒ NotFound。这一条就是本用例的回归护栏。
        assertEquals(
            "NotFound",
            manageStatusOf(matchHit = false, actTried = false, actOk = false, verified = null, flowStop = false),
        )
    }

    @Test
    fun `only a full-run stop is skipped`() {
        // flowStop = 整轮停止（exit/watchdog）。"本目标的网格止扫"（stopWhen/maxPages）不算 ——
        // 那时 flowStop 已是 false，落 NotFound（走完了、没命中），这是正确的收尾语义。
        assertEquals(
            "Skipped",
            manageStatusOf(matchHit = false, actTried = false, actOk = false, verified = null, flowStop = true),
        )
    }

    @Test
    fun `hit outranks flow stop`() {
        // 命中优先于整轮停止：确实选中过目标就不该报 Skipped
        assertEquals(
            "AlreadyCorrect",
            manageStatusOf(matchHit = true, actTried = false, actOk = false, verified = null, flowStop = true),
        )
    }
}
