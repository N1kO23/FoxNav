package app.nullpointr.foxnav.drive

import app.nullpointr.foxnav.shared.format.limitIn
import app.nullpointr.foxnav.ui.format.LocalUnits
import app.nullpointr.foxnav.ui.format.distanceText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
        val speedCamera = warning.camera.kind != Camera.RED_LIGHT
        WarningChip(
            icon = FoxIcons.Camera,
            label = if (speedCamera) "Speed camera" else "Red light camera",
            distance = distanceText(warning.distanceM),
            // オービス is what Japanese drivers call a speed camera; a red light one is something else.
            accent = if (speedCamera) "オービス" else null,
            trailing = { warning.camera.maxspeed?.let { SpeedLimitSign(limitIn(it, LocalUnits.current), size = 30.dp) } },
        )
    }
}
