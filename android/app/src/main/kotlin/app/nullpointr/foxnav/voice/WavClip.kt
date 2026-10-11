package app.nullpointr.foxnav.voice

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Recorded phrases as 16-bit mono PCM WAV: easy to trim, join and play without decoders. */
object WavClip {
    const val SAMPLE_RATE = 22_050
    const val HEADER_BYTES = 44L

    fun write(file: File, samples: ShortArray) {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort(it) }
        val header = ByteBuffer.allocate(HEADER_BYTES.toInt()).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples.size * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples.size * 2)
        }
        val temporary = File(file.parentFile, file.name + ".part")
        temporary.outputStream().use { out ->
            out.write(header.array())
            out.write(data.array())
        }
        temporary.renameTo(file)
    }

    /** The samples of a clip FoxNav wrote, or null for anything else. */
    fun read(file: File): ShortArray? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.size < HEADER_BYTES || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.getShort(22).toInt() != 1 || buffer.getInt(24) != SAMPLE_RATE || buffer.getShort(34).toInt() != 16) return null
        val samples = ShortArray((bytes.size - HEADER_BYTES.toInt()) / 2)
        buffer.position(HEADER_BYTES.toInt())
        buffer.asShortBuffer().get(samples)
        return samples
    }

    /**
     * The recording without the silence before and after the words, keeping a little breath at
     * each end, so joined clips sound like one sentence.
     */
    fun trim(samples: ShortArray): ShortArray {
        if (samples.isEmpty()) return samples
        val peak = samples.maxOf { abs(it.toInt()) }
        val threshold = maxOf(peak / 12, 300)
        val first = samples.indexOfFirst { abs(it.toInt()) > threshold }
        val last = samples.indexOfLast { abs(it.toInt()) > threshold }
        if (first < 0 || last <= first) return ShortArray(0)
        val pad = SAMPLE_RATE * PAD_MS / 1000
        return samples.copyOfRange((first - pad).coerceAtLeast(0), (last + pad).coerceAtMost(samples.size))
    }

    /** A clip ready to be joined with others: trimmed, and as loud as the rest. */
    fun prepare(samples: ShortArray): ShortArray {
        val words = trim(samples)
        if (words.isEmpty()) return words
        return Pcm.toShorts(Pcm.normalize(Pcm.toFloats(words)))
    }

    private const val PAD_MS = 60
}
