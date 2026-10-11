import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The dedicated server: the same server a phone hosts with, run on its own.
// ./gradlew :dedicated:installDist, or docker compose up --build
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_21 }
}

application {
    mainClass.set("com.rm.interchange.dedicated.MainKt")
    applicationName = "interchange-server"
}

dependencies {
    implementation(project(":server"))
    implementation(libs.kotlinx.coroutines.core)
}
