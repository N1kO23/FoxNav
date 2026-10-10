package app.nullpointr.foxnav.drive

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nullpointr.foxnav.appGraph
import app.nullpointr.foxnav.shared.discovery.DiscoveryDocument
import app.nullpointr.foxnav.voice.guidanceLanguage

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
            core = graph.ferrostarCore(routing, guidanceLanguage()),
            locationProvider = graph.locationProvider,
            placeSearch = search?.let(graph::placeSearch),
            roadLookup = graph.roadLookup(routing),
            convoy = graph.convoy,
            playOverLimitChime = graph.chime::playKinKon,
        )
    }
    // Follows the system theme for now; later at sunrise/sunset and in tunnels.
    val theme = if (isSystemInDarkTheme()) "night" else "day"
    val styleUrl = document.styles[theme] ?: document.styles.values.first()
    DriveScreen(viewModel, document, styleUrl)
}
