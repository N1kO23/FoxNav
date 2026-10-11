package app.nullpointr.foxnav.drive

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Position
import uniffi.ferrostar.Route

/** Draws the previewed routes: the selected one in the route colour, alternatives muted. */
@Composable
@MaplibreComposable
fun RoutePreviewLayers(preview: RoutePreview?) {
    val selected = preview?.selectedRoute ?: return
    val alternatives = preview.routes.filterIndexed { index, _ -> index != preview.selected }

    val alternativesSource = rememberGeoJsonSource(GeoJsonData.Features(FeatureCollection(alternatives.map { it.toFeature() })))
    val selectedSource = rememberGeoJsonSource(GeoJsonData.Features(selected.toFeature()))
    val colors = MaterialTheme.colorScheme

    LineLayer(
        id = "foxnav-preview-alternatives",
        source = alternativesSource,
        color = const(colors.onSurfaceVariant),
        opacity = const(0.7f),
        width = const(6.dp),
        cap = const(LineCap.Round),
        join = const(LineJoin.Round),
    )
    RouteLine("foxnav-preview", selectedSource)
}

private fun Route.toFeature() = Feature(
    geometry = LineString(geometry.map { Position(longitude = it.lng, latitude = it.lat) }),
    properties = JsonObject(emptyMap()),
)
