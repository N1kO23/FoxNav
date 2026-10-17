package app.nullpointr.foxnav.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.nullpointr.foxnav.BuildConfig
import app.nullpointr.foxnav.shared.discovery.DiscoveryDocument
import app.nullpointr.foxnav.shared.geo.GeoPoint
import app.nullpointr.foxnav.shared.routing.RoadSelection
import app.nullpointr.foxnav.shared.routing.RoadSelectionKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointKind
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Road data from the local stack (infra/, with its routing tiles published), downloaded and used
 * the way the app does: routes and road lookups without the server. Skipped when the stack isn't
 * reachable from the emulator.
 */
@RunWith(AndroidJUnit4::class)
class RoadDataTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "roaddata-test")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = OkHttpClient()
    private val online = MutableStateFlow(true)
    private lateinit var document: DiscoveryDocument

    @Before
    fun setUp() {
        root.deleteRecursively()
        val url = BuildConfig.DEFAULT_SERVER_URL.trimEnd('/') + DiscoveryDocument.PATH
        val found = runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { DiscoveryDocument.parse(it.body.string()).resolved(url) }
        }.getOrNull()
        assumeTrue("No local stack with routing tiles at $url", found?.routing?.tiles != null)
        document = found!!
    }

    @After
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    @Test
    fun downloadsAnAreaThenRoutesAndFindsRoadsWithoutTheServer() = runBlocking {
        val roadData = RoadData(root, client, online, scope)
        roadData.use(document)
        val manifest = withTimeout(30.seconds) { roadData.state.mapNotNull { it.manifest }.first() }
        val tiles = manifest.tilesIn(ROVANIEMI_SOUTH_WEST, ROVANIEMI_NORTH_EAST)
        roadData.add(RoadSelection.of("rovaniemi", RoadSelectionKind.AREA, "Rovaniemi", System.currentTimeMillis(), tiles))
        withTimeout(120.seconds) { roadData.state.first { it.coverage(tiles)?.complete == true } }
        assertTrue(roadData.covers(GeoPoint(ROVANIEMI_CENTRE.lat, ROVANIEMI_CENTRE.lng)))
        assertTrue(tiles.all { File(roadData.tilesDir, manifest.pathOf(it)).length() == manifest.sizeOf(it)?.bytes })

        online.value = false
        val onDevice = OnDeviceValhalla(context, roadData)
        val options = { mapOf<String, Any>("language" to "fi-FI", "units" to "kilometers") }
        val offline = ValhallaRoutes(client, "http://127.0.0.1:9/route", onDevice, online = { false }, options)
        val route = offline.getRoutes(location(ROVANIEMI_CENTRE), listOf(Waypoint(STATION, WaypointKind.BREAK))).first()
        assertTrue(route.distance in 500.0..5_000.0, "distance ${route.distance}")
        assertTrue(route.steps.isNotEmpty())

        // Online, but the server can't be reached: the phone answers all the same.
        val unreachable = ValhallaRoutes(client, "http://127.0.0.1:9/route", onDevice, online = { true }, options)
        assertEquals(route.distance, unreachable.getRoutes(location(ROVANIEMI_CENTRE), listOf(Waypoint(STATION, WaypointKind.BREAK))).first().distance)

        // A few points a second apart along the route, as the drive screen collects them.
        val along = route.geometry.map { GeoPoint(it.lat, it.lng) }.let { points -> points.subList(points.size / 3, (points.size / 3 + 6).coerceAtMost(points.size)) }
        val road = RoadLookup(client, document.routing!!, onDevice, online = { false }).currentRoad(along)
        assertNotNull(road)
        assertEquals("FI", road.countryCode)

        // Away from the road data, the phone has no answer and doesn't pretend to.
        assertNull(RoadLookup(client, document.routing!!, onDevice, online = { false }).currentRoad(listOf(HELSINKI, HELSINKI_NEXT)))

        roadData.remove("rovaniemi")
        withTimeout(30.seconds) { roadData.state.first { it.current == null } }
        assertNull(roadData.tilesDir)
        assertTrue(File(root, "builds").listFiles().isNullOrEmpty())
    }

    private fun location(at: GeographicCoordinate) = UserLocation(at, 10.0, null, Instant.now(), null)

    private companion object {
        // Rovaniemi: a small corner of the local stack's Finland graph, straddling a tile boundary (66.5° N).
        val ROVANIEMI_SOUTH_WEST = GeoPoint(66.48, 25.68)
        val ROVANIEMI_NORTH_EAST = GeoPoint(66.52, 25.78)
        val ROVANIEMI_CENTRE = GeographicCoordinate(66.5030, 25.7270)
        val STATION = GeographicCoordinate(66.4985, 25.7110)
        val HELSINKI = GeoPoint(60.1699, 24.9410)
        val HELSINKI_NEXT = GeoPoint(60.1705, 24.9420)
    }
}
