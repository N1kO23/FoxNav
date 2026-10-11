package app.nullpointr.foxnav.drive

import app.nullpointr.foxnav.ui.format.distanceText
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nullpointr.foxnav.BuildConfig
import app.nullpointr.foxnav.appGraph
import app.nullpointr.foxnav.settings.OfflineMapsSheet
import app.nullpointr.foxnav.settings.ServerSettingsSheet
import app.nullpointr.foxnav.shared.discovery.DiscoveryDocument
import app.nullpointr.foxnav.shared.format.formatDistance
import app.nullpointr.foxnav.shared.format.formatDuration
import app.nullpointr.foxnav.ui.components.SpeedCluster
import app.nullpointr.foxnav.ui.PanelBounds
import app.nullpointr.foxnav.ui.bottomPanelOf
import app.nullpointr.foxnav.ui.icons.FoxIcons
import app.nullpointr.foxnav.ui.topPanelOf
import app.nullpointr.foxnav.ui.theme.ChakraPetch
import com.stadiamaps.ferrostar.maplibreui.runtime.NavigationMapState
import org.maplibre.spatialk.geojson.BoundingBox
import java.text.DecimalFormatSymbols

/** Everything on top of the map when not navigating: search, route preview and the free-drive panel. */
@Composable
fun BoxScope.FreeDriveOverlay(
    modifier: Modifier,
    viewModel: DriveViewModel,
    document: DiscoveryDocument,
    navigationMapState: NavigationMapState,
    panels: PanelBounds,
    onOpenConvoy: () -> Unit,
    onOpenReport: () -> Unit,
) {
    val uiState by viewModel.navigationUiState.collectAsState()
    if (uiState.isNavigating()) return
    val preview by viewModel.preview.collectAsState()
    val query by viewModel.query.collectAsState()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val online by LocalContext.current.appGraph.connectivity.online.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (preview == null) {
            Row(Modifier.topPanelOf(panels), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                SearchPanel(viewModel, Modifier.weight(1f))
                val inConvoy = LocalContext.current.appGraph.convoy.active.collectAsState().value != null
                PanelButton(onClick = onOpenConvoy) {
                    Icon(FoxIcons.Convoy, contentDescription = "Convoy", tint = if (inConvoy) MaterialTheme.colorScheme.secondary else LocalContentColor.current)
                }
                PanelButton(onClick = { showSettings = true }) { Icon(FoxIcons.Settings, contentDescription = "Settings") }
            }
            if (!online) OfflineChip()
        }
        // While searching, the results and the keyboard need the room.
        if (preview != null || query.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (preview == null && viewModel.canReport) {
                    ReportButton(onClick = onOpenReport, modifier = Modifier.align(Alignment.BottomStart))
                }
                Column(Modifier.align(Alignment.BottomEnd), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (LocalContext.current.appGraph.settings.settings.collectAsState().value.zoomButtons) {
                        PanelButton(onClick = { navigationMapState.zoomIn() }) { Icon(FoxIcons.Plus, contentDescription = "Zoom in") }
                        PanelButton(onClick = { navigationMapState.zoomOut() }) { Icon(FoxIcons.Minus, contentDescription = "Zoom out") }
                    }
                    PanelButton(onClick = { navigationMapState.recenter(isNavigating = false) }) {
                        Icon(FoxIcons.Locate, contentDescription = "Center on my location")
                    }
                }
            }
            if (preview == null) {
                val cameraAhead by viewModel.cameraAhead.collectAsState()
                ReportStack(viewModel)
                CameraWarning(cameraAhead)
            }
            Box(Modifier.bottomPanelOf(panels)) {
                preview?.let { RoutePreviewPanel(it, viewModel) } ?: FreeDrivePanel(viewModel)
            }
        }
    }

    if (showSettings) {
        DriveSettings(document, viewModel, navigationMapState, onClose = { showSettings = false })
    }
}

/** Without a connection: what still works. */
@Composable
private fun OfflineChip() {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(16.dp), color = colors.surface, border = BorderStroke(1.dp, colors.tertiary)) {
        Text(
            "OFFLINE · map from downloaded areas",
            Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            letterSpacing = 1.sp,
            color = colors.tertiary,
        )
    }
}

/** Speed, limit and the road you're on, without a destination. */
@Composable
private fun FreeDrivePanel(viewModel: DriveViewModel) {
    val speed by viewModel.speed.collectAsState()
    val road by viewModel.road.collectAsState()
    val arrivedAt by viewModel.arrivedAt.collectAsState()
    val colors = MaterialTheme.colorScheme

    Surface(shape = RoundedCornerShape(24.dp), color = colors.surface, border = BorderStroke(1.dp, colors.outline)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            SpeedCluster(speed)
            Column(Modifier.weight(1f)) {
                Text(arrivedAt ?: road?.name ?: "Free drive", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (arrivedAt != null) "ARRIVED" else "FREE DRIVE",
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    letterSpacing = 2.sp,
                    color = colors.secondary,
                )
            }
        }
    }
}

@Composable
private fun SearchPanel(viewModel: DriveViewModel, modifier: Modifier = Modifier) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val colors = MaterialTheme.colorScheme

    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = colors.surface, border = BorderStroke(1.dp, colors.outline)) {
        Column {
            TextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                placeholder = { Text("Where to?") },
                leadingIcon = { Icon(FoxIcons.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) { Icon(FoxIcons.Close, contentDescription = "Clear") }
                    }
                },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            if (results.isNotEmpty()) {
                HorizontalDivider(color = colors.outline)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(results) { place ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.preview(place) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(FoxIcons.Pin, contentDescription = null, tint = colors.onSurfaceVariant)
                            Column {
                                Text(place.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (place.detail.isNotEmpty()) {
                                    Text(place.detail, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutePreviewPanel(preview: RoutePreview, viewModel: DriveViewModel) {
    val simulate by viewModel.simulate.collectAsState()
    val colors = MaterialTheme.colorScheme
    val separator = DecimalFormatSymbols.getInstance().decimalSeparator

    Surface(shape = RoundedCornerShape(20.dp), color = colors.surface, border = BorderStroke(1.dp, colors.outline)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(preview.destination.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (preview.destination.detail.isNotEmpty()) {
                    Text(preview.destination.detail, color = colors.onSurfaceVariant)
                }
            }
            when {
                preview.loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Finding routes…")
                }
                preview.error != null -> Text(preview.error, color = colors.error)
                else -> preview.routes.forEachIndexed { index, route ->
                    val selected = index == preview.selected
                    Surface(
                        onClick = { viewModel.selectRoute(index) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) colors.secondary.copy(alpha = 0.15f) else Color.Transparent,
                        border = if (selected) BorderStroke(1.dp, colors.secondary) else null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(formatDuration(route.steps.sumOf { it.duration }), fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(distanceText(route.distance), fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
            if (BuildConfig.DEBUG) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Simulate the drive", Modifier.weight(1f), color = colors.onSurfaceVariant)
                    Switch(checked = simulate, onCheckedChange = viewModel::setSimulate)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = viewModel::cancelPreview, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(onClick = viewModel::startNavigation, enabled = preview.selectedRoute != null, modifier = Modifier.weight(1f)) { Text("Start") }
            }
        }
    }
}

/** A round 56 dp panel-coloured button for map controls. */
@Composable
private fun PanelButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.size(56.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

