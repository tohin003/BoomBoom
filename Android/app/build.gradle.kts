plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.boomboom"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.boomboom.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // The script for the hidden tab and the ad rules are the iPhone app's own files: one source for both apps.
    sourceSets["main"].assets.srcDir("../../Packages/BoomEngine/Sources/BoomPlayer/Resources")
}

dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
}
