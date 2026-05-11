# EncryptedSharedPreferences / Tink (optional compile-only annotations)
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**

# libbox / gomobile JNI
-keep class io.nekohasekai.libbox.** { *; }
-keepclassmembers class * { native <methods>; }

# Kotlin / serialization
-keepattributes *Annotation*, InnerClasses, EnclosingMethod
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata { public <methods>; }

-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
}
-keepclasseswithmembers class **$$serializer {
    *** INSTANCE;
}
-keepclassmembers class **$$serializer {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.nothing.mobilecore.shared.**$$serializer { *; }
-keep class com.nothing.mobilecore.shared.domain.** { *; }
-keep class com.nothing.mobilecore.shared.application.** { *; }
