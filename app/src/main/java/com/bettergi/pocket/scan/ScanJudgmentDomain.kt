package com.bettergi.pocket.scan

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Mat
import com.bettergi.pocket.scan.ScanEngine.Companion.CARD_MOVED_MIN_BLOCKS
import com.bettergi.pocket.scan.ScanEngine.Companion.CARD_SIG_BLOCKS
import com.bettergi.pocket.scan.ScanEngine.Companion.CLICK_SETTLE_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.EQUIP_VERIFY_SETTLE_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.EQUIP_VERIFY_TRIES
import com.bettergi.pocket.scan.ScanEngine.Companion.FILTER_PANEL_BACK_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.FILTER_PANEL_POLL_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.FILTER_PANEL_STEP_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.LOCK_CLEAN_BEFORE_ABSENT
import com.bettergi.pocket.scan.ScanEngine.Companion.LOCK_PRESS_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.PANEL_CHANGE_WAIT_MAX_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.REVISIT_BACKOFF_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.SETTLE_LOCK_BUDGET_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.ZHUSHENG_CONFIRM_POLL_MS
import com.bettergi.pocket.scan.ScanEngine.Companion.SIG_BLOCKS_FINE_X
import com.bettergi.pocket.scan.ScanEngine.Companion.SIG_BLOCKS_FINE_Y
import com.bettergi.pocket.scan.ScanEngine.Companion.SIG_BLOCKS_X
import com.bettergi.pocket.scan.ScanEngine.Companion.SIG_BLOCKS_Y
import com.bettergi.pocket.scan.ScanEngine.Companion.SIG_CONFIRM_RETRIES
import com.bettergi.pocket.scan.ScanEngine.Companion.SIG_LEN
import com.bettergi.pocket.scan.ScanEngine.Companion.SIG_LEN_MAX
import com.bettergi.pocket.scan.ScanEngine.Companion.SIG_STABLE_SAMPLES
import com.bettergi.pocket.scan.ScanEngine.Companion.TAG
import com.bettergi.pocket.scan.ScanEngine.Companion.WEAPON_SAME_IDENTITY_CAP
import com.bettergi.pocket.scan.ScanEngine.Companion.WINDOW_MIN_CELLS
import com.bettergi.pocket.scan.ScanEngine.Companion.WINDOW_SETTLE_MS
import com.bettergi.pocket.log.RecognitionLog
import com.bettergi.pocket.notice.NoticeCenter
import com.bettergi.pocket.core.IntRect
import com.bettergi.pocket.scan.ScanEngine.Companion.equippedOwnerOf

/*
 * Stage 3.3 步④：ScanJudgmentDomain（do 原语按族拆分，扩展函数，函数体一字未改）。
 */

internal suspend fun ScanEngine.isCurrentlyEquipped(): Boolean {
    val gateway = ocr ?: return false
    val arr = profile.rawObject("panels.artifact_manage")?.optJSONArray("equipped") ?: return false
    if (arr.length() < 4) return false
    val r = profile.scaleRect(arr.getInt(0), arr.getInt(1), arr.getInt(2), arr.getInt(3))
    val frame = try {
        freshFrame()
    } catch (e: CancellationException) { throw e } catch (_: Exception) {
        return false
    }
    return try {
        // ⚠️ 2026-09-29 修正：原判据是"那格有没有字"，但**没人穿的件那格显示的是描述文字**
        //   （真机实测）⇒ 恒为 true ⇒ 卸下后还会多跑一遍复位链。改为只看「XX已装备」那行在不在。
        val text = gateway.readLines(frame, listOf(r)).joinToString(" ")
        equippedOwnerOf(StatParser.clean(text)) != null
    } finally {
        frame.release()
    }
}

/**
 * 装备者显示名 → GOOD key：#105 的用户昵称表优先，其次官方词典（含模糊）。认不出 ⇒ null。
 * 三处消费（武器 location / 圣遗物 location / 装配后复核）必须同一口径，否则同一个名字在
 * 导出里是一个键、在回执里是另一个键，对账时无从归因。
 */
internal suspend fun ScanEngine.verifyEquippedBy(expectChar: String, dict: JSONObject?): Boolean? {
    val gateway = ocr ?: return null
    val arr = profile.rawObject("panels.artifact_manage")?.optJSONArray("equipped") ?: return null
    if (arr.length() < 4) return null
    val r = profile.scaleRect(arr.getInt(0), arr.getInt(1), arr.getInt(2), arr.getInt(3))
    var last = EquipVerify.NOTHING_READ
    var lastOwner: String? = null
    for (attempt in 0 until EQUIP_VERIFY_TRIES) {
        val text = runCatchingCancellable {
            val f = freshFrame()
            try {
                gateway.readLines(f, listOf(r)).joinToString(" ")
            } finally {
                f.release()
            }
        }.getOrNull()
        lastOwner = equippedOwnerOf(text)
        last = equipVerifyOf(lastOwner, expectChar) { characterKeyOfDisplay(it, dict) }
        // ⚠️ 连 NOT_APPLIED 也要重试：换装是服务端往返，回读窗口里那一格**可能还写着上一个
        //   持有者**（目标件原本穿在行秋身上时尤其明显）⇒ 首读见到"别人"不能立刻判失败。
        if (last == EquipVerify.APPLIED) {
            if (attempt > 0) Log.i(TAG, "verifyEquip: 第 ${attempt + 1} 次回读才确认 '$expectChar'")
            return true
        }
        if (attempt < EQUIP_VERIFY_TRIES - 1) delay(EQUIP_VERIFY_SETTLE_MS)
    }
    Log.w(
        TAG,
        "verifyEquip: ${EQUIP_VERIFY_TRIES} 次回读仍未确认（装备者栏='${lastOwner ?: "<空>"}' " +
            "期望 '$expectChar'，判据=$last）⇒ " +
            if (last == EquipVerify.NOT_APPLIED) "记 Failed" else "记 ClickedUnverified（没看见，不判成败）",
    )
    return last.verifiedFlag()
}

/**
 * **卸下意图**的点击后复核：读同一个 `panels.artifact_manage.equipped` ROI，
 * **读空 = 卸干净了**（`APPLIED`），**还有字 = 还穿在谁身上**（`NOT_APPLIED`）。
 *
 * ⚠️ 与 [verifyEquippedBy] 判据**相反**（那边"空"是没看见），所以不复用它的名字解析：
 * 卸下只关心"那一格空没空"，不关心是谁。取帧/OCR 抛错 ⇒ `null`（没看见 ≠ 没发生）。
 */
internal suspend fun ScanEngine.verifyUnequipped(): Boolean? {
    val gateway = ocr ?: return null
    val arr = profile.rawObject("panels.artifact_manage")?.optJSONArray("equipped") ?: return null
    if (arr.length() < 4) return null
    val r = profile.scaleRect(arr.getInt(0), arr.getInt(1), arr.getInt(2), arr.getInt(3))
    var last = EquipVerify.NOTHING_READ
    var lastText: String? = null
    for (attempt in 0 until EQUIP_VERIFY_TRIES) {
        val text = runCatchingCancellable {
            val f = freshFrame()
            try {
                gateway.readLines(f, listOf(r)).joinToString(" ")
            } finally {
                f.release()
            }
        }.getOrNull()
        lastText = text
        last = unequipVerifyOf(readOk = text != null, owner = equippedOwnerOf(text))
        if (last == EquipVerify.APPLIED) {
            if (attempt > 0) Log.i(TAG, "verifyUnequipped: 第 ${attempt + 1} 次回读才确认已卸下")
            return true
        }
        if (attempt < EQUIP_VERIFY_TRIES - 1) delay(EQUIP_VERIFY_SETTLE_MS)
    }
    Log.w(
        TAG,
        "verifyUnequipped: ${EQUIP_VERIFY_TRIES} 次回读仍未确认卸下（装备者栏='${lastText ?: "<读失败>"}'，" +
            "判据=$last）⇒ " + if (last == EquipVerify.NOT_APPLIED) "记 Failed" else "记 ClickedUnverified",
    )
    return last.verifiedFlag()
}

/**
 * 读左下动作钮（`screens.artifact_manage.leftBtn`）的文本 —— **#93 的真回执判据**。
 *
 * 判据来源：GOODScanner `ui_actions.rs::click_equip_button_safe_at` 就是 OCR 这颗钮
 * （`SEL_ACTION_BUTTON_RECT`），含「卸」⇒ 已装备、不点；含「装/替」⇒ 点。
 * 我们这边 profile 的 `leftBtn.states` 登记了同样两态（「替换」/「卸下」）。
 *
 * 读失败一律返回 `null`（调用方走 `EquipAction.UNKNOWN` ⇒ **不点**、记 Failed）——
 * 该 ROI 只有 ~94×54 帧像素。
 * ★ 2026-09-28 BlueStacks@3200 真机已验**读得出来**：目标件正穿在该角色身上时读到
 *   `按钮='卸下'`（`dualStateButton … 不点击 ⇒ AlreadyCorrect`），整步耗时 11ms ⇒ 判据可用、
 *   代价可忽略。⚠️ 但「替换」那一侧**尚未在设备上验过**（跑它会真换装，需授权）⇒
 *   不能拿"读不到"当"没装"，也不能因为这条 ROI 在 3200 档读得动就假定别的档位同样读得动。
 */
internal suspend fun ScanEngine.readActionButtonText(rect: FrameRect): String? {
    val gateway = ocr ?: return null
    val frame = try {
        freshFrame()
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        Log.w(TAG, "readActionButtonText: 取帧失败（${e.message}）")
        return null
    }
    return try {
        val raw = gateway.readLines(frame, listOf(rect)).joinToString(" ")
        StatParser.clean(raw).takeIf { it.isNotEmpty() }
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        Log.w(TAG, "readActionButtonText: OCR 失败（${e.message}）")
        null
    } finally {
        frame.release()
    }
}

internal suspend fun ScanEngine.dualStateButton(step: JSONObject) {
    // §14 P2：ref 形态（"$screens.artifact_manage.leftBtn"）——非药丸双态，而是按 rect
    // 点击的动作按钮。states 描述语义：
    //   替换 → 点击后由后续 dialog(equipConfirm) 步骤确认（仅跨角色替换才弹窗）；
    //   卸下 → 无确认弹窗，且卸下后网格不回第一行 → 按 profiles resetChain 复位。
    val ref = step.optString("ref", "")
    if (ref.startsWith("$")) {
        val path0 = ref.removePrefix("$")
        // readonly（如 rightBtn 强化/重塑）：只判态、**绝不点击**——误点会进强化/重塑界面
        if (step.optBoolean("readonly", false)) {
            Log.i(TAG, "dualStateButton ref=$path0: readonly，仅判态不点击")
            return
        }
        val obj0 = profile.rawObject(path0)
        val rectArr = obj0?.optJSONArray("rect")
        if (rectArr != null && rectArr.length() >= 4) {
            val r = profile.scaleRect(
                rectArr.getInt(0), rectArr.getInt(1), rectArr.getInt(2), rectArr.getInt(3),
            )
            // ★★ #93：这颗钮的文本就是**真回执**（对齐 GOODScanner
            //   `ui_actions.rs::click_equip_button_safe_at`：先 OCR 按钮文本，含「卸」⇒ `AlreadyCorrect`
            //   且**不点**；含「装/替」⇒ 点它 ⇒ `Success`）。
            //   此前不读文本、闭眼点 + 不置任何状态 ⇒ 结果标签与实际做的事无关。
            val btnText = readActionButtonText(r)
            // ★ 2026-09-29 卸下意图：`char` 空 + `location` 非空 ⇒ 这条是要把这件**卸下来**
            //   （见 EquipIntent）。两侧"该点"的按钮语义正好相反，必须分开判。
            val intent = equipIntentOf(
                vars.currentTask?.optString("char"),
                vars.currentTask?.optString("location"),
            )
            val decision = equipDecisionOf(equipActionOf(btnText), intent)
            if (!decision.click) {
                // 两条"不点"的出口语义相反，日志必须分开打（否则 AlreadyCorrect 看起来像失败）：
                //  · 卸 ⇒ 目标件已在此角色身上，这是**正常态**，什么都不做 ⇒ AlreadyCorrect。
                //  · 读不出 ⇒ **也不点**（对齐参照实现：ui_actions.rs:2076 "Neither detected — bail"）。
                //    这一档曾经过"照点、只是不置回执"，那正是本案最危险的形态：按钮其实写着「卸下」、
                //    只是 OCR 没读出来，点下去就把该角色的这件装备**卸掉了**，标签还停在 AlreadyCorrect。
                //    记 actTried 不记 actOk ⇒ 落 Failed：宁可 loudly 失败，也不在判不出该不该动的时候
                //    动账号数据。
                if (decision.actTried) {
                    Log.w(
                        TAG,
                        "dualStateButton ref=$path0: 按钮文本判不出语义（读到='$btnText'）⇒ **不点击**，" +
                            "本项落 Failed（不记 Success/AlreadyCorrect）",
                    )
                } else if (intent == EquipIntent.UNEQUIP) {
                    Log.i(
                        TAG,
                        "dualStateButton ref=$path0: 卸下意图，按钮='$btnText'（替换/装备）⇒ 这件不在该角色身上" +
                            "（与『已卸干净』同形）⇒ 不点击 ⇒ AlreadyCorrect",
                    )
                } else {
                    Log.i(
                        TAG,
                        "dualStateButton ref=$path0: 按钮='$btnText'（卸下）⇒ 目标件已在该角色身上，" +
                            "不点击 ⇒ AlreadyCorrect",
                    )
                }
                vars.actTried = decision.actTried
                return
            }
            // ★★ 2026-09-29 #141：**装备动作必须有匹配依据**（本轮错装事故的正面修法）★★
            //   事故：`pagedGrid` 以「peek 消失（指纹不变）」收尾时 `vars.gridExhausted=false` ⇒
            //   `foreach` 那道防错装守卫（要求 `gridExhausted && !matchHit`）**没触发** ⇒ 走到这里
            //   照读「替换」照点 ⇒ 把屏幕上碰巧选中的那件（Lv0 角斗士死之羽）装给了目标角色，
            //   而回执仍写 NotFound。⇒ 判据不能挂在"网格到底"上（那是**另一个**坏消息）：
            //   **没命中就不该有点「替换」这个动作**，因为"该装哪件"这件事本身没有依据。
            //   判据取自 flow 自己声明的 states（只有装备链会声明「替换」/「装备」）⇒
            //   artifact_lock / artifact_scan 的五星开关（states=null）不受影响。
            val isEquipAction = step.optJSONObject("states")?.keys()?.asSequence()
                ?.any { it.contains("替换") || it.contains("装备") } == true
            if (isEquipAction && !vars.matchHit) {
                Log.e(
                    TAG,
                    "dualStateButton: 本项 matchHit=false（没有匹配到任何目标）⇒ **不点「替换」**" +
                        "（点了只会把屏上碰巧选中的那件装错）",
                )
                RecognitionLog.log(
                    logTag,
                    RecognitionLog.Level.W,
                    "未命中即止：无匹配依据，跳过「替换」（防错装）",
                )
                return
            }
            Log.i(
                TAG,
                // ★ P3（2026-09-30）：`+` 优先级只绑 else 分支 —— UNEQUIP 时 `）⇒ 点击…` 整段丢失。
                //   用括号把 when 表达式整体包住。
                "dualStateButton ref=$path0: 按钮='$btnText'（" +
                    (if (intent == EquipIntent.UNEQUIP) "卸下意图⇒点它把该件卸下来" else "替换/装备") +
                    "）⇒ 点击 (${r.centerX},${r.centerY})",
            )
            actions.click(r.centerX, r.centerY)
            delay(CLICK_SETTLE_MS)
            // ★ 回执（#93 → #107）：标签由 `foreach` 的状态映射按
            //   (matchHit, actTried, actOk, actVerified) 得出。
            //   `matchHit` 已由本格的 `stopWhen expr="panelMatch(...)"` 置（网格确实选中了目标件）。
            //   走到这里 = 按钮写着「装/替」⇒ 动作**该发**且已发出 ⇒ actOk。
            //   ⚠️ actOk **不等于**装上了。#107 实测过这个脱钩：报 Success 的换装，事后逐格核对
            //      服务端仍是原件。真正的确认要等**换装确认弹窗那一步走完**之后回读装备者栏，
            //      所以这里只登记"欠一次复核"，由 foreach 末尾去读（见 equipClicked）。
            //      跨角色的 `dialog(equipConfirm)` 也在复核之前 ⇒ 确认框没点掉就会落在
            //      ClickedUnverified/Failed，不会再像 #93 之前那样照样记 Success。
            vars.actTried = decision.actTried
            vars.actOk = decision.actOk
            vars.equipClicked = true
            val hasUnequip = step.optJSONObject("states")?.keys()?.asSequence()
                ?.any { it.contains("卸下") } == true
            if (hasUnequip && isCurrentlyEquipped()) {
                @Suppress("UNCHECKED_CAST")
                val chain = profile.rawAny("screens.artifact_manage.resetChain") as? JSONArray
                if (chain != null) {
                    Log.i(TAG, "dualStateButton: 卸下后网格复位（${chain.length()} 步）")
                    for (i in 0 until chain.length()) clickChainEntry(chain.getString(i))
                }
            }
            return
        }
        // 无 rect：带 pill（如 fiveStarToggle 五星筛选）→ 落到下方 pill 判态 ensure；
        //   两者皆无才跳过（原实现一律跳过，5★ 筛选 ensure 长期静默失效）
        if (obj0 == null) {
            Log.w(TAG, "dualStateButton ref '$path0' missing in profile")
            return
        }
        if (obj0.opt("pill") == null) {
            Log.w(TAG, "dualStateButton ref='$path0' 无 rect 且无 pill，跳过")
            return
        }
    }
    val path = ref.removePrefix("$")
    val obj = profile.rawObject(path) ?: run {
        Log.w(TAG, "dualStateButton ref '$path' missing in profile")
        return
    }
    // pill 两种形态兼容：纯 rect 数组 或 {rect:[...]} 对象
    val pillArr: JSONArray = when (val pill = obj.opt("pill")) {
        is JSONArray -> pill
        is JSONObject -> pill.optJSONArray("rect") ?: run {
            Log.w(TAG, "dualStateButton '$path'.pill has no rect")
            return
        }
        else -> {
            Log.w(TAG, "dualStateButton '$path' has no pill")
            return
        }
    }
    val ensure = step.optString("ensure", "off")
    var attempts = 0
    while (attempts < 3) {
        val frame = freshFrame()
        val state = try {
            pillState(frame, pillArr)
        } finally {
            frame.release()
        }
        if (state == ensure) {
            Log.i(TAG, "dualStateButton '$path' is $ensure")
            return
        }
        val rect = profile.scaleRect(
            pillArr.getInt(0), pillArr.getInt(1), pillArr.getInt(2), pillArr.getInt(3),
        )
        actions.click(rect.centerX, rect.centerY)
        delay(CLICK_SETTLE_MS)
        attempts++
    }
    Log.w(TAG, "dualStateButton '$path' could not reach '$ensure' after retries")
}

/**
 * 药丸底色判态：off=深藏青底（金像素≈0），on=金底（金像素占比过半）。
 * profiles: off="深藏青底+金圈×在左", on="金底+深✓在右"。
 */
internal fun ScanEngine.pillState(frameBgr: Mat, pillArr: JSONArray): String {
    val rect = profile.scaleRect(
        pillArr.getInt(0), pillArr.getInt(1), pillArr.getInt(2), pillArr.getInt(3),
    )
    val sample = FrameRect(
        rect.centerX - rect.width / 4,
        rect.centerY - rect.height / 4,
        rect.centerX + rect.width / 4,
        rect.centerY + rect.height / 4,
    )
    val gold = VoteJudges.countMatches(frameBgr, sample, VoteJudges.GOLD)
    val area = sample.width.coerceAtLeast(1) * sample.height.coerceAtLeast(1)
    return if (gold > area / 2) "on" else "off"
}

// ---- #2 readCount：右上总数 OCR（"圣遗物 1026/2400" → 1026；0 件时重进界面重试）----
internal suspend fun ScanEngine.readCount(step: JSONObject) {
    vars.totalRect = step.optString("rect").removePrefix("$").ifEmpty { null }
    if (ocr == null) {
        Log.w(TAG, "readCount skipped: OcrGateway not available")
        return
    }
    var n = readCountOnce(step, ocr)
    if (n == 0 && step.optString("onZero") == "reopenAndRetry") {
        // 读到 0：大概率停在了弹窗/半透明层——重跑入口链（退出重进）后再读一次。
        // ⚠️ 只重跑 `enterScreen`（点背包 → 锚点校验），**刻意不重跑 `filterReset`**：
        //    筛选是**游戏侧持久状态**，进入本页时已复位过；重进再开合一次筛选面板纯属多余，
        //    且会平白多一次「点到页面按钮 ⇒ 弹出全屏模态面板」的风险敞口。
        Log.w(TAG, "readCount got 0, reopening screen and retrying")
        enterScreenStep?.let { reentry -> enterScreen(reentry) }
        n = readCountOnce(step, ocr)
    }
    vars.total = n
}

internal suspend fun ScanEngine.readCountOnce(step: JSONObject, gateway: OcrGateway): Int? {
    val frame = freshFrame()
    return try {
        val rect = profile.rect(step.getString("rect").removePrefix("$"))
        gateway.readNumber(frame, rect)
    } finally {
        frame.release()
    }
}

/**
 * 收尾前的计数器复读。只用于"把 968 读成 96/668"这类**非零欠读** —— 那种读数原先没人
 * 再核一次，`collected >= total` 一满足就静默提前收尾，而总数不符告警在 stopRequested
 * 分支被刻意跳过，于是少扫一整屏也报"完成"。
 */
internal suspend fun ScanEngine.recountCounter(): Int? {
    val path = vars.totalRect ?: return null
    val gateway = ocr ?: return null
    return try {
        val frame = freshFrame()
        try {
            gateway.readNumber(frame, profile.rect(path))
        } finally {
            frame.release()
        }
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        Log.w(TAG, "计数器复读失败 ⇒ 沿用首次读数 ${vars.total}", e)
        null
    }
}

// ---- #3 pagedGrid：网格遍历编排 ----
internal fun ScanEngine.filterPanelCenter(key: String): IntArray? {
    val fp = profile.rawObject("screens.dialogs.filterPanel") ?: return null
    val r = fp.optJSONArray(key) ?: return null
    if (r.length() < 4) return null
    return intArrayOf((r.getInt(0) + r.getInt(2)) / 2, (r.getInt(1) + r.getInt(3)) / 2)
}

/** 点击 profile 基坐标点（缩放后）。 */
/**
 * **安全守卫：意外界面即中止**（★ 2026-09-18，事故驱动）。
 *
 * 事故：装配流程 `rosterFind` 静默失败后一路点漂，把游戏带到 **「确认退出游戏」** 弹窗
 * （截图 `产物/bs-20260918/05_装配卡住现场.png`）—— 再多点一次「确认」游戏就退了，
 * 而既有 `assertScreen(onFail=back)` **没拦住**。
 *
 * 语义：**每次点击之前**先判"当前是不是已知危险界面"；是则**立即中止整条流程**
 * （`stopReason="safety"` + ERROR 提醒，走 NoticeCenter 唯一通路）。
 * **绝不"点掉它再继续"** —— 中止是唯一安全动作，交还用户。
 *
 * 判据：OCR `screens.dialogs.quitConfirm.title`，出现「退出」即命中。
 * 二字关键词同时覆盖派蒙菜单里的「退出游戏」项（同一处文本），一处判据挡两种入口。
 * 拿不到 OCR / profile 缺项 / 读帧失败 ⇒ **放行**（守卫不应成为新的故障源）。
 */
internal fun ScanEngine.lockOverlayWhiteRatio(frame: Mat, probe: FrameRect): Double {
    var white = 0
    var total = 0
    var y = probe.top
    while (y < probe.bottom) {
        var x = probe.left
        while (x < probe.right) {
            val px = frame.get(y, x)
            val v = ((px[0].toInt() and 0xFF) + (px[1].toInt() and 0xFF) + (px[2].toInt() and 0xFF)) / 3
            if (v > 225) white++
            total++
            x += 12
        }
        y += 12
    }
    return if (total == 0) 0.0 else white.toDouble() / total
}
/**
 * 双区判"已锁"：普通位与祝圣位任一击中即算（跨 `zhushengShiftPx` 位移）。
 * 原为 [lockClickAdaptive] 内的局部 lambda ⇒ 抽成成员，供 [settleLockState] 共用。
 */
internal fun ScanEngine.lockVoteAny(frame: Mat): Boolean =
    VoteJudges.panelLock(frame, profile, 0).matched ||
        VoteJudges.panelLock(frame, profile, profile.zhushengShiftPx).matched

/**
 * 面板锁钮点击（fast.at=$zones.artifact.panel.lock 专用）。
 *
 * 2560 实测：同一单件名存在普通（锁徽 center 2309,744）与祝圣（+zhushengShiftPx ≈808）两种面板，
 * 固定坐标必漏一种。
 *
 * ★ 2026-09-18（P2⑧）两处修正：
 * 1. **方向**：原来的"双区判定已锁 ⇒ 跳过点击"只看**当前态**、不看**目标态** ⇒
 *    解锁方向（curLock=true、wantLock=false）会被这里静默跳过 ⇒ **永远解不了锁**。
 *    现在改成 `当前态 == 目标态 才跳过`，两个方向都正确。
 * 2. **确定性选位**：目标**显式**给了 `elixirCrafted` 时直接按它选普通位/祝圣位
 *    （GOODScanner：`Lock toggles use the scanned piece's elixirCrafted to apply a 40px Y-shift`），
 *    省掉"先点普通位 → 回读 → 未生效再点祝圣位"的第二次点击。
 *
 * ★ 2026-09-27（#97）：未生效后的兜底改为 [followUpLockShift] —— 见那个函数的 KDoc。
 *   原来无条件"换另一侧 +zhushengShiftPx"，对**非祝圣件**那是个没标定过的坐标
 *   （同帧实测 gold=0/red=0）；对**祝圣件**则等于把刚点过的位置再点一次（净效果归零）。
 *
 * ★ 2026-10-14（#279）：计划**没给** `elixirCrafted` 时，选位改用**屏幕事实**（同预读帧的紫横幅投票），
 *   不再盲猜普通位。实测（BS 2244×1080，task 缺失 elixirCrafted 的祝圣件）：旧行为首选 0 落空 ⇒
 *   settle 烧满 90s/46 轮才走到兜底；改后首选即 +sh、一轮内收敛。判据不可用 ⇒ 退回普通位（旧行为）。
 */
internal suspend fun ScanEngine.lockClickAdaptive(): Pair<Int, Int> {
    val obj = profile.zone("artifact.panel.lock")
        ?: throw IllegalStateException("zone artifact.panel.lock missing")
    val r = obj.getJSONArray("rect")
    val rect = profile.scaleRect(r.getInt(0), r.getInt(1), r.getInt(2), r.getInt(3))
    val sh = profile.zhushengShiftPx
    // 结果契约：一旦真的要动锁钮就记"尝试过写入"（后面 settleLockState 决定成败）。
    // 两个标志都置：`actTried` 供 foreach 状态映射，#93 起另置 `lockWriteAttempted`
    // 专供"加锁确认框只可能在锁写入后出现"那条探测闸门（装配链不碰它）。
    vars.actTried = true
    vars.lockWriteAttempted = true
    // 目标态：来自 {lock,unlock} 清单归一出的项内 wantLock（缺省 true = 应锁定）
    val desired = vars.currentTask?.optBoolean(GoodPlan.KEY_WANT_LOCK, true) ?: true
    // 计划里的 elixirCrafted：显式给了就采信，没给则用屏幕事实选位（见下）
    val wantElixir = vars.currentTask?.let {
        if (it.has("elixirCrafted")) it.optBoolean("elixirCrafted") else null
    }
    // 同一帧取两个事实：当前锁态 + 屏幕祝圣态（紫横幅投票，与 [parseArtifactPanel] 的 crafted 同源）。
    // ★ #279：屏幕祝圣态以前只在**补点兜底**里读，于是"计划没给 elixirCrafted"时首选位盲猜 0；
    //   祝圣件得等 settle 预算烧完（实测 90s、46 轮）才走到兜底。这里提前读，选位直接用屏幕事实。
    val f = freshFrame()
    val pre: Boolean
    val screenCrafted: Boolean?
    try {
        pre = lockVoteAny(f)
        screenCrafted = runCatchingCancellable {
            VoteJudges.panelZhusheng(f, profile).matched
        }.getOrElse { e ->
            Log.w(TAG, "lockClickAdaptive: 祝圣横幅判据不可用（${e.message}）⇒ 按计划/默认选位")
            null
        }
    } finally {
        f.release()
    }
    // 确定性选位：显式给了 elixirCrafted 就按它；没给就用**屏幕事实**（判据不可用 ⇒ 退回普通位）。
    val firstShift = when (wantElixir) {
        true -> sh
        false -> 0
        null -> if (screenCrafted == true) sh else 0
    }
    // ★ 只有"当前态已等于目标态"才可跳过 —— 原来只看"已锁"⇒ 解锁方向被静默跳过
    if (pre == desired) {
        Log.i(TAG, "lockClickAdaptive: 已处于目标态（locked=$pre desired=$desired），跳过点击")
        return rect.centerX to rect.centerY
    }
    Log.i(
        TAG,
        "lockClickAdaptive: locked=$pre desired=$desired elixirCrafted=$wantElixir " +
            "屏幕祝圣=$screenCrafted 首选位移=$firstShift",
    )
    // 原语取 `click`（2px 微滑 + 按压）。⚠️ **tap vs click 之争是伪命题**：
    //   09-18「真机 clickLocal accepted=true 但毫无反应」与 09-27「纯 tap 三轮全背包锁态零变化」
    //   两条互相矛盾的取证，其实都被**同一个真 bug** 污染了 —— 调用方（click 步骤）在
    //   `lockClickAdaptive()` 点完之后，又把返回的同一坐标**点了一遍**（详见 click 步骤里
    //   `clickedWhileResolving` 闸门）⇒ 锁被切两下 = 净效果归零。用"净效果归零"的路径去
    //   判别哪种原语有效，两种都能"证伪"，所以那两条结论都不作数。
    //   唯一干净的证据是 09-27 的 `DEBUG_CLICK`（外部单次微滑）：一次就翻态 ⇒ 坐标与注入通道正常。
    //   这里保留 `click` 是因为它与那条唯一有效的取证同形，**不是**因为 tap 被判过刑。
    actions.click(rect.centerX, rect.centerY + firstShift, LOCK_PRESS_MS)
    delay(CLICK_SETTLE_MS)
    if (settleLockState(desired)) return rect.centerX to (rect.centerY + firstShift)
    // ★★ #97：首选位未生效时的兜底 —— **只点"屏幕上真的是锁钮"的那一侧，且绝不重复点同一坐标** ★★
    //   判据与完整理由见 [followUpLockShift]（纯函数、有单测）。
    //   屏幕祝圣态复用**预读帧**那次探测（点击不会改变横幅，不再另取帧）。
    val craftedOnScreen = screenCrafted
    val followUp = followUpLockShift(firstShift, sh, craftedOnScreen)
    if (followUp == null) {
        Log.w(
            TAG,
            "lockClickAdaptive: 首选位($firstShift)未生效 ⇒ 不补点" +
                "（屏幕祝圣态=$craftedOnScreen 与首选位同侧，或判据不可用）",
        )
        return rect.centerX to (rect.centerY + firstShift)
    }
    Log.i(
        TAG,
        "lockClickAdaptive: 首选位($firstShift)未生效 ⇒ 按屏幕祝圣态($craftedOnScreen)补点 +$followUp",
    )
    actions.click(rect.centerX, rect.centerY + followUp, LOCK_PRESS_MS)
    delay(CLICK_SETTLE_MS)
    val ok = settleLockState(desired)
    Log.i(TAG, "lockClickAdaptive: 补点($followUp) → ${if (ok) "达到目标态" else "仍未达目标态"}")
    return rect.centerX to (rect.centerY + followUp)
}

/**
 * 诊断（#96 定案；2026-10-21 校正）：同帧打**判据实际使用的** [VoteJudges.WARM_LOCK] 计数，
 * 旧 gold/red 只作历史对照。
 *
 * ⚠️ 原 KDoc 写「未锁 = gold=0 且 red=0」**只在 2560 大档成立**：1280 档的灰开锁按钮
 * （中性灰蓝 R≈G≈B≈170）有抗锯齿暗边漏进 RED_LOCK，实测 52 命中 > 门槛 10 ⇒ **这就是
 * a3/a5/a6 每轮 2 件「未锁读成已锁」的根因**。暖色域下未锁 0 命中、已锁 201–312。
 * 留双色域是为了"读数自相矛盾"类问题能一次分辨，但**判决只看 warm**。
 */
internal fun ScanEngine.probeLockPixels(frame: Mat, tag: String) {
    val obj = profile.zone("artifact.panel.lock") ?: return
    val r = obj.getJSONArray("rect")
    val base = profile.scaleRect(r.getInt(0), r.getInt(1), r.getInt(2), r.getInt(3))
    val sh = profile.zhushengShiftPx
    val a = base.shiftedBy(0)
    val b = base.shiftedBy(sh)
    val thr = profile.countThreshold(VoteJudges.Thresholds.PANEL_LOCK_GOLD)
    Log.i(
        TAG,
        "lockPixels[$tag]: 零位 warm=${VoteJudges.countMatches(frame, a, VoteJudges.WARM_LOCK)}" +
            " (阈值$thr) | +$sh warm=${VoteJudges.countMatches(frame, b, VoteJudges.WARM_LOCK)}" +
            " || 旧域 gold=${VoteJudges.countMatches(frame, a, VoteJudges.GOLD)}" +
            "/${VoteJudges.countMatches(frame, b, VoteJudges.GOLD)}" +
            " red=${VoteJudges.countMatches(frame, a, VoteJudges.RED_LOCK)}" +
            "/${VoteJudges.countMatches(frame, b, VoteJudges.RED_LOCK)}（仅对照，不参与判决）",
    )
}

/**
 * 点完锁钮后的**收敛等待**：交错执行「处置迟到的确认弹框 + 复读锁态」，直到到达目标态或预算耗尽。
 *
 * ⚠️ **必须交错，不能只在开头 dismiss 一次**。真机实测（2026-09-18）：锁定是**服务端操作**，
 *   加锁确认弹框可能**迟至约 60s** 才出现 —— 点完 60s 后截屏才发现框正开着，
 *   而期间每次轮询都读到 `白底占比=0.022`（即"没框"）。旧实现只在点击后 dismiss 一次，
 *   随后 3 次纯复读**不再处置弹框** ⇒ 晚到的框永远点不掉、锁态也永远读不对。
 *   （当时把这条当成 `verify FAILED` 的解释，其实**不是** —— 那条另有真因，
 *   见 [probeLockPixels] 与 click 步骤里的 `clickedWhileResolving`。交错本身仍然要留：
 *   晚到的框会留在屏上把后续点击全吃掉。）
 *
 * 预算取 [SETTLE_LOCK_BUDGET_MS]（90s）：实测迟到极值 ~60s，留一半余量；超预算打 warn 后继续，不空转。
 */
internal suspend fun ScanEngine.settleLockState(desired: Boolean, budgetMs: Long = SETTLE_LOCK_BUDGET_MS): Boolean {
    val deadline = clock() + budgetMs
    var round = 0
    while (true) {
        round++
        // 确认框是**一次性提示**：账户在本设备确认过一次之后就不再弹。学到这点之后每轮
        // 仍做**一次零等待探测**（0L）—— 晚到 60s 的框照样点掉，只是不再为它每轮白等 1500ms。
        val dismissed = dismissLockConfirm(if (lockConfirmAbsent) 0L else 1500L)
        if (dismissed) {
            // 又弹了 ⇒ 之前的"不再弹"结论作废，回到带等待的档位并重新计数。
            cleanLocksNoDialog = 0
            lockConfirmAbsent = false
        }
        val f = freshFrame()
        val now = try {
            probeLockPixels(f, "settle#$round")
            lockVoteAny(f)
        } finally { f.release() }
        if (now == desired) {
            if (!dismissed) noteCleanLockWithoutDialog()
            Log.i(TAG, "settleLockState: 第 $round 轮达到目标态（locked=$now）${if (dismissed) "，本轮点掉了弹框确认" else ""}")
            return true
        }
        if (clock() >= deadline) {
            Log.w(TAG, "settleLockState: ${budgetMs}ms 预算用尽仍未达目标态（第 $round 轮 locked=$now desired=$desired）")
            return false
        }
        delay(2000L)
    }
}

/**
 * 「本设备已不再弹上锁确认框」的学习：连续 [LOCK_CLEAN_BEFORE_ABSENT] 次写锁成功且
 * 全程没点掉过弹框，才认定不再弹，并回写持久层（跨轮生效 —— 一次性提示的性质是账户级的，
 * 每轮从零重新学一遍等于每轮白等）。
 */
internal fun ScanEngine.noteCleanLockWithoutDialog() {
    if (++cleanLocksNoDialog < LOCK_CLEAN_BEFORE_ABSENT) return
    if (lockConfirmAbsent) return
    lockConfirmAbsent = true
    Log.i(
        TAG,
        "settleLockState: 连续 $cleanLocksNoDialog 次写锁都没出现确认弹框 ⇒ 本设备按「不再弹」处理" +
            "（每轮仍做一次零等待探测，晚到的框照样点掉）",
    )
    onLockConfirmAbsentLearned()
}

/**
 * checkbox 是否已勾选（"未选中黑 / 选中白"）。取该点亮度均值，>150 视为已选（防误取消）。
 * 读帧失败按「未选」处理（宁可多点一次也不会漏点）。
 */
internal suspend fun ScanEngine.checkboxChecked(x: Int, y: Int): Boolean {
    val sx = profile.scale(x, profile.scaleX)
    val sy = profile.scale(y, profile.scaleY)
    val frame = try {
        freshFrame()
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        return false
    }
    return try {
        val px = frame.get(sy, sx)
        val bright = (px[0] + px[1] + px[2]) / 3.0
        bright > 150.0
    } catch (e: CancellationException) { throw e } catch (_: Exception) {
        false
    } finally {
        frame.release()
    }
}

/**
 * §14 P1：筛选目标集合。取并集逻辑在 [TaskMatch.targets]（纯逻辑，可离线单测）。
 */
/** 每条计划项的落库结果（下标 / label / InstructionStatus），由 [foreach] 或 [planDone] 写入。 */
internal fun ScanEngine.filterTargets(): Set<String> = TaskMatch.targets(vars.currentTask, vars.plan)

/**
 * `planMatch` 原语（★ 2026-09-18，单趟扫描核心）：
 * 把**本格刚解析出来的圣遗物**拿去和 `plan` 里**尚未绑定**的项逐一比对，命中则
 * 把 `vars.currentTask` 指向该项、记下 `planMatchedIndex`，供下游 `wantLock`/`curLock`
 * 与 `hardMatch` 沿用（**下游逻辑零改动**）。
 *
 * 与旧写法的区别：旧写法是 `foreach(task){ pagedGrid{ parsePanel.match } }` ——
 * 外层锁定"这一轮只找一个目标"，于是每格都要与**同一个**任务比、且每换一个任务就重走整张网格。
 * 现在反过来：**网格只走一遍**，每格问"我是谁"。
 *
 * 绑定语义（对齐 GOODScanner 的绑定表）：**一个计划项只被绑定一次**；已绑定的项不再参与比对，
 * 因此同件重复出现（翻页重叠）不会被重复处理 ⇒ 幂等。
 *
 * ```json
 * { "do": "planMatch", "tol": 0.100001 }
 * ```
 */
internal fun ScanEngine.planMatch(step: JSONObject) {
    val plan = vars.plan
    // 每格先复位：宁可"不动作"，也不留下上一格的命中值
    vars.currentTask = null
    vars.planMatchedIndex = -1
    vars.panelMatched = false
    vars.matchHit = false
    vars.actOk = false
    if (plan.isNullOrEmpty()) {
        Log.d(TAG, "planMatch: plan 为空 ⇒ 跳过")
        return
    }
    val a = results.lastOrNull()
    if (a == null) {
        Log.d(TAG, "planMatch: 无已解析产物（本格 parsePanel 未入库）⇒ 跳过")
        return
    }
    val tol = step.optDouble("tol", 0.100001)
    for ((i, t) in plan.withIndex()) {
        if (i in vars.consumedPlanIndexes) continue
        val why = StringBuilder()
        if (TaskMatch.hardMatch(t, a, tol, why)) {
            vars.currentTask = t
            vars.planMatchedIndex = i
            vars.panelMatched = true
            vars.matchHit = true
            Log.i(TAG, "planMatch: 本格绑定第 $i 项 ${taskLabel(t)}（剩余未绑定 ${vars.planRemaining()}）")
            return
        }
    }
    Log.d(TAG, "planMatch: 本格未命中任何未绑定项")
}

/**
 * `planDone` 原语：把**本格绑定的项**标记为已绑定并记录结果状态。
 * **首次写入生效**（同一项被多个分支调用时后者忽略）⇒ 可安全地在多条 `ifMatch` 分支里都放一句。
 *
 * ```json
 * { "do": "planDone", "status": "Success" }        // 动作执行且 verify 通过后
 * { "do": "planDone", "status": "AlreadyCorrect" } // 命中但无需动作时
 * ```
 */
internal fun ScanEngine.planDone(step: JSONObject) {
    val i = vars.planMatchedIndex
    if (i < 0) {
        Log.d(TAG, "planDone: 本格未绑定任何项 ⇒ 忽略")
        return
    }
    if (!vars.consumedPlanIndexes.add(i)) {
        Log.d(TAG, "planDone: 第 $i 项已绑定过 ⇒ 忽略（${step.optString("status")}）")
        return
    }
    val label = vars.plan?.getOrNull(i)?.let { taskLabel(it) } ?: "#$i"
    val want = step.optString("status", "Success")
    // `Success` 是「服务端真的改了」的唯一出口。#89 起它由 verify 置的 `actOk` 驱动，
    // #107 起还要 `actVerified == true` —— 即**必须有写后回读的正面证据**，
    // 不接受"动作按语义发出了"这种代理。verify 不符时只打日志、不改控制流，原先这里照抄
    // flow 写的 "Success" ⇒ 迟到弹框把 verify 打成 FAILED 的那件照样记成功，
    // 而且已进绑定表**永不再补**。
    val status = when {
        want != "Success" || (vars.actOk && vars.actVerified == true) -> want
        else -> {
            Log.w(
                TAG,
                "planDone: 第 $i 项 $label 要求 Success 但 actOk=${vars.actOk} " +
                    "actVerified=${vars.actVerified}（verify 未过或没跑）⇒ 记 Failed",
            )
            RecognitionLog.log(
                logTag,
                RecognitionLog.Level.W,
                "结果降级 Success→Failed：写后复核未通过（$label）",
            )
            "Failed"
        }
    }
    manageResults.add(Triple(i, label, status))
    Log.i(TAG, "planDone: 第 $i 项 $label \u21d2 $status（剩余未绑定 ${vars.planRemaining()}）")
}

/**
 * `planSummary`：把**始终没被绑定**的项按缺席证明门禁分流后出汇总（照 `emitManageSummary`）。
 *
 * ★ P0-A（2026-10-08，GS 对齐 lock_manager.rs 的 missed/skipped 分流）：未绑定项的**处境**
 *   决定状态，不是一律 NotFound ——
 * - [ScanVars.lockPlanAborted]（流程异常中止：enterScreen/assertScreen 未达、profile 缺失）
 *   ⇒ **Skipped**：这一轮结果不可信，需要重跑，而不是"这件不存在"；
 * - [ScanVars.lockFilterPartial]（P0-C：筛选只勾上了部分目标）⇒ **Skipped**：剩余目标
 *   在筛选遮蔽的区段里，"没扫到"≠"不存在"；
 * - 正常走查完整（stopWhen/maxPages/网格到底）且两项都未置位 ⇒ **NotFound**：
 *   "背包里确实没有这件"（可据此重造方案——equip 侧 manageStatusOf 同款语义）。
 */
internal fun ScanEngine.planSummary() {
    val plan = vars.plan ?: return
    val absenceStatus = when {
        vars.lockPlanAborted -> "Skipped"
        vars.lockFilterPartial -> "Skipped"
        else -> "NotFound"
    }
    for (i in plan.indices) {
        if (i !in vars.consumedPlanIndexes) {
            manageResults.add(Triple(i, taskLabel(plan[i]), absenceStatus))
        }
    }
    emitManageSummary()
}

/**
 * `clickSlotTab` 原语（2026-09-18 P2⑪）：点**槽位页签**，槽位取自**当前计划项**的 `slotKey`。
 *
 * 为什么必须有这一步：GOODScanner 的装配流程是
 * "Clicks the artifact slot matching the artifact's slotKey"，**再**做套装筛选。
 * 我们此前完全不点槽位页签 ⇒ 选择网格停在上一次进入时的槽位 ⇒ 可能选到**错部位**的件
 * （目标身份含 slotKey，最终 hardMatch 会拒掉，表现为"整轮 NotFound"）。
 *
 * 坐标来自 `screens.artifact_manage.slotTabs`（5 个部位，机核零偏移）。
 * 未知 slotKey ⇒ 记警告并跳过（不猜、不乱点）。
 */
internal suspend fun ScanEngine.clickSlotTab(step: JSONObject) {
    val cur = vars.currentTask
    val key = cur?.optString("slotKey").takeUnless { it.isNullOrBlank() }
        ?: cur?.optString("slot").orEmpty()
    val cn = when (key) {
        "flower" -> "生之花"
        "plume" -> "死之羽"
        "sands" -> "时之沙"
        "goblet" -> "空之杯"
        "circlet" -> "理之冠"
        else -> step.optString("default", "")
    }
    if (cn.isEmpty()) {
        Log.w(TAG, "clickSlotTab: 未知 slotKey='" + key + "' ⇒ 跳过（不猜）")
        return
    }
    val arr = profile.rawObject("screens.artifact_manage.slotTabs")?.optJSONArray(cn)
    if (arr == null) {
        Log.w(TAG, "clickSlotTab: profile 缺 screens.artifact_manage.slotTabs." + cn + " ⇒ 跳过")
        return
    }
    clickAt(arr.getInt(0), arr.getInt(1))
    Log.i(TAG, "clickSlotTab: slotKey=" + key + " → 「" + cn + "」(" + arr.getInt(0) + "," + arr.getInt(1) + ")")
}

/** 当前处于「背包 / 圣遗物筛选主面板 / 圣遗物套装子面板」哪一态。 */
internal enum class FilterPanelState { BACKPACK, MAIN_PANEL, SET_PANEL, UNKNOWN }

/**
 * 读左上标题条判定当前面板态（2026-09-18 P0 新增）。
 *
 * 为什么必须判态：`setFilter` 打开的既有**套装子面板**（全屏「圣遗物**套装**筛选」）也有
 * **主面板**（窄栏「圣遗物筛选」），两者底部按钮完全不同。旧实现在子面板上点主面板坐标
 * ⇒ 点空白 ⇒ 面板不关 ⇒ 后面整段流程站在错屏上跑（真机实测）。
 *
 * 判据用三态互斥词：子面板含「套装」、背包含「背包」、主面板含「筛选」——
 * ⚠️ **不能只判「圣遗物」或「筛选」**，三态标题都含这两个词，必须靠「套装」「背包」区分。
 */
internal suspend fun ScanEngine.filterPanelState(): FilterPanelState {
    val gateway = ocr ?: return FilterPanelState.UNKNOWN
    // ⚠️ 2026-09-18 返工（真机暴露）：**必须只读标题那一行**。
    //   先用 `screens._common.titleBar`（y21..136）时，ROI 同时含标题与副标题
    //   「将筛选展示满足条件的圣遗物」，而设备端 OCR **只回了副标题且读成乱码**
    //   （实测 title='柔满物奈满芷亲件的圣遗物'）⇒ 不含「套装/背包/筛选」⇒ 一路 UNKNOWN
    //   ⇒ `ensureSetPanel` 判"没进子面板"、`leaveFilterPanels` 又不敢按 BACK ⇒ 卡在面板上。
    //   （旁证：同一 ROI 用 paddleocr 离线读能正确得到「圣遗物套装筛选」+ 副标题两行，
    //     说明 ROI 本身没切错，是设备端只回了一行。）
    //   ⇒ 收紧到 `grids.set_filter_popup.titleLine`（只框标题行）后就不会被副标题挤掉。
    // 读**两个** ROI 再 OR：紧 ROI（只框标题行）避免被副标题挤掉；宽 ROI（通用标题条）对
    // 边缘裁切更宽容。实测同一帧里「背包/圣遗物」在紧 ROI 被读成「背句/又遗物」而在宽 ROI 正确 ——
    // 单靠任一个都会误判，故取并集。
    val rects = listOfNotNull(
        runCatchingCancellable { profile.rect("grids.set_filter_popup.titleLine") }.getOrNull(),
        runCatchingCancellable { profile.rect("screens._common.titleBar") }.getOrNull(),
    )
    if (rects.isEmpty()) return FilterPanelState.UNKNOWN
    val frame = try {
        freshFrame()
    } catch (e: CancellationException) { throw e } catch (_: Exception) {
        return FilterPanelState.UNKNOWN
    }
    // ⚠️ 必须用循环而不是 joinToString{}：lambda 里不能调 suspend 函数（readLines 是 suspend）
    val sb = StringBuilder()
    try {
        for (r in rects) {
            val one = gateway.readLines(frame, listOf(r)).joinToString(" ")
            if (one.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(one)
            }
        }
    } catch (e: CancellationException) { throw e } catch (_: Exception) {
        return FilterPanelState.UNKNOWN
    } finally {
        frame.release()
    }
    val text = sb.toString()
    val st = when {
        text.contains("套装") -> FilterPanelState.SET_PANEL
        text.contains("背包") -> FilterPanelState.BACKPACK
        text.contains("筛选") -> FilterPanelState.MAIN_PANEL
        else -> FilterPanelState.UNKNOWN
    }
    Log.i(TAG, "filterPanelState: title='$text' ⇒ $st")
    return st
}

/**
 * 轮询 [filterPanelState] 直到读到非 UNKNOWN，或预算耗尽（返回最后一次读数，可能仍是 UNKNOWN）。
 *
 * 为什么需要：设备端 OCR 对同一标题条**一次读对、一次读成乱码**是常态（见 [filterPanelState] KDoc
 * 里 `ensureSetPanel` 轮询 6 次的同源教训）。#299 真机实测：`confirmFilter` 的收尾判定用**单发**
 * `filterPanelState()`，一次撞 title='A' ⇒ UNKNOWN ⇒ 漏关主面板 ⇒ 头几格压面板静默丢件。
 * 收尾/露出判定改走本函数，把"侥幸读到"变成"读到为止"。
 */
internal suspend fun ScanEngine.pollFilterPanelState(
    tries: Int = 4,
    gapMs: Long = FILTER_PANEL_POLL_MS,
): FilterPanelState {
    var last = filterPanelState()
    var i = 1
    while (last == FilterPanelState.UNKNOWN && i < tries) {
        delay(gapMs)
        last = filterPanelState()
        i++
    }
    return last
}

/** 套装子面板的按钮中心（`profiles.grids.set_filter_popup.<key>`：okBtn / clearBtn / title）。 */
internal fun ScanEngine.setPanelCenter(key: String): IntArray? {
    val g = profile.rawObject("grids.set_filter_popup") ?: return null
    val r = g.optJSONArray(key) ?: return null
    if (r.length() < 4) return null
    return intArrayOf((r.getInt(0) + r.getInt(2)) / 2, (r.getInt(1) + r.getInt(3)) / 2)
}

/**
 * 开启面板并确认**确实进了子面板**（可重试一次）。
 * 未确认时按 BACK 清掉可能卡住的层再试；仍不行由调用方决定降级。
 * 照 `filterReset` 的「绝不盲点」原则：不确定就不点后面的按钮。
 */
internal suspend fun ScanEngine.ensureSetPanel(open: suspend () -> Unit): Boolean {
    repeat(2) { attempt ->
        if (filterPanelState() == FilterPanelState.SET_PANEL) return true
        if (attempt > 0) {
            Log.w(TAG, "ensureSetPanel: 第 $attempt 次仍未进子面板 ⇒ BACK 清一层后重试")
            runCatchingCancellable { actions.back() }
            delay(FILTER_PANEL_BACK_MS)
        }
        open()
        for (i in 1..6) {
            delay(FILTER_PANEL_POLL_MS)
            if (filterPanelState() == FilterPanelState.SET_PANEL) return true
        }
    }
    return false
}

/**
 * 右上角提示条是否写着 [hint]（`screens._common.topRightHint`）。
 * 用于"我还在不在那个界面上"的**正向**判据：读不到就当作不在，绝不猜在。
 */
internal suspend fun ScanEngine.gridThumbOf(gridKey: String): ByteArray? {
    val frame = try {
        freshFrame()
    } catch (e: CancellationException) { throw e } catch (_: Exception) {
        return null
    }
    return try {
        VoteJudges.gridThumb(frame, profile, gridKey)
    } finally {
        frame.release()
    }
}

/**
 * 筛选弹窗**首行**（某一列）读到的是哪一套 —— **只打一行诊断读数，不判决**（返回 Unit）。
 *
 * 名字史（2026-10-02 审计纠正，别再当判据用）：原名 `filterTopRowIs` 返回 Boolean，本意是
 * **正向**确认"真的在顶页"——`swipeGridToTop` 的到顶判据是"画面没变"（松阈值），而那一下滑动被吞时
 * 画面同样没变 ⇒ 两者同形；09-29 实测过"判已到顶、其实停在半路"（diff=0.211 落进静止态抖动带）
 * ⇒ 翻满 5 页零命中 ⇒ `not_applied` ⇒ 静默退化成无筛全量扫。
 * ⛔ **但该立意已被实测推翻**（2026-09-29 21:29，见 profile `set_filter_popup.topRowKeyNote` 与
 * `dsl/verify/_char_filter_probe/README.md` §K）：回顶后首行读到的是「流浪大地的乐团」还是
 * 「华馆梦醒形骸记 / 角斗士的终幕礼」随轮次漂 ⇒ 那些读数**本身就不代表顶页**，基于坏测量的判据
 * 会把本来能用的筛选判死。⇒ 引擎侧降级为只打读数，判决仍归 `swipeGridToTop`；两处调用点本来
 * 就把返回值丢掉，如今把这点写进签名（`Unit`），免得下一个读到 `…Is` 又把它当判据接回去。
 *
 * ★ #169（2026-10-02）：**删掉名义行带回退**。原来量不到相位时用 `FrameRect(x0, y, x1, y+rowHeight)`
 *   硬读 —— 落点偏时那 90px 名义带里装的是**相邻卡被裁掉的下半截字**，照样能"认出"一个套名，
 *   于是这行诊断日志会给出**看起来可信的错行读数**（正是 §5 记的那类"保守回退反而更危险"）。
 *   `textLineCrop` 的 KDoc 明令"调用方不许拿名义行带凑数"，本函数是当时唯一的违反者。
 */
internal suspend fun ScanEngine.logTopRowRead(
    gateway: OcrGateway,
    lookup: (String) -> String?,
    box: JSONArray,
    y: Int,
    rowHeight: Int,
    rowPitch: Int,
    labelAnchor: Int,
    expectKey: String,
) {
    val x0 = box.getInt(0)
    val x1 = if (box.length() >= 4) box.getInt(2) else box.getInt(1)
    repeat(2) { attempt ->
        val frame = freshFrame()
        val crop = GridAlign.textLineCrop(frame, x0, x1 - x0, y, rowHeight, rowPitch, labelAnchor)
        if (crop == null) {
            // 量不到 ⇒ 不读：宁可这行日志缺席，也不留一条"错行但能认名"的假证据
            frame.release()
            Log.d(TAG, "setFilter 顶页锚[${if (attempt == 0) "首读" else "重读"}] y=$y 行位没量到 ⇒ 不读")
            if (attempt == 0) delay(250)
            return@repeat
        }
        val readRect = FrameRect(crop.crop.x, crop.crop.y, crop.crop.right, crop.crop.bottom)
        val text = try {
            gateway.readLines(frame, listOf(readRect)).joinToString(" ")
        } finally {
            frame.release()
        }
        val key = lookup(StatParser.clean(text))
        Log.i(TAG, "setFilter 顶页锚[${if (attempt == 0) "首读" else "重读"}] ph=${crop.phase} text='$text' key=$key expect=$expectKey")
        if (key != null) return
        delay(250)
    }
}

/**
 * 一行筛选名的**读数结果**（#147）：页级自洽度要同时知道"读到字了吗 / 认出套装名了吗"，
 * 只返回"是否命中待选目标"的话，**整页乱码**与"这页没有目标"两种情况同形 —— 而前者正是
 * #147 的病灶（判稳信号看不见残余慢漂 ⇒ 行带被十几 px 位移切成跨行 ⇒ 每行都读成自信乱码）。
 */
internal class RowRead(
    val blank: Boolean,
    val resolved: Boolean,
    val matched: Boolean,
    /** 本行 OCR 原文（#147：判"还在漂"要比对**两次读数的文本**，像素差量化不出来，文本变没变变得出来）。 */
    val text: String,
)

/**
 * 一页筛选读数的**自洽度**（#147）：`认出套装名的行数 / 读到非空文本的行数`。
 *
 * 分母**只数非空行**是这条判据的要害：列表落到行尾时，末行带在文字之外（读成**空**），
 * 把它们算进分母就会把"到底了"误判成"没读稳"，每页白等两次重读；而行位偏出余量时
 * rec-only 给的是**非空乱码**（'禁时文歌' 这种，#169），只有它们会拉低比值 —— 这正是我们要抓的信号。
 *
 * 一行都没读到字（整页空）⇒ 返回 1f：没有证据就不许判"未稳"，交给上层继续翻页。
 */
internal fun filterPageConsistency(rowsRead: Int, rowsBlank: Int, rowsResolved: Int): Float {
    val nonBlank = rowsRead - rowsBlank
    if (nonBlank <= 0) return 1f
    return rowsResolved.toFloat() / nonBlank
}

/**
 * 同一页**两次读数是否逐字相同**（#147 的第二道信号）—— 判"还要不要重读"。
 *
 * 自洽度低有两种时间形态，光看比值分不开：
 * · 列表还在慢漂 ⇒ **下一次读会给出另一串字**（00:33 轮实测同一行两遍读出 '双保限市的服务' /
 *   '平自限事的路者'，第 3 页比值 0.55→0.36）；这种值得等一等再读。
 * · 静止态 ⇒ **下一次读还是同一串字**。当初把它归因成"灰化低对比度 + 形近字"（#147⑥），
 *   2026-10-02 #169 复查**推翻**：同一批"读不出的套"在邻页一字不差地读对过，且劣化总是
 *   **整页**命中 —— 真因是行位落点偏出行带余量（见 [GridAlign.textLineCrop]）。静止的乱码重读也不会变，
 *   所以本判据的**动作**不变（立刻收手），只是原因换成"再读也没用"，兜底对象剩词典缺口。
 *
 * `prev == null` ⇒ 还没有对比基准，返回 false（要再读一次才知道稳不稳）。
 */
internal fun filterPageStable(prev: List<String>?, now: List<String>): Boolean = prev != null && prev == now

/**
 * 落点越界判定（#169 §8-5，纯函数好测）。
 *
 * `clickY = rowCenterY + phase` 是 #169 的不变式（读数、checkbox 读数、点击共用一个相位），
 * 但相位窗是 ±半行距，末行的最深落点会**越过列表视口下沿**：三档实测
 * 2244 916+51=**967** vs bounds 底 965（越 2px，而"重置"钮上沿就在 974）、
 * 2560 1222+68=**1290** vs 1272（越 18px）、3200 1290 vs 1292（不越）。
 * 越界点到"重置"= 把刚勾上的筛选静默清掉 ⇒ 这一轮目标达不成，而日志看着一切正常。
 *
 * ⚠️ 修法必须是**整格不做**（返回 null ⇒ 调用方按"量不到"记账），
 * **不许**把 clickY 钳到边界内继续点 —— 那等于回到"读 B 点 A"。
 */
internal fun filterClickYOrNull(rowCenterY: Int, phase: Int, panelTop: Int, panelBottom: Int): Int? {
    val y = rowCenterY + phase
    return if (y < panelTop || y > panelBottom) null else y
}

/**
 * 要不要给本页一次"半行轻推"重读的机会（#169 §6-1 的收法，纯函数）。
 *
 * 病灶：翻页行程不是行距的整数倍 ⇒ 每页落点 δ 几乎任意；δ 落在 ±半行距 的**窗沿**时，
 * 本行与邻行的字带离锚都 ≤`TEXT_AMBIG_MIN` 之差 ⇒ 歧义门成批拒判 ⇒ **整页大面积"量不到"**
 * （2560 尾页实测 7/16，代价口径见审计 §10-附2：漏格＝这一轮目标达不成，不是"慢一点"）。
 * 轻推半行就是去**改这个落点相位**：把 δ 从窗沿挪到窗中间，同一页再读一遍。
 *
 * 门槛取 **4/16 = 0.25**：这是照 2560/3200 两轮实测的空行分布定的（2560 尾页 3/16、3/16、5/16、
 * 7/16；3200 尾页 4/16、5/16），第一版定的是 1/3 ⇒ 20:41（3200）与 21:0x（2560）两轮**一次都没触发**
 * —— 门槛落在观测范围之上，等于写了条不响的判据。
 * 样本太少的页不推（<8 行读数时"空 3"就有歧义）。
 */
internal fun shouldNudgeFilterPage(rowsRead: Int, rowsBlank: Int, nudgesUsed: Int, nudgeBudget: Int): Boolean {
    if (nudgesUsed >= nudgeBudget) return false
    if (rowsRead < 8) return false
    return rowsBlank.toDouble() / rowsRead >= FILTER_PAGE_NUDGE_BLANK_RATIO
}

/** 每页允许的半行轻推次数（1 次就够：推完还读不到，说明不是窗沿问题，继续推只会白烧时间）。 */
internal const val FILTER_PAGE_NUDGE_BUDGET = 1

/** 触发轻推的"量不到"占比门槛。 */
internal const val FILTER_PAGE_NUDGE_BLANK_RATIO = 0.25

/**
 * 筛选列表单行匹配：OCR 名称 → 词典反查 → 命中且 checkbox 未勾选则点选（§12.4-③ 防误取消）。
 *
 * ★ #169：读数、checkbox 读数、点击三者**共用同一个相位**（`clickY = rowCenterY + phase`）；
 *   `textLineCrop` 量不到相位 ⇒ 不读也不点（详见该函数 KDoc：拿名义行带凑数＝"读 B 点 A"）。
 * @param panelTop/panelBottom 列表视口（profile `grids.<key>.bounds` 的 y 向）；落点越出去**整格不做**
 *   （见 [filterClickYOrNull]，越界点到"重置"是静默错动作）。
 */
internal suspend fun ScanEngine.matchFilterRow(
    gateway: OcrGateway,
    lookup: (String) -> String?,
    box: JSONArray,
    y: Int,
    rowHeight: Int,
    rowPitch: Int,
    labelAnchor: Int,
    checkboxX: Int,
    rowCenterY: Int,
    panelTop: Int,
    panelBottom: Int,
    pending: MutableSet<String>,
    side: String,
): RowRead {
    // nameBox 两种写法：[x0,x1]（canonical 2 元 x 区间）或 [x0,y0,x1,y1]（4 元 rect，y 取 index 2）
    val x0 = box.getInt(0)
    val x1 = if (box.length() >= 4) box.getInt(2) else box.getInt(1)
    // 行 OCR 失败重试一次：翻页残差使文字在 ROI 内错位时首读常烂，缓 250ms 后重取帧显著提升命中
    var key: String? = null
    var text = ""
    // ★ #169：名义行带（2244 实测 90px）远高于一行字（27px）且字**贴带顶** ⇒ 落点一偏出余量，
    //   名义带里装的就是**相邻卡被裁掉的下半截字**。逐帧量出字带位置再紧裁剪送 rec，并把量到的
    //   **行相位同时用于 checkbox 读数与点击**（三者同相位 ⇒ 即使量到的是相邻那张卡也自洽）。
    var phase = 0
    for (attempt in 0 until 2) {
        val frame = freshFrame()
        val crop = GridAlign.textLineCrop(frame, x0, x1 - x0, y, rowHeight, rowPitch, labelAnchor)
        if (crop == null) {
            // 没量到相位就**不读也不点**：读出来照样能"认成"一个套装名（下半截字也是字），点下去却是
            // 本行中心 ⇒ 读 B 点 A＝静默选错套装，比整行空坏得多。这格按"空"记账（不进自洽度分母）。
            // 真机 2244 尾页 δ=−53 实测：装机版 8 帧 × 16 格只认出 0 个，而名义带能"认出" 16 个——
            // 那 16 个全是错行读数。
            frame.release()
            Log.d(TAG, "filterRow[$side] y=$y a$attempt 行位没量到 ⇒ 不读不点")
            if (attempt == 0) delay(250)
            continue
        }
        phase = crop.phase
        val readRect = FrameRect(crop.crop.x, crop.crop.y, crop.crop.right, crop.crop.bottom)
        text = try {
            gateway.readLines(frame, listOf(readRect)).joinToString(" ")
        } finally {
            frame.release()
        }
        key = lookup(StatParser.clean(text))
        Log.d(TAG, "filterRow[$side] y=$y a$attempt ph=$phase text='$text' key=$key")
        if (key != null || text.isBlank()) break
        delay(250)
    }
    val blank = text.isBlank()
    if (key == null || key !in pending) return RowRead(blank, key != null, false, text)
    val clickY = filterClickYOrNull(rowCenterY, phase, panelTop, panelBottom)
    if (clickY == null) {
        // 落点越出列表视口 ⇒ **整格不做**（不钳到边界内继续点，那等于"读 B 点 A"）。
        // 按"空"记账：不进自洽度分母，但目标仍留在 pending ⇒ 页级轻推/后续页还会再遇到它。
        Log.w(
            TAG,
            "setFilter: $key ($side) 落点越出面板 ⇒ 不点（rowCenter=$rowCenterY ph=$phase " +
                "y=${rowCenterY + phase} 视口=[$panelTop,$panelBottom]）",
        )
        return RowRead(true, false, false, text)
    }
    if (checkboxChecked(checkboxX, clickY)) {
        Log.i(TAG, "setFilter: $key ($side) 已勾选，跳过（防误取消）ph=$phase")
        pending.remove(key)
        return RowRead(blank, true, true, text)
    }
    clickAt(checkboxX, clickY)
    pending.remove(key)
    Log.i(TAG, "setFilter matched: $key ($side) ph=$phase click=($checkboxX,$clickY)")
    return RowRead(blank, true, true, text)
}

/**
 * §12.4-⑥ 确认筛选并关闭。两层结构（OCR 实测 2560）：
 *   ① 先点 filterPanel.ok（套装子面板「确认筛选」）→ 仅收起子面板，回到主面板「圣遗物筛选」，主面板仍开；
 *   ② 再点 filterPanel.confirm（主面板「确认」）→ 应用筛选并关闭主面板，回到背包网格。
 * 若 profile 未定义 confirm（旧分辨率/配置），回退 tailGuard 旧逻辑：锚点仍在则再点一次 ok。
 */
internal suspend fun ScanEngine.confirmFilter(step: JSONObject) {
    // ★ 2026-09-18（P0① 核心修正）：点**套装子面板**右下角的「确认筛选」。
    //
    // 旧实现点的是 `screens.dialogs.filterPanel.ok/confirm` —— 那是**主面板**（窄栏「圣遗物筛选」，
    // 底部是「重置」+「确认」）的坐标；而 setFilter 的 chain（漏斗 → 所属套装「+」）
    // 打开的是**子面板**（全屏「圣遗物**套装**筛选」，底部左「清空条件」右「确认筛选」）。
    // ⇒ 点主面板坐标 = 点空白 ⇒ 子面板永不关闭 ⇒ 之后 foreach 全程对着子面板跑
    //   （真机实测：每格 panelMatch 未命中 + verify artifact.panel.lock FAILED）。整轮白跑。
    //
    // 根因是"同一块面板两处真值"：dsl/verify/_artifact_template.json 里
    // `set_filter_grid.buttons`（子面板）与 `filter_panel_popup`（主面板）早就分别记对了，
    // 但 profiles 只把主面板那份搬进 screens.dialogs.filterPanel。现已把子面板按钮入档
    // （grids.set_filter_popup.okBtn / clearBtn，3200 实测）。
    val subPt = setPanelCenter("okBtn")
    val pt = subPt ?: filterPanelCenter("ok")
    if (pt == null) {
        Log.w(TAG, "confirmFilter: 子面板 okBtn 与主面板 ok 均未标定 ⇒ 无法确认，按 BACK 退出")
        leaveFilterPanels("confirmFilter 未标定", homeHint = homeHintOf(step))
        return
    }
    Log.i(
        TAG,
        "confirmFilter: 确认筛选=(${pt[0]},${pt[1]}) 来源=${if (subPt != null) "子面板 okBtn" else "主面板 ok（回退）"}",
    )
    clickAt(pt[0], pt[1])
    delay(FILTER_PANEL_STEP_MS)
    // 子面板关掉后会**露出主面板「圣遗物筛选」**（背包入口是两层：主面板 + 套装子面板）。
    // 必须再点一次主面板「确认」把它关掉，否则流程会站在主面板上跑网格、头几格点击被面板吃掉
    // ⇒ 详情面板不刷新 ⇒ 陈旧帧判重静默丢件（#299 真机实测：run1 丢 3 根羽毛，run2 因判态侥幸
    //   读到 MAIN_PANEL 才走 BACK 收尾）。旧实现只在**单发** filterPanelState 命中 MAIN/SET_PANEL
    //   时才收尾，撞一次 OCR 乱码（title='A' ⇒ UNKNOWN）就整个跳过 ⇒ 漏关。
    // ⚠️ 只在**确读到面板**时才点主面板「确认」——UNKNOWN 时该坐标可能正压在网格上，盲点会误触格子
    //   （用户硬约束"不能影响网格点击"）。轮询后仍 UNKNOWN ⇒ 退回 leaveFilterPanels 的 BACK 收尾
    //   （BACK 是系统键，绝不压网格）。仅当从子面板进来（subPt!=null）才需要关露出的主面板；
    //   回退路（subPt==null）第一下点的本就是主面板「确认」，不再重复点。
    if (subPt != null) {
        when (pollFilterPanelState()) {
            FilterPanelState.MAIN_PANEL, FilterPanelState.SET_PANEL -> {
                val mainPt = filterPanelCenter("ok")
                if (mainPt != null) {
                    Log.i(TAG, "confirmFilter: 露出主面板 ⇒ 再点主面板「确认」关闭=(${mainPt[0]},${mainPt[1]})")
                    clickAt(mainPt[0], mainPt[1])
                    delay(FILTER_PANEL_STEP_MS)
                    when (pollFilterPanelState()) {
                        FilterPanelState.MAIN_PANEL, FilterPanelState.SET_PANEL -> {
                            Log.i(TAG, "confirmFilter: 点主面板确认后仍残留 ⇒ BACK 收尾")
                            leaveFilterPanels("confirmFilter 收尾", homeHint = homeHintOf(step))
                        }
                        else -> Unit
                    }
                } else {
                    Log.w(TAG, "confirmFilter: 主面板 ok 未标定 ⇒ 退回 BACK 收尾")
                    leaveFilterPanels("confirmFilter 主面板未标定", homeHint = homeHintOf(step))
                }
            }
            FilterPanelState.BACKPACK ->
                Log.i(TAG, "confirmFilter: 子面板确认后已回到背包网格 ⇒ 无需再关")
            FilterPanelState.UNKNOWN -> {
                Log.w(TAG, "confirmFilter: 轮询后判态仍 UNKNOWN ⇒ 不盲点主面板确认（会压网格），交 BACK 收尾")
                leaveFilterPanels("confirmFilter UNKNOWN", homeHint = homeHintOf(step))
            }
        }
    } else {
        // 回退路：pt 就是主面板「确认」，已点过一次；复核是否还残留（含 UNKNOWN 也交 BACK 兜）。
        when (pollFilterPanelState()) {
            FilterPanelState.MAIN_PANEL, FilterPanelState.SET_PANEL, FilterPanelState.UNKNOWN -> {
                Log.i(TAG, "confirmFilter: 回退路复核 ⇒ BACK 收尾")
                leaveFilterPanels("confirmFilter 回退收尾", homeHint = homeHintOf(step))
            }
            else -> Unit
        }
    }
    // 兼容旧配置的 tailGuard 语义已在上面被"判态 + 收尾"取代，step 参数保留给日志/未来扩展
    if (step.optString("tailGuard").isNotEmpty()) {
        Log.i(TAG, "confirmFilter: tailGuard='${step.optString("tailGuard").take(40)}' 已由判态收尾取代")
    }
}

/**
 * P3 foreach：遍历外部注入 plan（vars.plan），每项作为 vars.currentTask（"as" 字段，默认 "task"），执行内部 steps 数组（每步走 executeStep 顶层）。
 * 供 character_scan 的 "$plan" 遍历（auto_equip 的 "$plan" 同款，setFilter 用 vars.currentTask["setName"] 选套装）。
 */
internal suspend fun ScanEngine.verify(step: JSONObject) {
    val zone = step.getString("zone")
    val expectRaw = step.optString("expect", "true")
    // toggle($curLock)：期望与 vote as=curLock 快照取反（锁定翻转断言）
    val expect = if (expectRaw.startsWith("toggle(")) {
        val varName = expectRaw.removePrefix("toggle(").removeSuffix(")").removePrefix("$").trim()
        when (varName) {
            "curLock" -> !(vars.curLock ?: false)
            else -> {
                Log.w(TAG, "verify toggle: unknown var '$varName'")
                return
            }
        }
    } else expectRaw.toBoolean()
    // ★ 读判据前先清一次加锁确认弹框：它是全屏模态，盖住锁图标 ⇒ 否则恒读 false、假 FAILED
    // （只有锁定流程会用这条 verify；#93 起仍用 `actTried` 作闸是安全的 —— 该 zone 仅锁流程投）
    // #279：本设备已学会"不再弹确认框"时按 0 预算探一次即可，别白等 1.2s/格（与 [settleLockState] 同口径）。
    if (zone == "artifact.panel.lock" && vars.actTried) {
        dismissLockConfirm(if (lockConfirmAbsent) 0L else 1200L)
    }
    val frame = freshFrame()
    val actual = try {
        when (zone) {
            "artifact.panel.astral" -> VoteJudges.panelAstral(frame, profile, 0).matched
            "artifact.panel.lock" -> {
                probeLockPixels(frame, "verify")
                VoteJudges.panelLock(frame, profile, 0).matched ||
                    VoteJudges.panelLock(frame, profile, profile.zhushengShiftPx).matched
            }
            else -> {
                Log.w(TAG, "verify: zone '$zone' not supported in top-level context")
                return
            }
        }
    } finally { frame.release() }
    if (actual != expect) {
        Log.w(TAG, "verify FAILED: zone=$zone expect=$expect actual=$actual")
        RecognitionLog.log(
            logTag,
            RecognitionLog.Level.W,
            "verify 失败 $zone 期望=$expect 实际=$actual",
        )
    } else {
        // ★ 2026-09-18：**成功也要留一行**。原先只在失败时打日志 ⇒
        //   "点击后状态确实翻转了"与"这一步根本没执行"在日志上**不可区分**
        //   （排查解锁方向时被这条坑过：没有 FAILED 就以为没执行）。
        Log.i(TAG, "verify OK: zone=$zone expect=$expect actual=$actual")
        // #107：锁的 verify **就是**写后回读 ⇒ 它同时满足"点击后复核"这一档，两处一起置，
        // 好让 planDone 能用同一个口径要 Success。
        if (zone == "artifact.panel.lock") {
            vars.actOk = true
            vars.actVerified = true
        }
    }
}

/**
 * 升序索引列表里**最长连续段**的长度（`[3,4,5,9]` → 3）。
 *
 * 用途：区分「零星几格读失败」（定点重访有效）与「整页面板冻结」（定点重访实测 0/502 救回，
 * 见调用处）。入参须已 `distinct().sorted()`。
 */
internal fun ScanEngine.longestRunLen(sortedIdx: List<Int>): Int {
    var best = 0
    var cur = 0
    var prev = Int.MIN_VALUE
    for (i in sortedIdx) {
        cur = if (i == prev + 1) cur + 1 else 1
        if (cur > best) best = cur
        prev = i
    }
    return best
}

/**
 * **定点重访**（★ 2026-09-19 用户定稿，取代「退 1 行 + 整页重扫」）。
 *
 * 背景：格点击**不移动列表**（点击只改选中、不滚动）⇒ 某格读取失败时该格内容**整页不变** ⇒
 * 直接重访该格即可读到，无需退行、无需整页重走。位置信息本就在手
 * （页内行主序 `idx` → `row = idx / cols, col = idx % cols`），此前实现把它丢了才被迫整页重扫。
 *
 * 失败格判据（上游算好传入）：**本格身份 == 上一格身份**（相邻重复 = 本格没读成功；
 * 真机定位：5/5 真漏与 5 次相邻重复一一对应）。
 *
 * ⚠️ 重访期间抑制「连续重复件」计数：重访必然重读到刚记过的件，否则会把 duplicateStreak
 *   拉到阈值误触止扫。
 */
internal suspend fun ScanEngine.revisitFailedCells(
    visitBody: NativeVisitRunner,
    gridKey: String,
    prof: ScreenProfile,
    cols: Int,
    idxList: List<Int>,
    pageKeys: MutableList<String>,
    pageIdentities: MutableList<String>,
): Int {
    suppressDupStreak = true
    var recovered = 0
    try {
        // ★ 2026-09-19「压窗口」：把**连续失败格**聚成"窗口"——先等窗口过去再整段重扫；
        //   孤立失败格仍走短退避。依据：吞击窗口可跨 6 格/~12s（宿主 screencap 对拍实证），
        //   窗口内立即重发无效（同窗口重复点击已被实测证伪：两轮重访无增益），
        //   **等窗口就位**才是正确动作。
        val runs = ArrayList<ArrayList<Int>>()
        for (idx in idxList.sorted()) {
            val last = runs.lastOrNull()
            if (last != null && idx == last.last() + 1) last.add(idx) else runs.add(arrayListOf(idx))
        }
        for (run in runs) {
            val isWindow = run.size >= WINDOW_MIN_CELLS
            val waitMs = if (isWindow) WINDOW_SETTLE_MS else REVISIT_BACKOFF_MS
            Log.w(
                TAG,
                "定点重访: ${if (isWindow) "窗口(${run.size}格)" else "单格"} idx=${run.first()}..${run.last()}" +
                    " ⇒ 先等 ${waitMs}ms 让窗口就位",
            )
            delay(waitMs)
            for (idx in run) {
                val row = idx / cols
                val col = idx % cols
                val before = results.size + resultsWeapons.size + resultsCharacters.size
                // 与空读格回读同理：emit 按 [curCellIdx] 落 `curPageIds`，逐格改写才不会记错槽位
                curCellRow = row
                curCellCol = col
                curCellIdx = idx
                visitBody(this, col, row, idx, prof)
                // ★ #270：管道臂必须**先 join 再取结果**，理由有三条，缺一条都会静默出错：
                //   ① 本页的排干（`pipeDrainResults`）在格循环末尾就已经跑过了，重访 job 是那之后
                //      才发出的 ⇒ 没有任何排水点会把它们交付进 `pageKeys`，唯一交付路径就是这里
                //      直读 `lastCellKey`；不 join 就读到的是**上一格**的残值，把邻件身份写进本格槽位。
                //   ② 下面紧跟着 `lastCellKey = null`：不 join 就可能在消费者**正要把本格结果写进
                //      这两个字段**的时刻把它清空 ⇒ 那一格变成「未交付」，与 #257 同一类丢件。
                //   ③ `recovered` 看的是 results 增量，而 results 只在消费者里增长 ⇒ 不 join 则
                //      管道臂**恒为 0**（「定点重访 0/N 救回」在管道臂上是按构造成立的假现象）。
                //   join 在 `curCellIdx = -1` **之前**（与空读格回读 [ScanGridDomain] 同序）：
                //   emit 用活字段 `curCellIdx` 落 `curPageIds`，先复位会把救回件记到别的槽位。
                if (TimingOverrides.pipeDepth > 0) pipeJoinPage()
                curCellIdx = -1
                val k = lastCellKey
                val id = lastCellIdentity
                lastCellKey = null
                lastCellIdentity = null
                // 回填页表看**读到了没有**，不看"是否新增入库"：重访读到一件已入库的重复件时
                // results 不增长，但那一格确实有身份了 —— 按增长回填会让 pageKeys 与 curPageIds 打架。
                if (!k.isNullOrEmpty() && idx < pageKeys.size) pageKeys[idx] = k
                if (!id.isNullOrEmpty() && idx < pageIdentities.size) pageIdentities[idx] = id
                if (results.size + resultsWeapons.size + resultsCharacters.size - before > 0) recovered++
            }
        }
    } finally {
        suppressDupStreak = false
    }
    return recovered
}

internal suspend fun ScanEngine.vote(
    step: JSONObject,
    gridKey: String? = null,
    col: Int = 0,
    row: Int = 0,
    ctx: ScanEngine.CellFrameContext? = null,
    prof: ScreenProfile = profile,
) {
    val tVote = System.nanoTime() // 只读探针（见 PerfProbe）
    val zoneKey = step.getString("zone")
    // 单格共享帧：panel vote 复用 click 轮询的收敛帧（card vote 在 click 前且 ctx 帧为空，仍自抓）
    val frame = ctx?.acquire { freshFrame() } ?: freshFrame()
    try {
        // ★ #69（design-docs §14.5）：pipe>0 时投票位写**本格暂存**，不写共享 vars ——
        //   消费者此刻正在按上一格的 vars.crafted 决定祝圣 yShift，覆写它就是串格。
        val sink = if (pipeCellVotesStaged) pipePendingVars else vars
        val yShift = if (sink.crafted) profile.zhushengShiftPx else 0
        when (zoneKey) {
            // 网格类判据：坐标随页面相位偏移（prof = 偏移视图）
            "artifact.card.lockBadge" -> {
                val r = VoteJudges.cardLockBadge(frame, prof, requireNotNull(gridKey), col, row)
                sink.gridLocked = r.matched
            }
            "weapon.card.starStrip" -> {
                val r = VoteJudges.weaponStarStrip(frame, prof, requireNotNull(gridKey), col, row)
                sink.rarity = r.count
                Log.d(TAG, "vote starStrip c$r.count col=$col row=$row (D 诊断)")
            }
            "weapon.card.lockBadge" -> {
                val r = VoteJudges.cardLockBadgeByZone(frame, prof, requireNotNull(gridKey), "weapon.card.lockBadge", col, row)
                sink.locked = r.matched
            }
            // panel 类判据：详情面板固定位置，用原始 profile（不随网格相位偏移）
            "artifact.panel.zhusheng" -> {
                val zr = VoteJudges.panelZhusheng(frame, profile)
                // ★ 双帧裁决（2026-10-19 #316）：单帧紫横幅投票区分不了「真祝圣件」与
                //   「上一格祝圣横幅未褪尽的过渡帧」——r4 全量实测两者命中分布完全相同
                //   （真祝圣 23 格 hits=(6,7,5)/25，唯一误报格也是 (6,7,5)）⇒ 调 ratio 无解。
                //   区别在**时间维**：真祝圣横幅恒在；残留横幅会随新面板渲染褪去。
                //   ⇒ 首投命中后等一拍重抓复投，第二帧紫色消失 = 残留 ⇒ 判 false。
                //   开销：仅 2.4% 格子触发（首投命中才复投）× ~122ms ≈ 全轮 3s（0.6%）。
                //   实证链：误报格 L506 施加 yShift 后 level 读到「·暴击」、首条副词条丢失
                //   ⇒ 身份键变化被计为漏件（1280bg 漏4 / 2560bg 漏4 同签名）。
                val verdict = if (!zr.matched) {
                    zr
                } else {
                    delay(ZHUSHENG_CONFIRM_POLL_MS)
                    val f2 = runCatchingCancellable { settleFrame() }.getOrNull()
                    if (f2 == null) {
                        Log.w(TAG, "vote zhusheng: 复投票不可用 ⇒ 维持首投（保守：真祝圣件不受影响）")
                        zr
                    } else {
                        try {
                            val zr2 = VoteJudges.panelZhusheng(f2, profile)
                            if (zr.matched && !zr2.matched) {
                                Log.i(TAG, "vote zhusheng: 双帧裁决首投命中、复投消失 ⇒ 判残留横幅（非祝圣）hits ${zr.count}→${zr2.count}")
                            }
                            zr2
                        } finally {
                            f2.release()
                        }
                    }
                }
                // 诊断：命中数 + 三点采样（排查"祝圣件没被识别"用；GT 实测本账号 20 件 elixerCrafted）
                // ⚠️ 占比门槛打 zone 实际生效值（profile ratio，如 2560/1280=0.15、2244=0.6）——
                //   原先打 Thresholds.BANNER_PURPLE_RATIO(0.6)，而判定走的是 zone ratio ⇒
                //   历史日志里「紫占比>0.6」全是误导显示（判定本身一直正确）。
                Log.i(TAG, "vote zhusheng matched=${verdict.matched} hits=${verdict.count}（≥${VoteJudges.Thresholds.BANNER_POINTS_REQUIRED}/3 且紫占比>${profile.zone("artifact.panel.zhusheng")?.optDouble("ratio", VoteJudges.Thresholds.BANNER_PURPLE_RATIO) ?: VoteJudges.Thresholds.BANNER_PURPLE_RATIO}）col=$col row=$row")
                sink.crafted = verdict.matched
            }
            "artifact.panel.lock" -> {
                // 双区判定：同一单件名可普通(锁徽 y)/祝圣(锁徽 y+shift)两种面板（2560 实测 744 vs 808），
                // 任一区 gold 达阈即已锁；artifact_lock flow 不投 zhusheng，故弃 vars.crafted 改自动。
                sink.locked = VoteJudges.panelLock(frame, profile, 0).matched ||
                    VoteJudges.panelLock(frame, profile, profile.zhushengShiftPx).matched
                if (step.optString("as") == "curLock") sink.curLock = sink.locked
            }
            "artifact.panel.astral" -> {
                // ★ 2026-10-20：双区判定（与 artifact.panel.lock 同款）—— 祝圣横幅漏投时
                //   crafted=false ⇒ yShift=0 ⇒ 收藏星标读到未移位的行；而「空词条自愈」在
                //   消费者侧（裁片坐标，拿不到整帧重投）。这里一次判两个区，正常件两区都读不到
                //   金标（移位区是词条行/空档），真祝圣件由移位区命中。
                val shift = profile.zhushengShiftPx
                sink.favorited = VoteJudges.panelAstral(frame, profile, 0).matched ||
                    (shift > 0 && VoteJudges.panelAstral(frame, profile, shift).matched)
            }
            "artifact.rarity" -> {
                sink.rarity = VoteJudges.rarityFromBanner(frame, profile)
            }
            // ★ 2026-09-11：武器星级改由**详情面板星行**逐格判定（对齐圣遗物 starBand），
            //   取代卡片 starStrip —— 后者在 3★/4★ 边界抖动（同件两次读出 3★/4★，36 件中 10 件）。
            //   面板星行是固定坐标、大而清晰；5★ 大星带辉光会连成一片，故必须逐格采样（countStars 已是逐格）。
            "weapon.panel.starBand" -> {
                val panelStars = countStars(frame, "weapon_backpack")
                if (panelStars > 0) {
                    // ★ #259：必须写 **sink** 而不是 vars。`vote()` 其余分支都走 sink（pipe>0 时
                    //   sink = 生产者自持的暂存 ScanVars，随 job 快照给消费者），只有这两行漏改 ⇒
                    //   生产者把 rarity 写进共享 vars，而 `pipeSend` 从暂存取到的是 **-1 哨兵**，
                    //   消费者开头 `applyTo(vars)` 又把 -1 **盖回** 真值 ⇒ parseWeaponPanel 见 rarity<0
                    //   直接 return ⇒ **BS 2244 实测 63 格全解析、0 件入库、`导出跳过：无已入库结果`**。
                    //   与下面那条回退分支注释担心的"整档导出 0 件"是同一个后果，只是换了触发路径。
                    sink.rarity = panelStars
                    // ★ #265：同一份值留在生产者侧 —— 止扫判据在下一格第 ② 步读它，
                    //   那时共享 vars 已被消费者装载成"落后 ≤pipeDepth 格"的那一格。
                    lastVotedWeaponRarity = panelStars
                    Log.d(TAG, "weapon panel stars = $panelStars")
                } else {
                    // ⚠️ 回退：profiles 未标定 `panels.weapon_backpack.starBand` 的分辨率档
                    //    （3200/2560 目前没有）⇒ 走回旧的卡片星带判据，避免 rarity=0 让
                    //    parseWeaponPanel 直接 return（**整档导出 0 件**）。
                    val r = VoteJudges.weaponStarStrip(frame, prof, requireNotNull(gridKey), col, row)
                    sink.rarity = r.count
                    lastVotedWeaponRarity = r.count
                    Log.d(TAG, "weapon panel stars unavailable → fallback card starStrip = ${r.count}")
                }
            }
            else -> Log.w(TAG, "vote zone '$zoneKey' not implemented, skipped")
        }
    } finally {
        if (ctx?.frame !== frame) frame.release()
        PerfProbe.addVote(System.nanoTime() - tVote)
    }
}

// ---- #10 stopWhen：表达式谓词（D3）----

/**
 * ★ simple 分支（spec §7）stopWhen 的两个**语义入口**之一：duplicateStreak 止扫**登记**。
 * 判据本身在 emitCharacter 求值（那里才拿得到刚解析出的角色名与已入库集合）。
 * [stopWhen] 的 mode=duplicateStreak 分支与原生 visitBody 都走这里（单一事实源）。
 */
internal fun ScanEngine.stopWhenRegisterDuplicateStreak(streak: Int, dupPageConfirm: Int) {
    val confirm = dupPageConfirm.coerceIn(1, 5)
    // ★ #219：同值重复登记**不打日志**。本函数在同步臂是**逐格**被 visit 体调用的
    //   （`runVisitBody` 的 pipe 分支反而有 `if (changed)` 守卫）⇒ 不打这个补丁的话
    //   真机日志里每格一行"stopWhen 登记"，把"值真的变了"这件事淹掉；而且流水线臂
    //   现在也会在流程开头先登记一次（见各 flow 的 `pagedGridNative` 之前），
    //   同一轮里出现两次同值登记是常态。
    if (charDupStreakLimit == streak && this.dupPageConfirm == confirm) return
    charDupStreakLimit = streak
    // 页级确认页数（默认 2）：`1` = 恢复旧的「首次整页重复即停」行为
    this.dupPageConfirm = confirm
    Log.i(
        TAG,
        "stopWhen 登记 mode=duplicateStreak streak=$charDupStreakLimit dupPageConfirm=${this.dupPageConfirm}" +
            "（判据在 emitCharacter 求值）",
    )
}

/**
 * ★ simple 分支（spec §7）stopWhen 的两个**语义入口**之二：weapon_scan 的 expr `"rarity <= 2"`
 * 的**原生式**（该条 Expr 退役）。语义与 stopWhen(expr) 逐位对应：
 * - 哨兵守卫：rarity<0（vote 未判定，如 3★ 蓝卡无金/紫 banner）跳过——否则 `<` 比较对 -1 恒真，
 *   会把"整页未判定"误判为"全部低星"而立即终止；
 * - `rarity in 0..2` ⇒ stopRequested=true / stopReason="stopWhen"（scope=page 立即停；
 *   原 flow 的 action="Stop+flush" 在引擎里**无消费者**——flush 就是扫描结束的自然导出）；
 * - 命中/未命中对 [ScanEngine.stopWhenCellStreak] 的记账与 Expr 路径同口径（该计数只被
 *   scope=cell 的连续确认消费，weapon 这条恒 page ⇒ 不改变行为，纯记账一致）。
 *
 * ★ #265：读的是 [ScanEngine.lastVotedWeaponRarity]（**生产者自己投的**），不是 `vars.rarity`。
 *   本函数在 visit 步序第 ② 位求值，也就是**上一格**投出的星级（本格要到第 ⑤ 位才投票）——
 *   同步臂下 `vars.rarity` 恰好就是那个值，所以以前看不出差别。管道臂下 `vars` 由消费者按它
 *   自己的格装载 ⇒ 这里读到的是"落后 ≤pipeDepth 格"的星级 ⇒ **止扫点前移/后移**，
 *   收尾那几件低星武器两臂收得不一样（导出差在尾部，`PAGES=3` 的验收跑**永远撞不到**，
 *   因为那三页根本走不到列表末尾的素材区）。改成读生产者自持的那一份后两臂同值，
 *   与同步臂旧行为逐位一致（同步臂下两者本来就相等）。
 */
internal fun ScanEngine.stopWhenRarityAtMost2() {
    val rarity = lastVotedWeaponRarity
    // 哨兵保护（与 Expr 路径同款）：rarity=-1 不得触发
    if (rarity < 0) {
        Log.d(TAG, "stopWhen skipped: rarity not judged yet (-1 sentinel)")
        return
    }
    if (rarity in 0..2) {
        stopWhenCellStreak++
        vars.stopRequested = true
        vars.stopReason = "stopWhen"
        Log.i(TAG, "stopWhen triggered (page): rarity <= 2")
    } else {
        stopWhenCellStreak = 0
    }
}

internal fun ScanEngine.stopWhen(step: JSONObject) {
    // 显式模式优先（不再靠 expr 正则猜分支）：mode=duplicateStreak 的判据在 emitCharacter 求值
    // （那里才拿得到刚解析出的角色名与已入库集合；flow 顺序 emit→stopWhen 时 charName 已被清空）。
    if (step.optString("mode") == "duplicateStreak") {
        // ★ simple 分支（spec §7）：duplicateStreak 分支直提为语义入口（原生流程直调；JSON 路径照旧到这）。
        stopWhenRegisterDuplicateStreak(step.optInt("streak", 3), step.optInt("dupPageConfirm", 2))
        return
    }
    val expr = step.optString("expr")
    if (expr.isEmpty()) {
        Log.w(TAG, "stopWhen: 既无 mode 也无 expr，忽略")
        return
    }
    // §14 P1：panelMatch(target, tol=0.1) —— Expr 不支持函数调用，此处直接求值 [hardMatch]
    if (expr.contains("panelMatch(")) {
        val task = vars.currentTask
        if (task == null) {
            Log.d(TAG, "panelMatch skipped: 无 currentTask（P4 未注入计划）")
            return
        }
        val tol = Regex("tol\\s*=\\s*([0-9.]+)").find(expr)?.groupValues?.getOrNull(1)
            ?.toDoubleOrNull() ?: 0.1
        val why = StringBuilder()
        if (hardMatch(task, tol, why)) {
            vars.stopRequested = true
            vars.stopReason = "stopWhen"
            // ★★ #93：命中即"本格就是目标件" ⇒ 置 `matchHit` ★★
            //   auto_equip 的命中判据**只在这条路径上**（它的 parsePanel 不声明 `match`）；
            //   不置的话 foreach 状态映射拿不到"命中"这个事实，装好了也只能报 NotFound。
            //   （artifact_lock 走 `planMatch`，那条路本来自带置位，不受影响。）
            vars.matchHit = true
            Log.i(TAG, "stopWhen triggered (panelMatch tol=$tol): $expr | $why")
        } else {
            // 未命中也要留痕：否则「判据不生效」与「确实不匹配」在日志上不可区分。
            Log.i(TAG, "panelMatch 未命中 (tol=$tol): $why")
        }
        return
    }
    // 哨兵保护：rarity=-1（vote 未判定，如 3★ 蓝卡无金/紫 banner）不得触发止扫表达式
    // （rarity<4 对 -1 恒真 → 会把"整页未判定"误判为"全部低星"而立即终止）。
    if (expr.contains("rarity") && vars.rarity < 0) {
        Log.d(TAG, "stopWhen skipped: rarity not judged yet (-1 sentinel)")
        return
    }
    // ★ #233 单2④（2026-10-14 删）：这里原本有一条 `expr.contains("roster[0]")` 特判
    //   （首名重现=遍历完），注释写着"仅为兼容旧 flow 而保留"。核过的结论是**它按构造走不到**：
    //   `stopWhen(step)` 只有 `visitStopWhen`（EquipFlow / LockFlow）和 pipe 消费者两个调用方，
    //   而 character_scan 不在 `PIPE_DEFAULT_BY_FLOW` 里、其原生体直接调 `stopWhenRegisterDuplicateStreak`；
    //   Kotlin 与 dsl/json 双侧 grep 都没有任何地方再声明含 `roster[0]` 的表达式
    //   （`rosterFind` 是另一个原语，别把它当这条判据的引用）。
    //   留着它的代价不是性能，是**下一次诊断会被它骗**：看代码会以为角色轮有"回绕即停"这条路，
    //   而实际收尾一直是 duplicateStreak / reachedEnd（2026-09-11 那次 10 角色就是 reachedEnd 收尾）。
    //   判据失效的机制本身仍记在 CharDupJudge KDoc（`emit` 在 `stopWhen` 之前 ⇒ 读草稿名恒空）。
    val scope = step.optString("scope", "page")
    // 兜底：坏表达式（未定义变量/语法越界）不得炸掉整个流程——记 warn 后按未命中处理
    // ★ 2026-09-18：函数式写法（max(plan.level) / min(plan.rarity)）Expr 不支持 ⇒ 与 pageSkip
    //   一样先做文本替换。此前只替换了 pageSkip 的表达式，`stopWhen` 里的 min(plan.rarity)
    //   直接被 Expr 拒（unexpected char '('）⇒ 稀有度止扫**静默失效**（按未命中处理，不报错）。
    val expr2 = expr
        .replace("max(targets.level)", "targetMaxLevel")
        .replace("max(plan.level)", "targetMaxLevel")
        .replace("min(plan.rarity)", "targetMinRarity")
    val hit = runCatchingCancellable { Expr.eval(expr2, vars.exprVars()) }
        .onFailure { Log.w(TAG, "stopWhen 表达式求值失败，按未命中处理：$expr2 (${it.message})") }
        .getOrDefault(false)
    if (hit) {
        // ★ 2026-09-16 守卫（真机实测驱动）：`scope=cell` 的 3★/2★ 止扫**必须连续 2 格命中**才认。
        //   本账号实测**没有 3★/2★**（背包 941 全 5★/4★）⇒ 任何 `rarity < 4` 读数都是**单格星级误读**；
        //   一次误读即置 stopRequested 会把整轮扫描在第 10 页截断（实测只入库 149/941）。
        //   连续 2 格同时误读同一 rarity<4 的概率极低 ⇒ 代价可忽略，收益是避免整轮报废。
        stopWhenCellStreak++
        if (scope == "cell" && stopWhenCellStreak < 2) {
            Log.i(TAG, "stopWhen ($scope) 命中但未连续确认（$stopWhenCellStreak/2）⇒ 继续扫描：$expr")
            return
        }
        // scope=cell（本页后停）：置 flag，页遍历结束后停止；scope=page 立即停
        vars.stopRequested = true
        vars.stopReason = "stopWhen"
        Log.i(TAG, "stopWhen triggered ($scope): $expr")
    } else {
        stopWhenCellStreak = 0
    }
}

/**
 * ★ 通用「就绪轮询」（2026-09-12）：签名停稳 +（可选）锚内容非空。
 *
 * 两个调用方共用（面板就绪 / 导航页就绪），语义一致：
 * 1. **已变**：签名与动作前不同；
 * 2. **连续 [SIG_STABLE_SAMPLES] 次「跨帧」一致** ⇒ 渲染完成（3 次防交叉淡入的平台期）；
 * 3. **必须跨帧**：步长 < 帧间隔时同一帧自比会假稳定 ⇒ 用 `frameGeneration()` 作门；
 * 4. **未变兜底**：一直与动作前相同且过了 `fallbackMs` ⇒ 认定"本来就是这个状态"
 *    （⚠️ 此支**不要求跨帧**：静态屏不产新帧）；
 * 5. **下限 `minMs`**：防"动作尚未生效、页面还静止"时立刻判稳（导航场景必需）；
 * 6. **锚确认 `confirm`**：判稳后跑一次确认；返回 false ⇒ **以当前签名为新基准再等一轮**，
 *    最多 [SIG_CONFIRM_RETRIES] 次。这一步替代旧判据的"文本非空"隐含防护（空白平台期）。
 *
 * @param what 日志前缀（如 `panel` / `nav:天赋`）
 * @return **整个等待期是否至少观察到一次变化**。`false` = 面板签名**从未变过** ⇒ 极可能
 *   **点击被送达但被游戏吞掉**（2026-09-16 run6 实证：该页 21 格耗时**零方差且最快**
 *   = 就绪轮询一上来签名即稳定 = 内容从未变化；第 1 格是新件、第 2~21 格全重复）。
 *   调用方（cell 路径）据此做**同坐标重发**兜底。
 *   ⚠️ 与内部 `changedEver` **不同**：后者会被 `confirm 空读重试`重置（见下），不可用作该判据。
 *   签名不可用而中止 ⇒ 无法判断 ⇒ 返回 `true`（按"已送到"处理，不触发重发）。
 */
internal suspend fun ScanEngine.waitReadyBySignature(
    before: ByteArray,
    a: ByteArray,
    b: ByteArray,
    roi: FrameRect,
    /** 与「动作前基准」**必须同一网格**（块数变了 = 每块覆盖的物理区域变了 = 一比就"变了"）。 */
    blocksX: Int,
    blocksY: Int,
    step: Long,
    maxMs: Long,
    minMs: Long,
    fallbackMs: Long,
    what: String,
    /** 认定"已稳定"所需的**跨帧**连同样本数（默认 [SIG_STABLE_SAMPLES]）。 */
    stableSamples: Int = SIG_STABLE_SAMPLES,
    /**
     * ★ 2026-10-20：**首次检出变化之后**至少要再等这么久才允许判稳（默认 0 = 旧行为）。
     * 用于武器面板：后台 VD 帧在切换初期会先冒一个"仍像上一件"的稳定态，2×20ms 就判稳
     * 会解析到上一把武器（相邻格同身份重复）。见 [TimingOverrides.weaponPanelSettleMs]。
     */
    minAfterChangeMs: Long = 0L,
    confirm: (suspend () -> Boolean)? = null,
): Boolean {
    val roiI = roi.toIntRect()
    val sigBlocks = blocksX * blocksY
    var waited = 0L
    var first = true
    var lastGen = Long.MIN_VALUE
    var changedEver = false
    // ★ 2026-09-16：与 changedEver 并行、**永不重置**的"见过变化"标记（返回值判据，见 KDoc）
    var sawAnyChangeEver = false
    var sameStreak = 0
    var retries = 0
    var prev: ByteArray? = null
    var cur = a
    // ── 分段计时（只读诊断，`sigdebug=1` 才打）──
    var firstChangeAt = -1L          // 从"开始轮询"到**首次读到已变**的耗时 = 页面渲染时长
    var lastCountedAt = 0L
    var frameGapSum = 0L
    var frameGaps = 0
    var samples = 0
    fun logWait(reason: String) {
        if (!TimingOverrides.sigDebug || what != "panel") return
        Log.i(
            TAG,
            "sigWait[$what] $reason total=${waited}ms firstChange=${if (firstChangeAt < 0) "none" else "${firstChangeAt}ms"} " +
                "afterChange=${if (firstChangeAt < 0) "n/a" else "${waited - firstChangeAt}ms"} " +
                "samples=$samples frameGaps=$frameGaps/${frameGapSum}ms step=$step",
        )
    }
    while (waited < maxMs) {
        delay(step)
        waited += step
        if (!frameSource.sampleSignature(roiI, cur, blocksX, blocksY)) {
            Log.w(TAG, "$what signature unavailable mid-poll, abort")
            return true   // 无法判断 ⇒ 不判"点击被吞"
        }
        val gen = frameSource.frameGeneration()
        val genChanged = first || gen != lastGen
        lastGen = gen
        if (genChanged) {
            samples++
            if (lastCountedAt > 0) {
                frameGapSum += waited - lastCountedAt
                frameGaps++
            }
            lastCountedAt = waited
        }
        val changed = changedFraction(before, cur, sigBlocks) > 0f
        if (changed && firstChangeAt < 0) {
            firstChangeAt = waited
            // 诊断（`sigdebug=1`）：**首次检出变化**时打出块网格掩码 ——
            // 用来回答"band 里到底是**哪一块**先变"（若只是边框/背景先动，说明该把 ROI 收窄）。
            if (TimingOverrides.sigDebug && what == "panel") {
                Log.i(TAG, "sigFirstChange[${roiI.left},${roiI.top},${roiI.right},${roiI.bottom}] " +
                    "waited=${waited}ms\n${blockMask(before, cur, blocksX, blocksY)}")
            }
        }
        val p = prev
        var stable = false
        when {
            changed -> {
                changedEver = true
                sawAnyChangeEver = true
                // 未动判据**严格**：任何一块不同都不算"同一状态"（见 SIG_LEN 旁的事故记录）
                val sameAsPrev = p != null && changedFraction(p, cur, sigBlocks) == 0f
                sameStreak = if (sameAsPrev && genChanged) sameStreak + 1 else 1
                if (genChanged) tmSigSamples++
                stable = sameStreak >= stableSamples && waited >= minMs &&
                    (firstChangeAt < 0 || waited - firstChangeAt >= minAfterChangeMs)
            }
            !changedEver -> {
                if (genChanged) tmSigSamples++
                // 未变兜底：用"距动作的墙钟"（静态屏也能兜底）
                if (waited >= maxOf(fallbackMs, minMs)) {
                    logWait("unchanged-fallback")
                    return sawAnyChangeEver   // false = 全程未变 ⇒ 疑似点击被吞
                }
            }
            else -> sameStreak = 0
        }
        if (stable) {
            val ok = confirm?.invoke() ?: true
            if (ok) {
                logWait("stable")
                return sawAnyChangeEver
            }
            if (retries >= SIG_CONFIRM_RETRIES) {
                Log.w(TAG, "$what confirm still empty after $retries retries, proceed")
                return sawAnyChangeEver
            }
            retries++
            Log.i(TAG, "$what confirm empty → 以当前签名为新基准再等一轮 retry=$retries")
            System.arraycopy(cur, 0, before, 0, before.size) // 新基准 = 当前（可能是空白平台）
            changedEver = false
            sameStreak = 0
            prev = null
            cur = a
            first = true
            continue
        }
        // 轮换缓冲：prev = 本轮样本，cur = 下一轮写入目标
        val other = if (cur === a) b else a
        prev = cur
        cur = other
        first = false
    }
    logWait("timeout")
    Log.w(TAG, "$what signature not settled within ${maxMs}ms")
    return sawAnyChangeEver
}

/**
 * 面板**就绪内容带**：把面板里"靠后渲染的内容区"取并集，作为就绪签名的采样区。
 *
 * ⚠️ **不要用名字区做就绪信号**（2026-09-12 定论）：名字是**最先**渲染完的 ⇒
 *   "名字稳定" ≠ "面板渲染完" ⇒ 早退出 ⇒ `parsePanel` 读到半渲染的等级/副属性
 *   —— 这正是 artifact_scan 曾把 **20 件导成 18 件**的根因（靠"确认 OCR 读名字"兜不住，
 *   因为名字本来就早好了）。参考 GOODScanner `PANEL_POOL_RECT=(1330,478,370,187)`@1920：
 *   他们同样选**中下方面板窗口**（等级+前两条副属性），而不是名字。
 *
 * 取值为 [PANEL_BAND_KEYS] 里**存在**的项的并集（按 panel 自动适配圣遗物/武器/角色）；
 * 坐标统一走 [ScreenProfile.scaleRect] ⇒ 三档分辨率自动适配。
 *
 * @return null = 该面板没有可用内容带（调用方回退名字区）
 */
internal fun ScanEngine.panelReadyBand(panelKey: String): FrameRect? {
    val d = profile.rawObject("panels.$panelKey") ?: return null
    var l = Int.MAX_VALUE
    var t = Int.MAX_VALUE
    var r = Int.MIN_VALUE
    var b = Int.MIN_VALUE
    var any = false
    fun takeRect(arr: org.json.JSONArray) {
        if (arr.length() < 4) return
        val x0 = arr.optInt(0, Int.MIN_VALUE)
        val y0 = arr.optInt(1, Int.MIN_VALUE)
        val x1 = arr.optInt(2, Int.MIN_VALUE)
        val y1 = arr.optInt(3, Int.MIN_VALUE)
        if (x0 == Int.MIN_VALUE || y0 == Int.MIN_VALUE || x1 == Int.MIN_VALUE || y1 == Int.MIN_VALUE) return
        val sc = profile.scaleRect(x0, y0, x1, y1)
        if (sc.left < l) l = sc.left
        if (sc.top < t) t = sc.top
        if (sc.right > r) r = sc.right
        if (sc.bottom > b) b = sc.bottom
        any = true
    }
    // ⚠️ 取**面板内所有矩形条目**的并集（不按 key 过滤）：内容带必须**覆盖我们实际要读的每一个字段**，
    //    否则"带内已稳定"≠"整面板已就绪"。2026-09-12 实测踩过：按 key 只收左列属性行 ⇒ 武器面板的
    //    **锁图标（x2017）落在带外** ⇒ 2 样本 + 免确认时锁还没渲染就读 ⇒ 一件武器的 `lock` 由 true 读成 false。
    for (k in d.keys()) {
        val v = d.opt(k)
        if (v is org.json.JSONArray) {
            // 单矩形（4 个数字）或矩形数组（subStats 之类）；starBand 这类对象会被自然跳过
            if (v.length() > 0 && v.opt(0) is org.json.JSONArray) {
                for (i in 0 until v.length()) (v.opt(i) as? org.json.JSONArray)?.let { takeRect(it) }
            } else {
                takeRect(v)
            }
        }
    }
    if (!any || r <= l || b <= t) return null
    return FrameRect(l, t, r, b)
}

/**
 * 面板**星级行是否已画出**（就绪确认用）。
 *
 * ⚠️ 为什么必须有这一条（2026-09-12 实测）：把块网格细化到 16×8 后，"已变"能在 40ms 检出、
 *   80ms 即判稳；但武器面板存在**空白平台期**（像素静止、内容未画）⇒ 名字已可读而**星级行还没画**
 *   ⇒ `countStars` 读 0 ⇒ 误触发 `stopWhen(rarity<=2)`（实测导出 36 → 1~8 件）。
 *   ⇒ 就绪确认必须覆盖**实际出错的字段**，不能只看名字。
 * 无 `starBand` 的面板（如角色）⇒ 返回 true（不做此判据，由其余判据兜）。
 */
/**
 * 面板 → 就绪签名**块网格**（未显式 `sigblocks` 覆盖时生效）。
 *
 * ⚠️ 实测理由（2026-09-12，见 `PIPELINE-FEASIBILITY.md` §14.7）：
 * - **artifact_backpack → 16×8（细）**：圣遗物面板的内容在**约 1 帧内换完**，细网格能立刻检出。
 *   ⚠️ 但当年记的 "`firstChange=40ms`、等待 240→80ms、perCell −20%" **不可信**：那时 [waitReadyBySignature]
 *   的轮询把网格**写死成 8×4**，与这里的 16×8 基准不同网格 ⇒ 一上来就判"已变"（见审计 P1-1），
 *   80ms 退出是**在空白平台期早退**，不是内容就绪。改完（基准与轮询同网格）后这组数字要**重测**再定。
 * - **weapon_backpack → 8×4（粗）**：武器面板切换有一个 **~200ms 的过渡态**：整面板像素先全变、
 *   **文本约 200ms 后才换**。细网格会把过渡态误判成"已换完"（实测掩码 16×8 全 `#`、80ms 退出）
 *   ⇒ 读到**滞后 2 格的旧面板** ⇒ 内容指纹重复 ⇒ 被去重误杀（导出 36 → 1~8 件）。
 *   粗网格的块均值在过渡态几乎不动 ⇒ 要等内容真正出现才检出 ⇒ 天然躲开过渡态。
 * ⇒ 与 GOODScanner 一致（它对**武器用 FixedDelay**、其余用 Fingerprint：相同武器面板完全相同，
 *   指纹永不变化）。
 */
internal fun ScanEngine.sigBlocksFor(panelKey: String): Pair<Int, Int> =
    if (panelKey == "artifact_backpack") {
        SIG_BLOCKS_FINE_X to SIG_BLOCKS_FINE_Y
    } else {
        SIG_BLOCKS_X to SIG_BLOCKS_Y
    }

/**
 * 被点卡片自身的矩形（帧坐标）。
 *
 * 必须用**页面视图** [prof]（含 §12.5 的相位偏移）取，与 `prof.cellCenter` 同一套坐标，
 * 否则签名区会跟点击点错开一格。
 */
internal fun ScanEngine.cardRoi(prof: ScreenProfile, gridKey: String, col: Int, row: Int): IntRect? {
    val g = prof.gridGeometryFor(gridKey) ?: return null
    return prof.cardRelRect(gridKey, intArrayOf(0, 0, g.cardW, g.cardH), col, row).toIntRect()
}

/**
 * 「点击是否落到卡片上」——看**被点卡片的选中框**有没有出现，而不是看详情面板。
 *
 * 为什么需要它（2026-09-23，BlueStacks 实测）：面板签名判"未变"有两种完全不同的成因，
 * 而旧代码把它们当成一种：
 * 1. **点击被吞**（真该重发）；
 * 2. **相邻两格内容本来就一样** —— 武器尤其常见（同名同等级同精炼），面板像素一模一样，
 *    指纹**永远不会变**（`WEAPON_SAME_IDENTITY_CAP` 上方那条 🔴 注释说的就是这个死结）。
 * 实测代价：1 页武器 21 格里 7 格被判"疑似吞击"，白烧 35 次重发 ≈ 15s。
 *
 * 选中框能把它们分开：点中了 ⇒ 高亮框换到被点卡（实测被点卡 11/64 块变、原选中卡 10/64、
 * 其余 21 格 0/64）；点击没送达 ⇒ 被点卡一块都不动。
 *
 * @param before [cardRoi] 上**点击前**采好的签名
 * @return false = 采样不可用（按"没移动"处理，保守走重发）
 */
internal fun ScanEngine.cardFrameMoved(roi: IntRect, before: ByteArray): Boolean {
    val n = CARD_SIG_BLOCKS * CARD_SIG_BLOCKS
    if (!frameSource.sampleSignature(roi, sigCardCur, CARD_SIG_BLOCKS, CARD_SIG_BLOCKS)) return false
    return changedFraction(before, sigCardCur, n) * n >= CARD_MOVED_MIN_BLOCKS
}

internal fun ScanEngine.panelHasStarBand(panelKey: String): Boolean {
    val band = profile.rawObject("panels.$panelKey")?.optJSONObject("starBand") ?: return false
    return band.has("y") && band.has("x0")
}

/**
 * 两签名差异占比（复用既有容差语义；长度不一致/越界按"变了"处理）。
 *
 * ⚠️ [blocks] 必须显式给：签名缓冲是**复用的定长 scratch**（`SIG_LEN_MAX`），
 *   只比较一次采样真正写入的前 `blocksX*blocksY*3` 字节。整数组比较会把**没写过的尾部**
 *   也当成数据 ⇒ 换个网格就"全变了"（2026-09-23 审计 P1-1 的成因）。
 */
internal fun ScanEngine.changedFraction(x: ByteArray, y: ByteArray, blocks: Int = -1): Float =
    VoteJudges.thumbChangedFraction(x, y, VoteJudges.THUMB_DIFF_TOL, blocks) ?: 1f

/** 块网格差异掩码（'.'=同 '#'=变，第一行=ROI 上沿）；仅供 `sigdebug` 诊断。 */
internal fun ScanEngine.blockMask(a: ByteArray, b: ByteArray, bx: Int, by: Int): String {
    val sb = StringBuilder("  blockMask(${bx}x$by) . =同 # =变\n")
    for (y in 0 until by) {
        sb.append("  ")
        for (x in 0 until bx) {
            val i = (y * bx + x) * 3
            if (i + 2 >= a.size || i + 2 >= b.size) {
                sb.append('?')
                continue
            }
            val d = maxOf(
                kotlin.math.abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)),
                kotlin.math.abs((a[i + 1].toInt() and 0xFF) - (b[i + 1].toInt() and 0xFF)),
                kotlin.math.abs((a[i + 2].toInt() and 0xFF) - (b[i + 2].toInt() and 0xFF)),
            )
            sb.append(if (d > VoteJudges.THUMB_DIFF_TOL) '#' else '.')
        }
        sb.append('\n')
    }
    return sb.toString()
}
