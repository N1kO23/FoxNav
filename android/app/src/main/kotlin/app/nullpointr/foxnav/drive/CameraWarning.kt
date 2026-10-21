package app.nullpointr.foxnav.drive

import app.nullpointr.foxnav.shared.format.limitIn
import app.nullpointr.foxnav.ui.format.LocalUnits
import app.nullpointr.foxnav.ui.format.distanceText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nullpointr.foxnav.shared.safety.Camera
import app.nullpointr.foxnav.shared.safety.CameraAhead
import app.nullpointr.foxnav.ui.components.SpeedLimitSign
import app.nullpointr.foxnav.ui.components.WarningChip
import app.nullpointr.foxnav.ui.icons.FoxIcons

/** The camera coming up, as the mockups' amber chip: kind, distance and its limit. */
@Composable
fun CameraWarning(ahead: CameraAhead?, modifier: Modifier = Modifier) {
    // Keeps showing the last camera while the warning fades out.
    var shown by remember { mutableStateOf(ahead) }
    if (ahead != null) shown = ahead

    AnimatedVisibility(visible = ahead != null, modifier = modifier, enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut() + scaleOut(targetScale = 0.9f)) {
        val warning = shown ?: return@AnimatedVisibility
        val kind = warning.camera.kind
        WarningChip(
            icon = FoxIcons.Camera,
            label = when (kind) {
                Camera.RED_LIGHT -> "Red light camera"
                Camera.AVERAGE_SPEED -> "Average speed check"
                else -> "Speed camera"
            },
            distance = distanceText(warning.distanceM),
            // オービス is what Japanese drivers call a speed camera; red light and average speed cameras are something else.
            accent = if (kind == Camera.SPEED) "オービス" else null,
            trailing = { warning.camera.maxspeed?.let { SpeedLimitSign(limitIn(it, LocalUnits.current), size = 30.dp) } },
        )
    }
}

/** Where we are in an average speed section: [remainingM] to its end, our [average] so far, and whether it's over the limit. */
data class SectionShown(val remainingM: Double, val average: Int?, val over: Boolean)

/**
 * In an average speed section: our average so far, in the speedometer's units, and how far it is
 * to the end. Amber as the other warnings, red while the average is over the limit.
 */
@Composable
fun SectionWarning(section: SectionShown?, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(section) }
    if (section != null) shown = section

    AnimatedVisibility(visible = section != null, modifier = modifier, enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut() + scaleOut(targetScale = 0.9f)) {
        val now = shown ?: return@AnimatedVisibility
        val units = LocalUnits.current
        WarningChip(
            icon = FoxIcons.Camera,
            label = now.average?.let { "Average $it ${units.speedLabel}" } ?: "Average speed check",
            detail = if (now.average != null) "Average speed check" else null,
            distance = distanceText(now.remainingM),
            color = if (now.over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
            onColor = if (now.over) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onTertiary,
        )
    }
}
