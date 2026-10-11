package app.nullpointr.foxnav.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcmTest {
    private fun tone(hz: Double, rate: Int, seconds: Double, level: Float = 0.5f) =
        FloatArray((rate * seconds).toInt()) { (level * sin(2 * PI * hz * it / rate)).toFloat() }

    private fun rms(samples: FloatArray, skip: Int = 200): Double {
        val middle = samples.copyOfRange(skip, samples.size - skip)
        return sqrt(middle.sumOf { it.toDouble() * it } / middle.size)
    }

    /** Upward zero crossings per second: the tone's frequency. */
    private fun frequency(samples: FloatArray, rate: Int): Double {
        val crossings = (1 until samples.size).count { samples[it - 1] < 0 && samples[it] >= 0 }
        return crossings * rate.toDouble() / samples.size
    }

    @Test
    fun stereoBecomesTheAverage() {
        assertContentEquals(floatArrayOf(0.5f, 0f), Pcm.downmix(floatArrayOf(1f, 0f, 0.5f, -0.5f), 2))
    }

    @Test
    fun aVoiceKeepsItsPitchAndLevelAtTheNewRate() {
        val resampled = Pcm.resample(tone(440.0, 44_100, 1.0), 44_100, WavClip.SAMPLE_RATE)
        assertEquals(WavClip.SAMPLE_RATE, resampled.size)
        assertEquals(440.0, frequency(resampled, WavClip.SAMPLE_RATE), 2.0)
        assertEquals(0.5 / sqrt(2.0), rms(resampled), 0.01)
    }

    @Test
    fun highsAboveTheNewRateAreFilteredOutNotFoldedIn() {
        // 15 kHz can't exist at 22.05 kHz; without the filter it would come back as a 7 kHz whistle.
        val resampled = Pcm.resample(tone(15_000.0, 48_000, 0.5), 48_000, WavClip.SAMPLE_RATE)
        assertTrue(rms(resampled) < 0.02, "${rms(resampled)}")
    }

    @Test
    fun upsamplingKeepsTheLength() {
        val resampled = Pcm.resample(tone(300.0, 8_000, 1.0), 8_000, WavClip.SAMPLE_RATE)
        assertEquals(WavClip.SAMPLE_RATE, resampled.size)
        assertEquals(300.0, frequency(resampled, WavClip.SAMPLE_RATE), 2.0)
    }

    @Test
    fun quietSoundsGetLouderButOnlySoMuch() {
        val quiet = Pcm.normalize(tone(440.0, 8_000, 0.1, level = 0.1f))
        assertEquals(0.4f, quiet.maxOf { abs(it) }, 0.01f)
        val clipping = Pcm.normalize(tone(440.0, 8_000, 0.1, level = 1.5f))
        assertEquals(0.89f, clipping.maxOf { abs(it) }, 0.01f)
    }

    @Test
    fun aCutSoundFadesOut() {
        val faded = Pcm.fadeOut(FloatArray(8_000) { 1f }, 8_000)
        assertEquals(1f, faded[0])
        assertTrue(faded.last() < 0.01f)
    }

    @Test
    fun samplesConvertBothWays() {
        assertContentEquals(shortArrayOf(0, 16384, -16384, Short.MAX_VALUE), Pcm.toShorts(Pcm.toFloats(shortArrayOf(0, 16384, -16384, Short.MAX_VALUE))))
    }
}
