# Keep network protocol classes
-keep class org.apache.sshd.** { *; }
-keep class org.apache.commons.net.** { *; }
-keep class com.hierynomus.** { *; }
-keep class com.thegrizzlylabs.sardineandroid.** { *; }

# Keep Room entities
-keep class com.voyagerfiles.data.model.** { *; }

# Standard Android/Kotlin rules
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception
