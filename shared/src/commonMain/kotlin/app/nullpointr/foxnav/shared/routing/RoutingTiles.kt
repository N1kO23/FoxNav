package app.nullpointr.foxnav.shared.routing

import app.nullpointr.foxnav.shared.FoxNavJson
import app.nullpointr.foxnav.shared.geo.GeoPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt

/** One tile of Valhalla's routing graph: its hierarchy [level] (0 motorways, 1 arterials, 2 local roads) and [id] in it. */
data class RoutingTile(val level: Int, val id: Long) {
    /** `level/id`, as stored. */
    val key: String get() = "$level/$id"

    companion object {
        fun parse(key: String): RoutingTile? {
            val (level, id) = key.split('/').takeIf { it.size == 2 } ?: return null
            return RoutingTile(level.toIntOrNull() ?: return null, id.toLongOrNull() ?: return null)
        }
    }
}

/** A tile's size on the phone and to download, in bytes. */
data class TileSize(val bytes: Long, val download: Long)

/** A level of the graph's hierarchy and the size of its tiles in degrees. */
@Serializable
data class TileLevel(val level: Int, val size: Double)

/**
 * The routing graph's tiles a server publishes for routing on the phone (protocol/SPEC.md §3.3):
 * which tiles there are in one [build], and which of them an area or a route needs.
 *
 * Tiles from different builds don't fit together, so a phone keeps the tiles of one build only.
 * Looking up the road under the car needs the local-road tiles (level 2) where the car is, even on a
 * motorway: Valhalla finds nearby roads through level 2's spatial index. Routes need level 2 at
 * either end, and the levels above it in between.
 */
@Serializable
data class RoutingTileManifest(
    val format: Int,
    val engine: String,
    /** The Valhalla version that built the tiles. */
    val version: String,
    val build: String,
    val generated: String? = null,
    val attribution: String? = null,
    val levels: List<TileLevel>,
    /** Per level: `[tile ID, bytes, download bytes]`, by ID. */
    val tiles: Map<String, List<List<Long>>> = emptyMap(),
) {
    @Transient
    private val sizes: Map<RoutingTile, TileSize> = tiles.flatMap { (level, entries) ->
        entries.filter { it.size >= 3 }.map { (id, bytes, download) -> RoutingTile(level.toInt(), id) to TileSize(bytes, download) }
    }.toMap()

    /** Every tile in the build. */
    val all: Set<RoutingTile> get() = sizes.keys

    /** The local roads' level, whose tiles road lookups need (2 in Valhalla's graph). */
    val localLevel: Int get() = levels.maxOf { it.level }

    /** The local-road tile at [point], whether or not the build has one. */
    fun localTileAt(point: GeoPoint): RoutingTile {
        val size = sizeOfLevel(localLevel)
        val (row, column) = cell(point, size)
        return RoutingTile(localLevel, row.toLong() * (360 / size).roundToInt() + column)
    }

    /** Whether this client can use the tiles. */
    val isUsable: Boolean get() = format == FORMAT && engine == "valhalla" && version.substringBefore('.') == "3"

    fun sizeOf(tile: RoutingTile): TileSize? = sizes[tile]

    /** What [tiles] take on the phone and to download; tiles the build doesn't have count nothing. */
    fun sizeOf(tiles: Collection<RoutingTile>): TileSize = tiles.mapNotNull(sizes::get).let { known ->
        TileSize(known.sumOf { it.bytes }, known.sumOf { it.download })
    }

    /** Valhalla's path for [tile] under its tile directory, e.g. `2/000/864/819.gph`. */
    fun pathOf(tile: RoutingTile): String {
        val size = sizeOfLevel(tile.level)
        val columns = (360 / size).roundToInt()
        val rows = (180 / size).roundToInt()
        // As many digits as the level's largest ID has, rounded up to whole groups of three.
        val digits = ((rows.toLong() * columns - 1).toString().length + 2) / 3 * 3
        val groups = tile.id.toString().padStart(digits, '0').chunked(3)
        return "${tile.level}/${groups.joinToString("/")}.gph"
    }

    /** Where to download [tile], gzipped, under the manifest's directory [base]. */
    fun urlOf(base: String, tile: RoutingTile): String = "${base.trimEnd('/')}/$build/${pathOf(tile)}.gz"

    /**
     * The tiles of every level that overlap the box from [southWest] to [northEast]. A box whose
     * west edge is east of its east edge crosses 180°, as a map zoomed out far enough reports.
     */
    fun tilesIn(southWest: GeoPoint, northEast: GeoPoint): Set<RoutingTile> = buildSet {
        for (level in levels) {
            val columns = (360 / level.size).roundToInt()
            val (firstRow, firstColumn) = cell(southWest, level.size)
            val (lastRow, lastColumn) = cell(northEast, level.size)
            val spans = if (southWest.longitude <= northEast.longitude) listOf(firstColumn..lastColumn) else listOf(firstColumn until columns, 0..lastColumn)
            for (row in firstRow..lastRow) for (span in spans) for (column in span) {
                RoutingTile(level.level, row.toLong() * columns + column).takeIf { it in sizes }?.let(::add)
            }
        }
    }

    /**
     * The tiles a drive along [line] needs: those within [marginMetres] of it on every level, so
     * the road lookup works all along and a detour or a reroute nearby still finds its roads.
     */
    fun tilesAlong(line: List<GeoPoint>, marginMetres: Double = 2_000.0): Set<RoutingTile> = buildSet {
        val points = line.zipWithNext { from, to ->
            // Points no further apart than the margin, so no stretch of the line slips between tiles.
            val steps = ceil(from.distanceTo(to) / marginMetres).toInt().coerceAtLeast(1)
            (0 until steps).map { step ->
                val t = step.toDouble() / steps
                GeoPoint(from.latitude + (to.latitude - from.latitude) * t, from.longitude + (to.longitude - from.longitude) * t)
            }
        }.flatten() + listOfNotNull(line.lastOrNull())
        for (point in points) {
            val dLat = marginMetres / METRES_PER_DEGREE
            val dLon = dLat / cos(point.latitude * PI / 180).coerceAtLeast(0.01)
            addAll(tilesIn(GeoPoint(point.latitude - dLat, point.longitude - dLon), GeoPoint(point.latitude + dLat, point.longitude + dLon)))
        }
    }

    private fun sizeOfLevel(level: Int): Double =
        levels.firstOrNull { it.level == level }?.size ?: throw IllegalArgumentException("No level $level in the manifest")

    private fun cell(point: GeoPoint, size: Double): Pair<Int, Int> {
        val rows = (180 / size).roundToInt()
        val columns = (360 / size).roundToInt()
        val row = floor((point.latitude.coerceIn(-90.0, 90.0) + 90) / size).toInt().coerceIn(0, rows - 1)
        val column = floor((point.longitude.coerceIn(-180.0, 180.0) + 180) / size).toInt().coerceIn(0, columns - 1)
        return row to column
    }

    companion object {
        /** The manifest format this code reads. */
        const val FORMAT = 1

        /** Under the discovery document's routing `tiles` URL. */
        const val MANIFEST = "manifest.json"

        private const val METRES_PER_DEGREE = 111_320.0

        fun parse(json: String): RoutingTileManifest = FoxNavJson.decodeFromString(serializer(), json)
    }
}
