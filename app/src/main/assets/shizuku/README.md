# assets/shizuku/ — 内置 Shizuku 安装包（占位目录）

**此目录放置 Shizuku 官方 release APK，命名固定 `shizuku.apk`。**

- 下载源：<https://github.com/RikkaApps/Shizuku/releases>（取最新 `shizuku-vX.Y.ZrZZZZ.apk`，包名 `moe.shizuku.privileged.api`）
- 放置：`app/src/main/assets/shizuku/shizuku.apk`
- **真 APK 不入库**（`.gitignore` 已挡 `app/src/main/assets/shizuku/*.apk`）——
  体积 ~4MB 且随上游版本漂移，由维护者构建前手动放入。
- 代码入口：`ShizukuInstallHelper.isBundled()` 检测本资产；缺失时安装动线
  退回「跳 GitHub 下载」路径（M2 接 UI），app 功能不受影响。

## 为什么内置

后台模式（#310，virtualdisplay 分支）依赖 Shizuku 提权。目标用户群（原神扫描器
用户）大概率没装过 Shizuku，四态引导第一态「未装 → 获取 Shizuku」需要一条
**不离开设备**就能走通的安装路径：导出内置 apk 到 Downloads 拉系统安装器，
比让用户自己去 GitHub 找 release 成功率高得多。
