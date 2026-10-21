package app.nullpointr.foxnav.shared.speed

import app.nullpointr.foxnav.shared.FoxNavJson
import app.nullpointr.foxnav.shared.convoy.project
import app.nullpointr.foxnav.shared.geo.GeoPoint
import kotlinx.serialization.Serializable

/**
 * What a region's map gets wrong or doesn't know about speed limits (protocol/SPEC.md §3.2), keyed
 * by OpenStreetMap way: limits that depend on the date, such as Nordic winter limits, which routing
 * engines report as the plain limit all year, and limits for ways the map has none for.
 */
@Serializable
data class SpeedLimitPack(
    val format: Int,
    val region: String,
    val generated: String? = null,
    val attribution: String? = null,
    val ways: List<WayLimits> = emptyList(),
    /** Limits for ways the map has none for: km/h -> way IDs, sorted, each after the first as the difference from the one before. */
    val fill: Map<String, List<Long>> = emptyMap(),
) {
    /** [fill] as way ID -> km/h. */
    fun fillLimits(): Map<Long, Int> = buildMap {
        for ((limit, deltas) in fill) {
            val kmh = limit.toIntOrNull()?.takeIf { it in 1..200 } ?: continue
            var way = 0L
            for (delta in deltas) {
                way += delta
                put(way, kmh)
            }
        }
    }

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
    /** What the routing engine reports for the way, km/h; absent where it reports none. */
    val maxspeed: Int? = null,
    /** The way's limit instead of the engine's (or where it has none). */
    val limit: Int? = null,
    /** Stretches of the way with another limit, such as a village's 50 on a long rural road. */
    val sections: List<Section> = emptyList(),
    /** Limits for parts of the year; the first that applies wins. */
    val conditional: List<ConditionalLimit> = emptyList(),
)

@Serializable
data class Section(
    val maxspeed: Int,
    /** The stretch, as `[lon, lat]` pairs (GeoJSON order). */
    val line: List<List<Double>>,
) {
    private val points: List<GeoPoint> by lazy { line.mapNotNull { if (it.size >= 2) GeoPoint(it[1], it[0]) else null } }
    private val lengthM: Double by lazy { points.zipWithNext { a, b -> a.distanceTo(b) }.sum() }

    /** Whether [point] is on the stretch: beside it, or at most a little past either end. */
    fun contains(point: GeoPoint): Boolean {
        val at = project(points, point) ?: return false
        val pastAnEnd = at.alongM <= END_EPSILON_M || at.alongM >= lengthM - END_EPSILON_M
        return at.offM <= if (pastAnEnd) PAST_END_M else ON_SECTION_M
    }

    private companion object {
        /** GPS error and the other lane. */
        const val ON_SECTION_M = 25.0

        /** Past an end, the distance is along the road: only a little, or the change comes early. */
        const val PAST_END_M = 10.0
        const val END_EPSILON_M = 0.5
    }
}

@Serializable
data class ConditionalLimit(
    val maxspeed: Int,
    /** First day, `MM-DD` every year or `YYYY-MM-DD` once. */
    val from: String,
    /** Last day, inclusive, in the same form; a yearly range may wrap round the new year. */
    val to: String,
    /** `winter` or `summer` when the limit is seasonal, for showing it. */
    val season: String? = null,
    /** The weekdays it holds on, `Mo` to `Su`; empty: every day. */
    val days: List<String> = emptyList(),
    /**
     * The hours it holds, `[["07:00", "17:00"]]`, from the first minute to just before the second;
     * empty: all day. Hours that end before they start run past midnight, into the next day.
     */
    val times: List<List<String>> = emptyList(),
) {
    /**
     * Whether it applies on [date] at [minuteOfDay] (0–1439, local time). A rule for set hours
     * doesn't apply when the time isn't known, and a rule it can't read never applies.
     */
    fun appliesOn(date: Day, minuteOfDay: Int? = null): Boolean {
        if (!inDates(date)) return false
        val weekday = date.weekday()
        val onDays = days.mapNotNull { WEEKDAYS.indexOf(it).takeIf { index -> index >= 0 } }.toSet()
        if (days.isNotEmpty() && onDays.size != days.size) return false
        fun on(day: Int) = onDays.isEmpty() || day in onDays
        if (times.isEmpty()) return on(weekday)
        val minute = minuteOfDay ?: return false
        return times.any { span ->
            val start = span.getOrNull(0)?.let(::minutes) ?: return@any false
            val end = span.getOrNull(1)?.let(::minutes) ?: return@any false
            when {
                start < end -> on(weekday) && minute in start until end
                // Past midnight: the evening of a listed day, or the early hours after one.
                else -> (on(weekday) && minute >= start) || (on((weekday + 6) % 7) && minute < end)
            }
        }
    }

    private fun inDates(date: Day): Boolean {
        val start = DayPattern.parse(from) ?: return false
        val end = DayPattern.parse(to) ?: return false
        if (start.year != null && end.year != null) return date.ordinal() in start.ordinal(date.year)..end.ordinal(date.year)
        if (start.year != null || end.year != null) return false
        val today = date.month * 100 + date.day
        val first = start.month * 100 + start.day
        val last = end.month * 100 + end.day
        return if (first <= last) today in first..last else today >= first || today <= last
    }

    private companion object {
        val WEEKDAYS = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
        private val TIME = Regex("""^([01]\d|2[0-4]):([0-5]\d)$""")

        /** Minutes from midnight of `HH:MM`; `24:00` is the end of the day. */
        fun minutes(text: String): Int? {
            val (hours, minutes) = TIME.matchEntire(text)?.destructured ?: return null
            return (hours.toInt() * 60 + minutes.toInt()).takeIf { it <= 24 * 60 }
        }
    }
}

/** A calendar day where the phone is. */
data class Day(val year: Int, val month: Int, val day: Int) {
    internal fun ordinal() = year * 10_000 + month * 100 + day

    /** 0 for Monday to 6 for Sunday (Sakamoto's method). */
    internal fun weekday(): Int {
        val offsets = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
        val y = if (month < 3) year - 1 else year
        val sundayFirst = (y + y / 4 - y / 100 + y / 400 + offsets[month - 1] + day) % 7
        return (sundayFirst + 6) % 7
    }
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
class SpeedLimits(ways: List<WayLimits> = emptyList(), private val fill: Map<Long, Int> = emptyMap()) {
    private val byWay = ways.associateBy { it.way }

    constructor(packs: Collection<SpeedLimitPack>) : this(packs.flatMap { it.ways }, packs.fold(emptyMap<Long, Int>()) { all, pack -> all + pack.fillLimits() })

    /**
     * The limit at [at] on [wayId] on [date] at [minuteOfDay], where the routing engine reports
     * [mapKmh]. The pack's entry for the way counts only while the engine reports what the pack
     * says it does there: otherwise the engine is on a different road (say, an exit ramp, while
     * the last road lookup still says motorway) or the map has changed since the pack was built.
     */
    fun limit(wayId: Long?, mapKmh: Int?, date: Day, at: GeoPoint? = null, minuteOfDay: Int? = null): Limit? {
        val filled = wayId?.let(fill::get)
        val way = wayId?.let(byWay::get)?.takeIf { mapKmh == null || mapKmh == it.maxspeed }
            ?: return (mapKmh ?: filled)?.let { Limit(it) }
        at?.let { point -> way.sections.firstOrNull { it.contains(point) } }?.let { return Limit(it.maxspeed) }
        val plain = way.limit ?: mapKmh ?: filled ?: way.maxspeed
        val conditional = way.conditional.firstOrNull { it.appliesOn(date, minuteOfDay) }
            // A replaced limit came from elsewhere: the map's winter limit only counts below it.
            ?.takeIf { way.limit == null || plain == null || it.maxspeed < plain }
        return conditional?.let { Limit(it.maxspeed, it.season) } ?: plain?.let { Limit(it) }
    }
}
