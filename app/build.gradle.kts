plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "app.navigateur"
    compileSdk = 34
    defaultConfig { applicationId = "app.navigateur"; minSdk = 26; targetSdk = 34; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("androidx.webkit:webkit:1.12.1") }
