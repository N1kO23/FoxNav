package app.nullpointr.foxnav.shared.voice

import app.nullpointr.foxnav.shared.format.UnitSystem
import kotlin.math.abs
import kotlin.math.ln

/**
 * What a voice pack says, as clips: guidance is a distance and a manoeuvre ("In 300 metres" +
 * "turn left"), a warning is what's ahead and how far. Recorded voices can't say street names
 * or arbitrary numbers, so distances snap to the nearest recorded one, and anything without a
 * clip falls back to text-to-speech.
 */
object Phrases {
    /** One recordable phrase: its key in a pack, and what to say when recording it. */
    data class Phrase(val key: String, val prompt: String, val group: Group)

    enum class Group(val title: String) { DISTANCES("Distances"), MANEUVERS("Turns and lanes"), WARNINGS("Warnings") }

    private val METRIC = listOf(100 to "In 100 metres", 200 to "In 200 metres", 300 to "In 300 metres", 400 to "In 400 metres", 500 to "In 500 metres", 800 to "In 800 metres", 1000 to "In 1 kilometre", 1500 to "In 1.5 kilometres", 2000 to "In 2 kilometres", 3000 to "In 3 kilometres", 5000 to "In 5 kilometres")
    private val IMPERIAL = listOf(152 to "In 500 feet", 305 to "In 1000 feet", 402 to "In a quarter mile", 805 to "In half a mile", 1609 to "In 1 mile", 3219 to "In 2 miles", 8047 to "In 5 miles")

    private fun metricKey(metres: Int) = if (metres < 1000) "in_${metres}_m" else "in_${metres / 1000}${if (metres % 1000 != 0) "_${metres % 1000 / 100}" else ""}_km"
    private fun imperialKey(prompt: String) = "in_" + prompt.removePrefix("In ").lowercase().replace(" ", "_")

    private val MANEUVERS = listOf(
        "turn_left" to "Turn left", "turn_right" to "Turn right",
        "bear_left" to "Bear left", "bear_right" to "Bear right",
        "sharp_left" to "Turn sharp left", "sharp_right" to "Turn sharp right",
        "keep_left" to "Keep left", "keep_right" to "Keep right",
        "go_straight" to "Go straight ahead", "continue" to "Continue on this road",
        "u_turn" to "Make a U-turn", "merge" to "Merge",
        "ramp_left" to "Take the ramp on the left", "ramp_right" to "Take the ramp on the right",
        "exit_left" to "Take the exit on the left", "exit_right" to "Take the exit on the right",
        "roundabout" to "Enter the roundabout", "exit_roundabout" to "Exit the roundabout",
        "arriving" to "Your destination is ahead", "arrive" to "You have arrived",
    )

    private val WARNINGS = listOf(
        "speed_camera" to "Speed camera", "red_light_camera" to "Red light camera", "average_speed_check" to "Average speed check",
        "police" to "Police", "speed_check" to "Speed check", "accident" to "Accident", "hazard" to "Hazard",
        "stopped_vehicle" to "Stopped vehicle", "roadworks" to "Roadworks", "slippery_road" to "Slippery road", "animal" to "Animal on the road",
        "road_closed" to "Road closed",
    )

    /** Every phrase a complete pack has, in recording order. */
    val ALL: List<Phrase> =
        METRIC.map { (metres, prompt) -> Phrase(metricKey(metres), prompt, Group.DISTANCES) } +
            IMPERIAL.map { (_, prompt) -> Phrase(imperialKey(prompt), prompt, Group.DISTANCES) } +
            MANEUVERS.map { (key, prompt) -> Phrase(key, prompt, Group.MANEUVERS) } +
            WARNINGS.map { (key, prompt) -> Phrase(key, prompt, Group.WARNINGS) }

    /** "In 300 metres": the recorded distance nearest to [metres] (on a log scale), or null when it's too near or too far to say. */
    fun distance(metres: Double, units: UnitSystem): String? {
        if (metres < NOW_M || metres > FAR_M) return null
        val choices = if (units == UnitSystem.METRIC) METRIC.map { it.first to metricKey(it.first) } else IMPERIAL.map { it.first to imperialKey(it.second) }
        return choices.minBy { (at, _) -> abs(ln(at / metres)) }.second
    }

    /**
     * The phrase for a manoeuvre, from Ferrostar's (OSRM's) maneuver type and modifier names, like
     * "TURN" and "SLIGHT_LEFT". Null for manoeuvres a pack doesn't cover (departures, notices).
     */
    fun maneuver(type: String, modifier: String?): String? {
        val left = modifier in setOf("LEFT", "SLIGHT_LEFT", "SHARP_LEFT")
        val right = modifier in setOf("RIGHT", "SLIGHT_RIGHT", "SHARP_RIGHT")
        return when (type) {
            "TURN", "END_OF_ROAD" -> when (modifier) {
                "LEFT" -> "turn_left"
                "RIGHT" -> "turn_right"
                "SLIGHT_LEFT" -> "bear_left"
                "SLIGHT_RIGHT" -> "bear_right"
                "SHARP_LEFT" -> "sharp_left"
                "SHARP_RIGHT" -> "sharp_right"
                "U_TURN" -> "u_turn"
                "STRAIGHT" -> "go_straight"
                else -> null
            }
            "FORK" -> if (left) "keep_left" else if (right) "keep_right" else "go_straight"
            "MERGE" -> "merge"
            "ON_RAMP" -> if (left) "ramp_left" else "ramp_right"
            "OFF_RAMP" -> if (left) "exit_left" else "exit_right"
            "ROUNDABOUT", "ROTARY", "ROUNDABOUT_TURN" -> "roundabout"
            "EXIT_ROUNDABOUT", "EXIT_ROTARY" -> "exit_roundabout"
            "CONTINUE", "NEW_NAME" -> if (modifier == "U_TURN") "u_turn" else "continue"
            "ARRIVE" -> "arrive"
            else -> null
        }
    }

    /**
     * Guidance for [maneuver] at [metres]: the distance first when it's worth saying, else the
     * manoeuvre alone. Arrival is announced ahead ("Your destination is ahead"), then on arrival.
     */
    fun guidance(maneuver: String, metres: Double, units: UnitSystem): List<String> {
        if (maneuver == "arrive") return listOf(if (metres >= NOW_M) "arriving" else "arrive")
        if (maneuver == "continue") return listOf(maneuver)
        return listOfNotNull(distance(metres, units), maneuver)
    }

    /** A camera or report ahead: what it is, and in the full style how far. */
    fun warning(kind: String, metres: Double, units: UnitSystem, short: Boolean): List<String>? {
        if (WARNINGS.none { it.first == kind }) return null
        return if (short) listOf(kind) else listOfNotNull(kind, distance(metres, units))
    }

    /** Closer than this, the manoeuvre is now: no distance. */
    private const val NOW_M = 70.0

    /** Further than this, there's no recorded distance to round to. */
    private const val FAR_M = 6_500.0
}
