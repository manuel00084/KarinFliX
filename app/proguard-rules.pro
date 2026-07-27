# Karin Stream TV ProGuard Rules

# Keep R class (critical - prevents "Logcat" name and missing icon)
-keepclassmembers class **.R$* {
    public static <fields>;
}
-keep class **.R

# Keep all app classes (prevents R8 from stripping runtime-used utilities)
-keep class com.karin.streamtv.** { *; }

# ExoPlayer / Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Jsoup
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# Rhino JS Engine
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

# MoonGetter
-keep class io.github.darkryh.moongetter.** { *; }
-dontwarn io.github.darkryh.moongetter.**

# kotlinx.serialization
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# Sentry
-keep class io.sentry.** { *; }
-dontwarn io.sentry.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Keep enum values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Suppress missing class warnings
-dontwarn java.beans.**
-dontwarn javax.xml.**
-dontwarn org.w3c.**

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
