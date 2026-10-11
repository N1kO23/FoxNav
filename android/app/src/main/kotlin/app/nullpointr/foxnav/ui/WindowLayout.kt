package app.nullpointr.foxnav.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How the drive screen fits the window.
 *
 * A phone held upright gets the mockups' layout: search or the maneuver card at the top, the
 * panel docked along the bottom. Anything wider (a phone on its side, a tablet, an unfolded
 * foldable) gets a side column instead: the same top and panel stacked on the left, so the map
 * keeps its height, and the car drives in the open map to the right of them.
 */
data class WindowLayout(
    val side: Boolean,
    /** The side column's width; the full width with the bottom panel. */
    val columnWidth: Dp,
    /** A narrow phone (under 380 dp), or a short window (a phone on its side): panels use their smaller sizes. */
    val compact: Boolean,
    /** Ferrostar lays out its own navigation views for landscape (its maneuver card slot is the left half). */
    val ferrostarLandscape: Boolean,
    val width: Dp,
    val height: Dp,
)

@Composable
fun rememberWindowLayout(): WindowLayout {
    val size = LocalWindowInfo.current.containerSize
    val (width, height) = with(LocalDensity.current) { size.width.toDp() to size.height.toDp() }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val side = width >= SIDE_FROM || width > height
    return WindowLayout(
        side = side,
        columnWidth = if (side) minOf(COLUMN_MAX, width * 0.5f - 12.dp) else width,
        compact = width < NARROW_BELOW || height < SHORT_BELOW,
        ferrostarLandscape = landscape,
        width = width,
        height = height,
    )
}

private val SIDE_FROM = 600.dp
private val COLUMN_MAX = 420.dp
private val NARROW_BELOW = 380.dp
private val SHORT_BELOW = 500.dp

/** Whether panels use their smaller sizes ([WindowLayout.compact]). */
val LocalCompact = compositionLocalOf { false }
