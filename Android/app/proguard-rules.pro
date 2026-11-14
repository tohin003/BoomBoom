# What the release build's shrinker must leave alone.

# The engine's Kotlin face: the native library (core/, Rust) binds these classes' methods by their names, and
# makes and fills some of these objects itself.
-keep class io.github.boomboom.engine.** { *; }

# The data client's models are written to and read from saved files by their field names (the queue, downloads,
# presets); kotlinx.serialization's own rules keep the generated serializers.
-keepattributes *Annotation*, InnerClasses, Signature
-keep,includedescriptorclasses class io.github.boomboom.**$$serializer { *; }
-keepclassmembers class io.github.boomboom.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.boomboom.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# The bridge the page script posts its messages to (WebViews without message listeners call it by name).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Stack traces in the app's own log stay readable.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
