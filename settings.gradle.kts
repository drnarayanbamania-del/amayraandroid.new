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
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Maya"
include(":app")
include(":pc-assistant")
include(":scanner")

// Keep build outputs off OneDrive (sync file locks break Gradle's incremental deletes).
// Build dirs live under E:\maya-build\<project> — C: is chronically full (2026-09-14),
// E: has the space. Kotlin/Gradle caches also moved to E: via GRADLE_USER_HOME.
gradle.lifecycle.beforeProject {
    layout.buildDirectory = File("E:/maya-build/${path.removePrefix(":").replace(':', '/').ifEmpty { "root" }}")
}
