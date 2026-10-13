// The map renderer, the screens and the input. The Android app (:app) depends
// on this. It's multiplatform so a desktop or browser build can share it later.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    android {
        namespace = "com.rm.interchange.shared"
        compileSdk = 37
        minSdk = 27
        androidResources { enable = true }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":server"))
            implementation(libs.jb.compose.runtime)
            implementation(libs.jb.compose.foundation)
            implementation(libs.jb.compose.ui)
            implementation(libs.jb.compose.material3)
            implementation(libs.jb.compose.resources)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

compose.resources {
    packageOfResClass = "com.rm.interchange.res"
    publicResClass = false
}

// The resource copy only adds and replaces files, so art taken out of composeResources would stay in the APK. Start
// each copy from an empty folder.
tasks.matching { it.name == "copyAndroidMainComposeResourcesToAndroidAssets" }.configureEach {
    doFirst { outputs.files.forEach { it.deleteRecursively() } }
}
