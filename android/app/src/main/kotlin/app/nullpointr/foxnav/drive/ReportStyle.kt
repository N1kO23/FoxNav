package app.nullpointr.foxnav.drive

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import app.nullpointr.foxnav.shared.reports.ReportType
import app.nullpointr.foxnav.ui.icons.FoxIcons

/** Names for report types, as buttons show them and the voice says them. */
object ReportLabels {
    fun label(type: String): String = when (type) {
        ReportType.POLICE -> "Police"
        ReportType.SPEED_CHECK -> "Speed check"
        ReportType.ACCIDENT -> "Accident"
        ReportType.HAZARD -> "Hazard"
        ReportType.STOPPED_VEHICLE -> "Stopped vehicle"
        ReportType.ROADWORKS -> "Roadworks"
        ReportType.SLIPPERY_ROAD -> "Slippery road"
        ReportType.ANIMAL -> "Animal on the road"
        ReportType.ROAD_CLOSED -> "Road closed"
        else -> "Report"
    }
}

fun reportIcon(type: String): ImageVector = when (type) {
    ReportType.POLICE -> FoxIcons.ReportPolice
    ReportType.SPEED_CHECK -> FoxIcons.ReportSpeedCheck
    ReportType.ACCIDENT -> FoxIcons.ReportAccident
    ReportType.STOPPED_VEHICLE -> FoxIcons.ReportStoppedVehicle
    ReportType.ROADWORKS -> FoxIcons.ReportRoadworks
    ReportType.SLIPPERY_ROAD -> FoxIcons.ReportSlipperyRoad
    ReportType.ANIMAL -> FoxIcons.ReportAnimal
    ReportType.ROAD_CLOSED -> FoxIcons.ReportRoadClosed
    else -> FoxIcons.ReportHazard
}

/** Warnings are amber; a closed road is red. */
@Composable
fun reportColor(type: String): Color =
    if (type == ReportType.ROAD_CLOSED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary

/** The order of the report buttons: the quick, common ones first. */
val ReportButtonOrder = listOf(
    ReportType.POLICE, ReportType.ACCIDENT, ReportType.HAZARD,
    ReportType.SPEED_CHECK, ReportType.STOPPED_VEHICLE, ReportType.ANIMAL,
    ReportType.ROADWORKS, ReportType.SLIPPERY_ROAD, ReportType.ROAD_CLOSED,
)
