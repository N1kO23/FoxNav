package app.nullpointr.foxnav.drive

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.nullpointr.foxnav.shared.reports.Report
import app.nullpointr.foxnav.shared.reports.ReportType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/** Reports on the map, from zoom 10 up, each with its type's marker. */
@Composable
@MaplibreComposable
fun ReportLayers(reports: List<Report>) {
    val colors = MaterialTheme.colorScheme
    val features = remember(reports) {
        FeatureCollection(
            reports.map {
                Feature(
                    geometry = Point(Position(longitude = it.lon, latitude = it.lat)),
                    properties = JsonObject(mapOf("type" to JsonPrimitive(it.type))),
                )
            },
        )
    }
    val size = DpSize(22.dp, 22.dp)
    val markers = ReportType.ALL.associateWith { type -> image(rememberVectorPainter(reportIcon(type)), size = size, drawAsSdf = true) }
    val type = feature["type"].asString()
    SymbolLayer(
        id = "foxnav-reports",
        source = rememberGeoJsonSource(GeoJsonData.Features(features)),
        minZoom = 10f,
        iconImage = switch(type, *markers.map { (name, marker) -> case(name, marker) }.toTypedArray(), fallback = markers.getValue(ReportType.HAZARD)),
        iconColor = switch(type, case(ReportType.ROAD_CLOSED, const(colors.error)), fallback = const(colors.tertiary)),
        iconHaloColor = const(colors.background),
        iconHaloWidth = const(2.dp),
    )
}
