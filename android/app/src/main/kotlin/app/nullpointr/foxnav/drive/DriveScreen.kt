package app.nullpointr.foxnav.drive

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.nullpointr.foxnav.appGraph
import app.nullpointr.foxnav.convoy.ConvoyLayers
import app.nullpointr.foxnav.convoy.MyCarLayer
import app.nullpointr.foxnav.convoy.ConvoyOverlay
import app.nullpointr.foxnav.convoy.ConvoySheet
import app.nullpointr.foxnav.shared.discovery.DiscoveryDocument
import app.nullpointr.foxnav.shared.geo.GeoPoint
import app.nullpointr.foxnav.shared.search.Place
import app.nullpointr.foxnav.ui.ScreenPanels
import app.nullpointr.foxnav.ui.bottomPanelOf
import app.nullpointr.foxnav.ui.topPanelOf
import com.stadiamaps.ferrostar.composeui.config.NavigationViewComponentBuilder
import com.stadiamaps.ferrostar.composeui.config.VisualNavigationViewConfig
import com.stadiamaps.ferrostar.composeui.config.withCustomOverlayView
import com.stadiamaps.ferrostar.composeui.config.withInstructionsView
import com.stadiamaps.ferrostar.composeui.config.withProgressView
import com.stadiamaps.ferrostar.composeui.config.withRoadNameView
import com.stadiamaps.ferrostar.composeui.runtime.KeepScreenOnDisposableEffect
import com.stadiamaps.ferrostar.core.boundingBox
import com.stadiamaps.ferrostar.maplibreui.NavigationMapClickResult
import com.stadiamaps.ferrostar.maplibreui.routeline.RouteOverlayBuilder
import com.stadiamaps.ferrostar.maplibreui.runtime.NavigationCameraMode
import com.stadiamaps.ferrostar.maplibreui.runtime.navigationCameraOptions
import app.nullpointr.foxnav.settings.MapOrientation
import androidx.compose.runtime.snapshotFlow
import com.stadiamaps.ferrostar.maplibreui.runtime.rememberNavigationMapState
import com.stadiamaps.ferrostar.maplibreui.views.DynamicallyOrientingNavigationView
import kotlinx.coroutines.flow.MutableStateFlow
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.style.BaseStyle

/** The main screen: the map in free drive, search, route preview and turn-by-turn. */
@Composable
fun DriveScreen(viewModel: DriveViewModel, document: DiscoveryDocument, styleUrl: String) {
    KeepScreenOnDisposableEffect()
    RequestLocationPermission(onResult = viewModel::onLocationPermission)

    val navigationMapState = rememberNavigationMapState()
    val preview by viewModel.preview.collectAsState()
    val graph = LocalContext.current.appGraph
    val convoy = graph.convoy
    val activeConvoy by convoy.active.collectAsState()
    val convoyMembers by remember(activeConvoy) { activeConvoy?.session?.members ?: MutableStateFlow(emptyMap()) }.collectAsState()
    val regroupPoint by convoy.regroupPoint.collectAsState()
    val cameras by viewModel.cameras.collectAsState()
    val reports by viewModel.reports.collectAsState()
    var showConvoy by rememberSaveable { mutableStateOf(false) }
    var showReport by rememberSaveable { mutableStateOf(false) }
    val panels = remember { ScreenPanels() }
    val query by viewModel.query.collectAsState()
    val settings by graph.settings.settings.collectAsState()
    val profile by graph.profiles.profile.collectAsState()
    val mapCredits = rememberMapCredits(styleUrl)

    // Heading up or north up, whenever the camera follows us (navigating or not).
    LaunchedEffect(navigationMapState, settings.orientation) {
        snapshotFlow { navigationMapState.cameraMode }.collect { mode ->
            val wanted = if (settings.orientation == MapOrientation.NORTH_UP) NavigationCameraMode.FOLLOW_USER else NavigationCameraMode.FOLLOW_USER_WITH_BEARING
            if (mode.tracksLocation() && mode != wanted) navigationMapState.cameraMode = wanted
        }
    }

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
        navigationCameraOptions = navigationCameraOptions().let { defaults ->
            defaults.copy(browsingZoom = 15.0, navigationTilt = if (settings.tilt) defaults.navigationTilt else 0.0)
        },
        viewModel = rememberGlidingViewModel(viewModel),
        // Our own car (MyCarLayer) instead of Ferrostar's puck.
        showDefaultPuck = false,
        // Speed, limit and mute live in our panels.
        config = VisualNavigationViewConfig(showMute = false, showZoom = settings.zoomButtons, showRecenter = true),
        views = NavigationViewComponentBuilder.Default()
            .withInstructionsView { modifier, uiState -> ManeuverCard(uiState, modifier.topPanelOf(panels.navigation)) }
            .withProgressView { modifier, uiState, onTapExit ->
                val speed by viewModel.speed.collectAsState()
                DrivePanel(
                    uiState,
                    speed,
                    onToggleMute = viewModel::toggleMute,
                    onConvoy = { showConvoy = true },
                    convoySize = activeConvoy?.let { convoyMembers.size + 1 },
                    onReport = if (viewModel.canReport) ({ showReport = true }) else null,
                    onEnd = onTapExit,
                    mapCredits = mapCredits,
                    modifier = modifier.bottomPanelOf(panels.navigation),
                )
            }
            .withRoadNameView { modifier, roadName, _ ->
                val cameraAhead by viewModel.cameraAhead.collectAsState()
                Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReportStack(viewModel)
                    CameraWarning(cameraAhead)
                    RoadNamePill(roadName)
                }
            }
            .withCustomOverlayView { modifier ->
                FreeDriveOverlay(
                    modifier,
                    viewModel,
                    document,
                    navigationMapState,
                    panels.freeDrive,
                    mapCredits,
                    onOpenConvoy = { showConvoy = true },
                    onOpenReport = { showReport = true },
                )
                val uiState by viewModel.navigationUiState.collectAsState()
                val route = remember(uiState.routeGeometry) { uiState.routeGeometry?.map { GeoPoint(it.lat, it.lng) } }
                ConvoyOverlay(
                    convoy = convoy,
                    placements = graph.towerPlacement,
                    panels = panels.of(navigating = uiState.isNavigating()),
                    // Out of the way of search results.
                    hidden = query.isNotEmpty() && !uiState.isNavigating(),
                    route = route,
                    onOpen = { showConvoy = true },
                )
            },
        routeOverlayBuilder = RouteOverlayBuilder(
            navigationPath = { uiState -> uiState.routeGeometry?.let { NavigationRoute(it) } },
        ),
        onTapExit = { viewModel.stopNavigation() },
        onMapLongClick = { position, _ ->
            viewModel.preview(Place(title = "Dropped pin", detail = "", latitude = position.lat, longitude = position.lng))
            NavigationMapClickResult.Consume
        },
        // The credits are our own tag on the panels (MapCreditTag), where they don't cover the map's controls.
        mapOptions = MapOptions(ornamentOptions = OrnamentOptions(isCompassEnabled = false, isScaleBarEnabled = false, isLogoEnabled = false, isAttributionEnabled = false)),
    ) { uiState ->
        if (!uiState.isNavigating()) RoutePreviewLayers(preview)
        ReportLayers(reports)
        CameraLayers(cameras)
        ConvoyLayers(convoyMembers, regroupPoint)
        MyCarLayer(rememberShownLocation(uiState), profile.colorRgb, profile.bodyStyle)
    }

    if (showReport) {
        ReportSheet(
            onReport = { type ->
                showReport = false
                viewModel.report(type)
            },
            onDismiss = { showReport = false },
        )
    }

    if (showConvoy) {
        ConvoySheet(
            convoy = convoy,
            serverUrl = graph.serverSettings.serverUrl.collectAsState().value,
            invitePage = document.invites,
            onNavigateTo = viewModel::previewConvoyPlace,
            onDismiss = { showConvoy = false },
        )
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
