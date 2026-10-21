package app.nullpointr.foxnav.settings

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nullpointr.foxnav.appGraph
import app.nullpointr.foxnav.data.PlaceSearch
import app.nullpointr.foxnav.data.RoadData
import app.nullpointr.foxnav.shared.geo.GeoPoint
import app.nullpointr.foxnav.shared.routing.Coverage
import app.nullpointr.foxnav.shared.routing.RoadSelection
import app.nullpointr.foxnav.shared.routing.RoadSelectionKind
import app.nullpointr.foxnav.shared.routing.RoadSelections
import app.nullpointr.foxnav.shared.routing.RoutingTile
import org.maplibre.spatialk.geojson.BoundingBox
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Road data for routes, guidance and speed limits without a connection (ADR 0005): all of the
 * server's regions, their main roads, or the area on screen, next to what map areas and trips
 * brought. [visible] is what the map shows now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoadDataSheet(visible: BoundingBox?, regions: List<String>, placeSearch: PlaceSearch?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val graph = context.appGraph
    val state by graph.roadData.state.collectAsState()
    val online by graph.connectivity.online.collectAsState()
    val colors = MaterialTheme.colorScheme
    val manifest = state.manifest?.takeIf { it.isUsable }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Road data", style = MaterialTheme.typography.titleLarge)
            Text(
                "Routes, guidance, speed limits and search without a connection, worked out on your phone from the server's road network. " +
                    "Offline map areas and trips made available offline bring their own; here you can add more.",
                color = colors.onSurfaceVariant,
            )
            when {
                manifest != null -> {
                    WholeRegions(regions, state, online, graph.roadData)
                    if (visible != null) AreaOnScreen(visible, state, online, placeSearch, graph.roadData)
                }
                online -> Text("This server has no road data to download.", color = colors.onSurfaceVariant)
                else -> Text("Connect to the internet to see the server's road data.", color = colors.onSurfaceVariant)
            }
            val selections = state.selections.sortedByDescending { it.createdMs }
            if (selections.isNotEmpty()) {
                HorizontalDivider(color = colors.outline)
                Text("Downloaded", style = MaterialTheme.typography.titleMedium)
                selections.forEach { SelectionRow(it, state, online, graph.roadData) }
                Text(summary(context, state), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            state.problem?.let { Text("Download stopped: $it. It carries on by itself.", color = colors.error) }
        }
    }
}

@Composable
private fun WholeRegions(regions: List<String>, state: RoadData.State, online: Boolean, roadData: RoadData) {
    val manifest = state.manifest ?: return
    val names = regions.map { Locale.Builder().setRegion(it).build().displayCountry }.ifEmpty { listOf("the server's regions") }
    Card {
        Text("All of ${names.joinToString(", ")}", style = MaterialTheme.typography.titleMedium)
        listOf(
            Triple(RoadSelectionKind.ALL, "All roads", "Everything, as map areas bring it."),
            // Valhalla finds where a route starts and ends through the local roads' tiles: main roads alone route nowhere.
            Triple(RoadSelectionKind.MAIN_ROADS, "Main roads, to join up the rest", "Routes between your map areas and trips along motorways and main roads, without the local roads in between. Routes still start and end only where you have other road data."),
        ).forEach { (kind, title, detail) ->
            val id = kind.name.lowercase()
            val tiles = remember(manifest) { RoadSelection(id, kind, title, 0L).tilesIn(manifest) }
            ServerWideOption(title, detail, state.selection(id) != null, state.coverage(tiles), online) {
                roadData.add(RoadSelection(id, kind, "$title, ${regions.joinToString(", ")}".trimEnd(',', ' '), System.currentTimeMillis()))
            }
        }
    }
}

@Composable
private fun ServerWideOption(title: String, detail: String, chosen: Boolean, coverage: Coverage?, online: Boolean, onDownload: () -> Unit) {
    val context = LocalContext.current
    val waitingForWifi by context.appGraph.offlineDownloads.waitingForWifi.collectAsState()
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(detail, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        when {
            chosen && coverage?.complete != true -> Text(if (waitingForWifi) "Waiting for Wi-Fi, see below" else "Downloading, see below", color = colors.secondary)
            coverage == null -> Unit
            coverage.complete -> Text("On the phone", color = colors.secondary)
            else -> OutlinedButton(onClick = onDownload, enabled = online) { Text("Download · ${size(context, coverage.missingDownload)}") }
        }
    }
}

@Composable
private fun AreaOnScreen(visible: BoundingBox, state: RoadData.State, online: Boolean, placeSearch: PlaceSearch?, roadData: RoadData) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val tiles = remember(visible, state.manifest) { state.tilesIn(visible) }
    val coverage = state.coverage(tiles)
    var name by remember(visible) { mutableStateOf("") }
    var started by remember(visible) { mutableStateOf(false) }
    LaunchedEffect(visible) {
        val center = GeoPoint((visible.south + visible.north) / 2, (visible.west + visible.east) / 2)
        if (name.isEmpty()) name = runCatching { placeSearch?.areaName(center, small = false) }.getOrNull().orEmpty()
    }
    Card {
        Text("The area on screen", style = MaterialTheme.typography.titleMedium)
        Text(
            when {
                tiles.isEmpty() || coverage == null -> "The server has no roads here."
                coverage.complete -> "Already on the phone."
                else -> "About ${size(context, coverage.missingDownload)} to download, ${size(context, coverage.bytes)} on the phone."
            },
            color = colors.onSurfaceVariant,
        )
        if (coverage != null && !coverage.complete) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    started = true
                    roadData.add(RoadSelection.of(UUID.randomUUID().toString(), RoadSelectionKind.AREA, name.trim().ifEmpty { "Area" }, System.currentTimeMillis(), tiles))
                },
                enabled = online && !started,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text(if (started) "Downloading..." else "Download") }
            val waitingForWifi by context.appGraph.offlineDownloads.waitingForWifi.collectAsState()
            when {
                !online -> Text("Connect to the internet to download.", color = colors.onSurfaceVariant)
                waitingForWifi -> Text("Downloads when you're on Wi-Fi.", color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SelectionRow(selection: RoadSelection, state: RoadData.State, online: Boolean, roadData: RoadData) {
    val context = LocalContext.current
    val waitingForWifi by context.appGraph.offlineDownloads.waitingForWifi.collectAsState()
    val colors = MaterialTheme.colorScheme
    var confirmDelete by remember { mutableStateOf(false) }
    val coverage = state.coverage(selection)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(selection.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(kindLabel(selection), coverage?.let { status(context, it, state.downloading, online, waitingForWifi) }).joinToString(" · "),
            color = colors.onSurfaceVariant,
        )
        if (coverage != null && !coverage.complete) LinearProgressIndicator(progress = { coverage.fraction }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { confirmDelete = true }) {
            Text(if (selection.kind == RoadSelectionKind.MAP_AREA) "Remove road data" else "Delete", color = colors.error)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${selection.name}?") },
            text = {
                Text(
                    if (selection.kind == RoadSelectionKind.MAP_AREA) "The map area stays; routes and speed limits there need a connection again."
                    else "Routes and speed limits there need a connection again, unless other road data covers them.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    roadData.remove(selection.id)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = colors.surfaceVariant, border = BorderStroke(1.dp, colors.outline)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

private fun kindLabel(selection: RoadSelection): String = when (selection.kind) {
    RoadSelectionKind.MAP_AREA -> "With its map area"
    RoadSelectionKind.TRIP -> "Trip, kept until ${date(selection.createdMs + RoadSelections.TRIP_DAYS * 24 * 60 * 60 * 1000)}"
    RoadSelectionKind.AREA -> "Area"
    RoadSelectionKind.ALL -> "All roads"
    RoadSelectionKind.MAIN_ROADS -> "Main roads"
}

/** Where some road data stands: ready, downloading, or waiting (for a connection, or for Wi-Fi). */
internal fun status(context: Context, coverage: Coverage, downloading: Boolean, online: Boolean, waitingForWifi: Boolean = false): String = when {
    coverage.complete -> "Ready · ${size(context, coverage.bytes)}"
    waitingForWifi -> "Waiting for Wi-Fi · ${(coverage.fraction * 100).toInt()} %"
    downloading -> "Downloading · ${(coverage.fraction * 100).toInt()} %"
    !online -> "Waiting for a connection · ${(coverage.fraction * 100).toInt()} %"
    else -> "Waiting · ${(coverage.fraction * 100).toInt()} %"
}

private fun summary(context: Context, state: RoadData.State): String = listOfNotNull(
    "${size(context, state.bytes)} on the phone",
    state.manifest?.generated?.let { runCatching { "road network of ${date(Instant.parse(it).toEpochMilli())}" }.getOrNull() },
    state.update?.let { "downloading newer road data, ${(it.fraction * 100).toInt()} %" },
).joinToString(" · ")

/** The tiles of every level under [bounds]; none without a usable manifest. */
internal fun RoadData.State.tilesIn(bounds: BoundingBox): Set<RoutingTile> =
    manifest?.takeIf { it.isUsable }?.tilesIn(GeoPoint(bounds.south, bounds.west), GeoPoint(bounds.north, bounds.east)).orEmpty()

internal fun size(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

private fun date(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
