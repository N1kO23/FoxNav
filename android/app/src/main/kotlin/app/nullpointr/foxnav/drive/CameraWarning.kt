package app.nullpointr.foxnav.drive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nullpointr.foxnav.shared.format.formatDistance
import app.nullpointr.foxnav.shared.safety.Camera
import app.nullpointr.foxnav.shared.safety.CameraAhead
import app.nullpointr.foxnav.ui.components.SpeedLimitSign
import app.nullpointr.foxnav.ui.icons.FoxIcons
import app.nullpointr.foxnav.ui.theme.ChakraPetch
import java.text.DecimalFormatSymbols

/** The camera coming up, centred above the bottom panel: kind, distance and its limit. */
@Composable
fun CameraWarning(ahead: CameraAhead?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    // Keeps showing the last camera while the warning fades out.
    var shown by remember { mutableStateOf(ahead) }
    if (ahead != null) shown = ahead

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible = ahead != null, enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut() + scaleOut(targetScale = 0.9f)) {
            val warning = shown ?: return@AnimatedVisibility
            Surface(shape = RoundedCornerShape(22.dp), color = colors.tertiaryContainer, border = BorderStroke(1.dp, colors.tertiary)) {
                Row(
                    Modifier.padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(FoxIcons.Camera, contentDescription = null, tint = colors.tertiary, modifier = Modifier.size(22.dp))
                    Text(
                        if (warning.camera.kind == Camera.RED_LIGHT) "RED LIGHT CAMERA" else "SPEED CAMERA",
                        fontFamily = ChakraPetch,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        letterSpacing = 1.sp,
                        color = colors.onTertiaryContainer,
                    )
                    Text(
                        formatDistance(warning.distanceM, DecimalFormatSymbols.getInstance().decimalSeparator),
                        fontFamily = ChakraPetch,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = colors.onTertiaryContainer,
                    )
                    warning.camera.maxspeed?.let { SpeedLimitSign(it, size = 30.dp) }
                }
            }
        }
    }
}
