pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
    // gradle/libs.versions.toml is auto-imported as the "libs" catalog on Gradle 9+.
}
rootProject.name = "maya"

// Keep build outputs off OneDrive (sync file locks break Gradle's incremental deletes).
// Build dirs live under C:\maya-build\<project> instead of <project>/build.
gradle.lifecycle.beforeProject {
    layout.buildDirectory = File("C:/maya-build/maya/${path.removePrefix(":").replace(':', '/').ifEmpty { "root" }}")
}
include(
    ":app", ":core-ai", ":core-voice", ":core-agent", ":core-license",
    ":core-security", ":core-data", ":feature-live2d",
    ":tools-android", ":tools-internet", ":tools-dev"
)
