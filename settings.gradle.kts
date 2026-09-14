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
// Build dirs live under C:\maya-build\<project> instead of <project>/build.
gradle.lifecycle.beforeProject {
    layout.buildDirectory = File("C:/maya-build/${path.removePrefix(":").replace(':', '/').ifEmpty { "root" }}")
}
