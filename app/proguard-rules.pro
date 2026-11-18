# Ponts JavaScript du lecteur
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
# Jsoup (optionnel : re2j)
-dontwarn com.google.re2j.**
-dontwarn org.jspecify.**
# Noms lisibles dans les rapports de plantage (le code reste optimisé et réduit)
-dontobfuscate
