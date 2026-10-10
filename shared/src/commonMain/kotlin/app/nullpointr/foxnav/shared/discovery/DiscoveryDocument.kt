package app.nullpointr.foxnav.shared.discovery

import app.nullpointr.foxnav.shared.FoxNavJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The document a FoxNav server publishes at [PATH]. It tells the app where each backend
 * service lives and what the server supports, so users only ever type a domain.
 *
 * URLs may be relative. Clients resolve them against the URL the document was fetched from
 * (see [resolved]), so a server that puts everything behind one origin needs no public-URL
 * configuration. See `protocol/SPEC.md` for the full description.
 */
@Serializable
data class DiscoveryDocument(
    /** Version of this document's format. */
    @SerialName("foxnav") val version: Int,
    /** Server name shown in the app. */
    val name: String,
    /** Privacy policy of whoever runs this server. */
    val privacy: String? = null,
    /** WebSocket endpoint for convoys and live reports. Absent until the server supports them. */
    val realtime: String? = null,
    /** MapLibre style URLs keyed by theme, e.g. `night` and `day`. */
    val styles: Map<String, String> = emptyMap(),
    val routing: ServiceEndpoint? = null,
    val search: ServiceEndpoint? = null,
    /** Optional features the server implements, see [Feature]. */
    val features: Set<String> = emptySet(),
    /** ISO 3166-1 alpha-2 codes of the countries this server has map data for. */
    val regions: List<String> = emptyList(),
) {
    /**
     * Returns a copy with every URL made absolute against [documentUrl], the URL this document
     * was fetched from. A relative [realtime] URL gets a `ws`/`wss` scheme to match the
     * document's `http`/`https`.
     */
    fun resolved(documentUrl: String): DiscoveryDocument = copy(
        privacy = privacy?.let { resolveUrl(documentUrl, it) },
        realtime = realtime?.let { toWebSocketUrl(resolveUrl(documentUrl, it)) },
        styles = styles.mapValues { (_, url) -> resolveUrl(documentUrl, url) },
        routing = routing?.let { it.copy(url = resolveUrl(documentUrl, it.url)) },
        search = search?.let { it.copy(url = resolveUrl(documentUrl, it.url)) },
    )

    fun toJson(): String = FoxNavJson.encodeToString(serializer(), this)

    companion object {
        /** Where servers publish the document. */
        const val PATH = "/.well-known/foxnav"

        /** The format version this code writes. */
        const val CURRENT_VERSION = 1

        fun parse(json: String): DiscoveryDocument = FoxNavJson.decodeFromString(serializer(), json)
    }
}

@Serializable
data class ServiceEndpoint(
    /** API flavour, e.g. `valhalla` for routing or `photon` for search. */
    val type: String,
    val url: String,
)

/** Values for [DiscoveryDocument.features]. */
object Feature {
    const val CONVOY = "convoy"
    const val REPORTS = "reports"
    const val DATA_PACKS = "datapacks"
}
