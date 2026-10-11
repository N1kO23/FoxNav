package app.nullpointr.foxnav.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import app.nullpointr.foxnav.R
import app.nullpointr.foxnav.settings.OverLimitSound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Short alert sounds, played on the navigation-guidance audio stream like spoken instructions. */
class Chime(private val context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    // design/sounds/kinkon.py, ding.py, beep.py and alert.py
    private val kinKon = pool.load(context, R.raw.kinkon, 1)
    private val ding = pool.load(context, R.raw.ding, 1)
    private val beep = pool.load(context, R.raw.beep, 1)
    private val alert = pool.load(context, R.raw.alert, 1)

    /** The user's own over-limit sound, converted to a clip ([AudioDecoder]). */
    private val customFile = File(context.filesDir, "sounds/over_limit.wav")
    private var custom = customFile.takeIf { it.isFile }?.let { pool.load(it.path, 1) }

    val hasCustomOverLimit: Boolean get() = custom != null

    /** The over-limit warning, as chosen in the settings. */
    fun playOverLimit(sound: OverLimitSound) {
        val id = when (sound) {
            OverLimitSound.KIN_KON -> kinKon
            OverLimitSound.DING -> ding
            OverLimitSound.BEEP -> beep
            OverLimitSound.CUSTOM -> custom ?: kinKon
            OverLimitSound.OFF -> return
        }
        pool.play(id, 1f, 1f, 1, 0, 1f)
    }

    /** A camera or report ahead, when warnings are a tone rather than words. */
    fun playAlert() {
        pool.play(alert, 1f, 1f, 1, 0, 1f)
    }

    /** Makes the sound in [uri] the over-limit sound; false when the phone can't read it as sound. */
    suspend fun setCustomOverLimit(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val decoded = AudioDecoder.decode(context, uri, MAX_SECONDS) ?: return@withContext false
        val sound = Pcm.toShorts(Pcm.normalize(Pcm.toFloats(trimStart(decoded))))
        if (sound.isEmpty()) return@withContext false
        customFile.parentFile?.mkdirs()
        WavClip.write(customFile, sound)
        custom?.let(pool::unload)
        custom = pool.load(customFile.path, 1)
        true
    }

    /** Without the silence before the sound: a warning should start the moment it's due. The end rings out. */
    private fun trimStart(samples: ShortArray): ShortArray {
        val first = samples.indexOfFirst { kotlin.math.abs(it.toInt()) > START_THRESHOLD }
        return if (first < 0) ShortArray(0) else samples.copyOfRange(first, samples.size)
    }

    private companion object {
        /** Long enough for any chime; a warning longer than this would talk over the guidance. */
        const val MAX_SECONDS = 4
        const val START_THRESHOLD = 300
    }
}
