package app.nullpointr.foxnav.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import app.nullpointr.foxnav.settings.SpeedometerStyle
import app.nullpointr.foxnav.shared.format.UnitSystem
import app.nullpointr.foxnav.shared.format.formatDistance
import java.text.DecimalFormatSymbols

/** The user's units, for everything that shows a speed or distance. */
val LocalUnits = compositionLocalOf { UnitSystem.METRIC }

val LocalSpeedometerStyle = compositionLocalOf { SpeedometerStyle.MODERN }

/** A distance as the user reads it: their units, their locale's decimal separator. */
@Composable
fun distanceText(meters: Double): String = formatDistance(meters, DecimalFormatSymbols.getInstance().decimalSeparator, LocalUnits.current)
