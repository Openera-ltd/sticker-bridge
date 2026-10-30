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

# Kotlin coroutines find Android's main thread through META-INF/services files that name these
# classes. Renaming them breaks the lookup and the app crashes on launch (1.0.5).
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory { <init>(); }
-keep class kotlinx.coroutines.android.AndroidExceptionPreHandler { <init>(); }

# Release hardening: scrambled names, everything moved into one short package, aggressive optimisation.
# Line numbers stay; the mapping file (build/outputs/mapping/liveRelease/mapping.txt) is kept privately
# to decode Sentry crash reports.
-repackageclasses 'o'
-allowaccessmodification
-keepattributes LineNumberTable
# Don't use -renamesourcefileattribute: with Kotlin coroutines it produced an invalid dex
# (out-of-order annotation values) that Android refused to load (1.0.5 and 1.0.6).

# No logging in release builds.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
}
