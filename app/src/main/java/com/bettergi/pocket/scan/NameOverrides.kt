package com.bettergi.pocket.scan

import android.content.Context
import android.content.SharedPreferences

/**
 * 玩家自定义显示名 → GOOD key（#105，与 GOODScanner `good_config.json` 的 `NameOverrides` 同构）。
 *
 * **为什么交给用户填，而不是再猜一张内置表**：这几个角色的显示名由玩家自定义，词典以官方中文名为键，
 * 结构性不可能命中；而账号里已有的证据自相矛盾 —— 内置别名表写 `随机姓→Manekin`，GT 的武器配对
 * 却说 `随机姓名→Manekina`，离线分不清是"玩家改过名"还是"读错/记反了"。上游对同一个问题的答案
 * 就是不猜：`NameOverrides{traveler_name, wanderer_name, manekin_name, manekina_name}` 由用户手填。
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
     * 角色表口径。**故意不含旅行者**：那一格由 [ScanEngine] 的元素规则负责 —— 它手里有「X元素」，
     * 能给出 GT 要求的 `Traveler<元素>`；这里只会给不带元素的裸键，抢过来反而把对的改成错的。
     */
    fun characterKeyOf(display: String): String? = when {
        display.isEmpty() -> null
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
