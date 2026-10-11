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

    val Back = strokeIcon("Back", "M15 5l-7 7 7 7")
    val Plus = strokeIcon("Plus", "M12 5v14 M5 12h14")
    val Minus = strokeIcon("Minus", "M5 12h14")

    // From the mockups (design canvas): map controls, panels and the convoy sheet.
    val SpeakerOn = strokeIcon("SpeakerOn", "M4 9h4l5-4v14l-5-4H4Z M16.5 9a4 4 0 0 1 0 6 M19 6.5a7.5 7.5 0 0 1 0 11")
    val SpeakerOff = strokeIcon("SpeakerOff", "M4 9h4l5-4v14l-5-4H4Z M16.5 9.5l5 5 M21.5 9.5l-5 5")
    val People = strokeIcon("People", "M9 5a3 3 0 1 1 0 6a3 3 0 1 1 0-6 M3 20c0-3.5 2.7-6 6-6s6 2.5 6 6 M16 5.5a3 3 0 0 1 0 5.5 M18 14.5c2 0.8 3 2.8 3 5.5")
    val RouteOverview = strokeIcon("RouteOverview", "M5 19C9 19 8 12 12 12S15 5 19 5 M5 18a1 1 0 1 1 0 2a1 1 0 1 1 0-2 M19 4a1 1 0 1 1 0 2a1 1 0 1 1 0-2")
    val Qr = strokeIcon("Qr", "M4 4h6v6H4Z M14 4h6v6h-6Z M4 14h6v6H4Z M14 14h2v2h-2Z M18 18h2v2h-2Z M14 18h2 M18 14h2")
    val More = filledIcon("More", "M5 10.4a1.6 1.6 0 1 1 0 3.2a1.6 1.6 0 1 1 0-3.2Z M12 10.4a1.6 1.6 0 1 1 0 3.2a1.6 1.6 0 1 1 0-3.2Z M19 10.4a1.6 1.6 0 1 1 0 3.2a1.6 1.6 0 1 1 0-3.2Z")
    val Lock = strokeIcon("Lock", "M6 11h12v10H6Z M8.5 11V8a3.5 3.5 0 0 1 7 0v3")
    val Music = strokeIcon("Music", "M9 18V5l11-2v13 M9 18a3 3 0 1 1-6 0a3 3 0 1 1 6 0 M20 16a3 3 0 1 1-6 0a3 3 0 1 1 6 0")

    // Saved places.
    val Home = strokeIcon("Home", "M4 11l8-7 8 7 M6 9.5V20h4.5v-5.5h3V20H18V9.5")
    val Work = strokeIcon("Work", "M4 8h16v11H4Z M9 8V5.5h6V8 M4 13h16")
    val Star = strokeIcon("Star", "M12 3.5l2.6 5.4 5.9.8-4.3 4.1 1 5.9L12 16.9l-5.2 2.8 1-5.9-4.3-4.1 5.9-.8Z")
    val Recent = strokeIcon("Recent", "M12 4a8 8 0 1 1 0 16a8 8 0 1 1 0-16 M12 8v4.5l3 2")

    // Quick messages (the convoy mockup's icons, plus slow down and all good).
    val Fuel = strokeIcon("Fuel", "M5 21V5a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v16 M3 21h14 M5 10h10 M15 8l3 3v7a1.5 1.5 0 0 0 3 0V9l-2.5-2.5")
    val Coffee = strokeIcon("Coffee", "M4 9h12v5a5 5 0 0 1-5 5h-2a5 5 0 0 1-5-5Z M16 10.5h1.5a2.5 2.5 0 0 1 0 5H16 M8 3v3 M12 3v3")
    val PullOver = strokeIcon("PullOver", "M18 3v18 M7 21c0-6 2-9 6-10 M10.5 8L13 11L9.8 13")
    val Police = strokeIcon("Police", "M7 18v-6a5 5 0 0 1 10 0v6 M5 18h14v3H5Z M12 2v2 M4.5 5.5l1.4 1.4 M19.5 5.5l-1.4 1.4")
    val SlowDown = strokeIcon("SlowDown", "M6 5l6 6 6-6 M6 12l6 6 6-6")
    val AllGood = strokeIcon("AllGood", "M5 12.5l4.5 4.5L19 7")

    // Sound: playing, recording and files (voice packs, the over-limit sound).
    val Play = strokeIcon("Play", "M8 5.5v13l10.5-6.5Z")
    val Stop = strokeIcon("Stop", "M7 7h10v10H7Z")
    val Mic = strokeIcon("Mic", "M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3 M5.5 11a6.5 6.5 0 0 0 13 0 M12 17.5V21")
    val Folder = strokeIcon("Folder", "M3.5 7a1.5 1.5 0 0 1 1.5-1.5h4.5l2 2H19a1.5 1.5 0 0 1 1.5 1.5v8.5A1.5 1.5 0 0 1 19 19H5a1.5 1.5 0 0 1-1.5-1.5Z")

    // Cars from above, nose up, by body style: windows cut out, so the shape reads at 28 dp in any colour.
    val CarSedan = carIcon(
        "CarSedan",
        "M9 2.5h6c1.4 0 2.5 1.1 2.5 2.5v14c0 1.4-1.1 2.5-2.5 2.5H9c-1.4 0-2.5-1.1-2.5-2.5V5c0-1.4 1.1-2.5 2.5-2.5Z",
        "M8.3 8.5h7.4l-1 2.6H9.3Z M9.3 16h5.4l1 2.2H8.3Z",
    )
    val CarCoupe = carIcon(
        "CarCoupe",
        "M9 2.5h6c1.4 0 2.5 1.1 2.5 2.5v14c0 1.4-1.1 2.5-2.5 2.5H9c-1.4 0-2.5-1.1-2.5-2.5V5c0-1.4 1.1-2.5 2.5-2.5Z",
        "M8.3 9.5h7.4l-1 2.8H9.3Z M9.3 14.6h5.4l1 2.6H8.3Z",
    )
    val CarHatchback = carIcon(
        "CarHatchback",
        "M9 3.5h6c1.4 0 2.5 1.1 2.5 2.5v12c0 1.4-1.1 2.5-2.5 2.5H9c-1.4 0-2.5-1.1-2.5-2.5V6c0-1.4 1.1-2.5 2.5-2.5Z",
        "M8.3 8.5h7.4l-1 2.6H9.3Z M9 17h6l.6 1.8H8.4Z",
    )
    val CarWagon = carIcon(
        "CarWagon",
        "M9 2.5h6c1.4 0 2.5 1.1 2.5 2.5v14c0 1.4-1.1 2.5-2.5 2.5H9c-1.4 0-2.5-1.1-2.5-2.5V5c0-1.4 1.1-2.5 2.5-2.5Z",
        "M8.3 8.5h7.4l-1 2.6H9.3Z M9 18.6h6l.4 1.5H8.6Z",
    )
    val CarConvertible = carIcon(
        "CarConvertible",
        "M9 2.5h6c1.4 0 2.5 1.1 2.5 2.5v14c0 1.4-1.1 2.5-2.5 2.5H9c-1.4 0-2.5-1.1-2.5-2.5V5c0-1.4 1.1-2.5 2.5-2.5Z",
        "M8.3 8.5h7.4l-.6 1.6H8.9Z M8.8 11h6.4v5.6H8.8Z",
    )
    val CarKei = carIcon(
        "CarKei",
        "M8.6 4h6.8c.9 0 1.6.7 1.6 1.6v13.8c0 .9-.7 1.6-1.6 1.6H8.6c-.9 0-1.6-.7-1.6-1.6V5.6C7 4.7 7.7 4 8.6 4Z",
        "M8.2 6.5h7.6l-.8 2.4H9Z M8.8 18h6.4v1.4H8.8Z",
    )
    val CarSuv = carIcon(
        "CarSuv",
        "M8.5 2.5h7c1.7 0 3 1.3 3 3v13c0 1.7-1.3 3-3 3h-7c-1.7 0-3-1.3-3-3v-13c0-1.7 1.3-3 3-3Z",
        "M7.4 8h9.2l-1.2 2.8H8.6Z M8.4 18h7.2v1.6H8.4Z",
    )
    val CarVan = carIcon(
        "CarVan",
        "M8.5 2h7c1.4 0 2.5 1.1 2.5 2.5v15c0 1.4-1.1 2.5-2.5 2.5h-7C7.1 22 6 20.9 6 19.5v-15C6 3.1 7.1 2 8.5 2Z",
        "M7.2 5.5h9.6l-1.2 2.6H8.4Z",
    )
    val CarPickup = carIcon(
        "CarPickup",
        "M8.5 2.5h7c1.4 0 2.5 1.1 2.5 2.5v14c0 1.4-1.1 2.5-2.5 2.5h-7C7.1 21.5 6 20.4 6 19V5c0-1.4 1.1-2.5 2.5-2.5Z",
        "M7.3 7.5h9.4l-1.1 2.6H8.4Z M8.4 11.6h7.2v.9H8.4Z M8 13.8h8v6H8Z",
    )

    val Snowflake = strokeIcon("Snowflake", "M12 3v18 M4.2 7.5l15.6 9 M4.2 16.5l15.6-9 M9.5 4.5L12 6.5l2.5-2 M9.5 19.5L12 17.5l2.5 2")

    /** Report something: a warning triangle. */
    val Report = strokeIcon("Report", "M12 4L21 19.5H3Z M12 10v4 M12 16.8v.2")

    // Report markers: a disc with the type's glyph cut out, so they work as SDF map icons and as
    // tinted icons. Shapes inside a cut-out are solid again (even-odd).
    val ReportPolice = reportMarker("ReportPolice", "M8 15v-4a4 4 0 0 1 8 0v4Z M6.5 16h11v2.5h-11Z")
    val ReportSpeedCheck = reportMarker("ReportSpeedCheck", "M5.5 8.5h10v7h-10Z M10.5 10a2 2 0 1 1 0 4a2 2 0 1 1 0-4Z M15.5 10.5l3-1.5v6l-3-1.5Z")
    val ReportAccident = reportMarker("ReportAccident", "M12 4.5l1.9 4.4 4.6-1.4-2.6 4 3.6 3.1-4.7.4-.3 4.7-2.5-4-2.5 4-.3-4.7-4.7-.4 3.6-3.1-2.6-4 4.6 1.4Z")
    val ReportHazard = reportMarker("ReportHazard", "M10.75 5.5h2.5v8.5h-2.5Z M10.75 16h2.5v2.5h-2.5Z")
    val ReportStoppedVehicle = reportMarker("ReportStoppedVehicle", "M6.5 11.5l1.6-4.5h7.8l1.6 4.5h.5v5.5h-1.8v1.8h-2.3v-1.8h-3.8v1.8H7.8v-1.8H6v-5.5Z M8.6 11.2l.9-2.6h5l.9 2.6Z")
    val ReportRoadworks = reportMarker("ReportRoadworks", "M10.6 5h2.8l3.4 12.5H7.2Z M9.6 11h4.8l.5 2h-5.8Z M5.5 17.5h13v2h-13Z")
    val ReportSlipperyRoad = reportMarker("ReportSlipperyRoad", "M12 4.5c2.6 3.6 5 6.6 5 9.5a5 5 0 0 1-10 0c0-2.9 2.4-5.9 5-9.5Z")
    val ReportAnimal = reportMarker(
        "ReportAnimal",
        "M12 12.5c2.6 0 4.8 2.4 4.8 4.3 0 1.6-1.6 2.2-4.8 2.2s-4.8-.6-4.8-2.2c0-1.9 2.2-4.3 4.8-4.3Z " +
            "M7.6 9.1a1.5 1.5 0 1 1 0 3a1.5 1.5 0 1 1 0-3Z M10.3 6.1a1.5 1.5 0 1 1 0 3a1.5 1.5 0 1 1 0-3Z " +
            "M13.7 6.1a1.5 1.5 0 1 1 0 3a1.5 1.5 0 1 1 0-3Z M16.4 9.1a1.5 1.5 0 1 1 0 3a1.5 1.5 0 1 1 0-3Z",
    )
    val ReportRoadClosed = reportMarker("ReportRoadClosed", "M6 10.5h12v3H6Z")

    private fun carIcon(name: String, body: String, windows: String) = filledIcon(name, "$body $windows", evenOdd = true)

    private fun reportMarker(name: String, glyph: String) = filledIcon(name, "M12 1a11 11 0 1 1 0 22a11 11 0 1 1 0-22Z $glyph", evenOdd = true)

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
