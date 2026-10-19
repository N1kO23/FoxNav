package app.nullpointr.foxnav.drive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nullpointr.foxnav.appGraph
import app.nullpointr.foxnav.data.ReportsClient
import app.nullpointr.foxnav.shared.discovery.DiscoveryDocument
import app.nullpointr.foxnav.shared.discovery.Feature
import app.nullpointr.foxnav.shared.geo.GeoPoint
import app.nullpointr.foxnav.ui.theme.LocalNight
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** Sets up driving for the server described by [document]. */
@Composable
fun DriveRoute(document: DiscoveryDocument) {
    val routing = document.routing?.takeIf { it.type == "valhalla" }
    if (routing == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("${document.name} has no Valhalla routing, which FoxNav needs.", color = MaterialTheme.colorScheme.error)
        }
        return
    }

    val graph = LocalContext.current.appGraph
    val search = document.search?.takeIf { it.type == "photon" }
    val viewModel: DriveViewModel = viewModel(key = "drive:${routing.url}:${search?.url}") {
        DriveViewModel(
            core = graph.ferrostarCore(routing),
            locationProvider = graph.locationProvider,
            placeSearch = search?.let(graph::placeSearch),
            roadLookup = graph.roadLookup(routing),
            convoy = graph.convoy,
            settings = graph.settings.settings,
            playOverLimit = graph.chime::playOverLimit,
            playAlert = graph.chime::playAlert,
            loadCameras = { graph.dataPacks.cameras(document) },
            loadSpeedLimits = { graph.dataPacks.speedLimits(document) },
            cameraWarnings = graph.alertSettings.cameraWarnings,
            reportsClient = document.reports?.takeIf { Feature.REPORTS in document.features }?.let { ReportsClient(graph.httpClient, it) },
            reportWarnings = graph.alertSettings.reportWarnings,
            say = graph.guidanceVoice::say,
            places = graph.places,
            travelTimes = graph.travelTimes(routing),
            retired = graph.claimDrive(),
        )
    }
    // Road data for routes without a connection follows this server's tiles, and carries on downloading.
    LaunchedEffect(document) { graph.roadData.use(document) }
    LaunchedEffect(Unit) {
        // Downloads interrupted when the app last closed carry on, once MapLibre has listed them.
        val offline = graph.offlineMaps
        offline.configure()
        withTimeoutOrNull(5.seconds) { snapshotFlow { offline.areas }.first { it.isNotEmpty() } }
        if (graph.offlineDownloads.allowed.value) offline.resumeUnfinished()
    }
    // Where we are, roughly, for sunrise and sunset.
    LaunchedEffect(viewModel) {
        viewModel.location.collect { location -> location?.let { graph.lastArea.update(GeoPoint(it.coordinates.lat, it.coordinates.lng)) } }
    }
    // The map's day or night style follows the app's colours (settings: by the sun, the system, or fixed).
    val theme = if (LocalNight.current) "night" else "day"
    val styleUrl = document.styles[theme] ?: document.styles.values.first()
    DriveScreen(viewModel, document, styleUrl)
}
