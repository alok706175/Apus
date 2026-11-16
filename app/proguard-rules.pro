# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in defaultProguardFile("proguard-android-optimize.txt")

# PDFBox Android rules
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**

# Coil rules
-keepclassmembers class * extends coil.request.ImageRequest { *; }
-dontwarn coil.**

# Coroutines rules
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ML Kit rules
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class com.google.android.gms.vision.** { *; }

# Security Crypto & Android KeyStore
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# Compose & Lifecycle
-keep class androidx.compose.** { *; }
