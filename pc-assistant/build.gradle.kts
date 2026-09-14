plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `application`
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.amayra.pc.MainKt")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.vosk)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
