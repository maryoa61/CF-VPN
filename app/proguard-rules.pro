# Add project specific ProGuard rules here.

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Moshi
-keep class com.squareup.moshi.** { *; }
-keep class com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory { *; }
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}

# VPN data classes
-keep class com.example.data.VpnConfig { *; }
-keep class com.example.data.VpnConfigDao { *; }
-keep class com.example.data.VpnDatabase { *; }

# Xray/V2Ray JNI reflection — all AAR versions
-keep class libv2ray.** { *; }
-keep class io.coreny.v2ray.Libv2ray { *; }
-keep class io.coreny.Libv2ray { *; }
-keep class xray.lib.Xray { *; }
-keep class xray.lib.Libv2ray { *; }

# Go gobind runtime (used by libv2ray.aar v26+)
-keep class go.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keep class okhttp3.** { *; }
-keep class retrofit2.** { *; }

# General
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
