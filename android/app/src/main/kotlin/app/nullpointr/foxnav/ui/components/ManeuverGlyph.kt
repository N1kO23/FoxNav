package app.nullpointr.foxnav.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.stadiamaps.ferrostar.ui.shared.icons.ManeuverIcon
import uniffi.ferrostar.ManeuverModifier
import uniffi.ferrostar.ManeuverType
import uniffi.ferrostar.VisualInstructionContent

/** Ferrostar's maneuver icon (turn arrows and so on) at any size. */
@Composable
fun ManeuverGlyph(content: VisualInstructionContent, tint: Color, size: Dp) {
    ManeuverGlyph(content.maneuverType ?: return, content.maneuverModifier ?: return, tint, size)
}

@Composable
fun ManeuverGlyph(type: ManeuverType, modifier: ManeuverModifier, tint: Color, size: Dp) {
    val context = LocalContext.current
    val resource = remember(type, modifier) { ManeuverIcon(context, type, modifier).resourceId } ?: return
    Icon(painterResource(resource), contentDescription = null, tint = tint, modifier = Modifier.size(size))
}
