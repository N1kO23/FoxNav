plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    applicationName = "foxnav-conformance"
    mainClass = "app.nullpointr.foxnav.conformance.MainKt"
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.ktor.client.cio)
    runtimeOnly(libs.slf4j.nop)
}

// `./gradlew :protocol:conformance:run --args="https://maps.example.org"`
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
