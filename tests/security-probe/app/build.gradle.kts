plugins { id("com.android.application") }
android {
    namespace = "local.securityprobe"
    compileSdk = 37
    defaultConfig { applicationId = "local.securityprobe"; minSdk = 26; targetSdk = 37; versionCode = 1; versionName = "1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { implementation("androidx.media3:media3-session:1.11.1") }
