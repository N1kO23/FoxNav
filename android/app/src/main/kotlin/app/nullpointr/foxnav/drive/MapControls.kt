package app.nullpointr.foxnav.drive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.nullpointr.foxnav.appGraph
import app.nullpointr.foxnav.settings.MapOrientation
import app.nullpointr.foxnav.ui.components.MapButton
import app.nullpointr.foxnav.ui.icons.FoxIcons
import com.stadiamaps.ferrostar.maplibreui.runtime.NavigationMapState

/**
 * The round map buttons at the right edge, as in the mockups: mute, then north (free drive) or
 * the whole route (navigating), zoom if the user wants it, and back to the car once they've
 * moved the map.
 */
@Composable
fun MapControls(
    navigationMapState: NavigationMapState,
    isMuted: Boolean?,
    onToggleMute: () -> Unit,
    /** Shows the whole route; null when not navigating. */
    onRouteOverview: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val graph = LocalContext.current.appGraph
    val settings by graph.settings.settings.collectAsState()
    val navigating = onRouteOverview != null
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (isMuted != null) {
            MapButton(onClick = onToggleMute) {
                Icon(if (isMuted) FoxIcons.SpeakerOff else FoxIcons.SpeakerOn, contentDescription = if (isMuted) "Turn the voice on" else "Mute the voice")
            }
        }
        if (onRouteOverview != null) {
            if (navigationMapState.isTrackingUser) {
                MapButton(onClick = onRouteOverview) { Icon(FoxIcons.RouteOverview, contentDescription = "Show the whole route") }
            }
        } else {
            val northUp = settings.orientation == MapOrientation.NORTH_UP
            MapButton(
                onClick = { graph.settings.update { it.copy(orientation = if (northUp) MapOrientation.HEADING_UP else MapOrientation.NORTH_UP) } },
                modifier = Modifier.semantics { contentDescription = if (northUp) "Turn the map with your heading" else "Point the map north" },
            ) { CompassNeedle(navigationMapState) }
        }
        if (settings.zoomButtons) {
            MapButton(onClick = { navigationMapState.zoomIn() }) { Icon(FoxIcons.Plus, contentDescription = "Zoom in") }
            MapButton(onClick = { navigationMapState.zoomOut() }) { Icon(FoxIcons.Minus, contentDescription = "Zoom out") }
        }
        if (!navigationMapState.isTrackingUser) {
            MapButton(onClick = { navigationMapState.recenter(isNavigating = navigating) }) { Icon(FoxIcons.Locate, contentDescription = "Back to your car") }
        }
    }
}

/** The mockup's compass: a vermilion north half, turning with the map so it always points north. */
@Composable
private fun CompassNeedle(navigationMapState: NavigationMapState) {
    val north = MaterialTheme.colorScheme.primary
    val south = MaterialTheme.colorScheme.onSurface
    // Read while drawing, not composing: the map turns every frame in heading-up.
    Canvas(Modifier.size(22.dp).graphicsLayer { rotationZ = -navigationMapState.cameraState.position.bearing.toFloat() }) {
        val unit = size.width / 24f
        val northHalf = Path().apply {
            moveTo(12 * unit, 3 * unit)
            lineTo(16 * unit, 13 * unit)
            lineTo(8 * unit, 13 * unit)
            close()
        }
        val southHalf = Path().apply {
            moveTo(8 * unit, 13 * unit)
            lineTo(16 * unit, 13 * unit)
            lineTo(12 * unit, 21 * unit)
            close()
        }
        drawPath(northHalf, north)
        drawPath(southHalf, south, style = Stroke(width = 1.8f * unit, join = StrokeJoin.Round))
    }
}
