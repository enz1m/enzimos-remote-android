# enzremote ProGuard/R8 rules

-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# WebRTC looks up this JNI Zero entry point by name during native library loading.
-keep class org.jni_zero.JniZero {
    private static java.lang.Object[] init();
}

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep protocol data classes (they use @Serializable)
-keep,includedescriptorclasses class com.enzimos.remote.signaling.**$$serializer { *; }
-keepclassmembers class com.enzimos.remote.signaling.** {
    *** Companion;
    *** serializer(...);
}

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep ML Kit barcode scanning
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Keep CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# Keep pairing data classes
-keep,includedescriptorclasses class com.enzimos.remote.pairing.**$$serializer { *; }
-keepclassmembers class com.enzimos.remote.pairing.** {
    *** Companion;
    *** serializer(...);
}

# Keep EncryptedSharedPreferences
-keep class androidx.security.crypto.** { *; }

# Cached catalog in the connection manager (@Serializable)
-keep,includedescriptorclasses class com.enzimos.remote.connection.**$$serializer { *; }
-keepclassmembers class com.enzimos.remote.connection.** {
    *** Companion;
    *** serializer(...);
}
