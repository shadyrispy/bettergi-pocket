plugins {
    alias(libs.plugins.android.application)
}

// ── 单测分层开关（用法见 android.testOptions 那段注释）──
private val DRY_RUN_CLASS = "com.bettergi.pocket.scan.ScanEngineDryRunTest"
private val withDryRun: Boolean = project.hasProperty("dryrun")
private val dryRunOnly: Boolean = project.hasProperty("dryrunOnly")

android {
    namespace = "com.bettergi.pocket"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.bettergi.pocket"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            // ★ Stage 4-1（2026-10-01）：开启 R8/minify（A33 keep 规则已就位）。
            //   规则见 proguard-rules.pro：项目自身无 JNI/反射面，重心在三方库
            //   （onnxruntime / opencv / irminsul capture）整包 keep；另保留行号供崩溃栈解混淆。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // ── 单测闸门分层（2026-09-28 实测后定的）────────────────────────────────
        // 全量 69 类 / 509 条里，`ScanEngineDryRunTest`（17 条）占 ~316s，其余 68 类合计 ~6s。
        // 两个原因（第二个是本轮查出来的，第一条一开始被我猜错过）：
        //   ① 引擎的 settle 常量是**真实墙钟**（CLICK/ENTER/SCREEN_SETTLE、PANEL_* …），干跑
        //      用 `runBlocking { engine.run() }` 真等 ⇒ 健康路径 ≈0.66s/格 × 每页 21 格。
        //   ② 三条用例把合成计数器写成不可达的 1026 ⇒ "已入库 ≥ 计数器"永不成立 ⇒ 多扫一页
        //      "同帧假页"，那页同格名字不变 ⇒ **每格等满 PANEL_CHANGE_WAIT_MAX_MS(6000)**
        //      ⇒ 单条 ~167s。已改成可达计数器（断言一字未动），该类当场 640.8s → 185.5s（后随用例增多，现 ~316s）。
        // 干跑仍占 98% ⇒ 分层：
        //   默认（不带属性） 摘掉干跑 ⇒ 实测 68 类 / 492 条 / 测试 6s 级
        //   -Pdryrun        连干跑一起（**改了 ScanEngine 或 dsl/flows 必须带**）⇒ 全量约 5.4min
        //   -PdryrunOnly    只跑干跑 ⇒ 约 316s
        // ⚠️ 默认绿 ≠ 引擎 e2e 绿 —— 跳过时会打一行提示，别只看 BUILD SUCCESSFUL。
        unitTests.all { test ->
            if (dryRunOnly) {
                test.filter.includeTestsMatching(DRY_RUN_CLASS)
            } else if (!withDryRun) {
                test.filter.excludeTestsMatching(DRY_RUN_CLASS)
                test.doFirst {
                    logger.lifecycle(
                        "BetterGI: 已跳过 $DRY_RUN_CLASS（引擎干跑 e2e，~316s / 全量约 5.4min）。" +
                            "改了 ScanEngine 或 dsl/flows ⇒ 请用 -Pdryrun 重跑。",
                    )
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.opencv)
    implementation(libs.kotlinx.coroutines.android)
    // ONNX Runtime：PaddleOCR det+rec 推理（方案 §6.1），唯一 OCR 引擎（ML Kit 已移除）
    implementation(libs.onnxruntime.android)
    // 抓包数据源（irminsul-android :capture）：VPN 取包 + 会话解密 + proto 解析，
    // 与 OCR 扫描并列为第二数据源。版本 = 该仓库 gradle.properties 的 captureVersion。
    implementation("com.esc.irminsul:capture:1.10.1")
    testImplementation(libs.junit)
    // JVM 版 onnxruntime：本地单测跑真实推理（速度/精度对拍），不进 Android 主包
    testImplementation(libs.onnxruntime.jvm)
    // 桌面单测 OpenCV：统一走 openpnp org.opencv:opencv（与 libs.opencv.desktop 同源，A35）。
    // 曾有的 file 分叉（本地 app/libs/opencv-4.9.0-0.jar 存在则用 file，否则 openpnp）
    // 已删除：app/libs/ 目录本仓库并不存在（jar 未入库，非空分支只对个别机器生效），
    // 两台机器 classpath 因此不同，属隐形分叉。openpnp 4.9.0-0 是 OpenCV 4.9.0 官方
    // Java 绑定的桌面打包，org.opencv.core/imgproc/imgcodecs API 与 AAR 完全一致，
    // 单测所用 API（见下方 configurations.exclude：仅单测 classpath 换源）已由
    // VoteJudgesRealDataTest 等真实用例验证可跑。
    testImplementation(libs.opencv.desktop)
    testImplementation("org.json:json:20240303")
}

configurations.matching {
    val n = name.lowercase()
    n.contains("test") && n.contains("classpath") && !n.contains("androidtest")
}.configureEach {
    exclude(group = "org.opencv", module = "opencv")
}
