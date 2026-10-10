package app.nullpointr.foxnav.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Where a screen's fixed panels are, in root coordinates, so floating things (the convoy tower)
 * can stay between them whatever the screen size and font scale.
 */
@Stable
class PanelBounds {
    /** Bottom edge of the panels at the top: the search bar, or the maneuver card. 0 until known. */
    var top by mutableFloatStateOf(0f)

    /** Top edge of the panels at the bottom: the free-drive or drive panel. Infinite until known. */
    var bottom by mutableFloatStateOf(Float.POSITIVE_INFINITY)
}

/** Free drive and navigation show different panels, so each mode keeps its own bounds. */
@Stable
class ScreenPanels {
    val freeDrive = PanelBounds()
    val navigation = PanelBounds()

    fun of(navigating: Boolean) = if (navigating) navigation else freeDrive
}

/** Reports this panel as the top one: things float below it. */
fun Modifier.topPanelOf(bounds: PanelBounds) = onGloballyPositioned { bounds.top = it.boundsInRoot().bottom }

/** Reports this panel as the bottom one: things float above it. */
fun Modifier.bottomPanelOf(bounds: PanelBounds) = onGloballyPositioned { bounds.bottom = it.boundsInRoot().top }
