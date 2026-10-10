package app.nullpointr.foxnav.voice

import java.util.Locale

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
