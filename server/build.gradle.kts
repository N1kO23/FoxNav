import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream

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
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":protocol:conformance"))
}

// The web convoy page (web/) and the browser libraries it uses, pinned and checked against npm's
// integrity hashes. They're downloaded at build time, not kept in the repository.
val webVendor = listOf(
    WebVendor(
        "https://registry.npmjs.org/maplibre-gl/-/maplibre-gl-6.11.2.tgz",
        "sha512-Xh06pxoipjX/Ad1sUPGhiNh9go9naCNGZZ1IJm3sIeqK1kYGuMaMDSTIGDQ2igOLfVyukJU7kvEOA089VO7Z7g==",
        mapOf(
            "dist/maplibre-gl.mjs" to "maplibre-gl.mjs",
            "dist/maplibre-gl-shared.mjs" to "maplibre-gl-shared.mjs",
            "dist/maplibre-gl-worker.mjs" to "maplibre-gl-worker.mjs",
            "dist/maplibre-gl.css" to "maplibre-gl.css",
            "LICENSE.txt" to "maplibre-gl-LICENSE.txt",
        ),
    ),
    WebVendor(
        "https://registry.npmjs.org/pmtiles/-/pmtiles-4.5.0.tgz",
        "sha512-CBeD4SoUluFziGdy/8k7FOjQxQy486n+929W/tWophauvMICpkZ26vGBWhDt/6b1FZQWNaf4jFdT/5+q0Wii5w==",
        mapOf("dist/pmtiles.js" to "pmtiles.js"),
    ),
)

val fetchWebVendor by tasks.registering {
    val output = layout.buildDirectory.dir("web-vendor")
    val packages = webVendor
    inputs.property("packages", packages.map { it.url + it.integrity + it.files })
    outputs.dir(output)
    doLast {
        val target = output.get().asFile.apply { deleteRecursively(); mkdirs() }
        for (vendor in packages) {
            val tarball = URI(vendor.url).toURL().openStream().use { it.readBytes() }
            val digest = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(tarball))
            check("sha512-$digest" == vendor.integrity) { "${vendor.url} doesn't match its pinned integrity hash" }
            val tar = GZIPInputStream(tarball.inputStream()).use { it.readBytes() }
            // A tar file: 512-byte headers (name at 0, size in octal at 124, ustar prefix at 345), then the data in 512-byte blocks.
            var at = 0
            while (at + 512 <= tar.size && tar[at] != 0.toByte()) {
                fun text(offset: Int, length: Int) = String(tar, at + offset, length, Charsets.UTF_8).substringBefore('\u0000').trim()
                val prefix = if (text(257, 5) == "ustar") text(345, 155) else ""
                val name = if (prefix.isEmpty()) text(0, 100) else "$prefix/${text(0, 100)}"
                val size = text(124, 12).ifEmpty { "0" }.toLong(8).toInt()
                vendor.files[name.removePrefix("package/")]?.let { file -> target.resolve(file).writeBytes(tar.copyOfRange(at + 512, at + 512 + size)) }
                at += 512 + (size + 511) / 512 * 512
            }
            val missing = vendor.files.values.filterNot { target.resolve(it).exists() }
            check(missing.isEmpty()) { "${vendor.url} lacks $missing" }
        }
    }
}

tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("web")) {
        into("web")
        include("join.html", "join.css", "js/**")
    }
    from(fetchWebVendor) { into("web/vendor") }
}

// `./gradlew :server:run` serves the map styles from infra/ (map data comes from the Docker stack).
tasks.named<JavaExec>("run") {
    environment("FOXNAV_NAME", "FoxNav (dev)")
    environment("FOXNAV_STYLES_DIR", rootProject.file("infra/styles").absolutePath)
}

/** An npm package to serve: its tarball, npm's integrity hash, and file paths in it -> served names. */
data class WebVendor(val url: String, val integrity: String, val files: Map<String, String>)
