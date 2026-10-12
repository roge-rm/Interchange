plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.rm.interchange"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.rm.interchange"
        minSdk = 27
        targetSdk = 37
        versionCode = 12
        versionName = "0.8.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}
