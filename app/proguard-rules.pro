# Ponts JavaScript du lecteur
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
# Jsoup (optionnel : re2j)
-dontwarn com.google.re2j.**
-dontwarn org.jspecify.**
# Google ML Kit (traduction, détection de langue)
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.gms.tasks.** { *; }
