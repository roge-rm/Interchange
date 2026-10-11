import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// What players and servers send each other: commands stamped with the tick
// they run on, joins, and the world's checksum. Sockets go in jvmMain.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":sim"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
