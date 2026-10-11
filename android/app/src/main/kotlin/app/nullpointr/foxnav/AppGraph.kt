package app.nullpointr.foxnav

import android.content.Context
import app.nullpointr.foxnav.convoy.ConvoyController
import app.nullpointr.foxnav.convoy.ProfileStore
import app.nullpointr.foxnav.convoy.TowerPlacementStore
import app.nullpointr.foxnav.data.Connectivity
import app.nullpointr.foxnav.data.DataPacks
import app.nullpointr.foxnav.data.DiscoveryCache
import app.nullpointr.foxnav.data.OfflineMaps
import app.nullpointr.foxnav.data.PlaceSearch
import app.nullpointr.foxnav.data.ValhallaRoutes
import app.nullpointr.foxnav.shared.format.UnitSystem
import app.nullpointr.foxnav.voice.effectiveLanguage
import app.nullpointr.foxnav.data.RoadLookup
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
import java.time.Duration
import java.util.UUID

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
    val convoy by lazy { ConvoyController(context, locationProvider, profiles, voice) }

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

    fun placeSearch(endpoint: ServiceEndpoint) = PlaceSearch(httpClient, endpoint)

    fun roadLookup(routing: ServiceEndpoint) = RoadLookup(httpClient, routing)

    /**
     * A navigation core that routes with the server's Valhalla, in the guidance language and
     * units of the settings at the time of each route.
     */
    fun ferrostarCore(routing: ServiceEndpoint): FerrostarCore {
        val routes = ValhallaRoutes(httpClient, routing.url.trimEnd('/') + "/route") {
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
                RouteDeviationTracking.StaticThreshold(15u, 50.0),
                CourseFiltering.SNAP_TO_ROUTE,
            ),
        ).also { core ->
            core.spokenInstructionObserver = guidanceVoice
            // What the instruction being spoken is about, for voice packs: the current step's manoeuvre.
            guidanceVoice.currentManeuver = {
                val navigating = core.state.value.tripState as? TripState.Navigating
                navigating?.remainingSteps?.firstOrNull()?.visualInstructions?.firstOrNull()?.primaryContent
                    ?.let { content -> content.maneuverType?.name?.let { it to content.maneuverModifier?.name } }
            }
        }
    }
}
