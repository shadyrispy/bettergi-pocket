# BetterGI Pocket 优化方案（缺陷修复 × 架构重构合并版）

- 日期：2026-09-30
- 来源：同日三轮审查——①缺陷审查（1×P0、11×P1、约 25×P2、约 30×P3）；②架构体检（巨石文件、归集错位、包级依赖环）；③复审（约 40 处 file:line 全量机器核对 + 基线实测：修正基线数字、补 #147/#155/#156、A2/A31 加真机验证前置）
- 代码基线：`f967600`（天赋/昵称四文件已于 09-30 提交：`2c5b273` + `f967600`，行为有变化——characterKeyOf 含旅行者、天赋多数票）；本文档自身尚未入库——见 Stage 0
- 本文是唯一执行依据；执行中发现的新问题追加到 §7 映射表，不口头扩散

---

## 0. 全局原则与关键裁决（防前后矛盾、防顾此失彼）

**三条铁律**

1. **先修行为、再动结构**。Stage 1/2 只改行为（bug fix），不做任何文件移动/改名；Stage 3 只做结构（移动/拆分），不改行为。同一文件不被两个阶段"顺手"触碰。
2. **行为修复先补回归测试再改实现**（红→绿）。凡 ScanEngine / dsl flows 相关的修复，必须同步补 `ScanEngineDryRunTest` 用例，并以 `-Pdryrun` 全量（约 189s）作为合入闸门。
3. **机械移动单独成 commit**（移动与行为修改永不混提交），保证 review 可读、bisect 可用。

**七条裁决**（两轮发现之间的冲突点，按此执行，不再临场争论）

| # | 冲突 | 裁决 |
|---|---|---|
| 1 | ScanEngine 的 P1 缺陷（A9/A10/A11）落在 `pagedGrid` 一带，而 B1③ 要把 pagedGrid 重构成 PageSession | 缺陷在 Stage 1 修完并带回归测试入库；Stage 3 拆分时**行为已冻结**，PageSession 只吸收已修好的逻辑，拆分后跑 `-Pdryrun` 证明行为不变 |
| 2 | A3（windowContains 坐标 bug）与 B2（OverlayWindowController 1825 行拆分）同文件 | Stage 1 先修 A3（小 diff、真机可验），B2 在 Stage 3 拆分时保持已修行为 |
| 3 | OverlayWindowController 的 P3（hide() 残留引用、captureScope 不 cancel）与 B2 同文件 | 该两条 P3 **不进 Stage 2**，并入 B2 工单一并处理，避免二次触碰 |
| 4 | A7（OCR 降档期静默空结果）要改 OcrGateway 契约，而 B1① 要把 OcrGateway 接口搬出 ScanEngine.kt | 无硬依赖（Kotlin 不在乎接口在哪个文件）。A7 在 Stage 1 原地改契约；接口搬家是 Stage 3 的纯移动 |
| 5 | A22（NameMatcher tie-break）在 recognition 包，B1② 只把 scan 侧调用代码搬进 ArtifactDomain | 互不重叠。Stage 3 搬家时**禁止顺手改匹配逻辑**；两件事分属不同阶段不同包 |
| 6 | Stage 2 的构建项 A33（R8 keep 规则）与 Stage 4 开启 minify | Stage 2 只写规则不开启；开启 minify 放 Stage 4，带 release 全回归 |
| 7 | 依赖环的消解顺序 | 先归位共享类型（3.1）再归拢 bridge（3.2），两步都是全仓库 import 触碰，**串行执行**；之后 3.3–3.6 才可并行 |

**并行总图**

```
Stage 0 基线（串行，半天）
   └─► Stage 1 五轨并行：A 安全清单 ∥ B 悬浮窗输入 ∥ C 采集链路 ∥ D 扫描引擎 ∥ E ONNX OCR
         └─► Stage 2 四轨并行：2A scan ∥ 2B recognition ∥ 2C 系统集成 ∥ 2D 构建
               └─► Stage 3：3.1 类型归位 → 3.2 bridge 归拢 →（3.3 ScanEngine ∥ 3.4 Overlay ∥ 3.5 pcdata ∥ 3.6 MainActivity）
                     └─► Stage 4 加固/性能/文档（内部可并行）
```

- 阶段间唯一**硬依赖**：Stage 3 必须等 Stage 1+2 的行为修复全部入库（重构以全绿测试为基线）。
- Stage 2 各轨与 Stage 1 不同轨无文件交集，人力富余时可穿插提前；但建议按阶段合入，保持"每个阶段末尾有一个全绿可发布点"。
- 规模标记：S ≤1 人日，M 2–4 人日，L ≥1 周。

---

## 1. Stage 0：基线固化（串行，S）

1. ~~处置 4 个未提交文件~~ **已完成**（09-30：`2c5b273` 天赋锚定解析+多数票、`f967600` 昵称表旅行者格）。新账：**本文档自身 untracked**，开 Stage 1 分支前先入库；工作区必须干净后才开 Stage 1 分支。
2. 跑一遍基线并记录数字：`./gradlew :app:testDebugUnitTest`（默认闸门，09-30 实测 **55 类 / 394 条 / 3.2s / 0 fail**，干跑类按设计排除）+ `-Pdryrun`（**56 类 / 407 条**，约 189s）。⚠️ 初版审查写的 52/381 与 53/394 是 grep 静态推导的错数；数字随提交漂移，执行时以当轮 XML 解析为准，别照抄本文。
3. 记录当前 APK 资产清单：`unzip -l <apk> | grep assets/dsl`（应 24 个文件），作为后续交付复验基准。

---

## 2. Stage 1：P0/P1 热修（五轨并行，≥1 周——瓶颈为轨 D）

> 每轨独立分支、互不触碰对方文件；合入顺序不限，全部合入后跑一次完整闸门（默认 + dryrun）。

### 轨 A：安全与清单（M）
| 工单 | 位置 | 修法 |
|---|---|---|
| A1(P0) | `AndroidManifest.xml:135-149` | `DebugControlReceiver` 移入 `src/debug/AndroidManifest.xml`（项目已有 debug 源集先例）；若 adb 以外仍需触发，加 signature 级自定义 permission |
| A2(P1) | `AndroidManifest.xml:70-76` | `CapturePermissionActivity` 改 `exported="false"`。⚠️ 初版断言「adb shell 有 START_ANY_ACTIVITY」**未经证实**（常见结果是 `SecurityException: not exported from uid 2000`），而 `recover_bs.sh:21` 正是 adb 直接 `am start` 此 Activity ⇒ **改前必须真机跑一遍 recover_bs.sh 全链**；断链则回退，改为仅内部拉起 + adb 走广播触发 |
| A31(P2提前) | `src/debug/AndroidManifest.xml:7-11` | ~~加 signature 级 permission 收窄~~ **默认不做**：shell 不持有 signature 级自定义 permission，加上了 `am start-foreground-service` 直接被拒 ⇒ DEBUG_*/e2e/svc_cmd 全链断（`recover_bs.sh:18` 依赖）；这个导出面就是我们赖以工作的调试面。仅当未来要外发 debug 包给他人时再收（届时连同口令/签名校验一起设计） |

回归：`DebugControlReceiver` 的 adb 触发链走一遍 `e2e_scan.sh`（svc_cmd 路径）；确认 release 变体无 receiver；A2 若实施，另跑 `recover_bs.sh` 全链（install + a11y 追加 + 投影授权 + 进游戏）。

### 轨 B：悬浮窗/输入快修（S–M，需真机验证）
| 工单 | 位置 | 修法 |
|---|---|---|
| A3(P1) | `overlay/OverlayWindowController.kt:752-766` | `windowContains` 按 gravity（END/START）换算窗口实际左上坐标再做命中判定；补单测（两种 gravity × 边界点） |
| A25(P2提前) | `input/AccessibilityAutomationController.kt:46-49` | 穿透还原延时对齐 ScriptRunner 的既定教训：`max(durationMs,120) + slack`（slack 取 ScriptRunner 同款 600ms 或实测值） |

回归：真机验证悬浮球区域模拟点击不再被自家 overlay 吞（ScriptRunner 注释里的故障场景）；单元层用参数化测试钉坐标换算。

### 轨 C：采集链路（M）
| 工单 | 位置 | 修法 |
|---|---|---|
| A4(P1) | `capture/ScreenCaptureController.kt:201-215,706-731` | 重构 stop 锁序：join 移到锁外或两阶段停机（先置退出标志→锁内 close→锁外 join），消除主线程 1s 卡顿 |
| A5(P1) | `capture/FrameSource.kt:83-102` | `grabFresh` 先比 `frameGeneration()` 再取帧转换，杜绝等待期全帧 RGBA→BGR 空转（~1.7GB/2.5s 垃圾） |
| A27(P2提前) | `ScreenCaptureController.kt:227-253` | `start()` 捕获 `getMediaProjection`/displaySpec 异常 → 走失败上报（提醒条 error 档），不再裸崩 |
| A28(P2提前) | 同文件 `:280-294` | `acquireLatestBgr` 的整帧转换移出临界区（锁内只做 image 引用快照，沿用 pollerGeneration 模式，注意 image 生命周期） |

回归：现有 capture 单测 + 投影开关压测（快速开/关 20 次无 ANR、无泄漏）。

### 轨 D：扫描引擎行为（L，本阶段最重）
| 工单 | 位置 | 修法与回归测试 |
|---|---|---|
| A9(P1) | `scan/ScanEngine.kt:1563,1934,2512` | ⚠️ 初版「命中页也推进页级下界」**不可行**：skip 页不点格 ⇒ 无该页最低等级的读数来源。修法改为：**连续 skip 页数上限**（K 页全跳 ⇒ 干净收尾）为主；`pagesByCount`（:2512，计数器可读时）+ 总滑动次数上限作双保险。回归：dry-run 用例——无计数器、连续 skip 到上限能终止 |
| A10(P1) | `:349-357` | 总数核对改为 `results + resultsWeapons + resultsCharacters` 合计（按 flow 域取对应容器）。回归：weapon_scan 干跑不再出现 total_mismatch |
| A11(P1) | `:6883-6925` | 指纹闸门等到新面板后把 `f2` 赋回 `ctx.frame` 再解析。回归：dry-run"面板冻结 2 格"用例不丢件 |
| A12(P1) | `scan/StatParser.kt:131-137` | 修复 `".7"`→7.0：删不可达死代码（第二个 regex 永远够不着——含 ".7" 的串必先被第一个 regex 的 "7" 截胡，已核实），第一个 regex 增加"点前非数字/字母"的分支按小数解析。⚠️ 天真的「小数点开头优先」会把 `Lv.90` 解析成 0.90（现有注释明确防这个）。回归：`ParserAndExprTest` 补 `.8`→0.8、`.7`→0.7、`Lv.90`→90、`5.8%` 缺位用例 |
| A13(P2提前) | `scan/ScriptRunner.kt:316-367` | 看门狗导出前对 results 做同步快照（锁内 copy）+ 整体 runCatching，杜绝抢救时刻 CME 杀进程 |
| A14(P2提前) | `:129-146` | `dispatchOnMain` 超时分支移除 pending 消息（handler.removeCallbacks）或打弃用标记，杜绝恢复后的游离点击 |
| A15(P2提前) | `:213-216,397-400` | stop→start 原子化（等取消完成或复用同一 job 语义）；已运行时 `startScan` 静默 return 补 `onFinished` 回执 |
| A38(#155) | `scan/ScanEngine.kt:3736,4286` | TALENT_BONUS 查表成片少减 +3（命座加成表覆盖面不足，非 OCR）——按 GT 对账差异集逐角色补表。回归：dry-run 用例钉高命座角色的天赋值 |
| A39(#156) | `scan/ArtifactDomain.kt:144,157` + `ScanEngine.kt:3916` | 未解析身份导出 `key=""` ⇒ 两条记录在下游按 key 并成一条。修法：导出侧 `key ?: ""` 改为 null 缺省（JSON 不写 key）或带 rawName 哨兵，禁止空串；resolveKey 未命中已有日志，补齐后照常。回归：dry-run 用例——未解析记录不与任何记录合并 |
| A40(#147) | `scan/ScanEngine.kt:8494,1583` | 判稳信号（CROSS_PAGE_SETTLE 定时 250ms）看不见列表残余慢漂 ⇒ 整页 OCR 出"自信乱码"。修法：**换判稳信号**（相邻帧底栏锚位移 < 阈值），不是再调 settle（曾实测调 settle 无效）。回归：慢漂注入式 dry-run 用例 |

闸门：本轨合入必须 `-Pdryrun` 全量绿；改动不新增/删除 do 原语 ⇒ 无需更新 `DoCoverageTest`。

### 轨 E：ONNX OCR 引擎（M）
| 工单 | 位置 | 修法 |
|---|---|---|
| A6(P1) | `recognition/ocr/onnx/OnnxOcrEngine.kt:87-121,256-267` | initialize 互斥（单一 init 锁）+ "先建新→原子换入→再关旧"，消灭 initialize↔initialize 竞态（native 泄漏/UAF） |
| A8(P1) | `:238-244` | 慢/坏分离：超 3s 只记指标不降档；降档仅由真失败触发；`set(0)` 改 per-tier 有界重置，防跨线程互踩 |
| A7(P1) | `recognition/ocr/onnx/OnnxPaddleOcrService.kt` + `scan/OcrGatewayImpl.kt` | 引擎不可用显式化：OcrGateway 增加 availability 查询或 unavailable 结果标记；重建窗口内不再返回空串冒充数据；扫描中段不可用 → 走引擎既有重试/loud 失败路径。**不做**静默 ML Kit 兜底（维持现语义，README 同步说明） |
| A19(P2提前) | `recognition/ocr/onnx/OnnxPaddleOcrService.kt:96-99` | 兜底初始化失败必须让 `prepare()` 返回 false |

回归：并发 initialize 压测单测；`OnnxOcrBenchmarkTest` 冒烟。

---

## 3. Stage 2：P2 批量（四轨并行，约 1–2 周）

> 按包分组，四轨零文件交集。此阶段仍禁止任何文件移动。

### 2A：scan 包（M）
- A16 `Expr.kt:106-115`：未知变量名抛 `EvalException`（调用方已有 runCatching 兜底），杜绝判据静默反向；`FlowValidatorTest`/`ParserAndExprTest` 补用例。
- A17 `ScanEngine.kt:4111,3978` 等：`catch (_: Exception)` 包 suspend 的位置全部改为透传 CancellationException（`runCatching` 换 `try/catch (e: CancellationException) throw e` 模式）。
- A18 `RollSolver.kt:96-99` + `ScanEngine.kt:7375`：level+10 解出后回写导出 level，消除字段自相矛盾。
- P3 批：GridRowCheck 平票取小并置 UNKNOWN（`GridRowCheck.kt:75-83`）；TemplateMatcher 缓存读入锁或换 ConcurrentHashMap（`:102-120`）；`D_SIGCONFIRM` 文档与常量对齐（`TimingOverrides.kt:44`）；dualStateButton 日志拼接括号（`ScanEngine.kt:1301`）；ScriptStore 合并排序加稳定平局键（`ScriptStore.kt:85`）；ScreenProfile 越界由静默钳位改 fail-loud（`ScreenProfile.kt:212,228`，同步更新受影响单测）；`rawObject!!` 改显式校验（`ScanEngine.kt:7182`）；`resetScanAccumulation` 同步清 `seenArtifactKeys`（`:5687`）——⚠️ **方向未证实**：foreach 多目标共用一套圣遗物时，不清可能是**有意**防跨目标重复入库（:7466 判重靠它），改前先写测试钉语义（清 ⇒ 同件重复入库 vs 不清 ⇒ 跨目标漏扫）；startScan 双启回执（已入 A15）；VoteJudges 改整行 JNI 读（`VoteJudges.kt:90-108`）。

### 2B：recognition 包（M）
- A20 `recognition/ocr/onnx/OnnxOcrEngine.kt:173-179`：benchmark 预热先 `drop(1)` 再排序。
- A21 `area/ImageRegion.kt:101,152,195,212,243`：ROI 统一先 clamp 后判空（对齐 `deriveCrop` 先例），越界 fail-loud；补 16:10 屏跑 16:9 坐标的越界用例。
- A22 `name/NameMatcher.kt:165-176,193-199,225-237`：三层兜底补确定性 tie-break——tier4 取最长键、tier5 并列比长度差/公共前缀、tier7 加唯一性校验（对齐 LCS 层先例）；`NameMatcherTest` 补"数据序无关"用例（同一输入打乱 JSON 顺序结果不变）。
- A23 `TemplateAssetLoader.kt:29-54`：legacy scale 仅对未命中精确分辨率目录的模板生效。
- A24 `recognition/ocr/onnx/OnnxModelAssets.kt:50-60`：manifest 解析失败 → 硬失败触发重拷，不再静默跳过校验；字典条目数不符升级为硬失败。
- P3 批：CtcDecoder 置信度改由 manifest 显式声明；MatOps 4 通道防御分支修正；`u8/setU8` 改块读写；ML Kit 超时 recycle 竞态；OcrParallelProbe `latch.await` 加超时。

### 2C：系统集成（M）
- A26 `A11yOverlayRuntime`/`OverlayBridge`：主线程同步 binder 限流——高频调用（progress/notice/log）改异步 fire-and-forget + 状态缓存，click/prepare 保留同步但缩短 latch 上限并对繁忙降级；消除扫描期 ANR 面。
- A29 `MainActivity.kt:148-207` + `file_paths.xml`：深链校验 `intent.data`（host/path 白名单），extras 只信可信来源；FileProvider 收窄到导出子目录（如 `good_exports/`）。
- A30 `backup_rules.xml`/`data_extraction_rules.xml`：显式 exclude `filesDir`、`trigger_settings`（或直接 `allowBackup="false"`，与隐私口径一致）。
- A32 两个 Bridge Provider `call()` 加兜底 catch + 统一错误 Bundle；A11yOverlayRuntime else 分支返回明确错误键。
- P3 批：START_STICKY 空意图分支 `startForeground(sharing=false)` 或 `stopSelf`（`TriggerForegroundService.kt:177-188`）；:a11y 进程 NoticeCenter install（`PocketApplication.kt:17`）；balProbeView 挂载失败/卸载路径（`InputAccessibilityService.kt:905-975`）。

### 2D：构建配置（S）
- A33 `proguard-rules.pro`：补 onnxruntime / opencv / JNI 反射面 keep 规则（本期只写不启用，见裁决 6）。
- A34 `settings.gradle.kts:34`：`mavenLocal` 移出常规链（仅本地 profile 启用）或为 `com.esc.irminsul:capture` 加版本校验，保证异机/CI 可解析。
- A35 `app/build.gradle.kts:99-104`：桌面 OpenCV 依赖收敛为单一来源（提交 jar 入库并写明出处，或统一 openpnp）。
- A36（androidx 拉平）、A37（FGS dataSync 6h 上限）→ 挪 Stage 4。

---

## 4. Stage 3：结构重构（先归位、后拆分；约 2–3 周）

> 前置：Stage 1+2 全部入库、全量测试绿。本阶段所有子步骤均不改行为，每步合入后跑默认闸门；涉及 ScanEngine 的每步加跑 `-Pdryrun`。

### 3.1 共享类型与孤儿文件归位（M，串行第一）
机械移动（纯移动 + import 更新，一个 commit 系列）：
- 新建 `core` 包：`FlowSource`（自 dsl/）、`IntRect`/`Geometry`（自 recognition/）→ **消解 capture↔recognition 环的一半**。
- `recognition/CaptureContent.kt`、`CaptureScale.kt` → capture 包（帧容器本属 capture 域）。
- `recognition/opencv/MatOps.kt` → `core/image`（被 capture 与 recognition 两方使用；`MatchTemplateHelper`/`OpenCvRuntime` 留在 recognition）→ 环彻底消解。
- `ScanEngine.kt` 内的 `OcrGateway`、`ScanListener`、`ScanVars`、`ScanAbortedException` 拆为独立文件（位置暂留 scan 包）。
- `GoodExporter` 从 `ArtifactDomain.kt:91` 拆出独立文件；`SwipeMethod` 移出服务文件尾。

验收：`grep` 验证 capture 不再 import recognition、recognition 不再 import capture；全量测试绿。

### 3.2 bridge 包归拢 + 依赖环消解（M，紧跟 3.1 串行）
- 新建 `bridge` 包：`AccessibilityBridgeProvider`（自 input/）、`SettingsBridgeProvider`（自 settings/）、`OverlayBridge`、`BridgeSettingsRepository` 移入；两 Provider 的协议注释集中一处。
- `settings→service` 反转：SettingsBridgeProvider 不再 import TriggerForegroundService，改为服务启动时注册回调的注册表（registry 对象住 bridge 包）。
- `overlay→input` 反转：OverlayWindowController 不再 import InputAccessibilityService，构造注入 `AccessibilityServiceHealth` 同形接口。

验收：包依赖矩阵复查——input↔overlay、service↔settings 两环消失；全量测试绿。

### 3.3 ScanEngine 拆分（L，本阶段最大工单，单独一人推进；四步串行）

> ①（OcrGateway/ScanListener/ScanVars/ScanAbortedException 拆独立文件）已在 3.1 完成，本表从 ② 起编号。

| 步 | 内容 | 闸门 |
|---|---|---|
| ② parsePanel 域化 | `parsePanel`（函数在 :6830，:6638 是段注释；连带 dump* 取证出口，~930 行）+ set_name 反推 + 词条解析调用侧整体搬入 `ArtifactDomain`；新建 `WeaponDomain`/`CharacterDomain` 同构。**逻辑一行不改**（裁决 5） | `-Pdryrun` 绿 |
| ③ PageSession | pagedGrid（:1457→:2544，~1087 行。⚠️ 初版写 "~1925 行" 是到 §14 :3379 的距离，把 swipeGridToTop/rosterFind/charFilter 一串 helper 都算进去了）的页级可变状态（pageMinLevel、pageKeys/prevAllCellIds、curPageIds、lastCellKey、curCellIdx）收拢为显式 `PageSession` 状态机；A9/A11 已修复行为原样吸收；**新增状态机级单测**（skip 推进、重访、回卷、止扫各路径） | `-Pdryrun` 绿 + 新状态机测试绿 |
| ④ 原语分族 | 26 个 do 原语按族拆实现类（导航族：enterScreen/navigate/exit…；判读族：vote/parsePanel/ifMatch…；控制族：foreach/setFilter/stopWhen…），ScanEngine 保留解释器分发与编排 | `-Pdryrun` 绿 + DoCoverageTest 绿 |
| ⑤ 角色扫描域化 | §14 段（:3379 起 ~3184 行）搬入 CharacterDomain 收尾 | `-Pdryrun` 绿 |

目标：`ScanEngine.kt` ≤1500 行（纯编排 + 分发）；文件头补"当前拆分地图"注释，终结 #1/#6/#2/#3 式非顺序段落标记。

### 3.4 OverlayWindowController 拆分（M，可与 3.3 并行——无文件交集）
- 按窗口组件拆：Bubble（悬浮球）/ Panel（主面板）/ LogWindow（识别日志窗）/ NoticeBar（提醒条）/ ScriptRows（脚本行）各自成类，控制器退化为装配与 z 序管理。
- **一并处理裁决 3 的两条 P3**：`hide()` 清残留 view 引用、`captureScope` 生命周期（cancel 时机与 show/hide 对齐）。
- 回归：真机过一遍悬浮球展开/拖动/日志窗开合/提醒条三档（B2 拆分后 UI 行为逐项对拍）。

### 3.5 pcdata 分拆（S，3.2 之后）
- `CaptureReplay`/`CaptureSession` → `debug`（PC 抓包回放是调试设施）；`CaptureGate` → capture 包；`CaptureConsentActivity` 随 gate 走。
- 允许保留 debug→scan 单向依赖（GoodRepository 用于回放落库），在 README 架构图标注"调试设施允许单向依赖生产核心"。

### 3.6 MainActivity 拆屏（M，可与 3.3/3.4 并行）
- onboarding 与脚本管理器拆为两个 Activity（或 Fragment），复用 Stage 2C 已修的深链校验；每屏独立布局与入口。
- 顺带把根包 4 文件归位：MainActivity 进 `ui` 包（AppForeground/PocketApplication 留根包，属应用级）。

---

## 5. Stage 4：加固、性能与文档对齐（内部可并行，约 1 周）

1. **开启 R8/minify**：启用 A33 keep 规则，release 包全回归（真机 e2e + `unzip -l` 资产复验 + GOOD 导出对拍）。
2. **androidx 拉平**（A36）：core-ktx/appcompat/material/activity/constraintlayout/test 一次对齐，跑全量回归。
3. **FGS 长驻策略**（A37）：targetSdk 36 下 dataSync 型 6 小时上限——评估未投影期改 `stopSelf`、投影期用 mediaProjection 型、或 specialUse，需真机长挂验证。
4. **性能批**：OCR det 全帧短命分配复用（~13-16MB/帧，`OnnxPaddleOcrService.kt:255-281,336-358`）；Stage 1 后复查 grabFresh 无回归；VoteJudges 整行 JNI（若 2A 未做）。
5. **文档与测试卫生**：
   - README 数字对齐：默认 394 条/55 类、dryrun 407 条/56 类（非 374/53），dryrun 类耗时 **185.5s**（README 的 640.8s 是修可达计数器前的旧值；`app/build.gradle.kts` 注释"52 类/362 条"同错）、dsl 资产 24 文件（非 9 份）、do 原语 26 种（非 17）；§7.1 架构图按 3.1–3.6 后的实际包结构重画。
   - `DoCoverageTest.kt:9` 头注释"5 个 flow"改 7。
   - `scripts/e2e_scan.sh`：ADB/APK 路径参数化；删除 `appops SYSTEM_ALERT_WINDOW` 与 `pm grant POST_NOTIFICATIONS` 残留（:80-81）；`uiautomator dump` 临时文件按 serial 隔离。
   - 删除两份脚手架 Example 测试与空挂的 espresso 依赖（或补第一个真仪器测试）。
   - `D_SIGCONFIRM` 等 A/B 结论注释与常量终值对齐（若 2A 未清完）。

---

## 6. 全量工单映射表（发现 → 阶段·轨）

**P0/P1（Stage 1）**

| 发现 | 位置 | 轨 |
|---|---|---|
| A1 DebugControlReceiver 无保护导出 | AndroidManifest.xml:135 | 1A |
| A2 CapturePermissionActivity 导出 | AndroidManifest.xml:70 | 1A |
| A3 windowContains 坐标系错位 | OverlayWindowController.kt:752 | 1B |
| A4 stop 持锁 join | ScreenCaptureController.kt:201 | 1C |
| A5 grabFresh 全帧空转 | FrameSource.kt:83 | 1C |
| A6 initialize 竞态 | recognition/ocr/onnx/OnnxOcrEngine.kt:87 | 1E |
| A7 降档期静默空结果 | recognition/ocr/onnx/OnnxPaddleOcrService.kt / OcrGatewayImpl.kt | 1E |
| A8 慢推理计为失败→不可逆降档 | recognition/ocr/onnx/OnnxOcrEngine.kt:238 | 1E |
| A9 pageMinLevel 翻页不终止 | ScanEngine.kt:1563,1934,2512 | 1D |
| A10 武器扫描假"总数不符" | ScanEngine.kt:349 | 1D |
| A11 闸门等新帧解析旧帧 | ScanEngine.kt:6883 | 1D |
| A12 ".7"→7.0 十倍错 | StatParser.kt:131 | 1D |
| A38(#155) TALENT_BONUS 成片少减 +3 | ScanEngine.kt:3736,4286 | 1D |
| A39(#156) 未解析身份 key="" 下游并条 | ArtifactDomain.kt:144,157 | 1D |
| A40(#147) 判稳信号看不见慢漂 | ScanEngine.kt:8494,1583 | 1D |

**P2（Stage 2，个别提前入 Stage 1 对应轨）**

| 发现 | 位置 | 轨 |
|---|---|---|
| A13 看门狗 CME 杀进程 | ScriptRunner.kt:316 | 1D（提前） |
| A14 超时后游离点击 | ScriptRunner.kt:129 | 1D（提前） |
| A15 stop/start 双引擎 + 静默无回执 | ScriptRunner.kt:213,397 | 1D（提前） |
| A19 prepare 双失败返回 true | OnnxPaddleOcrService.kt:96 | 1E（提前） |
| A25 还原早于手势 UP | AccessibilityAutomationController.kt:46 | 1B（提前） |
| A26 主线程同步 binder ANR 面 | A11yOverlayRuntime/OverlayBridge | 2C |
| A27 getMediaProjection 裸异常 | ScreenCaptureController.kt:227 | 1C（提前） |
| A28 临界区内整帧转换 | ScreenCaptureController.kt:280 | 1C（提前） |
| A29 深链信任 extras / FileProvider 全域 | MainActivity.kt:148 / file_paths.xml | 2C |
| A30 allowBackup 空规则 | AndroidManifest.xml:39 / res/xml | 2C |
| A31 debug FGS 对所有应用导出 | src/debug/AndroidManifest.xml:7 | 1A（提前） |
| A32 Provider call() 无兜底 | 两个 BridgeProvider | 2C |
| A16 Expr 缺变量静默为 0 | Expr.kt:106 | 2A |
| A17 吞 CancellationException | ScanEngine.kt 多处 | 2A |
| A18 level+10 解出不回写 | RollSolver.kt:96 | 2A |
| A20 benchmark 预热剔错 | recognition/ocr/onnx/OnnxOcrEngine.kt:173 | 2B |
| A21 ROI 越界硬崩 | ImageRegion.kt:5 处 | 2B |
| A22 NameMatcher 三层无确定性 | NameMatcher.kt:165,193,225 | 2B |
| A23 legacy scale 误伤精确模板 | TemplateAssetLoader.kt:29 | 2B |
| A24 manifest 失败静默弃校验 | recognition/ocr/onnx/OnnxModelAssets.kt:50 | 2B |
| A33 R8 无 keep 规则 | proguard-rules.pro | 2D |
| A34 mavenLocal 可复现性 | settings.gradle.kts:34 | 2D |
| A35 OpenCV jar 分叉 | app/build.gradle.kts:99 | 2D |

**架构（Stage 3）**

| 发现 | 轨 |
|---|---|
| B1 ScanEngine 8592 行拆分（含 OcrGateway/ScanVars 归位、GoodExporter 拆出） | 3.1（前置部分）+ 3.3 |
| B2 OverlayWindowController 1825 行拆分 + 宿主反依赖 | 3.4 |
| B3 pcdata 包名实不符分拆 | 3.5 |
| B4 FlowSource 寄养 dsl | 3.1 |
| B5 IntRect/Geometry 共享类型归位 | 3.1 |
| B6 MatOps 双方共用 → core/image（消解 capture↔recognition 环） | 3.1 |
| B7 两 Provider 分居两处 → bridge 包（消解 input↔overlay、service↔settings 环） | 3.2 |
| B8 MainActivity 双屏 + 根包无 ui | 3.6 |
| B9 settings→service / capture→service 反向依赖 | 3.2（settings 侧反转；capture 侧 Activity→Service intent 属 Android 惯例，仅注释说明） |

**P3 与文档（Stage 2 各轨 P3 批 + Stage 4 第 5 项）**

| 发现 | 轨 |
|---|---|
| GridRowCheck 平票 / TemplateMatcher 锁 / D_SIGCONFIRM / 日志拼接 / ScriptStore 排序 / ScreenProfile 钳位 / rawObject!! / seenArtifactKeys / VoteJudges JNI | 2A P3 批 |
| CtcDecoder 置信度 / MatOps BGRA / u8 块读写 / ML Kit recycle / probe latch | 2B P3 批 |
| START_STICKY / :a11y NoticeCenter / balProbeView | 2C P3 批 |
| OCR det 每帧 13-16MB 分配 | Stage 4-4 |
| README 数字漂移 / DoCoverageTest 注释 / e2e 脚本路径与残留 / 脚手架测试与 espresso | Stage 4-5 |
| hide() 残留引用 + captureScope 不 cancel | 3.4（裁决 3，不提前） |
| SwipeMethod 枚举位置 / accessibility 配置面略宽 | 3.1 / 不处理（记录在案） |

---

## 7. 验收闸门与回滚

**每轨合入闸门（Stage 1/2）**
- `./gradlew :app:testDebugUnitTest` 默认闸门绿（09-30 实测 55 类 / 394 条 / 3.2s；数字随提交漂移，以当轮 XML 解析为准）；
- 触及 ScanEngine / dsl flows / 判据常量的，另跑 `-Pdryrun` 全量绿（56 类 / 407 条，约 189s）；
- 悬浮窗 / 输入 / 采集轨另需真机 e2e（`scripts/e2e_scan.sh` 对应阶段；manifest 类改动另跑 `recover_bs.sh` 全链），单元测试覆盖不了 z 序与投影行为。

**Stage 3 每步闸门**
- 机械移动 commit：全量测试绿 + 包依赖矩阵复查（无新环）；
- ScanEngine 各步：`-Pdryrun` 绿，PageSession 步额外新增状态机测试绿；
- 交付 APK 前复验：`unzip -l <apk> | grep assets/dsl` = 24 个文件。

**阶段末发布点**：Stage 1 末、Stage 2 末、Stage 3 每完成一个子步、Stage 4 末，各打一个 tag（如 `hotfix-stage1`、`refactor-3.3-pagesession`），保证任一步引入回归可按 tag 二分回滚。

**明确不做的事（本期范围外，防范围蔓延）**
- 不重写 OCR 归一化 / 通道序 / 字典契约（已对齐 PP-OCR 标准，核对通过项）；
- 不动 dsl JSON 资产内容与坐标（真机标定另行推进，见 overview.md 待标定清单）；
- 不引入 DI 框架 / 重写跨进程协议（bridge 包归拢已消除主要痛点）；
- 不做 ML Kit 兜底回归（裁决见 A7）。
