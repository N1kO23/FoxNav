package app.nullpointr.foxnav.shared.navigation

import app.nullpointr.foxnav.shared.geo.GeoPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OffRouteCheckTest {
    // A road heading due north for a kilometre, a point every 100 m.
    private val road = (0..10).map { at(northM = it * 100.0) }

    @Test
    fun onTheRoadAndTheRightWayIsOnRoute() {
        val check = OffRouteCheck()
        val drive = (0..8).map { second -> fix(northM = 100.0 + second * 12, second, courseDeg = 0.0, speedMps = 12.0) }

        assertTrue(drive.all { check.check(road, it) == RouteCheck.OnRoute })
    }

    @Test
    fun wellOffTheLineIsOffRoute() {
        val result = OffRouteCheck().check(road, fix(northM = 300.0, second = 0, eastM = 80.0))

        assertIs<RouteCheck.OffRoute>(result)
        assertEquals(80.0, result.distanceM, 1.0)
    }

    @Test
    fun aFixTooVagueToTellIsGivenTheBenefitOfTheDoubt() {
        assertEquals(RouteCheck.OnRoute, OffRouteCheck().check(road, fix(northM = 300.0, second = 0, eastM = 80.0, accuracyM = 40.0)))
    }

    @Test
    fun drivingAgainstTheRouteIsTheWrongWayAfterAFewSeconds() {
        val check = OffRouteCheck()
        val results = (0..6).map { second -> check.check(road, fix(northM = 800.0 - second * 12, second, courseDeg = 180.0, speedMps = 12.0)) }

        // Not at once: a GPS course can swing round for a moment.
        assertTrue(results.take(4).all { it == RouteCheck.OnRoute })
        assertIs<RouteCheck.WrongWay>(results.last())
    }

    @Test
    fun theCourseAndSpeedComeFromMovementWhenTheFixesHaveNone() {
        val check = OffRouteCheck()
        val results = (0..6).map { second -> check.check(road, fix(northM = 800.0 - second * 12, second)) }

        assertIs<RouteCheck.WrongWay>(results.last())
    }

    @Test
    fun turningRoundSlowlyIsNotTheWrongWay() {
        val check = OffRouteCheck()
        val results = (0..10).map { second -> check.check(road, fix(northM = 500.0 - second * 2, second, courseDeg = 180.0, speedMps = 2.0)) }

        assertTrue(results.all { it == RouteCheck.OnRoute })
    }

    @Test
    fun aMomentAgainstTheRouteIsForgotten() {
        val check = OffRouteCheck()
        val back = (0..2).map { second -> fix(northM = 500.0 - second * 12, second, courseDeg = 180.0, speedMps = 12.0) }
        val on = (3..8).map { second -> fix(northM = 476.0 + (second - 2) * 12, second, courseDeg = 0.0, speedMps = 12.0) }
        val againBack = (9..12).map { second -> fix(northM = 548.0 - (second - 8) * 12, second, courseDeg = 180.0, speedMps = 12.0) }

        assertTrue((back + on + againBack).all { check.check(road, it) == RouteCheck.OnRoute })
    }

    @Test
    fun aRouteThatComesBackAlongTheSameRoadGoesBothWays() {
        // Out to a dead end and back, the way back a few metres beside the way out.
        val outAndBack = road + road.reversed().map { GeoPoint(it.latitude, it.longitude + 5.0 / METRES_PER_LON) }
        val check = OffRouteCheck()
        val results = (0..8).map { second -> check.check(outAndBack, fix(northM = 900.0 - second * 12, second, courseDeg = 180.0, speedMps = 12.0)) }

        assertTrue(results.all { it == RouteCheck.OnRoute })
    }

    @Test
    fun aResetForgetsTheWayWeWereGoing() {
        val check = OffRouteCheck()
        (0..3).forEach { second -> check.check(road, fix(northM = 800.0 - second * 12, second, courseDeg = 180.0, speedMps = 12.0)) }
        check.reset()

        assertEquals(RouteCheck.OnRoute, check.check(road, fix(northM = 700.0, second = 5, courseDeg = 180.0, speedMps = 12.0)))
    }

    private fun fix(northM: Double, second: Int, eastM: Double = 0.0, courseDeg: Double? = null, speedMps: Double? = null, accuracyM: Double = 5.0) =
        Fix(at(northM, eastM), accuracyM, courseDeg, speedMps, timeMs = second * 1000L)

    private companion object {
        val START = GeoPoint(60.17, 24.94)
        const val METRES_PER_LAT = 111_195.0
        val METRES_PER_LON = METRES_PER_LAT * cos(START.latitude * PI / 180)

        fun at(northM: Double, eastM: Double = 0.0) = GeoPoint(START.latitude + northM / METRES_PER_LAT, START.longitude + eastM / METRES_PER_LON)
    }
}
