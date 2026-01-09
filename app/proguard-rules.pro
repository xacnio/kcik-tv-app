# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Keep Gson models
-keepattributes Signature
-keepattributes *Annotation*
-keep class dev.xacnio.kciktv.data.model.** { *; }

# Keep Retrofit
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }

# Keep OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Keep ExoPlayer
-keep class androidx.media3.** { *; }
