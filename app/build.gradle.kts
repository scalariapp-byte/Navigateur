plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
android {
    signingConfigs { create("hub") { storeFile = file("hub.keystore"); storePassword = "fanfichub"; keyAlias = "hub"; keyPassword = "fanfichub" } }
    namespace = "app.fanfichub"
    compileSdk = 36
    defaultConfig { applicationId = "app.fanfichub"; minSdk = 26; targetSdk = 34; versionCode = run; versionName = "1.$run" }
    buildTypes { getByName("debug") { signingConfig = signingConfigs.getByName("hub") } }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("org.jsoup:jsoup:1.18.3")
}
