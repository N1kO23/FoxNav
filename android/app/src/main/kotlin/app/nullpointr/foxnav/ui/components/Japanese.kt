package app.nullpointr.foxnav.ui.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import uniffi.ferrostar.ManeuverModifier
import uniffi.ferrostar.ManeuverType

/** Whether the user wants the Japanese accents (Settings, Look). */
val LocalJapaneseAccents = compositionLocalOf { true }

/**
 * A Japanese accent beside an English label, like 到着 beside the arrival time: flavour, never the
 * only label (design/README.md, rule 1). Screen readers skip it; the English says it all.
 *
 * Set in Japanese, so the phone's fallback font draws Japanese forms of the kanji, not Chinese.
 * Every string is in design/README.md's list for a native speaker to check.
 */
@Composable
fun JapaneseAccent(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    letterSpacing: TextUnit = 0.08.em,
) {
    if (!LocalJapaneseAccents.current) return
    Text(
        text,
        modifier.clearAndSetSemantics { }.wholeOrNothing(),
        style = TextStyle(localeList = JAPANESE, fontSize = fontSize, fontWeight = FontWeight.Normal, letterSpacing = letterSpacing, color = if (color == Color.Unspecified) LocalContentColor.current else color),
        maxLines = 1,
    )
}

/**
 * Measured at its natural width, and shown only if that fits: where room runs out (a narrow
 * phone, large text), an accent steps aside rather than squeezing or clipping the English, and a
 * button's label leaves its icon to speak for it.
 */
internal fun Modifier.wholeOrNothing() = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
    if (placeable.width > constraints.maxWidth) {
        layout(0, 0) {}
    } else {
        val lines: Map<AlignmentLine, Int> = listOf<AlignmentLine>(FirstBaseline, LastBaseline).associateWith { placeable[it] }.filterValues { it != AlignmentLine.Unspecified }
        layout(placeable.width, placeable.height, lines) { placeable.place(0, 0) }
    }
}

/** The car-navi way of saying a manoeuvre's direction, like 右方向 ("to the right"); null where there's none. */
fun maneuverAccent(type: ManeuverType?, modifier: ManeuverModifier?): String? = when (type) {
    ManeuverType.ARRIVE -> "目的地"
    ManeuverType.ROUNDABOUT, ManeuverType.ROTARY, ManeuverType.ROUNDABOUT_TURN -> "環状交差点"
    ManeuverType.MERGE -> "合流"
    null, ManeuverType.DEPART, ManeuverType.NOTIFICATION -> null
    else -> when (modifier) {
        ManeuverModifier.RIGHT, ManeuverModifier.SHARP_RIGHT -> "右方向"
        ManeuverModifier.LEFT, ManeuverModifier.SHARP_LEFT -> "左方向"
        ManeuverModifier.SLIGHT_RIGHT -> "斜め右方向"
        ManeuverModifier.SLIGHT_LEFT -> "斜め左方向"
        ManeuverModifier.STRAIGHT -> "直進"
        ManeuverModifier.U_TURN -> "Uターン"
        null -> null
    }
}

private val JAPANESE = LocaleList("ja")
