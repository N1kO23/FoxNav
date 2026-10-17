package app.nullpointr.foxnav

import android.content.Context
import app.nullpointr.foxnav.convoy.ConvoyController
import app.nullpointr.foxnav.convoy.MusicSessions
import app.nullpointr.foxnav.convoy.ProfileStore
import app.nullpointr.foxnav.convoy.TowerPlacementStore
import app.nullpointr.foxnav.data.Connectivity
import app.nullpointr.foxnav.data.DataPacks
import app.nullpointr.foxnav.data.DiscoveryCache
import app.nullpointr.foxnav.data.OfflineMaps
import app.nullpointr.foxnav.data.OnDeviceValhalla
import app.nullpointr.foxnav.data.PlaceSearch
import app.nullpointr.foxnav.data.ValhallaRoutes
import app.nullpointr.foxnav.shared.format.UnitSystem
import app.nullpointr.foxnav.voice.effectiveLanguage
import app.nullpointr.foxnav.data.PlaceStore
import app.nullpointr.foxnav.data.RoadData
import app.nullpointr.foxnav.data.RoadLookup
import app.nullpointr.foxnav.data.SearchIndex
import app.nullpointr.foxnav.data.TravelTimes
import app.nullpointr.foxnav.drive.RouteDeviationCheck
import app.nullpointr.foxnav.location.liveLocationProvider
import app.nullpointr.foxnav.settings.AlertSettings
import app.nullpointr.foxnav.settings.ServerSettings
import app.nullpointr.foxnav.settings.UserSettings
import app.nullpointr.foxnav.ui.theme.LastArea
import app.nullpointr.foxnav.voice.Chime
import app.nullpointr.foxnav.voice.ClipPlayer
import app.nullpointr.foxnav.voice.GuidanceVoice
import app.nullpointr.foxnav.voice.VoicePacks
import uniffi.ferrostar.TripState
import app.nullpointr.foxnav.shared.discovery.ServiceEndpoint
import com.stadiamaps.ferrostar.composeui.notification.DefaultForegroundNotificationBuilder
import com.stadiamaps.ferrostar.core.AndroidTtsObserver
import com.stadiamaps.ferrostar.core.FerrostarCore
import com.stadiamaps.ferrostar.core.http.OkHttpClientProvider.Companion.toOkHttpClientProvider
import com.stadiamaps.ferrostar.core.location.NavigationLocationProvider
import com.stadiamaps.ferrostar.core.location.SimulatedLocationProvider
import com.stadiamaps.ferrostar.core.service.FerrostarForegroundServiceManager
import okhttp3.OkHttpClient
import uniffi.ferrostar.CourseFiltering
import uniffi.ferrostar.NavigationControllerConfig
import uniffi.ferrostar.RouteDeviationTracking
import uniffi.ferrostar.SpokenInstruction
import uniffi.ferrostar.WaypointAdvanceMode
import uniffi.ferrostar.stepAdvanceDistanceEntryAndExit
import uniffi.ferrostar.stepAdvanceDistanceToEndOfStep
import java.io.File
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** App-wide objects, created once in [FoxNavApplication]. Small enough not to need a DI framework. */
class AppGraph(private val context: Context) {
    val httpClient: OkHttpClient = OkHttpClient.Builder().callTimeout(Duration.ofSeconds(15)).build()

    val serverSettings = ServerSettings(context)

    /** Real GPS, or a simulated drive along the route (for testing and the emulator). */
    val locationProvider = NavigationLocationProvider(
        liveProviding = liveLocationProvider(context),
        simulatedProvider = SimulatedLocationProvider(warpFactor = 2u),
    )

    /** Speaks guidance with the system text-to-speech engine. Started and stopped by MainActivity. */
    val voice = AndroidTtsObserver(context)

    val chime by lazy { Chime(context) }

    /** How we appear in convoys. */
    val profiles = ProfileStore(context)

    /** The convoy we're in, if any; it shares positions from [locationProvider]. */
    val convoy by lazy { ConvoyController(context, locationProvider, profiles, voice, MusicSessions(context), settings.settings) }

    /** Where the user put the convoy tower on the map. */
    val towerPlacement = TowerPlacementStore(context)

    val alertSettings = AlertSettings(context)

    /** How the user wants FoxNav to look, sound and behave. */
    val settings = UserSettings(context)

    val lastArea = LastArea(context)

    val dataPacks = DataPacks(context, httpClient)

    val connectivity = Connectivity(context)

    val discoveryCache = DiscoveryCache(context)

    /** Map areas downloaded for driving without a connection. */
    val offlineMaps by lazy { OfflineMaps(context) }

    /** App-wide work that outlives any screen: road data downloads. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Places to search without a connection, where there's road data. */
    private val searchIndex by lazy { SearchIndex(File(context.filesDir, "roaddata/search.db")) }

    /** The routing graph's tiles on the phone, with search data: for map areas, trips and the road data chosen in settings. */
    val roadData by lazy { RoadData(File(context.filesDir, "roaddata"), httpClient, connectivity.online, scope, searchIndex) }

    /** Valhalla on the phone, for routes and road lookups from [roadData] (ADR 0005). */
    private val onDeviceValhalla by lazy { OnDeviceValhalla(context, roadData) }

    val voicePacks = VoicePacks(context)

    val clipPlayer by lazy { ClipPlayer(context) }

    /** The voice for guidance and warnings: the chosen voice pack where it can, text-to-speech otherwise. */
    val guidanceVoice by lazy { GuidanceVoice(voice, voicePacks, clipPlayer, settings.settings) }

    /** Says [text] with the guidance voice, which ducks music and respects mute. */
    fun speak(text: String) {
        voice.onSpokenInstructionTrigger(SpokenInstruction(text, null, 0.0, UUID.randomUUID()))
    }

    // Keeps navigation (and location updates) running with the screen off.
    private val foregroundServiceManager by lazy {
        FerrostarForegroundServiceManager(context, DefaultForegroundNotificationBuilder(context))
    }

    /** Searches with the server's Photon; offline, the phone's search data, with only places its road data can route to. */
    fun placeSearch(endpoint: ServiceEndpoint) = PlaceSearch(
        httpClient,
        endpoint,
        searchIndex,
        online = { connectivity.online.value },
        covered = roadData::covers,
        reachable = onDeviceValhalla::reachable,
    )

    fun roadLookup(routing: ServiceEndpoint) = RoadLookup(httpClient, routing, onDeviceValhalla, online = { connectivity.online.value })

    /** Travel times for the shortcuts; only Valhalla says them. */
    fun travelTimes(routing: ServiceEndpoint): TravelTimes? = routing.takeIf { it.type == "valhalla" }?.let { TravelTimes(httpClient, it.url) }

    /** Home, work, favourites and recent destinations. */
    val places by lazy { PlaceStore(context) }

    private var navigationCore: Pair<String, FerrostarCore>? = null

    private val driveOwner = MutableStateFlow(0L)
    private var lastDriveOwner = 0L

    /**
     * For a new drive screen's view model: a flow that turns true once a newer one takes over
     * (another server's). The old one then stops its work, or its warnings would double up.
     */
    @Synchronized
    fun claimDrive(): Flow<Boolean> {
        val me = ++lastDriveOwner
        driveOwner.value = me
        return driveOwner.map { it != me }
    }

    /**
     * The navigation core, routing with the server's Valhalla in the guidance language and units
     * of the settings at the time of each route; without a connection, with the phone's.
     *
     * One for the app's life: the drive screen's view model goes when its activity does, but a
     * trip under way carries on in the foreground service. Reopened, the screen picks that trip
     * up again rather than starting a second core beside it, which would go on navigating and
     * talking unseen. Only another server gets a new core, and the old one stops.
     */
    @Synchronized
    fun ferrostarCore(routing: ServiceEndpoint): FerrostarCore {
        navigationCore?.let { (url, existing) ->
            if (url == routing.url) return existing
            existing.spokenInstructionObserver = null
            existing.stopNavigation()
            locationProvider.disableSimulation()
        }
        return newFerrostarCore(routing).also { navigationCore = routing.url to it }
    }

    private fun newFerrostarCore(routing: ServiceEndpoint): FerrostarCore {
        val routes = ValhallaRoutes(httpClient, routing.url.trimEnd('/') + "/route", onDeviceValhalla, online = { connectivity.online.value }) {
            val current = settings.settings.value
            mapOf(
                "language" to current.effectiveLanguage(),
                "units" to if (current.units == UnitSystem.IMPERIAL) "miles" else "kilometers",
                "alternates" to 2,
            )
        }
        return FerrostarCore(
            routes,
            httpClient = httpClient.toOkHttpClientProvider(),
            locationProvider = locationProvider,
            foregroundServiceManager = foregroundServiceManager,
            navigationControllerConfig = NavigationControllerConfig(
                WaypointAdvanceMode.WaypointWithinRange(100.0),
                stepAdvanceDistanceEntryAndExit(30u, 5u, 32u),
                stepAdvanceDistanceToEndOfStep(10u, 32u),
                // Off the line, or the wrong way along it: Ferrostar then reroutes from where we are.
                RouteDeviationTracking.Custom(RouteDeviationCheck()),
                CourseFiltering.SNAP_TO_ROUTE,
            ),
        ).also { core ->
            core.spokenInstructionObserver = guidanceVoice
            // What an instruction is about, for voice packs: the manoeuvre of the route step it
            // belongs to. Starting a trip (or after a new route), the core asks for the first
            // instruction a moment before it says it's navigating that route: wait for it.
            guidanceVoice.maneuverFor = { instruction ->
                withTimeoutOrNull(STEP_LOOKUP_MS) {
                    core.state
                        .map { state ->
                            (state.tripState as? TripState.Navigating)?.remainingSteps
                                ?.firstOrNull { step -> step.spokenInstructions.any { it.utteranceId == instruction.utteranceId } }
                        }
                        .filterNotNull()
                        .first()
                }?.visualInstructions?.firstOrNull()?.primaryContent
                    ?.let { content -> content.maneuverType?.name?.let { it to content.maneuverModifier?.name } }
            }
        }
    }
}

private const val STEP_LOOKUP_MS = 500L
