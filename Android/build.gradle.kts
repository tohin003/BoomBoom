// BoomBoom for Android. The screens, the hidden WebView and the playback service are Kotlin (app/); the engine
// (stream parsing, buffering, decoding, the sound processor shared with the iPhone app) is native code (core/).
plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
}
