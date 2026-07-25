# Keep network protocol classes
-keep class com.jcraft.jsch.** { *; }
-keep class org.apache.commons.net.** { *; }
-keep class com.hierynomus.** { *; }
-keep class com.thegrizzlylabs.sardineandroid.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class org.slf4j.** { *; }
-keep class com.github.thegrizzlylabs.** { *; }

# SMB/smbj dependencies
-keep class com.hierynomus.smbj.** { *; }
-keep class com.hierynomus.mssmb2.** { *; }
-keep class com.hierynomus.msdtyp.** { *; }
-keep class com.hierynomus.ntlm.** { *; }
-keep class com.hierynomus.asn1.** { *; }
-keep class com.hierynomus.protocol.** { *; }
-keep class com.hierynomus.security.** { *; }
-keep class com.hierynomus.spnego.** { *; }
-keep class dcerpc.** { *; }
-keep class net.engio.** { *; }

# Keep ServiceLoader implementations
-keepnames class * implements java.security.Provider
-keep class * extends java.security.Provider { *; }
-keep class * implements java.nio.file.spi.FileSystemProvider

# Sardine/OkHttp/WebDAV
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class org.xmlpull.** { *; }
-keepclassmembers class * {
    @org.simpleframework.xml.* *;
}

# Don't warn about missing optional dependencies
-dontwarn org.bouncycastle.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn org.newsclub.net.unix.**
-dontwarn javax.annotation.**
-dontwarn org.ietf.jgss.**
-dontwarn com.sun.**
-dontwarn sun.**
-dontwarn javax.security.**
-dontwarn org.apache.avalon.**
-dontwarn org.apache.log.**
-dontwarn org.apache.log4j.**
-dontwarn javax.servlet.**
-dontwarn javax.naming.**
-dontwarn javax.mail.**
-dontwarn java.beans.**
-dontwarn aQute.**
-dontwarn org.osgi.**
-dontwarn edu.umd.cs.findbugs.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn dalvik.system.**
-dontwarn javax.el.**
-dontwarn net.engio.mbassy.**

# Commons Compress optional XZ and Zstandard codecs are not enabled
-dontwarn com.github.luben.zstd.ZstdInputStream
-dontwarn org.tukaani.xz.MemoryLimitException
-dontwarn org.tukaani.xz.SingleXZInputStream
-dontwarn org.tukaani.xz.XZInputStream

# Keep Room entities
-keep class com.voyagerfiles.data.model.** { *; }
-keep class com.voyagerfiles.data.local.** { *; }

# Standard Android/Kotlin rules
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keep public class * extends java.lang.Exception

# Keep Kotlin metadata
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# Keep Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
