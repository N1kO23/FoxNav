package app.nullpointr.foxnav.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.nullpointr.foxnav.appGraph
import app.nullpointr.foxnav.shared.voice.Phrases
import app.nullpointr.foxnav.ui.icons.FoxIcons
import app.nullpointr.foxnav.voice.ClipRecorder
import app.nullpointr.foxnav.voice.VoicePack
import kotlinx.coroutines.launch

/** Voice packs: record your own (or a friend's, with their permission), or make one from audio files; import, export, choose, delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePackSheet(onDismiss: () -> Unit) {
    val graph = LocalContext.current.appGraph
    val packs by graph.voicePacks.packs.collectAsState()
    var editing by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val pack = editing?.let { id -> packs.firstOrNull { it.id == id } }
            if (pack != null) {
                PackEditor(pack, onBack = { editing = null })
            } else {
                PackList(packs, onEdit = { editing = it.id })
            }
        }
    }
}

@Composable
private fun PackList(packs: List<VoicePack>, onEdit: (VoicePack) -> Unit) {
    val graph = LocalContext.current.appGraph
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<VoicePack?>(null) }
    var importing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val importPack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        notice = null
        scope.launch {
            val result = graph.voicePacks.importPack(uri)
            importing = false
            notice = when {
                result.pack != null -> "Imported “${result.pack.name}”: ${result.phrases} of ${Phrases.ALL.size} phrases." +
                    if (result.unreadable > 0) " ${result.unreadable} ${if (result.unreadable == 1) "clip" else "clips"} couldn't be read." else ""
                result.unreadable > 0 -> "None of the clips in that file could be read as sound."
                else -> "No phrases in that file. A voice pack is a zip of clips named after their phrases, like turn_left.mp3."
            }
        }
    }

    Text("Voice packs", style = MaterialTheme.typography.titleLarge)
    Text(
        "Guidance and warnings in a recorded voice: yours, or a friend's who agrees to it. FoxNav joins the phrases, like “In 300 metres” and “turn left”. Street names, and anything not recorded, still use the phone's speech.",
        color = colors.onSurfaceVariant,
    )
    packs.forEach { pack ->
        Surface(shape = RoundedCornerShape(18.dp), color = colors.surfaceVariant, border = BorderStroke(1.dp, colors.outline)) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(pack.name, fontWeight = FontWeight.SemiBold)
                    Text("${pack.recorded} of ${Phrases.ALL.size} phrases", color = colors.onSurfaceVariant)
                }
                OutlinedButton(onClick = { onEdit(pack) }) { Text("Edit") }
                TextButton(onClick = { confirmDelete = pack }) { Text("Delete", color = colors.error) }
            }
        }
    }
    notice?.let { Text(it, color = colors.onSurfaceVariant) }
    HorizontalDivider(color = colors.outline)
    Text("New voice pack", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Name, like “My voice”") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Button(
        onClick = {
            val pack = graph.voicePacks.create(name.trim())
            name = ""
            onEdit(pack)
        },
        enabled = name.isNotBlank(),
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) { Text("Create") }
    OutlinedButton(
        onClick = { importPack.launch(ZIP_TYPES) },
        enabled = !importing,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) { Text(if (importing) "Importing..." else "Import a voice pack (zip)") }

    confirmDelete?.let { pack ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${pack.name}?") },
            text = { Text("Its recordings go too.") },
            confirmButton = {
                TextButton(onClick = {
                    if (graph.settings.settings.value.voicePack == pack.id) graph.settings.update { it.copy(voicePack = "") }
                    graph.voicePacks.delete(pack)
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun PackEditor(pack: VoicePack, onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.appGraph
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var recording by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var permitted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }
    // Recompose after each recording or import: the files changed.
    val packs by graph.voicePacks.packs.collectAsState()
    val current = packs.firstOrNull { it.id == pack.id } ?: pack

    var choosingFor by remember { mutableStateOf<String?>(null) }
    val chooseFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val key = choosingFor ?: return@rememberLauncherForActivityResult
        choosingFor = null
        if (uri == null) return@rememberLauncherForActivityResult
        notice = null
        scope.launch {
            if (graph.voicePacks.importClip(current, key, uri)) {
                current.clip(key)?.let { graph.clipPlayer.play(listOf(it)) }
            } else {
                notice = "Couldn't read that file as sound."
            }
        }
    }
    val addFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        notice = "Reading ${uris.size} ${if (uris.size == 1) "file" else "files"}..."
        scope.launch {
            val count = graph.voicePacks.importFiles(current, uris)
            notice = if (count == 0) {
                "None of those files is named after a phrase. Name them like the grey labels below: turn_left.mp3, in_300_m.ogg."
            } else {
                "Added $count ${if (count == 1) "phrase" else "phrases"}."
            }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch { notice = if (graph.voicePacks.exportPack(current, uri)) "Saved. Share the zip; FoxNav imports it on another phone." else "Couldn't save the voice pack there." }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(FoxIcons.Back, contentDescription = "Back to voice packs") }
        Column(Modifier.weight(1f)) {
            Text(current.name, style = MaterialTheme.typography.titleLarge)
            Text("${current.recorded} of ${Phrases.ALL.size} phrases", color = colors.onSurfaceVariant)
        }
    }
    Text(
        "Record each phrase, or choose an audio file for it: MP3, M4A, Ogg, FLAC or WAV, up to 10 seconds. Speak as you would to a driver: calm and clear. FoxNav trims the silence and evens out the volume.",
        color = colors.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { addFiles.launch(AUDIO_TYPES) }, modifier = Modifier.weight(1f)) { Text("Add files") }
        OutlinedButton(onClick = { export.launch("${current.name}.zip") }, enabled = current.recorded > 0, modifier = Modifier.weight(1f)) { Text("Export") }
    }
    if (!permitted) {
        Button(onClick = { askPermission.launch(Manifest.permission.RECORD_AUDIO) }, modifier = Modifier.fillMaxWidth()) { Text("Allow the microphone, to record") }
    }
    OutlinedButton(
        onClick = { graph.clipPlayer.play(listOf("in_300_m", "turn_left").mapNotNull { current.clip(it) }) },
        enabled = current.has(listOf("in_300_m", "turn_left")),
    ) { Text("Try “In 300 metres, turn left”") }

    notice?.let { Text(it, color = colors.onSurfaceVariant) }

    Phrases.Group.entries.forEach { group ->
        Text(group.title.uppercase(), style = MaterialTheme.typography.labelLarge, color = colors.secondary)
        Phrases.ALL.filter { it.group == group }.forEach { phrase ->
            val recorded = current.clip(phrase.key) != null
            val isRecording = recording == phrase.key
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (recorded) "✓" else "•", color = if (recorded) colors.secondary else colors.onSurfaceVariant, modifier = Modifier.size(width = 16.dp, height = 20.dp))
                Column(Modifier.weight(1f)) {
                    Text(phrase.prompt, fontWeight = if (isRecording) FontWeight.Bold else FontWeight.Normal)
                    // The file name that fills this phrase, for packs made from files.
                    Text(phrase.key, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.onSurfaceVariant)
                }
                if (recorded && !isRecording) {
                    IconButton(onClick = { current.clip(phrase.key)?.let { graph.clipPlayer.play(listOf(it)) } }) {
                        Icon(FoxIcons.Play, contentDescription = "Play “${phrase.prompt}”")
                    }
                }
                IconButton(
                    onClick = {
                        choosingFor = phrase.key
                        chooseFile.launch(AUDIO_TYPES)
                    },
                    enabled = recording == null,
                ) { Icon(FoxIcons.Folder, contentDescription = "Choose a file for “${phrase.prompt}”") }
                FilledIconButton(
                    onClick = {
                        if (isRecording) {
                            recording = null
                        } else {
                            recording = phrase.key
                            notice = null
                            scope.launch {
                                val heard = ClipRecorder.record(graph.voicePacks.clipTarget(current, phrase.key)) { recording == phrase.key }
                                if (recording == phrase.key) recording = null
                                if (!heard) notice = "Didn't hear “${phrase.prompt}”. Try again a little closer to the microphone."
                                graph.voicePacks.refresh()
                            }
                        }
                    },
                    enabled = permitted && (recording == null || isRecording),
                    colors = if (isRecording) IconButtonDefaults.filledIconButtonColors(containerColor = colors.error) else IconButtonDefaults.filledIconButtonColors(),
                ) {
                    Icon(
                        if (isRecording) FoxIcons.Stop else FoxIcons.Mic,
                        contentDescription = if (isRecording) "Stop recording" else "Record “${phrase.prompt}”",
                    )
                }
            }
        }
    }
}

/** Audio as file pickers label it; some label Ogg and Opus files as application/ogg. */
internal val AUDIO_TYPES = arrayOf("audio/*", "application/ogg")
private val ZIP_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
