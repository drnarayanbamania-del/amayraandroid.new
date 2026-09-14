import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.amayra.maya"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.amayra.maya"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
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
    buildFeatures {
        compose = true
    }
    testOptions {
        // android.util.Log becomes a no-op in JVM unit tests (TTS client failure paths log).
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

// Regenerate the live preview dashboard after every build or test run.
// Uses the project-root script (stdlib Python); never fails the build.
val generateDashboard by tasks.registering {
    doLast {
        try {
            val projectDir = rootProject.projectDir.absolutePath
            // Prefer a real interpreter; fall back to the Windows launcher.
            // Note: bare `python` hits the Microsoft Store stub on this machine.
            val interpreters = listOf(
                "C:/Users/drnar/AppData/Local/Python/bin/python.exe", // local install (first found wins)
                "python3",
                "py",
            )
            var exit = -1
            for (py in interpreters) {
                if (py.startsWith("C:/") && !File(py).exists()) continue
                try {
                    val proc = ProcessBuilder(py, "scripts/gen_dashboard.py")
                        .directory(File(projectDir))
                        .redirectErrorStream(true)
                        .start()
                    proc.inputStream.readBytes() // drain
                    exit = proc.waitFor()
                    if (exit == 0) break
                } catch (e: java.io.IOException) {
                    exit = -1 // interpreter not usable, try the next
                }
            }
            if (exit != 0) logger.lifecycle("dashboard regeneration failed (exit $exit) — run py scripts/gen_dashboard.py manually")
        } catch (t: Throwable) {
            logger.lifecycle("dashboard regeneration skipped: ${t.message}")
        }
    }
}
tasks.matching { it.name in setOf("assembleDebug", "testDebugUnitTest") }.configureEach {
    finalizedBy(generateDashboard)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.tensorflow.lite)
    implementation(libs.mlkit.barcode)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
