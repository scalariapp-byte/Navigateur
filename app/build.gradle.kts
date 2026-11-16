plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "app.navigateur"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.navigateur"; minSdk = 26; targetSdk = 34; versionCode = 2; versionName = "2.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("org.mozilla.geckoview:geckoview-arm64-v8a:[140,148)")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.core:core:1.13.1")
}
