import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The world and everything that happens in it. Plain Kotlin with no UI or
// platform code, so the whole simulation can be tested on the JVM:
// ./gradlew :sim:jvmTest
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
    }

    sourceSets {
        // The base pack's data goes in as resources, base/manifest.json and base/vehicles.json.
        jvmMain {
            resources.srcDir(rootProject.file("packs"))
            resources.include("base/*.json")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
