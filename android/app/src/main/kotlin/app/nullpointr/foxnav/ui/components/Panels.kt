package app.nullpointr.foxnav.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.nullpointr.foxnav.ui.LocalCompact
import app.nullpointr.foxnav.ui.theme.ChakraPetch
import app.nullpointr.foxnav.ui.theme.LocalNight

/**
 * The bottom panel as the mockups draw it: docked to the screen's bottom edge, rounded at the
 * top, a hairline along the top edge, and the ground colour just see-through enough to hint at
 * the map. It reaches under the navigation bar, with its content above it.
 *
 * Not [docked] (the side column of wide windows), it floats instead: rounded all round, with a
 * hairline all round, clear of the screen's edges.
 */
@Composable
fun BottomPanel(modifier: Modifier = Modifier, docked: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val color = colors.background.copy(alpha = if (LocalNight.current) 0.94f else 0.96f)
    if (docked) {
        Surface(
            modifier = modifier.fillMaxWidth().topHairline(colors.outline, PANEL_RADIUS),
            shape = RoundedCornerShape(topStart = PANEL_RADIUS, topEnd = PANEL_RADIUS),
            color = color,
        ) {
            PanelContent(Modifier.navigationBarsPadding(), content)
        }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(PANEL_RADIUS),
            color = color,
            border = BorderStroke(1.dp, colors.outline),
        ) {
            PanelContent(Modifier, content)
        }
    }
}

/** The panel's content, scrolling if the window is too short for it all (a route preview on a phone on its side). */
@Composable
private fun PanelContent(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val compact = LocalCompact.current
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(if (compact) 12.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp),
        content = content,
    )
}

/** A 1 dp line along the rounded top edge only: the panel's sides and bottom are the screen's. */
private fun Modifier.topHairline(color: Color, radius: Dp) = drawWithContent {
    drawContent()
    val r = radius.toPx()
    val inset = 0.5.dp.toPx()
    val path = Path().apply {
        moveTo(inset, r)
        arcTo(Rect(inset, inset, 2 * r, 2 * r), 180f, 90f, false)
        lineTo(size.width - r, inset)
        arcTo(Rect(size.width - 2 * r, inset, size.width - inset, 2 * r), 270f, 90f, false)
    }
    drawPath(path, color, style = Stroke(1.dp.toPx()))
}

/**
 * A big panel button with an icon and a label: Report, Convoy, End. The brand one (vermilion,
 * filled) is the main action; the rest are raised cards with a hairline.
 */
@Composable
fun RowScope.PanelAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    brand: Boolean = false,
    badge: Int? = null,
    iconSize: Dp = 20.dp,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        // Named here: with large text on a narrow screen, the label gives way to the icon.
        modifier = Modifier.weight(1f).heightIn(min = if (LocalCompact.current) 46.dp else 56.dp).semantics { contentDescription = label },
        shape = RoundedCornerShape(16.dp),
        color = if (brand) colors.primary else colors.surface,
        contentColor = if (brand) colors.onPrimary else colors.onSurface,
        border = if (brand) null else BorderStroke(1.dp, colors.outline),
    ) {
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize))
            Text(label, Modifier.weight(1f, fill = false).clearAndSetSemantics { }.wholeOrNothing(), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
            badge?.let { CountBadge(it) }
        }
    }
}

/** A count in the route colour, like the convoy's size on its button. */
@Composable
fun CountBadge(count: Int) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(22.dp).background(colors.secondary, CircleShape), contentAlignment = Alignment.Center) {
        Text("$count", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = colors.onSecondary)
    }
}

/** A round map control, like mute and north in the mockups. */
@Composable
fun MapButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier.size(52.dp),
        shape = CircleShape,
        color = colors.surface,
        contentColor = colors.onSurface,
        border = BorderStroke(1.dp, colors.outline),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * Something ahead on the road, as a solid warning-coloured chip with dark text: "Speed camera
 * 400 m". Amber means warning, and always comes with an icon (design/README.md, rule 2).
 */
@Composable
fun WarningChip(
    icon: ImageVector,
    label: String,
    distance: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.tertiary,
    onColor: Color = MaterialTheme.colorScheme.onTertiary,
    detail: String? = null,
    accent: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), color = color, contentColor = onColor) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Column {
                Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
                detail?.let { Text(it, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Text(distance, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            accent?.let { JapaneseAccent(it, color = onColor.copy(alpha = 0.8f)) }
            trailing()
        }
    }
}

/** "FREE DRIVE", "CONVOY": the HUD's small capitals, letter-spaced, with an optional Japanese accent. */
@Composable
fun HudLabel(text: String, color: Color, modifier: Modifier = Modifier, accent: String? = null, fontSize: Int = 12) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = fontSize.sp, letterSpacing = (fontSize * 0.16).sp, color = color)
        accent?.let { JapaneseAccent(it, fontSize = fontSize.sp) }
    }
}

private val PANEL_RADIUS = 24.dp
