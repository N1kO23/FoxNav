package app.nullpointr.foxnav.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import app.nullpointr.foxnav.R

/** Short alert sounds, played on the navigation-guidance audio stream like spoken instructions. */
class Chime(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val kinKon = pool.load(context, R.raw.kinkon, 1)

    /** The over-limit chime (design/sounds/kinkon.py). */
    fun playKinKon() {
        pool.play(kinKon, 1f, 1f, 1, 0, 1f)
    }
}
