# Ferry release build (R8). Most libraries ship their own consumer rules (Room, OkHttp, Media3,
# Coil, kotlinx.coroutines); these cover the rest.

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization: Ferry's own @Serializable DTOs (web API, backup, sync) and the generated serializers.
-keepclassmembers class dev.sysmoon.ferry.** {
    *** Companion;
}
-keepclasseswithmembers class dev.sysmoon.ferry.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.sysmoon.ferry.**$$serializer { *; }
-dontnote kotlinx.serialization.**
-dontwarn kotlinx.serialization.**

# Ktor server (CIO) uses reflection for plugins and routing.
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn javax.**
-dontwarn kotlinx.coroutines.debug.**

# HiveMQ MQTT client (Netty + RxJava + JCTools underneath). JCTools' queues look their own
# fields up by name in static initialisers, so nothing in there may be renamed or stripped.
-keep class com.hivemq.client.** { *; }
-keep class io.netty.** { *; }
-keep class org.jctools.** { *; }
-keep class io.reactivex.** { *; }
-keepclassmembers class * extends io.netty.util.Recycler$Handle { *; }
-dontwarn io.netty.**
-dontwarn io.reactivex.**
-dontwarn org.jctools.**
-dontwarn reactor.blockhound.**
-dontwarn com.google.protobuf.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn com.aayushatharva.**
-dontwarn com.github.luben.**
-dontwarn com.jcraft.**
-dontwarn com.ning.**
-dontwarn lzma.**
-dontwarn net.jpountz.**
-dontwarn org.apache.**
-dontwarn org.eclipse.**
-dontwarn org.jboss.**
-dontwarn sun.**
-dontwarn org.dataloader.**

# Compose runtime keeps what it needs; nothing else in Ferry is reached by reflection
# (BroadcastReceivers, the Service and the Activity are kept via the manifest).
