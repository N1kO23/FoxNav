package app.nullpointr.foxnav.shared.speed

import app.nullpointr.foxnav.shared.geo.GeoPoint
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
    fun weekdaysComeFromTheDate() {
        // 2026-10-21 is a Wednesday, 2027-01-01 a Friday, 2024-02-29 a Thursday.
        assertEquals(2, Day(2026, 10, 21).weekday())
        assertEquals(4, Day(2027, 1, 1).weekday())
        assertEquals(3, Day(2024, 2, 29).weekday())
        assertEquals(6, Day(2026, 10, 25).weekday())
    }

    @Test
    fun schoolZonesHoldOnSchoolDaysDuringTheDay() {
        // 30 on weekdays, 07:00–17:00, all year, on a 50 street (NVDB's Skole).
        val school = ConditionalLimit(30, from = "01-01", to = "12-31", days = listOf("Mo", "Tu", "We", "Th", "Fr"), times = listOf(listOf("07:00", "17:00")))
        val limits = SpeedLimits(listOf(WayLimits(way = 9, maxspeed = 50, conditional = listOf(school))))
        val wednesday = Day(2026, 10, 21)
        assertEquals(Limit(30), limits.limit(9, 50, wednesday, minuteOfDay = 8 * 60))
        assertEquals(Limit(30), limits.limit(9, 50, wednesday, minuteOfDay = 7 * 60))
        assertEquals(Limit(50), limits.limit(9, 50, wednesday, minuteOfDay = 17 * 60))
        assertEquals(Limit(50), limits.limit(9, 50, wednesday, minuteOfDay = 6 * 60 + 59))
        assertEquals(Limit(50), limits.limit(9, 50, Day(2026, 10, 24), minuteOfDay = 10 * 60))
        // Without the time of day, a rule for set hours can't be known to hold.
        assertEquals(Limit(50), limits.limit(9, 50, wednesday))
    }

    @Test
    fun hoursPastMidnightBelongToTheDayTheyStart() {
        // Friday and Saturday nights, 22:00–06:00.
        val nights = ConditionalLimit(40, from = "01-01", to = "12-31", days = listOf("Fr", "Sa"), times = listOf(listOf("22:00", "06:00")))
        assertTrue(nights.appliesOn(Day(2026, 10, 23), minuteOfDay = 23 * 60))
        // Early on Saturday and Sunday: the nights that started on Friday and Saturday.
        assertTrue(nights.appliesOn(Day(2026, 10, 24), minuteOfDay = 2 * 60))
        assertTrue(nights.appliesOn(Day(2026, 10, 25), minuteOfDay = 5 * 60 + 59))
        // Early on Friday: Thursday night, not one of them.
        assertFalse(nights.appliesOn(Day(2026, 10, 23), minuteOfDay = 2 * 60))
        assertFalse(nights.appliesOn(Day(2026, 10, 25), minuteOfDay = 23 * 60))
    }

    @Test
    fun theLowerOfTwoRulesComesFirst() {
        // A school zone in a winter limit: builders list the lower first, and the first that applies wins.
        val school = ConditionalLimit(30, from = "01-01", to = "12-31", days = listOf("Mo", "Tu", "We", "Th", "Fr"), times = listOf(listOf("07:00", "17:00")))
        val limits = SpeedLimits(listOf(WayLimits(way = 10, maxspeed = 80, conditional = listOf(school, ConditionalLimit(60, "10-15", "04-30", "winter")))))
        val wednesday = Day(2026, 12, 2)
        assertEquals(Limit(30), limits.limit(10, 80, wednesday, minuteOfDay = 9 * 60))
        assertEquals(Limit(60, "winter"), limits.limit(10, 80, wednesday, minuteOfDay = 20 * 60))
    }

    @Test
    fun unreadableDaysAndHoursNeverApply() {
        assertFalse(ConditionalLimit(30, "01-01", "12-31", days = listOf("Mon")).appliesOn(Day(2026, 10, 19), 600))
        assertFalse(ConditionalLimit(30, "01-01", "12-31", times = listOf(listOf("7:00", "17:00"))).appliesOn(Day(2026, 10, 19), 600))
        assertFalse(ConditionalLimit(30, "01-01", "12-31", times = listOf(listOf("07:00"))).appliesOn(Day(2026, 10, 19), 600))
        assertTrue(ConditionalLimit(30, "01-01", "12-31", times = listOf(listOf("07:00", "24:00"))).appliesOn(Day(2026, 10, 19), 23 * 60 + 59))
    }

    @Test
    fun packsParse() {
        val pack = SpeedLimitPack.parse(
            """{"format":1,"region":"FI","ways":[{"way":2287183,"maxspeed":120,"conditional":[{"maxspeed":100,"from":"10-15","to":"04-30","season":"winter"}]}],"extra":true}""",
        )
        assertEquals(listOf(winter), pack.ways.single().conditional)
        assertFailsWith<IllegalArgumentException> { SpeedLimitPack.parse("""{"format":2,"region":"FI"}""") }
    }

    @Test
    fun theFillGivesLimitsWhereTheMapHasNone() {
        val pack = SpeedLimitPack.parse(
            """{"format":1,"region":"FI","ways":[{"way":30,"maxspeed":80,"conditional":[{"maxspeed":60,"from":"10-15","to":"04-30","season":"winter"}]}],
               "fill":{"40":[10,5,2],"80":[30]}}""",
        )
        assertEquals(mapOf(10L to 40, 15L to 40, 17L to 40, 30L to 80), pack.fillLimits())
        val limits = SpeedLimits(listOf(pack))
        val summer = Day(2026, 6, 1)
        assertEquals(Limit(40), limits.limit(15, null, summer))
        // The map's own limit wins over the fill.
        assertEquals(Limit(30), limits.limit(15, 30, summer))
        assertNull(limits.limit(16, null, summer))
        // A filled way's winter limit.
        assertEquals(Limit(80), limits.limit(30, null, summer))
        assertEquals(Limit(60, "winter"), limits.limit(30, null, Day(2026, 12, 1)))
    }

    @Test
    fun sectionsGiveTheirLimitWhereTheCarIsOnThem() {
        // A long rural road at 80 through a village at 50, which the map has no limit for.
        val village = Section(50, line = listOf(listOf(25.000, 62.000), listOf(25.000, 62.005)))
        val limits = SpeedLimits(listOf(WayLimits(way = 7, limit = 80, sections = listOf(village))))
        val day = Day(2026, 6, 1)
        assertEquals(Limit(50), limits.limit(7, null, day, GeoPoint(62.0025, 25.0001)))
        // Before the village and past it, the road's limit.
        assertEquals(Limit(80), limits.limit(7, null, day, GeoPoint(61.998, 25.0)))
        assertEquals(Limit(80), limits.limit(7, null, day, GeoPoint(62.007, 25.0)))
        // At its ends (where a section meets a junction), still on it.
        assertEquals(Limit(50), limits.limit(7, null, day, GeoPoint(62.005, 25.0)))
        assertEquals(Limit(50), limits.limit(7, null, day, GeoPoint(62.00505, 25.0)))
        // Well off to the side: not on it.
        assertEquals(Limit(80), limits.limit(7, null, day, GeoPoint(62.0025, 25.002)))
        // Without a position, the road's limit.
        assertEquals(Limit(80), limits.limit(7, null, day))
        // The engine reports a limit here, the pack says it has none for way 7: a different road.
        assertEquals(Limit(60), limits.limit(7, 60, day, GeoPoint(62.0025, 25.0001)))
    }

    @Test
    fun aReplacedLimitWinsOnlyWhereTheEngineSaysWhatThePackExpects() {
        val limits = SpeedLimits(listOf(WayLimits(way = 8, maxspeed = 80, limit = 60, conditional = listOf(ConditionalLimit(70, "10-15", "04-30", "winter")))))
        assertEquals(Limit(60), limits.limit(8, 80, Day(2026, 6, 1)))
        assertEquals(Limit(100), limits.limit(8, 100, Day(2026, 6, 1)))
        // The map's winter 70 is above the replacing 60: 60 stands.
        assertEquals(Limit(60), limits.limit(8, 80, Day(2026, 12, 1)))
    }
}
