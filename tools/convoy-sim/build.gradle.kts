plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    applicationName = "convoy-sim"
    mainClass = "app.nullpointr.foxnav.sim.MainKt"
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.ktor.client.cio)
    // Ktor logs through SLF4J; the simulator prints what matters itself.
    runtimeOnly(libs.slf4j.nop)

    testImplementation(kotlin("test"))
}

// `./gradlew :tools:convoy-sim:run --args="..."` runs from the repository root, so relative paths work as typed.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}
