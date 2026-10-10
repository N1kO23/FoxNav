package app.nullpointr.foxnav.shared.convoy

import app.nullpointr.foxnav.shared.geo.GeoPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The gap from us to [other] for the timing tower, in metres: positive ahead of us, negative
 * behind. Along [route] when we're navigating and both cars are on it; otherwise the distance
 * as the crow flies, ahead or behind by our heading.
 */
fun roadGap(me: Fix, other: GeoPoint, route: List<GeoPoint>? = null): Double {
    if (route != null) {
        val mine = project(route, me.point)
        val theirs = project(route, other)
        if (mine != null && theirs != null && mine.offM <= ON_ROUTE_M && theirs.offM <= ON_ROUTE_M) return theirs.alongM - mine.alongM
    }
    val heading = me.headingDeg.toRadians()
    val (east, north) = flatOffset(me.point, other)
    val ahead = east * sin(heading) + north * cos(heading) >= 0
    val distance = me.point.distanceTo(other)
    return if (ahead) distance else -distance
}

/** How fresh what we know about a member is. */
enum class Signal {
    /** No position from them yet. */
    WAITING,
    LIVE,

    /** Nothing for a while: the car fades on the map. */
    STALE,

    /** Nothing for a minute: a tunnel or a dead zone, or the phone gave up. */
    LOST,
}

fun ConvoyMember.signal(nowMs: Long): Signal {
    val heard = lastHeardMs
    return when {
        fix == null || heard == null -> Signal.WAITING
        nowMs - heard >= LOST_AFTER_MS -> Signal.LOST
        nowMs - heard >= STALE_AFTER_MS -> Signal.STALE
        else -> Signal.LIVE
    }
}

/**
 * A fix from a location source that may leave out speed or heading (simulations, some network
 * fixes); the missing values are worked out from [previous]. Standing still, the heading of
 * the last movement is kept: GPS bearings are noise at walking pace.
 */
fun fixOf(point: GeoPoint, timeMs: Long, speedMps: Double?, headingDeg: Double?, previous: Fix?): Fix {
    val seconds = previous?.let { (timeMs - it.timeMs) / 1000.0 }
    val moved = previous?.point?.distanceTo(point)
    val derivedSpeed = (if (seconds != null && moved != null && seconds > 0 && seconds <= 10) moved / seconds else null)
        ?.takeIf { it <= MAX_PLAUSIBLE_MPS } // faster is a jump, e.g. from a simulated drive back to real GPS
    val speed = speedMps ?: derivedSpeed ?: 0.0
    val heading = when {
        speed < STILL_MPS && previous != null -> previous.headingDeg
        headingDeg != null -> headingDeg
        previous != null && moved != null && moved >= 1.0 -> previous.point.bearingTo(point)
        else -> previous?.headingDeg ?: 0.0
    }
    return Fix(point, speed, heading, timeMs)
}

/**
 * Where along [line] the point nearest to a point lies, how far off the line the point is (metres),
 * and which way the line runs there.
 */
internal data class LineProjection(val alongM: Double, val offM: Double, val bearingDeg: Double)

internal fun project(line: List<GeoPoint>, point: GeoPoint): LineProjection? {
    if (line.size < 2) return null
    var best: LineProjection? = null
    var travelled = 0.0
    for (index in 0 until line.lastIndex) {
        val start = line[index]
        val (segmentEast, segmentNorth) = flatOffset(start, line[index + 1])
        val (pointEast, pointNorth) = flatOffset(start, point)
        val lengthSquared = segmentEast * segmentEast + segmentNorth * segmentNorth
        val t = if (lengthSquared == 0.0) 0.0 else ((pointEast * segmentEast + pointNorth * segmentNorth) / lengthSquared).coerceIn(0.0, 1.0)
        val offEast = pointEast - t * segmentEast
        val offNorth = pointNorth - t * segmentNorth
        val off = sqrt(offEast * offEast + offNorth * offNorth)
        val length = sqrt(lengthSquared)
        if (best == null || off < best.offM) best = LineProjection(travelled + t * length, off, start.bearingTo(line[index + 1]))
        travelled += length
    }
    return best
}

/** [to] relative to [from] in metres east and north, on a flat approximation (fine over a few km). */
private fun flatOffset(from: GeoPoint, to: GeoPoint): Pair<Double, Double> {
    val east = (to.longitude - from.longitude).toRadians() * cos(from.latitude.toRadians()) * EARTH_RADIUS_M
    val north = (to.latitude - from.latitude).toRadians() * EARTH_RADIUS_M
    return east to north
}

private fun Double.toRadians() = this * PI / 180

private const val EARTH_RADIUS_M = 6_371_008.8

/** A car further than this from the route is measured along our heading instead. */
private const val ON_ROUTE_M = 150.0
private const val STILL_MPS = 1.0
private const val MAX_PLAUSIBLE_MPS = 100.0
const val STALE_AFTER_MS = 10_000L
const val LOST_AFTER_MS = 60_000L
