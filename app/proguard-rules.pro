# kotlinx.serialization: keep generated serializers for @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit service interfaces are proxied reflectively.
-keep,allowobfuscation,allowshrinking interface ro.davidmontei.loom.romm.api.** { *; }
-keepattributes Signature, Exceptions

# commons-compress optional codecs we don't ship.
-dontwarn org.brotli.dec.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.objectweb.asm.**
