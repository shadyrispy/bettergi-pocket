package com.bettergi.pocket.scan

/**
 * GOOD v3 数据模型（圣遗物/武器/角色共用）。
 *
 * 纯数据载体：[GoodSubStat] / [GoodArtifact] / [GoodWeapon] / [GoodCharacter]，
 * 由各解析面板填充、[GoodExporter] 消费导出。GOOD 导出的 JSON 构建逻辑不在此文件。
 */

/** GOOD v3 圣遗物条目（键风格与 GOODScanner 导出一致）。 */
/**
 * GOOD 副词条。`initialValue` / `rollCount` 由 [RollSolver] 解出（2026-09-16 补齐）：
 * `initialValue` = 首档显示值（歧义为 null ⇒ 不写进导出），`rollCount` = 强化次数（0 = 未解出）。
 */
data class GoodSubStat(
    val key: String,
    val value: Double,
    val initialValue: Double? = null,
    val rollCount: Int = 0,
)

data class GoodArtifact(
    val setKey: String?,
    val slotKey: String?,
    val level: Int,
    val rarity: Int,
    val mainStatKey: String?,
    val mainStatValue: Double,
    val substats: List<GoodSubStat>,
    val lock: Boolean,
    val favorited: Boolean?,
    val location: String = "",
    /** OCR 原文单件名（词典 276 件全局唯一）——入库去重键，不参与 GOOD 导出。 */
    val pieceName: String = "",
    /** 初始词条数 + 强化次数（[RollSolver] 解出；null = 不可解 ⇒ 不写进导出）。 */
    val totalRolls: Int? = null,
    /**
     * 祝圣之霜打造（面板有紫横幅 + 内容整体下移 [ScreenProfile.zhushengShiftPx]）
     * ⇒ 导出 GOOD v3 `elixerCrafted`。
     * ⚠️ 另有 `astralMark`（GT 941 里 19 件，与 elixer 仅 1 件重叠）是**另一个**属性，
     * 我方暂无判据 ⇒ **不臆造**（宁可少写不可写错）。
     */
    val elixerCrafted: Boolean = false,
    /** 带「(待激活)」标记的副词条（仅有 lv0 件；GT/Irminsul **每件都写**该字段，无则空数组）。 */
    val unactivatedSubstats: List<GoodSubStat> = emptyList(),
)

/** GOOD v3 武器（结构比圣遗物简单：name+key/refine/level/rarity/lock） */
data class GoodWeapon(
    val key: String?,     // mappings.weapons id
    val level: Int,
    val rarity: Int,      // 1-5
    val refine: Int?,     // 精炼 1-5
    val lock: Boolean,
    /**
     * 装备者（GOOD `location`）。空串 = 未装备。
     * 面板文案形如「珐露珊已装备」（`panels.weapon_backpack.equipped` 槽，2026-09-17 真机核对套准 ✓）。
     */
    val location: String = "",
    /**
     * 突破阶 0-6（GOOD `ascension`）。
     * ⚠️ **面板不显示突破阶** ⇒ 由等级推导（对齐 GT 口径，2026-09-17 用 Irminsul weapons 209 件反推验证）：
     * `≤20→0 / ≤40→1 / ≤50→2 / ≤60→3 / ≤70→4 / ≤80→5 / else→6` ⇒ 命中 208/209
     * （唯一例外：level=20 时 GT 有 asc=0 与 1 两种，面板无法区分）。
     */
    val ascension: Int = 0,
)

/**
 * 流程三（角色扫描）产物。字段取自 `panels.char_profile` / `char_constellation` / `char_talent`。
 * - [constellation] = 已点亮命座数（0-6，白锁判据）；
 * - [talents] = 3 个战斗天赋等级（[0]=普攻 auto、[1]=元素战技 skill、[2]=元素爆发 burst）；
 * - [talentLocked] = 对应天赋是否处于「灰锁」未解锁态（图标框低饱和亮块判据）。
 */
data class GoodCharacter(
    val key: String?,
    val name: String,
    val level: Int,
    val element: String?,
    val favor: Int = 0,
    val constellation: Int = 0,
    /** 突破阶 0-6（GOOD `ascension`）：由**等级上限**（面板 "Lv.X/Y" 的 Y）推导，同武器做法。 */
    val ascension: Int = 0,
    val talents: List<Int> = emptyList(),
    val talentLocked: List<Boolean> = emptyList(),
    /** OCR 原文（未匹配词典时保留，便于人工核对）。 */
    val rawName: String = "",
)

// ---------------------------------------------------------------------------
// 名称词典（角色/武器/套装/圣遗物单件/词条/部位）已统一收敛到：
//   recognition/name/GoodNames.kt  —— 单一文件 dsl/tools/mappings.json 装载
//   recognition/name/NameMatcher.kt —— 唯一一处模糊匹配算法
// 原 ArtifactSetDictionary / WeaponDictionary / CharacterDictionary 三套各写一份
// 模糊逻辑（且 contains 走 HashMap.firstOrNull，结果依赖遍历顺序）已全部删除：
// characters 表实测准确率 77.8% → 91.2%，全表合计 90.5% → 93.7%（见 NameMatcherTest）。
// ---------------------------------------------------------------------------
