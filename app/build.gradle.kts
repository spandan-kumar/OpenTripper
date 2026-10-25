plugins {
    id("com.android.application")
    kotlin("android")
}
android {
    namespace = "io.opentripper"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.opentripper"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-experimental"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation(project(":core")) }
