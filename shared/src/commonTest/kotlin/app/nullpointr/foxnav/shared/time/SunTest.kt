package app.nullpointr.foxnav.shared.time

import app.nullpointr.foxnav.shared.geo.GeoPoint
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SunTest {
    private val helsinki = GeoPoint(60.17, 24.94)
    private val utsjoki = GeoPoint(69.91, 27.03)

    // 2026-06-21 and 2026-12-21, in UTC (Helsinki is UTC+3 in summer, +2 in winter).
    private val midsummerNoonUtc = 1_782_043_200_000L // 12:00
    private val midwinterNoonUtc = 1_797_854_400_000L // 12:00

    @Test
    fun dayAndNightInHelsinki() {
        assertTrue(Sun.isUp(helsinki, midsummerNoonUtc))
        assertTrue(Sun.isUp(helsinki, midwinterNoonUtc - 2 * HOUR)) // 12:00 local in winter
        assertFalse(Sun.isUp(helsinki, midwinterNoonUtc + 6 * HOUR)) // 20:00 local
        assertFalse(Sun.isUp(helsinki, midsummerNoonUtc + 10 * HOUR + 30 * MINUTE)) // 01:30 local, the shortest night
    }

    @Test
    fun midnightSunAndPolarNightInLapland() {
        assertTrue(Sun.isUp(utsjoki, midsummerNoonUtc + 12 * HOUR - 3 * HOUR)) // midnight local
        assertFalse(Sun.isUp(utsjoki, midwinterNoonUtc - 2 * HOUR)) // noon local, kaamos
    }

    @Test
    fun elevationIsSensible() {
        // About 53° at midsummer noon in Helsinki (90 - 60.17 + 23.44).
        val noon = Sun.elevation(helsinki, midsummerNoonUtc - 2 * HOUR + 20 * MINUTE)
        assertTrue(noon in 50.0..54.0, "$noon")
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
