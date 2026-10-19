import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.wire)
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
            api(libs.kotlinx.coroutines.core)
            api(libs.wire.runtime)
            // The convoy client; consumers add an engine (OkHttp on Android, CIO for tools) and
            // install the WebSockets plugin.
            api(libs.ktor.client.core)
            api(libs.ktor.client.websockets)
        }
        jvmMain.dependencies {
            // Convoy frames' signatures and key exchange (Crypto.jvm.kt).
            implementation(libs.bouncycastle)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// Kotlin types for the realtime protocol, generated from the spec's .proto files.
wire {
    sourcePath {
        srcDir("../protocol/proto")
    }
    kotlin {}
}
