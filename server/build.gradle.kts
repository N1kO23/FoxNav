plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    applicationName = "foxnav-server"
    mainClass = "app.nullpointr.foxnav.server.MainKt"
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
}

// `./gradlew :server:run` serves the map styles from infra/ (map data comes from the Docker stack).
tasks.named<JavaExec>("run") {
    environment("FOXNAV_NAME", "FoxNav (dev)")
    environment("FOXNAV_STYLES_DIR", rootProject.file("infra/styles").absolutePath)
}
