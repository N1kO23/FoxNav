package app.nullpointr.foxnav.shared.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuidanceTextTest {
    @Test
    fun finnishSaysHowFarAheadInFinnish() {
        assertEquals("200 metrin päästä käänny vasemmalle kadulle Annankatu.", mendedGuidance("In 200 metriä, Käänny vasemmalle kadulle Annankatu."))
        assertEquals("1 kilometrin päästä jatka oikealla valitaksesi ulosmenon 5.", mendedGuidance("In 1 kilometri, Jatka oikealla valitaksesi ulosmenon 5."))
        assertEquals("1,5 kilometrin päästä käänny oikealle.", mendedGuidance("In 1,5 kilometriä, Käänny oikealle."))
        assertEquals("alle 10 metrin päästä käänny oikealle.", mendedGuidance("In alle 10 metriä, Käänny oikealle."))
        assertEquals("puolen mailin päästä käänny oikealle.", mendedGuidance("In puoli mailia, Käänny oikealle."))
        assertEquals("neljännesmailin päästä käänny oikealle.", mendedGuidance("In neljännesmaili, Käänny oikealle."))
        assertEquals("500 jalan päästä käänny oikealle.", mendedGuidance("In 500 jalkaa, Käänny oikealle."))
    }

    @Test
    fun otherLanguagesAndSentencesStayAsTheyAre() {
        listOf(
            "In 200 metres, Turn left onto Annankatu.",
            "Om 200 meter, Sväng vänster in på Annankatu.",
            "Käänny vasemmalle kadulle Annankatu.",
            "Jatka 2 kilometriä.",
        ).forEach { assertEquals(it, mendedGuidance(it)) }
    }

    @Test
    fun twoManoeuvresInOneInstruction() {
        assertTrue(chainsNext("Turn right onto Toinen linja. Then, in 20 meters, Turn left onto Porthaninkatu."))
        assertTrue(chainsNext("Käänny oikealle kadulle Viides linja. Sitten Käänny vasemmalle kadulle Porthaninkatu."))
        assertTrue(chainsNext("Sväng höger in på Annankatu. Därefter Sväng vänster."))
        assertTrue(chainsNext("Ta til høyre. Deretter, om 20 meter, Ta til venstre."))
        assertTrue(chainsNext("Drej til højre. Derefter Drej til venstre."))
        assertFalse(chainsNext("Turn left onto Thenstreet."))
        assertFalse(chainsNext("In 200 meters, Turn left onto Annankatu."))
    }
}
