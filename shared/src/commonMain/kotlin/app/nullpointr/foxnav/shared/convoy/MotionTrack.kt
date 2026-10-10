package app.nullpointr.foxnav.shared.convoy

import app.nullpointr.foxnav.shared.geo.GeoPoint
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** A position report: where a car was, how fast it went and which way, and when (Unix ms). */
data class Fix(val point: GeoPoint, val speedMps: Double, val headingDeg: Double, val timeMs: Long)

/**
 * Smooth motion for a convoy car between position reports (about one per second). The car is
 * extrapolated along its heading at its speed, and when a new report arrives, the drawn position
 * glides from where it was to the new estimate over [blendMs] instead of jumping.
 */
class MotionTrack(private val blendMs: Long = 1_000, private val maxExtrapolationMs: Long = 5_000) {
    private var fix: Fix? = null
    private var blendFrom: GeoPoint? = null
    private var blendStartMs = 0L

    /** Feeds a new report, received at [nowMs]. Reports older than the current one are ignored. */
    fun update(newFix: Fix, nowMs: Long) {
        val current = fix
        if (current != null && newFix.timeMs <= current.timeMs) return
        blendFrom = if (current != null) positionAt(nowMs) else null
        blendStartMs = nowMs
        fix = newFix
    }

    /** Where to draw the car at [nowMs], or null before the first report. */
    fun positionAt(nowMs: Long): GeoPoint? {
        val current = fix ?: return null
        val estimate = predict(current, nowMs)
        val from = blendFrom ?: return estimate
        val progress = ((nowMs - blendStartMs).toDouble() / blendMs).coerceIn(0.0, 1.0)
        if (progress >= 1.0) return estimate
        return GeoPoint(
            latitude = from.latitude + (estimate.latitude - from.latitude) * progress,
            longitude = from.longitude + (estimate.longitude - from.longitude) * progress,
        )
    }

    private fun predict(fix: Fix, nowMs: Long): GeoPoint {
        val elapsedMs = (nowMs - fix.timeMs).coerceIn(0, maxExtrapolationMs)
        return fix.point.moved(fix.speedMps * elapsedMs / 1000.0, fix.headingDeg)
    }
}

/** The point [meters] away along [bearingDeg] (great circle). */
fun GeoPoint.moved(meters: Double, bearingDeg: Double): GeoPoint {
    if (meters == 0.0) return this
    val angular = meters / EARTH_RADIUS_M
    val bearing = bearingDeg * PI / 180
    val lat1 = latitude * PI / 180
    val lon1 = longitude * PI / 180
    val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing))
    val lon2 = lon1 + atan2(sin(bearing) * sin(angular) * cos(lat1), cos(angular) - sin(lat1) * sin(lat2))
    return GeoPoint(lat2 * 180 / PI, lon2 * 180 / PI)
}

private const val EARTH_RADIUS_M = 6_371_008.8
