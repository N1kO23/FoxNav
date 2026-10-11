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
import kotlin.math.max
import kotlin.math.roundToInt

/** Where the map shows us, and which way we point there. */
data class ShownLocation(val position: Position, val bearing: Double?)

/**
 * Where Ferrostar's camera points, so our car can sit exactly there.
 *
 * Navigating on the route, Ferrostar slides along the route line to each new fix over a second,
 * pointing along the line rather than the noisy GPS course. This mirrors its internal
 * `rememberDisplayedNavigationLocation` (ferrostar ui-maplibre 0.57, BSD-3-Clause) step for step:
 * both animations start on the same frame, so car and camera move together. The car keeps to
 * the drawn line, which rounds the corners ([RoutePath]), so in a bend it swings a few metres
 * off the camera's point and back. Off the route and in
 * free drive, the camera follows the location itself, which [rememberGlidingViewModel] eases.
 */
@Composable
fun rememberShownLocation(uiState: NavigationUiState): ShownLocation? {
    val location = uiState.location ?: return null
    val raw = ShownLocation(Position(location.coordinates.lng, location.coordinates.lat), location.courseOverGround?.degrees?.toDouble())
    val route = remember(uiState.routeGeometry) { uiState.routeGeometry?.takeIf { it.size >= 2 }?.let { RoutePath(it.map { point -> Position(point.lng, point.lat) }) } }
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

private val DoubleToVector = TwoWayConverter<Double, AnimationVector1D>({ AnimationVector1D(it.toFloat()) }, { it.value.toDouble() })

// Ferrostar's, so the car and the camera agree.
private const val DISPLAY_ANIMATION_MS = 1000

private const val MAX_GLIDE_M = 300.0
