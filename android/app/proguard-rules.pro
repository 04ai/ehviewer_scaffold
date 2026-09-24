# ─── ProGuard / R8 Rules for Eh-ru (ehviewer_scaffold) ─────────────────────────

# 1. Native & JNI Boundary (CRITICAL: preserve Rust binding methods and data classes)
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.example.ehviewer_scaffold.rust.** { *; }
-keepclassmembers class com.example.ehviewer_scaffold.rust.** { *; }

# 2. Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# 3. AndroidX Biometric & Security
-keep class androidx.biometric.** { *; }
-keepclassmembers class androidx.biometric.** { *; }

# 4. Coil 3 Image Loading & OkHttp
-dontwarn coil3.**
-dontwarn okhttp3.**
-dontwarn okio.**

# 5. Coroutines
-dontwarn kotlinx.coroutines.**
