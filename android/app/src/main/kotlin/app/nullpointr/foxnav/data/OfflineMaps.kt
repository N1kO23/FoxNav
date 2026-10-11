package app.nullpointr.foxnav.data

import android.content.Context
import org.json.JSONObject
import org.maplibre.compose.offline.DownloadProgress
import org.maplibre.compose.offline.DownloadStatus
import org.maplibre.compose.offline.OfflinePack
import org.maplibre.compose.offline.OfflinePackDefinition
import org.maplibre.compose.offline.getOfflineManager
import org.maplibre.spatialk.geojson.BoundingBox
import java.util.UUID
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/**
 * Map areas downloaded for use without a connection. MapLibre keeps them in its offline database:
 * the style, its fonts and icons, and every tile in the area up to [MAX_ZOOM]. Each area is one
 * pack per theme (night and day share the tiles, so the second costs little).
 */
class OfflineMaps(context: Context) {
    private val manager = getOfflineManager(context)
    private val preferences = context.getSharedPreferences("offline_maps", Context.MODE_PRIVATE)

    /** Downloaded and downloading areas, newest first. Compose state: reading it in a composable follows changes. */
    val areas: List<OfflineArea>
        get() = manager.packs
            .mapNotNull { pack -> metadataOf(pack)?.let { it to pack } }
            .groupBy({ it.first.id }, { it })
            .map { (id, packs) -> OfflineArea(id, packs.first().first.name, packs.minOf { it.first.createdMs }, packs.map { it.second }) }
            .sortedByDescending { it.createdMs }

    /** Starts downloading [bounds] for every style in [styleUrls] as one area called [name]. */
    suspend fun download(name: String, bounds: BoundingBox, styleUrls: Collection<String>) {
        val id = UUID.randomUUID().toString()
        val created = System.currentTimeMillis()
        for (styleUrl in styleUrls.distinct()) {
            val metadata = PackMetadata(id, name, created).toBytes()
            val pack = manager.create(OfflinePackDefinition.TilePyramid(styleUrl, bounds, minZoom = 0, maxZoom = MAX_ZOOM), metadata)
            manager.resume(pack)
        }
    }

    fun pause(area: OfflineArea) {
        pausedIds = pausedIds + area.id
        area.packs.forEach(manager::pause)
    }

    fun resume(area: OfflineArea) {
        pausedIds = pausedIds - area.id
        area.packs.forEach(manager::resume)
    }

    /** Downloads the area again where the server has newer tiles. */
    suspend fun update(area: OfflineArea) {
        for (pack in area.packs) {
            manager.invalidate(pack)
            manager.resume(pack)
        }
    }

    suspend fun delete(area: OfflineArea) {
        pausedIds = pausedIds - area.id
        for (pack in area.packs) manager.delete(pack)
    }

    /** Carries on with downloads the app was closed in the middle of, except those paused on purpose. */
    fun resumeUnfinished() {
        for (area in areas) if (area.status != AreaStatus.READY && area.id !in pausedIds) area.packs.forEach(manager::resume)
    }

    private var pausedIds: Set<String>
        get() = preferences.getStringSet(KEY_PAUSED, emptySet()).orEmpty()
        set(value) = preferences.edit().putStringSet(KEY_PAUSED, value).apply()

    /** Also keeps more of what's been seen recently: the drive home works even outside downloaded areas. */
    suspend fun configure() {
        manager.setTileCountLimit(MAX_TILES)
        manager.setMaximumAmbientCacheSize(AMBIENT_CACHE_BYTES)
    }

    private fun metadataOf(pack: OfflinePack): PackMetadata? = pack.metadata?.let(PackMetadata::fromBytes)

    companion object {
        /** Street level; closer in, MapLibre stretches these tiles. One more level would quadruple the size. */
        const val MAX_ZOOM = 14

        /** Above this many tiles an area is too big to download in one go. */
        const val MAX_TILES = 40_000L

        /**
         * Typical compressed tile sizes by zoom, measured over Helsinki: a zoomed-out tile carries a
         * whole region and is big, a street-level one small. The countryside is lighter.
         */
        private fun bytesPerTile(zoom: Int): Long = when {
            zoom <= 11 -> 110_000L
            zoom == 12 -> 60_000L
            zoom == 13 -> 50_000L
            else -> 25_000L
        }

        /** Label fonts, downloaded with the first area and shared by all. */
        private const val FONT_BYTES = 6_000_000L
        private const val AMBIENT_CACHE_BYTES = 200L * 1024 * 1024
        private const val KEY_PAUSED = "paused"

        /** How many tiles [bounds] needs from zoom 0 to [MAX_ZOOM], and roughly how many bytes, with the fonts for the [first] area. */
        fun estimate(bounds: BoundingBox, first: Boolean = false): Estimate {
            var tiles = 0L
            var bytes = if (first) FONT_BYTES else 0L
            for (zoom in 0..MAX_ZOOM) {
                val n = 2.0.pow(zoom)
                val west = floor((bounds.west + 180) / 360 * n).toLong()
                val east = floor((bounds.east + 180) / 360 * n).toLong().coerceAtMost(n.toLong() - 1)
                val north = tileY(bounds.north, n)
                val south = tileY(bounds.south, n)
                val count = (east - west + 1).coerceAtLeast(1) * (south - north + 1).coerceAtLeast(1)
                tiles += count
                bytes += count * bytesPerTile(zoom)
            }
            return Estimate(tiles, bytes)
        }

        private fun tileY(latitude: Double, n: Double): Long {
            val radians = latitude.coerceIn(-85.0511, 85.0511) * PI / 180
            return floor((1 - ln(tan(radians) + 1 / kotlin.math.cos(radians)) / PI) / 2 * n).toLong().coerceIn(0, n.toLong() - 1)
        }
    }
}

data class Estimate(val tiles: Long, val bytes: Long)

enum class AreaStatus { DOWNLOADING, PAUSED, READY, FAILED }

/** One downloaded area: its packs, one per map theme. */
data class OfflineArea(val id: String, val name: String, val createdMs: Long, val packs: List<OfflinePack>) {
    private val progress: List<DownloadProgress> get() = packs.map { it.downloadProgress }

    val status: AreaStatus
        get() = when {
            progress.any { it is DownloadProgress.Error || it is DownloadProgress.TileLimitExceeded } -> AreaStatus.FAILED
            progress.all { it is DownloadProgress.Healthy && it.status == DownloadStatus.Complete } -> AreaStatus.READY
            progress.any { it is DownloadProgress.Healthy && it.status == DownloadStatus.Downloading } -> AreaStatus.DOWNLOADING
            else -> AreaStatus.PAUSED
        }

    /** 0–1, as far as MapLibre knows how much there is. */
    val fraction: Float
        get() {
            val healthy = progress.filterIsInstance<DownloadProgress.Healthy>()
            val required = healthy.sumOf { it.requiredResourceCount }
            return if (required == 0L) 0f else (healthy.sumOf { it.completedResourceCount }.toFloat() / required).coerceIn(0f, 1f)
        }

    /** What it takes up on the phone (tiles shared between the themes count once). */
    val bytes: Long
        get() = progress.filterIsInstance<DownloadProgress.Healthy>().maxOfOrNull { it.completedResourceBytes } ?: 0L

    val problem: String?
        get() = progress.firstNotNullOfOrNull {
            when (it) {
                is DownloadProgress.Error -> it.message
                is DownloadProgress.TileLimitExceeded -> "The area is too big"
                else -> null
            }
        }
}

/** What FoxNav stores with each pack in MapLibre's database, as JSON. */
private class PackMetadata(val id: String, val name: String, val createdMs: Long) {
    fun toBytes(): ByteArray = JSONObject().put("id", id).put("name", name).put("created", createdMs).toString().encodeToByteArray()

    companion object {
        fun fromBytes(bytes: ByteArray): PackMetadata? = runCatching {
            val json = JSONObject(bytes.decodeToString())
            PackMetadata(json.getString("id"), json.getString("name"), json.getLong("created"))
        }.getOrNull()
    }
}
