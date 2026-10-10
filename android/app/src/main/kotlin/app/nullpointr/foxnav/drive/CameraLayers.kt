package app.nullpointr.foxnav.drive

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.nullpointr.foxnav.shared.safety.Camera
import app.nullpointr.foxnav.ui.icons.FoxIcons
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/** Speed cameras on the map, from zoom 11 up; they give way to labels where it's crowded. */
@Composable
@MaplibreComposable
fun CameraLayers(cameras: List<Camera>) {
    val colors = MaterialTheme.colorScheme
    val features = remember(cameras) {
        FeatureCollection(cameras.map { Feature(geometry = Point(Position(longitude = it.lon, latitude = it.lat)), properties = JsonObject(emptyMap())) })
    }
    SymbolLayer(
        id = "foxnav-cameras",
        source = rememberGeoJsonSource(GeoJsonData.Features(features)),
        minZoom = 11f,
        iconImage = image(rememberVectorPainter(FoxIcons.CameraMarker), size = DpSize(20.dp, 20.dp), drawAsSdf = true),
        iconColor = const(colors.tertiary),
        iconHaloColor = const(colors.background),
        iconHaloWidth = const(2.dp),
    )
}
