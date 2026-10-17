package app.nullpointr.foxnav.data

import app.nullpointr.foxnav.shared.discovery.DiscoveryDocument
import app.nullpointr.foxnav.shared.discovery.Feature
import app.nullpointr.foxnav.shared.geo.GeoPoint
import app.nullpointr.foxnav.shared.routing.Coverage
import app.nullpointr.foxnav.shared.routing.RoadSelection
import app.nullpointr.foxnav.shared.routing.RoadSelections
import app.nullpointr.foxnav.shared.routing.RoutingTile
import app.nullpointr.foxnav.shared.routing.RoutingTileManifest
import app.nullpointr.foxnav.shared.routing.coverage
import app.nullpointr.foxnav.shared.search.SearchEntry
import app.nullpointr.foxnav.shared.search.SearchPackManifest
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
 *
 * Road data brings search with it: the search packs' cells (spec §3.4) where the phone has local
 * roads go into [search], so search works offline wherever routes do.
 */
class RoadData(private val root: File, client: OkHttpClient, online: StateFlow<Boolean>, scope: CoroutineScope, private val search: SearchIndex) {
    private val builds = File(root, "builds")
    private val selectionsFile = File(root, "selections.json")
    private val currentFile = File(root, "current")

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
        /** The search packs the phone knows, by region. */
        val search: Map<String, SearchPackManifest> = emptyMap(),
        /** Their cells on the phone: region → cell → build. */
        val searchCells: Map<String, Map<Long, String>> = emptyMap(),
        /** What the search index takes on the phone. */
        val searchBytes: Long = 0,
    ) {
        /**
         * How much of [tiles] the phone has, with the search data for their local roads; null
         * without a manifest. Search data counts by what its index takes on the phone, roughly.
         */
        fun coverage(tiles: Set<RoutingTile>): Coverage? = manifest?.let { routing ->
            val roads = routing.coverage(tiles, present)
            val cells = tiles.mapNotNullTo(HashSet()) { tile -> tile.id.takeIf { tile.level == routing.localLevel } }
            var bytes = 0L
            var there = 0L
            var missing = 0L
            for ((region, pack) in search) for (cell in cells) {
                val download = pack.downloadOf(cell) ?: continue
                val indexed = pack.indexBytesOf(cell) ?: 0L
                bytes += indexed
                if (searchCells[region]?.get(cell) == pack.build) there += indexed else missing += download
            }
            Coverage(roads.bytes + bytes, roads.presentBytes + there, roads.missingDownload + missing)
        }

        fun coverage(selection: RoadSelection): Coverage? = manifest?.let { coverage(selection.tilesIn(it)) }

        fun selection(id: String): RoadSelection? = selections.firstOrNull { it.id == id }

        /** What the road data takes on the phone, search included. */
        val bytes: Long get() = (manifest?.sizeOf(present)?.bytes ?: 0L) + searchBytes
    }

    private val _state = MutableStateFlow(
        State(manifest = readManifest(), current = currentFile.readTextOrNull(), selections = readSelections().selections, search = readSearchPacks()),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var tilesUrl: String? = null

    /** Where the server's search packs are, by region. */
    @Volatile
    private var searchUrls: Map<String, String> = emptyMap()
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
                        File(root, "${error.manifest}.etag").delete()
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

    /** Follows the server of [document]: its tiles, its search packs and their updates. Trips long over go. */
    fun use(document: DiscoveryDocument) {
        tilesUrl = document.routing?.tiles
        searchUrls = document.datapacks?.takeIf { Feature.DATA_PACKS in document.features }
            ?.let { base -> document.regions.associateWith { region -> "${base.trimEnd('/')}/$region/search" } }
            .orEmpty()
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
        val manifest = (if (online && url != null) fetchManifest(url) else null)?.let(::parseRoutingManifest) ?: _state.value.manifest ?: return
        _state.update { it.copy(manifest = manifest) }
        if (!manifest.isUsable) return
        val wanted = RoadSelections(_state.value.selections).wanted(manifest)
        if (wanted.isEmpty()) {
            builds.deleteRecursively()
            currentFile.delete()
            _state.update { it.copy(current = null, present = emptySet(), update = null, downloading = false, problem = null) }
            syncSearch(online, emptySet())
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
        syncSearch(online, wanted.mapNotNullTo(HashSet()) { tile -> tile.id.takeIf { tile.level == manifest.localLevel } })
    }

    /** Search data for the local roads' [cells]: what's missing or out of date downloads, what nothing needs goes. */
    private suspend fun syncSearch(online: Boolean, cells: Set<Long>) {
        val urls = searchUrls
        val packs = (urls.keys + _state.value.search.keys).mapNotNull { region ->
            val text = urls[region]?.takeIf { online }?.let { fetchCached("$it/${SearchPackManifest.MANIFEST}", "search-$region") }
            (text?.let(::parseSearchPack) ?: _state.value.search[region])?.let { region to it }
        }.toMap()
        val present = search.cells()
        for ((region, regionCells) in present) for (cell in regionCells.keys) {
            if (cell !in cells || packs[region]?.downloadOf(cell) == null && region in packs) search.delete(region, cell)
        }
        fun publish() = _state.update { it.copy(search = packs, searchCells = search.cells(), searchBytes = search.bytes) }
        publish()
        if (!online) return
        // Only from the server in use: a pack kept from another one stays as it is.
        val missing = packs.filterKeys { it in urls }.flatMap { (region, pack) ->
            cells.filter { pack.downloadOf(it) != null && present[region]?.get(it) != pack.build }.map { Triple(region, pack, it) }
        }
        if (missing.isEmpty()) return
        _state.update { it.copy(downloading = true, problem = null) }
        val slots = Semaphore(PARALLEL_DOWNLOADS)
        coroutineScope {
            for ((region, pack, cell) in missing) launch {
                slots.withPermit { runInterruptible(Dispatchers.IO) { downloadCell(region, urls.getValue(region), pack, cell) } }
                publish()
            }
        }
        _state.update { it.copy(downloading = false, problem = null) }
    }

    /** Downloads one search cell into the index. */
    private fun downloadCell(region: String, base: String, pack: SearchPackManifest, cell: Long) {
        val text = client.newCall(Request.Builder().url(pack.urlOf(base, cell)).build()).execute().use { response ->
            if (response.code == 404) throw BuildGone("search-$region")
            if (!response.isSuccessful) throw IOException("Search data: HTTP ${response.code}")
            GZIPInputStream(response.body.byteStream()).bufferedReader().use { it.readText() }
        }
        search.replace(region, cell, pack.build, SearchEntry.parseCell(text))
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
            if (response.code == 404) throw BuildGone(ROUTING_MANIFEST)
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

    /** The routing tiles' manifest text from the server, or null if it has none or can't be reached. */
    private fun fetchManifest(url: String): String? = fetchCached(url.trimEnd('/') + "/" + RoutingTileManifest.MANIFEST, ROUTING_MANIFEST)

    /**
     * A manifest from [url], kept in [root] under [name]: fresh, revalidated with its ETag, or
     * null if the server has none or can't be reached.
     */
    private fun fetchCached(url: String, name: String): String? {
        val file = File(root, "$name.json")
        val etag = File(root, "$name.etag")
        val source = File(root, "$name.source")
        val request = Request.Builder().url(url).apply {
            if (source.readTextOrNull() == url && file.exists()) etag.readTextOrNull()?.let { header("If-None-Match", it) }
        }.build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 304 -> file.readTextOrNull()
                    response.isSuccessful -> response.body.string().also { text ->
                        root.mkdirs()
                        File(root, "$name.json.tmp").apply { writeText(text) }.renameTo(file)
                        source.writeText(url)
                        response.header("ETag")?.let(etag::writeText) ?: etag.delete()
                    }
                    else -> null
                }
            }
        }.getOrNull()
    }

    private fun readManifest(): RoutingTileManifest? = File(root, "$ROUTING_MANIFEST.json").readTextOrNull()?.let(::parseRoutingManifest)

    private fun parseRoutingManifest(text: String): RoutingTileManifest? = runCatching { RoutingTileManifest.parse(text) }.getOrNull()

    private fun parseSearchPack(text: String): SearchPackManifest? = runCatching { SearchPackManifest.parse(text) }.getOrNull()?.takeIf { it.isUsable }

    /** The search packs kept from last time, so search data stays in step offline too. */
    private fun readSearchPacks(): Map<String, SearchPackManifest> =
        root.listFiles { file -> file.name.startsWith("search-") && file.name.endsWith(".json") }.orEmpty().mapNotNull { file ->
            file.readTextOrNull()?.let(::parseSearchPack)?.let { it.region to it }
        }.toMap()

    private fun readSelections(): RoadSelections = selectionsFile.readTextOrNull()?.let { runCatching { RoadSelections.parse(it) }.getOrNull() } ?: RoadSelections()

    private fun File.readTextOrNull(): String? = takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    /** A tile or cell of a build the server no longer has: [manifest] is out of date. */
    private class BuildGone(val manifest: String) : IOException("The server has newer road data")

    private companion object {
        const val ROUTING_MANIFEST = "manifest"
        const val PARALLEL_DOWNLOADS = 4
        const val RETRY_MS = 60_000L
    }
}
