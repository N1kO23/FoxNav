package app.nullpointr.foxnav.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.io.File

/**
 * Plays voice pack clips one after the other as a single sound, on the navigation-guidance
 * stream with transient ducking focus, like the text-to-speech guidance.
 */
class ClipPlayer(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).build()
    private var track: AudioTrack? = null

    /** Plays [clips] joined with a short pause; false if one can't be read. */
    @Synchronized
    fun play(clips: List<File>): Boolean {
        val parts = clips.map { WavClip.read(it) ?: return false }
        val gap = ShortArray(WavClip.SAMPLE_RATE * GAP_MS / 1000)
        val joined = parts.flatMapIndexed { index, part -> if (index == 0) part.asList() else gap.asList() + part.asList() }.toShortArray()
        if (joined.isEmpty()) return false
        stop()
        val format = AudioFormat.Builder().setSampleRate(WavClip.SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val newTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(joined.size * 2)
            .build()
        newTrack.write(joined, 0, joined.size)
        newTrack.notificationMarkerPosition = joined.size
        newTrack.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(finished: AudioTrack) = release(finished)
            override fun onPeriodicNotification(track: AudioTrack) = Unit
        })
        audio.requestAudioFocus(focus)
        newTrack.play()
        track = newTrack
        return true
    }

    @Synchronized
    fun stop() {
        track?.let(::release)
    }

    @Synchronized
    private fun release(finished: AudioTrack) {
        runCatching { finished.stop() }
        finished.release()
        if (track === finished) {
            track = null
            audio.abandonAudioFocusRequest(focus)
        }
    }

    private companion object {
        const val GAP_MS = 90
    }
}
