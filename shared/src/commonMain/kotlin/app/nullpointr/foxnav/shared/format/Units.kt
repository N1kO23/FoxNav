package app.nullpointr.foxnav.shared.format

import kotlin.math.roundToInt

/** "23 min" or "1 h 52 min". */
fun formatDuration(seconds: Double): String {
    val minutes = (seconds / 60).roundToInt().coerceAtLeast(1)
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}

/**
 * Metric distance the way a driver reads it: "50 m" and "850 m" (to 10 m), "18.4 km" below
 * 100 km, "189 km" above. [decimalSeparator] follows the user's locale (Finnish uses ',').
 */
fun formatDistance(meters: Double, decimalSeparator: Char = '.'): String = when {
    meters < 995 -> "${((meters / 10).roundToInt() * 10).coerceAtLeast(0)} m"
    meters < 99_950 -> {
        val tenths = (meters / 100).roundToInt()
        "${tenths / 10}$decimalSeparator${tenths % 10} km"
    }
    else -> "${(meters / 1000).roundToInt()} km"
}

/** Speed in km/h from metres per second, rounded to a whole number. */
fun kilometersPerHour(metersPerSecond: Double): Int = (metersPerSecond * 3.6).roundToInt()
