package app.nullpointr.foxnav.shared.speed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeedLimitsTest {
    private val winter = ConditionalLimit(100, from = "10-15", to = "04-30", season = "winter")

    /** Road 7, 120 in summer and 100 in winter. */
    private val limits = SpeedLimits(listOf(WayLimits(way = 2287183, maxspeed = 120, conditional = listOf(winter))))

    @Test
    fun winterLimitsApplyInWinter() {
        assertEquals(Limit(100, "winter"), limits.limit(2287183, 120, Day(2026, 12, 24)))
        assertEquals(Limit(100, "winter"), limits.limit(2287183, 120, Day(2027, 4, 30)))
        assertEquals(Limit(120), limits.limit(2287183, 120, Day(2027, 5, 1)))
        assertEquals(Limit(100, "winter"), limits.limit(2287183, 120, Day(2026, 10, 15)))
        assertEquals(Limit(120), limits.limit(2287183, 120, Day(2026, 10, 14)))
    }

    @Test
    fun otherRoadsKeepTheMapsLimit() {
        assertEquals(Limit(80), limits.limit(42, 80, Day(2026, 12, 24)))
        assertEquals(Limit(80), limits.limit(null, 80, Day(2026, 12, 24)))
        assertNull(limits.limit(null, null, Day(2026, 12, 24)))
    }

    @Test
    fun aDifferentLimitFromTheMapMeansADifferentRoad() {
        // An exit ramp's 80 while the last lookup still says road 7: not road 7's winter limit.
        assertEquals(Limit(80), limits.limit(2287183, 80, Day(2026, 12, 24)))
        // No limit from the map: the pack's word.
        assertEquals(Limit(100, "winter"), limits.limit(2287183, null, Day(2026, 12, 24)))
    }

    @Test
    fun rangesWithinAYearAndOneOffs() {
        val summer = ConditionalLimit(80, from = "06-01", to = "08-31")
        assertTrue(summer.appliesOn(Day(2026, 7, 1)))
        assertFalse(summer.appliesOn(Day(2026, 9, 1)))
        val roadworks = ConditionalLimit(30, from = "2026-05-13", to = "2026-12-31")
        assertTrue(roadworks.appliesOn(Day(2026, 10, 10)))
        assertFalse(roadworks.appliesOn(Day(2027, 1, 1)))
        assertFalse(roadworks.appliesOn(Day(2025, 10, 10)))
    }

    @Test
    fun unreadableRangesNeverApply() {
        assertFalse(ConditionalLimit(30, from = "Mo-Fr", to = "07:00").appliesOn(Day(2026, 10, 10)))
        assertFalse(ConditionalLimit(30, from = "2026-05-13", to = "12-31").appliesOn(Day(2026, 10, 10)))
        assertFalse(ConditionalLimit(30, from = "13-01", to = "12-31").appliesOn(Day(2026, 10, 10)))
    }

    @Test
    fun packsParse() {
        val pack = SpeedLimitPack.parse(
            """{"format":1,"region":"FI","ways":[{"way":2287183,"maxspeed":120,"conditional":[{"maxspeed":100,"from":"10-15","to":"04-30","season":"winter"}]}],"extra":true}""",
        )
        assertEquals(listOf(winter), pack.ways.single().conditional)
        assertFailsWith<IllegalArgumentException> { SpeedLimitPack.parse("""{"format":2,"region":"FI"}""") }
    }
}
