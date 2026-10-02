pluginManagement {
    repositories {
        // 国内镜像前置（dl.google.com/services.gradle.org 直连不稳），原仓库保留兜底
        maven("https://maven.aliyun.com/repository/google") {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/central")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google") {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
        // mavenLocal 放链尾而非首位（A34）：
        // ① 防遮蔽 —— 本机 ~/.m2 里的旧包（同名同坐标旧版本）会静默盖掉远程最新版；
        // ② 异机/CI 解析保证 —— 链上任何能解析的坐标不会先撞 mavenLocal 失败。
        // com.esc.irminsul:capture:1.10.1 不在任何远程仓库，是 irminsul-android 仓库
        // 本地发布的：`./gradlew :capture:publishToMavenLocal` ⇒ 本机开发需要它时
        // 必须先发布到 mavenLocal；异机/CI 需同样执行（其 POM 带 androidx.core/
        // coroutines 依赖，必须以坐标而非 libs/ 文件方式引入）。
        // 2026-09-30 验证：debugRuntimeClasspath 仍解析出 com.esc.irminsul:capture:1.10.1。
        mavenLocal()
        exclusiveContent {
            forRepository {
                maven("https://maven.aliyun.com/repository/central")
            }
            filter {
                includeModule("org.openpnp", "opencv")
            }
        }
    }
}

rootProject.name = "BetterGIPocket"
include(":app")
