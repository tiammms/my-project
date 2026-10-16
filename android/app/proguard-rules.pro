# Vosk calls native code through JNA, which uses reflection: keep both untouched.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
# Our own classes are small; keep names readable in crash reports
-keepattributes SourceFile,LineNumberTable
