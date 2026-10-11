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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import app.nullpointr.foxnav.shared.format.formatDistance
import app.nullpointr.foxnav.shared.format.formatDuration
import app.nullpointr.foxnav.shared.map.MapCredit
import app.nullpointr.foxnav.shared.speed.SpeedReading
import app.nullpointr.foxnav.ui.components.ManeuverGlyph
import app.nullpointr.foxnav.ui.components.SpeedCluster
import app.nullpointr.foxnav.ui.theme.ChakraPetch
import com.stadiamaps.ferrostar.core.NavigationUiState
import uniffi.ferrostar.LaneInfo
import uniffi.ferrostar.ManeuverModifier
import uniffi.ferrostar.ManeuverType
import java.text.DecimalFormatSymbols
import java.util.Date

/** The next maneuver, HUD style: icon, distance and road, then the maneuver after it. */
@Composable
fun ManeuverCard(uiState: NavigationUiState, modifier: Modifier = Modifier) {
    val instruction = uiState.visualInstruction ?: return
    val colors = MaterialTheme.colorScheme
    val next = uiState.remainingSteps?.getOrNull(1)

    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outline),
        ) {
            Column {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(72.dp).background(colors.secondary.copy(alpha = 0.14f), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        ManeuverGlyph(instruction.primaryContent, tint = colors.secondary, size = 52.dp)
                    }
                    Column(Modifier.weight(1f)) {
                        uiState.progress?.distanceToNextManeuver?.let { DistanceReadout(it) }
                        Text(instruction.primaryContent.text, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                val nextContent = next?.visualInstructions?.firstOrNull()?.primaryContent
                if (next != null && nextContent != null) {
                    HorizontalDivider(color = colors.outline)
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
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
        LaneGuide(instruction.subContent?.laneInfo)
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

/** "350 m" with a large number and a smaller unit. */
@Composable
private fun DistanceReadout(meters: Double) {
    val (number, unit) = distanceText(meters).split(' ', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    Row {
        Text(number, Modifier.alignByBaseline(), fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 40.sp)
        Text(" $unit", Modifier.alignByBaseline(), fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Bottom panel while navigating: speed and limit, arrival, reporting, mute, convoy and end. */
@Composable
fun DrivePanel(
    uiState: NavigationUiState,
    speed: SpeedReading,
    onToggleMute: () -> Unit,
    onConvoy: () -> Unit,
    /** Cars in our convoy, us included; null when not in one. */
    convoySize: Int?,
    onReport: (() -> Unit)?,
    onEnd: (() -> Unit)?,
    mapCredits: List<MapCredit>,
    modifier: Modifier = Modifier,
) {
    val progress = uiState.progress ?: return
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val arrival = remember(progress.durationRemaining.toLong() / 30) {
        DateFormat.getTimeFormat(context).format(Date(System.currentTimeMillis() + (progress.durationRemaining * 1000).toLong()))
    }

    Column(modifier.fillMaxWidth().padding(12.dp)) {
        MapCreditTag(mapCredits)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outline),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SpeedCluster(speed)
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(arrival, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 30.sp)
                        Text(
                            "${formatDuration(progress.durationRemaining)} · ${distanceText(progress.distanceRemaining)}",
                            fontFamily = ChakraPetch,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onReport != null) ReportButton(onClick = onReport, modifier = Modifier.size(PANEL_BUTTON_HEIGHT))
                    OutlinedButton(onClick = onToggleMute, contentPadding = PANEL_BUTTON_PADDING, modifier = Modifier.weight(1f).height(PANEL_BUTTON_HEIGHT)) {
                        Text(if (uiState.isMuted == true) "Unmute" else "Mute", maxLines = 1)
                    }
                    OutlinedButton(onClick = onConvoy, contentPadding = PANEL_BUTTON_PADDING, modifier = Modifier.weight(1f).height(PANEL_BUTTON_HEIGHT)) {
                        Text("Convoy", maxLines = 1)
                        if (convoySize != null) {
                            Box(
                                Modifier.padding(start = 6.dp).size(22.dp).background(colors.secondary, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("$convoySize", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = colors.onSecondary)
                            }
                        }
                    }
                    if (onEnd != null) {
                        Button(onClick = onEnd, contentPadding = PANEL_BUTTON_PADDING, modifier = Modifier.weight(1f).height(PANEL_BUTTON_HEIGHT)) { Text("End", maxLines = 1) }
                    }
                }
            }
        }
    }
}

private val PANEL_BUTTON_HEIGHT = 52.dp
private val PANEL_BUTTON_PADDING = PaddingValues(horizontal = 8.dp)

private fun decimalSeparator(): Char = DecimalFormatSymbols.getInstance().decimalSeparator
