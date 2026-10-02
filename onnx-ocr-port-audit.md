# ONNX PaddleOCR 移植 — 审计报告（2026-09-04）

## 一、移植范围

移植源：irminsul `com.esc.irminsul.ocr`（9 文件 751 行）→ bettergi-pocket `recognition/ocr/onnx`（6 文件）。

| 文件 | 来源 | 适配 |
|---|---|---|
| `EpTierPicker.kt` | 原样 | 纯逻辑，JVM 可测 |
| `CtcDecoder.kt` | 原样 | 纯逻辑；`dict[best-1]` 契约 |
| `OnnxModelAssets.kt` | 改写 | assets/onnx → filesDir/onnx + SHA-256 校验 |
| `DbPostProcessor.kt` | 改写 | 返回 `IntRect` 取代 `android.graphics.RectF` |
| `OnnxOcrEngine.kt` | 改写 | suspend → 同步；**不持 Context**（JVM 可测）；输入名动态取 |
| `OnnxPaddleOcrService.kt` | 改写 | **预处理入参 Bitmap → Mat**；实现 `IOcrService` |

未移植：`ZeroCopyDetector`（irminsul 的 AHardwareBuffer 零拷贝探测，本仓 FrameSource 已是 Mat 直通，不需要）、
`OcrTextPostProcessor`（见审计项 4）。

模型资产：`assets/onnx/{det.onnx 1.75MB, rec.onnx 4.46MB, ppocrv6_tiny_dict.txt, manifest.json}`，
SHA-256 与 manifest 已逐项核对一致。

## 二、关键设计决策

1. **预处理入参用 Mat，不落 Bitmap**（方案 §6.1）。归一化/缩放全部走 OpenCV 原生
   （`convertTo(scale=1/127.5, shift=-1)` + `split`），Kotlin 侧只做 3 次整块读取 + 3 次 arraycopy 完成
   HWC→CHW 重排，无逐像素循环。
2. **引擎不持 Context**，只吃模型路径 → JVM 单测可脱离 Android 跑真实推理（这是精度/速度可回归的前提）。
3. **同步接口**：`OrtSession.run` 本就阻塞，`IOcrService` 也是同步接口；本实现全程顺序执行，
   不存在 irminsul 那种「≤2 并发 rec」的会话并发访问风险。
4. **OcrFactory 两段式**：先挂 ML Kit（冷启动即可用）→ 后台预热 ONNX → 就绪后热切换；
   ONNX 不可用静默保持 ML Kit 兜底（落实 R4/Q3「双引擎共存」）。

## 三、审计发现与处置

| # | 发现 | 处置 |
|---|---|---|
| 1 | **EP 降档接口是死代码**：`degradeTier()` 从未被调用 | ✅ 已接线：连续推理失败 3 次自动降一档（CPU 兜底） |
| 2 | **锁死锁隐患**：降档会取写锁，若在读锁内调用必死锁（`ReentrantReadWriteLock` 不支持读锁升级） | ✅ 已改为读锁**外**调用 |
| 3 | **静默吞异常**：`runSession` 的 catch 不记日志；NNAPI 在部分 ROM 上会中途崩，日志是唯一线索 | ✅ 补 `Log.e`（含 tier / 输入名 / shape） |
| 4 | **字典与模型不配套风险**：irminsul 20260827 踩过——汉字全乱但 ASCII/数字看着正常，极易漏检 | ✅ 加初始化防呆：字典条目数 ≠ 6904 时告警；单测锁死 `dict=6904 / MODEL_CLASS_COUNT=6906` |
| 5 | **未使用常量** `CONF_THRESHOLD`（irminsul 用它触发重拍，本仓无重拍通道） | ✅ 删除 |
| 6 | **`android.util.Log` 在 JVM 单测**：android.jar 是 stub，本会抛 "not mocked" | ⚠️ 依赖既有 `testOptions.unitTests.isReturnDefaultValues = true` 兜底（代价：单测中日志被丢弃）。已在类注释标注 |
| 7 | **det 每次分配约 6.5MB**（FloatArray 4.9MB + 临时 1.6MB + 3 个 plane），连续扫描有 GC 压力 | ⚠️ 未修，见「五、建议」 |
| 8 | **包体增长**：APK 46.8MB → 70.0MB（+23MB；`libonnxruntime.so` 17.5MB + 模型 6.2MB，debug 未 strip） | ⚠️ 需决策，见「五、建议」 |
| 9 | `OcrFactory.engineLabel` 目前只写不读（预留给面板状态展示） | ⚠️ 未接线，待接入状态显示时启用 |

## 四、实测数据（JVM / desktop OpenCV / ORT 1.20.0，EP=CPU）

| 指标 | 结果 |
|---|---|
| rec-only（单槽，稳态扫描主路径） | 中位 **2ms**（样本 2–3ms） |
| 全管线 det+rec | 中位 **44ms**（样本 43–47ms） |
| 数字/拉丁精度（n=7：Lv.90 / 1026 / 4.7% / 23.4% …） | **7/7 完全匹配**，字符准确率 1.000 |
| 中文精度（n=5：圣遗物 / 攻击力 / 暴击率 / 元素精通 / 生命值） | **5/5 完全匹配**，字符准确率 1.000 |
| 整帧 recognize | 检出 1 行，坐标在界内，score 0.865 |

测试：`OnnxOcrBenchmarkTest`（8 例，挂 `testDebugUnitTest`）。全量 **117 例 0 失败**（109 既有 + 8 新增）。

> **口径声明**：样本是 Java2D 真字体合成的白底黑字，**不代表游戏内描边艺术字 + 复杂背景的真实准确率**。
> 这组数字证明的是**管线正确**（预处理/归一化/通道序/CTC/字典映射全链路无误，尤其中文 100% 排除了
> 字典错配），真实场景精度仍需真机标定。

## 四-b、模拟器实测（Bluestacks arm64，e2e_scan.sh，2026-09-04）

| 项 | 结果 |
|---|---|
| **NNAPI EP 崩溃（严重，已修）** | 首次安装启动即 **SIGSEGV**（app 启动 ~150ms，`libonnxruntime.so`，DefaultDispatch 线程）＝ `benchmarkTier(NNAPI)` → `createSession(addNnapi)`。NNAPI 依赖厂商驱动，模拟器上不可靠；**原生崩溃 Java 捕获不了，整个进程直接死**。JVM 测试发现不了（NNAPI 是 Android-only）。✅ 修：`DEFAULT_ORDER=[XNNPACK, CPU]`，NNAPI 留作真机显式评估（commit `400663f`） |
| 引擎激活 | 修复后日志 `OCR engine → ONNX (CPU)`，主进程存活，零 SIGSEGV（EP 基准实测后选 CPU） |
| e2e 实扫 | `SET_SCAN` → flow **自导航**（game_home→bagpack→artifact_tab）→ 锚点 OCR 命中 `1406/2700` → 逐格扫描 |
| 真实画面识别 | 圣遗物中文件名（止于荣礼的缎彩 / 至纯者的欢荣 / 司信者的圣冕 / 深廊的跃赐之宴…）全部正确；共实扫 **28 件**，每件约 2.3s（1406 件全量约 50 分钟） |
| 停止收尾 | `SET_SCAN false` 后干净停止，`screenShare=true / captureRunning=true`，进程存活、零崩溃 |
| 瑕疵（待办） | 停止时日志 `scan finished: error: StandaloneCoroutine was cancelled`——用户主动停止的 CancellationException 被当 error 记录，应单独处理 |
| 投影偶发不生效 | 服务刚启动 <5s 内发 `SET_SCREEN_SHARE`，授权后 `screenShare=false` 静默退出（ACTION_CAPTURE_RESULT 的守卫分支）。重发广播即正常。疑与启动时序竞态有关，待查 |

> e2e 日志：`.workbuddy/artifacts/e2e/run_*.log`（脚本产物）

## 五、建议（按性价比排序）

### 1. ~~扫描热路径改用 `recognizeRois`（rec-only）~~ ✅ 已落地（commit `9e2d79a`）
`IOcrService` 新增能力标记 `hasFastRecOnlyBatch`（默认 false，ONNX 覆写 true），
`MlKitOcrGateway.readNumber/readLines` 据此分发：ONNX 走 rec-only 批量，ML Kit 保持
原逐槽路径（含 <80px 2x 放大）**行为零变化**。

⚠️ 2x 放大**不能**挪进 `MlKitOcrService.recognize`：`ImageRegion` 的 OcrMatch 直接消费
`recognize` 返回的 region 坐标，放大后坐标会错位——放大只能留在「只要文本不要坐标」的
gateway 路径上。

实测：**10 槽批量 rec-only 总 18ms（每槽 1.8ms），10/10 命中**；单槽 rec-only 中位 2ms
vs 全管线 det+rec 中位 44ms。

### 2. det 输入缓冲复用
复用 640×640×3 的 FloatArray（及 toNchw 的临时数组），省掉每次约 6.5MB 分配。

### 3. 真实场景精度标定
用 dsl/ 的实测截图建一组带标注样本，把「合成样本 1.000」换成真实准确率，作为回归基线。

### 4. 包体
release 构建需 strip；若 23MB 不可接受，可考虑只保留 arm64 分包或换更小的 rec 模型。
