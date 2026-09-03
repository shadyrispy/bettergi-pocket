# BetterGI Pocket

更好的原神（BetterGI）的 Android 口袋版。通过悬浮球在游戏上方提供屏幕识别与自动化动作：
**自动对话 / 自动拾取 / 圣遗物与武器全量扫描（GOOD v3 导出）**。

支持国服官服、Bilibili 服、国际服、云原神及部分渠道服。

## 功能

- **悬浮球**：授权后以气泡形式显示在其他应用之上，可拖动、展开面板（含滑动测试调参、A11y 探针）
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
- **悬浮窗扫描控制区**：「自动扫描」行展开——流程切换（圣遗物/武器）、页数输入、▶开始 / ■停止扫描、分享 GOOD（全部零通知依赖；重要提示走 Toast/悬浮窗文字）
- **零通知权限**：不声明/不请求 `POST_NOTIFICATIONS`——FGS 常驻通知（系统强制，MediaProjection 必须由前台服务持有）在 Android 13+ 默认不显示，服务与悬浮窗全功能不受影响
- **启动原神**：打开已安装的客户端，可选未检测到时自动启动

## 使用

1. 安装并打开应用，按提示开启**悬浮窗**权限
2. 先在应用设置中找到 更好的原神 进行单次授权
3. 在系统设置中开启无障碍服务「更好的原神」（用于模拟点击、判断原神是否在前台）
4. 点开悬浮球，打开**共享屏幕** → 系统投影授权弹窗直接弹出（SAW 豁免直启；个别 ROM 拦截时自动拉 app 前台再弹，**不依赖通知**）
5. 展开悬浮窗「自动扫描」→ 选流程/页数 → 点「▶ 开始扫描」；完成后点「分享 GOOD」拉起系统分享（飞书/微信，免 adb）

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
│    └─ InputAccessibilityService（本地/远程 ContentProvider 双路径 + 桥：CLICK/SWIPE/BACK/PROBE/SCAN_PROGRESS）
├─ DebugControlReceiver（adb 调试链：SET_SCREEN_SHARE/SET_SCAN/SET_PROBE/SWIPE_TEST/SCAN_FLOW/STATUS）
└─ OverlayWindowController（悬浮球/面板/进度；滑动测试内嵌面板，执行时自动缩球）
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

## 路线

- ~~P0 原语层~~ / ~~P1 扫描核心~~ / ~~P3 脚本化（17 do 原语全实现 + DoCoverageTest 守门）~~
- **真机验收**（进行中）：emit 率/settle/坐标精度标定 + GOOD vs irminsul 对账 + 滑动参数回填
- P2 悬浮窗迁移（TYPE_ACCESSIBILITY_OVERLAY 零权限；桥模式已验证，探针结论待真机 z 序确认）
- P4 订阅管理（脚本仓库 / 一键更新 / plan 规则注入 → ifMatch/setFilter 激活）
