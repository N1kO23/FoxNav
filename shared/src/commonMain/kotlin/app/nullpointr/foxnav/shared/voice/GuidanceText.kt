package app.nullpointr.foxnav.shared.voice

/**
 * Valhalla's spoken guidance, mended where its translations fall short. Its Finnish says how far
 * ahead with the English "In": "In 200 metriä, Käänny vasemmalle" for "200 metrin päästä käänny
 * vasemmalle".
 */
fun mendedGuidance(text: String): String = FINNISH_AHEAD.replace(text) { match ->
    val (amount, unit, next) = match.destructured
    (if (amount == "puoli ") "puolen " else amount) + FINNISH_GENITIVE.getValue(unit) + " päästä " + next.lowercase()
}

/** "In <LENGTH>, <CUE>" with Valhalla's Finnish lengths: "200 metriä", "1 kilometri", "puoli mailia", "alle 10 jalkaa"... */
private val FINNISH_AHEAD = Regex("""^In ((?:alle )?\d+(?:[.,]\d+)? |puoli |neljännes)(kilometriä|kilometri|metriä|mailia|maili|jalkaa), (\p{L})""")

private val FINNISH_GENITIVE = mapOf(
    "kilometriä" to "kilometrin",
    "kilometri" to "kilometrin",
    "metriä" to "metrin",
    "mailia" to "mailin",
    "maili" to "mailin",
    "jalkaa" to "jalan",
)
