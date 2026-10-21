package app.nullpointr.foxnav.shared.navigation

import app.nullpointr.foxnav.shared.format.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuidanceTimingTest {
    // Steps of a route through central Helsinki, with the cues Valhalla gave them.
    private val shortBlock = 141.0 to listOf(
        Cue("a", "In 100 metres, Turn left onto Eerikinkatu.", 129.5),
        Cue("b", "Turn left onto Eerikinkatu.", 40.0),
    )
    private val longStreet = 1_879.0 to listOf(
        Cue("c", "Continue for 2 kilometres.", 1_869.0),
        Cue("d", "In 200 metres, Turn right onto Porthaninrinne.", 172.9),
        Cue("e", "Turn right onto Porthaninrinne.", 50.0),
    )

    /** Drives a step at [mps] from its start, a fix a second; what was said, with where it was heard. */
    private fun drive(step: Pair<Double, List<Cue>>, mps: Double, fixHasSpeed: Boolean = true, fromM: Double = step.first): List<Pair<String, Double>> {
        val (lengthM, cues) = step
        val timing = GuidanceTiming()
        return generateSequence(0) { it + 1 }.map { second -> second to fromM - mps * second }.takeWhile { (_, distanceM) -> distanceM >= 0 }
            .mapNotNull { (second, distanceM) ->
                timing.next(cues, distanceM, lengthM - distanceM, second * 1_000L, if (fixHasSpeed) mps else null, UnitSystem.METRIC)
            }
            .map { it.cue.text to it.heardAtM }
            .toList()
    }

    @Test
    fun atTownSpeedTheTurnIsHeardWithTimeToSlowDown() {
        // 50 km/h. Valhalla put "Turn left" 40 m before the turn: under 3 s at this speed.
        val said = drive(shortBlock, mps = 14.0)
        val (text, heardAtM) = said.single()
        assertEquals("Turn left onto Eerikinkatu.", text)
        assertTrue(heardAtM in 70.0..90.0, "heard $heardAtM m before the turn")
    }

    @Test
    fun aDistanceIsHeardWhereItsTrue() {
        val said = drive(longStreet, mps = 14.0).toMap()
        assertEquals(setOf("Continue for 2 kilometres.", "In 200 metres, Turn right onto Porthaninrinne.", "Turn right onto Porthaninrinne."), said.keys)
        // Valhalla put it at 173 m, saying 200.
        val alert = said.getValue("In 200 metres, Turn right onto Porthaninrinne.")
        assertTrue(alert in 185.0..205.0, "\"In 200 metres\" heard $alert m before the turn")
        assertTrue(said.getValue("Turn right onto Porthaninrinne.") in 70.0..90.0)
    }

    @Test
    fun oneThatWouldRunIntoTheNextIsLeftOut() {
        // Going fast, "In 100 metres" would still be going on when "Turn left" begins.
        assertEquals(listOf("Turn left onto Eerikinkatu."), drive(shortBlock, mps = 14.0).map { it.first })
        // Slowly there's time for both.
        assertEquals(listOf("In 100 metres, Turn left onto Eerikinkatu.", "Turn left onto Eerikinkatu."), drive(shortBlock, mps = 6.0).map { it.first })
    }

    @Test
    fun slowlyTheyAreWhereValhallaPutThem() {
        val said = drive(longStreet, mps = 2.0).toMap()
        // At 7 km/h, 50 m is 25 s ahead: Valhalla's place for it is fine.
        assertEquals(50.0, said.getValue("Turn right onto Porthaninrinne."), 5.0)
    }

    @Test
    fun withoutASpeedFromTheFixItsWorkedOutFromTheDistance() {
        // A simulated drive, 20 m/s with no speed in its fixes.
        val said = drive(longStreet, mps = 20.0, fixHasSpeed = false).toMap()
        val final = said.getValue("Turn right onto Porthaninrinne.")
        assertTrue(final in 100.0..125.0, "heard $final m before the turn")
    }

    @Test
    fun aCueAlreadyPassedIsNotSaidLate() {
        // The step starts with the car already past where "In 100 metres" belongs.
        assertEquals(listOf("Turn left onto Eerikinkatu."), drive(shortBlock, mps = 1.0, fromM = 35.0).map { it.first })
        val timing = GuidanceTiming()
        val (_, cues) = shortBlock
        assertEquals("b", timing.next(cues, 30.0, 111.0, 0, 0.0, UnitSystem.METRIC)?.cue?.id)
        assertNull(timing.next(cues, 29.0, 112.0, 1_000, 0.0, UnitSystem.METRIC), "once")
    }

    @Test
    fun theDistanceACueSays() {
        assertEquals(200.0, statedDistanceM("In 200 metres, Turn left onto Annankatu.", 155.2, UnitSystem.METRIC))
        assertEquals(200.0, statedDistanceM("Om 200 meter, Håll höger för att ta 51.", 222.8, UnitSystem.METRIC))
        assertEquals(1_000.0, statedDistanceM("In 1 kilometri, Jatka oikealla valitaksesi ulosmenon 5.", 1_067.8, UnitSystem.METRIC))
        assertEquals(1_500.0, statedDistanceM("Om 1,5 kilometer, Sväng höger.", 1_390.0, UnitSystem.METRIC))
        // Valhalla's imperial distances are miles where it means kilometres: 155 m isn't 800 feet.
        assertEquals(243.84, statedDistanceM("In 800 feet, Turn left onto Annankatu.", 155.2, UnitSystem.IMPERIAL)!!, 0.01)
        assertEquals(402.336, statedDistanceM("In a quarter mile, Keep right to take 51.", 222.8, UnitSystem.IMPERIAL)!!, 0.01)
        assertEquals(1_609.344, statedDistanceM("In 1 mile, Keep right to take exit 5.", 1_067.8, UnitSystem.IMPERIAL)!!, 0.01)
        // Roads and exits aren't distances.
        assertNull(statedDistanceM("Turn right onto Toinen linja.", 44.4, UnitSystem.METRIC))
        assertNull(statedDistanceM("Keep right to take exit 5 towards E18.", 300.0, UnitSystem.METRIC))
    }
}
