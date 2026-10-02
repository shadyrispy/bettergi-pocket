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
        // The capture AAR is built and published locally
        // (irminsul-android: `./gradlew :capture:publishToMavenLocal`); its POM is
        // what keeps androidx.core/coroutines resolving properly, so the library
        // must come in as a coordinate rather than a file in libs/.
        mavenLocal()
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
