package app.nullpointr.foxnav.drive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.nullpointr.foxnav.shared.theme.Palettes
import app.nullpointr.foxnav.ui.theme.LocalNight
import app.nullpointr.foxnav.ui.theme.LocalThemeColors
import org.maplibre.compose.style.BaseStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
        if (graph.offlineDownloads.allowed.value) {
            // Stale entries from an earlier version's map update: the areas download again, once. Only
            // when downloads may run, so nobody is left without maps.
            offline.repairStale()
            offline.resumeUnfinished()
        }
    }
    // Where we are, roughly, for sunrise and sunset.
    LaunchedEffect(viewModel) {
        viewModel.location.collect { location -> location?.let { graph.lastArea.update(GeoPoint(it.coordinates.lat, it.coordinates.lng)) } }
    }
    // The map's day or night style follows the app's colours (settings: by the sun, the system, or fixed),
    // from the copy on the phone, in the driver's palette and label size.
    LaunchedEffect(document) { graph.mapStyles.use(document) }
    val theme = if (LocalNight.current) "night" else "day"
    val styleUrl = document.styles[theme] ?: document.styles.values.first()
    val settings by graph.settings.settings.collectAsState()
    // The styles come in the default palette: only another one needs recolouring.
    val colors = LocalThemeColors.current?.takeIf { graph.palette(settings.palette).id != Palettes.DEFAULT }
    val styles by graph.mapStyles.styles.collectAsState()
    val stored = styles[styleUrl] ?: remember(styleUrl) { graph.mapStyles.style(styleUrl) }
    val labelScale = settings.textScale.toDouble()
    fun styled(): BaseStyle = stored?.let { BaseStyle.Json(graph.mapStyles.styled(it, colors, labelScale)) } ?: BaseStyle.Uri(styleUrl)
    // The first one at once, so the map never shows another palette first; later ones (another
    // palette, day turning to night) off the main thread, keeping the old style meanwhile.
    var baseStyle by remember { mutableStateOf(styled()) }
    LaunchedEffect(styleUrl, stored, colors, labelScale) { baseStyle = withContext(Dispatchers.Default) { styled() } }
    DriveScreen(viewModel, document, styleUrl, baseStyle)
}
