import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 compiles Kotlin itself (no kotlin-android plugin); Compose still needs its compiler plugin.
    alias(libs.plugins.kotlin.compose)
}

// On CI: "<run number>+<commit>", e.g. "57+3fa2c1d"; null on other machines.
val ciBuild: String? = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.let { run ->
    run + (providers.environmentVariable("GITHUB_SHA").orNull?.take(7)?.let { "+$it" } ?: "")
}

android {
    namespace = "app.nullpointr.foxnav"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.nullpointr.foxnav"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    // `foss` stays free of Google Play Services so it can ship on F-Droid; `play` is for the Play Store.
    flavorDimensions += "distribution"
    productFlavors {
        create("foss") { dimension = "distribution" }
        create("play") { dimension = "distribution" }
    }

    signingConfigs {
        // One debug key for everyone, checked in (debug keys are no secret), so dev builds from CI and
        // from any machine install over each other.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            // Dev builds install next to the store app. CI adds its run number and commit to the version.
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev" + (ciBuild?.let { ".$it" } ?: "")
            // The emulator reaches the host machine's localhost at 10.0.2.2 (see infra/README.md); used only
            // when running in the emulator. On a phone the app asks for a server.
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"http://10.0.2.2:8080\"")
        }
        release {
            // There is no official server yet; release builds will ask for one.
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        // Ferrostar needs newer java.* APIs than minSdk 26 has.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // A dependency ships Timber's lint rules; FoxNav logs with android.util.Log.
        disable += "LogNotTimber"
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":shared"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    // The convoy connection (the session lives in :shared), and invite QR codes: drawing and scanning them.
    implementation(libs.ktor.client.okhttp)
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.ferrostar.core)
    implementation(libs.ferrostar.ui.compose)
    implementation(libs.ferrostar.ui.maplibre)
    implementation(libs.ferrostar.ui.shared)
    implementation(libs.maplibre.compose)
    // Only the Play Store build may use Google Play Services (fused location).
    "playImplementation"(libs.ferrostar.google.play.services)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(kotlin("test-junit", libs.versions.kotlin.get()))
}
