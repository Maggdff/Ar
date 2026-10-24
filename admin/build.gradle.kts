plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.uniatt.admin"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.uniatt.admin"
        minSdk = 26
        targetSdk = 34
        versionCode = 100 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)   // يزداد مع كل بناء فيُثبَّت التحديث فوق القديم
        versionName = "1.0"
    }
    signingConfigs {
        // مفتاح توقيع ثابت داخل المستودع: يضمن أن كل APK جديد يُثبَّت فوق القديم بلا حذف التطبيق ولا فقد البيانات
        create("uniatt") {
            storeFile = rootProject.file("keystore/uniatt.jks")
            val pw = System.getenv("UNIATT_KS_PASS")?.takeIf { it.isNotBlank() } ?: "uniatt-2026"
            storePassword = pw; keyPassword = pw; keyAlias = System.getenv("UNIATT_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "uniatt"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("uniatt") }
        getByName("release") {
            signingConfig = signingConfigs.getByName("uniatt")
            isMinifyEnabled = true            // R8: تصغير وتعتيم أسماء الأصناف فيصعب فهم الشيفرة وبتر الفحوص
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation(project(":core"))
    implementation(project(":licensekit"))
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")   // قراءة نص ملفات PDF (استيراد كشوف المجموعات)
}
