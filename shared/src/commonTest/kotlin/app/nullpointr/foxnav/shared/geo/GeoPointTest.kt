package app.nullpointr.foxnav.shared.geo

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class GeoPointTest {
    @Test
    fun helsinkiToTampereIsAbout160KmAsTheCrowFlies() {
        val distance = GeoPoint(60.1699, 24.9384).distanceTo(GeoPoint(61.4978, 23.7610))
        assertTrue(abs(distance - 160_000) < 2_000, "was $distance")
    }

    @Test
    fun shortDistances() {
        val distance = GeoPoint(60.1699, 24.9384).distanceTo(GeoPoint(60.1700, 24.9384))
        assertTrue(abs(distance - 11.1) < 0.1, "was $distance")
    }

    @Test
    fun bearings() {
        val helsinki = GeoPoint(60.1699, 24.9384)
        assertTrue(abs(helsinki.bearingTo(GeoPoint(60.2699, 24.9384)) - 0.0) < 0.01)
        assertTrue(abs(helsinki.bearingTo(GeoPoint(60.1699, 25.0384)) - 90.0) < 0.1)
        assertTrue(abs(helsinki.bearingTo(GeoPoint(60.0699, 24.9384)) - 180.0) < 0.01)
        assertTrue(abs(helsinki.bearingTo(GeoPoint(60.1699, 24.8384)) - 270.0) < 0.1)
    }
}
