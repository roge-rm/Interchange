pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Interchange"
// The world and its simulation, with no UI. Deterministic, so every player in
// a game keeps the same world from the same commands. Its tests run on the JVM.
include(":sim")
// The messages players and servers send each other.
include(":net")
// The game server, used by a phone hosting a game and by the dedicated server.
include(":server")
// The dedicated server, run on its own or with docker compose.
include(":dedicated")

// The Docker build has no Android SDK, so it builds the server modules only.
val hasAndroidSdk = file("local.properties").exists() ||
    System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null

if (hasAndroidSdk) {
    // The map renderer and the UI.
    include(":shared")
    // The Android app.
    include(":app")
}
