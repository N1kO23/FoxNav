import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // JVM only for now: both the server and the Android app consume the JVM variant.
    // iOS targets come with the iOS app (building them needs macOS).
    jvm {
        compilerOptions {
            // The Android app consumes this module too, so stay on Java 17 bytecode.
            jvmTarget = JvmTarget.JVM_17
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
