package app.nullpointr.foxnav.shared.convoy

import app.nullpointr.foxnav.shared.geo.GeoPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotionTrackTest {
    private val start = GeoPoint(60.1699, 24.9384)

    @Test
    fun extrapolatesAlongTheHeading() {
        val track = MotionTrack()
        track.update(Fix(start, speedMps = 20.0, headingDeg = 0.0, timeMs = 0), nowMs = 0)
        val oneSecondLater = track.positionAt(1_000)!!
        assertTrue(abs(start.distanceTo(oneSecondLater) - 20.0) < 0.1)
        assertTrue(oneSecondLater.latitude > start.latitude)
    }

    @Test
    fun extrapolationStopsAfterAWhile() {
        val track = MotionTrack(maxExtrapolationMs = 5_000)
        track.update(Fix(start, 20.0, 90.0, 0), 0)
        assertEquals(track.positionAt(5_000), track.positionAt(60_000))
    }

    @Test
    fun newReportsBlendInsteadOfJumping() {
        val track = MotionTrack(blendMs = 1_000)
        track.update(Fix(start, 0.0, 0.0, 0), 0)
        val corrected = start.moved(100.0, 90.0)
        track.update(Fix(corrected, 0.0, 0.0, 1_000), 1_000)
        val halfway = track.positionAt(1_500)!!
        assertTrue(abs(start.distanceTo(halfway) - 50.0) < 1.0, "was ${start.distanceTo(halfway)}")
        assertTrue(corrected.distanceTo(track.positionAt(2_000)!!) < 0.01)
    }

    @Test
    fun olderReportsAreIgnored() {
        val track = MotionTrack()
        track.update(Fix(start, 0.0, 0.0, 5_000), 5_000)
        track.update(Fix(start.moved(500.0, 0.0), 0.0, 0.0, 4_000), 5_100)
        assertTrue(start.distanceTo(track.positionAt(10_000)!!) < 0.01)
    }

    @Test
    fun nothingBeforeTheFirstReport() {
        assertNull(MotionTrack().positionAt(0))
    }
}
