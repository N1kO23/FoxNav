package app.nullpointr.foxnav.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** Records one phrase from the microphone until cancelled (or [MAX_SECONDS]), then trims, levels and saves it. */
object ClipRecorder {
    private const val MAX_SECONDS = 6

    /** Records into [target] while the calling coroutine runs; returns false if nothing was said. Needs RECORD_AUDIO. */
    @SuppressLint("MissingPermission")
    suspend fun record(target: File, isRecording: () -> Boolean): Boolean = withContext(Dispatchers.IO) {
        val minimum = AudioRecord.getMinBufferSize(WavClip.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, WavClip.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minimum * 4)
        val samples = ShortArray(WavClip.SAMPLE_RATE * MAX_SECONDS)
        var count = 0
        try {
            recorder.startRecording()
            val chunk = ShortArray(minimum)
            while (coroutineContext.isActive && isRecording() && count < samples.size) {
                val read = recorder.read(chunk, 0, minOf(chunk.size, samples.size - count))
                if (read > 0) {
                    chunk.copyInto(samples, count, 0, read)
                    count += read
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        val words = WavClip.prepare(samples.copyOf(count))
        if (words.isEmpty()) return@withContext false
        WavClip.write(target, words)
        true
    }
}
