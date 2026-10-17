package app.nullpointr.foxnav.data

import app.nullpointr.foxnav.shared.discovery.DiscoveryDocument
import app.nullpointr.foxnav.shared.geo.GeoPoint
import app.nullpointr.foxnav.shared.routing.Coverage
import app.nullpointr.foxnav.shared.routing.RoadSelection
import app.nullpointr.foxnav.shared.routing.RoadSelections
import app.nullpointr.foxnav.shared.routing.RoutingTile
import app.nullpointr.foxnav.shared.routing.RoutingTileManifest
import app.nullpointr.foxnav.shared.routing.coverage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/**
 * The routing graph's tiles on the phone (protocol/SPEC.md §3.3, ADR 0005), for [OnDeviceValhalla].
 * Each download of road data, with a map area, for a trip or chosen in settings, is a
 * [RoadSelection]. The phone keeps the tiles of one build that cover them all, and fetches what's
 * missing whenever there's a connection. A new build downloads next to the one in use and takes
 * over once it has everything, since tiles of different builds don't fit together. Everything
 * lives in [root].
 */
class RoadData(private val root: File, client: OkHttpClient, online: StateFlow<Boolean>, scope: CoroutineScope) {
    private val builds = File(root, "builds")
    private val selectionsFile = File(root, "selections.json")
    private val currentFile = File(root, "current")
    private val manifestFile = File(root, "manifest.json")
    private val etagFile = File(root, "manifest.etag")
    private val sourceFile = File(root, "manifest.source")

    // A tile can be tens of megabytes: no limit on the whole call, only on silence.
    private val client = client.newBuilder().callTimeout(Duration.ZERO).readTimeout(Duration.ofSeconds(30)).build()

    data class State(
        /** The newest manifest the phone knows: which tiles there are, and their sizes. */
        val manifest: RoutingTileManifest? = null,
        /** The build whose tiles Valhalla uses; null until there's road data. */
        val current: String? = null,
        val selections: List<RoadSelection> = emptyList(),
        /** Tiles of [current] on the phone. */
        val present: Set<RoutingTile> = emptySet(),
        /** While a newer build downloads next to [current]: how far it's got. */
        val update: Coverage? = null,
        val downloading: Boolean = false,
        /** Why the last download stopped, until one goes through. */
        val problem: String? = null,
    ) {
        /** How much of [tiles] the phone has; null without a manifest. */
        fun coverage(tiles: Set<RoutingTile>): Coverage? = manifest?.coverage(tiles, present)

        fun coverage(selection: RoadSelection): Coverage? = manifest?.let { it.coverage(selection.tilesIn(it), present) }

        fun selection(id: String): RoadSelection? = selections.firstOrNull { it.id == id }

        /** What the road data takes on the phone. */
        val bytes: Long get() = manifest?.sizeOf(present)?.bytes ?: 0L
    }

    private val _state = MutableStateFlow(State(manifest = readManifest(), current = currentFile.readTextOrNull(), selections = readSelections().selections))
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var tilesUrl: String? = null
    private val changes = MutableStateFlow(0)

    init {
        scope.launch(Dispatchers.IO) {
            combine(changes, online) { _, online -> online }.collectLatest { online ->
                var refetched = false
                while (true) {
                    try {
                        sync(online)
                        break
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: BuildGone) {
                        // The server has moved on to a newer build: fetch its manifest afresh, at once.
                        // Should the new one be missing tiles too, it's the server's problem: wait.
                        etagFile.delete()
                        if (!refetched) refetched = true else delay(RETRY_MS)
                    } catch (error: Exception) {
                        _state.update { it.copy(downloading = false, problem = error.message ?: error.javaClass.simpleName) }
                        if (!online) break
                        delay(RETRY_MS)
                    }
                }
            }
        }
    }

    /** The tiles of the build in use, for Valhalla's `tile_dir`; null without road data. */
    val tilesDir: File? get() = _state.value.current?.let { File(builds, it) }?.takeIf { _state.value.present.isNotEmpty() }

    /** Whether the phone has the local roads at [point], which looking up the road there needs. */
    fun covers(point: GeoPoint): Boolean {
        val state = _state.value
        return state.manifest?.localTileAt(point)?.let { it in state.present } == true
    }

    /** Follows the server of [document]: its tiles and their updates. Trips long over go. */
    fun use(document: DiscoveryDocument) {
        tilesUrl = document.routing?.tiles
        val selections = RoadSelections(_state.value.selections)
        val kept = selections.withoutExpiredTrips(System.currentTimeMillis())
        if (kept != selections) edit { kept } else changes.update { it + 1 }
    }

    fun add(selection: RoadSelection) = edit { it.with(selection) }

    fun remove(id: String) = edit { it.without(id) }

    @Synchronized
    private fun edit(change: (RoadSelections) -> RoadSelections) {
        val selections = change(RoadSelections(_state.value.selections))
        root.mkdirs()
        File(root, "selections.json.tmp").apply { writeText(selections.toJson()) }.renameTo(selectionsFile)
        _state.update { it.copy(selections = selections.selections) }
        changes.update { it + 1 }
    }

    private suspend fun sync(online: Boolean) {
        val url = tilesUrl
        val manifest = (if (online && url != null) fetchManifest(url) else null) ?: _state.value.manifest ?: return
        _state.update { it.copy(manifest = manifest) }
        if (!manifest.isUsable) return
        val wanted = RoadSelections(_state.value.selections).wanted(manifest)
        if (wanted.isEmpty()) {
            builds.deleteRecursively()
            currentFile.delete()
            _state.update { it.copy(current = null, present = emptySet(), update = null, downloading = false, problem = null) }
            return
        }

        val target = manifest.build
        // The first road data, or what was in use went missing: the new build fits with itself.
        val current = _state.value.current?.takeIf { File(builds, it).isDirectory } ?: target.also(::makeCurrent)
        val targetDir = File(builds, target)
        prune(manifest, targetDir, wanted)
        val have: MutableSet<RoutingTile> = ConcurrentHashMap.newKeySet<RoutingTile>().apply {
            addAll(wanted.filter { File(targetDir, manifest.pathOf(it)).isFile })
        }
        val inUse = if (current == target) have else wanted.filterTo(HashSet()) { File(File(builds, current), manifest.pathOf(it)).isFile }
        fun publish() = _state.update {
            it.copy(current = current, present = if (current == target) HashSet(have) else inUse, update = manifest.coverage(wanted, have).takeIf { current != target })
        }
        publish()

        val missing = wanted - have
        if (missing.isNotEmpty() && online && url != null) {
            _state.update { it.copy(downloading = true, problem = null) }
            val slots = Semaphore(PARALLEL_DOWNLOADS)
            coroutineScope {
                for (tile in missing) launch {
                    slots.withPermit { runInterruptible(Dispatchers.IO) { download(manifest, url, tile, targetDir) } }
                    have += tile
                    publish()
                }
            }
            _state.update { it.copy(downloading = false, problem = null) }
        }
        if (current != target && have.containsAll(wanted)) {
            makeCurrent(target)
            _state.update { it.copy(current = target, present = HashSet(have), update = null) }
        }
        // Builds neither in use nor downloading.
        val keep = setOf(_state.value.current, target)
        builds.listFiles()?.filter { it.name !in keep }?.forEach { it.deleteRecursively() }
    }

    private fun makeCurrent(build: String) {
        root.mkdirs()
        currentFile.writeText(build)
    }

    /** Removes tiles nothing needs any more from [dir], and what interrupted downloads left. */
    private fun prune(manifest: RoutingTileManifest, dir: File, wanted: Set<RoutingTile>) {
        if (!dir.isDirectory) return
        val keep = wanted.mapTo(HashSet()) { File(dir, manifest.pathOf(it)).path }
        dir.walkBottomUp().forEach { file ->
            when {
                file.isFile && file.path !in keep -> file.delete()
                file.isDirectory && file != dir && file.list().isNullOrEmpty() -> file.delete()
            }
        }
    }

    /** Downloads one tile, unpacked and checked against the manifest's size. */
    private fun download(manifest: RoutingTileManifest, url: String, tile: RoutingTile, dir: File) {
        val target = File(dir, manifest.pathOf(tile))
        val part = File(target.path + ".part")
        target.parentFile?.mkdirs()
        client.newCall(Request.Builder().url(manifest.urlOf(url, tile)).build()).execute().use { response ->
            if (response.code == 404) throw BuildGone()
            if (!response.isSuccessful) throw IOException("Road data: HTTP ${response.code}")
            GZIPInputStream(response.body.byteStream()).use { input -> part.outputStream().use { input.copyTo(it) } }
        }
        val expected = manifest.sizeOf(tile)?.bytes
        if (part.length() != expected) {
            part.delete()
            throw IOException("Road data: tile ${tile.key} came out at ${part.length()} bytes, not $expected")
        }
        if (!part.renameTo(target)) throw IOException("Road data: couldn't store tile ${tile.key}")
    }

    /** The server's manifest: fresh, revalidated, or null if it has none or can't be reached. */
    private fun fetchManifest(url: String): RoutingTileManifest? {
        val manifestUrl = url.trimEnd('/') + "/" + RoutingTileManifest.MANIFEST
        val sameSource = sourceFile.readTextOrNull() == manifestUrl
        val request = Request.Builder().url(manifestUrl).apply {
            if (sameSource && manifestFile.exists()) etagFile.readTextOrNull()?.let { header("If-None-Match", it) }
        }.build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 304 -> readManifest()
                    response.isSuccessful -> {
                        val text = response.body.string()
                        RoutingTileManifest.parse(text).also {
                            root.mkdirs()
                            File(root, "manifest.json.tmp").apply { writeText(text) }.renameTo(manifestFile)
                            sourceFile.writeText(manifestUrl)
                            response.header("ETag")?.let(etagFile::writeText) ?: etagFile.delete()
                        }
                    }
                    else -> null
                }
            }
        }.getOrNull()
    }

    private fun readManifest(): RoutingTileManifest? = manifestFile.readTextOrNull()?.let { runCatching { RoutingTileManifest.parse(it) }.getOrNull() }

    private fun readSelections(): RoadSelections = selectionsFile.readTextOrNull()?.let { runCatching { RoadSelections.parse(it) }.getOrNull() } ?: RoadSelections()

    private fun File.readTextOrNull(): String? = takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    /** A tile of a build the server no longer has. */
    private class BuildGone : IOException("The server has newer road data")

    private companion object {
        const val PARALLEL_DOWNLOADS = 4
        const val RETRY_MS = 60_000L
    }
}
