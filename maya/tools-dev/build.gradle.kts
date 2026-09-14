plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.amayra.maya.tools.dev"
    compileSdk = 37
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":core-agent"))
}
