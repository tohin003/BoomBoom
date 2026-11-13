// The data client: the models and YouTube Music's web API, as a plain JVM library (no Android classes), so its tests
// run on the Mac in seconds. A port of Packages/BoomCore (BoomModels + InnerTube) and its tests.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // `api`: the public constructors take an OkHttpClient, the calls are suspend, the models are @Serializable.
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The recorded answers are the iPhone package's own files, used in place (never copied into this folder).
sourceSets.test {
    resources.srcDir("../../Packages/BoomCore/Tests/InnerTubeTests/Fixtures")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "512m"
    // The live tests only run with BOOM_LIVE=1; a change of it must not be answered with "up to date".
    inputs.property("boomLive", providers.environmentVariable("BOOM_LIVE").orElse(""))
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
