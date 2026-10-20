package app.nullpointr.foxnav.drive

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.nullpointr.foxnav.BuildConfig
import app.nullpointr.foxnav.appGraph
import app.nullpointr.foxnav.convoy.CarProfileSheet
import app.nullpointr.foxnav.convoy.label
import app.nullpointr.foxnav.settings.Announcements
import app.nullpointr.foxnav.settings.AUDIO_TYPES
import app.nullpointr.foxnav.settings.LookSettings
import app.nullpointr.foxnav.settings.MapOrientation
import app.nullpointr.foxnav.settings.OverLimitSound
import app.nullpointr.foxnav.settings.SettingsScope
import app.nullpointr.foxnav.settings.OfflineMapsSheet
import app.nullpointr.foxnav.settings.RoadDataSheet
import app.nullpointr.foxnav.settings.ServerSettingsSheet
import app.nullpointr.foxnav.settings.SettingsScreen
import app.nullpointr.foxnav.settings.SpeedometerStyle
import app.nullpointr.foxnav.settings.VoicePackSheet
import app.nullpointr.foxnav.settings.ThemeMode
import app.nullpointr.foxnav.shared.discovery.DiscoveryDocument
import app.nullpointr.foxnav.shared.format.UnitSystem
import app.nullpointr.foxnav.ui.components.Wordmark
import app.nullpointr.foxnav.voice.GUIDANCE_LANGUAGES
import app.nullpointr.foxnav.voice.effectiveLanguage
import app.nullpointr.foxnav.voice.sampleSentence
import app.nullpointr.foxnav.voice.voiceLabel
import app.nullpointr.foxnav.voice.voicesFor
import com.stadiamaps.ferrostar.maplibreui.runtime.NavigationMapState
import org.maplibre.spatialk.geojson.BoundingBox
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow
import app.nullpointr.foxnav.shared.search.SavedPlace
import app.nullpointr.foxnav.shared.search.SavedPlaces

private sealed interface SettingsSheet {
    data object Car : SettingsSheet
    data class OfflineMaps(val visible: BoundingBox?) : SettingsSheet
    data class Roads(val visible: BoundingBox?) : SettingsSheet
    data object Server : SettingsSheet
    data object VoicePacks : SettingsSheet
}

/** The settings page, opened from the drive screen. */
@Composable
fun DriveSettings(document: DiscoveryDocument, viewModel: DriveViewModel, navigationMapState: NavigationMapState, onClose: () -> Unit) {
    val context = LocalContext.current
    val graph = context.appGraph
    val store = graph.settings
    val settings by store.settings.collectAsState()
    val alerts = graph.alertSettings
    val cameraWarnings by alerts.cameraWarnings.collectAsState()
    val reportWarnings by alerts.reportWarnings.collectAsState()
    val profile by graph.profiles.profile.collectAsState()
    val serverUrl by graph.serverSettings.serverUrl.collectAsState()
    var sheet by remember { mutableStateOf<SettingsSheet?>(null) }

    SettingsScreen(onClose) {
        Section("Driving")
        ChoiceRow(
            "Units",
            options = listOf(UnitSystem.METRIC to "km/h and kilometres", UnitSystem.IMPERIAL to "mph and miles"),
            selected = settings.units,
            onSelect = { units -> store.update { it.copy(units = units) } },
        )
        ChoiceRow(
            "Speed warning",
            summary = "How far over the limit before the speedometer turns red.",
            options = listOf(0 to "At the limit", 3 to "+3", 5 to "+5", 10 to "+10"),
            selected = settings.overLimitMargin,
            onSelect = { margin -> store.update { it.copy(overLimitMargin = margin) } },
        )
        OverLimitSoundRow(settings.overLimitSound)
        ChoiceRow(
            "Speedometer",
            options = listOf(SpeedometerStyle.MODERN to "Modern", SpeedometerStyle.SEGMENT to "7-segment"),
            selected = settings.speedometer,
            onSelect = { style -> store.update { it.copy(speedometer = style) } },
        )

        Section("Warnings")
        SwitchRow("Speed camera warnings", "Always off in Germany, Switzerland and France, which forbid them.", cameraWarnings, alerts::setCameraWarnings)
        SwitchRow(
            "Report warnings",
            "Police, accidents and the like ahead, and asking whether they're still there. Reports stay on the map either way.",
            reportWarnings,
            alerts::setReportWarnings,
        )
        ChoiceRow(
            "How warnings sound",
            options = listOf(
                Announcements.FULL to "“Speed camera in 400 metres”",
                Announcements.SHORT to "“Speed camera”",
                Announcements.TONE to "A tone",
                Announcements.OFF to "Silent",
            ),
            selected = settings.announcements,
            onSelect = { level -> store.update { it.copy(announcements = level) } },
        )

        Section("Voice")
        ChoiceRow(
            "Guidance language",
            summary = "For turn instructions. New routes use it.",
            options = listOf("" to "Like the phone") + GUIDANCE_LANGUAGES.map { (tag, name) -> tag to name },
            selected = settings.voiceLanguage,
            onSelect = { language -> store.update { it.copy(voiceLanguage = language, voiceName = "") } },
        )
        val voices = remember(settings.voiceLanguage, graph.voice.isInitializedSuccessfully) { voicesFor(graph.voice.tts, settings.effectiveLanguage()) }
        if (voices.size > 1) {
            ChoiceRow(
                "Voice",
                options = listOf("" to "Default") + voices.map { voice -> voice.name to voiceLabel(voice, voices) },
                selected = settings.voiceName.takeIf { name -> voices.any { it.name == name } } ?: "",
                onSelect = { name -> store.update { it.copy(voiceName = name) } },
            )
        }
        Column {
            Text("Speaking speed", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = settings.speechRate,
                    onValueChange = { rate -> store.update { it.copy(speechRate = (rate * 10).roundToInt() / 10f) } },
                    valueRange = 0.7f..1.5f,
                    steps = 7,
                    modifier = Modifier.weight(1f),
                )
                Text("${(settings.speechRate * 100).roundToInt()} %", Modifier.padding(start = 12.dp))
            }
        }
        OutlinedButton(onClick = { graph.speak(sampleSentence(settings.effectiveLanguage(), settings.units)) }) { Text("Hear it") }
        val packs by graph.voicePacks.packs.collectAsState()
        if (packs.isNotEmpty()) {
            ChoiceRow(
                "Voice pack",
                summary = "Recorded phrases instead of the phone's speech, where the pack has them.",
                options = listOf("" to "Phone's speech") + packs.map { it.id to it.name },
                selected = settings.voicePack.takeIf { id -> packs.any { it.id == id } } ?: "",
                onSelect = { id -> store.update { it.copy(voicePack = id) } },
            )
        }
        LinkRow(
            "Voice packs",
            if (packs.isEmpty()) "Record your own voice, or a friend's, or make one from audio files." else "${packs.size} ${if (packs.size == 1) "voice pack" else "voice packs"}",
            action = if (packs.isEmpty()) "Start" else "Manage",
        ) { sheet = SettingsSheet.VoicePacks }

        Section("Map")
        ChoiceRow(
            "Day and night",
            options = listOf(ThemeMode.SUN to "By sunrise and sunset", ThemeMode.SYSTEM to "Like the phone", ThemeMode.NIGHT to "Always night", ThemeMode.DAY to "Always day"),
            selected = settings.theme,
            onSelect = { mode -> store.update { it.copy(theme = mode) } },
        )
        ChoiceRow(
            "Map direction",
            options = listOf(MapOrientation.HEADING_UP to "Heading up", MapOrientation.NORTH_UP to "North up"),
            selected = settings.orientation,
            onSelect = { orientation -> store.update { it.copy(orientation = orientation) } },
        )
        SwitchRow("3D view while navigating", "The map tilts to show the road ahead.", settings.tilt) { on -> store.update { it.copy(tilt = on) } }
        SwitchRow("Zoom buttons", "Next to the map, besides pinching.", settings.zoomButtons) { on -> store.update { it.copy(zoomButtons = on) } }

        Section("Look")
        LookSettings(settings, graph.palettes, store::update)
        SwitchRow(
            "Japanese accents",
            "Small Japanese labels beside some English ones, like \u5230\u7740 by the arrival time, as in the design. The English is always there.",
            settings.japaneseAccents,
        ) { on -> store.update { it.copy(japaneseAccents = on) } }

        Section("Places")
        SavedPlacesSettings()

        Section("Your car")
        LinkRow(
            profile.name.ifBlank { "No name yet" },
            listOf(listOf(profile.carMake, profile.carModel).filter { it.isNotBlank() }.joinToString(" "), profile.bodyStyle.label)
                .filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "How the convoy sees you" },
            action = "Edit",
        ) { sheet = SettingsSheet.Car }

        Section("Offline maps")
        val areas = graph.offlineMaps.areas
        LinkRow(
            "Offline maps",
            if (areas.isEmpty()) "Download areas to keep the map without signal."
            else "${areas.size} ${if (areas.size == 1) "area" else "areas"} · ${Formatter.formatShortFileSize(context, areas.sumOf { it.bytes })}",
            action = "Manage",
        ) { sheet = SettingsSheet.OfflineMaps(navigationMapState.cameraState.projection?.queryVisibleBoundingBox()) }
        val roads by graph.roadData.state.collectAsState()
        LinkRow(
            "Road data",
            if (roads.selections.isEmpty()) "Routes, guidance, speed limits and search without a connection."
            else "${roads.selections.size} ${if (roads.selections.size == 1) "download" else "downloads"} · ${Formatter.formatShortFileSize(context, roads.bytes)}",
            action = "Manage",
        ) { sheet = SettingsSheet.Roads(navigationMapState.cameraState.projection?.queryVisibleBoundingBox()) }
        val wifiOnly by graph.offlineDownloads.wifiOnly.collectAsState()
        SwitchRow(
            "Download on Wi-Fi only",
            "Map areas and road data wait for Wi-Fi. They carry on with the app closed either way.",
            checked = wifiOnly,
            onChange = graph.offlineDownloads::setWifiOnly,
        )

        Section("Server")
        LinkRow(document.name, serverUrl, action = "Change") { sheet = SettingsSheet.Server }

        Section("Data")
        DataSources(viewModel)

        Section("About")
        Wordmark(detail = "Version ${BuildConfig.VERSION_NAME}")
        Text(
            "Open source: GPL-3.0 for the app, AGPL-3.0 for the server. Map data © OpenStreetMap contributors.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (val open = sheet) {
            SettingsSheet.Car -> CarProfileSheet(onDismiss = { sheet = null })
            is SettingsSheet.OfflineMaps -> OfflineMapsSheet(
                visible = open.visible,
                styleUrls = document.styles.values,
                placeSearch = remember(document.search) { document.search?.takeIf { it.type == "photon" }?.let(graph::placeSearch) },
                onDismiss = { sheet = null },
            )
            is SettingsSheet.Roads -> RoadDataSheet(
                visible = open.visible,
                regions = document.regions,
                placeSearch = remember(document.search) { document.search?.takeIf { it.type == "photon" }?.let(graph::placeSearch) },
                onDismiss = { sheet = null },
            )
            SettingsSheet.Server -> {
                val overrides by graph.serverSettings.overrides.collectAsState()
                ServerSettingsSheet(
                    current = document,
                    serverUrl = serverUrl,
                    overrides = overrides,
                    onSave = { url, newOverrides ->
                        sheet = null
                        onClose()
                        graph.serverSettings.save(url, newOverrides)
                    },
                    onDismiss = { sheet = null },
                )
            }
            SettingsSheet.VoicePacks -> VoicePackSheet(onDismiss = { sheet = null })
            null -> Unit
        }
    }
}

/** The over-limit sound: built in, the user's own file, or none. Choosing one plays it. */
@Composable
private fun SettingsScope.OverLimitSoundRow(selected: OverLimitSound) {
    val graph = LocalContext.current.appGraph
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    fun choose(sound: OverLimitSound) {
        graph.settings.update { it.copy(overLimitSound = sound) }
        graph.chime.playOverLimit(sound)
    }
    val chooseFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            if (graph.chime.setCustomOverLimit(uri)) {
                notice = null
                choose(OverLimitSound.CUSTOM)
            } else {
                notice = "Couldn't read that file as sound."
            }
        }
    }
    ChoiceRow(
        "Over-limit sound",
        summary = "When you go over the limit. Kin-kon is the chime of 80s Japanese cars.",
        options = listOf(
            OverLimitSound.KIN_KON to "Kin-kon",
            OverLimitSound.DING to "Ding",
            OverLimitSound.BEEP to "Beep",
            OverLimitSound.CUSTOM to "Your own",
            OverLimitSound.OFF to "Silent",
        ),
        selected = selected,
        onSelect = { sound ->
            if (sound == OverLimitSound.CUSTOM && !graph.chime.hasCustomOverLimit) chooseFile.launch(AUDIO_TYPES) else choose(sound)
        },
    )
    if (selected == OverLimitSound.CUSTOM) {
        LinkRow("Your own sound", "Any audio file; the first 4 seconds play.", action = "Change") { chooseFile.launch(AUDIO_TYPES) }
    }
    notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

/** Home, work and favourites to remove, and the recent destinations to forget. All on this phone only. */
@Composable
private fun SettingsScope.SavedPlacesSettings() {
    val store = LocalContext.current.appGraph.places
    val places by store.places.collectAsState()
    val colors = MaterialTheme.colorScheme
    val saved = SavedPlaces.shortcuts(places)
    if (saved.isEmpty()) {
        Note("Save a place with the star in its route preview: home and work show under the search with their travel time, favourites beside them.")
    }
    saved.forEach { place ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(place.kind.icon, contentDescription = null, tint = colors.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(place.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val detail = listOf(place.place.title.takeIf { place.kind == SavedPlace.Kind.HOME || place.kind == SavedPlace.Kind.WORK }, place.place.detail).filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
                if (detail.isNotEmpty()) Text(detail, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { store.remove(place) }) { Text("Remove", color = colors.error) }
        }
    }
    val recent = SavedPlaces.recent(places).size
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Recent destinations", style = MaterialTheme.typography.titleMedium)
            Text(if (recent == 0) "None" else "The last $recent, offered in the search", color = colors.onSurfaceVariant)
        }
        OutlinedButton(onClick = store::forgetRecent, enabled = recent > 0) { Text("Forget") }
    }
}

/** Credits for the data behind limits, cameras and reports, as their licences ask. */
@Composable
private fun DataSources(viewModel: DriveViewModel) {
    val packCredits by LocalContext.current.appGraph.dataPacks.attributions.collectAsState()
    val reports by viewModel.reports.collectAsState()
    val credits = remember(packCredits, reports) {
        // The map line below credits OpenStreetMap already.
        val fromPacks = packCredits.flatMap { it.split("; ") }.filterNot { "OpenStreetMap contributors" in it }.map { it.replaceFirstChar(Char::uppercase) }
        val fromReports = reports.mapNotNull { it.source }.map { source -> "Reports from ${source.name}" + (source.licence?.let { ", $it" } ?: "") }
        (listOf("Map © OpenStreetMap contributors, ODbL; tiles from Protomaps") + fromPacks + fromReports).distinct()
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        credits.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

