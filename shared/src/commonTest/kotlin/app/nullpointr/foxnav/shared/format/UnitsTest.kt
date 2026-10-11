package app.nullpointr.foxnav.shared.format

import kotlin.test.Test
import kotlin.test.assertEquals

class UnitsTest {
    @Test
    fun durations() {
        assertEquals("1 min", formatDuration(10.0))
        assertEquals("23 min", formatDuration(23 * 60.0))
        assertEquals("2 h 7 min", formatDuration(127 * 60.0))
    }

    @Test
    fun distances() {
        assertEquals("0 m", formatDistance(3.0))
        assertEquals("350 m", formatDistance(347.0))
        assertEquals("1.0 km", formatDistance(996.0))
        assertEquals("18,4 km", formatDistance(18_420.0, decimalSeparator = ','))
        assertEquals("100 km", formatDistance(99_960.0))
        assertEquals("189 km", formatDistance(189_204.0))
    }

    @Test
    fun speeds() {
        assertEquals(50, kilometersPerHour(13.9))
        assertEquals(0, kilometersPerHour(0.0))
    }

    @Test
    fun imperialDistances() {
        val imperial = UnitSystem.IMPERIAL
        assertEquals("450 ft", formatDistance(137.0, units = imperial))
        assertEquals("0.2 mi", formatDistance(322.0, units = imperial))
        assertEquals("2.4 mi", formatDistance(3_862.0, units = imperial))
        assertEquals("118 mi", formatDistance(189_204.0, units = imperial))
    }

    @Test
    fun spokenDistances() {
        assertEquals("400 metres", spokenDistance(380.0))
        assertEquals("100 metres", spokenDistance(20.0))
        assertEquals("1.5 kilometres", spokenDistance(1_480.0))
        assertEquals("1 kilometre", spokenDistance(1_010.0))
        assertEquals("2 kilometres", spokenDistance(2_030.0))
        assertEquals("800 feet", spokenDistance(240.0, UnitSystem.IMPERIAL))
        assertEquals("0.5 miles", spokenDistance(800.0, UnitSystem.IMPERIAL))
        assertEquals("1 mile", spokenDistance(1_600.0, UnitSystem.IMPERIAL))
    }

    @Test
    fun speedsAndLimitsInMph() {
        assertEquals(31, speedIn(13.9, UnitSystem.IMPERIAL))
        assertEquals(50, speedIn(13.9, UnitSystem.METRIC))
        assertEquals(50, limitIn(80, UnitSystem.IMPERIAL))
        assertEquals(31, limitIn(50, UnitSystem.IMPERIAL))
        assertEquals(80, limitIn(80, UnitSystem.METRIC))
    }
}
