package app.nullpointr.foxnav.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Thin stroke icons in the style of the design mockups (24×24, tinted by `Icon`). */
object FoxIcons {
    val Search = strokeIcon("Search", "M10.5 4a6.5 6.5 0 1 1 0 13a6.5 6.5 0 1 1 0-13 M15.5 15.5L20 20")
    val Close = strokeIcon("Close", "M6 6L18 18 M18 6L6 18")
    val Locate = strokeIcon("Locate", "M12 3v3 M12 18v3 M3 12h3 M18 12h3 M12 8a4 4 0 1 1 0 8a4 4 0 1 1 0-8")
    val Route = strokeIcon("Route", "M5 19C9 19 8 12 12 12S15 5 19 5")
    val Settings = strokeIcon("Settings", "M4 7h9 M17 7h3 M4 17h3 M11 17h9 M15 5v4 M9 15v4")
    val Pin = strokeIcon("Pin", "M12 21s-7-6.5-7-11a7 7 0 0 1 14 0c0 4.5-7 11-7 11Z M12 7.5a2.5 2.5 0 1 1 0 5a2.5 2.5 0 1 1 0-5")

    /** Cars one behind the other. */
    val Convoy = strokeIcon("Convoy", "M6 11l6-6 6 6 M6 19l6-6 6 6")
    val Share = strokeIcon("Share", "M12 3v12 M7 8l5-5 5 5 M5 13v6a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-6")
    val Copy = strokeIcon("Copy", "M9 9h10v10H9z M5 15V5h10")
    val Flag = strokeIcon("Flag", "M6 21V4 M6 4h11l-2.5 4 2.5 4H6")
    val Scan = strokeIcon("Scan", "M4 9V5a1 1 0 0 1 1-1h4 M15 4h4a1 1 0 0 1 1 1v4 M20 15v4a1 1 0 0 1-1 1h-4 M9 20H5a1 1 0 0 1-1-1v-4 M8 12h8")
    val ChevronUp = strokeIcon("ChevronUp", "M6 15l6-6 6 6")
    val ChevronDown = strokeIcon("ChevronDown", "M6 9l6 6 6-6")

    val Camera = strokeIcon("Camera", "M4 8h3l2-3h6l2 3h3a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1Z M12 10a3.5 3.5 0 1 1 0 7a3.5 3.5 0 1 1 0-7")

    /** A speed camera on the map (filled, the lens cut out). */
    val CameraMarker = filledIcon("CameraMarker", "M9 4h6l2 3h3a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2h3Z M12 9.5a4 4 0 1 0 0 8a4 4 0 1 0 0-8Z", evenOdd = true)

    /** A convoy car on the map, pointing the way it drives (filled, for tinting as a map icon). */
    val CarMarker = filledIcon("CarMarker", "M12 2.5L19.5 20.5L12 16.5L4.5 20.5Z")

    /** The regroup point on the map (filled). */
    val FlagMarker = filledIcon("FlagMarker", "M5 22V3h2v1h12l-3 5 3 5H7v8Z")

    private fun strokeIcon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData = addPathNodes(pathData),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()

    private fun filledIcon(name: String, pathData: String, evenOdd: Boolean = false): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData = addPathNodes(pathData),
                fill = SolidColor(Color.Black),
                pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
            )
            .build()
}
