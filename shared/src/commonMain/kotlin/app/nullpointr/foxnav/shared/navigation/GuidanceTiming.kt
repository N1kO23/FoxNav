package app.nullpointr.foxnav.shared.navigation

import app.nullpointr.foxnav.shared.format.UnitSystem
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/** One of a route step's spoken instructions, where the router placed it: [triggerM] before the step's manoeuvre. */
data class Cue(val id: String, val text: String, val triggerM: Double)

/** A cue to say now, and how far before the manoeuvre the driver hears it begin. */
data class Announcement(val cue: Cue, val heardAtM: Double)

/**
 * When to say a route step's spoken instructions. Valhalla places them for the speed it predicts:
 * the last one ("Turn left onto Annankatu") 10 s before the manoeuvre but never under 40 m, one
 * that says how far ("In 200 metres, ...") 35 s before. In town its predictions count the waits at
 * junctions, 10–20 km/h, so at 50 km/h the last one starts under 3 s before the turn. And it's said
 * once a fix past that point has come and the speech engine has started: further on still.
 *
 * So, at the car's speed:
 * - each cue starts [leadS] before the car gets to where it's meant to be heard;
 * - the last is heard [finalS] or more before the manoeuvre;
 * - one that says how far is heard where that's so: Valhalla rounds, and its imperial distances
 *   are 1.6 times too far;
 * - one that wouldn't be over before the next begins is left out: the next is nearer the turn.
 *
 * Parked, or with no speed to go by, cues are said where Valhalla placed them.
 */
class GuidanceTiming(
    private val leadS: Double = LEAD_S,
    private val finalS: Double = FINAL_S,
) {
    private val said = LinkedHashSet<String>()
    private val speed = RouteSpeed()

    /**
     * What to say now, [distanceM] before the current step's manoeuvre: the nearest of the step's
     * [cues] (furthest first, as the router lists them) that's due, unless it's been said. Without
     * [speedMps] from the fix, [travelledM] along the route by [timeMs] gives the speed.
     */
    fun next(
        cues: List<Cue>,
        distanceM: Double,
        travelledM: Double,
        timeMs: Long,
        speedMps: Double?,
        units: UnitSystem,
        speechRate: Double = 1.0,
    ): Announcement? {
        val estimated = speed.update(travelledM, timeMs)
        val mps = (speedMps ?: estimated ?: 0.0).coerceAtLeast(0.0)
        val heardAt = heardAt(cues, mps, units, speechRate)
        // Where the car will be when the words begin.
        val atM = distanceM - mps * leadS
        val due = cues.indices.lastOrNull { i -> heardAt[i]?.let { atM <= it + SLACK_M } == true } ?: return null
        if (cues[due].id in said) return null
        // Those before it are past: they won't be said now.
        cues.take(due + 1).forEach { remember(it.id) }
        return Announcement(cues[due], max(atM, 0.0))
    }

    /** Where each of [cues] is meant to be heard at [mps], in metres before the manoeuvre; null for one left out. */
    internal fun heardAt(cues: List<Cue>, mps: Double, units: UnitSystem, speechRate: Double): List<Double?> {
        val planned = arrayOfNulls<Double>(cues.size)
        var next: Double? = null
        for (i in cues.indices.reversed()) {
            val cue = cues[i]
            val at = if (i == cues.lastIndex) max(cue.triggerM, mps * finalS) else statedDistanceM(cue.text, cue.triggerM, units) ?: cue.triggerM
            val saying = cue.text.length / (CHARS_PER_S * speechRate)
            if (next == null || at - mps * (saying + GAP_S) >= next) {
                planned[i] = at
                next = at
            }
        }
        return planned.toList()
    }

    private fun remember(id: String) {
        said += id
        if (said.size > REMEMBERED) said.remove(said.first())
    }

    private companion object {
        /**
         * From a fix to hearing the first word: the fix coming in, up to a second until the next
         * (half on average), the speech engine starting.
         */
        const val LEAD_S = 1.5

        /** Heard this long before the turn, the last cue leaves time to slow down and signal. */
        const val FINAL_S = 6.0

        /** Between one cue ending and the next beginning. */
        const val GAP_S = 1.0

        /** How fast text-to-speech reads guidance at its normal rate, roughly. */
        const val CHARS_PER_S = 14.0

        /** Valhalla places a step's first cue at its very start, which the car reaches a hair later. */
        const val SLACK_M = 5.0

        /** Cue IDs kept to tell which have been said: a few routes' worth. */
        const val REMEMBERED = 256
    }
}

/**
 * How far [text] says it is to the manoeuvre, in metres: "In 200 metres, ..." is 200. Only a
 * distance within a factor of two of [triggerM], where Valhalla placed the cue, counts: other
 * numbers are exits and road numbers. Null when it says none.
 */
internal fun statedDistanceM(text: String, triggerM: Double, units: UnitSystem): Double? {
    if (triggerM <= 0.0) return null
    val scales = if (units == UnitSystem.METRIC) listOf(1.0, 1_000.0) else listOf(FOOT_M, MILE_M)
    val numbers = NUMBER.findAll(text).mapNotNull { it.value.replace(',', '.').toDoubleOrNull() }.flatMap { n -> scales.map { n * it } }
    val fractions = if (units == UnitSystem.IMPERIAL) MILE_FRACTIONS.filter { (words, _) -> words.containsMatchIn(text) }.map { it.second * MILE_M } else emptyList()
    return (numbers + fractions)
        .filter { it / triggerM in 0.5..2.0 }
        .minByOrNull { abs(ln(it / triggerM)) }
}

private val NUMBER = Regex("""\d+(?:[.,]\d+)?""")

private const val FOOT_M = 0.3048
private const val MILE_M = 1_609.344

/** Valhalla's words for parts of a mile, in the languages FoxNav guides in. */
private val MILE_FRACTIONS = listOf(
    Regex("""\b(a half mile|half a mile|puoli mailia|en halv)\b""", RegexOption.IGNORE_CASE) to 0.5,
    Regex("""\b(a quarter mile|neljännesmaili|en kvarts?)\b""", RegexOption.IGNORE_CASE) to 0.25,
)

/** Speed along the route over the last few seconds, from how far the car has come: for fixes that have none. */
private class RouteSpeed {
    private val samples = ArrayDeque<Pair<Long, Double>>()

    fun update(travelledM: Double, timeMs: Long): Double? {
        val last = samples.lastOrNull()
        // A new route counts from nought.
        if (last != null && (travelledM < last.second || timeMs < last.first)) samples.clear()
        if (samples.lastOrNull()?.first == timeMs) samples.removeLast()
        samples.addLast(timeMs to travelledM)
        while (samples.size > 2 && timeMs - samples[1].first >= WINDOW_MS) samples.removeFirst()
        val (sinceMs, fromM) = samples.first()
        return if (timeMs - sinceMs >= MIN_SPAN_MS) (travelledM - fromM) * 1_000.0 / (timeMs - sinceMs) else null
    }

    private companion object {
        const val WINDOW_MS = 3_000L
        const val MIN_SPAN_MS = 500L
    }
}
