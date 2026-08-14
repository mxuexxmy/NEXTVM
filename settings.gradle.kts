pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    @Suppress("UnstableApiUsage")
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "NEXTVM"

// App module
include(":app")

// Core modules
include(":core:virtualization")
include(":core:model")
include(":core:hook")
include(":core:binder")
include(":core:apk")
include(":core:sandbox")
include(":core:designsystem")
include(":core:common")
include(":core:framework")
include(":core:services")

// Feature modules
include(":feature:launcher")
include(":feature:appmanager")
include(":feature:settings")
include(":feature:filemanager")
