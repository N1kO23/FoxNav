package app.nullpointr.foxnav.drive

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nullpointr.foxnav.shared.format.formatDistance
import app.nullpointr.foxnav.shared.format.formatDuration
import app.nullpointr.foxnav.shared.speed.SpeedReading
import app.nullpointr.foxnav.ui.components.ManeuverGlyph
import app.nullpointr.foxnav.ui.components.SpeedCluster
import app.nullpointr.foxnav.ui.theme.ChakraPetch
import com.stadiamaps.ferrostar.core.NavigationUiState
import java.text.DecimalFormatSymbols
import java.util.Date

/** The next maneuver, HUD style: icon, distance and road, then the maneuver after it. */
@Composable
fun ManeuverCard(uiState: NavigationUiState, modifier: Modifier = Modifier) {
    val instruction = uiState.visualInstruction ?: return
    val colors = MaterialTheme.colorScheme
    val next = uiState.remainingSteps?.getOrNull(1)

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
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
                    Text(formatDistance(next.distance, decimalSeparator()), fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant)
                }
            }
        }
    }
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
    val (number, unit) = formatDistance(meters, decimalSeparator()).split(' ', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    Row {
        Text(number, Modifier.alignByBaseline(), fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 40.sp)
        Text(" $unit", Modifier.alignByBaseline(), fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Bottom panel while navigating: speed and limit, arrival, mute and end. */
@Composable
fun DrivePanel(
    uiState: NavigationUiState,
    speed: SpeedReading,
    onToggleMute: () -> Unit,
    onEnd: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val progress = uiState.progress ?: return
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val arrival = remember(progress.durationRemaining.toLong() / 30) {
        DateFormat.getTimeFormat(context).format(Date(System.currentTimeMillis() + (progress.durationRemaining * 1000).toLong()))
    }

    Surface(
        modifier = modifier.fillMaxWidth().padding(12.dp),
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
                        "${formatDuration(progress.durationRemaining)} · ${formatDistance(progress.distanceRemaining, decimalSeparator())}",
                        fontFamily = ChakraPetch,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onToggleMute, modifier = Modifier.weight(1f)) {
                    Text(if (uiState.isMuted == true) "Unmute" else "Mute")
                }
                if (onEnd != null) {
                    Button(onClick = onEnd, modifier = Modifier.weight(1f)) { Text("End") }
                }
            }
        }
    }
}

private fun decimalSeparator(): Char = DecimalFormatSymbols.getInstance().decimalSeparator
