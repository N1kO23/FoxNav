package app.nullpointr.foxnav

import android.content.Context
import app.nullpointr.foxnav.convoy.ConvoyController
import app.nullpointr.foxnav.convoy.ProfileStore
import app.nullpointr.foxnav.convoy.TowerPlacementStore
import app.nullpointr.foxnav.data.PlaceSearch
import app.nullpointr.foxnav.data.RoadLookup
import app.nullpointr.foxnav.location.liveLocationProvider
import app.nullpointr.foxnav.settings.ServerSettings
import app.nullpointr.foxnav.voice.Chime
import app.nullpointr.foxnav.shared.discovery.ServiceEndpoint
import com.stadiamaps.ferrostar.composeui.notification.DefaultForegroundNotificationBuilder
import com.stadiamaps.ferrostar.core.AndroidTtsObserver
import com.stadiamaps.ferrostar.core.FerrostarCore
import com.stadiamaps.ferrostar.core.http.OkHttpClientProvider.Companion.toOkHttpClientProvider
import com.stadiamaps.ferrostar.core.location.NavigationLocationProvider
import com.stadiamaps.ferrostar.core.location.SimulatedLocationProvider
import com.stadiamaps.ferrostar.core.service.FerrostarForegroundServiceManager
import com.stadiamaps.ferrostar.core.withJsonOptions
import okhttp3.OkHttpClient
import uniffi.ferrostar.CourseFiltering
import uniffi.ferrostar.NavigationControllerConfig
import uniffi.ferrostar.RouteDeviationTracking
import uniffi.ferrostar.WaypointAdvanceMode
import uniffi.ferrostar.WellKnownRouteProvider
import uniffi.ferrostar.stepAdvanceDistanceEntryAndExit
import uniffi.ferrostar.stepAdvanceDistanceToEndOfStep
import java.time.Duration

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

    // Keeps navigation (and location updates) running with the screen off.
    private val foregroundServiceManager by lazy {
        FerrostarForegroundServiceManager(context, DefaultForegroundNotificationBuilder(context))
    }

    fun placeSearch(endpoint: ServiceEndpoint) = PlaceSearch(httpClient, endpoint)

    fun roadLookup(routing: ServiceEndpoint) = RoadLookup(httpClient, routing)

    /** A navigation core that routes with the server's Valhalla, with guidance in [language]. */
    fun ferrostarCore(routing: ServiceEndpoint, language: String): FerrostarCore {
        val provider = WellKnownRouteProvider.Valhalla(endpointUrl = routing.url.trimEnd('/') + "/route", profile = "auto")
            .withJsonOptions(mapOf("language" to language, "units" to "kilometers", "alternates" to 2))
        return FerrostarCore(
            provider,
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
        ).apply { spokenInstructionObserver = voice }
    }
}
