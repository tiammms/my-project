# Vosk calls native code through JNA, which uses reflection: keep both untouched.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
# Our own classes are small; keep names readable in crash reports
-keepattributes SourceFile,LineNumberTable
# Release builds: drop verbose/debug logging entirely (info/warning logs contain no numbers, SMS text or audio).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
