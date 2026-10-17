package app.nullpointr.foxnav.drive

import app.nullpointr.foxnav.shared.geo.GeoPoint
import app.nullpointr.foxnav.shared.navigation.Fix
import app.nullpointr.foxnav.shared.navigation.OffRouteCheck
import app.nullpointr.foxnav.shared.navigation.RouteCheck
import uniffi.ferrostar.DeviationKind
import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteDeviation
import uniffi.ferrostar.RouteDeviationDetector
import uniffi.ferrostar.TripState

/**
 * Tells Ferrostar when the car has left its route ([OffRouteCheck]): off the line, or the wrong way
 * along it, which Ferrostar's own distance check never sees. Ferrostar then fetches a new route
 * from where the car is, heading the way it's going, and switches to it. Starting a route from
 * somewhere else, the first fix is off the line and reroutes at once.
 */
class RouteDeviationCheck : RouteDeviationDetector {
    private val check = OffRouteCheck()
    private var routeKey: Any? = null

    @Synchronized
    override fun checkRouteDeviation(route: Route, tripState: TripState): RouteDeviation {
        val navigating = tripState as? TripState.Navigating ?: return RouteDeviation.NoDeviation
        // Every call brings a fresh copy of the route: tell routes apart by what they are.
        val key = Triple(route.distance, route.geometry.size, route.geometry.firstOrNull())
        if (key != routeKey) {
            routeKey = key
            check.reset()
        }
        // The stretch around and ahead of the car: the current step (from its start) and a few after it.
        val line = navigating.remainingSteps.take(STEPS_AHEAD).flatMap { step -> step.geometry.map { GeoPoint(it.lat, it.lng) } }
        val location = navigating.userLocation
        val fix = Fix(
            point = GeoPoint(location.coordinates.lat, location.coordinates.lng),
            accuracyM = location.horizontalAccuracy,
            courseDeg = location.courseOverGround?.degrees?.toDouble(),
            speedMps = location.speed?.value,
            timeMs = location.timestamp.toEpochMilli(),
        )
        return when (val result = check.check(line, fix)) {
            RouteCheck.OnRoute -> RouteDeviation.NoDeviation
            is RouteCheck.OffRoute -> RouteDeviation.Deviation(DeviationKind.CompletelyOffRoute(result.distanceM))
            is RouteCheck.WrongWay -> RouteDeviation.Deviation(DeviationKind.OffStepOnRoute(result.distanceM))
        }
    }

    private companion object {
        const val STEPS_AHEAD = 4
    }
}
