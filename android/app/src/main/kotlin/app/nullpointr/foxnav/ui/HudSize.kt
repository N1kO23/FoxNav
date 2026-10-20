package app.nullpointr.foxnav.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import app.nullpointr.foxnav.ui.theme.LocalHudScale

// Sizes in the driving displays (speedometer, limit sign, maneuver card, arrival time, road name):
// at the size the driver chose in settings ("Driving display size").

/** [this] dp at the driving displays' size. */
val Int.hudDp: Dp
    @Composable @ReadOnlyComposable get() = (this * LocalHudScale.current).dp

/**
 * [this] sp of text in the driving displays: at their size, but not the phone's or the app's
 * text size, since their boxes are made for their numbers.
 */
val Int.hudSp: TextUnit
    @Composable @ReadOnlyComposable get() = with(LocalDensity.current) { (this@hudSp * LocalHudScale.current).dp.toSp() }

/** Text [size] across, in the driving displays: without the text size settings, like [hudSp]. */
@Composable
@ReadOnlyComposable
fun hudText(size: Dp): TextUnit = with(LocalDensity.current) { size.toSp() }
