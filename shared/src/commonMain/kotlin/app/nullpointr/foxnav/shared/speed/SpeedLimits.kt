package app.nullpointr.foxnav.shared.speed

import app.nullpointr.foxnav.shared.FoxNavJson
import kotlinx.serialization.Serializable

/**
 * A region's speed limits that depend on the date (protocol/SPEC.md §3.2), such as Nordic winter
 * limits, which routing engines report as the plain limit all year. Keyed by OpenStreetMap way.
 */
@Serializable
data class SpeedLimitPack(
    val format: Int,
    val region: String,
    val generated: String? = null,
    val attribution: String? = null,
    val ways: List<WayLimits> = emptyList(),
) {
    companion object {
        const val FORMAT = 1

        fun parse(json: String): SpeedLimitPack {
            val pack = FoxNavJson.decodeFromString(serializer(), json)
            require(pack.format == FORMAT) { "Speed limit pack format ${pack.format}, this app reads $FORMAT" }
            return pack
        }
    }
}

@Serializable
data class WayLimits(
    /** The OpenStreetMap way, as Valhalla's `edge.way_id`. */
    val way: Long,
    /** The way's plain limit, km/h: what the routing engine reports for it. */
    val maxspeed: Int? = null,
    /** Limits for parts of the year; the first that applies wins. */
    val conditional: List<ConditionalLimit> = emptyList(),
)

@Serializable
data class ConditionalLimit(
    val maxspeed: Int,
    /** First day, `MM-DD` every year or `YYYY-MM-DD` once. */
    val from: String,
    /** Last day, inclusive, in the same form; a yearly range may wrap round the new year. */
    val to: String,
    /** `winter` or `summer` when the limit is seasonal, for showing it. */
    val season: String? = null,
) {
    /** Whether it applies on [date]. A range it can't read never applies. */
    fun appliesOn(date: Day): Boolean {
        val start = DayPattern.parse(from) ?: return false
        val end = DayPattern.parse(to) ?: return false
        if (start.year != null && end.year != null) return date.ordinal() in start.ordinal(date.year)..end.ordinal(date.year)
        if (start.year != null || end.year != null) return false
        val today = date.month * 100 + date.day
        val first = start.month * 100 + start.day
        val last = end.month * 100 + end.day
        return if (first <= last) today in first..last else today >= first || today <= last
    }
}

/** A calendar day where the phone is. */
data class Day(val year: Int, val month: Int, val day: Int) {
    internal fun ordinal() = year * 10_000 + month * 100 + day
}

private data class DayPattern(val year: Int?, val month: Int, val day: Int) {
    fun ordinal(defaultYear: Int) = (year ?: defaultYear) * 10_000 + month * 100 + day

    companion object {
        private val FORM = Regex("""^(?:(\d{4})-)?(\d{2})-(\d{2})$""")

        fun parse(text: String): DayPattern? {
            val match = FORM.matchEntire(text) ?: return null
            val (year, month, day) = match.destructured
            val pattern = DayPattern(year.toIntOrNull(), month.toInt(), day.toInt())
            return pattern.takeIf { it.month in 1..12 && it.day in 1..31 }
        }
    }
}

/** A speed limit and, for a seasonal one, its season. */
data class Limit(val kmh: Int, val season: String? = null)

/** Finds the limit in force on a way today. */
class SpeedLimits(ways: List<WayLimits> = emptyList()) {
    private val byWay = ways.associateBy { it.way }

    /**
     * The limit on [wayId] on [date], where the routing engine reports [mapKmh]. A conditional
     * limit counts only while the engine's limit matches the pack's for that way: otherwise the
     * engine is on a different road (say, an exit ramp) or the map data has changed since.
     */
    fun limit(wayId: Long?, mapKmh: Int?, date: Day): Limit? {
        val way = wayId?.let(byWay::get)
        val matches = way != null && (way.maxspeed == null || mapKmh == null || way.maxspeed == mapKmh)
        val conditional = if (matches) way!!.conditional.firstOrNull { it.appliesOn(date) } else null
        return conditional?.let { Limit(it.maxspeed, it.season) } ?: mapKmh?.let { Limit(it) }
    }
}
