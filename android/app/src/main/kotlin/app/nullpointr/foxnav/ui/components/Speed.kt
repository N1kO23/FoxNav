package app.nullpointr.foxnav.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nullpointr.foxnav.shared.speed.SpeedReading
import app.nullpointr.foxnav.ui.theme.ChakraPetch

// Vienna Convention sign colours, the same in every theme (design/tokens.json).
private val SignRing = Color(0xFFE3242B)
private val SignFace = Color(0xFFFFFFFF)
private val SignText = Color(0xFF16171A)

/** The digital speedometer with the limit sign next to it; both turn to warning over the limit. */
@Composable
fun SpeedCluster(reading: SpeedReading, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val warn = reading.overLimit
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = colors.surface,
            border = BorderStroke(2.dp, if (warn) colors.error else colors.outline),
        ) {
            Column(
                modifier = Modifier
                    .size(width = 96.dp, height = 76.dp)
                    .semantics { contentDescription = "Speed ${reading.speedKmh ?: "unknown"} km/h" },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = reading.speedKmh?.toString() ?: "–",
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.Bold,
                    fontSize = 40.sp,
                    lineHeight = 40.sp,
                    color = if (warn) colors.error else colors.onSurface,
                )
                Text("km/h", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
        reading.limitKmh?.let { SpeedLimitSign(it, pulsing = warn) }
    }
}

/** A round speed limit sign; it pulses while the driver is over the limit. */
@Composable
fun SpeedLimitSign(limitKmh: Int, pulsing: Boolean = false, size: Dp = 56.dp) {
    val scale = if (pulsing) {
        rememberInfiniteTransition(label = "limit pulse")
            .animateFloat(1f, 1.1f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "scale")
            .value
    } else {
        1f
    }
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(SignFace, CircleShape)
            .border(size * 0.11f, SignRing, CircleShape)
            .semantics { contentDescription = "Speed limit $limitKmh" },
        contentAlignment = Alignment.Center,
    ) {
        Text("$limitKmh", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp, color = SignText)
    }
}
