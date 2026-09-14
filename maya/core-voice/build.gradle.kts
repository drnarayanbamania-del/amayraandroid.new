plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.amayra.maya.voice"
    compileSdk = 37
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":core-agent"))
    implementation(project(":tools-android"))
    implementation(libs.tensorflow.lite)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    // Offline neural TTS (Piper VITS Hindi voice) — bundled AAR, no Maven.
    implementation(files("libs/sherpa-onnx.aar"))

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
