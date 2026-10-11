package app.nullpointr.foxnav.drive

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import com.stadiamaps.ferrostar.composeui.theme.DefaultNavigationUITheme
import com.stadiamaps.ferrostar.composeui.theme.InstructionRowTheme
import com.stadiamaps.ferrostar.composeui.theme.NavigationUITheme
import com.stadiamaps.ferrostar.composeui.theme.RoadNameViewTheme
import com.stadiamaps.ferrostar.composeui.theme.TripProgressViewTheme
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
import app.nullpointr.foxnav.ui.topPanelOf
import com.stadiamaps.ferrostar.composeui.config.NavigationViewComponentBuilder
import com.stadiamaps.ferrostar.composeui.config.VisualNavigationViewConfig
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.style.BaseStyle

/** The main screen: the map in free drive, search, route preview and turn-by-turn. */
@Composable
fun DriveScreen(viewModel: DriveViewModel, document: DiscoveryDocument, styleUrl: String) {
    KeepScreenOnDisposableEffect()
    RequestLocationPermission(onResult = viewModel::onLocationPermission)

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
    val uiState by viewModel.navigationUiState.collectAsState()
    val density = LocalDensity.current
    var screenHeight by remember { mutableIntStateOf(0) }
    // A little further out than Ferrostar's default while browsing, so free drive shows the road ahead.
    val cameraOptions = navigationCameraOptions().let { defaults ->
        val tilted = defaults.copy(browsingZoom = 15.0, navigationTilt = if (settings.tilt) defaults.navigationTilt else 0.0)
        // The docked panel covers the bottom of the map: keep the car above it (navigating, with room
        // for the road name too). Ferrostar would add room for its own progress view while
        // navigating; with this much already, it adds nothing, so its options and ours agree.
        val navigating = uiState.isNavigating()
        val panelTop = if (navigating) panels.navigation.bottom else panels.freeDrive.bottom
        if (screenHeight == 0 || panelTop.isInfinite()) {
            tilted
        } else {
            val height = with(density) { screenHeight.toDp() }
            // At most 40 %: on a short (landscape) screen the car still gets some map.
            val covered = (with(density) { (screenHeight - panelTop).coerceAtLeast(0f).toDp() } + if (navigating) ROAD_NAME_ROOM else 0.dp).coerceAtMost(height * 0.4f)
            tilted.copy(
                browsingPadding = PaddingValues(bottom = covered),
                navigationPadding = PaddingValues(top = height * 0.5f, bottom = covered),
            )
        }
    }
    // Ours, not Ferrostar's defaults: it sets the options it's given on every recomposition.
    val navigationMapState = rememberNavigationMapState(navigationCameraOptions = cameraOptions)

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

    val convoySize = activeConvoy?.let { convoyMembers.size + 1 }
    // Ferrostar keeps the car above the bottom panel, and the road name too, by the size of its
    // progress view; ours sits outside it (docked to the screen's edge), so it gets a stand-in.
    var drivePanelHeight by remember { mutableIntStateOf(0) }
    val navigationBar = WindowInsets.navigationBars.getBottom(density)

    // Ferrostar moves the camera only when the car moves: standing still in free drive, a new
    // padding (the panel measured, a route preview closed) would wait for the next fix. Apply it
    // then, once any camera move of Ferrostar's own (a second long) is over: starting one cancels
    // the other. Navigating, or moving, the car's fixes keep the camera up to date.
    LaunchedEffect(cameraOptions) {
        if (uiState.isNavigating()) return@LaunchedEffect
        delay(CAMERA_SETTLE_MS)
        val padding = when (navigationMapState.cameraMode) {
            NavigationCameraMode.FOLLOW_USER -> cameraOptions.browsingPadding
            NavigationCameraMode.FOLLOW_USER_WITH_BEARING -> cameraOptions.navigationPadding
            else -> null
        } ?: return@LaunchedEffect
        val camera = navigationMapState.cameraState
        if (camera.position.padding != padding) camera.animateTo(camera.position.copy(padding = padding), duration = 300.milliseconds)
    }

    Box(Modifier.fillMaxSize().onSizeChanged { screenHeight = it.height }) {
        DynamicallyOrientingNavigationView(
            modifier = Modifier.fillMaxSize(),
            baseStyle = BaseStyle.Uri(styleUrl),
            navigationMapState = navigationMapState,
            navigationCameraOptions = cameraOptions,
            viewModel = rememberGlidingViewModel(viewModel),
            // Our own car (MyCarLayer) instead of Ferrostar's puck.
            showDefaultPuck = false,
            // Our own map buttons (MapControls) instead of Ferrostar's.
            theme = WithoutFerrostarButtons,
            config = VisualNavigationViewConfig(showMute = false, showZoom = false, showRecenter = false),
            views = NavigationViewComponentBuilder.Default()
                .withInstructionsView { modifier, state ->
                    ManeuverCard(state, modifier.topPanelOf(panels.navigation)) { RoadWarnings(viewModel) }
                }
                .withProgressView { modifier, _, _ ->
                    Spacer(modifier.fillMaxWidth().height(with(density) { (drivePanelHeight - navigationBar).coerceAtLeast(0).toDp() }))
                }
                .withRoadNameView { modifier, roadName, _ ->
                    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReportStack(viewModel)
                        RoadNamePill(roadName)
                    }
                },
            routeOverlayBuilder = RouteOverlayBuilder(
                navigationPath = { state -> state.routeGeometry?.let { NavigationRoute(it) } },
            ),
            onTapExit = { viewModel.stopNavigation() },
            onMapLongClick = { position, _ ->
                viewModel.preview(Place(title = "Dropped pin", detail = "", latitude = position.lat, longitude = position.lng))
                NavigationMapClickResult.Consume
            },
            // The credits are our own tag on the panels (MapCreditTag), where they don't cover the map's controls.
            mapOptions = MapOptions(ornamentOptions = OrnamentOptions(isCompassEnabled = false, isScaleBarEnabled = false, isLogoEnabled = false, isAttributionEnabled = false)),
        ) { state ->
            if (!state.isNavigating()) RoutePreviewLayers(preview)
            ReportLayers(reports)
            CameraLayers(cameras)
            ConvoyLayers(convoyMembers, regroupPoint)
            MyCarLayer(rememberShownLocation(state), profile.colorRgb, profile.bodyStyle)
        }

        if (uiState.isNavigating()) {
            val speed by viewModel.speed.collectAsState()
            NavigationOverlay(
                uiState,
                speed,
                navigationMapState,
                panels.navigation,
                mapCredits,
                convoySize = convoySize,
                onToggleMute = viewModel::toggleMute,
                onConvoy = { showConvoy = true },
                onReport = if (viewModel.canReport) ({ showReport = true }) else null,
                onEnd = viewModel::stopNavigation,
                onPanelHeight = { drivePanelHeight = it },
            )
        } else {
            FreeDriveOverlay(
                viewModel,
                document,
                navigationMapState,
                panels.freeDrive,
                mapCredits,
                convoySize = convoySize,
                onOpenConvoy = { showConvoy = true },
                onOpenReport = { showReport = true },
            )
        }
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

/**
 * Ferrostar's look, but its own round map buttons shrunk to nothing: MapControls has ours, styled
 * as the mockups. Only their width goes: Ferrostar also counts the buttons' height into the room
 * it keeps between the car and the bottom panel, and the road name sits in that room.
 */
private object WithoutFerrostarButtons : NavigationUITheme {
    override val instructionRowTheme: InstructionRowTheme
        @Composable get() = DefaultNavigationUITheme.instructionRowTheme
    override val roadNameViewTheme: RoadNameViewTheme
        @Composable get() = DefaultNavigationUITheme.roadNameViewTheme
    override val tripProgressViewTheme: TripProgressViewTheme
        @Composable get() = DefaultNavigationUITheme.tripProgressViewTheme
    override val buttonSize: DpSize
        @Composable get() = DpSize(0.dp, 56.dp)
}

private const val CAMERA_SETTLE_MS = 1_200L

/** Above the navigation panel, between it and the car: the road name pill. */
private val ROAD_NAME_ROOM = 56.dp

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
