package app.nullpointr.foxnav.voice

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteOrder

/**
 * Any audio file the phone can play (MP3, AAC and M4A, Ogg Vorbis and Opus, FLAC, WAV…) as
 * FoxNav's sound format: mono 16-bit at [WavClip.SAMPLE_RATE].
 */
object AudioDecoder {
    /** The sound in [uri], up to [maxSeconds] (cut there with a fade), or null when it isn't audio. Blocking. */
    fun decode(context: Context, uri: Uri, maxSeconds: Int): ShortArray? =
        decode(maxSeconds) { setDataSource(context, uri, null) }

    fun decode(file: File, maxSeconds: Int): ShortArray? = decode(maxSeconds) { setDataSource(file.path) }

    private fun decode(maxSeconds: Int, open: MediaExtractor.() -> Unit): ShortArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            runCatching { extractor.open() }.getOrElse { return null }
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val out = FloatList()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0
            while (true) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                // A decoder that never says it's done: give up rather than spin.
                idle = if (index == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) idle + 1 else 0
                if (idle > MAX_IDLE_POLLS) break
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = codec.outputFormat
                        rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = output.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        when (encoding) {
                            AudioFormat.ENCODING_PCM_FLOAT -> buffer.asFloatBuffer().let { while (it.hasRemaining()) out.add(it.get()) }
                            PCM_32BIT -> buffer.asIntBuffer().let { while (it.hasRemaining()) out.add(it.get() / 2147483648f) }
                            PCM_24BIT_PACKED -> while (buffer.remaining() >= 3) {
                                val low = buffer.get().toInt() and 0xFF
                                val middle = buffer.get().toInt() and 0xFF
                                val high = buffer.get().toInt()
                                out.add(((high shl 16) or (middle shl 8) or low) / 8388608f)
                            }
                            AudioFormat.ENCODING_PCM_8BIT -> while (buffer.hasRemaining()) out.add(((buffer.get().toInt() and 0xFF) - 128) / 128f)
                            else -> buffer.asShortBuffer().let { while (it.hasRemaining()) out.add(it.get() / 32768f) }
                        }
                        codec.releaseOutputBuffer(index, false)
                        val enough = out.size >= maxSeconds.toLong() * rate * channels
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || enough) break
                    }
                }
            }
            val limit = (maxSeconds.toLong() * rate * channels).toInt()
            val cut = out.size > limit
            var samples = Pcm.downmix(out.toArray(limit), channels)
            samples = Pcm.resample(samples, rate, WavClip.SAMPLE_RATE)
            if (cut) samples = Pcm.fadeOut(samples, WavClip.SAMPLE_RATE)
            return Pcm.toShorts(samples).takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            // A file that claims to be audio but isn't, or a codec the phone lacks.
            return null
        } finally {
            codec?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }

    private const val TIMEOUT_US = 10_000L
    private const val MAX_IDLE_POLLS = 200 // two seconds

    // AudioFormat's constants for these are API 31; decoders on older phones simply never report them.
    private const val PCM_24BIT_PACKED = 21
    private const val PCM_32BIT = 22
}

/** A growing list of floats without boxing each one. */
private class FloatList {
    private var values = FloatArray(1 shl 16)
    var size = 0
        private set

    fun add(value: Float) {
        if (size == values.size) values = values.copyOf(values.size * 2)
        values[size++] = value
    }

    fun toArray(limit: Int): FloatArray = values.copyOf(minOf(size, limit))
}
