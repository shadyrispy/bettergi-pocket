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

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            isMinifyEnabled = false
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
        // 全量 53 类 / 374 条里，`ScanEngineDryRunTest` 占 640.8s / 643.6s，其余 51 类合计 2.8s。
        // 两个原因（第二个是本轮查出来的，第一条一开始被我猜错过）：
        //   ① 引擎的 settle 常量是**真实墙钟**（CLICK/ENTER/SCREEN_SETTLE、PANEL_* …），干跑
        //      用 `runBlocking { engine.run() }` 真等 ⇒ 健康路径 ≈0.66s/格 × 每页 21 格。
        //   ② 三条用例把合成计数器写成不可达的 1026 ⇒ "已入库 ≥ 计数器"永不成立 ⇒ 多扫一页
        //      "同帧假页"，那页同格名字不变 ⇒ **每格等满 PANEL_CHANGE_WAIT_MAX_MS(6000)**
        //      ⇒ 单条 ~167s。已改成可达计数器（断言一字未动），该类 640.8s → **185.5s**。
        // 干跑仍占 98% ⇒ 分层：
        //   默认（不带属性） 摘掉干跑 ⇒ 实测 52 类 / 362 条 / 测试 3.0s（BUILD 5s）
        //   -Pdryrun        连干跑一起（**改了 ScanEngine 或 dsl/flows 必须带**）⇒ 188.5s
        //   -PdryrunOnly    只跑干跑 ⇒ 185.5s
        // ⚠️ 默认绿 ≠ 引擎 e2e 绿 —— 跳过时会打一行提示，别只看 BUILD SUCCESSFUL。
        unitTests.all { test ->
            if (dryRunOnly) {
                test.filter.includeTestsMatching(DRY_RUN_CLASS)
            } else if (!withDryRun) {
                test.filter.excludeTestsMatching(DRY_RUN_CLASS)
                test.doFirst {
                    logger.lifecycle(
                        "BetterGI: 已跳过 $DRY_RUN_CLASS（引擎干跑 e2e，185s / 全量 189s）。" +
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
    implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.kotlinx.coroutines.android)
    // ONNX Runtime：PaddleOCR det+rec 推理（方案 §6.1）；~10-15MB/ABI，ML Kit 保留为降级兜底
    implementation(libs.onnxruntime.android)
    // 抓包数据源（irminsul-android :capture）：VPN 取包 + 会话解密 + proto 解析，
    // 与 OCR 扫描并列为第二数据源。版本 = 该仓库 gradle.properties 的 captureVersion。
    implementation("com.esc.irminsul:capture:1.10.1")
    testImplementation(libs.junit)
    // JVM 版 onnxruntime：本地单测跑真实推理（速度/精度对拍），不进 Android 主包
    testImplementation(libs.onnxruntime.jvm)
    val desktopOpenCv = file("libs/opencv-4.9.0-0.jar")
    if (desktopOpenCv.exists()) {
        testImplementation(files(desktopOpenCv))
    } else {
        testImplementation(libs.opencv.desktop)
    }
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

configurations.matching {
    val n = name.lowercase()
    n.contains("test") && n.contains("classpath") && !n.contains("androidtest")
}.configureEach {
    exclude(group = "org.opencv", module = "opencv")
}
