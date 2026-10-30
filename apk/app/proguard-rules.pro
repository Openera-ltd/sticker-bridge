# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class tech.openera.stickerbridge.**$$serializer { *; }

# WhatsApp reads our sticker provider by class name from the manifest.
-keep class tech.openera.stickerbridge.whatsapp.StickerContentProvider

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Release hardening: scrambled names, everything moved into one short package, aggressive optimisation.
# Line numbers stay; the mapping file (build/outputs/mapping/liveRelease/mapping.txt) is kept privately
# to decode Sentry crash reports.
-repackageclasses 'o'
-allowaccessmodification
-overloadaggressively
-keepattributes LineNumberTable
-renamesourcefileattribute s

# No logging in release builds.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
}
