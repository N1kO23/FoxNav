package app.nullpointr.foxnav.shared.routing

import app.nullpointr.foxnav.shared.FoxNavJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a download of road data covers (ADR 0005). */
@Serializable
enum class RoadSelectionKind {
    /** The roads under an offline map area, downloaded with it. */
    @SerialName("map_area") MAP_AREA,

    /** The roads along a trip made available offline. */
    @SerialName("trip") TRIP,

    /** An area picked in the road-data settings. */
    @SerialName("area") AREA,

    /** All of the server's roads. */
    @SerialName("all") ALL,

    /** The server's motorways and arterials: routes between areas, but road lookups only where local roads are too. */
    @SerialName("main_roads") MAIN_ROADS,
}

/** One download of road data: what it covers, and for areas and trips, which tiles. */
@Serializable
data class RoadSelection(
    val id: String,
    val kind: RoadSelectionKind,
    val name: String,
    val createdMs: Long,
    /** For areas and trips: their tiles, as [RoutingTile.key]. */
    val tiles: List<String> = emptyList(),
) {
    /** The tiles it needs from [manifest]'s build. A server-wide one takes whatever the build has. */
    fun tilesIn(manifest: RoutingTileManifest): Set<RoutingTile> = when (kind) {
        RoadSelectionKind.ALL -> manifest.all
        RoadSelectionKind.MAIN_ROADS -> manifest.all.filterTo(HashSet()) { it.level < manifest.localLevel }
        else -> tiles.mapNotNullTo(HashSet(), RoutingTile::parse).filterTo(HashSet()) { manifest.sizeOf(it) != null }
    }

    companion object {
        fun of(id: String, kind: RoadSelectionKind, name: String, createdMs: Long, tiles: Collection<RoutingTile>) =
            RoadSelection(id, kind, name, createdMs, tiles.sortedWith(compareBy({ it.level }, { it.id })).map { it.key })
    }
}

/** Everything the phone keeps road data for. */
@Serializable
data class RoadSelections(val selections: List<RoadSelection> = emptyList()) {
    /** The tiles of [manifest]'s build that all the selections together need. */
    fun wanted(manifest: RoutingTileManifest): Set<RoutingTile> = selections.flatMapTo(HashSet()) { it.tilesIn(manifest) }

    fun with(selection: RoadSelection) = RoadSelections(selections.filterNot { it.id == selection.id } + selection)

    fun without(id: String) = RoadSelections(selections.filterNot { it.id == id })

    /** Without trips older than [TRIP_DAYS]: they were for a drive that's long over. */
    fun withoutExpiredTrips(nowMs: Long) = RoadSelections(selections.filterNot { it.kind == RoadSelectionKind.TRIP && nowMs - it.createdMs > TRIP_DAYS * DAY_MS })

    fun toJson(): String = FoxNavJson.encodeToString(serializer(), this)

    companion object {
        /** How long a trip's road data stays. */
        const val TRIP_DAYS = 30L
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        fun parse(json: String): RoadSelections = FoxNavJson.decodeFromString(serializer(), json)
    }
}

/** How much of some road data is on the phone, in bytes as [RoutingTileManifest] counts them. */
data class Coverage(
    /** What it all takes on the phone. */
    val bytes: Long,
    /** What of that is there already. */
    val presentBytes: Long,
    /** What the rest takes to download. */
    val missingDownload: Long,
) {
    val complete: Boolean get() = presentBytes >= bytes
    val fraction: Float get() = if (bytes == 0L) 1f else (presentBytes.toFloat() / bytes).coerceIn(0f, 1f)
}

/** How much of [tiles] is among [present]. */
fun RoutingTileManifest.coverage(tiles: Set<RoutingTile>, present: Set<RoutingTile>): Coverage {
    val missing = tiles - present
    return Coverage(
        bytes = sizeOf(tiles).bytes,
        presentBytes = sizeOf(tiles.intersect(present)).bytes,
        missingDownload = sizeOf(missing).download,
    )
}
