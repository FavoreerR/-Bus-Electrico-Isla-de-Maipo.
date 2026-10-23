plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "cl.islademaipo.buselectrico"
 compileSdk = 35
 defaultConfig { applicationId = "cl.islademaipo.buselectrico"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "1.0" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation("androidx.core:core-ktx:1.15.0")
 implementation("androidx.activity:activity-ktx:1.9.3")
 implementation("com.google.android.material:material:1.12.0")
 implementation("org.osmdroid:osmdroid-android:6.1.20")
 implementation("com.google.android.gms:play-services-location:21.3.0")
}
