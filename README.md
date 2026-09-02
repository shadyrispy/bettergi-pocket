# BetterGI Pocket

更好的原神（BetterGI）的 Android 口袋版。通过悬浮球在游戏上方提供屏幕识别与自动化动作：
**自动对话 / 自动拾取 / 圣遗物全量扫描（GOOD v3 导出）**。

支持国服官服、Bilibili 服、国际服、云原神及部分渠道服。

## 功能

- **悬浮球**：授权后以气泡形式显示在其他应用之上，可拖动、展开面板
- **屏幕共享**：MediaProjection 常驻丢帧流（唯一帧源），用于模板识别与扫描（Android 14+ 可选择单个应用）
- **自动对话**：识别剧情对话后自动点击选项；可选「快速点击跳过」
- **自动拾取**：周期点击屏幕下方拾取
- **自动扫描**：圣遗物背包全量扫描 → GOOD v3 JSON 导出
  - DSL 驱动：`assets/dsl/profiles.json`（坐标唯一事实源）+ `flows/artifact_scan.json`（8 业务原语解释器）
  - 像素投票判据（锁/收藏/祝圣/稀有度/星带逐格）+ ML Kit OCR 字段槽解析
  - set_name 反推词典（276 件/56 套，背包面板 set_name 被遮挡不采集）
  - 3★/2★ 止扫标识、指纹判到底、跨页去重
- **启动原神**：打开已安装的客户端，可选未检测到游戏时自动启动

## 使用

1. 安装并打开应用，按提示开启**悬浮窗**权限
2. 先在应用设置中找到 更好的原神 进行单次授权
3. 在系统设置中开启无障碍服务「更好的原神」（用于模拟点击、判断原神是否在前台）
4. 点开悬浮球，打开**共享屏幕**，在系统弹窗中选择原神画面
5. 按需打开**自动对话 / 自动扫描**等开关

自动扫描硬前提：**投影共享 + 无障碍**（开启扫描开关时会自动引导两者）。
扫描产出：`/data/data/com.bettergi.pocket/files/good_export_<ts>.json`（`adb shell run-as com.bettergi.pocket` 拉取）。

## 架构（P0+P1 落地态）

```
TriggerForegroundService（生命周期）
├─ ScreenCaptureController ──→ ProjectionFrameSource（FrameSource 唯一实现）
│    · grabFresh(afterTs)：动作后取新帧（markActionAt 快照动作前末帧 ns 时间戳）
├─ TriggerEngine（100ms 节拍，实时触发：AutoPick/AutoSkip，依赖 FrameSource 零行为变化）
├─ ScriptRunner（独立协程单步循环，不进节拍）
│    └─ ScanEngine（DSL 解释器：enterScreen/dualStateButton/readCount/pagedGrid/
│        vote/parsePanel/emit/stopWhen；坐标全部来自 profiles）
│        ├─ VoteJudges（像素投票：gold/pink/紫/稀有度色分类 + 网格指纹）
│        ├─ StatParser（zh 词条→GOOD key，GOODScanner 键风格）
│        ├─ ArtifactSetDictionary（set_name 反推）
│        └─ GoodExporter（GOOD v3 → filesDir）
├─ AccessibilityAutomationController（动作域：Click/LongPress/Swipe 三段无惯性）
│    └─ InputAccessibilityService（本地/远程 ContentProvider 双路径 + METHOD_SWIPE 桥）
└─ OverlayWindowController（悬浮球/面板/进度）
```

设计文档：`../design-docs/bettergi-pocket-scan-integration-plan.md`（含 §7.1 P1 实施记录与偏差口径）。
DSL 终稿：`../dsl/`（README 发现列表 #13-#15）。

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

### 守门测试（36 例，挂 testDebugUnitTest）

| 测试类 | 例数 | 防护 |
|---|---|---|
| DslAssetsIntegrityTest | 4 | dsl JSON 静默丢失/损坏 |
| ProfileFlowConsistencyTest | 6 | 全 flow `$` 引用路径/形态/别名错配 |
| ParserAndExprTest | 12 | 词条解析 + stopWhen 表达式 |
| ScanEngineDryRunTest | 7 | 数字孪生：合成帧跑通真实 flow（止扫/多页/去重/坐标） |
| VoteJudgesRealDataTest | 6 | 判据阈值 vs dsl/verify 实测定稿对账 |
| GoodExporterTest | 1 | GOOD v3 结构 |

**交付 APK 前必须 `unzip -l <apk> | grep assets/dsl` 复验关键资产在包内**（git clean ≠ 磁盘文件存在）。

## dsl 资产同步

`app/assets/dsl/` 拷贝自 `../dsl/json` + `../dsl/tools`（canonical 源）。**dsl 变更后须手动重拷并跑守门测试**；同步机制 P4 订阅管理解决。

## 路线

- ~~P0 原语层~~ / ~~P1 扫描核心~~（已完成，待真机验收）
- P2 悬浮窗迁移（TYPE_ACCESSIBILITY_OVERLAY，零权限；AppOverlayHost 保留回退）
- P3 脚本化补全（18 do 原语 + fast path 校准 + checkpoint 续扫）
- P4 订阅管理（脚本仓库 / 一键更新）
