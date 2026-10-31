pluginManagement {
    repositories {
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
    // 离线构建时注释：本机 JDK 25 与 app 的 jvmToolchain(25) 一致，不需要 toolchain 自动下载；
    // 该插件在离线态下无法解析（gradlePluginPortal 不可达），会让整个构建在配置阶段就失败。
    // id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Eta"
include(":app")
