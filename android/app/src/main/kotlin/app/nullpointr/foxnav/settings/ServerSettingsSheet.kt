package app.nullpointr.foxnav.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.nullpointr.foxnav.shared.discovery.DiscoveryDocument

/** Choose the FoxNav server, and optionally replace single services with your own. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSettingsSheet(
    current: DiscoveryDocument?,
    serverUrl: String,
    overrides: EndpointOverrides,
    onSave: (String, EndpointOverrides) -> Unit,
    onDismiss: () -> Unit,
) {
    var server by remember { mutableStateOf(serverUrl) }
    var routing by remember { mutableStateOf(overrides.routing) }
    var search by remember { mutableStateOf(overrides.search) }
    var style by remember { mutableStateOf(overrides.style) }
    var showAdvanced by remember { mutableStateOf(overrides != EndpointOverrides()) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Server", style = MaterialTheme.typography.titleLarge)
            if (current != null) {
                Text(
                    "Connected to ${current.name}" + if (current.regions.isEmpty()) "" else " (maps for ${current.regions.joinToString()})",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text("Server address") },
                placeholder = { Text("maps.example.org") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            if (showAdvanced) {
                Text("Use your own service instead of the server's (leave empty to keep it):", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(routing, { routing = it }, label = { Text("Routing (Valhalla URL)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(search, { search = it }, label = { Text("Search (Photon URL)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(style, { style = it }, label = { Text("Map style (MapLibre style URL)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            } else {
                TextButton(onClick = { showAdvanced = true }) { Text("Override single services") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = { onSave(server, EndpointOverrides(routing, search, style)) },
                    enabled = server.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
            }
        }
    }
}
