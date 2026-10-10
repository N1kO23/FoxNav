package app.nullpointr.foxnav.drive

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.nullpointr.foxnav.shared.discovery.DiscoveryDocument
import app.nullpointr.foxnav.shared.search.Place
import com.stadiamaps.ferrostar.composeui.config.NavigationViewComponentBuilder
import com.stadiamaps.ferrostar.composeui.config.VisualNavigationViewConfig
import com.stadiamaps.ferrostar.composeui.config.withCustomOverlayView
import com.stadiamaps.ferrostar.composeui.config.withInstructionsView
import com.stadiamaps.ferrostar.composeui.config.withProgressView
import com.stadiamaps.ferrostar.composeui.config.withRoadNameView
import com.stadiamaps.ferrostar.composeui.runtime.KeepScreenOnDisposableEffect
import com.stadiamaps.ferrostar.core.boundingBox
import com.stadiamaps.ferrostar.maplibreui.NavigationMapClickResult
import com.stadiamaps.ferrostar.maplibreui.NavigationMapPuckStyle
import com.stadiamaps.ferrostar.maplibreui.routeline.BorderedPolyline
import com.stadiamaps.ferrostar.maplibreui.routeline.RouteOverlayBuilder
import com.stadiamaps.ferrostar.maplibreui.runtime.navigationCameraOptions
import com.stadiamaps.ferrostar.maplibreui.runtime.rememberNavigationMapState
import com.stadiamaps.ferrostar.maplibreui.views.DynamicallyOrientingNavigationView
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.style.BaseStyle

/** The main screen: the map in free drive, search, route preview and turn-by-turn. */
@Composable
fun DriveScreen(viewModel: DriveViewModel, document: DiscoveryDocument, styleUrl: String) {
    KeepScreenOnDisposableEffect()
    RequestLocationPermission(onResult = viewModel::onLocationPermission)

    val colors = MaterialTheme.colorScheme
    val navigationMapState = rememberNavigationMapState()
    val preview by viewModel.preview.collectAsState()

    // Frame the selected route while previewing.
    LaunchedEffect(preview?.selectedRoute) {
        preview?.selectedRoute?.geometry?.boundingBox()?.let { bounds ->
            navigationMapState.showRouteOverview(bounds, PaddingValues(start = 48.dp, end = 48.dp, top = 120.dp, bottom = 360.dp))
        }
    }

    DynamicallyOrientingNavigationView(
        modifier = Modifier.fillMaxSize(),
        baseStyle = BaseStyle.Uri(styleUrl),
        navigationMapState = navigationMapState,
        // A little further out than Ferrostar's default while browsing, so free drive shows the road ahead.
        navigationCameraOptions = navigationCameraOptions().copy(browsingZoom = 15.0),
        viewModel = viewModel,
        locationPuckStyle = NavigationMapPuckStyle(
            dotFillColorCurrentLocation = colors.primary,
            dotFillColorOldLocation = colors.primary.copy(alpha = 0.6f),
            dotStrokeColor = Color.White,
            accuracyStrokeColor = colors.primary,
            accuracyFillColor = colors.primary.copy(alpha = 0.12f),
            bearingColor = colors.primary,
        ),
        // Speed, limit and mute live in our panels.
        config = VisualNavigationViewConfig(showMute = false, showZoom = false, showRecenter = true),
        views = NavigationViewComponentBuilder.Default()
            .withInstructionsView { modifier, uiState -> ManeuverCard(uiState, modifier) }
            .withProgressView { modifier, uiState, onTapExit ->
                val speed by viewModel.speed.collectAsState()
                DrivePanel(uiState, speed, onToggleMute = viewModel::toggleMute, onEnd = onTapExit, modifier = modifier)
            }
            .withRoadNameView { modifier, roadName, _ -> RoadNamePill(roadName, modifier) }
            .withCustomOverlayView { modifier -> FreeDriveOverlay(modifier, viewModel, document, navigationMapState) },
        routeOverlayBuilder = RouteOverlayBuilder(
            navigationPath = { uiState ->
                uiState.routeGeometry?.let { geometry ->
                    BorderedPolyline(points = geometry, color = colors.secondary, borderColor = colors.background, lineWidth = 9f, borderWidth = 3f)
                }
            },
        ),
        onTapExit = { viewModel.stopNavigation() },
        onMapLongClick = { position, _ ->
            viewModel.preview(Place(title = "Dropped pin", detail = "", latitude = position.lat, longitude = position.lng))
            NavigationMapClickResult.Consume
        },
        mapOptions = MapOptions(ornamentOptions = OrnamentOptions(isCompassEnabled = false, isScaleBarEnabled = false)),
    ) { uiState ->
        if (!uiState.isNavigating()) RoutePreviewLayers(preview)
    }
}

/** Asks for location (and, for the navigation notification, notification) permission once. */
@Composable
private fun RequestLocationPermission(onResult: (Boolean) -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        onResult(results[Manifest.permission.ACCESS_FINE_LOCATION] == true)
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            onResult(true)
        } else {
            val permissions = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            launcher.launch(permissions.toTypedArray())
        }
    }
}
