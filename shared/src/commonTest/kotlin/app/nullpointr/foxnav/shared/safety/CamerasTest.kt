package app.nullpointr.foxnav.shared.safety

import app.nullpointr.foxnav.shared.convoy.Fix
import app.nullpointr.foxnav.shared.convoy.moved
import app.nullpointr.foxnav.shared.geo.GeoPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CamerasTest {
    private val here = GeoPoint(60.1699, 24.9384)

    /** Driving east at 72 km/h: looks 500 m ahead. */
    private val eastbound = Fix(here, speedMps = 20.0, headingDeg = 90.0, timeMs = 0)

    private fun camera(at: GeoPoint, vararg bearings: Double) = Camera("test/${at.latitude},${at.longitude}", at.latitude, at.longitude, bearings = bearings.toList())

    @Test
    fun findsTheNextCameraAhead() {
        val near = camera(here.moved(300.0, 90.0))
        val far = camera(here.moved(450.0, 90.0))
        val ahead = CameraWatch(listOf(far, near)).ahead(eastbound)!!
        assertEquals(near, ahead.camera)
        assertNear(300.0, ahead.distanceM)
    }

    @Test
    fun ignoresCamerasBehindBesideOrBeyondTheLookahead() {
        val watch = CameraWatch(
            listOf(
                camera(here.moved(200.0, 270.0)), // behind
                camera(here.moved(300.0, 0.0)), // beside the road
                camera(here.moved(900.0, 90.0)), // too far at this speed
            ),
        )
        assertNull(watch.ahead(eastbound))
        // Faster, it looks further ahead.
        assertEquals(900.0, watch.ahead(eastbound.copy(speedMps = 40.0))!!.distanceM, 2.0)
    }

    @Test
    fun onlyCamerasCheckingOurDirectionCount() {
        val westward = camera(here.moved(300.0, 90.0), 270.0)
        val eastward = camera(here.moved(400.0, 90.0), 88.0)
        val both = camera(here.moved(450.0, 90.0), 90.0, 270.0)
        assertEquals(eastward, CameraWatch(listOf(westward, eastward)).ahead(eastbound)!!.camera)
        assertEquals(both, CameraWatch(listOf(westward, both)).ahead(eastbound)!!.camera)
    }

    @Test
    fun noWarningsWhenParked() {
        assertNull(CameraWatch(listOf(camera(here.moved(100.0, 90.0)))).ahead(eastbound.copy(speedMps = 0.0)))
    }

    @Test
    fun alongARouteTheCameraAroundTheCornerCountsAndTheParallelRoadsDont() {
        // East 300 m, then north: the camera is around the corner, out of the heading's cone.
        val corner = here.moved(300.0, 90.0)
        val route = listOf(here, corner, corner.moved(1_000.0, 0.0))
        val aroundTheCorner = camera(corner.moved(150.0, 0.0), 0.0)
        val parallelRoad = camera(here.moved(200.0, 90.0).moved(80.0, 180.0))
        val ahead = CameraWatch(listOf(aroundTheCorner, parallelRoad)).ahead(eastbound, route)!!
        assertEquals(aroundTheCorner, ahead.camera)
        assertNear(450.0, ahead.distanceM)
    }

    @Test
    fun readsPacksAndSkipsKindsItDoesntKnow() {
        val pack = CameraPack.parse(
            """{"format":1,"region":"FI","future":"field","cameras":[
              {"id":"osm/node/1","lat":60.1,"lon":24.9,"kind":"speed","bearings":[90],"maxspeed":50},
              {"id":"osm/node/2","lat":60.2,"lon":24.9,"kind":"red_light"},
              {"id":"osm/node/3","lat":60.3,"lon":24.9,"kind":"teleport"}]}""",
        )
        assertEquals(listOf("osm/node/1", "osm/node/2"), pack.cameras.map { it.id })
        assertEquals(50, pack.cameras.first().maxspeed)
        assertFailsWith<IllegalArgumentException> { CameraPack.parse("""{"format":2,"region":"FI"}""") }
    }

    @Test
    fun anglesWrapAround() {
        assertEquals(20.0, angleBetween(350.0, 10.0), 1e-9)
        assertEquals(180.0, angleBetween(0.0, 180.0), 1e-9)
    }

    private fun assertNear(expected: Double, actual: Double) = assertTrue(abs(expected - actual) < 3.0, "expected $expected, was $actual")
}

class CameraRulesTest {
    @Test
    fun warningsWhereTheyreLegal() {
        assertTrue(CameraRules.warningsAllowed("FI"))
        assertTrue(CameraRules.warningsAllowed("se"))
        assertTrue(CameraRules.warningsAllowed(null))
        assertFalse(CameraRules.warningsAllowed("DE"))
        assertFalse(CameraRules.warningsAllowed("ch"))
        assertFalse(CameraRules.warningsAllowed("FR"))
    }
}
