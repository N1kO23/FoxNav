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
        assertNull(Phrases.warning("roadworks", 410.0, UnitSystem.METRIC, short = false))
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
    }
}
