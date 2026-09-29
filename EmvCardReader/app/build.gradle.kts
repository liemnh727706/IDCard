import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Đọc API key từ local.properties (file này KHÔNG lên git - xem .gitignore) thay vì hardcode
// thẳng vào mã nguồn, để key không bị lộ công khai trên GitHub hay trong APK giải mã ngược.
// Thêm dòng sau vào local.properties (cùng cấp với sdk.dir):
//   faceid.apiKey=<key production của cropnlu.duckdns.org>
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val faceIdApiKey: String = localProps.getProperty("faceid.apiKey", "")

android {
    namespace = "vn.edu.hcmuaf.nlu.emvreader"
    compileSdk = 34

    defaultConfig {
        applicationId = "vn.edu.hcmuaf.nlu.emvreader"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        buildConfigField("String", "FACEID_API_KEY", "\"$faceIdApiKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation("junit:junit:4.13.2")
}
