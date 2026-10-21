package app.nullpointr.foxnav.shared.voice

import app.nullpointr.foxnav.shared.format.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhrasesTest {
    @Test
    fun distancesSnapToTheNearestRecordedOne() {
        assertEquals("in_300_m", Phrases.distance(310.0, UnitSystem.METRIC))
        assertEquals("in_800_m", Phrases.distance(750.0, UnitSystem.METRIC))
        assertEquals("in_1_5_km", Phrases.distance(1_420.0, UnitSystem.METRIC))
        assertEquals("in_1_km", Phrases.distance(1_050.0, UnitSystem.METRIC))
        assertEquals("in_a_quarter_mile", Phrases.distance(420.0, UnitSystem.IMPERIAL))
        assertNull(Phrases.distance(30.0, UnitSystem.METRIC), "now")
        assertNull(Phrases.distance(12_000.0, UnitSystem.METRIC), "too far to round")
    }

    @Test
    fun maneuversByTypeAndModifier() {
        assertEquals("turn_left", Phrases.maneuver("TURN", "LEFT"))
        assertEquals("bear_right", Phrases.maneuver("TURN", "SLIGHT_RIGHT"))
        assertEquals("keep_left", Phrases.maneuver("FORK", "SLIGHT_LEFT"))
        assertEquals("exit_right", Phrases.maneuver("OFF_RAMP", "SLIGHT_RIGHT"))
        assertEquals("roundabout", Phrases.maneuver("ROUNDABOUT", "RIGHT"))
        assertEquals("arrive", Phrases.maneuver("ARRIVE", "RIGHT"))
        assertNull(Phrases.maneuver("DEPART", null))
        assertNull(Phrases.maneuver("NOTIFICATION", "LEFT"))
    }

    @Test
    fun guidanceAndWarnings() {
        assertEquals(listOf("in_300_m", "turn_left"), Phrases.guidance("turn_left", 290.0, UnitSystem.METRIC))
        assertEquals(listOf("turn_left"), Phrases.guidance("turn_left", 40.0, UnitSystem.METRIC))
        assertEquals(listOf("arriving"), Phrases.guidance("arrive", 200.0, UnitSystem.METRIC))
        assertEquals(listOf("arrive"), Phrases.guidance("arrive", 10.0, UnitSystem.METRIC))
        assertEquals(listOf("speed_camera", "in_400_m"), Phrases.warning("speed_camera", 410.0, UnitSystem.METRIC, short = false))
        assertEquals(listOf("police"), Phrases.warning("police", 410.0, UnitSystem.METRIC, short = true))
        assertEquals(listOf("roadworks", "in_400_m"), Phrases.warning("roadworks", 410.0, UnitSystem.METRIC, short = false))
        assertNull(Phrases.warning("meteor_shower", 410.0, UnitSystem.METRIC, short = false))
    }

    @Test
    fun whatAManoeuvreAdds() {
        val metric = UnitSystem.METRIC
        assertEquals(listOf("in_200_m", "roundabout", "roundabout_exit_2"), Phrases.guidance(Phrases.Maneuver("ROUNDABOUT", "RIGHT", exit = 2), 210.0, metric))
        // No phrase that far round: the phone says it.
        assertTrue("roundabout_exit_7" in Phrases.guidance(Phrases.Maneuver("ROUNDABOUT", "RIGHT", exit = 7), 210.0, metric).orEmpty())
        assertEquals(listOf("arriving_left"), Phrases.guidance(Phrases.Maneuver("ARRIVE", "LEFT"), 200.0, metric))
        assertEquals(listOf("arriving"), Phrases.guidance(Phrases.Maneuver("ARRIVE", null), 200.0, metric))
        assertEquals(listOf("arrive"), Phrases.guidance(Phrases.Maneuver("ARRIVE", "LEFT"), 10.0, metric))
        assertNull(Phrases.guidance(Phrases.Maneuver("DEPART"), 200.0, metric))
    }

    @Test
    fun twoTurnsCloseTogetherAreSaidAsOne() {
        val right = Phrases.Maneuver("TURN", "RIGHT")
        assertEquals(listOf("turn_right", "then", "turn_left"), Phrases.guidance(right, 50.0, UnitSystem.METRIC, then = Phrases.Maneuver("TURN", "LEFT")))
        assertEquals(listOf("turn_right", "then", "arriving_right"), Phrases.guidance(right, 50.0, UnitSystem.METRIC, then = Phrases.Maneuver("ARRIVE", "RIGHT")))
        assertEquals(listOf("turn_right", "then", "roundabout", "roundabout_exit_3"), Phrases.guidance(right, 50.0, UnitSystem.METRIC, then = Phrases.Maneuver("ROUNDABOUT", "STRAIGHT", exit = 3)))
        // Nothing to say for the second: the first alone.
        assertEquals(listOf("turn_right"), Phrases.guidance(right, 50.0, UnitSystem.METRIC, then = Phrases.Maneuver("NOTIFICATION")))
    }

    @Test
    fun aCamerasLimit() {
        assertEquals(listOf("speed_camera", "in_400_m", "limit_80"), Phrases.warning("speed_camera", 410.0, UnitSystem.METRIC, short = false, limit = 80))
        assertEquals(listOf("speed_camera"), Phrases.warning("speed_camera", 410.0, UnitSystem.METRIC, short = true, limit = 80))
        // 100 km/h in mph: no phrase for 62.
        assertEquals(listOf("speed_camera", "in_a_quarter_mile"), Phrases.warning("speed_camera", 410.0, UnitSystem.IMPERIAL, short = false, limit = 62))
    }

    @Test
    fun aPackWithoutTheNewerPhrasesSaysTheRest() {
        assertEquals(listOf("in_100_m", "turn_left"), Phrases.plainer(listOf("in_100_m", "turn_left", "then", "turn_right")))
        assertEquals(listOf("speed_camera", "in_400_m"), Phrases.plainer(listOf("speed_camera", "in_400_m", "limit_80")))
        assertEquals(listOf("arriving"), Phrases.plainer(listOf("arriving_left")))
        // Without which exit, the phone says it.
        assertNull(Phrases.plainer(listOf("in_200_m", "roundabout", "roundabout_exit_2")))
        assertNull(Phrases.plainer(listOf("in_300_m", "turn_left")))
    }

    @Test
    fun everyPhraseHasAUniqueKey() {
        val keys = Phrases.ALL.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue("in_1_5_km" in keys && "in_half_a_mile" in keys && "animal" in keys, keys.toString())
        // Every manoeuvre and distance the rules can produce is recordable.
        val produced = listOf("TURN", "FORK", "MERGE", "ON_RAMP", "OFF_RAMP", "ROUNDABOUT", "EXIT_ROUNDABOUT", "CONTINUE", "ARRIVE", "END_OF_ROAD")
            .flatMap { type -> listOf("LEFT", "RIGHT", "SLIGHT_LEFT", "SLIGHT_RIGHT", "SHARP_LEFT", "SHARP_RIGHT", "STRAIGHT", "U_TURN").mapNotNull { Phrases.maneuver(type, it) } }
        assertTrue(produced.all { it in keys }, (produced - keys.toSet()).toString())
        val distances = (70..6_500 step 10).flatMap { listOfNotNull(Phrases.distance(it.toDouble(), UnitSystem.METRIC), Phrases.distance(it.toDouble(), UnitSystem.IMPERIAL)) }
        assertTrue(distances.all { it in keys }, (distances.toSet() - keys.toSet()).toString())
        val details = (1..6).flatMap { Phrases.guidance(Phrases.Maneuver("ROUNDABOUT", "RIGHT", exit = it), 50.0, UnitSystem.METRIC, then = Phrases.Maneuver("ARRIVE", "LEFT")).orEmpty() } +
            (20..120 step 10).flatMap { Phrases.warning("speed_camera", 400.0, UnitSystem.METRIC, short = false, limit = it).orEmpty() }
        assertTrue(details.all { it in keys }, (details.toSet() - keys.toSet()).toString())
    }
}
