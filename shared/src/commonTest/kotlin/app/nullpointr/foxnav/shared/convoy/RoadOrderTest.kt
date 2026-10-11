package app.nullpointr.foxnav.shared.convoy

import app.nullpointr.foxnav.protocol.convoy.v0.NowPlaying
import app.nullpointr.foxnav.protocol.convoy.v0.Profile
import app.nullpointr.foxnav.shared.geo.GeoPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoadOrderTest {
    private val here = GeoPoint(60.1699, 24.9384)

    @Test
    fun withoutARouteCarsAreAheadOrBehindByOurHeading() {
        val me = Fix(here, speedMps = 20.0, headingDeg = 90.0, timeMs = 0)
        assertNear(300.0, roadGap(me, here.moved(300.0, 90.0)))
        assertNear(-200.0, roadGap(me, here.moved(200.0, 270.0)))
        // Off to the side, the gap is still the real distance, not just the part along our heading.
        assertNear(-1_000.0, roadGap(me, here.moved(1_000.0, 200.0)))
    }

    @Test
    fun onARouteTheDistanceFollowsTheRoad() {
        // An L-shaped route: 500 m east, then 500 m north.
        val corner = here.moved(500.0, 90.0)
        val route = listOf(here, corner, corner.moved(500.0, 0.0))
        val me = Fix(here.moved(100.0, 90.0), 20.0, 90.0, 0)
        val aroundTheCorner = corner.moved(300.0, 0.0)
        // 400 m to the corner, then 300 m: further than the straight line.
        assertNear(700.0, roadGap(me, aroundTheCorner, route))
        // Off the route: as the crow flies instead.
        val elsewhere = here.moved(2_000.0, 180.0)
        assertNear(roadGap(me, elsewhere), roadGap(me, elsewhere, route))
    }

    @Test
    fun freshnessFadesThenGetsLost() {
        val member = ConvoyMember(1, fix = Fix(here, 0.0, 0.0, 0), lastHeardMs = 0)
        assertEquals(Signal.LIVE, member.signal(9_999))
        assertEquals(Signal.STALE, member.signal(STALE_AFTER_MS))
        assertEquals(Signal.LOST, member.signal(LOST_AFTER_MS))
        assertEquals(Signal.WAITING, ConvoyMember(2).signal(0))
    }

    @Test
    fun missingSpeedAndHeadingComeFromThePreviousFix() {
        val first = fixOf(here, 0, speedMps = null, headingDeg = null, previous = null)
        assertEquals(0.0, first.speedMps)
        val second = fixOf(here.moved(25.0, 45.0), 1_000, null, null, first)
        assertNear(25.0, second.speedMps)
        assertNear(45.0, second.headingDeg)
        // Reported values win.
        assertEquals(30.0, fixOf(here.moved(25.0, 45.0), 1_000, 30.0, 50.0, first).speedMps)
        // Standing still keeps the last heading, whatever the GPS says.
        val stopped = fixOf(second.point, 2_000, 0.0, 300.0, second)
        assertNear(45.0, stopped.headingDeg)
        // A jump isn't a speed.
        assertEquals(0.0, fixOf(here.moved(5_000.0, 0.0), 3_000, null, null, stopped).speedMps)
    }

    @Test
    fun aReplayedOlderStateChangesNothing() {
        val current = ConvoyMember(1).withState(memberState(Profile(display_name = "Now"), Fix(here, 10.0, 0.0, timeMs = 2_000)), heardMs = 10)
        val replayed = memberState(Profile(display_name = "Then"), Fix(here.moved(500.0, 0.0), 30.0, 0.0, timeMs = 1_000))
        assertEquals(current, current.withState(replayed, heardMs = 20))
        // The same state again (a refresh) still counts as hearing from them.
        val refreshed = current.withState(memberState(Profile(display_name = "Now"), current.fix), heardMs = 30)
        assertEquals(30L, refreshed.lastHeardMs)
    }

    @Test
    fun theirRoleAndSongComeWithTheirState() {
        val song = NowPlaying(title = "Plastic Love", artist = "Mariya Takeuchi", app = "Music")
        val leading = ConvoyMember(1).withState(
            memberState(Profile(display_name = "Aki", role = Profile.Role.ROLE_LEAD), Fix(here, 20.0, 0.0, timeMs = 1_000), nowPlaying = song),
            heardMs = 10,
        )
        assertEquals(Profile.Role.ROLE_LEAD, leading.profile?.role)
        assertEquals(song, leading.nowPlaying)
        // Each state says it all: no song in it, they stopped sharing (or playing).
        val quiet = leading.withState(memberState(Profile(display_name = "Aki", role = Profile.Role.ROLE_LEAD), Fix(here, 20.0, 0.0, timeMs = 2_000)), heardMs = 20)
        assertEquals(null, quiet.nowPlaying)
    }

    private fun assertNear(expected: Double, actual: Double, tolerance: Double = 1.0) =
        assertTrue(abs(expected - actual) < tolerance, "expected $expected, was $actual")
}
