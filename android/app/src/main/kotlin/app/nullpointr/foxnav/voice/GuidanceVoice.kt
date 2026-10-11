package app.nullpointr.foxnav.voice

import android.util.Log
import app.nullpointr.foxnav.settings.Settings
import app.nullpointr.foxnav.shared.voice.Phrases
import com.stadiamaps.ferrostar.core.AndroidTtsObserver
import com.stadiamaps.ferrostar.core.SpokenInstructionObserver
import kotlinx.coroutines.flow.StateFlow
import uniffi.ferrostar.SpokenInstruction
import java.util.UUID

/** Something to say: voice pack phrases if the pack has them all, otherwise [text] for text-to-speech. */
class Utterance(val phrases: List<String>?, val text: String)

/**
 * The voice FoxNav speaks with: the chosen voice pack where it has every phrase a sentence needs,
 * the phone's text-to-speech otherwise (street names, numbers it hasn't recorded, other languages).
 * Mute is shared, so the mute button silences both.
 */
class GuidanceVoice(
    private val tts: AndroidTtsObserver,
    private val packs: VoicePacks,
    private val clips: ClipPlayer,
    private val settings: StateFlow<Settings>,
) : SpokenInstructionObserver {
    /** The upcoming manoeuvre's type and modifier names, e.g. ("TURN", "LEFT"); set by the navigation core's owner. */
    @Volatile
    var currentManeuver: () -> Pair<String, String?>? = { null }

    override fun onSpokenInstructionTrigger(spokenInstruction: SpokenInstruction) {
        if (isMuted) return
        val phrases = currentManeuver()
            ?.let { (type, modifier) -> Phrases.maneuver(type, modifier) }
            ?.let { Phrases.guidance(it, spokenInstruction.triggerDistanceBeforeManeuver, settings.value.units) }
        if (!playFromPack(phrases)) tts.onSpokenInstructionTrigger(spokenInstruction)
    }

    /** Says [utterance], from the pack if it can. */
    fun say(utterance: Utterance) {
        if (isMuted) return
        if (!playFromPack(utterance.phrases)) tts.onSpokenInstructionTrigger(SpokenInstruction(utterance.text, null, 0.0, UUID.randomUUID()))
    }

    private fun playFromPack(phrases: List<String>?): Boolean {
        val pack = settings.value.voicePack.takeIf { it.isNotEmpty() }?.let(packs::find) ?: return false
        if (phrases == null || !pack.has(phrases)) return false
        Log.i(TAG, "Voice pack: ${phrases.joinToString(" + ")}")
        return clips.play(phrases.map { pack.clip(it)!! })
    }

    override fun stopAndClearQueue() {
        clips.stop()
        tts.stopAndClearQueue()
    }

    override fun setMuted(muted: Boolean) {
        if (muted) clips.stop()
        tts.setMuted(muted)
    }

    override val muteState: StateFlow<Boolean> get() = tts.muteState

    private companion object {
        const val TAG = "FoxNavVoice"
    }
}
