package app.nullpointr.foxnav.drive

import app.nullpointr.foxnav.ui.format.distanceText
import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nullpointr.foxnav.shared.format.formatDuration
import app.nullpointr.foxnav.shared.map.MapCredit
import app.nullpointr.foxnav.shared.speed.SpeedReading
import app.nullpointr.foxnav.ui.components.ManeuverGlyph
import app.nullpointr.foxnav.ui.components.SpeedCluster
import app.nullpointr.foxnav.ui.theme.ChakraPetch
import com.stadiamaps.ferrostar.core.NavigationUiState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import app.nullpointr.foxnav.ui.PanelBounds
import app.nullpointr.foxnav.ui.LocalCompact
import app.nullpointr.foxnav.ui.WindowLayout
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.Dp
import app.nullpointr.foxnav.ui.bottomPanelOf
import app.nullpointr.foxnav.ui.components.BottomPanel
import app.nullpointr.foxnav.ui.components.JapaneseAccent
import app.nullpointr.foxnav.ui.components.PanelAction
import app.nullpointr.foxnav.ui.components.maneuverAccent
import app.nullpointr.foxnav.ui.icons.FoxIcons
import com.stadiamaps.ferrostar.core.boundingBox
import com.stadiamaps.ferrostar.maplibreui.runtime.NavigationMapState
import uniffi.ferrostar.LaneInfo
import uniffi.ferrostar.ManeuverModifier
import uniffi.ferrostar.ManeuverType
import uniffi.ferrostar.RouteDeviation
import java.text.DecimalFormatSymbols
import java.util.Date

/** The next maneuver, HUD style: icon, distance and road, then the maneuver after it. */
@Composable
fun ManeuverCard(uiState: NavigationUiState, modifier: Modifier = Modifier, maxWidth: Dp = Dp.Unspecified, warnings: @Composable () -> Unit = {}) {
    val instruction = uiState.visualInstruction ?: return
    val colors = MaterialTheme.colorScheme
    val next = uiState.remainingSteps?.getOrNull(1)
    val compact = LocalCompact.current

    Column(modifier.widthIn(max = maxWidth).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outline),
        ) {
            Column {
                Row(Modifier.padding(if (compact) 12.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(if (compact) 56.dp else 72.dp).background(colors.secondary.copy(alpha = 0.14f), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        ManeuverGlyph(instruction.primaryContent, tint = colors.secondary, size = if (compact) 40.dp else 52.dp)
                    }
                    Column(Modifier.weight(1f)) {
                        val content = instruction.primaryContent
                        uiState.progress?.distanceToNextManeuver?.let { DistanceReadout(it, maneuverAccent(content.maneuverType, content.maneuverModifier)) }
                        Text(content.text, fontSize = if (compact) 17.sp else 20.sp, fontWeight = FontWeight.SemiBold, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                val nextContent = next?.visualInstructions?.firstOrNull()?.primaryContent
                if (next != null && nextContent != null) {
                    HorizontalDivider(color = colors.outline)
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = if (compact) 6.dp else 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Then", color = colors.onSurfaceVariant)
                        ManeuverGlyph(nextContent, tint = colors.onSurfaceVariant, size = 22.dp)
                        Text(nextContent.text, Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(distanceText(next.distance), fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
        // Off the route, the lanes are the old route's: say what's happening instead, until the new route is in.
        if (uiState.routeDeviation is RouteDeviation.Deviation) Rerouting() else LaneGuide(instruction.subContent?.laneInfo)
        Box(Modifier.fillMaxWidth()) { warnings() }
    }
}

/** Off the route (or the wrong way along it), while the new one comes. */
@Composable
private fun Rerouting() {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(16.dp), color = colors.surface, border = BorderStroke(1.dp, colors.tertiary)) {
        Text(
            "OFF ROUTE · finding a new one",
            Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            letterSpacing = 1.sp,
            color = colors.tertiary,
        )
    }
}

/** The lanes at the next maneuver, where the map knows them: the ones to take lit in the route colour. */
@Composable
private fun LaneGuide(lanes: List<LaneInfo>?) {
    if (lanes == null || lanes.size < 2 || lanes.none { it.active }) return
    val colors = MaterialTheme.colorScheme
    val take = lanes.count { it.active }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outline),
        modifier = Modifier.semantics { contentDescription = "Lanes: $take of ${lanes.size} go your way" },
    ) {
        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            lanes.forEach { lane ->
                Box(
                    Modifier.size(width = 40.dp, height = 34.dp).background(if (lane.active) colors.secondary.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    // A lane marked for several directions shows them all; the one we take on top, lit.
                    val ours = lane.activeDirection?.takeIf { lane.active } ?: lane.directions.firstOrNull().takeIf { lane.active }
                    (lane.directions - setOfNotNull(ours)).forEach { LaneArrow(it, colors.onSurfaceVariant.copy(alpha = 0.55f)) }
                    ours?.let { LaneArrow(it, colors.secondary) }
                }
            }
        }
    }
}

@Composable
private fun LaneArrow(direction: String, tint: Color) {
    val (type, modifier) = when (direction) {
        "uturn" -> ManeuverType.CONTINUE to ManeuverModifier.U_TURN
        "sharp right" -> ManeuverType.TURN to ManeuverModifier.SHARP_RIGHT
        "right" -> ManeuverType.TURN to ManeuverModifier.RIGHT
        "slight right" -> ManeuverType.TURN to ManeuverModifier.SLIGHT_RIGHT
        "slight left" -> ManeuverType.TURN to ManeuverModifier.SLIGHT_LEFT
        "left" -> ManeuverType.TURN to ManeuverModifier.LEFT
        "sharp left" -> ManeuverType.TURN to ManeuverModifier.SHARP_LEFT
        // "straight", and "none" for a lane with no arrows painted
        else -> ManeuverType.TURN to ManeuverModifier.STRAIGHT
    }
    ManeuverGlyph(type, modifier, tint, 24.dp)
}

/** The road the car is on, as a pill above the bottom panel. */
@Composable
fun RoadNamePill(roadName: String?, modifier: Modifier = Modifier) {
    if (roadName.isNullOrBlank()) return
    val colors = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(20.dp), color = colors.surface, border = BorderStroke(1.dp, colors.outline)) {
            Text(roadName, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "350 m" with a large number and a smaller unit, and the direction's Japanese accent (右方向). */
@Composable
private fun DistanceReadout(meters: Double, accent: String?) {
    val (number, unit) = distanceText(meters).split(' ', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    val compact = LocalCompact.current
    Row {
        Text(number, Modifier.alignByBaseline(), fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = if (compact) 34.sp else 44.sp, lineHeight = if (compact) 34.sp else 44.sp)
        Text(" $unit", Modifier.alignByBaseline(), fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = if (compact) 18.sp else 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        accent?.let { JapaneseAccent(it, Modifier.alignByBaseline().padding(start = 10.dp), fontSize = 14.sp) }
    }
}

/** On top of the map while navigating: the map buttons at the right edge and the panel docked at the bottom. */
@Composable
fun NavigationOverlay(
    uiState: NavigationUiState,
    speed: SpeedReading,
    navigationMapState: NavigationMapState,
    panels: PanelBounds,
    layout: WindowLayout,
    mapCredits: List<MapCredit>,
    convoySize: Int?,
    onToggleMute: () -> Unit,
    onConvoy: () -> Unit,
    onReport: (() -> Unit)?,
    onEnd: () -> Unit,
    onPanelHeight: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val sides = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenHeight = maxHeight
        MapControls(
            navigationMapState,
            isMuted = uiState.isMuted,
            onToggleMute = onToggleMute,
            onRouteOverview = {
                uiState.routeGeometry?.boundingBox()?.let { bounds ->
                    val padding = if (layout.side) {
                        // Right of the side column.
                        PaddingValues(start = layout.columnWidth + 36.dp, end = 72.dp, top = 48.dp, bottom = 48.dp)
                    } else {
                        // Between the maneuver card and the panel, with room to spare.
                        val top = with(density) { panels.top.toDp() } + 24.dp
                        val bottom = (screenHeight - with(density) { panels.bottom.coerceAtMost(constraints.maxHeight.toFloat()).toDp() }) + 24.dp
                        PaddingValues(start = 48.dp, end = 72.dp, top = top, bottom = bottom)
                    }
                    navigationMapState.showRouteOverview(bounds, padding)
                }
            },
            modifier = Modifier.align(Alignment.CenterEnd).windowInsetsPadding(sides).padding(end = 12.dp),
        )
        // Below the maneuver card, however short the window: the panel scrolls rather than covering it.
        val room = if (panels.top > 0f) screenHeight - with(density) { panels.top.toDp() } - 12.dp else Dp.Infinity
        DrivePanel(
            uiState,
            speed,
            onConvoy = onConvoy,
            convoySize = convoySize,
            onReport = onReport,
            onEnd = onEnd,
            mapCredits = mapCredits,
            docked = !layout.side,
            modifier = if (layout.side) {
                Modifier
                    .align(Alignment.BottomStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .padding(start = 12.dp, bottom = 12.dp)
                    .width(layout.columnWidth)
                    .heightIn(max = room)
                    .bottomPanelOf(panels)
                    .onSizeChanged { onPanelHeight(0) }
            } else {
                Modifier.align(Alignment.BottomCenter).heightIn(max = room).bottomPanelOf(panels).onSizeChanged { onPanelHeight(it.height) }
            },
        )
    }
}

/** Bottom panel while navigating, as in the mockup: speed and limit, arrival, then Report, Convoy and End. */
@Composable
fun DrivePanel(
    uiState: NavigationUiState,
    speed: SpeedReading,
    onConvoy: () -> Unit,
    /** Cars in our convoy, us included; null when not in one. */
    convoySize: Int?,
    onReport: (() -> Unit)?,
    onEnd: () -> Unit,
    mapCredits: List<MapCredit>,
    modifier: Modifier = Modifier,
    /** Along the screen's bottom edge, or floating in the side column. */
    docked: Boolean = true,
) {
    val progress = uiState.progress ?: return
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val arrival = remember(progress.durationRemaining.toLong() / 30) {
        DateFormat.getTimeFormat(context).format(Date(System.currentTimeMillis() + (progress.durationRemaining * 1000).toLong()))
    }

    Column(modifier) {
        MapCreditTag(mapCredits, if (docked) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(start = 12.dp) else Modifier.padding(start = 4.dp))
        BottomPanel(Modifier.weight(1f, fill = false), docked = docked) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpeedCluster(speed)
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Row {
                        JapaneseAccent("到着", Modifier.alignByBaseline().padding(end = 6.dp))
                        Text(arrival, Modifier.alignByBaseline(), fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = if (LocalCompact.current) 24.sp else 32.sp, maxLines = 1)
                    }
                    Text(
                        "${formatDuration(progress.durationRemaining)} · ${distanceText(progress.distanceRemaining)}",
                        fontFamily = ChakraPetch,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = if (LocalCompact.current) 14.sp else 16.sp,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onReport != null) PanelAction("Report", FoxIcons.Report, onReport, brand = true)
                PanelAction("Convoy", FoxIcons.People, onConvoy, badge = convoySize)
                PanelAction("End", FoxIcons.Close, onEnd, iconSize = 18.dp)
            }
        }
    }
}


private fun decimalSeparator(): Char = DecimalFormatSymbols.getInstance().decimalSeparator
