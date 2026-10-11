package app.nullpointr.foxnav.drive

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import app.nullpointr.foxnav.shared.geo.GeoPoint
import com.stadiamaps.ferrostar.core.NavigationUiState
import com.stadiamaps.ferrostar.core.NavigationViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import org.maplibre.spatialk.geojson.Position
import uniffi.ferrostar.CourseOverGround
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.RouteDeviation
import uniffi.ferrostar.UserLocation
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Where the map shows us, and which way we point there. */
data class ShownLocation(val position: Position, val bearing: Double?)

/**
 * Where Ferrostar's camera points, so our car can sit exactly there.
 *
 * Navigating on the route, Ferrostar slides along the route line to each new fix over a second,
 * pointing along the line rather than the noisy GPS course. This mirrors its internal
 * `rememberDisplayedNavigationLocation` (ferrostar ui-maplibre 0.57, BSD-3-Clause) step for step:
 * both animations start on the same frame, so car and camera move together. Off the route and in
 * free drive, the camera follows the location itself, which [rememberGlidingViewModel] eases.
 */
@Composable
fun rememberShownLocation(uiState: NavigationUiState): ShownLocation? {
    val location = uiState.location ?: return null
    val raw = ShownLocation(Position(location.coordinates.lng, location.coordinates.lat), location.courseOverGround?.degrees?.toDouble())
    val route = remember(uiState.routeGeometry) { uiState.routeGeometry?.takeIf { it.size >= 2 }?.let(::RouteLine) }
    if (!uiState.isNavigating() || uiState.routeDeviation !is RouteDeviation.NoDeviation || route == null) return raw

    val target = remember(route, raw.position) { route.project(raw.position) }
    val progress = remember(route) { Animatable(target, DoubleToVector) }
    val shown by progress.asState()
    LaunchedEffect(route, target) {
        // Never backwards along the route: GPS jitter would make the car shuffle.
        val to = max(progress.value, target)
        if (to == progress.value) return@LaunchedEffect
        progress.animateTo(to, tween(DISPLAY_ANIMATION_MS, easing = LinearEasing))
    }
    return remember(route, shown) { ShownLocation(route.positionAt(shown), route.bearingAt(shown)) }
}

/**
 * [viewModel] as Ferrostar sees it, with free-drive locations eased from one fix to the next over
 * a second, frame by frame: the map glides like it does while navigating, instead of stepping once
 * a second. While navigating, Ferrostar eases along the route itself, so states pass through.
 */
@Composable
fun rememberGlidingViewModel(viewModel: NavigationViewModel): NavigationViewModel {
    val glided = remember(viewModel) { MutableStateFlow(viewModel.navigationUiState.value) }
    LaunchedEffect(viewModel) {
        val glide = Glide()
        viewModel.navigationUiState.collectLatest { state ->
            val target = state.location
            if (state.isNavigating() || target == null) {
                glide.reset()
                glided.value = state
                return@collectLatest
            }
            var now = withFrameNanos { it }
            glide.aim(target, now)
            while (true) {
                glided.value = state.copy(location = glide.at(now))
                if (glide.arrived(now)) break
                now = withFrameNanos { it }
            }
        }
    }
    return remember(viewModel) { GlidingViewModel(viewModel, glided) }
}

private class GlidingViewModel(
    viewModel: NavigationViewModel,
    override val navigationUiState: StateFlow<NavigationUiState>,
) : NavigationViewModel by viewModel

/** A straight, steady slide from where we're shown to the latest fix. */
private class Glide {
    private var from: UserLocation? = null
    private var to: UserLocation? = null
    private var startNanos = 0L

    fun reset() {
        from = null
        to = null
    }

    fun aim(target: UserLocation, nowNanos: Long) {
        if (target == to) return
        val shown = to?.let { at(nowNanos) }
        // The first fix, or a jump (a new GPS lock): no slide across the map.
        from = shown?.takeIf { it.point().distanceTo(target.point()) < MAX_GLIDE_M } ?: target
        to = target
        startNanos = nowNanos
    }

    fun arrived(nowNanos: Long): Boolean = fraction(nowNanos) >= 1.0

    fun at(nowNanos: Long): UserLocation {
        val from = from ?: return to!!
        val to = to!!
        val t = fraction(nowNanos)
        if (t >= 1.0) return to
        return to.copy(
            coordinates = GeographicCoordinate(
                lat = from.coordinates.lat + (to.coordinates.lat - from.coordinates.lat) * t,
                lng = from.coordinates.lng + (to.coordinates.lng - from.coordinates.lng) * t,
            ),
            courseOverGround = turn(from.courseOverGround, to.courseOverGround, t),
        )
    }

    private fun fraction(nowNanos: Long): Double = ((nowNanos - startNanos) / 1e6 / DISPLAY_ANIMATION_MS).coerceIn(0.0, 1.0)

    /** The shorter way round from one course to the other. */
    private fun turn(from: CourseOverGround?, to: CourseOverGround?, t: Double): CourseOverGround? {
        if (from == null || to == null) return to
        val delta = ((to.degrees.toDouble() - from.degrees.toDouble() + 540.0) % 360.0) - 180.0
        return to.copy(degrees = (from.degrees.toDouble() + delta * t).roundToInt().mod(360).toUShort())
    }

    private fun UserLocation.point() = GeoPoint(coordinates.lat, coordinates.lng)
}

/** The route as a line to measure along: how far along a point is, and what's there. */
private class RouteLine(geometry: List<GeographicCoordinate>) {
    private val points = geometry.map { Position(it.lng, it.lat) }
    private val along = buildList {
        add(0.0)
        for (index in 1 until points.size) add(last() + distance(points[index - 1], points[index]))
    }
    private val length = along.last()

    /** Metres along the route to the nearest point on it. */
    fun project(position: Position): Double {
        var best = Double.POSITIVE_INFINITY
        var progress = 0.0
        for (index in 0 until points.lastIndex) {
            val start = points[index]
            val end = points[index + 1]
            val scaleX = METRES_PER_DEGREE * cos(Math.toRadians((start.latitude + end.latitude + position.latitude) / 3.0))
            val segmentX = (end.longitude - start.longitude) * scaleX
            val segmentY = (end.latitude - start.latitude) * METRES_PER_DEGREE
            val pointX = (position.longitude - start.longitude) * scaleX
            val pointY = (position.latitude - start.latitude) * METRES_PER_DEGREE
            val lengthSquared = segmentX * segmentX + segmentY * segmentY
            val t = if (lengthSquared == 0.0) 0.0 else ((pointX * segmentX + pointY * segmentY) / lengthSquared).coerceIn(0.0, 1.0)
            val dx = pointX - segmentX * t
            val dy = pointY - segmentY * t
            val distanceSquared = dx * dx + dy * dy
            if (distanceSquared < best) {
                best = distanceSquared
                progress = along[index] + sqrt(lengthSquared) * t
            }
        }
        return progress
    }

    fun positionAt(progress: Double): Position {
        val at = progress.coerceIn(0.0, length)
        val index = segmentAt(at)
        val start = points[index]
        val end = points[index + 1]
        val segment = along[index + 1] - along[index]
        if (segment == 0.0) return start
        val t = (at - along[index]) / segment
        return Position(start.longitude + (end.longitude - start.longitude) * t, start.latitude + (end.latitude - start.latitude) * t)
    }

    /** Along the line from a little behind to a little ahead: steady through the bends. */
    fun bearingAt(progress: Double): Double {
        val at = progress.coerceIn(0.0, length)
        val behind = (at - BEARING_BEHIND_M).coerceAtLeast(0.0)
        val ahead = (at + BEARING_AHEAD_M).coerceAtMost(length)
        if (ahead <= behind) {
            val index = segmentAt(at)
            return bearing(points[index], points[index + 1])
        }
        return bearing(positionAt(behind), positionAt(ahead))
    }

    private fun segmentAt(progress: Double): Int {
        for (index in 0 until along.lastIndex) {
            if (progress <= along[index + 1]) return index
        }
        return max(0, points.lastIndex - 1)
    }

    private fun distance(a: Position, b: Position): Double {
        val dy = (b.latitude - a.latitude) * METRES_PER_DEGREE
        val dx = (b.longitude - a.longitude) * METRES_PER_DEGREE * cos(Math.toRadians((a.latitude + b.latitude) / 2.0))
        return sqrt(dx * dx + dy * dy)
    }

    private fun bearing(start: Position, end: Position): Double {
        val dx = (end.longitude - start.longitude) * cos(Math.toRadians((start.latitude + end.latitude) / 2.0))
        val dy = end.latitude - start.latitude
        return (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
    }
}

private val DoubleToVector = TwoWayConverter<Double, AnimationVector1D>({ AnimationVector1D(it.toFloat()) }, { it.value.toDouble() })

// Ferrostar's own values, so the car and the camera agree.
private const val DISPLAY_ANIMATION_MS = 1000
private const val BEARING_BEHIND_M = 5.0
private const val BEARING_AHEAD_M = 15.0
private const val METRES_PER_DEGREE = 111_320.0

private const val MAX_GLIDE_M = 300.0
