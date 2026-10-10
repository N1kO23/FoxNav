package app.nullpointr.foxnav.server

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

/** Server settings, read from `FOXNAV_*` environment variables (see `infra/compose.yaml`). */
data class ServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    /** Server name shown in the app. */
    val name: String = "FoxNav",
    val privacyUrl: String? = null,
    /** Routing (Valhalla) URL as published in the discovery document; may be relative. */
    val routingUrl: String = "/valhalla",
    /** Search (Photon) URL as published in the discovery document; may be relative. */
    val searchUrl: String = "/photon",
    /** Directory of MapLibre style templates (`<theme>.json`), see [StyleTemplates]. */
    val stylesDir: Path? = null,
    /** ISO 3166-1 alpha-2 codes of the countries this server has map data for. */
    val regions: List<String> = emptyList(),
    /** Where the data packs are published (spec §3), e.g. `/datapacks`; none if null. */
    val datapacksUrl: String? = null,
    /** Members per convoy. */
    val maxConvoySize: Int = 20,
) {
    /** Theme names of the available style templates, e.g. `day` and `night`. */
    fun styleNames(): List<String> = stylesDir
        ?.takeIf { it.isDirectory() }
        ?.listDirectoryEntries()
        ?.filter { it.isRegularFile() && it.extension == "json" && StyleTemplates.isValidName(it.nameWithoutExtension) }
        ?.map { it.nameWithoutExtension }
        ?.sorted()
        .orEmpty()

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): ServerConfig {
            val defaults = ServerConfig()
            return ServerConfig(
                host = env["FOXNAV_HOST"] ?: defaults.host,
                port = env["FOXNAV_PORT"]?.toInt() ?: defaults.port,
                name = env["FOXNAV_NAME"] ?: defaults.name,
                privacyUrl = env["FOXNAV_PRIVACY_URL"],
                routingUrl = env["FOXNAV_ROUTING_URL"] ?: defaults.routingUrl,
                searchUrl = env["FOXNAV_SEARCH_URL"] ?: defaults.searchUrl,
                stylesDir = env["FOXNAV_STYLES_DIR"]?.let { Path(it) },
                regions = env["FOXNAV_REGIONS"]?.split(',')?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() }
                    ?: defaults.regions,
                maxConvoySize = env["FOXNAV_MAX_CONVOY_SIZE"]?.toInt() ?: defaults.maxConvoySize,
                datapacksUrl = env["FOXNAV_DATAPACKS_URL"]?.takeIf { it.isNotBlank() },
            )
        }
    }
}
