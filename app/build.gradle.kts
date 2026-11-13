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
}
