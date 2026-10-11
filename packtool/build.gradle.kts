import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Checks content packs and zips them up, on any computer with Java.
// ./gradlew :packtool:installDist, then packtool/build/install/packtool/bin/packtool check my-pack
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_11 }
}

application {
    mainClass.set("com.rm.interchange.packtool.MainKt")
    applicationName = "packtool"
}

dependencies {
    implementation(project(":sim"))
}
