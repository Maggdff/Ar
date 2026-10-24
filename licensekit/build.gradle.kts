plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
fun certSha256(): String {
    val f = rootProject.file("keystore/uniatt.jks")
    val pass = (System.getenv("UNIATT_KS_PASS")?.takeIf { it.isNotBlank() } ?: "uniatt-2026").toCharArray()
    val ks = java.security.KeyStore.getInstance(f, pass)
    val cert = ks.getCertificate(System.getenv("UNIATT_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "uniatt")
    return java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
}
android {
    namespace = "com.uniatt.licensekit"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    defaultConfig {
        // بصمة SHA-256 لشهادة التوقيع تُحسب وقت البناء من نفس المفتاح الذي يوقّع التطبيقات
        buildConfigField("String", "CERT_SHA256", "\"" + certSha256() + "\"")
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}
dependencies {
    api(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
