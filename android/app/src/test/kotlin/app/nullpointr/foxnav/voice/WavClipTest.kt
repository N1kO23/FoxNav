package app.nullpointr.foxnav.voice

import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WavClipTest {
    private val rate = WavClip.SAMPLE_RATE

    /** Half a second of silence, a 0.4 s "word", half a second of silence. */
    private fun spoken(): ShortArray {
        val silence = ShortArray(rate / 2) { (it % 7 - 3).toShort() } // a little hiss
        val word = ShortArray(rate * 4 / 10) { (8000 * sin(2 * PI * 220 * it / rate)).toInt().toShort() }
        return silence + word + silence
    }

    @Test
    fun trimmingKeepsTheWordAndABreath() {
        val trimmed = WavClip.trim(spoken())
        val seconds = trimmed.size.toDouble() / rate
        assertTrue(seconds in 0.4..0.55, "$seconds s")
    }

    @Test
    fun silenceTrimsToNothing() {
        assertEquals(0, WavClip.trim(ShortArray(rate) { (it % 5 - 2).toShort() }).size)
    }

    @Test
    fun clipsSurviveARoundTrip() {
        val file = Files.createTempFile("clip", ".wav").toFile()
        val samples = WavClip.trim(spoken())
        WavClip.write(file, samples)
        assertEquals(WavClip.HEADER_BYTES + samples.size * 2, file.length())
        assertContentEquals(samples, WavClip.read(file))
    }

    @Test
    fun otherFilesAreRefused() {
        val file = File.createTempFile("not-a-clip", ".wav").apply { writeText("hello, this is not audio at all, but long enough to have a header") }
        assertNull(WavClip.read(file))
    }
}
