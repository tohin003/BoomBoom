import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // The debug screen is Compose; the compiler plugin ships with Kotlin and must match its version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0"
}

// The key the released app is signed with lives outside the repository, on the Mac that makes releases. Without it
// a release build comes out unsigned (and a debug build is unaffected).
val signing = Properties().apply {
    val file = File(System.getProperty("user.home"), ".config/boomboom/keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

android {
    namespace = "io.github.boomboom"
    compileSdk = 36
    // The NDK the engine (core/) is built with; Gradle also strips its libraries with it.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "io.github.boomboom.android"
        // Android 8.1: the first whose own audio path (AAudio) is dependable; on 8.0 it was not.
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // The on-device tests drive the engine themselves, in a process without the app's own player (BareRunner).
        testInstrumentationRunner = "io.github.boomboom.BareRunner"
        // Only the processors the engine is built for: a library of some other kind (a dependency brings one for
        // 32-bit x86) must not make a phone think the whole app runs on it.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        if (signing.containsKey("storeFile")) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Shrunk and optimised: a third of the size, and the screens draw noticeably faster.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // The release build's own lint pass needs more memory than this project's builds are given.
    lint {
        checkReleaseBuilds = false
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

    buildFeatures {
        compose = true
    }

    // The script for the hidden tab and the ad rules are the iPhone app's own files: one source for both apps.
    sourceSets["main"].assets.srcDir("../../Packages/BoomEngine/Sources/BoomPlayer/Resources")
    // The Mac-side tests read the parts that need no device (the ad rules, the script's messages); android.jar's
    // stubs answer with defaults instead of throwing, for the log lines on the way.
    testOptions.unitTests.isReturnDefaultValues = true

    // The engine's on-device test plays the iPhone engine's own test tones, from where they are.
    sourceSets["androidTest"].assets.srcDir("../../Packages/BoomEngine/Tests/BoomAudioTests/Fixtures")
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":logic"))
    implementation(project(":innertube"))
    implementation("androidx.webkit:webkit:1.14.0")
    // FileProvider, for sharing a problem report.
    implementation("androidx.core:core-ktx:1.16.0")
    // The stores run on the main thread through Dispatchers.Main.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // The playback service: the session the system draws the notification and lock-screen controls from.
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.media3:media3-common:1.8.0")
    // The window that holds the screens (and, in debug builds, the plain test screen).
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub; the tests use the real one.
    testImplementation("org.json:json:20250517")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// The engine: core/ (Rust) and the shared C it compiles in, built with cargo-ndk for the three ABIs into
// src/main/jniLibs (not in git). Gradle skips it when none of its inputs changed since the last build.
val coreDir = rootProject.file("core")
val sharedC = rootProject.file("../Packages/BoomEngine/Sources/CBoomAudio")
val jniLibsDir = file("src/main/jniLibs")
val buildCore by tasks.registering(Exec::class) {
    description = "Builds libboomcore.so (core/) for arm64-v8a, armeabi-v7a and x86_64 with cargo-ndk."
    group = "build"
    inputs.dir(coreDir.resolve("src"))
    inputs.dir(coreDir.resolve(".cargo"))
    inputs.files(coreDir.resolve("Cargo.toml"), coreDir.resolve("Cargo.lock"), coreDir.resolve("build.rs"))
    inputs.dir(sharedC)
    outputs.dir(jniLibsDir)

    val home = System.getProperty("user.home")
    val sdk = android.sdkDirectory
    val ndk = android.ndkDirectory
    val cmakeBin = sdk.resolve("cmake/3.22.1/bin")
    workingDir = coreDir
    environment("ANDROID_HOME", sdk.absolutePath)
    environment("ANDROID_NDK_HOME", ndk.absolutePath)
    // libopus is built with CMake through the NDK's toolchain file, for API 26 like the app.
    environment("ANDROID_PLATFORM", "android-26")
    environment("CMAKE", cmakeBin.resolve("cmake").absolutePath)
    environment("PATH", "${cmakeBin.absolutePath}:$home/.cargo/bin:${System.getenv("PATH")}")
    commandLine(
        "$home/.cargo/bin/cargo", "ndk",
        "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64",
        "--platform", "26",
        "-o", jniLibsDir.absolutePath,
        "build", "--release",
    )
}

tasks.named("preBuild") { dependsOn(buildCore) }
