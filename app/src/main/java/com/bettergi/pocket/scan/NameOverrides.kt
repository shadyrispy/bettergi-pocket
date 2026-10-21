package com.bettergi.pocket.scan

import android.content.Context
import android.content.SharedPreferences

/**
 * 玩家自定义显示名 → GOOD key（#105，与 GOODScanner `good_config.json` 的 `NameOverrides` 同构）。
 *
 * **为什么交给用户填，而不是再猜一张内置表**：这几个角色的显示名由玩家自定义，词典以官方中文名为键，
 * 结构性不可能命中。上游对同一个问题的答案就是不猜：`NameOverrides{traveler_name, wanderer_name,
 * manekin_name, manekina_name}` 由用户手填。
 *
 * 本账号实测到的显示名（2026-09-29 由**已入库的武器配对**定方向，不是猜的：扫描读到的主人名
 * 对上 GT 同一把武器的 `location`）：`随机姓名`→Manekina、`随机人名`→Manekin、
 * 旅行者叫 `崽崽`/`魏崽`（填进 [traveler] 这一格，元素后缀由角色面板补，见 [characterKeyOf]）。
 * ⚠️ 早先那张内置表写的是 `随机姓→Manekin` —— **方向是反的**，已删；别再照它填。
 *
 * ⚠️ 这张表是**账号/存档相关**的：换号或改名后要重填；留空即该条不参与匹配。
 * ⚠️ 命中顺序上**用户填的优先于词典与模糊匹配**（显式压过推断）。填错会盖掉真名 —— 例如把旅行者
 *    昵称填成「珐露珊」会让装备者栏里的珐露珊变成 Traveler。这是本设计的已知代价。
 */
data class NameOverrides(
    val traveler: String? = null,
    val wanderer: String? = null,
    val manekin: String? = null,
    val manekina: String? = null,
) {

    /**
     * 装备者（`location`）口径：武器/圣遗物面板底部「XX已装备」里的 XX。
     * 该路径**读不到元素**，而 GT 在这里写的就是不带后缀的 `Traveler`（本账号 92 件有主人的武器里
     * 唯一那条旅行者正是 `Traveler`，而角色表里同一人是 `TravelerCryo`）⇒ 两处口径不同不是笔误。
     */
    fun ownerKeyOf(display: String): String? = when {
        display.isEmpty() -> null
        display == traveler -> TRAVELER_KEY
        display == wanderer -> "Wanderer"
        display == manekin -> "Manekin"
        display == manekina -> "Manekina"
        else -> null
    }

    /**
     * 角色表口径。旅行者这一格给的是**不带元素的裸键** [TRAVELER_KEY]，
     * 由调用方（`ScanEngine.resolveKey`）按概览面板读到的「X元素」补成 GT 要的 `Traveler<元素>` ——
     * 玩家换一次元素就要重填一次昵称表是不可接受的。
     * ⚠️ 装备者口径（[ownerKeyOf]）那边**本来就要裸键**，两处的差异见 [ownerKeyOf] 的 KDoc。
     */
    fun characterKeyOf(display: String): String? = when {
        display.isEmpty() -> null
        display == traveler -> TRAVELER_KEY
        display == wanderer -> "Wanderer"
        display == manekin -> "Manekin"
        display == manekina -> "Manekina"
        else -> null
    }

    /** 按 [FIELDS] 的键取值（管理器页渲染用；键名与 [save] 同源，防止改一边忘一边）。 */
    fun valueOf(field: String): String? = when (field) {
        "traveler" -> traveler
        "wanderer" -> wanderer
        "manekin" -> manekin
        "manekina" -> manekina
        else -> null
    }

    /** 只改一个字段，其余原样 —— 管理器页逐格保存，不必凑齐四个。 */
    fun withField(field: String, value: String?): NameOverrides = when (field) {
        "traveler" -> copy(traveler = value)
        "wanderer" -> copy(wanderer = value)
        "manekin" -> copy(manekin = value)
        "manekina" -> copy(manekina = value)
        else -> this
    }

    companion object {
        const val TRAVELER_KEY = "Traveler"
        const val PREFS = "name_overrides"

        val EMPTY = NameOverrides()

        /** 设置项键 → 管理器页上的字段名。顺序即展示顺序。 */
        val FIELDS = listOf(
            "traveler" to "旅行者昵称",
            "wanderer" to "流浪者昵称",
            "manekin" to "奇偶·男性昵称",
            "manekina" to "奇偶·女性昵称",
        )

        fun load(context: Context): NameOverrides =
            load(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

        fun load(prefs: SharedPreferences): NameOverrides =
            NameOverrides(
                traveler = prefs.trimmed("traveler"),
                wanderer = prefs.trimmed("wanderer"),
                manekin = prefs.trimmed("manekin"),
                manekina = prefs.trimmed("manekina"),
            )

        fun save(context: Context, overrides: NameOverrides) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("traveler", overrides.traveler.orEmpty())
                .putString("wanderer", overrides.wanderer.orEmpty())
                .putString("manekin", overrides.manekin.orEmpty())
                .putString("manekina", overrides.manekina.orEmpty())
                .apply()
        }

        /** 纯空白按"没填"处理 —— 否则两个留空字段会互相命中（都等于 `""`）。 */
        private fun SharedPreferences.trimmed(key: String): String? =
            getString(key, null)?.trim()?.takeIf { it.isNotEmpty() }
    }
}
