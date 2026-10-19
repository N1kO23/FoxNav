package app.nullpointr.foxnav.data

import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import androidx.core.content.edit
import org.json.JSONObject
import org.maplibre.compose.offline.DownloadProgress
import org.maplibre.compose.offline.DownloadStatus
import org.maplibre.compose.offline.OfflinePack
import org.maplibre.compose.offline.OfflinePackDefinition
import org.maplibre.compose.offline.getOfflineManager
import org.maplibre.spatialk.geojson.BoundingBox
import java.io.File
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
class OfflineMaps(
    context: Context,
    /** Whether downloads may run now: off Wi-Fi they wait, if the driver chose Wi-Fi only. */
    private val mayDownload: () -> Boolean = { true },
    /** Something started or resumed downloading: see it through, also with the app closed. */
    private val onDownloading: () -> Unit = {},
) {
    private val manager = getOfflineManager(context)
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** Downloaded and downloading areas, newest first. Compose state: reading it in a composable follows changes. */
    val areas: List<OfflineArea>
        get() = manager.packs
            .mapNotNull { pack -> metadataOf(pack)?.let { it to pack } }
            .groupBy({ it.first.id }, { it })
            .map { (id, packs) -> OfflineArea(id, packs.first().first.name, packs.minOf { it.first.createdMs }, packs.map { it.second }) }
            .sortedByDescending { it.createdMs }

    /** Starts downloading [bounds] for every style in [styleUrls] as one area called [name]; returns the area's ID. */
    suspend fun download(name: String, bounds: BoundingBox, styleUrls: Collection<String>): String {
        val id = UUID.randomUUID().toString()
        createPacks(PackMetadata(id, name, System.currentTimeMillis()), bounds, styleUrls)
        onDownloading()
        return id
    }

    private suspend fun createPacks(metadata: PackMetadata, bounds: BoundingBox, styleUrls: Collection<String>) {
        for (styleUrl in styleUrls.distinct()) {
            val pack = manager.create(OfflinePackDefinition.TilePyramid(styleUrl, bounds, minZoom = 0, maxZoom = MAX_ZOOM), metadata.toBytes())
            if (mayDownload() && metadata.id !in pausedIds) manager.resume(pack)
        }
    }

    fun pause(area: OfflineArea) {
        pausedIds = pausedIds + area.id
        area.packs.forEach(manager::pause)
    }

    fun resume(area: OfflineArea) {
        pausedIds = pausedIds - area.id
        if (mayDownload()) area.packs.forEach(manager::resume)
        onDownloading()
    }

    /**
     * Downloads [area] afresh, with the server's [styleUrls]: its packs go and come back under the same
     * ID, so its road data stays with it. Tiles it shares with other areas stay as they are.
     *
     * Not with MapLibre's invalidate. That marks everything the packs hold, the styles, fonts and icons
     * all areas share included, as needing a check with the server before use, which offline never
     * comes. Resuming the download doesn't fetch what's already there either, so the light theme, say,
     * then stays broken offline.
     */
    suspend fun update(area: OfflineArea, styleUrls: Collection<String>) {
        val bounds = area.bounds ?: return
        val styles = styleUrls.ifEmpty { area.styleUrls }
        for (pack in area.packs) manager.delete(pack)
        // A deleted pack's tiles stay in MapLibre's cache, and the new pack would take them from there.
        manager.clearAmbientCache()
        createPacks(PackMetadata(area.id, area.name, area.createdMs), bounds, styles)
        onDownloading()
    }

    /**
     * Repairs what earlier versions' updates did with MapLibre's invalidate (see [update]), if
     * [noteStaleEntries] found its marks: every area downloads again. Only deleting every pack and then
     * clearing the cache gets rid of the stale entries the areas share. The next start checks again.
     */
    suspend fun repairStale() {
        if (!preferences.getBoolean(KEY_REPAIR, false)) return
        val again = areas.mapNotNull { area -> area.bounds?.let { Triple(area, it, area.styleUrls) } }
        for (area in areas) for (pack in area.packs) manager.delete(pack)
        manager.clearAmbientCache()
        for ((area, bounds, styleUrls) in again) createPacks(PackMetadata(area.id, area.name, area.createdMs), bounds, styleUrls)
        if (again.isNotEmpty()) onDownloading()
        preferences.edit { remove(KEY_REPAIR) }
    }

    suspend fun delete(area: OfflineArea) {
        pausedIds = pausedIds - area.id
        for (pack in area.packs) manager.delete(pack)
    }

    /** Carries on with downloads the app was closed in the middle of, except those paused on purpose. */
    fun resumeUnfinished() {
        for (area in areas) if (area.status != AreaStatus.READY && area.id !in pausedIds) area.packs.forEach(manager::resume)
    }

    /** Holds the downloads under way, without counting them as paused by the driver: off Wi-Fi, for Wi-Fi only. */
    fun holdDownloads() {
        for (area in areas) if (area.status == AreaStatus.DOWNLOADING) area.packs.forEach(manager::pause)
    }

    /** Whether [area] is unfinished but not paused by the driver: waiting to carry on, say for Wi-Fi. */
    fun isWaiting(area: OfflineArea): Boolean = area.status == AreaStatus.PAUSED && area.id !in pausedIds

    private var pausedIds: Set<String>
        get() = preferences.getStringSet(KEY_PAUSED, emptySet()).orEmpty()
        set(value) = preferences.edit { putStringSet(KEY_PAUSED, value) }

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
        private const val PREFERENCES = "offline_maps"
        private const val KEY_PAUSED = "paused"
        private const val KEY_REPAIR = "repair_stale"
        private const val KEY_CLEAN = "no_stale_entries"

        /** MapLibre's offline database, in the app's files. */
        private const val DATABASE = "mbgl-offline.db"

        /**
         * Notes, for [repairStale], whether MapLibre's database holds entries that an earlier version's
         * update marked stale, until it finds none: this version marks nothing. Runs at the app's start,
         * before MapLibre opens the database and locks it.
         *
         * Invalidate sets `expires` to 0 with `must_revalidate`, which nothing else does, on a pack's
         * tiles and on its style, icons and fonts. Those are in the small `resources` table, so that
         * table tells: reading `tiles` would mean reading every tile.
         */
        fun noteStaleEntries(context: Context) {
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            if (preferences.getBoolean(KEY_CLEAN, false)) return
            val database = File(context.filesDir, DATABASE)
            val stale = if (!database.isFile) false else runCatching {
                SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    DatabaseUtils.longForQuery(db, "SELECT EXISTS (SELECT 1 FROM resources WHERE must_revalidate = 1 AND expires = 0)", null) == 1L
                }
            }.getOrNull() ?: return
            preferences.edit { if (stale) putBoolean(KEY_REPAIR, true) else putBoolean(KEY_CLEAN, true) }
        }

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

    /** The area itself. */
    val bounds: BoundingBox? get() = packs.firstNotNullOfOrNull { (it.definition as? OfflinePackDefinition.TilePyramid)?.bounds }

    /** The styles (themes) it was downloaded for. */
    val styleUrls: List<String> get() = packs.map { it.definition.styleUrl }

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
