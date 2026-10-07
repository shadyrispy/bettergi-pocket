# simple 分支：DSL 运行时下线 + 五流程原生化 转换规格（2026-10-07）

- 分支：`simple`（自 `f922f05` + 本文档提交）。方案：①A 彻底删 DSL 运行时，五流程转原生；
  ②P0/P1 对齐项随转换一并实现；③DryRun 17 场景全保留（改造为原生驱动）。
- 本文是**唯一实施依据**。上一步勘察已确认：五流程逻辑早已是原生 Kotlin（Scan*Domain 六域文件），
  DSL 只剩「步序编排 + 参数解包」——本转换就是把这一层从 JSON 换成 Kotlin 字面量。

## 1. 目标架构

```
simple 分支新增：
  scan/flows/NativeFlows.kt          —— 五条流程的入口函数（runWeaponScan/runArtifactScan/…）
  scan/flows/WeaponFlow.kt 等        —— 每条一个文件：步序 + 常量（原 JSON params → Kotlin 字面量）
  scan/flows/FlowDicts.kt            —— 原 vars/dict 块（subStats 档位表等数据常量）

删除：
  scan/ScanEngine.executeStep/executeStepInner（顶层 JSON 分发）
  scan/ScanControlDomain 的 JSON 步分发骨架（do 原语解包保留为直接调用）
  dsl/FlowValidator.kt、dsl/ScriptStore.kt 的导入持久化、dsl/ScriptIcons.kt（并入 OverlayScriptRows）
  assets/dsl/flows/*.json（5 条业务流程；profiles/tools 保留——是标定数据不是 DSL）
  scan/GoodRepository 的「导入输入」UI 通道保留（GOOD 输入不是脚本）

保留（不是 DSL）：
  profiles*.json（标定坐标）、tools/*.json（rollTable/词典）、GoodRepository、
  Scan*Domain 六域文件的全部域函数、PerfProbe、流水线（S1/S2a/S2b）。
```

## 2. pagedGrid 原生签名（唯一签名级改造）

```kotlin
// 现：pagedGrid(step: JSONObject) —— grid/visit/pageSkip 全从 JSON 读
// 改：
internal suspend fun ScanEngine.pagedGridNative(
    gridKey: String,
    maxPages: Int,
    pageSkip: PageSkip?,                    // data class PageSkip(exprKind: ExprKind, targets:…)
    visitBody: suspend (col: Int, row: Int, idx: Int, ctx: CellFrameContext, prof: ScreenProfile) -> Unit,
)
```
- 函数体 = 现 pagedGrid 主体**原样**（页循环/翻页/相位/回读/行级闭环/锚定/页级冻结——一个字不改），
  只有两处换：① visit 解释循环 → `visitBody(col, row, idx, ctx, prof)` 逐格调用的**生产者侧壳**
  （visitSkipTail 判重逻辑留在壳里，跳过时不调 visitBody）；② visit 数组遍历删除。
- visitSkipTail/生者跳过语义：原 runVisit 的 TAIL_RUN_ALWAYS（stopWhen/emit 在判重时仍跑）
  由 visitBody 内部自行保证（见 §3 weapon 示例的顺序）。
- snap 模式（auto_equip/character 用 snapTraverse）同理加 lambda 变体。

## 3. 五流程步序 → 原生映射（按 gs-flow-step-diff §1-§6 的对照逐条）

### 3.1 weapon_scan（参考转换，先行）
```
runWeaponScan(maxPages) {
    enterScreenChain1("weapon_backpack", chain=[game_home→bagpack(2786,36)], assertTitle="背包")
    enterScreenChain2("weapon_backpack", chain=[weapon_tab(210,196,290,321)], assertTitle="武器")
    clicks(listOf("weaponSort","weaponSort"))          // 排序两连（现 clicks 步）
    readCount(rect=$screens.weapon_backpack.count, as="total", onZero=ReopenAndRetry)
    pagedGridNative("weapon_backpack", maxPages, pageSkip=null) {
        stopWhenRegisterDuplicateStreak(streak=3, confirm=1)   // 登记留在生产者（§12.1）
        stopWhenExprRarityLE2Flush()                            // rarity<=2 → Stop+flush
        voteWeaponCardLockBadge(col, row)                       // zone weapon.card.lockBadge
        clickCell(col, row, fallback=ocrAnchor("panel.weapon_backpack.name"))
        voteWeaponPanelStarBand()                               // zone weapon.panel.starBand
        parseWeaponPanelNative(dict=WEAPON_DICT)                // 含 S1 origin 裁片
        emitGoodWeapon()
    }
    exitWeapon()
}
```
- `stopWhenExprRarityLE2Flush`：原 Expr 表达式 `rarity <= 2` 直接写成 Kotlin 比较（Expr 引擎对该条退役）。
- dict=WEAPON_DICT：原 flow `dict` 块转 Kotlin 常量（放 FlowDicts.kt）。

### 3.2 artifact_scan
步序同构（enterScreen×2 → clicks → readCount → pagedGrid[stopWhen×2 + vote(锁/星/祝圣) + parse + emit] → exit）。
**随转换实现**：subStats ROI 已收窄（2660）+ 颜色判据已在 parse 里 ✓；页级 pageSkip（若该 flow 声明）同 §2 签名。

### 3.3 character_scan
- visit=[12 步]：rosterFind/click/navigate/dedupeSkip/parseCharacterPanel/emit(as=GoodCharacter)。
- **随转换实现**：`dedupeSkip` 语义内联进 visitBody（CharDupJudge 判 key，key==null 不跳过）。
- §0 描述背离：Phase 2 不存在 ⇒ 原生版注释按实际实现写。

### 3.4 artifact_lock
- visit=[6 步]：ifMatch(when)/planMatch/click(fast.at=$zones.artifact.panel.lock)/verify/…。
- plan 注入（GoodRepository 输入）不变；`ifMatch.when` 表达式改 Kotlin 直判（Expr 对该条退役）。

### 3.5 auto_equip
- top=2、visit=[]：foreach(plan)/parsePanel/manage 操作为主；plan 注入同 3.4。

### 3.6 P0/P1 对齐项（gs-flow-step-diff §0.1，随转换一并实现）
P0 缺席证明门禁（emit 处 NotFound/Skipped 分流）；P0 副词条重试链（solver 不可解→9 组重裁→启发式兜底）；
P0 筛选三态（部分命中⇒清筛选改扫全量）。P1 锁图标歧义带 / 套装分组 / 页签交替 / skip_open /
名称匹配护栏——**列为 follow-up**（转换期不加，防归因混淆），在 FlowDicts.kt 头注释登记待办。

## 4. DSL 运行时删除清单（五流程全绿后执行）
- `ScanEngine.executeStep/executeStepInner` + ScanControlDomain 的 vop when 骨架
  （do 原语的**实现函数保留**——原生流程直接调用）。
- `dsl/FlowValidator.kt`、`dsl/ScriptStore.kt` 导入持久化（内置五条的开关状态改为
  SharedPreferences 一个 bitset）、`dsl/ScriptIcons.kt` 并入 OverlayScriptRows。
- `assets/dsl/flows/*.json`（5 条）；`char_calibrate/setfilter_calibrate` 两条**标定流**
  保留为 JSON（标定工具链用，不进 simple 的运行时判断——如运行时仍加载则一并原生化的决策点）。
- 测试删除：FlowValidatorTest/DoCoverageTest/DslAssetsIntegrityTest(改 profiles 断言)/
  ProfileFlowConsistencyTest/PlanContractTest/ScriptStoreTest。
- MainActivity 导入脚本 UI + FileProvider 分享链的脚本部分。

## 5. DryRun 原生改造（回归网主力，17 场景全保留）
- 现驱动：合成帧 + `engine.run()`（JSON flow）。
- 改：合成帧 + `runWeaponScanNative(maxPages=…)` 等直调——**场景语义逐条保留**
  （止扫/多页/去重/坐标/前台闸门/素材标识/revisit）。
- 验证：改造前后同合成帧跑，GOOD 导出逐字段对账（已有 GT 对账口径）。

## 6. 执行顺序与闸门
1. pagedGridNative 签名改造（visitBody lambda）+ weapon 原生化 → DryRun weapon 场景绿。
2. artifact/character/lock/equip 逐条 → DryRun 对应场景绿。
3. 删 DSL 运行时与测试 → 默认闸门全绿（预计 77→约 60 类）。
4. BS：pipe=0 全流程对账（与 10-03 基线：63a/63w/14c）→ pipe=1 A/B（正反两序 + CpuTelemetry）。
5. 真机（小米/华为任一）对账 → main 合并。

## 7. pagedGridCore 抽取与 weapon visitBody（2026-10-07 勘察补遗，实施按此）

**pagedGrid 签名改造（两步委托，主体零改动）**：
- 现 `pagedGrid(step: JSONObject)` 拆成 `pagedGridCore(gridKey, pageSkipExpr, visitJson?, visitBody?)`
  —— 主体（页循环/翻页/相位/回读/行级闭环/锚定/页级冻结）原样；visit 的两处消费
  （主循环 :467 + 空读回读 :731）改走 `runVisitJsonOrBody`：visitJson≠null 走 runVisit(JSON)，
  否则 `runVisitBody(col,row,idx)` 调 visitBody。
- `pagedGrid(step)` 保留为 JSON 委托（迁移期其它流程用）；`pagedGridNative(gridKey, pageSkip, visitBody)`
  为原生入口。

**weapon visitBody（原生体，stopWhen 两个语义入口需从 stopWhen(step) 抽出）**：
- `stopWhenRegisterDuplicateStreak(streak, dupPageConfirm)` —— duplicateStreak 分支直提；
- `stopWhenRarityAtMost2()` —— expr "rarity<=2" 的原生式：哨兵守卫（rarity<0 跳过）+
  `vars.rarity in 0..2` ⇒ stopRequested=true/stopReason="stopWhen"（scope 默认 page 立即停；
  "Stop+flush" 的 action 字段在引擎里**无消费者**（flush 就是扫描结束的自然导出））。
- vote(locked)/vote(rarity)/parsePanel/emit 四步的原生调用式见 §3.1；其中 vote 全部留在
  生产者（读闸门整帧，无 origin 需求——S2a 并集是为未来搬迁预铺）。
