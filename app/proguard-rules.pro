# Keep network protocol classes
-keep class org.apache.sshd.** { *; }
-keep class org.apache.commons.net.** { *; }
-keep class com.hierynomus.** { *; }
-keep class com.thegrizzlylabs.sardineandroid.** { *; }
-keep class net.schmizz.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class org.slf4j.** { *; }
-keep class com.github.thegrizzlylabs.** { *; }

# Don't warn about missing optional dependencies
-dontwarn org.apache.sshd.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn org.ietf.jgss.**
-dontwarn com.sun.**
-dontwarn sun.**
-dontwarn javax.security.**

# Keep Room entities
-keep class com.voyagerfiles.data.model.** { *; }

# Standard Android/Kotlin rules
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception
