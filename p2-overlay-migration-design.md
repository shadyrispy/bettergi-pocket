# P2 悬浮窗迁移设计（A11yOverlayHost）

状态：**已落地（2026-09-18）**。

> 实施说明（与本文原始设计的差异，实测后定稿）：
> - 本文写于方案阶段，当时设想 `OverlayHost`/`AppOverlayHost` 双实现回退。**最终未做双实现** ——
>   无障碍本就是扫描的硬前提，双轨只会带来「两个进程各起一个控制器」的状态同步难题。
>   改为**单轨**：悬浮窗只活在无障碍进程（`A11yOverlayRuntime` 持有控制器），主进程只留一个
>   同名门面 `OverlayBridge` 负责转发。
> - 设置桥按本文「决策一 A」实现（`.settings` Provider 主进程权威 + `notifyChange` 回推），
>   另外补了识别日志的跨进程镜像（`log_snapshot` 轮询 + `log_append` 回写）。
> - 逐点击的 `prepareClickPassthrough` **保留 IPC**，没有搬进注入侧（会改掉扫描/自动对话两处语义）。

## 已确认的前提

- 探针已在模拟器验证：`TYPE_ACCESSIBILITY_OVERLAY` 可见地盖在原神界面上方 → 方案 §4.2.1 第 5 条「可见则 A11yOverlayHost 为主挂载」成立。
- 硬约束：**View 不能跨进程**，视图树必须在 `:a11y` 进程内构建。

## 关键调研结论（决定设计形态）

1. **控制器交互全部收敛到 `settingsRepository`**：`OverlayWindowController` 的所有按钮都只是 `setScanEnabled`/`setScreenShareEnabled`/`setScanFlow`/`setScanMaxPages`…，再靠 `addListener` 观察回写 UI；**从不直接调 `TriggerForegroundService`**。跨进程契约主要是「设置状态」，不是任意方法调用——这是最大的简化。
2. **`TriggerSettingsRepository` 跨进程会失效**：SharedPreferences(`MODE_PRIVATE`) + **进程内**缓存 `current` + 进程内 `listeners`。`:a11y` 自己 new 一个会读脏、也收不到主进程变更 → **这是迁移唯一的核心难点**。
3. `PocketApplication.onCreate` 已在非主进程提前 return，`:a11y` 天然轻量，符合「:a11y 补一套轻量 UI 装配」。
4. 控制器还有第二个窗口（日志窗 handle + body）需一并迁移。

## 设计决策一：设置如何跨进程

| 方案 | 做法 | 评价 |
|---|---|---|
| **A（推荐）主进程持有权威，`:a11y` 经桥代理** | `:a11y` 侧用 `BridgeSettingsRepository`（同接口），写操作经 `ContentProvider.call` 发主进程真实 repository；主进程变更用 `ContentResolver.notifyChange` 推回 `:a11y`（`:a11y` 注册 ContentObserver） | 单一权威、无竞态；`TriggerForegroundService` 零改动。代价：新增 2 个桥方法 + 一个 observer；`:a11y` 依赖主进程存活 |
| B 双进程各自持有 | 两边都直接读写 SharedPreferences，靠 notifyChange 互相同步 | `MODE_PRIVATE` 无跨进程锁，并发写会分裂。不推荐 |

方案原文 §4.2.4 也正是「主 → a11y：`ContentProvider.call` 下发；a11y → 主：…`ContentResolver.notifyChange` 回传」，与 A 一致。

## 设计决策二：控制器在哪个进程跑

| 方案 | 做法 | 评价 |
|---|---|---|
| **1（推荐）整体迁移 + 运行时二选一** | a11y 可用时，controller 实例**只在 `:a11y` 起**（用 AccessibilityService 作 context，视图 inflate 在该进程），配 `A11yOverlayHost`；a11y 不可用时主进程起 controller + `AppOverlayHost`。同一时刻只有一个实例 | 只有一套 UI 逻辑，符合 §4.2.2「拖拽贴边逻辑保留在 controller」。需处理：从 `:a11y` 起 Activity（Bilibili / 无障碍设置 / 启动原神，需 `FLAG_ACTIVITY_NEW_TASK`）、`onShareGoodRequested`（FileProvider 分享需 Activity）回主进程 |
| 3 `:a11y` 精简 UI + 主进程完整面板 | 两套 UI | 维护成本翻倍，但 `:a11y` 侧极轻量（契合 R2）。不到必要时不做 |

## 实施分解（推荐路径）

1. **设置桥**：新增 `METHOD_SETTINGS_GET` / `METHOD_SETTINGS_SET`；`:a11y` 侧 `BridgeSettingsRepository` 实现同接口；主进程变更 `notifyChange` → `:a11y` ContentObserver 触发 UI 刷新。
2. **`A11yOverlayHost`**：`attach/detach/updateParams` 转调已加固的 `InputAccessibilityService.attach/detach/updateA11yView`。
3. **`:a11y` 运行时装配**：在 `InputAccessibilityService.onServiceConnected` 里按条件起 controller（context = service），inflate 用 `ContextThemeWrapper(service, R.style.Theme_BetterGIPocket)`；日志窗一并走 `A11yOverlayHost`。
4. **回切与去重**：a11y 可用时主进程不起 controller；a11y 断开时销毁 `:a11y` 视图并切回主进程 `AppOverlayHost`。
5. **跨进程回调**：`onExit` / `onShareGoodRequested` / 起 Activity 三类，统一经桥回主进程执行。
6. **验收**（方案 §7 P2）：不开「显示在应用上层」权限、仅开无障碍，可完成一次扫描；老 AutoSkip 悬浮窗回归。

## 风险

- `:a11y` 进程被杀 → 悬浮窗与手势同时失效（方案已接受：扫描强依赖无障碍）。
- 从 service context 起部分系统页面（无障碍设置）可能受限，需实测；受限则回主进程执行。
- `:a11y` 依赖主进程活着提供设置权威；主进程被杀时 `:a11y` 需降级为只读缓存 + 不响应写入。
