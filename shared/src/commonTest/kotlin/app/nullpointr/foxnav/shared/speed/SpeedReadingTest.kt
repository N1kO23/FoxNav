package app.nullpointr.foxnav.shared.speed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeedReadingTest {
    @Test
    fun overStartsAtTheTolerance() {
        assertFalse(SpeedReading().next(52, 50).overLimit)
        assertTrue(SpeedReading().next(53, 50).overLimit)
    }

    @Test
    fun overEndsOnlyBackAtTheLimit() {
        val over = SpeedReading().next(56, 50)
        assertTrue(over.next(51, 50).overLimit)
        assertFalse(over.next(50, 50).overLimit)
    }

    @Test
    fun aNewLimitIsJudgedFresh() {
        // Leaving a 50 zone at 56 km/h into an 80 zone isn't over.
        assertFalse(SpeedReading().next(56, 50).next(56, 80).overLimit)
        // Entering a 40 zone at 56 km/h is.
        assertTrue(SpeedReading().next(56, 50).next(56, 40).overLimit)
    }

    @Test
    fun unknownSpeedOrLimitNeverWarns() {
        assertEquals(SpeedReading(null, 50, false), SpeedReading().next(null, 50))
        assertEquals(SpeedReading(90, null, false), SpeedReading().next(90, null))
    }
}
