# BetterGI Pocket — R8 keep 规则（A33，2026-09-30）
# ─────────────────────────────────────────────────────────────
# 当前 release 的 isMinifyEnabled = false（方案裁决 6：本期只写规则不启用）。
# 本文件是未来开启 minify 的兜底：所有规则宁可略宽（keep classes + members），
# 上线前再按 R8 收缩报告（mapping.txt / usage.txt）逐步收紧 —— "先宽后收紧"。
#
# 项目自身的 JNI/反射面盘点（2026-09-30 grep 证据）：
#   - System.loadLibrary：仅 DeviceCapabilities.kt:143 探测 QNN（QnnHtp/QnnHtpPrepare/
#     QnnSystem，Qualcomm EP，均为系统 so，非本包类）——无需 keep 项目类。
#   - Class.forName：仅两处，均反射【框架/库类】而非项目类：
#       DeviceCapabilities.kt:93  → android.os.SystemProperties（framework，运行时存在，
#                                   R8 不会动 framework 类）；
#       OpenCvRuntime.kt:35      → nu.pattern.OpenCV（仅 JVM 单测路径，不进 release APK）。
#   - newInstance：ScreenCaptureController.kt 三处均为 ImageReader.newInstance ——
#     framework 工厂方法，不是反射构造项目类。
#   - @Keep：main 源码零使用。
#   - org.json：pcdata/scan 用 JSONObject 做手工解析，走的是 Android 平台内置
#     org.json（framework），不依赖项目类反射，无需 keep。
#   - 项目内没有 external fun / 自定义 native 声明；JNI 全部住在三方 AAR 内
#     （onnxruntime-android、OpenCV 官方 AAR、irminsul capture）。
# ⇒ 所以对项目自身类没有额外 keep 需求，重心在三方库（见下）。

# ── ONNX Runtime（ai.onnxruntime.**）──────────────────────────
# 为什么：onnxruntime-android 的 C++ 层通过 JNI 按完整类名查找 Java 类与方法
# （OrtEnvironment/OrtSession/OnnxTensor 等的 native 方法是静态注册表 + JNI
# OnnxTensor/OrtSession/SessionOptions 构造面直接从 C++ 侧 new Java 对象）。
# 一旦类名/方法名被混淆或类被移除，OrtEnvironment.getEnvironment() 会直接
# UnsatisfiedLinkError / NoSuchMethodError。官方文档要求 keep ai.onnxruntime.**。
# 先宽：classes + members 全保（含 EnumMap/值类型包装如 OnnxJavaType、OnnxMap）。
-keep class ai.onnxruntime.** { *; }
# OnnxTensor 依赖的裸内存访问由 native 侧直接读字段，连带保护其继承面。
-keep class ai.onnxruntime.OrtEnvironment$* { *; }

# ── OpenCV（org.opencv.**）────────────────────────────────────
# 为什么：OpenCV 官方 Android SDK（AAR）自带 consumer rules，但覆盖不全：
#   ① native 方法（Imgproc/Imgcodecs/Core 等大量 `private static native long`
#      句柄操作）按符号名 JNI 查找，混淆必炸 UnsatisfiedLinkError；
#   ② Mat 及其子类（MatOfByte/MatOfPoint/MatOfRect…）的 native 指针存在 Java
#      对象头字段里，R8 若删字段或改布局即 native 访问越界；
#   ③ OpenCVLoader.initLocal()（OpenCvRuntime.kt:20）内部按类名触发 so 加载。
# consumer rules 只保了部分类，这里整体保住，先宽后收紧。
-keep class org.opencv.** { *; }
# OpenCV AAR 里的静态初始化器（System.loadLibrary("opencv_java4")）不可内联/移除。
-keepclassmembers class org.opencv.core.Mat { <init>(...); void n_*(...); long nativeObj; }

# ── irminsul capture（com.esc.irminsul:capture:1.10.1）────────
# 为什么：capture 内含 VPN/会话解密 native 层（libcapture.so / libirminsul.so），
# libcapture 按方法名在对象上解析 onPacketCaptured/onCaptureStats/protectSocket。
# AAR 已自带 consumer rules（proguard.txt：保 native 方法 + internal.**），这里
# 整包再保一层兜底，防止其未来改版漏带 consumer rules 时被 R8 收缩。
-keep class com.esc.irminsul.** { *; }

# ── 崩溃栈可读性 ──────────────────────────────────────────────
# 开 minify 后仍要能对着 mapping.txt 解混淆崩溃栈，行号必须保留。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
