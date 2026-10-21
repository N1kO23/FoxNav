package app.nullpointr.foxnav.drive

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import app.nullpointr.foxnav.BuildConfig
import app.nullpointr.foxnav.convoy.ConvoyController
import app.nullpointr.foxnav.data.PlaceSearch
import app.nullpointr.foxnav.data.ReportsClient
import app.nullpointr.foxnav.data.RoadLookup
import app.nullpointr.foxnav.settings.Announcements
import app.nullpointr.foxnav.settings.OverLimitSound
import app.nullpointr.foxnav.settings.Settings
import app.nullpointr.foxnav.shared.voice.Phrases
import app.nullpointr.foxnav.voice.Utterance
import app.nullpointr.foxnav.shared.format.UnitSystem
import app.nullpointr.foxnav.shared.format.limitIn
import app.nullpointr.foxnav.shared.format.speedIn
import app.nullpointr.foxnav.shared.format.spokenDistance
import app.nullpointr.foxnav.shared.convoy.Fix
import app.nullpointr.foxnav.shared.convoy.fixOf
import app.nullpointr.foxnav.shared.geo.GeoPoint
import app.nullpointr.foxnav.shared.reports.NewReport
import app.nullpointr.foxnav.shared.reports.PassedReports
import app.nullpointr.foxnav.shared.reports.Report
import app.nullpointr.foxnav.shared.reports.ReportAhead
import app.nullpointr.foxnav.shared.reports.ReportType
import app.nullpointr.foxnav.shared.reports.ReportWatch
import app.nullpointr.foxnav.shared.road.RoadInfo
import app.nullpointr.foxnav.shared.safety.Camera
import app.nullpointr.foxnav.shared.safety.CameraAhead
import app.nullpointr.foxnav.shared.safety.CameraRules
import app.nullpointr.foxnav.shared.safety.CameraWatch
import app.nullpointr.foxnav.shared.safety.SectionWatch
import app.nullpointr.foxnav.shared.search.Place
import app.nullpointr.foxnav.shared.search.PlaceNames
import app.nullpointr.foxnav.shared.speed.Day
import app.nullpointr.foxnav.shared.speed.SpeedLimitPack
import app.nullpointr.foxnav.shared.speed.SpeedLimits
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.ferrostar.CourseOverGround
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.Route
import uniffi.ferrostar.Speed
import uniffi.ferrostar.TripState
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointKind
import java.time.LocalDateTime
import kotlin.math.roundToInt
import app.nullpointr.foxnav.data.PlaceStore
import app.nullpointr.foxnav.data.TravelTimes
import app.nullpointr.foxnav.shared.search.SavedPlace
import app.nullpointr.foxnav.shared.search.SavedPlaces
import kotlin.math.ceil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancelChildren

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
    /** What the user chose: units, warnings, sounds. */
    private val settings: StateFlow<Settings>,
    private val playOverLimit: (OverLimitSound) -> Unit,
    /** The warning tone, for warnings set to a tone rather than words. */
    private val playAlert: () -> Unit,
    /** The server's speed cameras (an empty list without data packs). */
    private val loadCameras: suspend () -> List<Camera>,
    /** The server's speed limit packs: winter limits, and limits for roads the map has none for. */
    private val loadSpeedLimits: suspend () -> List<SpeedLimitPack>,
    cameraWarnings: StateFlow<Boolean>,
    /** The server's reports; null if it has none. */
    private val reportsClient: ReportsClient?,
    private val reportWarnings: StateFlow<Boolean>,
    /** Says something with the guidance voice: a voice pack's phrases, or text-to-speech (and its mute). */
    private val say: (Utterance) -> Unit,
    /** Home, work, favourites and recent destinations. */
    private val places: PlaceStore,
    /** For the shortcuts' travel times; null without Valhalla. */
    private val travelTimes: TravelTimes?,
    /** Turns true once a newer drive screen (another server's) takes over: this one then goes quiet. */
    retired: Flow<Boolean>,
) : DefaultNavigationViewModel(core, valhallaExtendedOSRMAnnotationPublisher()) {

    init {
        viewModelScope.launch {
            retired.first { it }
            viewModelScope.coroutineContext.cancelChildren()
        }
    }

    /** Home, work, favourites and recent destinations, for the shortcuts and the search. */
    val savedPlaces: StateFlow<List<SavedPlace>> get() = places.places

    private val _shortcutMinutes = MutableStateFlow<Map<Place, Int>>(emptyMap())

    /** Minutes by car from here to each shortcut, where the server could say. */
    val shortcutMinutes: StateFlow<Map<Place, Int>> = _shortcutMinutes.asStateFlow()

    fun savePlace(place: Place, kind: SavedPlace.Kind) = places.save(place, kind)

    fun removePlace(saved: SavedPlace) = places.remove(saved)

    private val hasLocationPermission = MutableStateFlow(false)
    private val freeDriveLocation = MutableStateFlow<UserLocation?>(null)

    /** The latest location while not navigating (during navigation see [navigationUiState]). */
    val location: StateFlow<UserLocation?> = freeDriveLocation.asStateFlow()

    private val _road = MutableStateFlow<RoadInfo?>(null)

    /** The road the car is on (name, speed limit, country, way), when known. */
    val road: StateFlow<RoadInfo?> = _road.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<Place>>(emptyList())
    val results: StateFlow<List<Place>> = _results.asStateFlow()

    private val _preview = MutableStateFlow<RoutePreview?>(null)
    val preview: StateFlow<RoutePreview?> = _preview.asStateFlow()

    private var destination: Place? = null
    private var arrivalJob: Job? = null

    /** Keeps the open preview's routes starting where the car is. */
    private var previewJob: Job? = null
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

    // Current speed in metres per second, derived from consecutive locations where the source
    // reports none (Ferrostar's simulation, the emulator).
    private val speedMps =
        navigationUiState
            .map { it.location }
            // UI state also changes without a new fix (progress, annotations); only new fixes count.
            .distinctUntilChanged()
            .runningFold(null as UserLocation? to null as Double?) { (previous, _), location ->
                location to location?.withSpeedFrom(previous)?.speed?.value
            }
            .map { (_, speed) -> speed }
            // No new fix for a while (a tunnel, a GPS gone quiet): the last speed isn't true any more.
            .transformLatest { speed ->
                emit(speed)
                delay(SPEED_STALE_MS)
                emit(null)
            }

    private val speedLimits = MutableStateFlow(SpeedLimits())

    // The limit where we are: from the route's annotations while navigating, the road otherwise,
    // corrected for the date and time where the road has a winter limit or a school zone (which
    // routing ignores), and from the speed limit pack where the map has none.
    private val limit =
        combine(navigationUiState, _road, speedLimits) { uiState, road, limits ->
            val mapKmh = if (uiState.isNavigating()) {
                uiState.currentAnnotation?.speedLimit?.value(MeasurementSpeedUnit.KilometersPerHour)?.roundToInt()
            } else {
                road?.speedLimitKmh
            }
            val here = uiState.location?.coordinates?.let { GeoPoint(it.lat, it.lng) }
            val now = LocalDateTime.now()
            limits.limit(road?.wayId, mapKmh, now.toDay(), here, minuteOfDay = now.hour * 60 + now.minute)
        }

    private val _cameras = MutableStateFlow<List<Camera>>(emptyList())

    /** Speed cameras to draw on the map. */
    val cameras: StateFlow<List<Camera>> = _cameras.asStateFlow()

    private val cameraWatch = _cameras.map(::CameraWatch)
    private val sectionWatch = _cameras.map(::SectionWatch)

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
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

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

    /** Whether this server takes reports. */
    val canReport: Boolean get() = reportsClient != null

    private val _reports = MutableStateFlow<List<Report>>(emptyList())

    /** Reports to show: police and speed checks only where warning about them is legal. */
    val reports: StateFlow<List<Report>> =
        combine(_reports, _road) { reports, road -> reports.filter { it.allowedIn(road?.countryCode) } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val reportWatch = reports.map(::ReportWatch)

    /** The next report on our road. Shown even with spoken report warnings off. */
    val reportAhead: StateFlow<ReportAhead?> =
        combine(ourFix, reportWatch, routePoints) { fix, watch, route -> fix?.let { watch.ahead(it, route) } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val refreshReports = Channel<Unit>(Channel.CONFLATED)
    private val passedReports = PassedReports()
    private val _stillThere = MutableStateFlow<Report?>(null)
    private var stillThereJob: Job? = null

    /** A driver's report we just passed: is it still there? */
    val stillThere: StateFlow<Report?> = _stillThere.asStateFlow()

    private val _reportNotice = MutableStateFlow<String?>(null)
    private var reportNoticeJob: Job? = null

    /** How the last report we made went, for a few seconds. */
    val reportNotice: StateFlow<String?> = _reportNotice.asStateFlow()

    /** Speed, the limit where we are, and whether to warn. */
    val speed: StateFlow<SpeedReading> =
        combine(speedMps, limit, settings) { speed, limit, settings -> Triple(speed, limit, settings) }
            .runningFold(SpeedReading()) { reading, (speed, limit, settings) ->
                reading.next(
                    speed = speed?.let { speedIn(it, settings.units) },
                    limit = limit?.kmh?.let { limitIn(it, settings.units) },
                    margin = settings.overLimitMargin,
                    limitSeason = limit?.season,
                )
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, SpeedReading())

    /**
     * The average speed section we're in, where camera warnings are on and legal: the distance to
     * its end, and our average so far against the limit. Kept on the phone only, like our speed.
     */
    val section: StateFlow<SectionShown?> =
        combine(ourFix, sectionWatch, cameraWarnings, _road, speed) { fix, watch, enabled, road, reading ->
            if (fix == null || !enabled || !CameraRules.warningsAllowed(road?.countryCode)) return@combine null
            watch.update(fix)?.let { progress ->
                val units = settings.value.units
                val average = progress.averageMps?.let { speedIn(it, units) }
                val limit = reading.limit
                SectionShown(progress.remainingM, average, over = average != null && limit != null && average >= limit + settings.value.overLimitMargin)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch { refreshShortcutMinutes() }
        viewModelScope.launch {
            hasLocationPermission
                .flatMapLatest { granted ->
                    if (granted) locationProvider.locationUpdates(1000L).map { it.toUserLocation() } else emptyFlow()
                }
                // Heading-up needs a course, which some sources (and the emulator) leave out.
                .collect { location -> freeDriveLocation.value = location.withCourseFrom(freeDriveLocation.value) }
        }
        viewModelScope.launch { trackRoad() }
        viewModelScope.launch { _cameras.value = runCatching { loadCameras() }.getOrDefault(emptyList()) }
        viewModelScope.launch(Dispatchers.Default) { speedLimits.value = SpeedLimits(runCatching { loadSpeedLimits() }.getOrDefault(emptyList())) }
        viewModelScope.launch {
            // Once per camera, as it comes into range.
            cameraAhead.distinctUntilChangedBy { it?.camera?.id }.filterNotNull().collect { ahead ->
                val kind = when (ahead.camera.kind) {
                    Camera.RED_LIGHT -> "red_light_camera"
                    Camera.AVERAGE_SPEED -> "average_speed_check"
                    else -> "speed_camera"
                }
                val units = settings.value.units
                warn(kind, ahead.distanceM, ahead.spoken(units), ahead.short(), limit = ahead.camera.maxspeed?.let { limitIn(it, units) })
            }
        }
        if (reportsClient != null) {
            viewModelScope.launch {
                while (true) {
                    runCatching { reportsClient.feed() }.onSuccess { _reports.value = it }
                    withTimeoutOrNull(REPORTS_REFRESH_MS) { refreshReports.receive() }
                }
            }
            viewModelScope.launch {
                // Once per report, as it comes into range. Roadworks are on the map, not worth a voice.
                reportAhead.distinctUntilChangedBy { it?.report?.id }.filterNotNull()
                    .filter { it.report.type != ReportType.ROADWORKS && reportWarnings.value }
                    .collect { warn(it.report.type, it.distanceM, it.spoken(settings.value.units), ReportLabels.label(it.report.type)) }
            }
            viewModelScope.launch {
                combine(ourFix.filterNotNull(), reportAhead) { fix, ahead -> fix to ahead }
                    .distinctUntilChangedBy { it.first }
                    .collect { (fix, ahead) ->
                        val passed = passedReports.next(fix, ahead) ?: return@collect
                        // Official reports end when their source says so; only drivers' reports are voted on.
                        if (passed.source == null && reportWarnings.value) askStillThere(passed)
                    }
            }
        }
        viewModelScope.launch {
            // Arrived: end the trip, so the route line clears and a convoy stops seeing our
            // destination. Ferrostar completes a trip only a few metres from the destination;
            // parking a little short counts as arriving too.
            core.state.map { it.tripState.hasArrived() }.distinctUntilChanged().filter { it }.collect { endTrip(arrived = true) }
        }
        viewModelScope.launch {
            speed.map { it.overLimit }.distinctUntilChanged().filter { it }.collect {
                if (core.spokenInstructionObserver?.isMuted != true) playOverLimit(settings.value.overLimitSound)
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
     * Every few seconds the last few positions are matched to the road network: free drive has no
     * route to read speed limits from, and while navigating the road's way and country still
     * matter (winter limits, camera rules).
     */
    private suspend fun trackRoad() {
        val recent = ArrayDeque<GeoPoint>()
        var lastLookup = 0L
        freeDriveLocation.collect { location ->
            if (location == null) return@collect
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

    fun onLocationPermission(granted: Boolean) {
        hasLocationPermission.value = granted
    }

    fun onQueryChange(query: String) {
        _query.value = query
    }

    /** Fetches routes from here to [place], including alternatives. */
    /**
     * Travel times to the shortcuts, while they're on screen: in free drive, again every few
     * minutes and after moving a while. The server learns the saved places only then, as it
     * would routing to them.
     */
    private suspend fun refreshShortcutMinutes() {
        val times = travelTimes ?: return
        val targets = places.places.map { saved -> SavedPlaces.shortcuts(saved).map { it.place } }.distinctUntilChanged()
        val here = freeDriveLocation.filterNotNull()
            .map { GeoPoint(it.coordinates.lat, it.coordinates.lng) }
            .distinctUntilChanged { old, new -> old.distanceTo(new) < SHORTCUT_MOVE_M }
        val navigating = navigationUiState.map { it.isNavigating() }.distinctUntilChanged()
        combine(targets, here, navigating, ::Triple).collectLatest { (shortcuts, from, navigating) ->
            if (navigating || shortcuts.isEmpty()) return@collectLatest
            while (true) {
                _shortcutMinutes.value = shortcuts.mapNotNull { place ->
                    times.seconds(from, GeoPoint(place.latitude, place.longitude))?.let { place to ceil(it / 60).toInt().coerceAtLeast(1) }
                }.toMap()
                delay(SHORTCUT_REFRESH_MS)
            }
        }
    }

    fun preview(place: Place) {
        _query.value = ""
        _results.value = emptyList()
        previewJob?.cancel()
        previewJob = viewModelScope.launch { previewFromHere(place) }
    }

    /**
     * Routes to [place] from where the car is, and again whenever it has moved on while the
     * preview is open: the route starts where you are when you press Start, not where you were
     * when you looked. Waits for a first fix if there's none yet.
     */
    private suspend fun previewFromHere(place: Place) {
        val destination = Waypoint(GeographicCoordinate(place.latitude, place.longitude), WaypointKind.BREAK)
        if (freeDriveLocation.value == null) _preview.value = RoutePreview(place, error = "Waiting for your location")
        var from: GeoPoint? = null
        var fromMs = 0L
        freeDriveLocation.filterNotNull().collectLatest { location ->
            val here = GeoPoint(location.coordinates.lat, location.coordinates.lng)
            val last = from
            val showing = _preview.value?.routes?.isNotEmpty() == true
            if (last != null && showing && here.distanceTo(last) < PREVIEW_MOVE_M) return@collectLatest
            // Not more often than every PREVIEW_REFRESH_MS: wait out the rest, unless a newer fix comes first.
            if (last != null) delay(fromMs + PREVIEW_REFRESH_MS - SystemClock.elapsedRealtime())
            from = here
            fromMs = SystemClock.elapsedRealtime()
            // Fixes keep coming while the routes do: they wait for them rather than cancel them.
            withContext(NonCancellable) {
                if (!showing) _preview.update { if (it == null) RoutePreview(place, loading = true) else if (it.destination.sameSpot(place)) it.copy(loading = true, error = null) else it }
                val result = runCatching { withContext(Dispatchers.IO) { core.getRoutes(location, listOf(destination)) } }
                _preview.update { current ->
                    val routes = result.getOrNull().orEmpty()
                    when {
                        // Closed, or another place previewed meanwhile. (A dropped pin may have got its name since: same spot.)
                        current == null || !current.destination.sameSpot(place) -> current
                        routes.isNotEmpty() -> current.copy(routes = routes, selected = current.selected.takeIf { it < routes.size } ?: 0, loading = false, error = null)
                        // A refresh that failed keeps the routes we had: they're still good to go.
                        current.routes.isNotEmpty() -> current
                        result.isSuccess -> current.copy(loading = false, error = "No route found")
                        else -> current.copy(loading = false, error = "Couldn't get a route: ${result.exceptionOrNull()?.message}")
                    }
                }
            }
        }
    }

    /** Previews a route to a pin dropped on the map: named after what's there, online or off, once that's known. */
    fun dropPin(point: GeoPoint) {
        val pin = Place(title = PlaceNames.DROPPED_PIN, detail = "", latitude = point.latitude, longitude = point.longitude)
        preview(pin)
        val search = placeSearch ?: return
        viewModelScope.launch {
            val named = runCatching { search.pinAt(point) }.getOrNull() ?: return@launch
            _preview.update { current -> if (current != null && current.destination.sameSpot(pin)) current.copy(destination = named) else current }
        }
    }

    private fun Place.sameSpot(other: Place) = latitude == other.latitude && longitude == other.longitude

    /** Previews a route to a place from the convoy: a shared destination or the regroup point. */
    fun previewConvoyPlace(point: GeoPoint, name: String) {
        preview(Place(title = name, detail = "From the convoy", latitude = point.latitude, longitude = point.longitude))
    }

    fun selectRoute(index: Int) {
        _preview.update { it?.copy(selected = index) }
    }

    fun cancelPreview() {
        previewJob?.cancel()
        _preview.value = null
    }

    fun setSimulate(simulate: Boolean) {
        _simulate.value = simulate
    }

    /** Starts turn-by-turn on the selected route. */
    fun startNavigation() {
        val preview = _preview.value ?: return
        val route = preview.selectedRoute ?: return
        previewJob?.cancel()
        _preview.value = null
        destination = preview.destination
        places.driveTo(preview.destination)
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

    /**
     * A camera or report ahead ([kind] as in [Phrases.warning]), said as the settings say: the
     * full sentence (with a camera's [limit]), the short one, a tone, or nothing.
     */
    private fun warn(kind: String, distanceM: Double, full: String, short: String, limit: Int? = null) {
        if (core.spokenInstructionObserver?.isMuted == true) return
        val units = settings.value.units
        when (settings.value.announcements) {
            Announcements.FULL -> say(Utterance(Phrases.warning(kind, distanceM, units, short = false, limit = limit), full))
            Announcements.SHORT -> say(Utterance(Phrases.warning(kind, distanceM, units, short = true), short))
            Announcements.TONE -> playAlert()
            Announcements.OFF -> Unit
        }
    }

    /** Reports [type] where we are, for the direction we're heading. */
    fun report(type: String) {
        val client = reportsClient ?: return
        val fix = ourFix.value
        if (fix == null) {
            showReportNotice("Waiting for your location")
            return
        }
        val moving = fix.speedMps >= REPORT_HEADING_MIN_MPS
        viewModelScope.launch {
            runCatching { client.report(NewReport(type, fix.point.latitude, fix.point.longitude, bearing = fix.headingDeg.takeIf { moving })) }
                .onSuccess { id ->
                    passedReports.skip(id)
                    refreshReports.trySend(Unit)
                    showReportNotice("Reported: ${ReportLabels.label(type).lowercase()}. Thanks!")
                }
                .onFailure { showReportNotice("Couldn't send the report") }
        }
    }

    /** The answer to [stillThere]: yes, no, or null for no answer. */
    fun answerStillThere(stillThere: Boolean?) {
        val report = _stillThere.value ?: return
        _stillThere.value = null
        stillThereJob?.cancel()
        if (stillThere == null) return
        val client = reportsClient ?: return
        viewModelScope.launch {
            runCatching { client.vote(report.id, stillThere) }
            refreshReports.trySend(Unit)
        }
    }

    private fun askStillThere(report: Report) {
        _stillThere.value = report
        stillThereJob?.cancel()
        stillThereJob = viewModelScope.launch {
            delay(STILL_THERE_SHOWN_MS)
            _stillThere.value = null
        }
    }

    private fun showReportNotice(text: String) {
        _reportNotice.value = text
        reportNoticeJob?.cancel()
        reportNoticeJob = viewModelScope.launch {
            delay(REPORT_NOTICE_SHOWN_MS)
            _reportNotice.value = null
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
        const val REPORTS_REFRESH_MS = 60_000L
        const val STILL_THERE_SHOWN_MS = 12_000L
        const val REPORT_NOTICE_SHOWN_MS = 3_000L

        /** Slower than this, the heading is GPS noise: the report is for both directions. */
        const val REPORT_HEADING_MIN_MPS = 3.0

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

/**
 * The course from the previous fix where the source reports none. Below a few metres of movement
 * the direction is GPS noise, so the previous course carries on (or none, standing still from the start).
 */
private fun UserLocation.withCourseFrom(previous: UserLocation?): UserLocation {
    if (courseOverGround != null || previous == null) return this
    val from = GeoPoint(previous.coordinates.lat, previous.coordinates.lng)
    val to = GeoPoint(coordinates.lat, coordinates.lng)
    if (from.distanceTo(to) < MIN_COURSE_MOVE_M) return copy(courseOverGround = previous.courseOverGround)
    return copy(courseOverGround = CourseOverGround(from.bearingTo(to).roundToInt().mod(360).toUShort(), COURSE_ACCURACY_DEG))
}

private const val MIN_COURSE_MOVE_M = 3.0
private const val SHORTCUT_MOVE_M = 500.0

/** An open preview's routes are worked out again after this far, at most this often. */
private const val PREVIEW_MOVE_M = 50.0
private const val PREVIEW_REFRESH_MS = 10_000L
private const val SHORTCUT_REFRESH_MS = 5 * 60_000L
private val COURSE_ACCURACY_DEG: UShort = 20u

private fun LocalDateTime.toDay(): Day = Day(year, monthValue, dayOfMonth)

/** "Accident in 600 metres". */
private fun ReportAhead.spoken(units: UnitSystem): String = "${ReportLabels.label(report.type)} in ${spokenDistance(distanceM, units)}"

/** "Speed camera in 400 metres, limit 80". */
private fun CameraAhead.spoken(units: UnitSystem): String =
    "${short()} in ${spokenDistance(distanceM, units)}" + (camera.maxspeed?.let { ", limit ${limitIn(it, units)}" } ?: "")

private fun CameraAhead.short(): String = when (camera.kind) {
    Camera.RED_LIGHT -> "Red light camera"
    Camera.AVERAGE_SPEED -> "Average speed check"
    else -> "Speed camera"
}
