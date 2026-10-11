package app.nullpointr.foxnav.voice

import android.util.Log
import app.nullpointr.foxnav.settings.Settings
import app.nullpointr.foxnav.shared.voice.Phrases
import com.stadiamaps.ferrostar.core.AndroidTtsObserver
import com.stadiamaps.ferrostar.core.SpokenInstructionObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
    /**
     * The manoeuvre an instruction is about, as type and modifier names like ("TURN", "LEFT"):
     * its route step's. Set by the navigation core's owner; it may wait a moment for the core.
     */
    @Volatile
    var maneuverFor: suspend (SpokenInstruction) -> Pair<String, String?>? = { null }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // One at a time and in order: an instruction may wait a moment to learn its manoeuvre.
    private val instructions = Channel<SpokenInstruction>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (instruction in instructions) speakInstruction(instruction)
        }
    }

    override fun onSpokenInstructionTrigger(spokenInstruction: SpokenInstruction) {
        if (isMuted) return
        instructions.trySend(spokenInstruction)
    }

    private suspend fun speakInstruction(spokenInstruction: SpokenInstruction) {
        if (isMuted) return
        val maneuver = maneuverFor(spokenInstruction)
        val phrases = maneuver
            ?.let { (type, modifier) -> Phrases.maneuver(type, modifier) }
            ?.let { Phrases.guidance(it, spokenInstruction.triggerDistanceBeforeManeuver, settings.value.units) }
        if (!playFromPack(phrases, spokenInstruction.text, maneuver)) tts.onSpokenInstructionTrigger(spokenInstruction)
    }

    /** Says [utterance], from the pack if it can. */
    fun say(utterance: Utterance) {
        if (isMuted) return
        if (!playFromPack(utterance.phrases, utterance.text, null)) tts.onSpokenInstructionTrigger(SpokenInstruction(utterance.text, null, 0.0, UUID.randomUUID()))
    }

    private fun playFromPack(phrases: List<String>?, text: String, maneuver: Pair<String, String?>?): Boolean {
        val pack = settings.value.voicePack.takeIf { it.isNotEmpty() }?.let(packs::find) ?: return false
        // Why the phone's speech says this one: no phrases for it (a departure, a manoeuvre the
        // pack doesn't cover), or phrases the pack hasn't recorded.
        if (phrases == null) {
            Log.i(TAG, "Phone's speech, no phrases for ${maneuver ?: "this"}: $text")
            return false
        }
        val missing = phrases.filter { pack.clip(it) == null }
        if (missing.isNotEmpty()) {
            Log.i(TAG, "Phone's speech, the pack lacks ${missing.joinToString()}: $text")
            return false
        }
        // A very short first step, or arriving, brings the same words twice in a moment: once is enough.
        val now = System.currentTimeMillis()
        if (phrases == lastPhrases && now - lastPlayedMs < REPEAT_MS) {
            Log.i(TAG, "Voice pack, said a moment ago: ${phrases.joinToString(" + ")}")
            return true
        }
        Log.i(TAG, "Voice pack: ${phrases.joinToString(" + ")}")
        lastPhrases = phrases
        lastPlayedMs = now
        return clips.play(phrases.map { pack.clip(it)!! })
    }

    @Volatile
    private var lastPhrases: List<String>? = null

    @Volatile
    private var lastPlayedMs = 0L

    override fun stopAndClearQueue() {
        while (instructions.tryReceive().isSuccess) Unit
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
