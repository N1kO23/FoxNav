package app.nullpointr.foxnav.shared.format

import kotlin.math.roundToInt

/** "23 min" or "1 h 52 min". */
fun formatDuration(seconds: Double): String {
    val minutes = (seconds / 60).roundToInt().coerceAtLeast(1)
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}

/** Metric (km/h, metres) or imperial (mph, miles and feet). Data stays metric; only display changes. */
enum class UnitSystem {
    METRIC,
    IMPERIAL;

    val speedLabel: String get() = if (this == METRIC) "km/h" else "mph"
}

/**
 * Distance the way a driver reads it. Metric: "50 m" and "850 m" (to 10 m), "18.4 km" below
 * 100 km, "189 km" above. Imperial: "450 ft" (to 50 ft) below a tenth of a mile, then "2.4 mi"
 * and "189 mi". [decimalSeparator] follows the user's locale (Finnish uses ',').
 */
fun formatDistance(meters: Double, decimalSeparator: Char = '.', units: UnitSystem = UnitSystem.METRIC): String {
    if (units == UnitSystem.IMPERIAL) {
        val miles = meters / METERS_PER_MILE
        return when {
            miles < 0.095 -> "${((meters / METERS_PER_FOOT / 50).roundToInt() * 50).coerceAtLeast(0)} ft"
            miles < 99.95 -> {
                val tenths = (miles * 10).roundToInt()
                "${tenths / 10}$decimalSeparator${tenths % 10} mi"
            }
            else -> "${miles.roundToInt()} mi"
        }
    }
    return when {
        meters < 995 -> "${((meters / 10).roundToInt() * 10).coerceAtLeast(0)} m"
        meters < 99_950 -> {
            val tenths = (meters / 100).roundToInt()
            "${tenths / 10}$decimalSeparator${tenths % 10} km"
        }
        else -> "${(meters / 1000).roundToInt()} km"
    }
}

/** A distance to say out loud, coarser than to read: "400 metres", "1.5 kilometres", "800 feet", "0.5 miles". */
fun spokenDistance(meters: Double, units: UnitSystem = UnitSystem.METRIC): String {
    if (units == UnitSystem.IMPERIAL) {
        val feet = meters / METERS_PER_FOOT
        if (feet < 1_000) return "${((feet / 100).roundToInt() * 100).coerceAtLeast(100)} feet"
        val tenths = (meters / METERS_PER_MILE * 10).roundToInt()
        return if (tenths == 10) "1 mile" else "${tenths / 10}.${tenths % 10} miles".replace(".0 ", " ")
    }
    if (meters < 950) return "${((meters / 100).roundToInt() * 100).coerceAtLeast(100)} metres"
    val tenths = (meters / 100).roundToInt()
    return if (tenths == 10) "1 kilometre" else "${tenths / 10}.${tenths % 10} kilometres".replace(".0 ", " ")
}

/** Speed in km/h from metres per second, rounded to a whole number. */
fun kilometersPerHour(metersPerSecond: Double): Int = (metersPerSecond * 3.6).roundToInt()

/** Speed in [units] from metres per second, rounded to a whole number. */
fun speedIn(metersPerSecond: Double, units: UnitSystem): Int =
    if (units == UnitSystem.METRIC) kilometersPerHour(metersPerSecond) else (metersPerSecond / METERS_PER_MILE * 3600).roundToInt()

/** A limit known in km/h, in [units]: 80 km/h is 50 mph. */
fun limitIn(kilometersPerHour: Int, units: UnitSystem): Int =
    if (units == UnitSystem.METRIC) kilometersPerHour else (kilometersPerHour * 1000 / METERS_PER_MILE).roundToInt()

private const val METERS_PER_MILE = 1609.344
private const val METERS_PER_FOOT = 0.3048
