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
}
