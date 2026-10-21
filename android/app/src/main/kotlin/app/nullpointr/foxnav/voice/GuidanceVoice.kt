package app.nullpointr.foxnav.voice

import android.util.Log
import app.nullpointr.foxnav.settings.Settings
import app.nullpointr.foxnav.shared.voice.Phrases
import app.nullpointr.foxnav.shared.voice.mendedGuidance
import com.stadiamaps.ferrostar.core.AndroidTtsObserver
import com.stadiamaps.ferrostar.core.SpokenInstructionObserver
import kotlinx.coroutines.flow.StateFlow
import uniffi.ferrostar.SpokenInstruction
import java.util.UUID
import kotlin.math.roundToInt

/** Something to say: voice pack phrases if the pack has them all, otherwise [text] for text-to-speech. */
class Utterance(val phrases: List<String>?, val text: String)

/**
 * The voice FoxNav speaks with: the chosen voice pack where it has every phrase a sentence needs,
 * the phone's text-to-speech otherwise (street names, numbers it hasn't recorded, other languages).
 * Mute is shared, so the mute button silences both.
 *
 * Ferrostar's core stops and mutes it, but doesn't say when to speak: [RouteAnnouncer] does.
 */
class GuidanceVoice(
    private val tts: AndroidTtsObserver,
    private val packs: VoicePacks,
    private val clips: ClipPlayer,
    private val settings: StateFlow<Settings>,
) : SpokenInstructionObserver {
    /** Ferrostar's timing, where Valhalla placed each instruction: [RouteAnnouncer] times them for the car's speed instead. */
    override fun onSpokenInstructionTrigger(spokenInstruction: SpokenInstruction) = Unit

    /**
     * Says a route instruction, [heardAtM] before its [maneuver] and [then] the one right after
     * it, if the instruction tells both: from the pack with the distance as it will be heard, or
     * [text] aloud.
     */
    fun announce(text: String, maneuver: Phrases.Maneuver?, heardAtM: Double, then: Phrases.Maneuver? = null) {
        if (isMuted) return
        val phrases = maneuver?.let { Phrases.guidance(it, heardAtM, settings.value.units, then) }
        val spoken = mendedGuidance(text)
        Log.i(TAG, "Heard ${heardAtM.roundToInt()} m before: $spoken")
        if (!playFromPack(phrases, spoken, maneuver)) tts.onSpokenInstructionTrigger(SpokenInstruction(spoken, null, heardAtM, UUID.randomUUID()))
    }

    /** Says [utterance], from the pack if it can. */
    fun say(utterance: Utterance) {
        if (isMuted) return
        if (!playFromPack(utterance.phrases, utterance.text, null)) tts.onSpokenInstructionTrigger(SpokenInstruction(utterance.text, null, 0.0, UUID.randomUUID()))
    }

    private fun playFromPack(phrases: List<String>?, text: String, maneuver: Phrases.Maneuver?): Boolean {
        val pack = settings.value.voicePack.takeIf { it.isNotEmpty() }?.let(packs::find) ?: return false
        // Why the phone's speech says this one: no phrases for it (a departure, a manoeuvre the
        // pack doesn't cover), or phrases the pack hasn't recorded.
        if (phrases == null) {
            Log.i(TAG, "Phone's speech, no phrases for ${maneuver ?: "this"}: $text")
            return false
        }
        // All of it, or what a pack made before some phrases existed can say of it.
        val sayable = listOfNotNull(phrases, Phrases.plainer(phrases)).firstOrNull { candidate -> candidate.all { pack.clip(it) != null } }
        if (sayable == null) {
            Log.i(TAG, "Phone's speech, the pack lacks ${phrases.filter { pack.clip(it) == null }.joinToString()}: $text")
            return false
        }
        // A very short first step, or arriving, brings the same words twice in a moment: once is enough.
        val now = System.currentTimeMillis()
        if (sayable == lastPhrases && now - lastPlayedMs < REPEAT_MS) {
            Log.i(TAG, "Voice pack, said a moment ago: ${sayable.joinToString(" + ")}")
            return true
        }
        Log.i(TAG, "Voice pack: ${sayable.joinToString(" + ")}")
        lastPhrases = sayable
        lastPlayedMs = now
        return clips.play(sayable.map { pack.clip(it)!! })
    }

    @Volatile
    private var lastPhrases: List<String>? = null

    @Volatile
    private var lastPlayedMs = 0L

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
        const val REPEAT_MS = 5_000L
    }
}
