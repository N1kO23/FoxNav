package app.nullpointr.foxnav.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.nullpointr.foxnav.appGraph
import app.nullpointr.foxnav.data.ACCESS_LOCAL_NETWORK
import app.nullpointr.foxnav.data.fetchDiscovery
import app.nullpointr.foxnav.data.isOnLocalNetwork
import app.nullpointr.foxnav.data.localNetworkPermissionRequired
import app.nullpointr.foxnav.settings.ServerSettings
import app.nullpointr.foxnav.settings.ServerSettingsSheet
import app.nullpointr.foxnav.shared.discovery.DiscoveryDocument

private sealed interface GateState {
    data object NoServer : GateState
    data object Loading : GateState
    data object NeedsLocalNetwork : GateState
    data class Failed(val message: String) : GateState
    /** [saved]: the document from the last time, because the server can't be reached now. */
    data class Ready(val document: DiscoveryDocument, val saved: Boolean = false) : GateState
}

/**
 * Connects to the server chosen in [settings] (asking for the local network permission when
 * needed) and shows [content] with its discovery document, with the user's overrides applied.
 * Without a connection it uses the document from last time, so downloaded maps still work, and
 * fetches a fresh one when the connection is back. Whenever the app isn't connected, the user
 * can choose a different server here.
 */
@Composable
fun ServerGate(settings: ServerSettings, content: @Composable (DiscoveryDocument) -> Unit) {
    val context = LocalContext.current
    val cache = context.appGraph.discoveryCache
    val online by context.appGraph.connectivity.online.collectAsState()
    val serverUrl by settings.serverUrl.collectAsState()
    val overrides by settings.overrides.collectAsState()
    var state by remember { mutableStateOf<GateState>(GateState.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    var changingServer by rememberSaveable { mutableStateOf(false) }

    val requestLocalNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            attempt++
        } else {
            state = GateState.Failed("Without the Nearby devices permission, FoxNav can't reach a server on your local network.")
        }
    }

    LaunchedEffect(serverUrl, overrides, attempt) {
        if (serverUrl.isBlank()) {
            state = GateState.NoServer
            return@LaunchedEffect
        }
        // Refreshing behind a working screen keeps it up; only a first start shows the spinner.
        if (state !is GateState.Ready) state = GateState.Loading
        val blocked = localNetworkPermissionRequired &&
            ContextCompat.checkSelfPermission(context, ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED &&
            isOnLocalNetwork(serverUrl)
        state = when {
            blocked -> GateState.NeedsLocalNetwork
            else -> runCatching { fetchDiscovery(serverUrl) }.fold(
                onSuccess = { fetched ->
                    cache.save(serverUrl, fetched)
                    val document = fetched.withOverrides(overrides.routing, overrides.search, overrides.style)
                    if (document.styles.isEmpty()) GateState.Failed("${document.name} publishes no map styles") else GateState.Ready(document)
                },
                onFailure = { error ->
                    cache.load(serverUrl)?.takeIf { it.styles.isNotEmpty() }
                        ?.let { GateState.Ready(it.withOverrides(overrides.routing, overrides.search, overrides.style), saved = true) }
                        ?: GateState.Failed("Couldn't load $serverUrl: ${error.message}")
                },
            )
        }
    }

    // Started offline: catch up once the connection is back.
    LaunchedEffect(online) {
        if (online && (state as? GateState.Ready)?.saved == true) attempt++
    }

    when (val current = state) {
        is GateState.Ready -> content(current.document)
        GateState.Loading -> Centered { CircularProgressIndicator() }
        GateState.NoServer -> ServerSetup(onConnect = { url -> settings.save(url, overrides) })
        GateState.NeedsLocalNetwork -> Centered {
            Text(
                "$serverUrl is on your local network. Android asks for the Nearby devices permission before apps can reach it.",
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Button(onClick = { requestLocalNetwork.launch(ACCESS_LOCAL_NETWORK) }) { Text("Allow") }
            OutlinedButton(onClick = { changingServer = true }) { Text("Change server") }
        }
        is GateState.Failed -> Centered {
            Text(current.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            Button(onClick = { attempt++ }) { Text("Try again") }
            OutlinedButton(onClick = { changingServer = true }) { Text("Change server") }
        }
    }

    if (changingServer) {
        ServerSettingsSheet(
            current = (state as? GateState.Ready)?.document,
            serverUrl = serverUrl,
            overrides = overrides,
            onSave = { url, newOverrides ->
                changingServer = false
                settings.save(url, newOverrides)
                attempt++
            },
            onDismiss = { changingServer = false },
        )
    }
}

/** First run (or no server saved): ask for a server address. */
@Composable
private fun ServerSetup(onConnect: (String) -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }
    val connect = { if (address.isNotBlank()) onConnect(address) }

    Centered {
        Text("Connect to a FoxNav server", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        Text(
            "Type the server's address: a domain like maps.example.org, or your own computer on the same Wi-Fi, like http://192.168.1.10:8080.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("Server address") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { connect() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = connect, enabled = address.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Connect") }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().imePadding().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}
