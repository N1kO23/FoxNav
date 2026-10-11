package app.nullpointr.foxnav.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** Sound as numbers: the steps from any decoded audio file to a FoxNav clip. Samples are -1..1. */
object Pcm {
    /** Interleaved channels averaged into one. */
    fun downmix(interleaved: FloatArray, channels: Int): FloatArray {
        if (channels <= 1) return interleaved
        return FloatArray(interleaved.size / channels) { frame ->
            var sum = 0f
            for (channel in 0 until channels) sum += interleaved[frame * channels + channel]
            sum / channels
        }
    }

    /**
     * [samples] at another sample rate. Windowed-sinc interpolation, low-passed below the lower of
     * the two Nyquist frequencies: downsampling a 48 kHz file doesn't fold its highs back into
     * the voice as hiss and whistles.
     */
    fun resample(samples: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || samples.isEmpty()) return samples
        val step = from.toDouble() / to
        // Cutoff as a fraction of the input rate, a little under Nyquist.
        val cutoff = 0.5 * minOf(1.0, to.toDouble() / from) * ROLLOFF
        val halfWidth = ceil(TAPS / 2 / (2 * cutoff)).toInt()
        val length = floor(samples.size / step).toInt()
        return FloatArray(length) { index ->
            val center = index * step
            val first = (ceil(center) - halfWidth).toInt().coerceAtLeast(0)
            val last = (floor(center) + halfWidth).toInt().coerceAtMost(samples.lastIndex)
            var sum = 0.0
            var weights = 0.0
            for (k in first..last) {
                val x = k - center
                val weight = sinc(2 * cutoff * x) * blackman(x / halfWidth)
                sum += samples[k] * weight
                weights += weight
            }
            if (weights == 0.0) 0f else (sum / weights).toFloat()
        }
    }

    /** Louder to [PEAK] where it's quiet, by at most [MAX_GAIN]; quieter where it clips. */
    fun normalize(samples: FloatArray): FloatArray {
        val peak = samples.maxOfOrNull { abs(it) } ?: return samples
        if (peak == 0f) return samples
        val gain = minOf(PEAK / peak, MAX_GAIN)
        return FloatArray(samples.size) { samples[it] * gain }
    }

    /** A short fade at the end, for sounds cut off at a length limit: no click. */
    fun fadeOut(samples: FloatArray, rate: Int): FloatArray {
        val fade = minOf(samples.size, rate * FADE_MS / 1000)
        return FloatArray(samples.size) { index ->
            val left = samples.size - index
            if (left > fade) samples[index] else samples[index] * left / fade
        }
    }

    fun toShorts(samples: FloatArray): ShortArray =
        ShortArray(samples.size) { (samples[it] * 32768f).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() }

    fun toFloats(samples: ShortArray): FloatArray = FloatArray(samples.size) { samples[it] / 32768f }

    private fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

    /** The Blackman window over -1..1. */
    private fun blackman(x: Double): Double = if (abs(x) >= 1.0) 0.0 else 0.42 + 0.5 * cos(PI * x) + 0.08 * cos(2 * PI * x)

    private const val TAPS = 16.0
    private const val ROLLOFF = 0.92
    private const val PEAK = 0.89f // -1 dBFS
    private const val MAX_GAIN = 4f // +12 dB: enough for a quiet file, not so much that hiss becomes a roar
    private const val FADE_MS = 40
}
