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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nullpointr.foxnav.R
import app.nullpointr.foxnav.settings.SpeedometerStyle
import app.nullpointr.foxnav.shared.speed.SpeedReading
import app.nullpointr.foxnav.ui.format.LocalSpeedometerStyle
import app.nullpointr.foxnav.ui.format.LocalUnits
import app.nullpointr.foxnav.ui.icons.FoxIcons
import app.nullpointr.foxnav.ui.theme.ChakraPetch

// Vienna Convention sign colours, the same in every theme (design/tokens.json).
private val SignRing = Color(0xFFE3242B)
private val SignFace = Color(0xFFFFFFFF)
private val SignText = Color(0xFF16171A)

/**
 * The digital speedometer with the limit sign next to it; both turn to warning over the limit.
 * Speed and limit are in the user's units ([LocalUnits]); the style comes from the settings.
 */
@Composable
fun SpeedCluster(reading: SpeedReading, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val warn = reading.overLimit
    val units = LocalUnits.current
    val segment = LocalSpeedometerStyle.current == SpeedometerStyle.SEGMENT
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = if (segment) SegmentPanel else colors.surface,
            border = BorderStroke(2.dp, if (warn) colors.error else colors.outline),
        ) {
            Column(
                modifier = Modifier
                    .size(width = 96.dp, height = 76.dp)
                    .semantics { contentDescription = "Speed ${reading.speed ?: "unknown"} ${units.speedLabel}" },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (segment) {
                    SegmentDigits(reading.speed, lit = if (warn) colors.error else SegmentLit)
                } else {
                    Text(
                        text = reading.speed?.toString() ?: "–",
                        fontFamily = ChakraPetch,
                        fontWeight = FontWeight.Bold,
                        fontSize = 40.sp,
                        lineHeight = 40.sp,
                        color = if (warn) colors.error else colors.onSurface,
                    )
                }
                Text(units.speedLabel, style = MaterialTheme.typography.labelSmall, color = if (segment) SegmentUnlit.copy(alpha = 1f) else colors.onSurfaceVariant)
            }
        }
        reading.limit?.let { SpeedLimitSign(it, pulsing = warn, winter = reading.limitSeason == "winter") }
    }
}

/** A 7-segment readout: the unlit "888" behind the speed, like an 80s digital dash. */
@Composable
private fun SegmentDigits(speed: Int?, lit: Color) {
    Box(contentAlignment = Alignment.CenterEnd) {
        Text("888", fontFamily = Dseg7, fontSize = 32.sp, lineHeight = 36.sp, color = SegmentUnlit)
        // DSEG7's "!" is a blank cell, so the digits right-align over the ghosts.
        val digits = speed?.coerceIn(0, 999)?.toString() ?: "-"
        Text(digits.padStart(3, '!'), fontFamily = Dseg7, fontSize = 32.sp, lineHeight = 36.sp, color = lit)
    }
}

private val Dseg7 = FontFamily(Font(R.font.dseg7_classic_bold))

// A dark, slightly green-tinted LCD in both themes, so the segments always glow.
private val SegmentPanel = Color(0xFF0E1412)
private val SegmentLit = Color(0xFF7CFFB2)
private val SegmentUnlit = Color(0x1F7CFFB2)

/** A round speed limit sign; it pulses while the driver is over the limit. */
@Composable
fun SpeedLimitSign(limit: Int, pulsing: Boolean = false, size: Dp = 56.dp, winter: Boolean = false) {
    val scale = if (pulsing) {
        rememberInfiniteTransition(label = "limit pulse")
            .animateFloat(1f, 1.1f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "scale")
            .value
    } else {
        1f
    }
    Box(Modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale }) {
        Box(
            modifier = Modifier
                .size(size)
                .background(SignFace, CircleShape)
                .border(size * 0.11f, SignRing, CircleShape)
                .semantics { contentDescription = if (winter) "Winter speed limit $limit" else "Speed limit $limit" },
            contentAlignment = Alignment.Center,
        ) {
            Text("$limit", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp, color = SignText)
        }
        // A winter limit, from the date rather than the map: says why it isn't the summer number.
        if (winter) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary),
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = size * 0.08f, y = size * 0.08f).size(size * 0.4f),
            ) {
                Icon(FoxIcons.Snowflake, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(size * 0.07f))
            }
        }
    }
}
