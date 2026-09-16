package com.bettergi.pocket.scan

import org.opencv.core.Mat
import java.util.Arrays

/**
 * **面板指纹**（移植 GOODScanner `GenshinGameController.wait_until_panel_loaded` / `panel_snapshot`，
 * 2026-09-16）。用途：**不跑 OCR 就能判定"这次点击是不是又点了同一张卡"** ⇒ 直接去重。
 *
 * ## GOODScanner 原设计（deepwiki 查证）
 * - 维护 `panel_snapshot` = 上次面板某区域的**原始像素**
 * - 点击网格项后反复抓该区域：**当前帧 ≠ snapshot（换了卡）** 且 **连续两帧相同（稳定）** ⇒ 才认为加载完
 * - 面板没变 ⇒ **不跑 OCR**；翻页时清空 snapshot（新页首项永远算新）
 * - `PANEL_POOL_RECT = (1330,478,370,187) @1920×1080` = **副词条 + 套装名区**（y=478 避开锁图标淡入动画）
 *
 * ## 我方适配（⚠️ 不可照搬 1920 基准）
 * 两档面板**不是等比缩放**（1920×1.333 → y=637，而我方副词条在 y794..1040）⇒ 指纹区域**从自己的
 * profile 取**：`panels.<key>.subStats`（4 行）∪ `mainValue`，`artifact_manage` 再加 `setName`。
 * —— 语义与 GOODScanner 一致（副词条区是最能区分"不同件"的稳定文本区，且不含锁图标/星标等淡入元素）。
 *
 * ## 与内容键去重的关系
 * 指纹是**上游闸门**：指纹相同 ⇒ 根本不解析、不入库（省一次 OCR ≈40ms/格，重复格约占 1/3）；
 * 内容键去重**仍然保留**作为兜底（跨页非相邻的重复、以及指纹噪声导致的漏判）。
 */
object PanelFingerprint {

    /**
     * 指纹**子区域列表**（按固定顺序拼接成指纹）：profile 的 `subStats`（4 行） + `mainValue`（+ setName）。
     *
     * ⚠️ 2026-09-16 真机抓图定稿：**不要用"外包矩形"**。外包框 y490..1040 会把
     * **锁 🔒 / 收藏 ⭐ 图标**（约 y700..760）也框进来 —— 而 GOODScanner 明确用 `y=478` 起
     * **正是为了避开锁图标的淡入动画**（`PANEL_POOL_RECT` 只取副词条+套装名区）。
     * 含动画元素 ⇒ 同一张卡两次读可能不等 ⇒ 漏判重复（只是多跑一次 OCR，无害但白费）；
     * 且抓拍已验证：真实面板该区内容 = 主词条值 + 4 条副词条，足够区分不同件。
     */
    fun regions(profile: ScreenProfile, panelKey: String): List<FrameRect> {
        val base = "panels.$panelKey"
        val rects = ArrayList<FrameRect>(6)
        (profile.rawObject(base)?.optJSONArray("subStats"))?.let { arr ->
            for (i in 0 until arr.length()) {
                val r = arr.optJSONArray(i) ?: continue
                if (r.length() >= 4) {
                    rects += profile.scaleRect(r.getInt(0), r.getInt(1), r.getInt(2), r.getInt(3))
                }
            }
        }
        listOf("mainValue", "setName").forEach { key ->
            profile.rawObject(base)?.optJSONArray(key)?.takeIf { it.length() >= 4 }?.let {
                rects += profile.scaleRect(it.getInt(0), it.getInt(1), it.getInt(2), it.getInt(3))
            }
        }
        return rects
    }

    /** 抓全部子区域的原始像素并**按序拼接**成指纹（⚠️ 必须逐行 get，整块 get 只有首像素有效）。 */
    fun capture(frame: Mat, rects: List<FrameRect>): ByteArray? = runCatching {
        val chunks = ArrayList<ByteArray>(rects.size)
        val row = ByteArray(frame.cols() * frame.channels())
        for (rect in rects) {
            val x0 = rect.left.coerceIn(0, frame.cols() - 1)
            val y0 = rect.top.coerceIn(0, frame.rows() - 1)
            val x1 = rect.right.coerceIn(0, frame.cols())
            val y1 = rect.bottom.coerceIn(0, frame.rows())
            if (x1 <= x0 || y1 <= y0) continue
            val w = (x1 - x0) * frame.channels()
            val out = ByteArray(w * (y1 - y0))
            var p = 0
            for (y in y0 until y1) {
                frame.get(y, 0, row)
                System.arraycopy(row, x0 * frame.channels(), out, p, w)
                p += w
            }
            chunks += out
        }
        if (chunks.isEmpty()) return null
        val total = chunks.sumOf { it.size }
        val joined = ByteArray(total)
        var p = 0
        for (c in chunks) {
            System.arraycopy(c, 0, joined, p, c.size)
            p += c.size
        }
        joined
    }.getOrNull()

    /** 两帧指纹是否"同一块面板"。原始像素**精确相等**（GOODScanner 同款；面板是静态 UI 文本区）。 */
    fun same(a: ByteArray?, b: ByteArray?): Boolean = a != null && b != null && Arrays.equals(a, b)
}
