# BetterGI Pocket

更好的原神（BetterGI）的 Android 口袋版。通过悬浮球在游戏上方提供屏幕识别与自动化动作：
**自动对话 / 自动拾取 / 圣遗物与武器全量扫描（GOOD v3 导出）**。

支持国服官服、Bilibili 服、国际服、云原神及部分渠道服。

## 功能

- **悬浮球**：以气泡形式显示在游戏之上，可拖动、展开面板；**零权限**（走无障碍悬浮窗，不需要「显示在上层」授权）
- **屏幕共享**：MediaProjection 常驻丢帧流（唯一帧源），用于模板识别与扫描
- **自动对话**：识别剧情对话后自动点击选项；可选「快速点击跳过」
- **自动拾取**：周期点击屏幕下方拾取
- **自动扫描**：圣遗物/武器背包全量扫描 → GOOD v3 JSON 导出（artifacts + weapons 双数组）
  - DSL 驱动：`app/src/main/assets/dsl/profiles.json`（坐标唯一事实源）+ `flows/*.json`（**17 do 原语解释器**）
  - 像素投票判据（锁/收藏/祝圣/稀有度/星带逐格/武器金条）+ ML Kit OCR 字段槽解析（小字自动放大 2x）
  - set_name 反推词典（276 件/56 套）+ 武器词典（236 件）+ 角色词典（121），**三级匹配：精确 → 包含 → 单字 Dice ≥0.55**
  - 3★/2★ 止扫标识（rarity=-1 哨兵保护）、指纹判到底、跨页去重、**详情切换等待**（防读旧件）
  - **anchor 数字兜底**（`prefixStrict` flow 可配：weapon_scan 严格防跨 tab 误配；默认宽松容忍 OCR 丢前缀）+ 失败 BACK 清弹窗后重进重试
  - maxPages 翻页早停（悬浮窗可设，0=不限；调试翻页准确性）
  - **分辨率专属 profile**：`dsl/profiles_<w>x<h>.json` 按帧尺寸自动路由（无则回退基准 3200x1440；宽高比失真 >2% 告警——16:9 设备坐标不可用已实测钉死）
- **脚本驱动悬浮窗**：**一脚本一行**（脚本图标 + 名称 + 行右侧图标动作）。动作由脚本自己声明（`ui.actions`）：
  扫描三条是「导出 ⤓ + 开始 ▶」，锁定/装配是「导入 ⧉ + 开始 ▶」；启用哪条就上哪条，关掉即从浮窗消失。
  扫描中当前行的 ▶ 就地翻转成 ■ 停止，其余动作禁点。脚本区标题右侧的「页数」徽标点开可改页数上限。
- **通用提醒**：全应用只有一条提醒通路 —— 管理器在前台走本页横幅，否则浮到游戏上方的**提醒条**
  （信息 / 告警 / 错误三档）。脚本也能用 `notify` 原语推重点信息。
- **脚本管理器（MainActivity）**：脚本列表（逐个开关键 + 动作摘要 + 长按恢复内置）、
  **GOOD 数据**（最近导出的结果可分享 + 执行输入可选择并显示"能驱动哪几条脚本"）、
  标定流程折叠区、**滑动测试**卡片；「导入脚本 JSON」用系统文件选择器（免存储权限）。
  首次启动自动出现，之后**长按悬浮窗的日志按钮**进入。
- **识别日志窗**：可在浮窗内开合（含按流程标签过滤），显示扫描/对话/加锁/装备/角色的实时识别行
- **零通知权限**：不声明/不请求 `POST_NOTIFICATIONS`——FGS 常驻通知（系统强制，MediaProjection 必须由前台服务持有）在 Android 13+ 默认不显示，服务与悬浮窗全功能不受影响
- **启动原神**：打开已安装的客户端，可选未检测到时自动启动

## 使用

1. 安装并打开应用 —— 首次启动进入**三步引导**：① 开启无障碍 ② 了解屏幕共享 ③ 记住入口
2. 在系统设置中开启无障碍服务「更好的原神」（模拟点击、判断原神是否在前台，**同时也是悬浮窗的宿主**）
3. 点开悬浮球，打开**共享屏幕** → 系统投影授权弹窗直接弹出（个别 ROM 拦截时自动拉 app 前台再弹，**不依赖通知**）
4. 在悬浮窗里点某条脚本的 **▶**（如「圣遗物扫描」）即开跑；跑完点同行的 **⤓** 拉起系统分享
5. 要跑「锁定 / 自动装备」：先点同行的 **⧉** 选一份 GOOD 或配装计划文件，再点 ▶
6. 改脚本 / 换输入：**长按悬浮窗的日志按钮**进入管理器
5. 改脚本/调开关：长按浮窗**「设置」**回到管理器

> 无需「显示在上层」授权：悬浮窗由无障碍服务以 `TYPE_ACCESSIBILITY_OVERLAY` 承载，因此**只开无障碍**即可。

## 架构

```
TriggerForegroundService（生命周期 + settingsListener + 前台通知）
├─ ScreenCaptureController ──→ ProjectionFrameSource（FrameSource 唯一实现）
│    · grabFresh(afterTs)：动作后取新帧（markActionAt 快照动作前末帧 ns 时间戳）
├─ TriggerEngine（100ms 节拍，实时触发：AutoPick/AutoSkip，依赖 FrameSource 零行为变化）
├─ ScriptRunner（独立协程单步循环，不进节拍；startScan(flowName, maxPages)）
│    └─ ScanEngine（DSL 解释器，17 do 原语）
│        顶层: enterScreen/dualStateButton/readCount/pagedGrid/dialog/
│              ocrWithRetry/navigate/foreach/setFilter/exit/verify/emit
│        visit 内: vote/click(两级 fallback)/parsePanel/stopWhen/ifMatch
│        ├─ ScreenProfile（多分辨率路由 loadFor + aspectDistortion 检测）
│        ├─ VoteJudges（像素投票：gold/pink/purple/starStrip + 网格指纹 settle）
│        ├─ StatParser（zh 词条→GOOD key，GOODScanner 键风格）
│        ├─ ArtifactSetDictionary / WeaponDictionary / CharacterDictionary（三级匹配）
│        └─ GoodExporter（GOOD v3 artifacts+weapons → filesDir）
├─ AccessibilityAutomationController（动作域：Click/Back/LongPress/Swipe 三段无惯性）
│    └─ OverlayBridge（悬浮窗门面：show/hide/进度/点击穿透 —— 转成桥调用发给 :a11y）
├─ DebugControlReceiver（adb 调试链：SET_SCREEN_SHARE/SET_SCAN/SET_PROBE/SWIPE_TEST/SCAN_FLOW/STATUS）
└─ MainActivity（引导页 / 脚本管理器：脚本开关 + GOOD 数据 + 滑动测试）
     └─ NoticeCenter + NoticeRouter（全应用唯一提醒通路；展位二选一：本页横幅 or 悬浮窗提醒条）

:a11y 进程（无障碍服务所在，悬浮窗宿主）
└─ InputAccessibilityService
     ├─ 输入注入（dispatchGesture：点击/三段无惯性滑动）
     └─ A11yOverlayRuntime
          └─ OverlayWindowController（悬浮球/面板/日志窗/提醒条/脚本行，TYPE_ACCESSIBILITY_OVERLAY 零权限）
               └─ BridgeSettingsRepository（设置读写的跨进程代理）

跨进程通道（同 UID 两个 Provider）
  app → :a11y   AccessibilityBridgeProvider（.a11y）   ：点击/滑动/悬浮窗指令
  :a11y → app   SettingsBridgeProvider（.settings）    ：设置读写/开始导出/退出/识别日志
  识别日志单一日志源在主进程；:a11y 侧日志窗按 700ms 拉镜像渲染
```

- 端到端脚本：`scripts/e2e_scan.sh`（connect/install/grant/bubble/projection/game/scan/swipe/probe/wait/report 分阶段可单跑）
- 2560×1440 profile 生成器：`scripts/gen_profile_2560.py`（等比占位；16:9 与 20:9 布局不同，可用坐标需实机标定）
- 设计文档：`../design-docs/bettergi-pocket-scan-integration-plan.md`（含 §7.1 P1 实施记录与偏差口径）
- DSL 终稿：`../dsl/`（README 发现列表 #13-#15）

## 构建

工具链由 **mise** 管理（`mise.toml`：java 21.0.2，daemon JVM criteria=21；Android SDK 走 mise `android-sdk`）。

```bash
# 常规构建（mise exec 注入 JAVA_HOME）
mise exec -- ./gradlew :app:testDebugUnitTest :app:assembleDebug

# 沙箱/CI 下 mise PATH 注入可能被宿主覆盖时，显式：
JAVA_HOME=$(mise where java) ./gradlew -p <abs-path> :app:testDebugUnitTest :app:assembleDebug
```

- Maven 仓库：阿里云镜像前置（`settings.gradle.kts`，dl.google.com 国内直连不稳）
- Gradle 分发：腾讯云镜像（`gradle/wrapper/gradle-wrapper.properties`）
- `local.properties`：`sdk.dir` 指向 mise android-sdk（gitignore，不入库）

### 守门测试（108 例，挂 testDebugUnitTest）

| 测试类 | 例数 | 防护 |
|---|---|---|
| DslAssetsIntegrityTest | 4 | dsl JSON 静默丢失/损坏（清单含 mappings.json——曾拷错目录不进 APK） |
| ProfileFlowConsistencyTest | 6 | 全 flow `$` 引用路径/形态/别名错配 |
| ParserAndExprTest | 12 | 词条解析 + stopWhen 表达式 |
| ScanEngineDryRunTest | 9 | 数字孪生：合成帧跑通真实 flow（止扫/多页/去重/坐标） |
| VoteJudgesRealDataTest | 6 | 判据阈值 vs dsl/verify 实测定稿对账 |
| GoodExporterTest | 1 | GOOD v3 结构 |
| ProfileScalingTest | 4 | 多分辨率坐标钉值（16:9 失真 25% 实测：点错行） |
| DictionaryFuzzyTest | 4 | 词典三级匹配（错字命中/乱码不命中/误配防护） |
| DoCoverageTest | 1 | 全 flow do 原语 100% 有实现分支（防静默 skip） |
| 其余（app 级单元） | 61 | 服务/仓库/网关等 |

**交付 APK 前必须 `unzip -l <apk> | grep assets/dsl` 复验关键资产在包内**（当前应 9 份；git clean ≠ 磁盘文件存在）。

## dsl 资产同步

**真目录是 `app/src/main/assets/dsl/`**（拷贝自 `../dsl/json` + `../dsl/tools` canonical 源）。
⚠️ `app/assets/`（无 src/main 前缀）不是 assets 目录——文件放那里不进 APK（fix45 教训：mappings.json 曾因此全部 unavailable）。
dsl 变更后须手动重拷并跑守门测试；同步机制 P4 订阅管理解决。

**名称词典只有一份**：`tools/good_names.json`（角色/武器/套装/圣遗物单件/词条/部位），
由 `../dsl/scripts/gen_good_names.py` 生成并同步。`../dsl/tools/mappings.json` 与
`artifactSetPieces.json` 只是 dsl 侧的**生成源**，不再拷进 app（守门测试钉死：出现即红）。

## 路线

- ~~P0 原语层~~ / ~~P1 扫描核心~~ / ~~P3 脚本化（17 do 原语全实现 + DoCoverageTest 守门）~~
- **真机验收**（进行中）：emit 率/settle/坐标精度标定 + GOOD vs irminsul 对账 + 滑动参数回填
- P2 悬浮窗迁移（TYPE_ACCESSIBILITY_OVERLAY 零权限；桥模式已验证，探针结论待真机 z 序确认）
- P4 订阅管理（脚本仓库 / 一键更新 / plan 规则注入 → ifMatch/setFilter 激活）
