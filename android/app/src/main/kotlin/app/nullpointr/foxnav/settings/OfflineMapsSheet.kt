package app.nullpointr.foxnav.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nullpointr.foxnav.appGraph
import app.nullpointr.foxnav.data.AreaStatus
import app.nullpointr.foxnav.data.OfflineArea
import app.nullpointr.foxnav.data.OfflineMaps
import app.nullpointr.foxnav.data.PlaceSearch
import app.nullpointr.foxnav.shared.geo.GeoPoint
import app.nullpointr.foxnav.shared.routing.RoadSelection
import app.nullpointr.foxnav.shared.routing.RoadSelectionKind
import kotlinx.coroutines.launch
import org.maplibre.spatialk.geojson.BoundingBox

/**
 * Downloads map areas for driving without a connection, and manages them. [visible] is what the
 * map shows now: the area offered for download.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapsSheet(visible: BoundingBox?, styleUrls: Collection<String>, placeSearch: PlaceSearch?, onDismiss: () -> Unit) {
    val graph = LocalContext.current.appGraph
    val offline = graph.offlineMaps
    val online by graph.connectivity.online.collectAsState()
    val colors = MaterialTheme.colorScheme

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Offline maps", style = MaterialTheme.typography.titleLarge)
            Text(
                "Download areas to keep the map without signal. With their road data, routes, guidance and speed limits work there too, " +
                    "and speed cameras work anywhere. Search, reports and convoys need a connection.",
                color = colors.onSurfaceVariant,
            )
            if (visible != null) NewArea(visible, styleUrls, placeSearch, online, offline)
            val areas = offline.areas
            if (areas.isNotEmpty()) {
                HorizontalDivider(color = colors.outline)
                Text("Downloaded", style = MaterialTheme.typography.titleMedium)
                areas.forEach { AreaRow(it, offline, online) }
            }
        }
    }
}

@Composable
private fun NewArea(visible: BoundingBox, styleUrls: Collection<String>, placeSearch: PlaceSearch?, online: Boolean, offline: OfflineMaps) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val first = offline.areas.isEmpty()
    val estimate = remember(visible, first) { OfflineMaps.estimate(visible, first) }
    val center = GeoPoint((visible.south + visible.north) / 2, (visible.west + visible.east) / 2)
    val small = GeoPoint(visible.south, visible.west).distanceTo(GeoPoint(visible.north, visible.east)) < SMALL_AREA_M
    var name by remember(visible) { mutableStateOf("") }
    var started by remember(visible) { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (name.isEmpty()) name = runCatching { placeSearch?.areaName(center, small) }.getOrNull().orEmpty()
    }
    val tooBig = estimate.tiles > OfflineMaps.MAX_TILES
    val roadData = context.appGraph.roadData
    val roads by roadData.state.collectAsState()
    val roadTiles = remember(visible, roads.manifest) { roads.tilesIn(visible) }
    val roadCoverage = roads.coverage(roadTiles)?.takeIf { roadTiles.isNotEmpty() }
    var withRoads by remember { mutableStateOf(true) }

    Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceVariant, border = BorderStroke(1.dp, colors.outline)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("The area on screen", style = MaterialTheme.typography.titleMedium)
            Text(
                if (tooBig) "Too big to download in one go (about ${size(context, estimate.bytes)}). Zoom in and download it in parts."
                else "About ${size(context, estimate.bytes)}, ${"%,d".format(estimate.tiles)} map tiles.",
                color = if (tooBig) colors.error else colors.onSurfaceVariant,
            )
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (roadCoverage != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("With road data", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (roadCoverage.complete) "Routes and speed limits offline here: already on the phone."
                            else "Routes and speed limits offline here too. About ${size(context, roadCoverage.missingDownload)} more.",
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Switch(checked = withRoads, onCheckedChange = { withRoads = it })
                }
            }
            Button(
                onClick = {
                    started = true
                    val areaName = name.trim().ifEmpty { "Map area" }
                    scope.launch {
                        val id = offline.download(areaName, visible, styleUrls)
                        if (withRoads && roadTiles.isNotEmpty()) {
                            roadData.add(RoadSelection.of(id, RoadSelectionKind.MAP_AREA, areaName, System.currentTimeMillis(), roadTiles))
                        }
                    }
                },
                enabled = online && !tooBig && !started,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text(if (started) "Downloading…" else "Download")
            }
            if (!online) Text("Connect to the internet to download.", color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun AreaRow(area: OfflineArea, offline: OfflineMaps, online: Boolean) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    val status = area.status
    val roadData = context.appGraph.roadData
    val roads by roadData.state.collectAsState()
    val roadSelection = roads.selection(area.id)
    val bounds = area.bounds
    val roadTiles = remember(bounds, roads.manifest) { bounds?.let { roads.tilesIn(it) }.orEmpty() }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(area.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            when (status) {
                AreaStatus.READY -> "Ready · ${size(context, area.bytes)}"
                AreaStatus.DOWNLOADING -> "Downloading · ${(area.fraction * 100).toInt()} % · ${size(context, area.bytes)}"
                AreaStatus.PAUSED -> "Paused at ${(area.fraction * 100).toInt()} %"
                AreaStatus.FAILED -> "Download failed: ${area.problem ?: "unknown error"}"
            },
            color = if (status == AreaStatus.FAILED) colors.error else colors.onSurfaceVariant,
        )
        if (status == AreaStatus.DOWNLOADING || status == AreaStatus.PAUSED) {
            LinearProgressIndicator(progress = { area.fraction }, modifier = Modifier.fillMaxWidth())
        }
        roadSelection?.let { roads.coverage(it) }?.let { coverage ->
            Text("Road data: ${status(context, coverage, roads.downloading, online).replaceFirstChar { it.lowercase() }}", color = colors.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when (status) {
                AreaStatus.DOWNLOADING -> TextButton(onClick = { offline.pause(area) }) { Text("Pause") }
                AreaStatus.PAUSED, AreaStatus.FAILED -> TextButton(onClick = { offline.resume(area) }, enabled = online) { Text("Resume") }
                AreaStatus.READY -> TextButton(onClick = { scope.launch { offline.update(area) } }, enabled = online) { Text("Update") }
            }
            if (roadSelection == null && roadTiles.isNotEmpty()) {
                val download = roads.coverage(roadTiles)?.missingDownload ?: 0L
                TextButton(
                    onClick = { roadData.add(RoadSelection.of(area.id, RoadSelectionKind.MAP_AREA, area.name, System.currentTimeMillis(), roadTiles)) },
                    enabled = online,
                ) { Text(if (download > 0) "Add road data · ${size(context, download)}" else "Add road data") }
            }
            TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = colors.error) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${area.name}?") },
            text = { Text("The map there needs a connection again, or a new download. So do routes and speed limits, unless other road data covers them.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    roadData.remove(area.id)
                    scope.launch { offline.delete(area) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

/** Diagonal below which an area is named after its district rather than its city. */
private const val SMALL_AREA_M = 15_000.0
