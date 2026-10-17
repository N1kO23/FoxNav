package app.nullpointr.foxnav.data

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.nullpointr.foxnav.shared.geo.GeoPoint
import app.nullpointr.foxnav.shared.road.RoadInfo
import app.nullpointr.foxnav.shared.road.TraceAttributes
import app.nullpointr.foxnav.shared.speed.Day
import app.nullpointr.foxnav.shared.speed.Limit
import app.nullpointr.foxnav.shared.speed.SpeedLimitPack
import app.nullpointr.foxnav.shared.speed.SpeedLimits
import com.stadiamaps.ferrostar.core.withJsonOptions
import com.valhalla.valhalla.Valhalla
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteAdapter
import uniffi.ferrostar.RouteRequest
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointKind
import uniffi.ferrostar.WellKnownRouteProvider
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Valhalla on the phone (valhalla-mobile), with routing tiles from the local stack, through the same
 * requests and parsers the app uses with the server's Valhalla. The tiles are too big to bundle, so
 * copy them into the app first (all of them, or at least levels 0 and 1 and level 2 around central
 * Helsinki and Tampere); without them these tests are skipped. Into the app's own files, because it
 * can't read what adb pushes to its external files:
 *
 *     tar -cf - -C infra/data/valhalla/valhalla_tiles . | adb shell "run-as app.nullpointr.foxnav.dev sh -c 'mkdir -p files/valhalla_tiles && tar -xf - -C files/valhalla_tiles'"
 *     adb shell "run-as app.nullpointr.foxnav.dev sh -c 'cat > files/speedlimits.json'" < infra/data/datapacks/FI/speedlimits.json
 *
 * The expected roads and limits are Finland's OpenStreetMap data as the local stack builds it.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceValhallaTest {
    private val files = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
    private val tiles = File(files, "valhalla_tiles")
    private lateinit var valhalla: Valhalla

    @Before
    fun setUp() {
        assumeTrue("No routing tiles in $tiles", File(tiles, "0").isDirectory)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        valhalla = timed("start") { Valhalla(context, OnDeviceValhalla.config(tiles)) }
    }

    @After
    fun tearDown() {
        if (::valhalla.isInitialized) valhalla.close()
    }

    @Test
    fun routesAcrossHelsinki() {
        val route = timed("route in Helsinki") { route(from = HELSINKI_CENTRE, to = TOOLO) }.first()
        Log.i(TAG, "Helsinki: ${route.distance.toInt()} m, ${route.steps.size} steps: " + route.steps.take(4).joinToString(" / ") { it.instruction })
        assertTrue(route.distance in 2_000.0..8_000.0, "distance ${route.distance}")
        assertTrue(route.steps.size >= 3)
        assertTrue(route.steps.any { step -> step.annotations.orEmpty().any { "\"maxspeed\"" in it } }, "speed limits along the route")
    }

    @Test
    fun routesFromHelsinkiToTampereOnMainRoads() {
        val route = timed("route Helsinki-Tampere") { route(from = HELSINKI_CENTRE, to = TAMPERE_CENTRE) }.first()
        Log.i(TAG, "Helsinki-Tampere: ${route.distance.toInt()} m, ${route.steps.size} steps")
        assertTrue(route.distance in 150_000.0..200_000.0, "distance ${route.distance}")
    }

    @Test
    fun findsTheStreetForSpeedLimits() {
        val road = timed("road lookup in Helsinki") { currentRoad(MANNERHEIMINTIE) }
        Log.i(TAG, "Mannerheimintie: $road")
        assertNotNull(road)
        assertEquals("Mannerheimintie", road.name)
        assertEquals(40, road.speedLimitKmh)
        assertEquals("FI", road.countryCode)
    }

    @Test
    fun findsTheMotorwayAndItsWinterLimit() {
        val road = timed("road lookup on the motorway") { currentRoad(TAMPERE_MOTORWAY) }
        Log.i(TAG, "Motorway: $road")
        assertNotNull(road)
        assertEquals(120, road.speedLimitKmh)
        val pack = File(files, "speedlimits.json")
        assumeTrue("No speed limit pack at $pack", pack.isFile)
        val limits = SpeedLimits(listOf(SpeedLimitPack.parse(pack.readText())))
        assertEquals(Limit(100, "winter"), limits.limit(road.wayId, road.speedLimitKmh, Day(2027, 1, 15)))
        assertEquals(Limit(120), limits.limit(road.wayId, road.speedLimitKmh, Day(2027, 7, 1)))
    }

    /** What the app's ValhallaRoutes does, with the phone's Valhalla in place of the server. */
    private fun route(from: GeographicCoordinate, to: GeographicCoordinate): List<Route> {
        val provider = WellKnownRouteProvider.Valhalla(endpointUrl = "http://localhost/route", profile = "auto")
            .withJsonOptions(mapOf("language" to "fi-FI", "units" to "kilometers", "alternates" to 2))
        return RouteAdapter.fromWellKnownRouteProvider(provider).use { adapter ->
            val location = UserLocation(from, 10.0, null, Instant.now(), null)
            val request = adapter.generateRequest(location, listOf(Waypoint(to, WaypointKind.BREAK))) as RouteRequest.HttpPost
            adapter.parseResponse(valhalla.routeRaw(request.body.decodeToString()).toByteArray())
        }
    }

    /** What the app's RoadLookup does, with the phone's Valhalla in place of the server. */
    private fun currentRoad(recent: List<GeoPoint>): RoadInfo? =
        TraceAttributes.parseCurrentRoad(valhalla.traceAttributesRaw(TraceAttributes.request(recent)))

    private fun <T> timed(what: String, block: () -> T): T {
        val start = System.nanoTime()
        return block().also { Log.i(TAG, "$what: ${(System.nanoTime() - start) / 1_000_000} ms") }
    }

    private companion object {
        const val TAG = "OnDeviceValhalla"
        val HELSINKI_CENTRE = GeographicCoordinate(60.1699, 24.9410)
        val TOOLO = GeographicCoordinate(60.1990, 24.9240)
        val TAMPERE_CENTRE = GeographicCoordinate(61.4981, 23.7610)

        // Consecutive points along a route from the local stack, about a second apart.
        val MANNERHEIMINTIE = listOf(
            GeoPoint(60.180075, 24.928465), GeoPoint(60.18055, 24.928214), GeoPoint(60.18095, 24.927975),
            GeoPoint(60.181497, 24.927681), GeoPoint(60.181747, 24.927532),
        )
        val TAMPERE_MOTORWAY = listOf(
            GeoPoint(60.549507, 24.836591), GeoPoint(60.55051, 24.836014), GeoPoint(60.551349, 24.835453),
            GeoPoint(60.55173, 24.835178), GeoPoint(60.552477, 24.834612),
        )
    }
}
