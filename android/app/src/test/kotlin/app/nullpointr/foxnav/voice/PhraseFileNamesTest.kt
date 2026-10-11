package app.nullpointr.foxnav.voice

import app.nullpointr.foxnav.shared.voice.Phrases
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhraseFileNamesTest {
    @Test
    fun filesAreMatchedToPhrasesByName() {
        assertEquals("turn_left", phraseKeyOf("turn_left.mp3"))
        assertEquals("in_300_m", phraseKeyOf("in_300_m.WAV"))
    }

    @Test
    fun namesAsPeopleTypeThemMatchToo() {
        assertEquals("turn_left", phraseKeyOf("Turn Left.ogg"))
        assertEquals("turn_left", phraseKeyOf("turn-left.m4a"))
    }

    @Test
    fun otherFilesAreLeftOut() {
        assertNull(phraseKeyOf("voice.json"))
        assertNull(phraseKeyOf("turn_left_please.mp3"))
        assertNull(phraseKeyOf("README"))
    }

    @Test
    fun everyPhraseIsInTheDocs() {
        // Pack makers name their files from docs/voice-packs.md.
        val docs = File("../../docs/voice-packs.md").readText()
        Phrases.ALL.forEach { phrase -> assertTrue("`${phrase.key}` | ${phrase.prompt} |" in docs, "${phrase.key} is missing from docs/voice-packs.md") }
    }
}
