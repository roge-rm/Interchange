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
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
