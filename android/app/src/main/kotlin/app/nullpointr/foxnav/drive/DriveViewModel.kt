package app.nullpointr.foxnav.drive

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import app.nullpointr.foxnav.BuildConfig
import app.nullpointr.foxnav.convoy.ConvoyController
import app.nullpointr.foxnav.data.PlaceSearch
import app.nullpointr.foxnav.data.RoadLookup
import app.nullpointr.foxnav.shared.format.kilometersPerHour
import app.nullpointr.foxnav.shared.convoy.Fix
import app.nullpointr.foxnav.shared.convoy.fixOf
import app.nullpointr.foxnav.shared.geo.GeoPoint
import app.nullpointr.foxnav.shared.road.RoadInfo
import app.nullpointr.foxnav.shared.safety.Camera
import app.nullpointr.foxnav.shared.safety.CameraAhead
import app.nullpointr.foxnav.shared.safety.CameraRules
import app.nullpointr.foxnav.shared.safety.CameraWatch
import app.nullpointr.foxnav.shared.search.Place
import app.nullpointr.foxnav.shared.speed.SpeedReading
import com.stadiamaps.ferrostar.core.DefaultNavigationViewModel
import com.stadiamaps.ferrostar.core.FerrostarCore
import com.stadiamaps.ferrostar.core.NavigationUiState
import com.stadiamaps.ferrostar.core.annotation.valhalla.valhallaExtendedOSRMAnnotationPublisher
import com.stadiamaps.ferrostar.core.location.NavigationLocationProvider
import com.stadiamaps.ferrostar.core.location.toUserLocation
import com.stadiamaps.ferrostar.core.measurement.MeasurementSpeedUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.Route
import uniffi.ferrostar.Speed
import uniffi.ferrostar.TripState
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointKind
import kotlin.math.roundToInt

/** Routes to a picked place, with alternatives to choose from. */
data class RoutePreview(
    val destination: Place,
    val routes: List<Route> = emptyList(),
    val selected: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val selectedRoute: Route? get() = routes.getOrNull(selected)
}

/**
 * State for the drive screen: free drive (no destination), search, route preview and
 * turn-by-turn navigation, which [DefaultNavigationViewModel] provides.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class DriveViewModel(
    private val core: FerrostarCore,
    private val locationProvider: NavigationLocationProvider,
    private val placeSearch: PlaceSearch?,
    private val roadLookup: RoadLookup,
    private val convoy: ConvoyController,
    private val playOverLimitChime: () -> Unit,
    /** The server's speed cameras (an empty list without data packs). */
    private val loadCameras: suspend () -> List<Camera>,
    cameraWarnings: StateFlow<Boolean>,
    /** Says something out loud with the guidance voice (and its mute). */
    private val announce: (String) -> Unit,
) : DefaultNavigationViewModel(core, valhallaExtendedOSRMAnnotationPublisher()) {

    private val hasLocationPermission = MutableStateFlow(false)
    private val freeDriveLocation = MutableStateFlow<UserLocation?>(null)

    /** The latest location while not navigating (during navigation see [navigationUiState]). */
    val location: StateFlow<UserLocation?> = freeDriveLocation.asStateFlow()

    private val _road = MutableStateFlow<RoadInfo?>(null)

    /** The road the car is on in free drive (name and speed limit), when known. */
    val road: StateFlow<RoadInfo?> = _road.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<Place>>(emptyList())
    val results: StateFlow<List<Place>> = _results.asStateFlow()

    private val _preview = MutableStateFlow<RoutePreview?>(null)
    val preview: StateFlow<RoutePreview?> = _preview.asStateFlow()

    private var destination: Place? = null
    private var arrivalJob: Job? = null
    private val _arrivedAt = MutableStateFlow<String?>(null)

    /** Where we just arrived, for a few seconds after a trip ends there. */
    val arrivedAt: StateFlow<String?> = _arrivedAt.asStateFlow()

    // Debug builds drive the route themselves by default, so the emulator can navigate.
    private val _simulate = MutableStateFlow(BuildConfig.DEBUG)
    val simulate: StateFlow<Boolean> = _simulate.asStateFlow()

    // Ferrostar only reports locations while navigating; free drive needs them too.
    override val navigationUiState: StateFlow<NavigationUiState> =
        combine(super.navigationUiState, freeDriveLocation) { uiState, location ->
            if (uiState.isNavigating()) uiState else uiState.copy(location = location)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), NavigationUiState.empty())

    // Current speed in km/h, derived from consecutive locations where the source reports none
    // (Ferrostar's simulation, the emulator).
    private val speedKmh =
        navigationUiState
            .map { it.location }
            // UI state also changes without a new fix (progress, annotations); only new fixes count.
            .distinctUntilChanged()
            .runningFold(null as UserLocation? to null as Int?) { (previous, _), location ->
                location to location?.withSpeedFrom(previous)?.speed?.value?.let(::kilometersPerHour)
            }
            .map { (_, speed) -> speed }
            // No new fix for a while (a tunnel, a GPS gone quiet): the last speed isn't true any more.
            .transformLatest { speed ->
                emit(speed)
                delay(SPEED_STALE_MS)
                emit(null)
            }

    // The limit where we are: from the route's annotations while navigating, the road otherwise.
    private val limitKmh =
        combine(navigationUiState, _road) { uiState, road ->
            if (uiState.isNavigating()) {
                uiState.currentAnnotation?.speedLimit?.value(MeasurementSpeedUnit.KilometersPerHour)?.roundToInt()
            } else {
                road?.speedLimitKmh
            }
        }

    private val _cameras = MutableStateFlow<List<Camera>>(emptyList())

    /** Speed cameras to draw on the map. */
    val cameras: StateFlow<List<Camera>> = _cameras.asStateFlow()

    private val cameraWatch = _cameras.map(::CameraWatch)

    // Where we are, with speed and heading filled in where the location source leaves them out.
    private val ourFix =
        navigationUiState
            .map { it.location }
            .distinctUntilChanged()
            .runningFold(null as Fix?) { previous, location ->
                location?.let {
                    fixOf(
                        point = GeoPoint(it.coordinates.lat, it.coordinates.lng),
                        timeMs = it.timestamp.toEpochMilli(),
                        speedMps = it.speed?.value,
                        headingDeg = it.courseOverGround?.degrees?.toDouble(),
                        previous = previous,
                    )
                }
            }

    private val routePoints =
        navigationUiState
            .map { if (it.isNavigating()) it.routeGeometry else null }
            .distinctUntilChanged()
            .map { geometry -> geometry?.map { GeoPoint(it.lat, it.lng) } }

    /** The next speed camera on our road, while warnings are on and legal where we are. */
    val cameraAhead: StateFlow<CameraAhead?> =
        combine(ourFix, cameraWatch, routePoints, cameraWarnings, _road) { fix, watch, route, enabled, road ->
            if (fix == null || !enabled || !CameraRules.warningsAllowed(road?.countryCode)) null else watch.ahead(fix, route)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Speed, the limit where we are, and whether to warn. */
    val speed: StateFlow<SpeedReading> =
        combine(speedKmh, limitKmh) { speed, limit -> speed to limit }
            .runningFold(SpeedReading()) { reading, (speed, limit) -> reading.next(speed, limit) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, SpeedReading())

    init {
        viewModelScope.launch {
            hasLocationPermission
                .flatMapLatest { granted ->
                    if (granted) locationProvider.locationUpdates(1000L).map { it.toUserLocation() } else emptyFlow()
                }
                .collect { location -> freeDriveLocation.value = location }
        }
        viewModelScope.launch { trackFreeDriveRoad() }
        viewModelScope.launch { _cameras.value = runCatching { loadCameras() }.getOrDefault(emptyList()) }
        viewModelScope.launch {
            // Once per camera, as it comes into range.
            cameraAhead.distinctUntilChangedBy { it?.camera?.id }.filterNotNull().collect { announce(it.spoken()) }
        }
        viewModelScope.launch {
            // Arrived: end the trip, so the route line clears and a convoy stops seeing our
            // destination. Ferrostar completes a trip only a few metres from the destination;
            // parking a little short counts as arriving too.
            core.state.map { it.tripState.hasArrived() }.distinctUntilChanged().filter { it }.collect { endTrip(arrived = true) }
        }
        viewModelScope.launch {
            speed.map { it.overLimit }.distinctUntilChanged().filter { it }.collect {
                if (core.spokenInstructionObserver?.isMuted != true) playOverLimitChime()
            }
        }
        viewModelScope.launch {
            _query.debounce(300).map { it.trim() }.distinctUntilChanged().collectLatest { query ->
                _results.value = if (query.length < 3 || placeSearch == null) {
                    emptyList()
                } else {
                    val near = freeDriveLocation.value?.coordinates?.let { GeoPoint(it.lat, it.lng) }
                    runCatching { placeSearch.search(query, near) }.getOrDefault(emptyList())
                }
            }
        }
    }

    /**
     * Free drive has no route to read speed limits from, so every few seconds the last few
     * positions are matched to the road network instead.
     */
    private suspend fun trackFreeDriveRoad() {
        val recent = ArrayDeque<GeoPoint>()
        var lastLookup = 0L
        freeDriveLocation.collect { location ->
            if (location == null || isNavigating()) return@collect
            val point = GeoPoint(location.coordinates.lat, location.coordinates.lng)
            if (recent.isEmpty() || recent.last().distanceTo(point) >= MIN_TRACE_SPACING_M) {
                recent.addLast(point)
                if (recent.size > MAX_TRACE_POINTS) recent.removeFirst()
            }
            val now = SystemClock.elapsedRealtime()
            if (recent.size >= 2 && now - lastLookup >= ROAD_LOOKUP_INTERVAL_MS) {
                lastLookup = now
                runCatching { roadLookup.currentRoad(recent.toList()) }.onSuccess { road -> if (road != null) _road.value = road }
            }
        }
    }

    private fun isNavigating() = super.navigationUiState.value.isNavigating()

    fun onLocationPermission(granted: Boolean) {
        hasLocationPermission.value = granted
    }

    fun onQueryChange(query: String) {
        _query.value = query
    }

    /** Fetches routes from here to [place], including alternatives. */
    fun preview(place: Place) {
        _query.value = ""
        _results.value = emptyList()
        val origin = freeDriveLocation.value
        if (origin == null) {
            _preview.value = RoutePreview(place, error = "Waiting for your location")
            return
        }
        _preview.value = RoutePreview(place, loading = true)
        viewModelScope.launch(Dispatchers.IO) {
            val destination = Waypoint(GeographicCoordinate(place.latitude, place.longitude), WaypointKind.BREAK)
            _preview.value = runCatching { core.getRoutes(origin, listOf(destination)) }.fold(
                onSuccess = { routes ->
                    if (routes.isEmpty()) RoutePreview(place, error = "No route found") else RoutePreview(place, routes)
                },
                onFailure = { RoutePreview(place, error = "Couldn't get a route: ${it.message}") },
            )
        }
    }

    /** Previews a route to a place from the convoy: a shared destination or the regroup point. */
    fun previewConvoyPlace(point: GeoPoint, name: String) {
        preview(Place(title = name, detail = "From the convoy", latitude = point.latitude, longitude = point.longitude))
    }

    fun selectRoute(index: Int) {
        _preview.update { it?.copy(selected = index) }
    }

    fun cancelPreview() {
        _preview.value = null
    }

    fun setSimulate(simulate: Boolean) {
        _simulate.value = simulate
    }

    /** Starts turn-by-turn on the selected route. */
    fun startNavigation() {
        val preview = _preview.value ?: return
        val route = preview.selectedRoute ?: return
        _preview.value = null
        destination = preview.destination
        arrivalJob?.cancel()
        _arrivedAt.value = null
        if (_simulate.value) locationProvider.enableSimulationOn(route)
        core.startNavigation(route = route)
        // In a convoy, the others can follow us there.
        convoy.shareDestination(GeoPoint(preview.destination.latitude, preview.destination.longitude), preview.destination.title)
    }

    override fun stopNavigation() = endTrip(arrived = false)

    private fun endTrip(arrived: Boolean) {
        val place = destination
        destination = null
        locationProvider.disableSimulation()
        core.stopNavigation()
        convoy.shareDestination(null)
        if (arrived) {
            _arrivedAt.value = place?.title ?: "Your destination"
            arrivalJob?.cancel()
            arrivalJob = viewModelScope.launch {
                delay(ARRIVED_SHOWN_MS)
                _arrivedAt.value = null
            }
        }
    }

    override fun toggleMute() {
        core.spokenInstructionObserver?.let { it.setMuted(!it.isMuted) }
    }

    private companion object {
        const val MIN_TRACE_SPACING_M = 15.0
        const val MAX_TRACE_POINTS = 6
        const val ROAD_LOOKUP_INTERVAL_MS = 5_000L
        const val SPEED_STALE_MS = 4_000L
        const val ARRIVAL_RADIUS_M = 30.0
        const val ARRIVED_SHOWN_MS = 10_000L

        fun TripState.hasArrived() =
            this is TripState.Complete || (this is TripState.Navigating && progress.distanceRemaining <= ARRIVAL_RADIUS_M)
    }
}

/** Some location sources (and the emulator) report no speed; derive it from the previous fix. */
private fun UserLocation.withSpeedFrom(previous: UserLocation?): UserLocation {
    if (speed != null || previous == null) return this
    val seconds = (timestamp.toEpochMilli() - previous.timestamp.toEpochMilli()) / 1000.0
    if (seconds <= 0 || seconds > 10) return this
    val meters = GeoPoint(previous.coordinates.lat, previous.coordinates.lng)
        .distanceTo(GeoPoint(coordinates.lat, coordinates.lng))
    // A jump, not a drive: e.g. back to real GPS when a simulated drive ends.
    if (meters / seconds > MAX_PLAUSIBLE_SPEED_MPS) return this
    return copy(speed = Speed(meters / seconds, null))
}

private const val MAX_PLAUSIBLE_SPEED_MPS = 100.0

/** "Speed camera in 400 metres, limit 80". */
private fun CameraAhead.spoken(): String {
    val what = if (camera.kind == Camera.RED_LIGHT) "Red light camera" else "Speed camera"
    val metres = (distanceM / 100).roundToInt().coerceAtLeast(1) * 100
    return "$what in $metres metres" + (camera.maxspeed?.let { ", limit $it" } ?: "")
}
