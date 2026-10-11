package app.nullpointr.foxnav.voice

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import app.nullpointr.foxnav.settings.Settings
import app.nullpointr.foxnav.shared.format.UnitSystem
import java.util.Locale

/** Languages Valhalla guides in that FoxNav offers, as Valhalla tags with their own names. */
val GUIDANCE_LANGUAGES = listOf(
    "fi-FI" to "Suomi",
    "sv-SE" to "Svenska",
    "nb-NO" to "Norsk",
    "da-DK" to "Dansk",
    "en-US" to "English",
)

/**
 * The language for spoken and written guidance: the device language when Valhalla has it for the
 * Nordics, otherwise English.
 */
fun guidanceLanguage(locale: Locale = Locale.getDefault()): String = when (locale.language) {
    "fi" -> "fi-FI"
    "sv" -> "sv-SE"
    "nb", "no", "nn" -> "nb-NO"
    "da" -> "da-DK"
    else -> "en-US"
}

/** The guidance language chosen in the settings, or the phone's. */
fun Settings.effectiveLanguage(): String = voiceLanguage.ifEmpty { guidanceLanguage() }

/**
 * The phone's voices for [language] that work without a connection (guidance can't wait for the
 * network on a back road), the language's home country first; empty before the engine is ready.
 */
fun voicesFor(tts: TextToSpeech?, language: String): List<Voice> {
    val wanted = Locale.forLanguageTag(language)
    val all = runCatching { tts?.voices }.getOrNull().orEmpty()
    return all
        .filter { it.locale.language == wanted.language || (wanted.language == "nb" && it.locale.language in setOf("no", "nb")) }
        .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features && !it.isNetworkConnectionRequired }
        .sortedWith(compareBy<Voice>({ it.locale.country != wanted.country }, { it.locale.country }, { it.name }))
}

/** "United States 2": the voice's country, numbered among that country's voices in [voices]. */
fun voiceLabel(voice: Voice, voices: List<Voice>): String {
    val country = voice.locale.displayCountry.ifEmpty { voice.locale.displayLanguage }
    val sameCountry = voices.filter { it.locale.country == voice.locale.country }
    return if (sameCountry.size == 1) country else "$country ${sameCountry.indexOf(voice) + 1}"
}

/** Points the engine at the chosen language, voice and speed. */
fun applyVoiceSettings(tts: TextToSpeech, settings: Settings) {
    val language = settings.effectiveLanguage()
    val voice = voicesFor(tts, language).firstOrNull { it.name == settings.voiceName }
    if (voice != null) tts.voice = voice else tts.language = Locale.forLanguageTag(language)
    tts.setSpeechRate(settings.speechRate)
}

/** A sentence like the guidance, to hear the voice. */
fun sampleSentence(language: String, units: UnitSystem): String {
    val imperial = units == UnitSystem.IMPERIAL
    return when (language.substringBefore('-')) {
        "fi" -> "300 metrin päästä käänny vasemmalle."
        "sv" -> "Om 300 meter, sväng vänster."
        "nb" -> "Om 300 meter, ta til venstre."
        "da" -> "Om 300 meter, drej til venstre."
        else -> if (imperial) "In 1000 feet, turn left." else "In 300 metres, turn left."
    }
}
