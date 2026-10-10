package app.nullpointr.foxnav.map

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.nullpointr.foxnav.data.ACCESS_LOCAL_NETWORK
import app.nullpointr.foxnav.data.fetchDiscovery
import app.nullpointr.foxnav.data.isOnLocalNetwork
import app.nullpointr.foxnav.data.localNetworkPermissionRequired
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** Full-screen map using the style the server publishes for the current theme. */
@Composable
fun MapScreen(serverUrl: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Follows the system theme for now; navigation will switch at sunrise/sunset and in tunnels.
    val theme = if (isSystemInDarkTheme()) "night" else "day"
    var styleUrl by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var needsLocalNetwork by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }

    val requestLocalNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        needsLocalNetwork = false
        if (granted) {
            attempt++
        } else {
            error = "Without the Nearby devices permission, FoxNav can't reach a server on your local network."
        }
    }

    LaunchedEffect(serverUrl, theme, attempt) {
        error = null
        if (serverUrl.isBlank()) {
            error = "No server configured"
            return@LaunchedEffect
        }
        val blockedByLocalNetworkProtection = localNetworkPermissionRequired &&
            ContextCompat.checkSelfPermission(context, ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED &&
            isOnLocalNetwork(serverUrl)
        if (blockedByLocalNetworkProtection) {
            needsLocalNetwork = true
            return@LaunchedEffect
        }
        runCatching { fetchDiscovery(serverUrl) }
            .onSuccess { document ->
                styleUrl = document.styles[theme] ?: document.styles.values.firstOrNull()
                if (styleUrl == null) error = "${document.name} publishes no map styles"
            }
            .onFailure { error = "Couldn't load $serverUrl: ${it.message}" }
    }

    Box(modifier.fillMaxSize()) {
        styleUrl?.let { MapLibreMap(styleUrl = it, modifier = Modifier.fillMaxSize()) }
        when {
            needsLocalNetwork -> LocalNetworkPrompt(
                serverUrl = serverUrl,
                onAllow = { requestLocalNetwork.launch(ACCESS_LOCAL_NETWORK) },
                modifier = Modifier.align(Alignment.Center),
            )
            error != null -> Text(
                text = error.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }
    }
}

/** Explains the permission before Android asks for it. */
@Composable
private fun LocalNetworkPrompt(serverUrl: String, onAllow: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "$serverUrl is on your local network. Android asks for the Nearby devices permission before apps can reach it.",
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onAllow) { Text("Allow") }
    }
}

@Composable
private fun MapLibreMap(styleUrl: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply {
            onCreate(null)
            getMapAsync { map ->
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(60.1699, 24.9384)) // Helsinki
                    .zoom(11.0)
                    .build()
            }
        }
    }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView, styleUrl) {
        mapView.getMapAsync { map -> map.setStyle(Style.Builder().fromUri(styleUrl)) }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}
