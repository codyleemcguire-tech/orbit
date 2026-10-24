# Orbit uses no reflection-based serialisation (org.json, hand-written parsing), so the
# defaults are enough. Keep line numbers for readable crash traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# NuvioTV's Media3 engine and decoders (app/libs), as NuvioTV keeps them: the renderers,
# extractors and FFmpeg / AV1 / MPEG-H decoders are created by reflection or called from
# native code, so they're kept whole.
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
