package app.nullpointr.foxnav.drive

import app.nullpointr.foxnav.ui.format.distanceText
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
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
import app.nullpointr.foxnav.shared.discovery.DiscoveryDocument
import app.nullpointr.foxnav.shared.format.formatDuration
import app.nullpointr.foxnav.ui.components.DockedPanel
import app.nullpointr.foxnav.ui.components.HudLabel
import app.nullpointr.foxnav.ui.components.PanelAction
import app.nullpointr.foxnav.ui.components.SpeedCluster
import app.nullpointr.foxnav.ui.PanelBounds
import app.nullpointr.foxnav.shared.map.MapCredit
import app.nullpointr.foxnav.ui.bottomPanelOf
import app.nullpointr.foxnav.ui.icons.FoxIcons
import app.nullpointr.foxnav.ui.topPanelOf
import app.nullpointr.foxnav.ui.theme.ChakraPetch
import com.stadiamaps.ferrostar.maplibreui.runtime.NavigationMapState
import java.text.DecimalFormatSymbols

/**
 * Everything on top of the map when not navigating, laid out as the free-drive mockup: search at
 * the top with what's ahead under it, the map buttons at the right edge, and the panel docked
 * at the bottom with speed, the road, and the Report and Convoy buttons.
 */
@Composable
fun FreeDriveOverlay(
    viewModel: DriveViewModel,
    document: DiscoveryDocument,
    navigationMapState: NavigationMapState,
    panels: PanelBounds,
    mapCredits: List<MapCredit>,
    /** Cars in our convoy, us included; null when not in one. */
    convoySize: Int?,
    onOpenConvoy: () -> Unit,
    onOpenReport: () -> Unit,
) {
    val uiState by viewModel.navigationUiState.collectAsState()
    if (uiState.isNavigating()) return
    val preview by viewModel.preview.collectAsState()
    val query by viewModel.query.collectAsState()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val online by LocalContext.current.appGraph.connectivity.online.collectAsState()
    val sides = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (preview == null) {
                Row(Modifier.topPanelOf(panels), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    SearchPanel(viewModel, Modifier.weight(1f))
                    VoiceSearchButton(onHeard = viewModel::onQueryChange)
                    PanelButton(onClick = { showSettings = true }) { Icon(FoxIcons.Settings, contentDescription = "Settings") }
                }
                if (!online) OfflineChip()
                if (query.isEmpty()) RoadWarnings(viewModel)
            }
        }
        // While searching, the results and the keyboard need the room.
        if (preview != null || query.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(sides).padding(horizontal = 12.dp)) {
                MapControls(
                    navigationMapState,
                    isMuted = uiState.isMuted,
                    onToggleMute = viewModel::toggleMute,
                    onRouteOverview = null,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
                if (preview == null) {
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)) { ReportStack(viewModel) }
                }
            }
            Column(Modifier.bottomPanelOf(panels)) {
                MapCreditTag(mapCredits, Modifier.windowInsetsPadding(sides).padding(start = 12.dp))
                DockedPanel {
                    preview?.let { RoutePreviewContent(it, viewModel) }
                        ?: FreeDriveContent(viewModel, convoySize, onOpenConvoy, onOpenReport.takeIf { viewModel.canReport })
                }
            }
        }
    }

    if (showSettings) {
        DriveSettings(document, viewModel, navigationMapState, onClose = { showSettings = false })
    }
}

/** "Where to?" by voice: the phone's speech recogniser fills in the search. Hidden on phones without one. */
@Composable
private fun VoiceSearchButton(onHeard: (String) -> Unit) {
    val context = LocalContext.current
    val intent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Where to?")
    }
    val available = remember { intent.resolveActivity(context.packageManager) != null }
    val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(onHeard)
    }
    if (available) {
        PanelButton(onClick = { runCatching { listen.launch(intent) } }) { Icon(FoxIcons.Mic, contentDescription = "Search by voice") }
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

/** Speed, limit and the road you're on, without a destination; then Report and Convoy. */
@Composable
private fun FreeDriveContent(viewModel: DriveViewModel, convoySize: Int?, onOpenConvoy: () -> Unit, onOpenReport: (() -> Unit)?) {
    val speed by viewModel.speed.collectAsState()
    val road by viewModel.road.collectAsState()
    val arrivedAt by viewModel.arrivedAt.collectAsState()
    val colors = MaterialTheme.colorScheme

    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        SpeedCluster(speed)
        Column(Modifier.weight(1f)) {
            Text(arrivedAt ?: road?.name ?: "Free drive", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (arrivedAt != null) {
                HudLabel("ARRIVED", colors.secondary, accent = "到着")
            } else {
                HudLabel("FREE DRIVE", colors.secondary, accent = "走行中")
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (onOpenReport != null) PanelAction("Report", FoxIcons.Report, onOpenReport, brand = true)
        PanelAction("Convoy", FoxIcons.People, onOpenConvoy, badge = convoySize)
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

/** The routes to a place, to pick one and start. */
@Composable
private fun RoutePreviewContent(preview: RoutePreview, viewModel: DriveViewModel) {
    val simulate by viewModel.simulate.collectAsState()
    val colors = MaterialTheme.colorScheme
    val separator = DecimalFormatSymbols.getInstance().decimalSeparator

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

/** A 56 dp panel-coloured button beside the search, as in the mockups. */
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

