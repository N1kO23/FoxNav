package app.nullpointr.foxnav.shared.search

import app.nullpointr.foxnav.shared.FoxNavJson
import app.nullpointr.foxnav.shared.geo.GeoPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A region's search pack (protocol/SPEC.md §3.4): its places, streets, addresses and points of
 * interest in cells of [size] degrees, for searching without a connection. Cells are numbered like
 * Valhalla's local-road tiles, so a phone takes the cells it has road data for.
 */
@Serializable
data class SearchPackManifest(
    val format: Int,
    val region: String,
    val build: String,
    val generated: String? = null,
    val attribution: String? = null,
    val size: Double = CELL_SIZE,
    /** `[cell, entries, download bytes]`, by cell. */
    val cells: List<List<Long>> = emptyList(),
) {
    @Transient
    private val downloads: Map<Long, Long> = cells.filter { it.size >= 3 }.associate { it[0] to it[2] }

    @Transient
    private val entries: Map<Long, Long> = cells.filter { it.size >= 3 }.associate { it[0] to it[1] }

    /** Whether this client can use the pack. */
    val isUsable: Boolean get() = format == FORMAT && size == CELL_SIZE

    /** What [cell] takes to download; null when the pack has nothing there. */
    fun downloadOf(cell: Long): Long? = downloads[cell]

    /**
     * Roughly what [cell] takes in a phone's full-text index, from its entries: an SQLite FTS4
     * index of the reference builder's cells takes about [INDEX_BYTES_PER_ENTRY] each, ten times
     * the download.
     */
    fun indexBytesOf(cell: Long): Long? = entries[cell]?.let { it * INDEX_BYTES_PER_ENTRY }

    /** Where to download [cell], gzipped, under the pack's directory [base]. */
    fun urlOf(base: String, cell: Long): String = "${base.trimEnd('/')}/$build/$cell.json.gz"

    companion object {
        const val FORMAT = 1
        const val MANIFEST = "manifest.json"

        /** The cells' size in degrees: Valhalla's local-road tiles. */
        const val CELL_SIZE = 0.25

        /** Measured over central Helsinki: 83,000 entries, 15.7 MB of index. */
        const val INDEX_BYTES_PER_ENTRY = 190L

        fun parse(json: String): SearchPackManifest = FoxNavJson.decodeFromString(serializer(), json)

        /** The cell [point] is in. */
        fun cellAt(point: GeoPoint): Long {
            val columns = (360 / CELL_SIZE).roundToInt()
            val row = floor((point.latitude.coerceIn(-90.0, 89.999999) + 90) / CELL_SIZE).toLong()
            val column = floor((point.longitude.coerceIn(-180.0, 179.999999) + 180) / CELL_SIZE).toLong()
            return row * columns + column
        }
    }
}

enum class SearchKind(val key: String) {
    PLACE("place"),
    STREET("street"),
    ADDRESS("address"),
    POI("poi");

    companion object {
        fun of(key: String): SearchKind? = entries.firstOrNull { it.key == key }
    }
}

/** One thing to find: a town, a street, an address or a point of interest. */
data class SearchEntry(
    val kind: SearchKind,
    /** As a result's first line: "Mannerheimintie 5", "Kamppi". */
    val title: String,
    /** Its second line: "Helsinki". */
    val detail: String,
    val latitude: Double,
    val longitude: Double,
    /** How notable it is, 0–1: towns and landmarks above street addresses. */
    val weight: Double,
    /** Other names it goes by, such as the Swedish one. */
    val aka: List<String> = emptyList(),
) {
    val point: GeoPoint get() = GeoPoint(latitude, longitude)

    fun toPlace() = Place(title = title, detail = detail, latitude = latitude, longitude = longitude)

    companion object {
        /** The entries of one cell's file: `{"format": 1, "entries": [[kind, title, detail, lat, lon, weight, names?], …]}`. */
        fun parseCell(json: String): List<SearchEntry> {
            val root = FoxNavJson.parseToJsonElement(json).jsonObject
            if (root["format"]?.jsonPrimitive?.content != SearchPackManifest.FORMAT.toString()) return emptyList()
            return root["entries"]?.jsonArray.orEmpty().mapNotNull { element ->
                runCatching {
                    val fields = element.jsonArray
                    SearchEntry(
                        kind = SearchKind.of(fields[0].jsonPrimitive.content) ?: return@mapNotNull null,
                        title = fields[1].jsonPrimitive.content,
                        detail = fields[2].jsonPrimitive.content,
                        latitude = fields[3].jsonPrimitive.doubleOrNull ?: return@mapNotNull null,
                        longitude = fields[4].jsonPrimitive.doubleOrNull ?: return@mapNotNull null,
                        weight = fields.getOrNull(5)?.jsonPrimitive?.doubleOrNull ?: 0.0,
                        aka = (fields.getOrNull(6) as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(),
                    )
                }.getOrNull()
            }
        }
    }
}
