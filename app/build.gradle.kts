plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nethax.ultimate"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nethax.ultimate"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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
    // Existing WebView support
    implementation("androidx.webkit:webkit:1.14.0")

    // ML Kit Text Recognition (OCR)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // ML Kit Language Identification
    implementation("com.google.mlkit:language-id:17.0.6")
}
