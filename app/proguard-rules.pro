# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.weelay.app.**$$serializer { *; }
-keepclassmembers class com.weelay.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.weelay.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Java-WebSocket pulls in slf4j-api, which has no Android binding
-dontwarn org.slf4j.**

# Tink (via security-crypto) references compile-only errorprone annotations
-dontwarn com.google.errorprone.annotations.**
