package app.nullpointr.foxnav.convoy

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.nullpointr.foxnav.appGraph
import app.nullpointr.foxnav.data.ACCESS_LOCAL_NETWORK
import app.nullpointr.foxnav.data.isOnLocalNetwork
import app.nullpointr.foxnav.data.localNetworkPermissionRequired
import app.nullpointr.foxnav.shared.convoy.Invite
import kotlinx.coroutines.launch

/** Starting or joining a convoy from the UI: progress, errors, and the local network permission. */
class ConvoyStarter internal constructor(
    val busy: Boolean,
    val error: String?,
    /** Joins with [invite], asking for the local network permission first if its server needs it. */
    val join: (Invite) -> Unit,
    /** Starts a convoy on the app's own server. */
    val create: (serverUrl: String) -> Unit,
)

@Composable
fun rememberConvoyStarter(onStarted: () -> Unit = {}): ConvoyStarter {
    val context = LocalContext.current
    val convoy = context.appGraph.convoy
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var waitingForPermission by remember { mutableStateOf<Invite?>(null) }

    fun run(action: suspend () -> Result<Unit>) {
        busy = true
        error = null
        scope.launch {
            action().onSuccess { onStarted() }.onFailure { error = it.message ?: "Couldn't reach the server" }
            busy = false
        }
    }

    val requestLocalNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val invite = waitingForPermission ?: return@rememberLauncherForActivityResult
        waitingForPermission = null
        if (granted) {
            run { convoy.join(invite) }
        } else {
            error = "Without the Nearby devices permission, FoxNav can't reach a convoy server on your local network."
        }
    }

    return ConvoyStarter(
        busy = busy,
        error = error,
        join = { invite ->
            busy = true
            error = null
            scope.launch {
                val blocked = localNetworkPermissionRequired &&
                    ContextCompat.checkSelfPermission(context, ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED &&
                    isOnLocalNetwork(invite.server)
                if (blocked) {
                    busy = false
                    waitingForPermission = invite
                    requestLocalNetwork.launch(ACCESS_LOCAL_NETWORK)
                } else {
                    run { convoy.join(invite) }
                }
            }
        },
        create = { serverUrl -> run { convoy.create(serverUrl) } },
    )
}

/** Asks before joining a convoy from an invite link the app was opened with. */
@Composable
fun JoinInviteDialog(invite: Invite?, onDone: () -> Unit) {
    val context = LocalContext.current
    val graph = context.appGraph
    val profile by graph.profiles.profile.collectAsState()
    var name by remember { mutableStateOf(profile.name) }
    val starter = rememberConvoyStarter(onStarted = onDone)

    if (invite == null) {
        AlertDialog(
            onDismissRequest = onDone,
            title = { Text("Broken invite") },
            text = { Text("This convoy invite link is incomplete or from a newer version of FoxNav.") },
            confirmButton = { TextButton(onClick = onDone) { Text("OK") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (!starter.busy) onDone() },
        title = { Text("Join the convoy?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("It runs on ${invite.server}. Everyone in it sees where you are and how fast you're going until you leave.")
                ProfileNameField(name, onChange = { name = it })
                starter.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && !starter.busy,
                onClick = {
                    graph.profiles.save(profile.copy(name = name))
                    // With no server chosen yet, use the convoy's for maps too.
                    if (graph.serverSettings.serverUrl.value.isBlank()) {
                        graph.serverSettings.save(invite.server, graph.serverSettings.overrides.value)
                    }
                    starter.join(invite)
                },
            ) {
                if (starter.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Join")
            }
        },
        dismissButton = { TextButton(onClick = onDone, enabled = !starter.busy) { Text("Not now") } },
    )
}
