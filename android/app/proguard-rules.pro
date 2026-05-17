# Keep everything in our package (UI, ViewModel, Application).
-keep class com.beautifuldump.** { *; }

# libsu uses JNI + reflection internally.
-keep class com.topjohnwu.superuser.** { *; }
-keepclassmembers class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# Compose runtime relies on intrinsics that R8 sometimes over-aggressively prunes.
-dontwarn androidx.compose.**

# Coroutines: keep ServiceLoader-discovered factories so Main dispatcher works.
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory
-keep class kotlinx.coroutines.internal.MainDispatcherFactory

-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
