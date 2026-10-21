package app.nullpointr.foxnav.shared.safety

import app.nullpointr.foxnav.shared.convoy.Fix
import app.nullpointr.foxnav.shared.convoy.moved
import app.nullpointr.foxnav.shared.geo.GeoPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AverageSpeedTest {
    private val start = GeoPoint(59.0491, 10.9515)

    /** 2 km due east, checking eastbound traffic. */
    private val end = start.moved(2_000.0, 90.0)
    private val section = Camera(
        id = "nvdb/823/1", lat = start.latitude, lon = start.longitude, kind = Camera.AVERAGE_SPEED,
        bearings = listOf(90.0), line = listOf(listOf(start.longitude, start.latitude), listOf(end.longitude, end.latitude)),
    )

    /** Where a car driving east from 100 m before the start at [speedMps] is after [seconds]. */
    private fun eastbound(seconds: Int, speedMps: Double = 20.0) =
        Fix(start.moved(-100.0 + speedMps * seconds, 90.0), speedMps, 90.0, timeMs = seconds * 1_000L)

    @Test
    fun theAverageIsDistanceOverTimeSinceEntering() {
        val watch = SectionWatch(listOf(section))
        assertNull(watch.update(eastbound(0)), "before the start")
        val entered = assertNotNull(watch.update(eastbound(5)))
        assertNull(entered.averageMps, "too soon to say")
        // 20 m/s for the first 40 s, then 30 m/s for 20 s: 1,400 m in 55 s from entering.
        for (second in 6..45) watch.update(eastbound(second))
        var progress: SectionProgress? = null
        for (second in 46..65) progress = watch.update(Fix(start.moved(-100.0 + 20.0 * 45 + 30.0 * (second - 45), 90.0), 30.0, 90.0, second * 1_000L))
        val now = assertNotNull(progress)
        assertNear(1_400.0 / 60.0, now.averageMps!!, 0.2)
        assertNear(1_400.0, now.travelledM, 5.0)
        assertNear(600.0, now.remainingM, 5.0)
    }

    @Test
    fun throughTheEndTheSectionIsDone() {
        val watch = SectionWatch(listOf(section))
        // At 103 s, 1,960 m into the section.
        for (second in 5..103) assertNotNull(watch.update(eastbound(second)), "at $second s")
        // At its end: past the last camera.
        assertNull(watch.update(eastbound(105)))
    }

    @Test
    fun theOtherWayRoundIsNotThisSection() {
        val watch = SectionWatch(listOf(section))
        assertNull(watch.update(Fix(start.moved(10.0, 90.0), 20.0, 270.0, 0)))
        assertNull(watch.update(Fix(start, 20.0, 270.0, 1_000)))
    }

    @Test
    fun turningOffLeavesTheSection() {
        val watch = SectionWatch(listOf(section))
        for (second in 5..20) watch.update(eastbound(second))
        // Off to the north at a junction 300 m in.
        val junction = start.moved(300.0, 90.0)
        assertNotNull(watch.update(Fix(junction.moved(60.0, 0.0), 20.0, 0.0, 21_000)), "one fix off the line could be GPS")
        assertNotNull(watch.update(Fix(junction.moved(80.0, 0.0), 20.0, 0.0, 22_000)))
        assertNull(watch.update(Fix(junction.moved(100.0, 0.0), 20.0, 0.0, 23_000)))
    }

    @Test
    fun theWarningAtASectionsFirstCameraIsForTheSection() {
        // NVDB lists the section's first camera as a camera too, a few metres from where it starts.
        val first = Camera("nvdb/162/1", start.moved(20.0, 90.0).latitude, start.moved(20.0, 90.0).longitude, bearings = listOf(90.0))
        val elsewhere = Camera("nvdb/162/2", end.moved(1_000.0, 90.0).latitude, end.moved(1_000.0, 90.0).longitude, bearings = listOf(90.0))
        val oneWayOnly = Camera("nvdb/162/3", start.latitude, start.longitude, bearings = listOf(270.0))
        val watch = CameraWatch(listOf(section, first, elsewhere, oneWayOnly))
        val ahead = assertNotNull(watch.ahead(eastbound(0)))
        assertEquals("nvdb/823/1", ahead.camera.id)
        // A camera for the other direction at the same spot is its own, and not ahead of us anyway.
        assertNull(CameraWatch(listOf(oneWayOnly)).ahead(eastbound(0)))
    }

    @Test
    fun otherCamerasAndShortLinesAreLeftOut() {
        val point = Camera("osm/node/1", start.latitude, start.longitude)
        val broken = section.copy(id = "nvdb/823/2", line = listOf(listOf(start.longitude, start.latitude)))
        assertNull(SectionWatch(listOf(point, broken)).update(eastbound(5)))
        assertTrue(Camera.AVERAGE_SPEED in Camera.KINDS)
    }

    private fun assertNear(expected: Double, actual: Double, tolerance: Double) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected ± $tolerance, was $actual")
}
