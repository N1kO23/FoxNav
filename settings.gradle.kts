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
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "foxnav"

include(":shared", ":server")

// The Android app needs an Android SDK. It is only included when one is configured
// (local.properties `sdk.dir`, ANDROID_HOME or ANDROID_SDK_ROOT), so the server and
// shared code still build on machines without it, e.g. for people who only self-host.
val androidSdk: File? = run {
    val localProperties = file("local.properties")
    val fromLocalProperties = if (localProperties.isFile) {
        java.util.Properties()
            .apply { localProperties.inputStream().use { load(it) } }
            .getProperty("sdk.dir")
    } else {
        null
    }
    (fromLocalProperties ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT"))
        ?.let(::File)
        ?.takeIf { it.resolve("platforms").isDirectory || it.resolve("licenses").isDirectory }
}

if (androidSdk != null) {
    include(":android:app")
} else {
    logger.lifecycle("FoxNav: no Android SDK configured, skipping :android:app (see CONTRIBUTING.md)")
}
